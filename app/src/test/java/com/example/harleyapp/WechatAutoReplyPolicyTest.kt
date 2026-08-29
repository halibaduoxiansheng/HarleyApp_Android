package com.example.harleyapp

import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.notification.AutoReplyContentDecision
import com.example.harleyapp.notification.WechatAutoReplyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 微信自动回复时间窗口和高风险通知过滤的本地单元测试。
 *
 * 使用方法：
 * 执行gradlew testDebugUnitTest。测试不连接手机、不读取真实微信通知，也不会发送任何消息。
 */
class WechatAutoReplyPolicyTest {

    /**
     * 验证同一天时间段的起点包含、终点排除，避免边界时重复生效。
     *
     * @return 无返回值；任一时间边界错误时由JUnit报告失败。
     */
    @Test
    fun sameDayScheduleUsesExpectedBoundaries() {
        assertTrue(WechatAutoReplyPolicy.isWithinSchedule(9 * 60, 9 * 60, 18 * 60))
        assertTrue(WechatAutoReplyPolicy.isWithinSchedule(17 * 60 + 59, 9 * 60, 18 * 60))
        assertFalse(WechatAutoReplyPolicy.isWithinSchedule(18 * 60, 9 * 60, 18 * 60))
    }

    /**
     * 验证跨午夜和全天时间段，保证夜间设置不会在00:00后意外失效。
     *
     * @return 无返回值；跨日计算错误时由JUnit报告失败。
     */
    @Test
    fun overnightAndFullDaySchedulesWork() {
        assertTrue(WechatAutoReplyPolicy.isWithinSchedule(23 * 60, 22 * 60, 7 * 60))
        assertTrue(WechatAutoReplyPolicy.isWithinSchedule(6 * 60 + 59, 22 * 60, 7 * 60))
        assertFalse(WechatAutoReplyPolicy.isWithinSchedule(12 * 60, 22 * 60, 7 * 60))
        assertTrue(WechatAutoReplyPolicy.isWithinSchedule(12 * 60, 8 * 60, 8 * 60))
    }

    /**
     * 验证普通私聊在启用时间内可以进入系统快捷回复阶段。
     *
     * @return 无返回值；普通消息被错误拦截时由JUnit报告失败。
     */
    @Test
    fun normalPrivateMessageIsAllowed() {
        val decision = WechatAutoReplyPolicy.evaluate(
            title = "小明",
            content = "下午开会吗",
            conversationTitle = "",
            isGroupConversation = false,
            settings = AutoReplySettings(
                enabled = true,
                startMinuteOfDay = 9 * 60,
                endMinuteOfDay = 18 * 60
            ),
            now = LocalDateTime.of(2026, 8, 29, 10, 30)
        )

        assertEquals(AutoReplyContentDecision.ALLOWED, decision)
    }

    /**
     * 验证微信通话和支付通知始终被排除，避免回复文字影响来电或记账流程。
     *
     * @return 无返回值；高风险通知未被过滤时由JUnit报告失败。
     */
    @Test
    fun callAndPaymentNotificationsAreBlocked() {
        val settings = AutoReplySettings(
            enabled = true,
            startMinuteOfDay = 0,
            endMinuteOfDay = 0
        )
        val now = LocalDateTime.of(2026, 8, 29, 10, 30)

        assertEquals(
            AutoReplyContentDecision.CALL_NOTIFICATION,
            WechatAutoReplyPolicy.evaluate(
                title = "小明",
                content = "邀请你加入语音通话",
                conversationTitle = "",
                isGroupConversation = false,
                settings = settings,
                now = now
            )
        )
        assertEquals(
            AutoReplyContentDecision.SYSTEM_NOTIFICATION,
            WechatAutoReplyPolicy.evaluate(
                title = "微信支付",
                content = "支付成功￥12.00",
                conversationTitle = "",
                isGroupConversation = false,
                settings = settings,
                now = now
            )
        )
    }

    /**
     * 验证群聊默认关闭，并能在用户显式开启后通过内容策略。
     *
     * @return 无返回值；群聊开关不生效时由JUnit报告失败。
     */
    @Test
    fun groupReplyRequiresExplicitOptIn() {
        val now = LocalDateTime.of(2026, 8, 29, 10, 30)
        val defaultSettings = AutoReplySettings(
            enabled = true,
            startMinuteOfDay = 0,
            endMinuteOfDay = 0
        )

        assertEquals(
            AutoReplyContentDecision.GROUP_DISABLED,
            WechatAutoReplyPolicy.evaluate(
                title = "项目群",
                content = "小明：请看一下进度",
                conversationTitle = "项目群",
                isGroupConversation = true,
                settings = defaultSettings,
                now = now
            )
        )
        assertEquals(
            AutoReplyContentDecision.ALLOWED,
            WechatAutoReplyPolicy.evaluate(
                title = "项目群",
                content = "小明：请看一下进度",
                conversationTitle = "项目群",
                isGroupConversation = true,
                settings = defaultSettings.copy(replyToGroups = true),
                now = now
            )
        )
    }
}
