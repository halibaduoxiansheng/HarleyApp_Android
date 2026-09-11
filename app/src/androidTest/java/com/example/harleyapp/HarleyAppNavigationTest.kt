package com.example.harleyapp

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

/**
 * Harley生活助手关键页面的无数据污染实机导航测试。
 *
 * 使用方法：
 * 连接测试手机后执行gradlew connectedDebugAndroidTest。测试只打开并关闭记账对话框，
 * 不保存账目、不修改快捷应用选择，也不会改变用户业务数据。内置WebView受真实网络加载影响，
 * 不加入稳定性等待；网站页面由编译、首轮实机到达记录和应用启动日志单独验证。
 * 本测试通过Android无障碍节点读取页面，不注册Compose空闲监听，因此不会被首页持续更新的
 * 网速、内存和传感器状态阻塞。同时直接启动被测MainActivity，绕开部分小米系统会卡住的
 * ActivityScenario空白中转页面。
 */
@RunWith(AndroidJUnit4::class)
class HarleyAppNavigationTest {

    /**
     * 直接启动App主页面，并清理上一个测试可能保留的页面任务。
     *
     * 使用方法：
     * 每个独立测试开始时调用launchMainActivity()，随后即可通过waitForNode读取实际页面。
     *
     * @return 无返回值；启动请求失败时会通过断言报告系统命令输出。
     */
    private fun launchMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launchFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        // 使用shell身份异步拉起页面，可获得前台启动权限且不会等待持续刷新的Compose页面完全空闲。
        val commandOutput = instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.example.harleyapp/.MainActivity -f $launchFlags"
        )
        val outputText = ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { inputStream ->
            inputStream.bufferedReader().readText()
        }

        assertTrue(
            "MainActivity launch failed: $outputText",
            !outputText.contains("Error", ignoreCase = true)
        )
    }

    /**
     * 在当前页面中等待指定文字或内容说明对应的无障碍节点。
     *
     * 使用方法：
     * 页面切换或对话框打开后调用waitForNode("目标文字")，函数会在限定时间内轮询页面树。
     *
     * @param label 需要查找的完整文字或contentDescription内容。
     * @param timeoutMs 最长等待毫秒数，默认10秒。
     * @return 找到的无障碍节点；超时会抛出AssertionError并使测试明确失败。
     */
    private fun waitForNode(
        label: String,
        timeoutMs: Long = 10_000L
    ): AccessibilityNodeInfo {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        while (SystemClock.uptimeMillis() < deadline) {
            val rootNode = uiAutomation.rootInActiveWindow
            val matchedNode = rootNode?.let { findNodeByLabel(it, label) }
            if (matchedNode != null) {
                return matchedNode
            }

            // 页面切换存在异步绘制过程，短暂等待可减少无意义的高频遍历。
            SystemClock.sleep(100L)
        }

        throw AssertionError("Node not found: $label")
    }

    /**
     * 使用广度优先方式遍历无障碍节点树并匹配完整标签。
     *
     * 使用方法：
     * 由waitForNode内部调用，不需要测试用例直接调用。
     *
     * @param rootNode 当前活动窗口的根节点。
     * @param label 需要匹配的文字或内容说明。
     * @return 第一个完整匹配的节点；不存在时返回null。
     */
    private fun findNodeByLabel(
        rootNode: AccessibilityNodeInfo,
        label: String
    ): AccessibilityNodeInfo? {
        val pendingNodes = ArrayDeque<AccessibilityNodeInfo>()
        pendingNodes.add(rootNode)

        while (pendingNodes.isNotEmpty()) {
            val currentNode = pendingNodes.removeFirst()
            val nodeText = currentNode.text?.toString()
            val nodeDescription = currentNode.contentDescription?.toString()

            if (nodeText == label || nodeDescription == label) {
                return currentNode
            }

            for (index in 0 until currentNode.childCount) {
                currentNode.getChild(index)?.let(pendingNodes::addLast)
            }
        }

        return null
    }

    /**
     * 点击指定标签对应的节点；若文字节点本身不可点击，则向父级查找可点击容器。
     *
     * 使用方法：
     * 调用clickNode("记账")可点击底部导航，调用clickNode("取消")可关闭对话框。
     *
     * @param label 需要点击的完整文字或内容说明。
     * @return 无返回值；节点或父级无法执行点击时会使测试明确失败。
     */
    private fun clickNode(label: String) {
        var clickableNode = waitForNode(label)

        while (!clickableNode.isClickable && clickableNode.parent != null) {
            clickableNode = clickableNode.parent
        }

        assertTrue(
            "Node cannot be clicked: $label",
            clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        )
    }

    /**
     * 向上滑动当前页面，供功能中心查找屏幕下方的新入口。
     *
     * 使用方法：
     * 仅在已确认处于功能中心等只读列表页面时调用。滑动坐标根据当前设备像素动态计算，不点击卡片，
     * 也不修改任何业务数据。
     *
     * @return 无返回值；Shell命令完成后短暂等待Compose列表稳定。
     */
    private fun swipePageUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val displayMetrics = instrumentation.targetContext.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2
        val startY = (displayMetrics.heightPixels * 0.78f).toInt()
        val endY = (displayMetrics.heightPixels * 0.28f).toInt()
        val commandOutput = instrumentation.uiAutomation.executeShellCommand(
            "input swipe $centerX $startY $centerX $endY 420"
        )
        ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { inputStream ->
            inputStream.readBytes()
        }
        SystemClock.sleep(600L)
    }

    /**
     * 在可滚动页面中查找固定文字，未找到时最多向上滑动六次。
     *
     * 使用方法：
     * 功能中心新入口位于旧用户保存顺序的末尾时，调用本函数确保测试不依赖具体屏幕高度。
     *
     * @param label 需要完整匹配的固定页面文字。
     * @return 找到的无障碍节点；六次滑动后仍不存在时由最终waitForNode给出明确失败。
     */
    private fun scrollUntilNode(label: String): AccessibilityNodeInfo {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(6) {
            val matchedNode = uiAutomation.rootInActiveWindow?.let { rootNode ->
                findNodeByLabel(rootNode, label)
            }
            if (matchedNode != null) {
                return matchedNode
            }
            swipePageUp()
        }
        return waitForNode(label, timeoutMs = 2_000L)
    }

    /**
     * 验证记账可从功能中心进入，新增账目对话框和返回入口均正常。
     *
     * @return 无返回值；任一关键界面缺失时由测试框架报告失败。
     */
    @Test
    fun ledgerNavigationAndDialogWork() {
        launchMainActivity()

        clickNode("功能")
        waitForNode("功能中心")
        clickNode("记账")
        waitForNode("我的账本")
        waitForNode("← 功能中心")

        clickNode("新增账目")
        waitForNode("记一笔")
        clickNode("取消")
    }

    /**
     * 验证运动页和动态项目管理入口可以从功能中心进入。
     *
     * 使用方法：
     * 测试只打开、关闭管理弹窗，不新增、修改或删除任何运动项目，不污染用户数据。
     *
     * @return 无返回值；运动页或项目管理入口未显示时由测试框架报告失败。
     */
    @Test
    fun fitnessNavigationWorks() {
        launchMainActivity()

        clickNode("功能")
        waitForNode("功能中心")
        clickNode("运动")
        waitForNode("今日训练")
        waitForNode("← 功能中心")
        clickNode("管理项目")
        waitForNode("新增项目")
        clickNode("完成")
    }

    /**
     * 验证“我的”页显示外观、应用版本和快捷应用管理入口。
     *
     * @return 无返回值；页面或微信设置卡片未显示时由测试框架报告失败。
     */
    @Test
    fun profileNavigationShowsBasicSettings() {
        launchMainActivity()

        clickNode("我的")
        waitForNode("外观模式")
        waitForNode("Harley生活助手")
        waitForNode("快捷应用管理")
    }

    /**
     * 验证微信消息提醒已移动到功能中心，并可通过统一返回入口回到概览。
     *
     * @return 无返回值；功能入口、详情卡片或返回流程缺失时由测试框架报告失败。
     */
    @Test
    fun featureCenterWechatReminderNavigationWorks() {
        launchMainActivity()

        clickNode("功能")
        waitForNode("功能中心")
        clickNode("微信消息提醒")
        waitForNode("微信未查看消息提醒")
        clickNode("← 功能中心")
        waitForNode("功能中心")
    }

    /**
     * 验证应用使用和深海憋气均可从功能中心进入，并保持无数据污染。
     *
     * 使用方法：
     * 测试只检查固定标题；憋气确认后在三秒倒计时阶段取消，不进入正式计时、不保存历史。
     *
     * @return 无返回值；任一入口、标题或安全确认缺失时由测试框架报告失败。
     */
    @Test
    fun personalPhoneToolsNavigationWorksWithoutChangingUserData() {
        launchMainActivity()

        clickNode("功能")
        waitForNode("功能中心")
        scrollUntilNode("应用使用")
        clickNode("应用使用")
        waitForNode("看见时间去了哪里")
        waitForNode("最近7天趋势")
        clickNode("‹ 返回")

        waitForNode("功能中心")
        scrollUntilNode("深海憋气")
        clickNode("深海憋气")
        waitForNode("沉入安静的蓝色")
        clickNode("开始憋气")
        waitForNode("先确认安全环境")
        clickNode("我已坐稳，开始倒计时")
        waitForNode("倒计时结束后开始计时")
        clickNode("取消")
        waitForNode("沉入安静的蓝色")
    }
}
