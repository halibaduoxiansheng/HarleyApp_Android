package com.example.harleyapp.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.system.ExactAlarmAccessController

/** 通知声音与后台测试的执行结果。 */
enum class NotificationTestResult {
    SHOWN,
    SCHEDULED,
    NOTIFICATION_PERMISSION_MISSING,
    APP_NOTIFICATIONS_DISABLED,
    CHANNEL_DISABLED,
    FAILED
}

/**
 * 使用与正式定时提醒相同的渠道和Alarm链路测试声音、振动与后台接收能力。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面调用[publishNow]可立即验证提示音；调用
 * [scheduleBackgroundTest]后在十秒内返回桌面，可验证App不在前台时系统是否仍会唤醒广播。
 * 测试通知不读取或修改微信消息、账本及用户创建的提醒计划。
 *
 * @param context Android上下文，内部转换为Application Context。
 */
class NotificationTestController(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)
    private val exactAlarmAccessController = ExactAlarmAccessController(applicationContext)

    /**
     * 立即发布一条强提醒测试通知。
     *
     * @param alertChannelId 普通提醒或微信提醒的渠道ID，用于播放对应类型已保存的App内提示音。
     *
     * @return [NotificationTestResult.SHOWN]表示系统已经接受通知；权限、总开关、渠道或发布失败
     * 会返回对应状态，页面可给出明确处理入口。
     */
    fun publishNow(
        alertChannelId: String = NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
    ): NotificationTestResult {
        val preparationFailure = prepareNotificationDelivery(alertChannelId)
        if (preparationFailure != null) {
            return preparationFailure
        }

        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            TEST_NOTIFICATION_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(
            applicationContext,
            alertChannelId
        )
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("HarleyApp 强提醒测试")
            .setContentText("如果听到提示音或感到振动，通知提醒链路工作正常。")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "这是一条不包含私人内容的测试通知，用于验证声音、振动和后台通知权限。"
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

        return runCatching {
            // 每次先取消旧测试通知，确保重复测试仍会重新触发声音和振动。
            notificationManager.cancel(TEST_NOTIFICATION_ID)
            notificationManager.notify(TEST_NOTIFICATION_ID, notification)
            ReminderAlertPlaybackService.start(
                context = applicationContext,
                alertChannelId = alertChannelId
            )
            Log.i(TAG, "Notification alert test displayed")
            NotificationTestResult.SHOWN
        }.getOrElse { error ->
            Log.e(TAG, "Failed to display notification alert test", error)
            NotificationTestResult.FAILED
        }
    }

    /**
     * 安排一次短延迟后台测试。
     *
     * 使用方法：
     * 用户点击“10秒后台测试”后调用，并立即返回桌面或切换到其他App。系统到点后通过显式
     * PendingIntent创建[NotificationTestReceiver]，即使业务Activity不在前台也能发布测试通知。
     *
     * @param delayMillis 从当前时刻开始的延迟毫秒数，限制为3秒到60秒。
     *
     * @return 系统接受Alarm返回[SCHEDULED]；通知权限、渠道或Alarm异常返回对应失败状态。
     */
    fun scheduleBackgroundTest(
        delayMillis: Long = DEFAULT_BACKGROUND_TEST_DELAY_MILLIS
    ): NotificationTestResult {
        val preparationFailure = prepareNotificationDelivery(
            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
        )
        if (preparationFailure != null) {
            return preparationFailure
        }

        val safeDelayMillis = delayMillis.coerceIn(
            MIN_BACKGROUND_TEST_DELAY_MILLIS,
            MAX_BACKGROUND_TEST_DELAY_MILLIS
        )
        val elapsedTriggerAtMillis = SystemClock.elapsedRealtime() + safeDelayMillis
        val wallClockTriggerAtMillis = System.currentTimeMillis() + safeDelayMillis
        val pendingIntent = createTestAlarmPendingIntent()
        return runCatching {
            alarmManager.cancel(pendingIntent)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                exactAlarmAccessController.isGranted()
            ) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        wallClockTriggerAtMillis,
                        createAlarmClockInfoPendingIntent()
                    ),
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTriggerAtMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Background notification test scheduled")
            NotificationTestResult.SCHEDULED
        }.getOrElse { error ->
            Log.e(TAG, "Failed to schedule background notification test", error)
            NotificationTestResult.FAILED
        }
    }

    /**
     * 创建渠道并验证发布通知所需的三层系统开关。
     *
     * @param alertChannelId 普通提醒或微信提醒的受支持渠道ID。
     *
     * @return 可以继续发布返回null；否则返回具体失败状态。
     */
    private fun prepareNotificationDelivery(alertChannelId: String): NotificationTestResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationTestResult.NOTIFICATION_PERMISSION_MISSING
        }

        when (alertChannelId) {
            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID ->
                NotificationAlertChannels.createScheduledReminderChannel(notificationManager)
            NotificationAlertChannels.WECHAT_REMINDER_CHANNEL_ID ->
                NotificationAlertChannels.createWechatReminderChannel(notificationManager)
            else -> return NotificationTestResult.FAILED
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return NotificationTestResult.APP_NOTIFICATIONS_DISABLED
        }
        if (!NotificationAlertChannels.isChannelEnabled(
                notificationManager,
                alertChannelId
            )
        ) {
            return NotificationTestResult.CHANNEL_DISABLED
        }
        return null
    }

    /** @return 指向测试广播接收器的固定不可变PendingIntent。 */
    private fun createTestAlarmPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, NotificationTestReceiver::class.java)
            .setAction(ACTION_SHOW_BACKGROUND_NOTIFICATION_TEST)
        return PendingIntent.getBroadcast(
            applicationContext,
            TEST_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 创建后台测试对应的系统闹钟展示入口。
     *
     * 使用方法：
     * 仅传入[AlarmManager.AlarmClockInfo]供用户点击系统闹钟标记时回到HarleyApp；它与真正
     * 触发测试的广播PendingIntent分离，因此不会因为用户点击标记而提前播放声音。
     *
     * @return 指向[MainActivity]的不可变PendingIntent。
     */
    private fun createAlarmClockInfoPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            TEST_ALARM_CLOCK_INFO_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SHOW_BACKGROUND_NOTIFICATION_TEST =
            "com.example.harleyapp.action.SHOW_BACKGROUND_NOTIFICATION_TEST"
        const val ACTION_SCHEDULE_BACKGROUND_NOTIFICATION_TEST =
            "com.example.harleyapp.action.SCHEDULE_BACKGROUND_NOTIFICATION_TEST"
        const val TEST_NOTIFICATION_ID = 91_001
        private const val TAG = "NotificationTest"
        private const val TEST_NOTIFICATION_REQUEST_CODE = 91_002
        private const val TEST_ALARM_REQUEST_CODE = 91_003
        private const val TEST_ALARM_CLOCK_INFO_REQUEST_CODE = 91_004
        private const val DEFAULT_BACKGROUND_TEST_DELAY_MILLIS = 10_000L
        private const val MIN_BACKGROUND_TEST_DELAY_MILLIS = 3_000L
        private const val MAX_BACKGROUND_TEST_DELAY_MILLIS = 60_000L
    }
}

