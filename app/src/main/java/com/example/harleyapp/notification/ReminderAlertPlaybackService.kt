package com.example.harleyapp.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.R

/**
 * 使用短时前台服务播放用户在HarleyApp内选择的独立合成提示音。
 *
 * 使用方法：
 * 通知已经成功提交给NotificationManager后，调用[start]并传入对应的提醒渠道ID。服务根据渠道
 * 区分普通提醒和微信提醒，从[ReminderSoundRepository]读取各自保存的声音，建立低重要性的短时
 * 前台状态后由[AppReminderSoundPlayer]实时合成播放，最多五秒后自动停止。提示音不读取手机
 * 来电或系统通知铃声，因此两类提醒可以独立选择并在App退出页面后继续生效。
 */
class ReminderAlertPlaybackService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val soundRepository by lazy {
        ReminderSoundRepository(applicationContext)
    }
    private var activeSoundPlayer: AppReminderSoundPlayer? = null
    private val stopRunnable = Runnable {
        stopPlaybackAndService()
    }

    /**
     * 创建只用于短时声音服务状态的静音通知渠道。
     *
     * @return 无返回值；渠道由Android系统持久化并安全去重。
     */
    override fun onCreate() {
        super.onCreate()
        createPlaybackServiceChannel()
    }

    /**
     * 建立前台状态并播放目标提醒类型当前选择的HarleyApp提示音与振动节奏。
     *
     * @param intent [start]创建的显式服务Intent，其中包含目标提醒渠道ID。
     * @param flags Android传入的服务启动标志，本服务不依赖该值。
     * @param startId 本次服务启动编号，用于Android内部区分重复启动。
     *
     * @return [START_NOT_STICKY]，进程被系统终止后不自动重播旧提醒。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alertChannelId = intent?.getStringExtra(EXTRA_ALERT_CHANNEL_ID)
        if (alertChannelId.isNullOrBlank()) {
            Log.e(TAG, "Alert playback service started without channel id")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(PLAYBACK_SERVICE_NOTIFICATION_ID, createPlaybackServiceNotification())
        mainHandler.removeCallbacks(stopRunnable)
        stopActiveSound()
        playChannelAlert(alertChannelId)
        mainHandler.postDelayed(stopRunnable, MAX_PLAYBACK_DURATION_MILLIS)
        return START_NOT_STICKY
    }

    /**
     * 本服务不向其他组件提供绑定接口。
     *
     * @param intent Android传入的绑定Intent，本服务不使用。
     *
     * @return 固定返回null，调用方应使用[start]启动。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务销毁时停止App提示音并清除延迟任务，避免声音泄漏到下一次提醒。
     *
     * @return 无返回值。
     */
    override fun onDestroy() {
        mainHandler.removeCallbacks(stopRunnable)
        stopActiveSound()
        super.onDestroy()
    }

    /**
     * 根据指定通知渠道读取对应的App内提示音并执行一次短提醒。
     *
     * @param alertChannelId 普通定时提醒或微信提醒的系统通知渠道ID。
     *
     * @return 无返回值；渠道不存在、类型未知、用户选择仅振动或音频设备失败时记录英文日志。
     */
    private fun playChannelAlert(alertChannelId: String) {
        val alertChannel = notificationManager.getNotificationChannel(alertChannelId)
        if (alertChannel == null) {
            Log.e(TAG, "Alert playback channel is missing")
            return
        }

        val soundTarget = when (alertChannelId) {
            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID ->
                ReminderSoundTarget.SCHEDULED
            NotificationAlertChannels.WECHAT_REMINDER_CHANNEL_ID ->
                ReminderSoundTarget.WECHAT
            else -> null
        }
        if (soundTarget == null) {
            Log.e(TAG, "Alert playback channel type is unknown")
            return
        }

        val selectedSound = soundRepository.getSound(soundTarget)
        if (selectedSound == AppReminderSound.VIBRATION_ONLY) {
            Log.i(TAG, "Custom reminder sound skipped because vibration-only is selected")
        } else {
            val player = AppReminderSoundPlayer()
            if (player.play(selectedSound)) {
                activeSoundPlayer = player
                Log.i(TAG, "Custom alert sound started: sound=${selectedSound.name}")
            } else {
                Log.e(TAG, "Custom alert sound could not be started")
            }
        }

        vibrateIfAllowed(alertChannel)
    }

    /**
     * 按通知渠道和系统勿扰状态执行一次有限振动。
     *
     * @param alertChannel 当前提醒对应的系统通知渠道。
     *
     * @return 无返回值；渠道关闭振动、设备无振动器或处于完全勿扰时安全跳过。
     */
    private fun vibrateIfAllowed(alertChannel: NotificationChannel) {
        if (!alertChannel.shouldVibrate() ||
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        ) {
            return
        }

        val vibrator = getAlertVibrator()
        if (!vibrator.hasVibrator()) {
            return
        }
        val vibrationPattern = alertChannel.vibrationPattern
            ?.takeIf { pattern -> pattern.isNotEmpty() }
            ?: DEFAULT_VIBRATION_PATTERN
        val effect = VibrationEffect.createWaveform(vibrationPattern, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
        Log.i(TAG, "Alert vibration started")
    }

    /**
     * 获取当前Android版本对应的默认振动器。
     *
     * @return Android 12及以上返回VibratorManager默认振动器，旧系统返回传统Vibrator服务。
     */
    private fun getAlertVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    /**
     * 创建低重要性且无声无振动的前台服务渠道，避免与真正提醒通道重复发声。
     *
     * @return 无返回值；重复创建不会覆盖用户设置。
     */
    private fun createPlaybackServiceChannel() {
        val channel = NotificationChannel(
            PLAYBACK_SERVICE_CHANNEL_ID,
            "提醒声音播放状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "仅在后台播放用户选择的HarleyApp提示音时短暂显示"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建服务播放期间短暂显示的前台状态通知。
     *
     * @return 无声、不可常驻且不参与角标计数的服务通知。
     */
    private fun createPlaybackServiceNotification(): Notification {
        return Notification.Builder(applicationContext, PLAYBACK_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("HarleyApp 正在发出提醒")
            .setContentText("正在播放你选择的HarleyApp提示音")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * 停止当前铃声、移除前台状态并结束服务。
     *
     * @return 无返回值；重复调用安全。
     */
    private fun stopPlaybackAndService() {
        stopActiveSound()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Alert playback service stopped")
    }

    /**
     * 停止并释放当前App内提示音播放器。
     *
     * @return 无返回值；当前没有声音时安全跳过。
     */
    private fun stopActiveSound() {
        activeSoundPlayer?.stop()
        activeSoundPlayer = null
    }

    companion object {

        /**
         * 从任意提醒接收器启动短时前台声音服务。
         *
         * @param context Android上下文，内部转换为Application Context。
         * @param alertChannelId 用于区分普通提醒和微信提醒App内提示音选择的通知渠道ID。
         *
         * @return 系统接受前台服务启动请求返回true；后台策略拒绝或参数无效时返回false。
         */
        fun start(context: Context, alertChannelId: String): Boolean {
            if (alertChannelId.isBlank()) {
                return false
            }
            val serviceIntent = Intent(
                context.applicationContext,
                ReminderAlertPlaybackService::class.java
            ).putExtra(EXTRA_ALERT_CHANNEL_ID, alertChannelId)

            return runCatching {
                ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
                Log.i(TAG, "Alert playback foreground service requested")
                true
            }.onFailure { error ->
                Log.e(TAG, "Failed to start alert playback foreground service", error)
            }.getOrDefault(false)
        }

        private const val TAG = "ReminderAlertPlayback"
        private const val EXTRA_ALERT_CHANNEL_ID = "alert_channel_id"
        private const val PLAYBACK_SERVICE_CHANNEL_ID = "reminder_alert_playback_service_v1"
        private const val PLAYBACK_SERVICE_NOTIFICATION_ID = 91_101
        private const val MAX_PLAYBACK_DURATION_MILLIS = 5_000L
        private val DEFAULT_VIBRATION_PATTERN = longArrayOf(0L, 280L, 160L, 280L)
    }
}
