package com.example.harleyapp

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.reminder.ReminderScheduler
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通用通知提醒从AlarmManager到系统通知栏的真实设备交付测试。
 *
 * 使用方法：
 * 仅在已连接的Android设备上运行。测试创建一条数秒后的专用计划，等待系统真实触发广播，验证
 * 仓库状态和通知栏后，在finally中取消Alarm、删除测试计划并移除测试通知，不影响用户正式数据。
 */
@RunWith(AndroidJUnit4::class)
class ReminderNotificationDeliveryTest {

    /**
     * 验证精确Alarm能够在短时间内触发ReminderReceiver并成功发布本App通知。
     *
     * @return 无返回值；Alarm未触发、计划未标记或通知不存在时由JUnit报告失败。
     */
    @Test
    fun exactAlarmPostsAndRecordsLocalNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ReminderRepository(context)
        val scheduler = ReminderScheduler(context)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        var savedReminder: ScheduledReminder? = null

        try {
            savedReminder = repository.upsertReminder(
                ScheduledReminder(
                    id = 0L,
                    content = TEST_REMINDER_CONTENT,
                    nextTriggerAtMillis = System.currentTimeMillis() + TRIGGER_DELAY_MILLIS,
                    repeatIntervalDays = 0
                )
            )
            assertNotNull(savedReminder)
            assertTrue(scheduler.schedule(savedReminder!!))

            Thread.sleep(DELIVERY_WAIT_MILLIS)

            val deliveredReminder = repository.getReminder(savedReminder.id)
            assertNotNull(deliveredReminder)
            assertTrue(deliveredReminder!!.lastTriggeredAtMillis > 0L)
            assertTrue(
                notificationManager.activeNotifications.any { statusBarNotification ->
                    statusBarNotification.notification.extras
                        .getCharSequence(Notification.EXTRA_TEXT)
                        ?.toString() == TEST_REMINDER_CONTENT
                }
            )
        } finally {
            savedReminder?.let { reminder ->
                scheduler.cancel(reminder.id)
                repository.deleteReminder(reminder.id)
                notificationManager.cancel(reminder.id.toNotificationId())
            }
        }
    }

    /**
     * 使用与ReminderReceiver一致的算法生成测试通知编号，便于结束后精确清理。
     *
     * @return 当前计划对应的32位系统通知编号。
     */
    private fun Long.toNotificationId(): Int {
        return (this xor (this ushr 32)).toInt() xor ReminderScheduler.REQUEST_CODE_SALT
    }

    private companion object {
        const val TEST_REMINDER_CONTENT = "Harley reminder delivery test"
        const val TRIGGER_DELAY_MILLIS = 5_000L
        const val DELIVERY_WAIT_MILLIS = 8_000L
    }
}