/**
 * 接收短延迟后台测试Alarm并发布无私人内容的强提醒通知。
 *
 * 使用方法：
 * 仅由[NotificationTestController.scheduleBackgroundTest]创建的显式PendingIntent触发，Manifest中
 * 保持exported=false。用户无需保持App页面打开，Android会在到点时创建接收器。
 */
class NotificationTestReceiver : BroadcastReceiver() {

    /**
     * 校验测试Action并复用统一测试通知发布逻辑。
     *
     * @param context Android广播上下文。
     * @param intent AlarmManager交付的显式广播Intent。
     *
     * @return 无返回值；安排测试Action会先提交真实Alarm，展示Action才发布通知并启动声音服务，
     * 其他Action直接忽略。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == NotificationTestController.ACTION_SCHEDULE_BACKGROUND_NOTIFICATION_TEST) {
            val result = NotificationTestController(context.applicationContext)
                .scheduleBackgroundTest()
            Log.i(TAG, "Background notification test setup finished: result=${result.name}")
            return
        }
        if (intent?.action != NotificationTestController.ACTION_SHOW_BACKGROUND_NOTIFICATION_TEST) {
            return
        }

        val result = NotificationTestController(context.applicationContext).publishNow()
        Log.i(TAG, "Background notification test finished: result=${result.name}")
    }

    private companion object {
        const val TAG = "NotificationTest"
    }
}
