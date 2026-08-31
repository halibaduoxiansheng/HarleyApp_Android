package com.example.harleyapp.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import com.example.harleyapp.data.WebsiteToolRepository
import com.example.harleyapp.model.EbookWebDownloadRequest
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WebsiteToolSettings
import com.example.harleyapp.web.WebsiteScriptController
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 在应用内部安全加载用户当前选择的网站，并提供网页工具、增强视频全屏、刷新和外部浏览器入口。
 *
 * 使用方法：
 * 由HarleyApp在“网站”导航项选中时调用。网页工具设置按网站保存在本机；离开页面时会主动
 * 退出自定义全屏并销毁WebView，防止Chromium渲染对象长期占用内存。
 *
 * @param website 当前需要加载的网站；用户删除全部网站时传null。
 * @param onManageWebsites 无网站时返回首页添加网站的回调。
 * @param onFullscreenChanged 自定义视频全屏状态回调，用于让宿主隐藏底部导航。
 * @param onEbookDownloadRequested 网页触发下载时，把公开直链交给电子书仓库的回调。
 * @param modifier 外部传入的页面安全边距。
 *
 * @return 无返回值，直接输出网站浏览页面。
 */
@Composable
fun WebsiteScreen(
    website: WebsiteShortcut?,
    onManageWebsites: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onEbookDownloadRequested: (EbookWebDownloadRequest) -> Unit,
    modifier: Modifier = Modifier
) {
    if (website == null) {
        EmptyWebsiteScreen(
            modifier = modifier,
            onManageWebsites = onManageWebsites
        )
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val websiteToolRepository = remember(context) {
        WebsiteToolRepository(context.applicationContext)
    }
    val scriptController = remember {
        WebsiteScriptController()
    }
    var toolSettings by remember(website.id) {
        mutableStateOf(websiteToolRepository.getSettings(website.id))
    }
    var showToolbox by remember(website.id) {
        mutableStateOf(false)
    }
    var toolMessage by remember(website.id) {
        mutableStateOf<String?>(null)
    }
    var pageGeneration by remember(website.id) {
        mutableIntStateOf(0)
    }
    var findQuery by remember(website.id) {
        mutableStateOf("")
    }
    var findResultText by remember(website.id) {
        mutableStateOf("")
    }
    var fullscreenContent by remember(website.id) {
        mutableStateOf<FullscreenWebContent?>(null)
    }
    var fullscreenControlsVisible by remember(website.id) {
        mutableStateOf(false)
    }
    var fullscreenControlsLocked by remember(website.id) {
        mutableStateOf(false)
    }
    var fullscreenControlRevision by remember(website.id) {
        mutableIntStateOf(0)
    }
    var showFullscreenRatePicker by remember(website.id) {
        mutableStateOf(false)
    }
    var fullscreenBallOffsetY by remember(website.id) {
        mutableFloatStateOf(0f)
    }
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnEbookDownloadRequested by rememberUpdatedState(onEbookDownloadRequested)
    var loadingProgress by remember {
        mutableIntStateOf(0)
    }
    var errorText by remember {
        mutableStateOf<String?>(null)
    }
    var canGoBack by remember {
        mutableStateOf(false)
    }

    /** 显示全屏控制层并重新开始自动隐藏倒计时。 */
    fun revealFullscreenControls() {
        fullscreenControlsVisible = true
        fullscreenControlRevision++
    }

    /** 关闭当前全屏View并只回调网站一次。 */
    fun exitFullscreen() {
        val content = fullscreenContent ?: return
        fullscreenContent = null
        fullscreenControlsLocked = false
        fullscreenControlsVisible = false
        showFullscreenRatePicker = false
        fullscreenBallOffsetY = 0f
        content.close()
    }

    val webView = remember(context, website.id, website.url) {
        createWebsiteWebView(
            context = context,
            initialUrl = website.url,
            initialSettings = toolSettings,
            onProgressChanged = { progress ->
                loadingProgress = progress
            },
            onNavigationStateChanged = { canNavigateBack ->
                canGoBack = canNavigateBack
            },
            onError = { message ->
                errorText = message
            },
            onPageFinished = {
                pageGeneration++
            },
            onShowFullscreen = { view, callback ->
                fullscreenContent?.close()
                fullscreenContent = FullscreenWebContent(view, callback)
                fullscreenControlsLocked = false
                fullscreenControlsVisible = false
                showFullscreenRatePicker = false
                fullscreenBallOffsetY = 0f
                fullscreenControlRevision++
            },
            onHideFullscreen = {
                exitFullscreen()
            },
            onFindResult = { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                if (isDoneCounting) {
                    findResultText = if (numberOfMatches <= 0) {
                        "当前网页没有匹配内容"
                    } else {
                        "第${activeMatchOrdinal + 1}项，共${numberOfMatches}项"
                    }
                }
            },
            onDownloadRequested = { url, contentDisposition, mimeType ->
                currentOnEbookDownloadRequested(
                    EbookWebDownloadRequest(
                        url = url,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType
                    )
                )
            }
        )
    }

    /**
     * 保存并立即应用当前网站的工具设置。
     *
     * @param updatedSettings 用户刚修改的完整设置。
     * @return 写入Room及兼容副本成功返回true，失败时保留旧设置并显示提示。
     */
    fun updateToolSettings(updatedSettings: WebsiteToolSettings): Boolean {
        val normalizedSettings = updatedSettings.normalized()
        val desktopModeChanged =
            normalizedSettings.desktopModeEnabled != toolSettings.desktopModeEnabled
        if (!websiteToolRepository.saveSettings(website.id, normalizedSettings)) {
            toolMessage = "网页工具设置保存失败，请重试"
            return false
        }

        toolSettings = normalizedSettings
        if (desktopModeChanged) {
            applyDesktopUserAgent(
                context = context,
                webView = webView,
                enabled = normalizedSettings.desktopModeEnabled
            )
            webView.reload()
            toolMessage = "已切换网页模式并重新加载"
        }
        return true
    }

    // 全屏优先于网页历史；用户按一次返回即可恢复工具栏和系统栏。
    BackHandler(enabled = fullscreenContent != null || canGoBack) {
        if (fullscreenContent != null && fullscreenControlsLocked) {
            fullscreenControlsLocked = false
            toolMessage = "屏幕锁已解除"
            revealFullscreenControls()
        } else if (fullscreenContent != null) {
            exitFullscreen()
        } else {
            webView.goBack()
        }
    }

    // 通知外层Scaffold隐藏底部导航；页面销毁时无条件恢复，避免其他一级页面丢失导航栏。
    LaunchedEffect(fullscreenContent != null) {
        currentOnFullscreenChanged(fullscreenContent != null)
    }

    // 悬浮球展开的工具面板停止操作后自动收起；悬浮球本身始终保留且不覆盖底部播放器进度条。
    LaunchedEffect(
        fullscreenContent,
        fullscreenControlsVisible,
        fullscreenControlRevision,
        showFullscreenRatePicker
    ) {
        if (fullscreenContent != null &&
            fullscreenControlsVisible &&
            !showFullscreenRatePicker
        ) {
            delay(FULLSCREEN_CONTROLS_HIDE_DELAY_MILLIS)
            fullscreenControlsVisible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            currentOnFullscreenChanged(false)
        }
    }

    // 页面加载完成或设置变化后重应用脚本；同时更新不依赖JavaScript的原生文字缩放和常亮状态。
    LaunchedEffect(webView, pageGeneration, toolSettings) {
        webView.settings.textZoom = toolSettings.textZoomPercent
        webView.keepScreenOn = toolSettings.keepScreenOn
        scriptController.applySettings(webView, toolSettings)
    }

    // 操作结果只短暂显示，避免占用网页可视区域。
    LaunchedEffect(toolMessage) {
        if (toolMessage != null) {
            delay(TOOL_MESSAGE_DURATION_MILLIS)
            toolMessage = null
        }
    }

    // 网站进入自定义全屏后隐藏系统栏并允许设备自由旋转；退出时恢复Activity原状态。
    DisposableEffect(fullscreenContent, activity) {
        val content = fullscreenContent
        if (content == null || activity == null) {
            onDispose { }
        } else {
            val previousOrientation = activity.requestedOrientation
            val insetsController = WindowCompat.getInsetsController(
                activity.window,
                activity.window.decorView
            )
            content.view.keepScreenOn = true
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

            onDispose {
                content.view.keepScreenOn = false
                content.close()
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    // WebView包含独立渲染进程资源，页面离开时必须显式停止并销毁。
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.keepScreenOn = false
            webView.clearMatches()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    fullscreenContent?.let { content ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    (content.view.parent as? ViewGroup)?.removeView(content.view)
                    content.view
                }
            )

            // 全屏夜间模式使用原生半透明层，保证Chromium自定义视频View也能降低亮度。
            if (toolSettings.nightModeEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = toolSettings.nightOverlayAlpha))
                )
            }

            toolMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    color = Color.Black.copy(alpha = 0.46f),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 只有锁屏和倍速选择器这种明确的模态状态才拦截整屏；普通收起状态完全让出播放器触摸。
            if (fullscreenControlsLocked || showFullscreenRatePicker) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            fullscreenControlsLocked,
                            showFullscreenRatePicker
                        ) {
                            detectTapGestures {
                                if (showFullscreenRatePicker) {
                                    showFullscreenRatePicker = false
                                }
                                revealFullscreenControls()
                            }
                        }
                )
            }

            val maximumBallOffsetPixels = with(LocalDensity.current) {
                FULLSCREEN_FLOATING_BALL_MAX_VERTICAL_OFFSET.toPx()
            }
            val ballDragState = rememberDraggableState { delta ->
                fullscreenBallOffsetY = calculateFullscreenBallOffset(
                    currentOffset = fullscreenBallOffsetY,
                    dragDelta = delta,
                    maximumOffset = maximumBallOffsetPixels
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .offset { IntOffset(x = 0, y = fullscreenBallOffsetY.roundToInt()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (fullscreenControlsVisible) {
                    Surface(
                        modifier = Modifier
                            .weight(weight = 1f, fill = false)
                            .widthIn(max = FULLSCREEN_FLOATING_PANEL_MAX_WIDTH),
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.extraLarge,
                        shadowElevation = 8.dp
                    ) {
                        if (fullscreenControlsLocked) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "屏幕已锁定",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                FullscreenToolButton(
                                    text = "解除锁定",
                                    active = true,
                                    onClick = {
                                        fullscreenControlsLocked = false
                                        toolMessage = "屏幕锁已解除"
                                        revealFullscreenControls()
                                    }
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FullscreenToolButton(
                                    text = "${formatToolbarRate(toolSettings.playbackRate)} ▾",
                                    active = toolSettings.playbackRate != 1f,
                                    onClick = {
                                        showFullscreenRatePicker = true
                                        revealFullscreenControls()
                                    }
                                )
                                FullscreenToolButton(
                                    text = "播放/暂停",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.togglePlayback(webView) { count ->
                                            toolMessage = mediaActionMessage(count, "已切换播放状态")
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = "-10秒",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.seekBy(webView, -10) { count ->
                                            toolMessage = mediaActionMessage(count, "已快退10秒")
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = "+10秒",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.seekBy(webView, 10) { count ->
                                            toolMessage = mediaActionMessage(count, "已快进10秒")
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.videoMuted) "静音开" else "静音",
                                    active = toolSettings.videoMuted,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(videoMuted = !toolSettings.videoMuted)
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.videoLoopEnabled) "循环开" else "循环",
                                    active = toolSettings.videoLoopEnabled,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(
                                                videoLoopEnabled = !toolSettings.videoLoopEnabled
                                            )
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.nightModeEnabled) "夜间开" else "夜间",
                                    active = toolSettings.nightModeEnabled,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(
                                                nightModeEnabled = !toolSettings.nightModeEnabled
                                            )
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = "锁屏",
                                    onClick = {
                                        fullscreenControlsLocked = true
                                        showFullscreenRatePicker = false
                                        toolMessage = "屏幕已锁定，点击悬浮球可管理"
                                        revealFullscreenControls()
                                    }
                                )
                                FullscreenToolButton(
                                    text = "退出全屏",
                                    onClick = ::exitFullscreen
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(FULLSCREEN_FLOATING_BALL_SIZE)
                        .draggable(
                            state = ballDragState,
                            orientation = Orientation.Vertical
                        )
                        .clickable {
                            fullscreenControlsVisible = !fullscreenControlsVisible
                            showFullscreenRatePicker = false
                            fullscreenControlRevision++
                        },
                    color = if (fullscreenControlsVisible) {
                        FULLSCREEN_ACTIVE_COLOR.copy(alpha = 0.92f)
                    } else {
                        Color.Black.copy(alpha = 0.68f)
                    },
                    shape = CircleShape,
                    shadowElevation = 10.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (fullscreenControlsVisible) "收起" else "脚本",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showFullscreenRatePicker && !fullscreenControlsLocked) {
                FullscreenPlaybackRatePicker(
                    modifier = Modifier.align(Alignment.Center),
                    selectedRate = toolSettings.playbackRate,
                    onRateSelected = { selectedRate ->
                        updateToolSettings(toolSettings.copy(playbackRate = selectedRate))
                        showFullscreenRatePicker = false
                        toolMessage = "播放速度已切换为 ${formatToolbarRate(selectedRate)}"
                        revealFullscreenControls()
                    },
                    onDismiss = {
                        showFullscreenRatePicker = false
                        revealFullscreenControls()
                    }
                )
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    enabled = canGoBack,
                    onClick = {
                        webView.goBack()
                    }
                ) {
                    Text(text = "← 返回")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = website.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = website.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = {
                        errorText = null
                        webView.reload()
                    }
                ) {
                    Text(text = "刷新")
                }

                TextButton(
                    onClick = {
                        openExternalUrl(context, website.url)
                    }
                ) {
                    Text(text = "浏览器")
                }
            }
        }

        if (loadingProgress in 0..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showToolbox = true }) {
                    Text(text = "网页工具")
                }
                TextButton(onClick = { showToolbox = true }) {
                    Text(text = formatToolbarRate(toolSettings.playbackRate))
                }
                TextButton(
                    onClick = {
                        scriptController.togglePlayback(webView) { count ->
                            toolMessage = mediaActionMessage(count, "已切换播放状态")
                        }
                    }
                ) {
                    Text(text = "播放/暂停")
                }
                TextButton(
                    onClick = {
                        scriptController.seekBy(webView, -10) { count ->
                            toolMessage = mediaActionMessage(count, "已快退10秒")
                        }
                    }
                ) {
                    Text(text = "-10秒")
                }
                TextButton(
                    onClick = {
                        scriptController.seekBy(webView, 10) { count ->
                            toolMessage = mediaActionMessage(count, "已快进10秒")
                        }
                    }
                ) {
                    Text(text = "+10秒")
                }
                TextButton(
                    onClick = {
                        scriptController.requestFullscreen(webView) { count ->
                            toolMessage = mediaActionMessage(count, "已向播放器请求全屏")
                        }
                    }
                ) {
                    Text(text = "全屏")
                }
            }
        }

        toolMessage?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    webView
                }
            )

            errorText?.let { message ->
                WebsiteErrorView(
                    message = message,
                    onRetry = {
                        errorText = null
                        webView.loadUrl(website.url)
                    },
                    onOpenBrowser = {
                        openExternalUrl(context, website.url)
                    }
                )
            }

            if (loadingProgress == 0 && errorText == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    if (showToolbox) {
        WebsiteToolboxDialog(
            settings = toolSettings,
            findQuery = findQuery,
            findResultText = findResultText,
            onSettingsChanged = { updatedSettings ->
                updateToolSettings(updatedSettings)
            },
            onFindQueryChanged = { query ->
                findQuery = query
                if (query.isBlank()) {
                    findResultText = ""
                    webView.clearMatches()
                } else {
                    findResultText = "正在查找…"
                    webView.findAllAsync(query)
                }
            },
            onFindPrevious = {
                webView.findNext(false)
            },
            onFindNext = {
                webView.findNext(true)
            },
            onTogglePlayback = {
                scriptController.togglePlayback(webView) { count ->
                    toolMessage = mediaActionMessage(count, "已切换播放状态")
                }
            },
            onSeekBy = { seconds ->
                scriptController.seekBy(webView, seconds) { count ->
                    toolMessage = mediaActionMessage(
                        count,
                        if (seconds < 0) "已快退${-seconds}秒" else "已快进${seconds}秒"
                    )
                }
            },
            onRequestFullscreen = {
                showToolbox = false
                scriptController.requestFullscreen(webView) { count ->
                    toolMessage = mediaActionMessage(count, "已向播放器请求全屏")
                }
            },
            onDismiss = {
                showToolbox = false
            }
        )
    }
}

