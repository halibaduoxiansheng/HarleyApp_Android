package com.example.harleyapp.browser

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.example.harleyapp.R
import com.example.harleyapp.data.WebsiteRepository
import com.example.harleyapp.model.BrowserBookmarkNodeSnapshot
import com.example.harleyapp.model.EDGE_BROWSER_PACKAGE_NAME
import com.example.harleyapp.model.GOOGLE_APP_PACKAGE_NAME
import com.example.harleyapp.model.MAX_BROWSER_BOOKMARK_NODE_COUNT
import com.example.harleyapp.model.MAX_BROWSER_BOOKMARK_NODE_DEPTH
import com.example.harleyapp.model.WebsiteBookmarkSaveResult
import com.example.harleyapp.model.resolveBrowserBookmarkPage
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 在浏览器前台显示可拖动悬浮球，并在用户单击时把当前网页写入HarleyApp网站收藏。
 *
 * 使用方法：
 * 用户必须先在HarleyApp设置卡片同意用途说明，再到Android无障碍设置中手动启用本服务。服务
 * 只根据前台包名决定是否显示悬浮球，不会后台记录浏览历史；只有用户单击悬浮球时才查找
 * 浏览器工具栏分享入口。浏览器未公开分享入口时，才读取正文外的地址栏作为兜底；密码节点、
 * 网页正文和非HTTP/HTTPS页面始终不会被读取或保存。
 *
 * @return 本类由Android系统创建和绑定，业务代码不应直接实例化。
 */
class BrowserBookmarkAccessibilityService : AccessibilityService() {

    private lateinit var preferences: BrowserBookmarkPreferences
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var browserPackages: Set<String> = emptySet()
    private var currentForegroundPackage: String = ""
    private var ballView: TextView? = null
    private var ballLayoutParams: WindowManager.LayoutParams? = null
    private var ballIsCollapsed: Boolean = false
    private var ballCollapsedOnLeft: Boolean = false
    private var shareFlowGeneration: Long = 0L
    private var shareFlowInProgress: Boolean = false
    private val autoCollapseRunnable = Runnable {
        collapseBallToEdge()
    }

    /**
     * 初始化浏览器列表、悬浮窗口依赖和当前显示状态。
     *
     * @return 无返回值；系统完成无障碍绑定后自动调用。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = WeakReference(this)
        preferences = BrowserBookmarkPreferences(applicationContext)
        windowManager = getSystemService(WindowManager::class.java)
        browserPackages = queryBrowserPackages()
        refreshVisibility()
    }

    /**
     * 只处理前台窗口变化，用包名显示或隐藏悬浮球，不在事件回调中读取网页内容。
     *
     * @param event Android发送的窗口状态事件；其他事件类型会被忽略。
     * @return 无返回值。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null ||
            event.eventType !in SUPPORTED_WINDOW_EVENT_TYPES
        ) {
            return
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) {
            return
        }
        currentForegroundPackage = packageName
        updateBallVisibility(packageName)
    }

    /**
     * Android临时中断无障碍反馈时隐藏悬浮球，避免残留在非浏览器页面。
     *
     * @return 无返回值。
     */
    override fun onInterrupt() {
        hideBall()
    }

    /**
     * 系统解绑服务时移除悬浮窗口并释放当前实例引用。
     *
     * @param intent 系统解绑Intent。
     * @return 继承系统默认解绑结果。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        removeBall()
        clearConnectedService(this)
        return super.onUnbind(intent)
    }

    /**
     * 服务销毁时再次兜底移除悬浮球和延迟反馈任务。
     *
     * @return 无返回值。
     */
    override fun onDestroy() {
        shareFlowGeneration += 1L
        shareFlowInProgress = false
        mainHandler.removeCallbacksAndMessages(null)
        removeBall()
        clearConnectedService(this)
        super.onDestroy()
    }

    /**
     * 根据总开关和当前活动包重新计算悬浮球显示状态。
     *
     * 使用方法：
     * 设置页改变总开关后通过[refreshRunningService]触发；服务初次连接时也会调用。函数只读取
     * 当前窗口包名，不读取标题、网址或网页正文。
     *
     * @return 无返回值。
     */
    private fun refreshVisibility() {
        if (!::preferences.isInitialized || !::windowManager.isInitialized) {
            return
        }
        if (currentForegroundPackage.isBlank()) {
            currentForegroundPackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        }
        updateBallVisibility(currentForegroundPackage)
    }

