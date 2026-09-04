package com.example.harleyapp.charging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R

/**
 * 在用户明确开启功能后常驻监听充电器连接，并触发2.5秒未来感充电页面。
 *
 * 使用方法：
 * 只能通过[start]启动、通过[stop]停止。服务建立低重要性常驻通知后动态监听电源连接、电源
 * 断开和电池状态广播；同一次连接只触发一次效果，服务被系统回收后若仍处于同一充电周期也
 * 不会重复弹出。用户强行停止应用时Android会停止所有组件，这是系统不可绕过的边界。
 */
class ChargingMonitorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val batteryReader by lazy {
        ChargingBatteryReader(applicationContext)
    }
    private val preferences by lazy {
        ChargingEffectPreferences(applicationContext)
    }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private var receiverRegistered = false
    private var connectionActive = false
    private var effectHandledForConnection = false

    private val delayedConnectionCheck = Runnable {
        val snapshot = batteryReader.read()
        if (snapshot.isConnected) {
            handleConnectionState(connected = true)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {

        /**
         * 接收系统电源与电池变化，并把不同广播统一为连接状态机。
         *
         * @param context 广播接收上下文，本实现不直接持有该实例。
         * @param intent 系统广播，支持电源连接、断开和电池状态变化。
         * @return 无返回值；未知Action会被安全忽略。
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    // 部分设备先发送连接广播、稍后才刷新粘性电池数据，短暂延迟后再确认。
                    mainHandler.removeCallbacks(delayedConnectionCheck)
                    mainHandler.postDelayed(delayedConnectionCheck, CONNECTION_CONFIRM_DELAY_MILLIS)
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    mainHandler.removeCallbacks(delayedConnectionCheck)
                    handleConnectionState(connected = false)
                }

                Intent.ACTION_BATTERY_CHANGED -> {
                    handleConnectionState(connected = batteryReader.read().isConnected)
                }
            }
        }
    }

    /**
     * 创建通知渠道；真正前台状态在[onStartCommand]确认开关仍启用后建立。
     *
     * @return 无返回值。
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * 建立常驻前台状态并注册系统充电广播。
     *
     * @param intent 启动Intent；系统恢复粘性服务时允许为null。
     * @param flags Android服务启动标志，本服务不依赖该值。
     * @param startId 本次启动编号，用于Android内部区分重复请求。
     * @return 开关有效时返回[START_STICKY]请求系统回收后恢复，否则返回[START_NOT_STICKY]。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!preferences.isEnabled()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForegroundCompat(createServiceNotification())
        registerBatteryReceiverIfNeeded()
        return START_STICKY
    }

    /**
     * 本服务不向其他组件提供绑定接口。
     *
     * @param intent Android传入的绑定Intent，本实现不使用。
     * @return 固定返回null，调用方应使用[start]启动服务。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 注销广播与延迟任务，防止服务结束后继续持有系统资源。
     *
     * @return 无返回值。
     */
    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedConnectionCheck)
        if (receiverRegistered) {
            runCatching {
                unregisterReceiver(batteryReceiver)
            }.onFailure { error ->
                Log.e(TAG, "Failed to unregister charging receiver", error)
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    /**
     * 合并电源广播与电池广播产生的连接状态，保证每次插入只显示一次效果。
     *
     * @param connected 当前是否已连接任何外部供电来源。
     * @return 无返回值；从未连接切换到已连接时尝试启动充电效果页面。
     */
    private fun handleConnectionState(connected: Boolean) {
        if (!connected) {
            connectionActive = false
            effectHandledForConnection = false
            return
        }

        if (!connectionActive) {
            connectionActive = true
            effectHandledForConnection = false
        }
        if (effectHandledForConnection) {
            return
        }

        effectHandledForConnection = true
        showChargingEffect()
    }

    /**
     * 检查悬浮窗授权后，从后台启动能够点亮屏幕并显示在锁屏上的短时效果页面。
     *
     * @return 无返回值；权限被撤销或系统拒绝后台启动时只记录英文日志并保留常驻服务。
     */
    private fun showChargingEffect() {
        if (!Settings.canDrawOverlays(applicationContext)) {
            Log.e(TAG, "Charging effect skipped because overlay permission is missing")
            return
        }

        runCatching {
            startActivity(
                ChargingEffectActivity.createIntent(
                    context = applicationContext,
                    preview = false
                )
            )
            Log.i(TAG, "Charging effect activity requested")
        }.onFailure { error ->
            Log.e(TAG, "Failed to launch charging effect activity", error)
        }
    }

    /**
     * 首次启动时注册系统充电广播，并以当前供电状态作为基线避免服务重启重复弹出。
     *
     * @return 无返回值；重复调用安全。
     */
    private fun registerBatteryReceiverIfNeeded() {
        if (receiverRegistered) {
            return
        }

        val initialSnapshot = batteryReader.read()
        connectionActive = initialSnapshot.isConnected
        effectHandledForConnection = initialSnapshot.isConnected
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
        receiverRegistered = true
        Log.i(TAG, "Charging monitor receiver registered")
    }

    /**
     * 按Android版本使用带类型或传统重载建立前台服务状态。
     *
     * @param notification 持续显示监控状态和关闭入口的通知。
     * @return 无返回值；系统不接受声明时由调用栈抛出异常，便于现场日志定位。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    /**
     * 创建安静、低重要性且不显示角标的常驻监控通知渠道。
     *
     * @return 无返回值；Android系统会按固定渠道ID安全去重。
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "充电动画监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "插入充电器时显示2.5秒HarleyApp充电效果"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建服务运行期间持续显示的前台通知。
     *
     * @return 点击后打开HarleyApp、无声且不重复提醒的常驻通知。
     */
    private fun createServiceNotification(): Notification {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(applicationContext, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_charging)
            .setContentTitle("充电动画监控已开启")
            .setContentText("插入电源时显示2.5秒未来感充电效果")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {

        /**
         * 请求启动全天充电监控前台服务。
         *
         * @param context Android上下文，内部转换为Application Context。
         * @return 系统接受启动请求返回true；后台策略拒绝或组件异常时返回false。
         */
        fun start(context: Context): Boolean {
            val applicationContext = context.applicationContext
            return runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, ChargingMonitorService::class.java)
                )
                Log.i(TAG, "Charging monitor foreground service requested")
                true
            }.onFailure { error ->
                Log.e(TAG, "Failed to start charging monitor foreground service", error)
            }.getOrDefault(false)
        }

        /**
         * 停止全天充电监控并移除前台通知。
         *
         * @param context Android上下文，内部转换为Application Context。
         * @return 系统找到并请求停止服务时返回true；服务原本未运行时可能返回false。
         */
        fun stop(context: Context): Boolean {
            val applicationContext = context.applicationContext
            val stopped = applicationContext.stopService(
                Intent(applicationContext, ChargingMonitorService::class.java)
            )
            Log.i(TAG, "Charging monitor foreground service stop requested")
            return stopped
        }

        private const val TAG = "ChargingMonitor"
        private const val SERVICE_CHANNEL_ID = "charging_monitor_service_v1"
        private const val SERVICE_NOTIFICATION_ID = 92_501
        private const val CONNECTION_CONFIRM_DELAY_MILLIS = 180L
    }
}