/**
 * 用户删除全部网站后显示空状态，并引导回首页添加。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param onManageWebsites 返回首页网站管理区域的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyWebsiteScreen(
    modifier: Modifier,
    onManageWebsites: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "还没有可访问的网站",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                text = "返回首页添加网站并选择卡片颜色。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onManageWebsites) {
                Text(text = "返回首页添加")
            }
        }
    }
}

/**
 * 创建并配置用于HTTP或HTTPS网页浏览的WebView。
 *
 * @param context 页面上下文，用于创建WebView和打开外部协议。
 * @param initialUrl 当前网站的完整HTTPS网址。
 * @param initialSettings 当前网站首次创建WebView时应用的原生设置。
 * @param onProgressChanged 页面加载进度变化回调，范围为0到100。
 * @param onNavigationStateChanged 是否可以网页后退的状态回调。
 * @param onError 主页面加载失败时的中文错误提示回调；传null表示清除旧错误。
 * @param onPageFinished 页面完成加载后的脚本重应用通知。
 * @param onShowFullscreen 网站请求显示自定义全屏View的回调。
 * @param onHideFullscreen 网站请求退出自定义全屏的回调。
 * @param onFindResult 页内查找位置和总数回调。
 * @param onDownloadRequested 网页下载公开直链、文件名响应头和MIME类型回调。
 *
 * @return 已完成安全设置并开始加载网站的WebView实例。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebsiteWebView(
    context: Context,
    initialUrl: String,
    initialSettings: WebsiteToolSettings,
    onProgressChanged: (Int) -> Unit,
    onNavigationStateChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onPageFinished: () -> Unit,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onFindResult: (Int, Int, Boolean) -> Unit,
    onDownloadRequested: (String, String, String) -> Unit
): WebView {
    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
        }
        settings.textZoom = initialSettings.textZoomPercent
        keepScreenOn = initialSettings.keepScreenOn
        applyDesktopUserAgent(
            context = context,
            webView = this,
            enabled = initialSettings.desktopModeEnabled
        )

        webViewClient = HarleyWebViewClient(
            context = context,
            onPageStartedCallback = {
                onError(null)
            },
            onPageFinishedCallback = {
                onNavigationStateChanged(canGoBack())
                onPageFinished()
            },
            onMainFrameErrorCallback = { message ->
                onError(message)
            }
        )
        webChromeClient = HarleyWebChromeClient(
            onProgressChangedCallback = onProgressChanged,
            onShowFullscreenCallback = onShowFullscreen,
            onHideFullscreenCallback = onHideFullscreen
        )
        setFindListener(onFindResult)
        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val safeUrl = url.orEmpty()
            if (safeUrl.isBlank()) {
                Log.e(TAG, "Website reported an empty download URL")
            } else {
                onDownloadRequested(
                    safeUrl,
                    contentDisposition.orEmpty(),
                    mimeType.orEmpty()
                )
            }
        }
        loadUrl(initialUrl)
    }
}

/**
 * 显示网页主文档加载失败后的可恢复提示。
 *
 * @param message 具体错误说明。
 * @param onRetry 重新加载网站回调。
 * @param onOpenBrowser 使用系统浏览器打开回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteErrorView(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "网页暂时无法打开",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                text = message.ifBlank { "请检查网络连接或稍后重试。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) {
                    Text(text = "重新加载")
                }
                TextButton(onClick = onOpenBrowser) {
                    Text(text = "使用浏览器")
                }
            }
        }
    }
}

/**
 * 使用系统默认浏览器或支持该链接的应用打开网址。
 *
 * @param context 用于启动Activity的上下文。
 * @param url 完整HTTPS网址。
 *
 * @return 成功发送启动请求返回true；设备没有可用浏览器时返回false。
 */
