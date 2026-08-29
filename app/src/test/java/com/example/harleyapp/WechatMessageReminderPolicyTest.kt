package com.example.harleyapp

import com.example.harleyapp.notification.WechatMessageReminderPolicy
import com.example.harleyapp.notification.WechatReminderDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 微信未查看消息提醒过滤策略的本地单元测试。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest。测试不连接手机、不读取真实通知，也不会展示提醒。
 */
class WechatMessageReminderPolicyTest {

    /**
     * 验证普通单聊和群聊消息都会进入未查看提醒。
     *
     * @return 无返回值；判断不符时由JUnit报告失败。
     */
    @Test
    fun ordinaryChatsAreReminded() {
        assertEquals(
            WechatReminderDecision.REMIND,
            WechatMessageReminderPolicy.evaluate("好友", "晚上一起吃饭吗？")
        )
        assertEquals(
            WechatReminderDecision.REMIND,
            WechatMessageReminderPolicy.evaluate("家庭群", "妈妈：明天早点回来")
        )
    }

    /**
     * 验证微信隐藏正文时的通用消息提示仍能触发提醒。
     *
     * @return 无返回值；隐藏内容被错误跳过时由JUnit报告失败。
     */
    @Test
    fun hiddenMessageContentIsReminded() {
        assertEquals(
            WechatReminderDecision.REMIND,
            WechatMessageReminderPolicy.evaluate("微信", "你收到了一条消息")
        )
    }

    /**
     * 验证语音和视频通话不会产生反复提醒。
     *
     * @return 无返回值；通话通知被允许时由JUnit报告失败。
     */
    @Test
    fun callNotificationsAreIgnored() {
        assertEquals(
            WechatReminderDecision.CALL_NOTIFICATION,
            WechatMessageReminderPolicy.evaluate("好友", "邀请你加入视频通话")
        )
    }

    /**
     * 验证支付与系统类通知不会产生反复提醒。
     *
     * @return 无返回值；系统通知被允许时由JUnit报告失败。
     */
    @Test
    fun systemNotificationsAreIgnored() {
        assertEquals(
            WechatReminderDecision.SYSTEM_NOTIFICATION,
            WechatMessageReminderPolicy.evaluate("微信支付", "支付成功 ¥20.00")
        )
        assertEquals(
            WechatReminderDecision.SYSTEM_NOTIFICATION,
            WechatMessageReminderPolicy.evaluate("微信团队", "安全提醒")
        )
    }

    /**
     * 验证完全空白的通知不会进入提醒集合。
     *
     * @return 无返回值；空通知被允许时由JUnit报告失败。
     */
    @Test
    fun emptyNotificationIsIgnored() {
        assertEquals(
            WechatReminderDecision.EMPTY_NOTIFICATION,
            WechatMessageReminderPolicy.evaluate(" ", " ")
        )
    }
}