    /**
     * 在已识别浏览器前台显示悬浮球，在其他应用或总开关关闭时隐藏。
     *
     * @param packageName 当前活动窗口包名。
     * @return 无返回值。
     */
    private fun updateBallVisibility(packageName: String) {
        if (!preferences.isEnabled() || packageName !in browserPackages) {
            hideBall()
            return
        }
        showBall()
    }

    /**
     * 创建或恢复浏览器收藏悬浮球。
     *
     * @return 无返回值；窗口系统拒绝添加时只记录英文错误日志，不影响浏览器继续使用。
     */
    private fun showBall() {
        ballView?.let { existingView ->
            val wasHidden = existingView.visibility != View.VISIBLE
            existingView.visibility = View.VISIBLE
            if (wasHidden) {
                expandBallFromEdge()
                scheduleAutoCollapse()
            }
            return
        }

        val sizePixels = dpToPixels(BALL_SIZE_DP)
        val position = preferences.getBallPosition()
        val maximumX = (resources.displayMetrics.widthPixels - sizePixels).coerceAtLeast(0)
        val maximumY = (resources.displayMetrics.heightPixels - sizePixels).coerceAtLeast(0)
        val layoutParams = WindowManager.LayoutParams(
            sizePixels,
            sizePixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (maximumX * position.xFraction).roundToInt()
            y = (maximumY * position.yFraction).roundToInt()
        }
        val view = createBallView(layoutParams)

        runCatching {
            windowManager.addView(view, layoutParams)
        }.onSuccess {
            ballView = view
            ballLayoutParams = layoutParams
            view.post {
                scheduleAutoCollapse()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to add browser bookmark overlay", error)
        }
    }

    /**
     * 创建带点击、拖动、贴边和无障碍说明的圆形收藏按钮。
     *
     * @param layoutParams 当前悬浮窗口位置参数，拖动时会直接更新。
     * @return 可交给WindowManager显示的TextView按钮。
     */
    private fun createBallView(layoutParams: WindowManager.LayoutParams): TextView {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var expandOnlyTouch = false

        return TextView(this).apply {
            text = BALL_DEFAULT_TEXT
            contentDescription = "收藏当前网页到HarleyApp"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = BALL_TEXT_SIZE_SP
            elevation = dpToPixels(BALL_ELEVATION_DP).toFloat()
            background = createBallBackground(BALL_DEFAULT_COLOR)
            setOnClickListener {
                saveCurrentBrowserPage()
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelAutoCollapse()
                        expandOnlyTouch = ballIsCollapsed
                        if (expandOnlyTouch) {
                            expandBallFromEdge()
                        }
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = layoutParams.x
                        startY = layoutParams.y
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - downRawX
                        val deltaY = event.rawY - downRawY
                        if (!dragging &&
                            (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)
                        ) {
                            dragging = true
                        }
                        if (dragging) {
                            updateBallPosition(
                                layoutParams = layoutParams,
                                requestedX = startX + deltaX.roundToInt(),
                                requestedY = startY + deltaY.roundToInt()
                            )
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            snapAndPersistBallPosition(layoutParams)
                        } else if (expandOnlyTouch) {
                            scheduleAutoCollapse()
                        } else {
                            view.performClick()
                            scheduleAutoCollapse()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            snapAndPersistBallPosition(layoutParams)
                        }
                        scheduleAutoCollapse()
                        true
                    }

                    else -> false
                }
            }
        }
    }

    /**
     * 把拖动坐标限制在当前屏幕范围内，并立即刷新悬浮窗口位置。
     *
     * @param layoutParams 当前悬浮窗口参数。
     * @param requestedX 手指移动后请求的左上角横坐标。
     * @param requestedY 手指移动后请求的左上角纵坐标。
     * @return 无返回值。
     */
    private fun updateBallPosition(
        layoutParams: WindowManager.LayoutParams,
        requestedX: Int,
        requestedY: Int
    ) {
        val view = ballView ?: return
        val maximumX = (resources.displayMetrics.widthPixels - view.width)
            .coerceAtLeast(0)
        val maximumY = (resources.displayMetrics.heightPixels - view.height)
            .coerceAtLeast(0)
        layoutParams.x = requestedX.coerceIn(0, maximumX)
        layoutParams.y = requestedY.coerceIn(0, maximumY)
        runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }.onFailure { error ->
            Log.e(TAG, "Failed to move browser bookmark overlay", error)
        }
    }

    /**
     * 把悬浮球吸附到最近屏幕侧边，并按相对坐标保存最终位置。
     *
     * @param layoutParams 当前悬浮窗口参数。
     * @return 无返回值；位置保存失败只记录英文日志，下次仍使用默认安全位置。
     */
    private fun snapAndPersistBallPosition(layoutParams: WindowManager.LayoutParams) {
        val view = ballView ?: return
        val maximumX = (resources.displayMetrics.widthPixels - view.width)
            .coerceAtLeast(0)
        val maximumY = (resources.displayMetrics.heightPixels - view.height)
            .coerceAtLeast(0)
        layoutParams.x = if (layoutParams.x <= maximumX / 2) 0 else maximumX
        layoutParams.y = layoutParams.y.coerceIn(0, maximumY)
        runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }.onFailure { error ->
            Log.e(TAG, "Failed to snap browser bookmark overlay", error)
        }

        val xFraction = if (maximumX == 0) 0f else layoutParams.x.toFloat() / maximumX
        val yFraction = if (maximumY == 0) 0f else layoutParams.y.toFloat() / maximumY
        if (!preferences.saveBallPosition(xFraction, yFraction)) {
            Log.e(TAG, "Failed to persist browser bookmark overlay position")
        }
        ballCollapsedOnLeft = layoutParams.x == 0
        ballIsCollapsed = false
        scheduleAutoCollapse()
    }

    /**
     * 在悬浮球完整显示且用户停止操作后，重新安排一次三秒闲置缩边任务。
     *
     * 使用方法：
     * 悬浮球首次出现、从边缘展开、拖动结束或收藏操作完成后调用。重复调用会覆盖旧任务，确保
     * 三秒从最后一次交互重新计算；分享流程进行中或悬浮球不可见时不会安排。
     *
     * @return 无返回值。
     */
    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        val view = ballView ?: return
        if (view.visibility != View.VISIBLE ||
            ballIsCollapsed ||
            shareFlowInProgress ||
            currentForegroundPackage !in browserPackages
        ) {
            return
        }
        mainHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY_MILLIS)
    }

    /**
     * 取消尚未执行的闲置缩边任务。
     *
     * @return 无返回值；当前没有任务时安全忽略。
     */
    private fun cancelAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
    }

    /**
     * 把完整悬浮球向最近屏幕侧边移出约三分之二，仅保留可触摸的边缘部分。
     *
     * @return 无返回值；窗口已隐藏、收藏流程进行中或尺寸尚未就绪时不改变位置。
     */
    private fun collapseBallToEdge() {
        val view = ballView ?: return
        val layoutParams = ballLayoutParams ?: return
        if (view.visibility != View.VISIBLE ||
            shareFlowInProgress ||
            ballIsCollapsed ||
            view.width <= 0
        ) {
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val maximumExpandedX = (screenWidth - view.width).coerceAtLeast(0)
        val visibleWidth = (view.width * COLLAPSED_VISIBLE_FRACTION)
            .roundToInt()
            .coerceAtLeast(dpToPixels(MIN_COLLAPSED_VISIBLE_WIDTH_DP))
            .coerceAtMost(view.width)
        ballCollapsedOnLeft = layoutParams.x <= maximumExpandedX / 2
        layoutParams.x = if (ballCollapsedOnLeft) {
            -(view.width - visibleWidth)
        } else {
            screenWidth - visibleWidth
        }

        runCatching {
            windowManager.updateViewLayout(view, layoutParams)
        }.onSuccess {
            ballIsCollapsed = true
            view.alpha = COLLAPSED_ALPHA
            view.contentDescription = "收藏悬浮球已收起，点击展开"
        }.onFailure { error ->
            Log.e(TAG, "Failed to collapse browser bookmark overlay", error)
        }
    }

    /**
     * 把缩在屏幕边缘的悬浮球恢复为完整圆形；本次触摸只负责展开，不触发收藏。
     *
     * @return 无返回值；悬浮球未收起或窗口尺寸尚未就绪时只恢复外观状态。
     */
    private fun expandBallFromEdge() {
        val view = ballView ?: return
        val layoutParams = ballLayoutParams ?: return
        if (ballIsCollapsed) {
            val maximumExpandedX = (
                resources.displayMetrics.widthPixels - view.width
                ).coerceAtLeast(0)
            layoutParams.x = if (ballCollapsedOnLeft) 0 else maximumExpandedX
            runCatching {
                windowManager.updateViewLayout(view, layoutParams)
            }.onFailure { error ->
                Log.e(TAG, "Failed to expand browser bookmark overlay", error)
            }
        }

        ballIsCollapsed = false
        view.alpha = 1f
        view.contentDescription = "收藏当前网页到HarleyApp"
    }

    /**
     * 在用户点击时读取当前浏览器窗口、解析页面并保存到现有网站收藏。
     *
     * @return 无返回值；保存结果通过悬浮球颜色和中文Toast立即反馈。
     */
    private fun saveCurrentBrowserPage() {
        if (shareFlowInProgress) {
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            showSaveFeedback(false, "暂时无法连接当前浏览器页面，请稍后重试")
            return
        }
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName !in browserPackages) {
            hideBall()
            showSaveFeedback(false, "当前页面不是可支持的浏览器网页")
            return
        }

        if (startBrowserShareFlow(root, packageName)) {
            return
        }
        if (packageName == EDGE_BROWSER_PACKAGE_NAME) {
            showSaveFeedback(false, "请用Edge的共享功能选择“收藏到HarleyApp”")
            return
        }

        saveFromAccessibilityTree(root, packageName)
    }

    /**
     * 在浏览器公开完整地址栏时直接解析并保存，作为未知浏览器和Google内置网页的保守兜底。
     *
     * @param root 当前活动浏览器窗口根节点。
     * @param packageName 当前浏览器包名。
     * @return 无返回值；保存结果通过悬浮球颜色和中文Toast立即反馈。
     */
    private fun saveFromAccessibilityTree(
        root: AccessibilityNodeInfo,
        packageName: String
    ) {

        val nodes = collectNodeSnapshots(root)
        val page = resolveBrowserBookmarkPage(packageName, nodes)
        if (page == null) {
            showSaveFeedback(false, "没有读取到完整网址，请用浏览器共享并选择“收藏到HarleyApp”")
            return
        }

        val result = WebsiteRepository(applicationContext).saveBookmarkedPage(
            pageTitle = page.title,
            pageUrl = page.url
        )
        when (result) {
            WebsiteBookmarkSaveResult.SAVED ->
                showSaveFeedback(true, "已收藏到HarleyApp网站收藏")
            WebsiteBookmarkSaveResult.ALREADY_SAVED ->
                showSaveFeedback(true, "当前网站已经收藏")
            WebsiteBookmarkSaveResult.INVALID_URL ->
                showSaveFeedback(false, "当前页面不是可收藏的网页地址")
            WebsiteBookmarkSaveResult.SAVE_FAILED ->
                showSaveFeedback(false, "收藏保存失败，请稍后重试")
        }
    }

    /**
     * 启动由用户单击触发的浏览器原生分享流程，以取得未被地址栏折叠的完整网页地址。
     *
     * 使用方法：
     * 函数先寻找工具栏上明确的“分享”控件；没有时只点击语义明确的菜单或溢出按钮，再由
     * [findAndClickBrowserShareAction]寻找“共享/分享”菜单项。所有匹配都跳过WebView正文，
     * 不会点击网页里的同名按钮。
     *
     * @param root 当前活动浏览器窗口根节点。
     * @param browserPackage 当前浏览器包名，用于确保延迟步骤没有误入其他应用。
     * @return 成功点击分享或菜单入口时返回true；浏览器未公开安全入口时返回false。
     */
    private fun startBrowserShareFlow(
        root: AccessibilityNodeInfo,
        browserPackage: String
    ): Boolean {
        val directShareNode = findNodeOutsideWebView(root, ::isShareActionNode)
        if (directShareNode != null && clickNodeOrAncestor(directShareNode)) {
            val generation = beginShareFlow()
            findAndClickHarleyShareTarget(generation, attempt = 0)
            return true
        }

        val menuNode = findNodeOutsideWebView(root, ::isBrowserMenuNode)
            ?: return false
        if (!clickNodeOrAncestor(menuNode)) {
            return false
        }

        val generation = beginShareFlow()
        findAndClickBrowserShareAction(
            generation = generation,
            browserPackage = browserPackage,
            attempt = 0
        )
        return true
    }

    /**
     * 标记一次新的自动分享流程，并把悬浮球切换为处理中状态。
     *
     * @return 本次流程的递增代号；后续延迟任务必须核对代号，避免旧任务操作新窗口。
     */
    private fun beginShareFlow(): Long {
        cancelAutoCollapse()
        shareFlowGeneration += 1L
        shareFlowInProgress = true
        ballView?.let { view ->
            mainHandler.removeCallbacksAndMessages(FEEDBACK_CALLBACK_TOKEN)
            view.text = BALL_PROGRESS_TEXT
            view.background = createBallBackground(BALL_DEFAULT_COLOR)
        }
        return shareFlowGeneration
    }

    /**
     * 等待浏览器菜单展开，并点击WebView正文外语义明确的“共享/分享”菜单项。
     *
     * @param generation 本次分享流程代号。
     * @param browserPackage 发起流程的浏览器包名。
     * @param attempt 当前轮询次数，从0开始，达到上限后停止自动操作并提示用户。
     * @return 无返回值；通过主线程短延迟重试处理菜单动画，不阻塞界面。
     */
    private fun findAndClickBrowserShareAction(
        generation: Long,
        browserPackage: String,
        attempt: Int
    ) {
        mainHandler.postDelayed(
            {
                if (!isCurrentShareFlow(generation)) {
                    return@postDelayed
                }
                val root = rootInActiveWindow
                val packageName = root?.packageName?.toString().orEmpty()
                val shareNode = root?.let { activeRoot ->
                    findNodeOutsideWebView(activeRoot, ::isShareActionNode)
                }
                if (packageName == browserPackage &&
                    shareNode != null &&
                    clickNodeOrAncestor(shareNode)
                ) {
                    findAndClickHarleyShareTarget(generation, attempt = 0)
                    return@postDelayed
                }

                if (attempt + 1 < BROWSER_SHARE_ACTION_MAX_ATTEMPTS) {
                    findAndClickBrowserShareAction(
                        generation = generation,
                        browserPackage = browserPackage,
                        attempt = attempt + 1
                    )
                } else {
                    failShareFlow("未找到浏览器共享入口，请手动共享到HarleyApp")
                }
            },
            SHARE_FLOW_RETRY_DELAY_MILLIS
        )
    }

    /**
     * 等待系统分享面板出现，并点击本App唯一的“收藏到HarleyApp”接收目标。
     *
     * @param generation 本次分享流程代号。
     * @param attempt 当前轮询次数，从0开始，达到上限后保留分享面板供用户手动选择。
     * @return 无返回值；点击成功后由[BrowserBookmarkShareActivity]负责校验、保存和反馈。
     */
    private fun findAndClickHarleyShareTarget(generation: Long, attempt: Int) {
        mainHandler.postDelayed(
            {
                if (!isCurrentShareFlow(generation)) {
                    return@postDelayed
                }
                val expectedLabel = getString(R.string.browser_bookmark_share_target_name)
                val targetNode = rootInActiveWindow?.let { root ->
                    findNodeOutsideWebView(root) { node ->
                        sequenceOf(node.text, node.contentDescription)
                            .map { value -> value?.toString().orEmpty() }
                            .any { value -> value.contains(expectedLabel, ignoreCase = true) }
                    }
                }
                if (targetNode != null && clickNodeOrAncestor(targetNode)) {
                    completeShareFlow()
                    return@postDelayed
                }

                if (attempt + 1 < SHARE_TARGET_MAX_ATTEMPTS) {
                    findAndClickHarleyShareTarget(
                        generation = generation,
                        attempt = attempt + 1
                    )
                } else {
                    failShareFlow("分享面板已打开，请手动选择“收藏到HarleyApp”")
                }
            },
            SHARE_FLOW_RETRY_DELAY_MILLIS
        )
    }

    /**
     * 在有限深度和数量内查找浏览器工具栏或系统面板节点，并完全跳过网页正文WebView。
     *
     * @param root 当前窗口根节点。
     * @param predicate 对正文外节点执行的匹配条件。
     * @return 第一个匹配节点；达到安全上限或没有匹配时返回null。
     */
    private fun findNodeOutsideWebView(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var visitedCount = 0

        /**
         * 深度优先查找单个节点。
         *
         * @param node 当前待检查节点。
         * @param depth 当前节点深度。
         * @return 找到时返回匹配节点，否则返回null。
         */
        fun find(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > MAX_BROWSER_BOOKMARK_NODE_DEPTH ||
                visitedCount >= MAX_BROWSER_BOOKMARK_NODE_COUNT
            ) {
                return null
            }
            visitedCount += 1
            if (node.className?.toString() == ANDROID_WEB_VIEW_CLASS_NAME) {
                return null
            }
            if (!node.isPassword && predicate(node)) {
                return node
            }

            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                val match = find(child, depth + 1)
                if (match != null) {
                    return match
                }
            }
            return null
        }

        return runCatching {
            find(root, depth = 0)
        }.onFailure { error ->
            Log.e(TAG, "Failed to find browser share control", error)
        }.getOrNull()
    }

    /**
     * 判断节点是否是浏览器工具栏或弹出菜单中明确的分享操作。
     *
     * @param node WebView正文外的候选节点。
     * @return 资源名或可见语义明确表示共享时返回true。
     */
    private fun isShareActionNode(node: AccessibilityNodeInfo): Boolean {
        val shortResourceId = node.viewIdResourceName
            ?.substringAfterLast('/')
            .orEmpty()
            .lowercase(Locale.ROOT)
        val labels = sequenceOf(node.text, node.contentDescription)
            .map { value -> value?.toString().orEmpty().trim().lowercase(Locale.ROOT) }

        return shortResourceId == "share" ||
            shortResourceId.endsWith("_share") ||
            shortResourceId.contains("share_button") ||
            labels.any(SHARE_ACTION_LABELS::contains)
    }

    /**
     * 判断节点是否是可安全展开浏览器功能菜单的工具栏按钮。
     *
     * @param node WebView正文外的候选节点。
     * @return 已知Edge入口、常见溢出资源名或明确菜单辅助文字匹配时返回true。
     */
    private fun isBrowserMenuNode(node: AccessibilityNodeInfo): Boolean {
        val resourceId = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
        val shortResourceId = resourceId.substringAfterLast('/')
        val labels = sequenceOf(node.text, node.contentDescription)
            .map { value -> value?.toString().orEmpty().trim().lowercase(Locale.ROOT) }

        return resourceId in KNOWN_BROWSER_MENU_RESOURCE_IDS ||
            shortResourceId.contains("overflow") ||
            shortResourceId in GENERIC_BROWSER_MENU_RESOURCE_IDS ||
            labels.any(BROWSER_MENU_ACTION_LABELS::contains)
    }

    /**
     * 从候选节点向上寻找可点击父节点并执行一次标准无障碍点击。
     *
     * @param node 文本、图标或容器候选节点。
     * @return 本节点或有限层父节点成功接受ACTION_CLICK时返回true，否则返回false。
     */
    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var currentNode: AccessibilityNodeInfo? = node
        repeat(MAX_CLICKABLE_ANCESTOR_DEPTH) {
            val candidate = currentNode ?: return false
            if (candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            currentNode = candidate.parent
        }
        return false
    }

    /**
     * 判断延迟任务是否仍属于当前尚未结束的分享流程。
     *
     * @param generation 延迟任务创建时保存的流程代号。
     * @return 流程仍有效时返回true。
     */
    private fun isCurrentShareFlow(generation: Long): Boolean {
        return shareFlowInProgress && generation == shareFlowGeneration
    }

    /**
     * 在系统分享目标点击成功后结束自动操作状态，等待接收Activity反馈保存结果。
     *
     * @return 无返回值。
     */
    private fun completeShareFlow() {
        shareFlowInProgress = false
        ballView?.let { view ->
            view.text = BALL_DEFAULT_TEXT
            view.background = createBallBackground(BALL_DEFAULT_COLOR)
        }
        scheduleAutoCollapse()
    }

    /**
     * 停止当前自动分享流程，并给出不丢失精确网址的手动分享兜底说明。
     *
     * @param message 面向用户的中文操作提示。
     * @return 无返回值。
     */
    private fun failShareFlow(message: String) {
        shareFlowGeneration += 1L
        shareFlowInProgress = false
        showSaveFeedback(successful = false, message = message)
    }

    /**
     * 把当前无障碍树转换为有限、不可回查浏览器窗口的纯数据快照。
     *
     * @param root 当前活动浏览器窗口根节点。
     * @return 最多[MAX_BROWSER_BOOKMARK_NODE_COUNT]项的正文外节点列表；密码和WebView正文文字
     * 始终留空。
     */
    private fun collectNodeSnapshots(
        root: AccessibilityNodeInfo
    ): List<BrowserBookmarkNodeSnapshot> {
        val snapshots = ArrayList<BrowserBookmarkNodeSnapshot>()

        /**
         * 深度优先复制当前节点的必要字段。
         *
         * @param node 当前无障碍节点。
         * @param depth 当前节点深度。
         * @param parentInsideWebView 父节点是否已经处于网页正文中。
         */
        fun collect(
            node: AccessibilityNodeInfo,
            depth: Int,
            parentInsideWebView: Boolean
        ) {
            if (depth > MAX_BROWSER_BOOKMARK_NODE_DEPTH ||
                snapshots.size >= MAX_BROWSER_BOOKMARK_NODE_COUNT
            ) {
                return
            }

            val className = node.className?.toString().orEmpty()
            val insideWebView = parentInsideWebView || className == ANDROID_WEB_VIEW_CLASS_NAME
            snapshots += BrowserBookmarkNodeSnapshot(
                resourceId = node.viewIdResourceName.orEmpty(),
                className = className,
                text = if (node.isPassword || insideWebView) {
                    ""
                } else {
                    node.text?.toString().orEmpty()
                },
                contentDescription = if (node.isPassword || insideWebView) {
                    ""
                } else {
                    node.contentDescription?.toString().orEmpty()
                },
                insideWebView = insideWebView
            )

            // WebView以下全部属于网页正文；地址栏和浏览器菜单位于其兄弟节点，无需继续读取。
            if (insideWebView) {
                return
            }

            for (childIndex in 0 until node.childCount) {
                if (snapshots.size >= MAX_BROWSER_BOOKMARK_NODE_COUNT) {
                    break
                }
                val child = node.getChild(childIndex) ?: continue
                collect(child, depth + 1, insideWebView)
            }
        }

        runCatching {
            collect(root, depth = 0, parentInsideWebView = false)
        }.onFailure { error ->
            Log.e(TAG, "Failed to read browser accessibility tree", error)
        }
        return snapshots
    }

    /**
     * 显示收藏结果，并短暂改变悬浮球文字和颜色后恢复默认外观。
     *
     * @param successful true表示收藏成功或已经收藏，false表示需要用户处理错误。
     * @param message 面向用户的中文结果说明。
     * @return 无返回值。
     */
    private fun showSaveFeedback(successful: Boolean, message: String) {
        val view = ballView
        if (view != null) {
            view.text = if (successful) BALL_SUCCESS_TEXT else BALL_FAILURE_TEXT
            view.background = createBallBackground(
                if (successful) BALL_SUCCESS_COLOR else BALL_FAILURE_COLOR
            )
            mainHandler.removeCallbacksAndMessages(FEEDBACK_CALLBACK_TOKEN)
            mainHandler.postAtTime(
                {
                    view.text = BALL_DEFAULT_TEXT
                    view.background = createBallBackground(BALL_DEFAULT_COLOR)
                },
                FEEDBACK_CALLBACK_TOKEN,
                android.os.SystemClock.uptimeMillis() + FEEDBACK_DURATION_MILLIS
            )
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        scheduleAutoCollapse()
    }

    /**
     * 创建悬浮球圆形半透明背景。
     *
     * @param color ARGB背景颜色。
     * @return 带边框的圆形GradientDrawable。
     */
    private fun createBallBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dpToPixels(BALL_BORDER_WIDTH_DP), Color.argb(150, 255, 255, 255))
        }
    }

    /**
     * 隐藏已经创建的悬浮球但保留窗口实例，便于切回浏览器时快速恢复。
     *
     * @return 无返回值。
     */
    private fun hideBall() {
        cancelAutoCollapse()
        ballView?.visibility = View.GONE
    }

    /**
     * 从WindowManager彻底移除悬浮球，并清空本地引用。
     *
     * @return 无返回值。
     */
    private fun removeBall() {
        cancelAutoCollapse()
        val view = ballView ?: return
        runCatching {
            windowManager.removeView(view)
        }.onFailure { error ->
            Log.e(TAG, "Failed to remove browser bookmark overlay", error)
        }
        ballView = null
        ballLayoutParams = null
        ballIsCollapsed = false
    }

    /**
     * 查询当前安装环境中能够处理HTTPS链接的浏览器包，并额外加入Google App内置网页。
     *
     * @return 浏览器包名集合；查询失败时仍至少包含Edge与Google App两个明确目标。
     */
    private fun queryBrowserPackages(): Set<String> {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_DISCOVERY_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val resolvedPackages = runCatching {
            packageManager.queryIntentActivities(browserIntent, 0)
                .map { resolveInfo -> resolveInfo.activityInfo.packageName }
        }.onFailure { error ->
            Log.e(TAG, "Failed to query installed browsers", error)
        }.getOrDefault(emptyList())

        return buildSet {
            addAll(resolvedPackages)
            add(EDGE_BROWSER_PACKAGE_NAME)
            add(GOOGLE_APP_PACKAGE_NAME)
        }
    }

    /**
     * 把密度无关像素转换为当前屏幕实际像素。
     *
     * @param valueDp dp数值。
     * @return 四舍五入后的正整数像素。
     */
    private fun dpToPixels(valueDp: Int): Int {
        return (valueDp * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    companion object {
        @Volatile
        private var connectedService: WeakReference<BrowserBookmarkAccessibilityService>? = null

        /**
         * 通知当前已绑定服务重新读取用户总开关并刷新悬浮球。
         *
         * 使用方法：
         * [BrowserBookmarkController.setEnabled]成功保存后调用。服务未绑定时安全忽略，Android后续
         * 绑定服务时会主动读取最新设置。
         *
         * @return 无返回值。
         */
        fun refreshRunningService() {
            connectedService?.get()?.refreshVisibility()
        }

        /**
         * 仅在销毁实例仍是当前记录对象时清空弱引用，避免旧实例覆盖新绑定服务。
         *
         * @param service 正在解绑或销毁的服务实例。
         * @return 无返回值。
         */
        private fun clearConnectedService(service: BrowserBookmarkAccessibilityService) {
            if (connectedService?.get() === service) {
                connectedService = null
            }
        }

        const val TAG = "BrowserBookmarkService"
        const val BROWSER_DISCOVERY_URL = "https://example.com"
        const val ANDROID_WEB_VIEW_CLASS_NAME = "android.webkit.WebView"
        const val BALL_DEFAULT_TEXT = "收"
        const val BALL_PROGRESS_TEXT = "…"
        const val BALL_SUCCESS_TEXT = "✓"
        const val BALL_FAILURE_TEXT = "!"
        const val BALL_SIZE_DP = 54
        const val BALL_TEXT_SIZE_SP = 18f
        const val BALL_ELEVATION_DP = 8
        const val BALL_BORDER_WIDTH_DP = 1
        const val FEEDBACK_DURATION_MILLIS = 900L
        const val AUTO_COLLAPSE_DELAY_MILLIS = 3_000L
        const val COLLAPSED_VISIBLE_FRACTION = 0.34f
        const val COLLAPSED_ALPHA = 0.72f
        const val MIN_COLLAPSED_VISIBLE_WIDTH_DP = 18
        const val SHARE_FLOW_RETRY_DELAY_MILLIS = 180L
        const val BROWSER_SHARE_ACTION_MAX_ATTEMPTS = 8
        const val SHARE_TARGET_MAX_ATTEMPTS = 14
        const val MAX_CLICKABLE_ANCESTOR_DEPTH = 6
        const val BALL_DEFAULT_COLOR = 0xDD4056C7.toInt()
        const val BALL_SUCCESS_COLOR = 0xDD008C72.toInt()
        const val BALL_FAILURE_COLOR = 0xDDC63C4A.toInt()
        val FEEDBACK_CALLBACK_TOKEN = Any()
        val SUPPORTED_WINDOW_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        val SHARE_ACTION_LABELS = setOf("共享", "分享", "share")
        val BROWSER_MENU_ACTION_LABELS = setOf(
            "菜单",
            "更多",
            "更多选项",
            "menu",
            "more",
            "more options"
        )
        val KNOWN_BROWSER_MENU_RESOURCE_IDS = setOf(
            "com.microsoft.emmx:id/edge_overflow_button",
            "com.microsoft.emmx:id/overflow_button_bottom"
        )
        val GENERIC_BROWSER_MENU_RESOURCE_IDS = setOf(
            "menu_button",
            "more_button",
            "toolbar_menu"
        )
    }
}