private fun openExternalUrl(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.e(TAG, "No application can open the website", error)
        false
    }
}

/**
 * 处理WebView页面导航和主文档错误。
 *
 * @param context 用于打开非HTTP协议的上下文。
 * @param onPageStartedCallback 主页面开始加载回调。
 * @param onPageFinishedCallback 主页面加载完成回调。
 * @param onMainFrameErrorCallback 主页面失败回调。
 */
private class HarleyWebViewClient(
    private val context: Context,
    private val onPageStartedCallback: () -> Unit,
    private val onPageFinishedCallback: () -> Unit,
    private val onMainFrameErrorCallback: (String) -> Unit
) : WebViewClient() {

    /**
     * 主页面开始加载时清除旧错误。
     *
     * @param view 当前WebView。
     * @param url 正在加载的网址。
     * @param favicon 网站图标，可能为null。
     *
     * @return 无返回值。
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStartedCallback()
    }

    /**
     * 页面加载结束后更新网页是否可以后退。
     *
     * @param view 当前WebView。
     * @param url 已完成加载的网址。
     *
     * @return 无返回值。
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback()
    }

    /**
     * 仅处理主文档加载错误，忽略图片等子资源错误，避免整页被错误遮挡。
     *
     * @param view 当前WebView。
     * @param request 失败的资源请求。
     * @param error 系统返回的网页错误。
     *
     * @return 无返回值。
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)

        if (request?.isForMainFrame == true) {
            val description = error?.description?.toString().orEmpty()
            Log.e(TAG, "Main frame failed to load: $description")
            onMainFrameErrorCallback(
                if (description.isBlank()) "请检查网络连接或域名是否可以访问。" else description
            )
        }
    }

    /**
     * HTTP和HTTPS链接继续在内置WebView打开，电话等其他协议交给系统应用处理。
     *
     * @param view 当前WebView。
     * @param request 即将跳转的网页请求。
     *
     * @return 返回false表示WebView自行加载；返回true表示已交给外部应用。
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme == "http" || scheme == "https") {
            return false
        }

        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to open external URL scheme", error)
            true
        }
    }
}

/**
 * 把WebView加载进度转发给Compose状态。
 *
 * @param onProgressChangedCallback 进度变化回调，范围为0到100。
 * @param onShowFullscreenCallback 网站请求显示自定义视频View的回调。
 * @param onHideFullscreenCallback 网站或系统请求退出视频全屏的回调。
 */
private class HarleyWebChromeClient(
    private val onProgressChangedCallback: (Int) -> Unit,
    private val onShowFullscreenCallback: (View, CustomViewCallback) -> Unit,
    private val onHideFullscreenCallback: () -> Unit
) : WebChromeClient() {

    /**
     * 接收Chromium页面加载进度。
     *
     * @param view 当前WebView。
     * @param newProgress 新进度，范围为0到100。
     *
     * @return 无返回值。
     */
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress.coerceIn(0, 100))
    }

    /**
     * 接收网页播放器通过HTML5 Fullscreen API创建的自定义全屏View。
     *
     * @param view Chromium提供的视频View；为空时立即结束本次请求。
     * @param callback 宿主退出全屏后必须调用的完成回调。
     * @return 无返回值。
     */
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            callback?.onCustomViewHidden()
            return
        }
        onShowFullscreenCallback(view, callback)
    }

    /**
     * 接收网站播放器主动退出全屏的请求。
     *
     * @return 无返回值。
     */
    override fun onHideCustomView() {
        onHideFullscreenCallback()
    }
}

