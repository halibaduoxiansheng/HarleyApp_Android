package com.example.harleyapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.WechatReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 微信等待提醒配置、倒计时和通知次数的真实SharedPreferences持久化测试。
 *
 * 使用方法：
 * 连接Android设备后单独运行本测试类。测试使用独立SharedPreferences名称，不读取或覆盖用户在
 * 正式页面保存的微信提醒设置，并在结束时删除测试文件。
 */
@RunWith(AndroidJUnit4::class)
class WechatReminderRepositoryPersistenceTest {

    /**
     * 验证退出页面后重新创建仓库仍能恢复开关、间隔、通知次数和下一次倒计时时间。
     *
     * @return 无返回值；任一持久化字段不一致时由JUnit报告失败。
     */
    @Test
    fun settingsAndCountdownSurviveRepositoryRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(TEST_PREFERENCE_NAME)

        try {
            val repository = WechatReminderRepository(context, TEST_PREFERENCE_NAME)
            val settings = WechatReminderSettings(
                enabled = true,
                intervalMinutes = 11,
                notificationCount = 3
            )
            val nextReminderAtMillis = System.currentTimeMillis() + 11L * 60L * 1_000L

            assertTrue(repository.saveSettings(settings))
            repository.trackNotification("test-notification-key", System.currentTimeMillis())
            assertTrue(repository.recordNextReminderAt(nextReminderAtMillis))

            val recreatedRepository = WechatReminderRepository(context, TEST_PREFERENCE_NAME)
            assertEquals(settings, recreatedRepository.getSettings())
            assertEquals(1, recreatedRepository.getStatus().pendingNotificationCount)
            assertEquals(nextReminderAtMillis, recreatedRepository.getStatus().nextReminderAtMillis)

            // 固定三次模式在前两次后继续保留待提醒状态，第三次成功展示后才结束本轮。
            assertEquals(
                1,
                recreatedRepository.recordReminderShown(System.currentTimeMillis())
                    .pendingNotificationCount
            )
            assertEquals(
                1,
                recreatedRepository.recordReminderShown(System.currentTimeMillis())
                    .pendingNotificationCount
            )
            val completedStatus = recreatedRepository.recordReminderShown(
                System.currentTimeMillis()
            )
            assertEquals(0, completedStatus.pendingNotificationCount)
            assertEquals(0L, completedStatus.nextReminderAtMillis)
            assertEquals(3, completedStatus.notificationsShownInCycle)
        } finally {
            context.deleteSharedPreferences(TEST_PREFERENCE_NAME)
        }
    }

    /**
     * 验证通知次数为0时表示持续重复，不会在任意固定次数后自动清除本轮待提醒状态。
     *
     * @return 无返回值；持续模式被错误结束时由JUnit报告失败。
     */
    @Test
    fun zeroNotificationCountKeepsContinuousReminderCycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(TEST_PREFERENCE_NAME)

        try {
            val repository = WechatReminderRepository(context, TEST_PREFERENCE_NAME)
            assertTrue(
                repository.saveSettings(
                    WechatReminderSettings(
                        enabled = true,
                        intervalMinutes = 1,
                        notificationCount = 0
                    )
                )
            )
            repository.trackNotification("continuous-test-key", System.currentTimeMillis())

            // 一分钟是用户可保存的最短有效间隔，重新读取时不得回退到旧配置。
            assertEquals(1, repository.getSettings().intervalMinutes)

            repeat(25) {
                repository.recordReminderShown(System.currentTimeMillis())
            }

            assertEquals(1, repository.getStatus().pendingNotificationCount)
            assertEquals(25, repository.getStatus().notificationsShownInCycle)
        } finally {
            context.deleteSharedPreferences(TEST_PREFERENCE_NAME)
        }
    }

    private companion object {
        const val TEST_PREFERENCE_NAME = "wechat_reminder_persistence_test"
    }
}
