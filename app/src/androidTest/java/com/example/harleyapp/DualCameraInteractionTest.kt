package com.example.harleyapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

/**
 * 在支持并发相机的Android设备上验证双摄页面的交换和双指缩放输入链路。
 *
 * 使用方法：
 * 连接已解锁的测试手机后执行
 * `gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.harleyapp.DualCameraInteractionTest`。
 * 测试会授予相机权限、打开双摄页面并注入真实的双指MotionEvent，不拍照、不录像，也不修改用户
 * 业务数据；设备没有声明并发相机能力时会跳过，而不会把硬件限制误报成代码失败。
 */
@RunWith(AndroidJUnit4::class)
class DualCameraInteractionTest {

    /**
     * 验证点击任一画面会交换上下位置，后摄双指放大、缩小不会改变前摄倍率。
     *
     * @return 无返回值；导航、交换或任一缩放方向不正确时由JUnit报告失败。
     */
    @Test
    fun dualCameraSwapAndIndependentPinchZoomWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assumeTrue(
            "Device does not declare concurrent camera support",
            targetContext.packageManager.hasSystemFeature(
                PackageManager.FEATURE_CAMERA_CONCURRENT
            )
        )

        executeShellCommand("pm grant ${targetContext.packageName} ${Manifest.permission.CAMERA}")
        launchMainActivity()

        clickNode("功能")
        waitForNode { node -> node.text?.toString() == "功能中心" }
        scrollUntilNode("前后双摄")
        clickNode("前后双摄")
        waitForNode(timeoutMs = 15_000L) { node -> node.text?.toString() == "双面摄像头" }
        waitForNode(timeoutMs = 15_000L) { node ->
            node.contentDescription?.toString() == "双路相机已打开"
        }
        SystemClock.sleep(VIEWFINDER_SETTLE_MILLIS)

        val initialFront = waitForLensNode("前置摄像头")
        val initialBack = waitForLensNode("后置摄像头")
        val initialFrontBounds = nodeBounds(initialFront)
        val initialBackBounds = nodeBounds(initialBack)
        val initialFrontZoom = parseZoomRatio(initialFront)

        clickNode(initialBack)
        waitForNode { node ->
            val text = node.text?.toString().orEmpty()
            text.startsWith("后置摄像头") && nodeBounds(node) != initialBackBounds
        }
        SystemClock.sleep(CAMERA_SWAP_SETTLE_MILLIS)

        val swappedFront = waitForLensNode("前置摄像头")
        val swappedBack = waitForLensNode("后置摄像头")
        val swappedFrontBounds = nodeBounds(swappedFront)
        val swappedBackBounds = nodeBounds(swappedBack)
        assertEquals(initialBackBounds, swappedFrontBounds)
        assertEquals(initialFrontBounds, swappedBackBounds)
        waitForNode { node -> node.text?.toString() == "双面摄像头" }
        waitForNode { node -> node.text?.toString() == "功能" }

        val backPaneBounds = clickableAncestorBounds(swappedBack)
        val initialBackZoom = parseZoomRatio(swappedBack)
        injectPinchGesture(backPaneBounds, opening = true)
        val enlargedBack = waitForZoomChange(
            lensLabel = "后置摄像头",
            previousRatio = initialBackZoom,
            shouldIncrease = true
        )
        val enlargedBackZoom = parseZoomRatio(enlargedBack)
        assertEquals(swappedBackBounds, nodeBounds(enlargedBack))
        assertEquals(swappedFrontBounds, nodeBounds(waitForLensNode("前置摄像头")))
        assertEquals(initialFrontZoom, parseZoomRatio(waitForLensNode("前置摄像头")), 0.11f)