/**
 * 保存一次WebView自定义全屏会话并保证完成回调最多执行一次。
 *
 * @param view Chromium交给宿主展示的全屏View。
 * @param callback 全屏View被隐藏后通知Chromium的回调。
 */
private class FullscreenWebContent(
    val view: View,
    private val callback: WebChromeClient.CustomViewCallback
) {
    private var closed = false

    /**
     * 结束全屏会话。
     *
     * @return 无返回值；重复调用会安全忽略。
     */
    fun close() {
        if (closed) return
        closed = true
        callback.onCustomViewHidden()
    }
}

/**
 * 显示全屏视频底部半透明工具条中的一个紧凑操作按钮。
 *
 * @param text 按钮文字或当前状态。
 * @param active 是否使用强调色表示功能已经开启。
 * @param onClick 点击回调。
 * @return 无返回值。
 */
@Composable
private fun FullscreenToolButton(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (active) FULLSCREEN_ACTIVE_COLOR else Color.White,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * 在全屏视频中央显示可直接选择的常用播放速度。
 *
 * 使用方法：
 * 用户点击全屏工具条当前倍速后显示本组件，不需要再以0.05为步长反复点击。点击任一速度
 * 立即保存并应用，点击取消或弹层外部由宿主关闭。
 *
 * @param selectedRate 当前已保存的播放速度。
 * @param onRateSelected 用户选择预设速度后的回调。
 * @param onDismiss 关闭选择器但不改变速度的回调。
 * @param modifier 外部定位修饰器。
 *
 * @return 无返回值，直接输出全屏倍速选择面板。
 */
@Composable
private fun FullscreenPlaybackRatePicker(
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "直接选择播放速度",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            FULLSCREEN_PLAYBACK_RATE_PRESETS.chunked(4).forEach { rateRow ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rateRow.forEach { rate ->
                        val selected = kotlin.math.abs(rate - selectedRate) < 0.001f
                        if (selected) {
                            Button(onClick = { onRateSelected(rate) }) {
                                Text(text = formatToolbarRate(rate))
                            }
                        } else {
                            TextButton(onClick = { onRateSelected(rate) }) {
                                Text(text = formatToolbarRate(rate), color = Color.White)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Color.White)
            }
        }
    }
}

/**
 * 从可能经过主题包装的Compose Context中查找Activity。
 *
 * @return 当前Activity；无法解析时返回null，此时仍可展示网页但不控制系统栏和方向。
 */
private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * 切换WebView桌面版或系统默认手机版User-Agent。
 *
 * @param context 用于读取系统WebView默认User-Agent。
 * @param webView 目标网页视图。
 * @param enabled true使用桌面标识，false恢复当前系统默认标识。
 * @return 无返回值；调用方需要自行决定是否重新加载当前页面。
 */
private fun applyDesktopUserAgent(context: Context, webView: WebView, enabled: Boolean) {
    val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
    webView.settings.userAgentString = if (enabled) {
        defaultUserAgent
            .replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("Version/4.0 ", "")
            .replace(" Mobile ", " ")
    } else {
        defaultUserAgent
    }
}

/** @return 工具栏使用的两位小数倍速文本。 */
private fun formatToolbarRate(rate: Float): String {
    return String.format(Locale.CHINA, "%.2f×", rate)
}

/**
 * 根据脚本找到的媒体数量生成操作反馈。
 *
 * @param mediaCount 脚本找到并尝试控制的媒体数量。
 * @param successMessage 找到媒体时显示的成功说明。
 * @return 可直接显示在网页工具栏下方的中文反馈。
 */
private fun mediaActionMessage(mediaCount: Int, successMessage: String): String {
    return if (mediaCount > 0) {
        successMessage
    } else {
        "当前页面未检测到可控制的HTML5视频"
    }
}

/**
 * 计算全屏脚本悬浮球拖动后的安全纵向偏移。
 *
 * 使用方法：
 * [rememberDraggableState]每次收到拖动增量时传入当前偏移和增量。函数会把结果限制在屏幕中部
 * 的安全范围，避免悬浮球被拖出屏幕，或移动到底部后再次挡住视频原生进度条。
 *
 * @param currentOffset 当前相对屏幕垂直中心的像素偏移。
 * @param dragDelta 本次纵向拖动增加的像素。
 * @param maximumOffset 允许向上或向下移动的最大绝对像素。
 * @return 已限制在[-maximumOffset, maximumOffset]范围内的新偏移。
 */
internal fun calculateFullscreenBallOffset(
    currentOffset: Float,
    dragDelta: Float,
    maximumOffset: Float
): Float {
    val safeMaximum = maximumOffset.coerceAtLeast(0f)
    return (currentOffset + dragDelta).coerceIn(-safeMaximum, safeMaximum)
}

private const val TAG = "WebsiteScreen"
private const val TOOL_MESSAGE_DURATION_MILLIS = 2_600L
private const val FULLSCREEN_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private val FULLSCREEN_FLOATING_BALL_SIZE = 52.dp
private val FULLSCREEN_FLOATING_BALL_MAX_VERTICAL_OFFSET = 120.dp
private val FULLSCREEN_FLOATING_PANEL_MAX_WIDTH = 520.dp
private val FULLSCREEN_ACTIVE_COLOR = Color(0xFFFFD54F)
private val FULLSCREEN_PLAYBACK_RATE_PRESETS = listOf(
    0.25f,
    0.50f,
    0.75f,
    1.00f,
    1.25f,
    1.50f,
    2.00f,
    2.50f,
    3.00f,
    4.00f,
    5.00f
)
