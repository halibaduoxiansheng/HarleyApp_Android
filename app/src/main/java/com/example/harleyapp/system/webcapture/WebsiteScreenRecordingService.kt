package com.example.harleyapp.system.webcapture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录制当前网页视频全屏画面并在停止后自动保存到系统视频库的前台服务。
 *
 * 使用方法：
 * 页面每次通过MediaProjection系统授权后调用[createStartIntent]并用startForegroundService启动；
 * 用户再次点击录屏按钮或通知“停止并保存”时发送[createStopIntent]。服务只使用一次投屏令牌和一次
 * createVirtualDisplay，适配Android 14及以上约束；视频采用屏幕原始像素范围内设备支持的最高H.264
 * 配置，系统声音仅在Android 10以上且播放方允许内录时附加，不录麦克风。
 */
class WebsiteScreenRecordingService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private var ownerTabId: String? = null
    private var foregroundStarted = false
    private var stopping = false
    private var cleanupCompleted = false
    private var startedAtElapsedRealtime: Long? = null
    private var videoStartedAtElapsedRealtimeNanos: Long? = null
    private var audioStartOffsetMicros = 0L
    private var videoConfiguration: WebsiteRecordingVideoConfiguration? = null
    private var videoRecorder: MediaRecorder? = null
    private var playbackAudioRecorder: PlaybackAudioMp4Recorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var videoTempFile: File? = null
    private var audioTempFile: File? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        /** 系统撤销投屏、锁屏或启动其他投屏会话时停止并保存已经完成的有效片段。 */
        override fun onStop() {
            if (!stopping) {
                requestStop("Media projection was revoked")
            }
        }
    }

    /**
     * 接收开始或停止命令。
     *
     * @param intent 页面或通知发送的显式命令。
     * @param flags Android服务启动标志，本实现不依赖。
     * @param startId 当前启动序号。
     * @return [START_NOT_STICKY]，禁止系统重启后复用失效授权。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> requestStop("User requested stop")
            else -> {
                Log.w(TAG, "Ignoring website recording command without a known action")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** @return 本服务不支持绑定，始终返回null。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 初始化通知、一次性投屏、视频编码器和可选播放声音编码器。
     *
     * @param intent 包含系统投屏授权、标签标识和当前全屏尺寸的启动Intent。
     * @return 无返回值；任一步失败都会进入统一清理且不留下半成品。
     */
    private fun startRecording(intent: Intent) {
        if (foregroundStarted) {
            Log.w(TAG, "Ignoring duplicate website recording start request")
            return
        }
        val requestedOwnerTabId = intent.getStringExtra(EXTRA_OWNER_TAB_ID).orEmpty()
        ownerTabId = requestedOwnerTabId
        try {
            createNotificationChannel()
            startForegroundForProjection(buildNotification("正在准备最高可用画质…", true))
            foregroundStarted = true
            WebsiteRecordingSessionStore.publishStarting(requestedOwnerTabId)

            val resultCode = intent.getIntExtra(
                EXTRA_PROJECTION_RESULT_CODE,
                Activity.RESULT_CANCELED
            )
            val projectionData = intent.readProjectionData()
            check(resultCode == Activity.RESULT_OK && projectionData != null) {
                "Media projection permission data is missing"
            }
            val requestedWidth = intent.getIntExtra(EXTRA_CAPTURE_WIDTH, 0)
            val requestedHeight = intent.getIntExtra(EXTRA_CAPTURE_HEIGHT, 0)
            val densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, 0)
            check(requestedWidth > 0 && requestedHeight > 0 && densityDpi > 0) {
                "Capture dimensions are invalid"
            }
            val configuration = selectHighestWebsiteRecordingConfiguration(
                sourceWidth = requestedWidth,
                sourceHeight = requestedHeight
            )
            videoConfiguration = configuration
            val sessionSuffix = "${System.currentTimeMillis()}_${System.nanoTime()}"
            val recordingDirectory = File(cacheDir, TEMP_DIRECTORY_NAME).apply { mkdirs() }
            val newVideoTempFile = File(recordingDirectory, "video_$sessionSuffix.mp4")
            val newAudioTempFile = File(recordingDirectory, "audio_$sessionSuffix.mp4")
            videoTempFile = newVideoTempFile
            audioTempFile = newAudioTempFile
            val recorder = createWebsiteVideoRecorder(newVideoTempFile, configuration)
            videoRecorder = recorder

            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, projectionData)
                ?: error("Unable to create MediaProjection")
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            val display = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                configuration.width,
                configuration.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                mainHandler
            )
            virtualDisplay = display
            recorder.start()
            startedAtElapsedRealtime = SystemClock.elapsedRealtime()
            videoStartedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            val requestAudio = intent.getBooleanExtra(EXTRA_INCLUDE_PLAYBACK_AUDIO, false)
            val audioStarted = if (requestAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    PlaybackAudioMp4Recorder.create(
                        context = applicationContext,
                        projection = projection,
                        outputFile = newAudioTempFile
                    ).also { audioRecorder ->
                        check(audioRecorder.start()) { "Playback audio encoder did not start" }
                        playbackAudioRecorder = audioRecorder
                        audioStartOffsetMicros = audioRecorder.startedAtElapsedRealtimeNanos
                            ?.let { audioStartNanos ->
                                val videoStartNanos = videoStartedAtElapsedRealtimeNanos
                                    ?: audioStartNanos
                                ((audioStartNanos - videoStartNanos) / NANOS_PER_MICROSECOND)
                                    .coerceAtLeast(0L)
                            }
                            ?: 0L
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Website recording will continue without playback audio", error)
                    playbackAudioRecorder = null
                    newAudioTempFile.delete()
                }.isSuccess
            } else {
                false
            }

            WebsiteRecordingSessionStore.publishRecording(
                ownerTabId = requestedOwnerTabId,
                startedAtElapsedRealtime = requireNotNull(startedAtElapsedRealtime),
                width = configuration.width,
                height = configuration.height,
                frameRate = configuration.frameRate,
                audioIncluded = audioStarted
            )
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    statusText = if (audioStarted) {
                        "${configuration.width}×${configuration.height} · 正在录制画面和系统声音"
                    } else {
                        "${configuration.width}×${configuration.height} · 正在录制高画质画面"
                    },
                    showStopAction = true
                )
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start website screen recording", error)
            WebsiteRecordingSessionStore.publishError(
                ownerTabId = ownerTabId,
                message = recordingErrorMessage(error, starting = true)
            )
            requestStop("Recording startup failed", preservePublishedError = true)
        }
    }

    /**
     * 只允许一个停止流程，立即更新界面和通知后在IO线程完成编码器关闭与自动保存。
     *
     * @param reason 只写入英文日志的内部停止原因。
     * @param preservePublishedError true表示启动路径已经发布具体错误，不改成“正在保存”。
     * @return 无返回值。
     */
    private fun requestStop(reason: String, preservePublishedError: Boolean = false) {
        if (stopping) return
        stopping = true
        Log.i(TAG, "Stopping website screen recording: $reason")
        if (!preservePublishedError && startedAtElapsedRealtime != null) {
            WebsiteRecordingSessionStore.publishSaving()
            if (foregroundStarted) {
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildNotification("正在自动保存到相册…", false)
                )
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            stopAndFinalize(preservePublishedError)
        }
    }

    /**
     * 依次停止音频、视频、VirtualDisplay和投屏，并把有效片段写入MediaStore。
     *
     * @param preservePublishedError 是否保留启动阶段已经发布的错误。
     * @return 无返回值。
     */
    private suspend fun stopAndFinalize(preservePublishedError: Boolean) {
        val audioFile = stopPlaybackAudioRecorderSafely()
        playbackAudioRecorder = null

        val recorder = videoRecorder
        val videoStopped = if (recorder != null && startedAtElapsedRealtime != null) {
            runCatching {
                recorder.stop()
                true
            }.onFailure { error ->
                Log.e(TAG, "Failed to stop website video recorder", error)
            }.getOrDefault(false)
        } else {
            false
        }
        runCatching { recorder?.release() }
        videoRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        val videoFile = videoTempFile
        val elapsedMillis = startedAtElapsedRealtime?.let { startedAt ->
            SystemClock.elapsedRealtime() - startedAt
        } ?: 0L
        if (!preservePublishedError) {
            if (!videoStopped || videoFile == null || !videoFile.isFile || videoFile.length() <= 0L) {
                WebsiteRecordingSessionStore.publishError(
                    ownerTabId,
                    "录屏时间过短或编码器未能生成视频，请重新开始后稍等再停止"
                )
            } else if (elapsedMillis < MIN_RECORDING_DURATION_MILLIS) {
                WebsiteRecordingSessionStore.publishError(
                    ownerTabId,
                    "录屏时间过短，请至少录制1秒后再停止"
                )
            } else {
                val saveResult = saveRecording(videoFile, audioFile)
                if (saveResult == null) {
                    WebsiteRecordingSessionStore.publishError(
                        ownerTabId,
                        "录屏保存失败，请检查存储空间后重试"
                    )
                } else {
                    WebsiteRecordingSessionStore.publishSaved(
                        savedUri = saveResult.first,
                        audioIncluded = saveResult.second
                    )
                }
            }
        }

        videoTempFile?.delete()
        audioTempFile?.delete()
        videoTempFile = null
        audioTempFile = null
        withContext(Dispatchers.Main.immediate) {
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            cleanupCompleted = true
            stopSelf()
        }
    }

    /**
     * 在Android 10及以上停止可选的系统播放声音采集器，并等待AAC文件封装结束。
     *
     * 使用方法：仅由统一停止流程调用；低版本没有播放声音内录能力，直接返回null。函数会在内部
     * 吸收音频编码器的停止异常，使画面轨仍可继续保存为无声视频。
     *
     * @return 有效的AAC/MP4临时音轨；未启用、版本不支持或停止失败时返回null。
     */
    private fun stopPlaybackAudioRecorderSafely(): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { playbackAudioRecorder?.stopAndJoin() }
            .onFailure { error -> Log.e(TAG, "Failed to stop playback audio recorder", error) }
            .getOrNull()
    }

    /**
     * 创建系统视频库待发布条目并写入画面轨和可选声音轨。
     *
     * @param videoFile 已停止且有效的画面临时文件。
     * @param audioFile 可选AAC临时音轨。
     * @return 保存Uri和是否成功包含声音轨；失败返回null并删除半成品。
     */
    private fun saveRecording(videoFile: File, audioFile: File?): Pair<Uri, Boolean>? {
        val firstAttempt = writeRecordingToMediaStore(
            videoFile = videoFile,
            audioFile = audioFile,
            audioStartOffsetMicros = audioStartOffsetMicros
        )
        if (firstAttempt != null || audioFile == null) return firstAttempt

        Log.w(TAG, "Retrying website recording save without playback audio")
        return writeRecordingToMediaStore(videoFile, null, 0L)
    }

    /**
     * 执行一次MediaStore创建、轨道封装和发布。
     *
     * @return 保存Uri和是否包含声音；异常时删除对应MediaStore条目并返回null。
     */
    private fun writeRecordingToMediaStore(
        videoFile: File,
        audioFile: File?,
        audioStartOffsetMicros: Long
    ): Pair<Uri, Boolean>? {
        val resolver = contentResolver
        val displayName = "Harley_recording_${timestamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$MEDIA_FOLDER_NAME"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val publicDirectory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    MEDIA_FOLDER_NAME
                )
                if (!publicDirectory.exists() && !publicDirectory.mkdirs()) {
                    return null
                }
                put(MediaStore.Video.Media.DATA, File(publicDirectory, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val audioIncluded = resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                muxWebsiteRecording(
                    videoFile = videoFile,
                    audioFile = audioFile,
                    destination = descriptor.fileDescriptor,
                    audioStartOffsetMicros = audioStartOffsetMicros
                )
            } ?: error("Unable to open MediaStore video destination")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            uri to audioIncluded
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to publish website screen recording", error)
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Android销毁服务时释放所有仍存活资源，不尝试复用或静默重启录屏。 */
    override fun onDestroy() {
        serviceScope.cancel()
        if (!cleanupCompleted) {
            stopPlaybackAudioRecorderSafely()
            runCatching { videoRecorder?.stop() }
            runCatching { videoRecorder?.release() }
            runCatching { virtualDisplay?.release() }
            runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
            runCatching { mediaProjection?.stop() }
            videoTempFile?.delete()
            audioTempFile?.delete()
            if (!WebsiteRecordingSessionStore.snapshot.value.let { snapshot ->
                    snapshot.status == WebsiteRecordingStatus.SAVED ||
                        snapshot.status == WebsiteRecordingStatus.ERROR
                }
            ) {
                WebsiteRecordingSessionStore.publishError(
                    ownerTabId,
                    "录屏服务被系统结束，本次视频未能完整保存"
                )
            }
        }
        super.onDestroy()
    }

    /** @return 创建低重要性、持续显示的网页录屏通知渠道。 */
    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "网页视频录屏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示网页视频录屏状态，并提供停止和自动保存入口"
                setShowBadge(false)
            }
        )
    }

    /**
     * 创建录屏持续通知。
     *
     * @param statusText 当前录制或保存状态。
     * @param showStopAction true显示“停止并保存”动作。
     * @return 可交给startForeground或通知管理器的Notification。
     */
    private fun buildNotification(statusText: String, showStopAction: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_translation_notification)
            .setContentTitle("网页视频录屏")
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(showStopAction)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (showStopAction) {
            val stopIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_STOP,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_live_translation_notification),
                    "停止并保存",
                    stopIntent
                ).build()
            )
        }
        return builder.build()
    }

    /** @return 以前台mediaProjection类型启动通知，满足Android 10及以上服务要求。 */
    private fun startForegroundForProjection(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 把启动异常转换为不泄漏内部路径的中文说明。
     *
     * @param error 启动或编码异常。
     * @param starting true表示发生在初始化阶段。
     * @return 面向用户的恢复建议。
     */
    private fun recordingErrorMessage(error: Throwable, starting: Boolean): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "projection" in message -> "系统录屏授权已失效，请重新点击录屏并允许授权"
            "dimension" in message || "size" in message -> "当前全屏尺寸不受编码器支持，请旋转屏幕后重试"
            "permission" in message -> "录屏或存储权限已失效，请重新授权"
            starting -> "高画质录屏启动失败，请关闭其他录屏应用后重试"
            else -> "录屏保存失败，请检查存储空间后重试"
        }
    }

    /** @return 用于相册文件名且不含路径特殊字符的本地时间戳。 */
    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    companion object {
        private const val TAG = "WebsiteRecording"
        private const val ACTION_START = "com.example.harleyapp.action.START_WEBSITE_RECORDING"
        private const val ACTION_STOP = "com.example.harleyapp.action.STOP_WEBSITE_RECORDING"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val EXTRA_OWNER_TAB_ID = "owner_tab_id"
        private const val EXTRA_CAPTURE_WIDTH = "capture_width"
        private const val EXTRA_CAPTURE_HEIGHT = "capture_height"
        private const val EXTRA_DENSITY_DPI = "density_dpi"
        private const val EXTRA_INCLUDE_PLAYBACK_AUDIO = "include_playback_audio"
        private const val NOTIFICATION_CHANNEL_ID = "website_screen_recording"
        private const val NOTIFICATION_ID = 4_410
        private const val REQUEST_CODE_OPEN_APP = 4_411
        private const val REQUEST_CODE_STOP = 4_412
        private const val VIRTUAL_DISPLAY_NAME = "HarleyWebsiteRecording"
        private const val TEMP_DIRECTORY_NAME = "website_recording"
        private const val MEDIA_FOLDER_NAME = "HarleyApp"
        private const val VIDEO_MIME_TYPE = "video/mp4"
        private const val MIN_RECORDING_DURATION_MILLIS = 1_000L
        private const val NANOS_PER_MICROSECOND = 1_000L

        /**
         * 创建只供本应用启动一次网页录屏会话的显式Intent。
         *
         * @param context Android上下文。
         * @param resultCode 系统MediaProjection授权返回码。
         * @param projectionData 系统返回的一次性授权数据。
         * @param ownerTabId 发起录屏的网站标签标识。
         * @param captureWidth 当前全屏内容宽度。
         * @param captureHeight 当前全屏内容高度。
         * @param densityDpi 当前显示密度DPI。
         * @param includePlaybackAudio true尝试附加系统播放声音。
         * @return 可交给ContextCompat.startForegroundService的显式Intent。
         */
        fun createStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            ownerTabId: String,
            captureWidth: Int,
            captureHeight: Int,
            densityDpi: Int,
            includePlaybackAudio: Boolean
        ): Intent {
            return Intent(context, WebsiteScreenRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
                .putExtra(EXTRA_OWNER_TAB_ID, ownerTabId)
                .putExtra(EXTRA_CAPTURE_WIDTH, captureWidth)
                .putExtra(EXTRA_CAPTURE_HEIGHT, captureHeight)
                .putExtra(EXTRA_DENSITY_DPI, densityDpi)
                .putExtra(EXTRA_INCLUDE_PLAYBACK_AUDIO, includePlaybackAudio)
        }

        /**
         * 创建页面或通知用于停止并自动保存当前录屏的显式Intent。
         *
         * @param context Android上下文。
         * @return Action为停止命令且目标固定为本服务的Intent。
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, WebsiteScreenRecordingService::class.java)
                .setAction(ACTION_STOP)
        }

        /**
         * 启动一次网页录屏前台服务。
         *
         * @param context Android上下文。
         * @param startIntent 由[createStartIntent]构建的显式Intent。
         * @return 无返回值。
         */
        fun start(context: Context, startIntent: Intent) {
            ContextCompat.startForegroundService(context, startIntent)
        }

        /**
         * 请求当前服务停止录制并自动保存。
         *
         * @param context Android上下文。
         * @return 无返回值。
         */
        fun stop(context: Context) {
            context.startService(createStopIntent(context))
        }
    }
}

/** @return 兼容Android 13前后的方式读取MediaProjection授权Intent。 */
private fun Intent.readProjectionData(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra("projection_data", Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra("projection_data")
    }
}