        injectPinchGesture(clickableAncestorBounds(enlargedBack), opening = false)
        val reducedBack = waitForZoomChange(
            lensLabel = "后置摄像头",
            previousRatio = enlargedBackZoom,
            shouldIncrease = false
        )
        assertEquals(swappedBackBounds, nodeBounds(reducedBack))
        assertEquals(swappedFrontBounds, nodeBounds(waitForLensNode("前置摄像头")))
        assertEquals(initialFrontZoom, parseZoomRatio(waitForLensNode("前置摄像头")), 0.11f)
    }

    /**
     * 直接启动主Activity，并清理上一轮测试可能遗留的页面任务。
     *
     * @return 无返回值；系统拒绝启动时由断言报告命令输出。
     */
    private fun launchMainActivity() {
        val launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val output = executeShellCommand(
            "am start -n com.example.harleyapp/.MainActivity -f $launchFlags"
        )

        assertTrue(
            "MainActivity launch failed: $output",
            !output.contains("Error", ignoreCase = true)
        )
    }

    /**
     * 执行一条测试所需的只读导航或权限Shell命令，并完整读取输出以关闭文件描述符。
     *
     * @param command 交给Android shell执行的命令。
     * @return 命令的标准输出文本。
     */
    private fun executeShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { inputStream ->
            inputStream.bufferedReader().readText()
        }
    }

    /**
     * 等待无障碍树中出现满足条件的节点，避免依赖Compose空闲状态或固定动画时长。
     *
     * @param timeoutMs 最长等待时长，单位为毫秒。
     * @param predicate 判断节点是否为目标的条件。
     * @return 首个满足条件的节点；超时会抛出AssertionError。
     */
    private fun waitForNode(
        timeoutMs: Long = DEFAULT_NODE_TIMEOUT_MILLIS,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        while (SystemClock.uptimeMillis() < deadline) {
            val rootNode = uiAutomation.rootInActiveWindow
            val matchedNode = rootNode?.let { root -> findNode(root, predicate) }
            if (matchedNode != null) return matchedNode
            SystemClock.sleep(NODE_POLL_INTERVAL_MILLIS)
        }

        throw AssertionError("Accessibility node was not found before timeout")
    }

    /**
     * 以广度优先顺序遍历当前窗口的无障碍节点。
     *
     * @param rootNode 当前活动窗口根节点。
     * @param predicate 判断节点是否为目标的条件。
     * @return 首个满足条件的节点；不存在时返回null。
     */
    private fun findNode(
        rootNode: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
        pendingNodes.add(rootNode)

        while (pendingNodes.isNotEmpty()) {
            val currentNode = pendingNodes.removeFirst()
            if (predicate(currentNode)) return currentNode

            for (index in 0 until currentNode.childCount) {
                currentNode.getChild(index)?.let(pendingNodes::addLast)
            }
        }

        return null
    }

    /**
     * 点击指定完整文字对应的节点；文字本身不可点击时向上查找可点击容器。
     *
     * @param label 需要完整匹配的界面文字。
     * @return 无返回值；节点或祖先不能点击时由断言报告失败。
     */
    private fun clickNode(label: String) {
        clickNode(waitForNode { node -> node.text?.toString() == label })
    }

    /**
     * 点击已找到节点对应的可点击祖先。
     *
     * @param node 目标文字或内容节点。
     * @return 无返回值；未找到可点击祖先或系统拒绝动作时由断言报告失败。
     */
    private fun clickNode(node: AccessibilityNodeInfo) {
        var clickableNode = node
        while (!clickableNode.isClickable && clickableNode.parent != null) {
            clickableNode = clickableNode.parent
        }

        assertTrue("Node has no clickable ancestor", clickableNode.isClickable)
        assertTrue(
            "Accessibility click action failed",
            clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        )
    }

    /**
     * 在功能中心向上滑动，直到目标入口进入无障碍树。
     *
     * @param label 需要完整匹配的功能名称。
     * @return 找到的入口节点；多次滑动后仍不存在时由waitForNode报告失败。
     */
    private fun scrollUntilNode(label: String): AccessibilityNodeInfo {
        repeat(MAX_FEATURE_CENTER_SCROLLS) {
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow
            val matchedNode = rootNode?.let { root ->
                findNode(root) { node -> node.text?.toString() == label }
            }
            if (matchedNode != null) return matchedNode

            val displayMetrics = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
            val centerX = displayMetrics.widthPixels / 2
            val startY = (displayMetrics.heightPixels * 0.78f).toInt()
            val endY = (displayMetrics.heightPixels * 0.26f).toInt()
            executeShellCommand("input swipe $centerX $startY $centerX $endY 300")
            SystemClock.sleep(FEATURE_CENTER_SCROLL_SETTLE_MILLIS)
        }

        return waitForNode(timeoutMs = 2_000L) { node -> node.text?.toString() == label }
    }

    /**
     * 等待并返回指定前摄或后摄的倍率标签节点。
     *
     * @param lensLabel 固定镜头名称，例如“后置摄像头”。
     * @return 当前显示该镜头名称和倍率的文本节点。
     */
    private fun waitForLensNode(lensLabel: String): AccessibilityNodeInfo {
        return waitForNode { node -> node.text?.toString().orEmpty().startsWith(lensLabel) }
    }

    /**
     * 等待指定镜头倍率相对旧值按预期方向变化。
     *
     * @param lensLabel 固定镜头名称。
     * @param previousRatio 手势前读取到的倍率。
     * @param shouldIncrease true等待放大，false等待缩小。
     * @return 倍率已经变化的最新文本节点。
     */
    private fun waitForZoomChange(
        lensLabel: String,
        previousRatio: Float,
        shouldIncrease: Boolean
    ): AccessibilityNodeInfo {
        return waitForNode(timeoutMs = ZOOM_CHANGE_TIMEOUT_MILLIS) { node ->
            val text = node.text?.toString().orEmpty()
            if (!text.startsWith(lensLabel)) {
                false
            } else {
                val ratio = parseZoomRatioOrNull(text)
                ratio != null && if (shouldIncrease) {
                    ratio > previousRatio
                } else {
                    ratio < previousRatio
                }
            }
        }
    }

    /**
     * 从镜头标签中解析一位小数的倍率。
     *
     * @param node 含“镜头名称  1.0×”格式文字的无障碍节点。
     * @return 解析出的浮点倍率；格式不正确时由断言报告失败。
     */
    private fun parseZoomRatio(node: AccessibilityNodeInfo): Float {
        val text = node.text?.toString().orEmpty()
        return parseZoomRatioOrNull(text)
            ?: throw AssertionError("Zoom ratio was not found in node text: $text")
    }

    /**
     * 尝试从镜头标签文字末尾解析倍率。
     *
     * @param text 镜头标签完整文字。
     * @return 解析成功时返回倍率，文字格式不匹配时返回null。
     */
    private fun parseZoomRatioOrNull(text: String): Float? {
        return ZOOM_RATIO_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    /**
     * 读取节点在物理屏幕中的边界副本。
     *
     * @param node 需要读取坐标的无障碍节点。
     * @return 不会随节点后续刷新而改变的Rect副本。
     */
    private fun nodeBounds(node: AccessibilityNodeInfo): Rect {
        return Rect().also(node::getBoundsInScreen)
    }

    /**
     * 向上查找镜头标签对应的可点击半屏，并返回该半屏物理坐标。
     *
     * @param node 镜头倍率标签节点。
     * @return 可接收交换与缩放手势的完整半屏边界。
     */
    private fun clickableAncestorBounds(node: AccessibilityNodeInfo): Rect {
        var currentNode = node
        while (!currentNode.isClickable && currentNode.parent != null) {
            currentNode = currentNode.parent
        }
        assertTrue("Lens label has no clickable camera pane", currentNode.isClickable)
        return nodeBounds(currentNode)
    }

    /**
     * 在指定半屏内注入一组合法的双指张开或合拢MotionEvent。
     *
     * 使用方法：
     * [opening]为true时两指从中心向两侧移动以放大；为false时从两侧向中心移动以缩小。所有事件
     * 使用同一downTime和两个稳定pointer id，并在失败时补发ACTION_CANCEL避免残留触点。
     *
     * @param bounds 接收手势的可点击相机半屏物理坐标。
     * @param opening true注入放大手势，false注入缩小手势。
     * @return 无返回值；系统拒绝任一输入事件时由断言报告失败。
     */
    private fun injectPinchGesture(bounds: Rect, opening: Boolean) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val nearGap = (bounds.width() * 0.10f).coerceAtLeast(MIN_POINTER_GAP_PIXELS)
        val farGap = (bounds.width() * 0.36f).coerceAtMost(
            bounds.width() / 2f - POINTER_EDGE_MARGIN_PIXELS
        )
        val startGap = if (opening) nearGap else farGap
        val endGap = if (opening) farGap else nearGap
        val downTime = SystemClock.uptimeMillis()
        var gestureFinished = false

        try {
            injectMotionEvent(
                action = MotionEvent.ACTION_DOWN,
                downTime = downTime,
                points = listOf(centerX - startGap to centerY)
            )
            injectMotionEvent(
                action = MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                downTime = downTime,
                points = listOf(
                    centerX - startGap to centerY,
                    centerX + startGap to centerY
                )
            )

            for (step in 1..PINCH_MOVE_STEPS) {
                val fraction = step.toFloat() / PINCH_MOVE_STEPS
                val currentGap = startGap + (endGap - startGap) * fraction
                injectMotionEvent(
                    action = MotionEvent.ACTION_MOVE,
                    downTime = downTime,
                    points = listOf(
                        centerX - currentGap to centerY,
                        centerX + currentGap to centerY
                    )
                )
                SystemClock.sleep(PINCH_FRAME_INTERVAL_MILLIS)
            }

            injectMotionEvent(
                action = MotionEvent.ACTION_POINTER_UP or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                downTime = downTime,
                points = listOf(
                    centerX - endGap to centerY,
                    centerX + endGap to centerY
                )
            )
            injectMotionEvent(
                action = MotionEvent.ACTION_UP,
                downTime = downTime,
                points = listOf(centerX - endGap to centerY)
            )
            gestureFinished = true
        } finally {
            if (!gestureFinished) {
                val cancelEvent = createMotionEvent(
                    action = MotionEvent.ACTION_CANCEL,
                    downTime = downTime,
                    points = listOf(centerX to centerY)
                )
                uiAutomation.injectInputEvent(cancelEvent, true)
                cancelEvent.recycle()
            }
        }
    }

    /**
     * 创建并同步注入一个触摸事件，随后立即回收MotionEvent对象。
     *
     * @param action 含pointer index的MotionEvent动作值。
     * @param downTime 本轮完整手势共享的按下时间。
     * @param points 当前事件内每个pointer的屏幕坐标，列表索引同时作为稳定pointer id。
     * @return 无返回值；注入失败时由断言报告失败。
     */
    private fun injectMotionEvent(
        action: Int,
        downTime: Long,
        points: List<Pair<Float, Float>>
    ) {
        val event = createMotionEvent(action, downTime, points)
        try {
            assertTrue(
                "MotionEvent injection failed for action=$action",
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .injectInputEvent(event, true)
            )
        } finally {
            event.recycle()
        }
    }

    /**
     * 按Android多点触控协议创建包含稳定pointer id的触摸事件。
     *
     * @param action 含pointer index的动作值。
     * @param downTime 本轮手势共享的按下时间。
     * @param points 每根手指的屏幕坐标。
     * @return 尚未注入、需要由调用方回收的MotionEvent。
     */
    private fun createMotionEvent(
        action: Int,
        downTime: Long,
        points: List<Pair<Float, Float>>
    ): MotionEvent {
        val pointerProperties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val pointerCoordinates = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }

        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            points.size,
            pointerProperties,
            pointerCoordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private companion object {
        val ZOOM_RATIO_PATTERN = Regex("([0-9]+(?:\\.[0-9]+)?)×$")

        const val DEFAULT_NODE_TIMEOUT_MILLIS = 10_000L
        const val ZOOM_CHANGE_TIMEOUT_MILLIS = 5_000L
        const val NODE_POLL_INTERVAL_MILLIS = 100L
        const val FEATURE_CENTER_SCROLL_SETTLE_MILLIS = 500L
        const val VIEWFINDER_SETTLE_MILLIS = 500L
        const val CAMERA_SWAP_SETTLE_MILLIS = 300L
        const val PINCH_FRAME_INTERVAL_MILLIS = 16L
        const val MAX_FEATURE_CENTER_SCROLLS = 8
        const val PINCH_MOVE_STEPS = 14
        const val MIN_POINTER_GAP_PIXELS = 48f
        const val POINTER_EDGE_MARGIN_PIXELS = 36f
    }
}
