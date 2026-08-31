package com.example.harleyapp

import com.example.harleyapp.notification.NotificationAlertChannels
import com.example.harleyapp.notification.NotificationTestResult
import com.example.harleyapp.ui.notificationTestResultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证App独立声音渠道版本和通知测试反馈，不依赖真实NotificationManager或手机声音设备。
 *
 * 使用方法：
 * 在项目根目录运行gradlew testDebugUnitTest，由JUnit自动执行本测试类。
 */
class NotificationDeliveryModelsTest {

    /**
     * 验证通用提醒和微信提醒已经切换到新的v5 App独立声音渠道。
     *
     * @return 无返回值；任一渠道仍复用旧系统铃声配置时由JUnit报告失败。
     */
    @Test
    fun alertChannelsUseNewStrongReminderVersion() {
        assertTrue(NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID.endsWith("_v5"))
        assertTrue(NotificationAlertChannels.WECHAT_REMINDER_CHANNEL_ID.endsWith("_v5"))
    }

    /**
     * 验证每种测试失败原因都有独立中文提示，后台测试成功时明确要求离开App等待。
     *
     * @return 无返回值；反馈混淆或缺少后台操作说明时由JUnit报告失败。
     */
    @Test
    fun notificationTestResultsProvideActionableFeedback() {
        val failureMessages = NotificationTestResult.entries
            .filterNot { result -> result == NotificationTestResult.SCHEDULED }
            .map { result ->
                notificationTestResultMessage(result, backgroundTest = false)
            }
        assertEquals(failureMessages.size, failureMessages.distinct().size)

        val scheduledMessage = notificationTestResultMessage(
            result = NotificationTestResult.SCHEDULED,
            backgroundTest = true
        )
        assertTrue(scheduledMessage.contains("10秒"))
        assertTrue(scheduledMessage.contains("返回桌面"))
    }
}
