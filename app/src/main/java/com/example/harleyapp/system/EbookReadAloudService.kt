package com.example.harleyapp.system

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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.model.EbookTranslationDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 唯一持有电子书TTS控制器的前台媒体播放服务。
 *
 * 使用方法：
 * 页面先调用[EbookReadAloudPlayback.register]登记完整分页配置，再将返回token传给[start]。
 * 服务立即建立低重要性媒体通知和平台[MediaSession]，随后自动朗读初始页；页面可通过
 * [createBindingIntent]绑定并使用[LocalBinder]控制播放，也可直接收集
 * [EbookReadAloudPlayback.snapshot]。通知、锁屏和耳机键最终都进入同一主线程状态机。
 *
 * 服务采用[START_NOT_STICKY]：进程被系统终止后，进程内正文注册表同时消失，不会凭旧Intent
 * 自动恢复或突然发声。宿主仍需在AndroidManifest中把本服务声明为不导出的mediaPlayback服务。
 *
 * @return Android系统创建并管理本服务实例；业务方不应直接调用构造函数。
 */
class EbookReadAloudService : Service() {

    /** 等待音频焦点后准备提交给TTS的一次不可变请求。 */
    private data class PendingSpeechSubmission(
        val generation: Long,
        val pageIndex: Int,
        val text: String,
        val resumeOffset: Int,
        val languageCode: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localBinder = LocalBinder()
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val audioManager by lazy {
        getSystemService(AudioManager::class.java)
    }
    private val audioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
            .build()
    }
    private val offlineTranslator = EbookOfflineTranslator()
    private val translatedPages = object : LinkedHashMap<Int, String>(
        TRANSLATION_CACHE_PAGE_COUNT,
        TRANSLATION_CACHE_LOAD_FACTOR,
        true
    ) {
        /**
         * 限制服务会话内译文页缓存，避免长时间连续朗读无限占用内存。
         *
         * @param eldest 当前最久未访问的页码与译文。
         * @return 缓存超过固定页数时返回true并删除最旧项，否则返回false。
         */
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
            return size > TRANSLATION_CACHE_PAGE_COUNT
        }
    }

    private lateinit var mediaSession: MediaSession
    private lateinit var readAloudController: EbookReadAloudController
    private var registeredSession: EbookReadAloudPlayback.RegisteredSession? = null
    private var controllerState = EbookReadAloudState.INITIALIZING
    private var currentSnapshot = EbookReadAloudSnapshot()
    private var playbackGeneration = 0L
    private var preparationJob: Job? = null
    private var pendingSpeechSubmission: PendingSpeechSubmission? = null
    private var activeUtteranceId: String? = null
    private var activeUtteranceGeneration = -1L
    private var activeUtterancePage = -1
    private var activeUtteranceBaseOffset = 0
    private var playWhenReady = false
    private var resumeAfterAudioFocusGain = false
    private var audioFocusHeld = false
    private var audioFocusRequestActive = false
    private var noisyReceiverRegistered = false
    private var foregroundStarted = false
    private var stopCallbackPublished = false
    private var destroyed = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    private val noisyAudioReceiver = object : BroadcastReceiver() {

        /**
         * 耳机或蓝牙音频路线断开时暂停朗读，防止声音突然从扬声器外放。
         *
         * @param context 系统广播上下文，本实现不持有该引用。
         * @param intent 系统音频路线变化广播；未知Action会被忽略。
         * @return 无返回值；有效广播统一进入服务主线程暂停状态机。
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlaybackInternal(
                    reason = "audio route became noisy",
                    resumeOnFocusGain = false
                )
            }
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback() {

        /**
         * 处理锁屏、通知和外部媒体控制器的播放命令。
         *
         * @return 无返回值；命令在服务主线程恢复当前安全断点。
         */
        override fun onPlay() {
            playInternal()
        }

        /**
         * 处理锁屏、通知和外部媒体控制器的暂停命令。
         *
         * @return 无返回值；暂停会保存当前range起点并回退到安全词句边界。
         */
        override fun onPause() {
            pausePlaybackInternal(reason = "media session pause", resumeOnFocusGain = false)
        }

        /**
         * 处理锁屏、通知和外部媒体控制器的停止命令。
         *
         * @return 无返回值；最终快照发布后移除前台通知并结束服务。
         */
        override fun onStop() {
            stopPlaybackInternal(reason = "media session stop")
        }

        /**
         * 处理媒体控制器的上一页命令。
         *
         * @return 无返回值；播放态切页后继续播放，暂停态切页后仍保持暂停。
         */
        override fun onSkipToPrevious() {
            previousPageInternal()
        }

        /**
         * 处理媒体控制器的下一页命令。
         *
         * @return 无返回值；播放态切页后继续播放，暂停态切页后仍保持暂停。
         */
        override fun onSkipToNext() {
            nextPageInternal()
        }

        /**
         * 把平台毫秒进度映射到虚拟页时间轴并跳转到对应页。
         *
         * 使用方法：
         * 本服务把每一页表示为固定[PAGE_POSITION_UNIT_MILLIS]毫秒，避免把TTS误装成真实音频时长。
         *
         * @param pos 外部MediaController传入的虚拟毫秒位置。
         * @return 无返回值；页码会被限制到当前分页范围。
         */
        override fun onSeekTo(pos: Long) {
            val requestedPage = (pos.coerceAtLeast(0L) / PAGE_POSITION_UNIT_MILLIS)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            seekToPageInternal(requestedPage, resumeOffset = 0)
        }

        /**
         * 直接处理常见耳机媒体按键，确保没有兼容媒体库时仍能控制平台MediaSession。
         *
         * @param mediaButtonIntent Android封装的ACTION_MEDIA_BUTTON Intent。
         * @return 已识别并处理按键返回true；未知或无效事件交给平台默认实现。
         */
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val keyEvent = mediaButtonIntent.readMediaKeyEvent()
                ?: return super.onMediaButtonEvent(mediaButtonIntent)
            if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount > 0) {
                return true
            }
            return when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    playInternal()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (currentSnapshot.status == EbookReadAloudPlaybackStatus.PLAYING ||
                        currentSnapshot.status == EbookReadAloudPlaybackStatus.PREPARING
                    ) {
                        pausePlaybackInternal(
                            reason = "media button pause",
                            resumeOnFocusGain = false
                        )
                    } else {
                        playInternal()
                    }
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    pausePlaybackInternal(reason = "media button pause", resumeOnFocusGain = false)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    stopPlaybackInternal(reason = "media button stop")
                    true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    nextPageInternal()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    previousPageInternal()
                    true
                }

                else -> super.onMediaButtonEvent(mediaButtonIntent)
            }
        }
    }

    /**
     * 创建通知渠道、平台媒体会话、音频路线监听和唯一TTS控制器。
     *
     * @return 无返回值；实际前台状态要等[onStartCommand]取得有效进程内配置后建立。
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMediaSession()
        registerNoisyAudioReceiver()
        readAloudController = EbookReadAloudController(
            context = applicationContext,
            onStateChanged = ::handleControllerStateChanged,
            onSpeakingChanged = ::handleControllerSpeakingChanged,
            onUtteranceRangeChanged = ::handleUtteranceRangeChanged,
            onUtteranceFinished = ::handleUtteranceFinished,
            onUtteranceFailed = ::handleUtteranceFailed
        )
    }

    /**
     * 消费启动token或执行通知栏媒体命令。
     *
     * @param intent 启动Intent；只有首次/替换配置会携带token，控制Intent只携带显式Action。
     * @param flags Android传入的服务启动标志，本服务不依赖该值。
     * @param startId 本次服务启动编号，仅在无有效配置时用于停止当前无效启动。
     * @return 固定返回[START_NOT_STICKY]，进程和同进程正文丢失后不自动恢复旧会话。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val session = EbookReadAloudPlayback.consume(
                    intent.getStringExtra(EXTRA_CONFIG_TOKEN)
                )
                if (session == null) {
                    Log.e(TAG, "Ebook read-aloud service received an invalid configuration token")
                    if (registeredSession == null) {
                        stopSelf(startId)
                    }
                    return START_NOT_STICKY
                }
                activateRegisteredSession(session)
            }

            ACTION_PLAY -> playInternal()
            ACTION_PAUSE -> pausePlaybackInternal(
                reason = "notification pause",
                resumeOnFocusGain = false
            )
            ACTION_STOP -> stopPlaybackInternal(reason = "notification stop")
            ACTION_PREVIOUS -> previousPageInternal()
            ACTION_NEXT -> nextPageInternal()
            ACTION_SEEK -> seekToPageInternal(
                requestedPage = intent.getIntExtra(
                    EXTRA_SEEK_PAGE,
                    currentSnapshot.currentPage
                ),
                resumeOffset = intent.getIntExtra(EXTRA_SEEK_OFFSET, 0)
            )
            null -> {
                Log.w(TAG, "Ebook read-aloud service ignored a null start intent")
                if (registeredSession == null) {
                    stopSelf(startId)
                }
            }

            else -> Log.w(TAG, "Ebook read-aloud service ignored an unknown action")
        }
        if (registeredSession == null && !foregroundStarted) {
            // STOP与其他通知按钮可能已经同时排队；后到的无配置startId不能留下空的started Service。
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * 向同进程阅读页面提供状态流和完整播放控制接口。
     *
     * @param intent 页面通过[createBindingIntent]生成的显式绑定Intent，本服务不读取其附加数据。
     * @return 当前服务实例的[LocalBinder]；跨进程绑定不受支持。
     */
    override fun onBind(intent: Intent?): IBinder = localBinder

    /**
     * 服务结束时依次发布最终断点、停止TTS、释放音频焦点、译文模型、广播和MediaSession。
     *
     * @return 无返回值；显式STOP已发布过回调时不会重复调用宿主持久化接口。
     */
    override fun onDestroy() {
        destroyed = true
        val session = registeredSession
        if (session != null && !stopCallbackPublished) {
            val stoppedSnapshot = buildStoppedSnapshot()
            currentSnapshot = stoppedSnapshot
            EbookReadAloudPlayback.publish(stoppedSnapshot)
            stopCallbackPublished = true
            registeredSession = null
            publishStoppedCallback(session.progressCallback, stoppedSnapshot)
        }

        invalidateCurrentWork(stopController = true)
        abandonAudioFocus()
        serviceScope.cancel()
        offlineTranslator.close()
        unregisterNoisyAudioReceiver()
        if (::readAloudController.isInitialized) {
            readAloudController.shutdown()
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        registeredSession = null
        super.onDestroy()
    }

    /**
     * 同进程页面使用的轻量Binder。
     *
     * 使用方法：
     * ServiceConnection取得本类型后收集[snapshot]，并在按钮事件中调用[play]、[pause]、[stop]、
     * [previous]、[next]或[seekTo]。所有命令都会自动切换到主线程，调用方无需持有Service实例。
     */
    inner class LocalBinder internal constructor() : Binder() {

        /**
         * 返回进程内统一的只读播放状态流。
         *
         * @return 与[EbookReadAloudPlayback.snapshot]相同的StateFlow实例。
         */
        val snapshot: StateFlow<EbookReadAloudSnapshot>
            get() = EbookReadAloudPlayback.snapshot

        /**
         * 从当前安全断点开始或恢复朗读。
         *
         * @return 无返回值；TTS或离线译文尚未就绪时状态先进入PREPARING。
         */
        fun play() {
            dispatchToMain(::playInternal)
        }

        /**
         * 暂停并保存最新range起点对应的安全词句断点。
         *
         * @return 无返回值；已经暂停或没有会话时安全忽略。
         */
        fun pause() {
            dispatchToMain {
                pausePlaybackInternal(reason = "bound client pause", resumeOnFocusGain = false)
            }
        }

        /**
         * 停止当前会话、发布最终断点并结束前台服务。
         *
         * @return 无返回值；重复调用安全。
         */
        fun stop() {
            dispatchToMain {
                stopPlaybackInternal(reason = "bound client stop")
            }
        }

        /**
         * 切换到上一页。
         *
         * @return 无返回值；第一页调用不改变状态。
         */
        fun previous() {
            dispatchToMain(::previousPageInternal)
        }

        /**
         * 切换到下一页。
         *
         * @return 无返回值；末页调用不改变状态。
         */
        fun next() {
            dispatchToMain(::nextPageInternal)
        }

        /**
         * 跳到指定页面和可选页内文字位置。
         *
         * @param pageIndex 目标零基页码，越界值会被限制到有效范围。
         * @param resumeOffset 目标[spokenText]内UTF-16位置；原文模式可直接传原文位置，未缓存译文会从0开始。
         * @return 无返回值；播放态跳页后继续播放，暂停态跳页后保持暂停。
         */
        fun seekTo(pageIndex: Int, resumeOffset: Int = 0) {
            dispatchToMain {
                seekToPageInternal(pageIndex, resumeOffset)
            }
        }
    }

    /**
     * 建立平台MediaSession并让锁屏、耳机和通知控制共享同一回调。
     *
     * @return 无返回值；会话在服务存活期间保持激活，STOP或销毁时释放。
     */
    private fun createMediaSession() {
        mediaSession = MediaSession(applicationContext, MEDIA_SESSION_TAG).apply {
            setCallback(mediaSessionCallback, mainHandler)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackToLocal(audioAttributes)
            setSessionActivity(createOpenAppPendingIntent())
            isActive = false
        }
        updateMediaMetadata()
        updateMediaPlaybackState()
    }

    /**
     * 动态注册仅接受系统音频路线变化的广播。
     *
     * @return 无返回值；重复调用安全，Android 13及以上明确标记为不导出接收器。
     */
    private fun registerNoisyAudioReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyAudioReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    /**
     * 注销音频路线广播，防止服务结束后继续收到耳机断开事件。
     *
     * @return 无返回值；未注册时安全忽略，系统异常只写英文日志。
     */
    private fun unregisterNoisyAudioReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching {
            unregisterReceiver(noisyAudioReceiver)
        }.onFailure { error ->
            Log.e(TAG, "Failed to unregister ebook noisy-audio receiver", error)
        }
        noisyReceiverRegistered = false
    }

    /**
     * 用新注册配置替换当前会话并从初始页自动开始。
     *
     * @param session 已由一次性token从进程内注册表消费的配置与回调。
     * @return 无返回值；前台通知建立成功后才会请求音频焦点和开始TTS。
     */
    private fun activateRegisteredSession(session: EbookReadAloudPlayback.RegisteredSession) {
        val oldSession = registeredSession
        val oldStoppedSnapshot = oldSession?.let { buildStoppedSnapshot() }
        invalidateCurrentWork(stopController = true)
        abandonAudioFocus()
        translatedPages.clear()
        offlineTranslator.close()
        registeredSession = null
        if (oldSession != null && oldStoppedSnapshot != null) {
            publishStoppedCallback(oldSession.progressCallback, oldStoppedSnapshot)
        }
        registeredSession = session
        stopCallbackPublished = false
        playWhenReady = false
        resumeAfterAudioFocusGain = false
        mediaSession.isActive = true

        val config = session.config
        val initialPage = config.initialPage.coerceIn(config.pages.indices)
        currentSnapshot = EbookReadAloudSnapshot(
            bookId = config.bookId,
            title = config.title,
            author = config.author,
            status = EbookReadAloudPlaybackStatus.PAUSED,
            currentPage = initialPage,
            pageCount = config.pages.size,
            resumeOffset = 0,
            highlightStart = EbookReadAloudSnapshot.NO_HIGHLIGHT,
            highlightEnd = EbookReadAloudSnapshot.NO_HIGHLIGHT,
            spokenText = initialSpokenText(config, initialPage),
            error = null
        )
        publishSnapshot(currentSnapshot, updateSystemSurfaces = true)

        val notification = createMediaNotification()
        runCatching {
            if (!foregroundStarted) {
                startForegroundCompat(notification)
                foregroundStarted = true
            } else {
                notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
            }
        }.onSuccess {
            Log.i(TAG, "Ebook read-aloud foreground session started")
            playInternal()
        }.onFailure { error ->
            Log.e(TAG, "Failed to establish ebook read-aloud foreground service", error)
            failPlayback(
                userMessage = "无法建立后台朗读通知，请检查系统通知权限。",
                logMessage = "Ebook foreground notification could not be established",
                error = error
            )
            stopSelf()
        }
    }

    /**
     * 从当前断点开始一次新的服务级播放代际。
     *
     * @return 无返回值；没有配置、已经播放或正在准备时安全忽略。
     */
    private fun playInternal() {
        if (destroyed) return
        val session = registeredSession ?: return
        if (playWhenReady && (
                currentSnapshot.status == EbookReadAloudPlaybackStatus.PLAYING ||
                    currentSnapshot.status == EbookReadAloudPlaybackStatus.PREPARING
                )
        ) {
            return
        }

        val generation = invalidateCurrentWork(stopController = true)
        playWhenReady = true
        resumeAfterAudioFocusGain = false
        val resetCompletedPage = currentSnapshot.status == EbookReadAloudPlaybackStatus.COMPLETED ||
            currentSnapshot.resumeOffset >= currentSnapshot.spokenText.length
        val requestedOffset = if (resetCompletedPage) 0 else currentSnapshot.resumeOffset
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.PREPARING,
                resumeOffset = requestedOffset,
                highlightStart = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                highlightEnd = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                error = null
            ),
            updateSystemSurfaces = true
        )

        when (controllerState) {
            EbookReadAloudState.READY -> prepareCurrentPage(generation, requestedOffset)
            EbookReadAloudState.INITIALIZING -> Log.i(TAG, "Ebook TTS is still initializing")
            EbookReadAloudState.MISSING_OFFLINE_VOICE -> failPlayback(
                userMessage = "没有可用的离线中英文朗读音色，请先在系统中安装语音包。",
                logMessage = "Ebook playback cannot start because offline TTS voices are missing"
            )
            EbookReadAloudState.ERROR -> failPlayback(
                userMessage = "系统朗读引擎初始化失败，请稍后重试。",
                logMessage = "Ebook playback cannot start because TTS initialization failed"
            )
            EbookReadAloudState.RELEASED -> failPlayback(
                userMessage = "朗读服务已经结束，请重新开始连续朗读。",
                logMessage = "Ebook playback attempted to use a released TTS controller"
            )
        }
        Log.i(TAG, "Ebook playback requested: book=${session.config.bookId}")
    }

    /**
     * 暂停当前准备或播放任务，并把最新范围起点回退到安全词句边界。
     *
     * @param reason 仅写入英文日志的暂停原因，不包含书籍正文。
     * @param resumeOnFocusGain true表示瞬时焦点丢失后保留焦点请求并在GAIN时自动恢复。
     * @return 无返回值；非播放/准备状态不会重复发布暂停回调。
     */
    private fun pausePlaybackInternal(reason: String, resumeOnFocusGain: Boolean) {
        if (destroyed) return
        if (registeredSession == null) return
        if (currentSnapshot.status !in setOf(
                EbookReadAloudPlaybackStatus.PLAYING,
                EbookReadAloudPlaybackStatus.PREPARING
            )
        ) {
            // 瞬时焦点丢失可能先进入PAUSED，随后耳机断开或永久焦点丢失必须取消自动恢复。
            if (currentSnapshot.status == EbookReadAloudPlaybackStatus.PAUSED &&
                !resumeOnFocusGain &&
                resumeAfterAudioFocusGain
            ) {
                abandonAudioFocus()
                Log.i(TAG, "Ebook automatic resume was cancelled: reason=$reason")
            }
            return
        }

        val safeResumeOffset = currentSafeResumeOffset()
        playWhenReady = false
        resumeAfterAudioFocusGain = resumeOnFocusGain
        invalidateCurrentWork(stopController = true)
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.PAUSED,
                resumeOffset = safeResumeOffset,
                error = null
            ),
            updateSystemSurfaces = true
        )
        if (!resumeOnFocusGain) {
            abandonAudioFocus()
        }
        publishPausedCallback(registeredSession?.progressCallback, currentSnapshot)
        Log.i(TAG, "Ebook playback paused: reason=$reason")
    }

    /**
     * 停止会话并在发布最终断点后结束前台服务。
     *
     * @param reason 仅写入英文日志的停止原因，不包含书籍正文。
     * @return 无返回值；没有活动配置时仅停止空服务，已停止状态不会重复回调。
     */
    private fun stopPlaybackInternal(reason: String) {
        if (destroyed) return
        val session = registeredSession
        if (session == null) {
            stopSelf()
            return
        }
        if (stopCallbackPublished) return

        playWhenReady = false
        resumeAfterAudioFocusGain = false
        invalidateCurrentWork(stopController = true)
        abandonAudioFocus()
        val stoppedSnapshot = buildStoppedSnapshot()
        publishSnapshot(stoppedSnapshot, updateSystemSurfaces = true)
        stopCallbackPublished = true
        mediaSession.isActive = false
        registeredSession = null
        translatedPages.clear()
        offlineTranslator.close()
        publishStoppedCallback(session.progressCallback, stoppedSnapshot)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        Log.i(TAG, "Ebook playback stopped: reason=$reason")
        stopSelf()
    }

    /**
     * 切换到当前页的前一页。
     *
     * @return 无返回值；没有会话或已经在第一页时安全忽略。
     */
    private fun previousPageInternal() {
        if (registeredSession == null || currentSnapshot.currentPage <= 0) return
        seekToPageInternal(currentSnapshot.currentPage - 1, resumeOffset = 0)
    }

    /**
     * 切换到当前页的后一页。
     *
     * @return 无返回值；没有会话或已经在末页时安全忽略。
     */
    private fun nextPageInternal() {
        val session = registeredSession ?: return
        if (currentSnapshot.currentPage >= session.config.pages.lastIndex) return
        seekToPageInternal(currentSnapshot.currentPage + 1, resumeOffset = 0)
    }

    /**
     * 跳转到指定页，并严格保持跳转前的播放或暂停意图。
     *
     * @param requestedPage 目标零基页码，越界时限制到有效范围。
     * @param resumeOffset 目标实际朗读文本内的UTF-16起点，译文尚未缓存时自动归零。
     * @return 无返回值；成功切页后总会发布一次onPageChanged。
     */
    private fun seekToPageInternal(requestedPage: Int, resumeOffset: Int) {
        if (destroyed) return
        val session = registeredSession ?: return
        val config = session.config
        val targetPage = requestedPage.coerceIn(config.pages.indices)
        val shouldContinuePlaying = playWhenReady &&
            currentSnapshot.status in setOf(
                EbookReadAloudPlaybackStatus.PLAYING,
                EbookReadAloudPlaybackStatus.PREPARING
            )
        val generation = invalidateCurrentWork(stopController = true)
        playWhenReady = shouldContinuePlaying
        resumeAfterAudioFocusGain = false
        if (!shouldContinuePlaying) {
            abandonAudioFocus()
        } else if (audioFocusRequestActive && !audioFocusHeld) {
            // 取消上一页仍在等待的延迟焦点请求，防止其GAIN误触发已经换页后的旧准备工作。
            abandonAudioFocus()
        }

        val targetText = initialSpokenText(config, targetPage)
        val safeOffset = if (
            config.translationDirection != null && !translatedPages.containsKey(targetPage)
        ) {
            0
        } else {
            findEbookReadAloudResumeOffset(targetText, resumeOffset)
        }
        publishSnapshot(
            currentSnapshot.copy(
                status = if (shouldContinuePlaying) {
                    EbookReadAloudPlaybackStatus.PREPARING
                } else {
                    EbookReadAloudPlaybackStatus.PAUSED
                },
                currentPage = targetPage,
                resumeOffset = safeOffset,
                highlightStart = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                highlightEnd = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                spokenText = targetText,
                error = null
            ),
            updateSystemSurfaces = true
        )
        publishPageChangedCallback(session.progressCallback, currentSnapshot)

        if (shouldContinuePlaying) {
            prepareCurrentPage(generation, safeOffset)
        }
    }

    /**
     * 准备当前页原文或异步离线译文，并在代际仍有效时请求音频焦点。
     *
     * @param generation 发起本次准备工作的服务级代际。
     * @param requestedOffset 目标实际朗读文本内的页内UTF-16恢复位置。
     * @return 无返回值；翻译失败会进入ERROR并安全停止，不会回退成错误语言的原文朗读。
     */
    private fun prepareCurrentPage(generation: Long, requestedOffset: Int) {
        val session = registeredSession ?: return
        if (!isPlaybackGenerationCurrent(generation) || !playWhenReady) return
        val config = session.config
        val pageIndex = currentSnapshot.currentPage.coerceIn(config.pages.indices)
        val originalText = config.pages[pageIndex].text
        if (originalText.isBlank()) {
            handleEmptyPageDuringPlayback(generation)
            return
        }

        val direction = config.translationDirection
        if (direction == null) {
            prepareSpeechSubmission(
                generation = generation,
                pageIndex = pageIndex,
                text = originalText,
                requestedOffset = requestedOffset,
                languageCode = detectEbookLanguageCode(originalText)
            )
            return
        }

        translatedPages[pageIndex]?.let { translatedText ->
            prepareSpeechSubmission(
                generation = generation,
                pageIndex = pageIndex,
                text = translatedText,
                requestedOffset = requestedOffset,
                languageCode = direction.targetLanguageCode()
            )
            return
        }

        // 离线模型首次准备可能耗时较长，译文真正可提交前不占用其他媒体应用的音频焦点。
        abandonAudioFocus()
        preparationJob = serviceScope.launch {
            try {
                val translatedText = offlineTranslator.translate(originalText, direction)
                if (!isPlaybackGenerationCurrent(generation) || !playWhenReady) return@launch
                if (translatedText.isBlank()) {
                    failPlayback(
                        userMessage = "当前页译文为空，无法继续朗读。",
                        logMessage = "Offline ebook translation returned empty text"
                    )
                    return@launch
                }
                translatedPages[pageIndex] = translatedText
                prepareSpeechSubmission(
                    generation = generation,
                    pageIndex = pageIndex,
                    text = translatedText,
                    requestedOffset = 0,
                    languageCode = direction.targetLanguageCode()
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isPlaybackGenerationCurrent(generation)) {
                    failPlayback(
                        userMessage = "无法准备当前页离线译文，请确认翻译模型已经下载后重试。",
                        logMessage = "Failed to prepare offline ebook translation",
                        error = error
                    )
                }
            } finally {
                if (isPlaybackGenerationCurrent(generation)) {
                    preparationJob = null
                }
            }
        }
    }

    /**
     * 跳过空白页并保持连续播放，直到找到正文或到达书末。
     *
     * @param generation 发现空白页时的服务级代际。
     * @return 无返回值；后面存在非空页时发布一次页变，否则完成整本朗读。
     */
    private fun handleEmptyPageDuringPlayback(generation: Long) {
        val session = registeredSession ?: return
        if (!isPlaybackGenerationCurrent(generation) || !playWhenReady) return
        val nextReadablePage = ((currentSnapshot.currentPage + 1)..session.config.pages.lastIndex)
            .firstOrNull { pageIndex -> session.config.pages[pageIndex].text.isNotBlank() }
        if (nextReadablePage == null) {
            completePlayback()
        } else {
            seekToPageInternal(nextReadablePage, resumeOffset = 0)
        }
    }

    /**
     * 把已经准备好的整页文本和恢复位置封装成等待音频焦点的提交请求。
     *
     * @param generation 当前服务级代际。
     * @param pageIndex 当前零基页码。
     * @param text 当前实际要交给TTS的整页原文或译文。
     * @param requestedOffset 页内UTF-16候选恢复位置。
     * @param languageCode TTS音色使用的zh或en语言代码。
     * @return 无返回值；位置会再次限制到安全词句边界，随后请求音频焦点。
     */
    private fun prepareSpeechSubmission(
        generation: Long,
        pageIndex: Int,
        text: String,
        requestedOffset: Int,
        languageCode: String
    ) {
        if (!isPlaybackGenerationCurrent(generation) || !playWhenReady) return
        val safeOffset = findEbookReadAloudResumeOffset(text, requestedOffset)
        if (safeOffset >= text.length) {
            handleUtterancePageCompleted(generation, pageIndex)
            return
        }

        pendingSpeechSubmission = PendingSpeechSubmission(
            generation = generation,
            pageIndex = pageIndex,
            text = text,
            resumeOffset = safeOffset,
            languageCode = languageCode
        )
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.PREPARING,
                spokenText = text,
                resumeOffset = safeOffset,
                highlightStart = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                highlightEnd = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                error = null
            ),
            updateSystemSurfaces = true
        )
        requestAudioFocusAndSubmit()
    }

    /**
     * 在已经成为前台媒体服务后请求语音型媒体音频焦点。
     *
     * @return 无返回值；立即获得焦点则提交TTS，延迟焦点则保持PREPARING，失败则进入ERROR。
     */
    private fun requestAudioFocusAndSubmit() {
        if (!foregroundStarted) {
            failPlayback(
                userMessage = "后台朗读服务尚未就绪，请重新开始朗读。",
                logMessage = "Audio focus was requested before foreground service activation"
            )
            return
        }
        if (audioFocusHeld && audioFocusRequestActive) {
            submitPendingSpeech()
            return
        }

        audioFocusRequestActive = true
        val focusResult = runCatching {
            audioManager.requestAudioFocus(audioFocusRequest)
        }.getOrElse { error ->
            audioFocusRequestActive = false
            failPlayback(
                userMessage = "当前无法取得音频播放权限，请稍后重试。",
                logMessage = "Ebook audio focus request raised an exception",
                error = error
            )
            return
        }
        when (focusResult) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusHeld = true
                submitPendingSpeech()
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                audioFocusHeld = false
                Log.i(TAG, "Ebook audio focus request was delayed")
            }

            else -> {
                audioFocusRequestActive = false
                failPlayback(
                    userMessage = "当前无法取得音频播放权限，请暂停其他音频后重试。",
                    logMessage = "Ebook audio focus request was denied"
                )
            }
        }
    }

    /**
     * 在音频焦点已经获得时，把当前安全子串提交给唯一TTS控制器。
     *
     * @return 无返回值；代际、页码或播放意图已变化时丢弃旧请求，提交失败时进入ERROR。
     */
    private fun submitPendingSpeech() {
        val submission = pendingSpeechSubmission ?: return
        if (!isPlaybackGenerationCurrent(submission.generation) ||
            !playWhenReady ||
            submission.pageIndex != currentSnapshot.currentPage
        ) {
            pendingSpeechSubmission = null
            return
        }
        pendingSpeechSubmission = null

        val utteranceId = buildUtteranceId(
            generation = submission.generation,
            pageIndex = submission.pageIndex,
            resumeOffset = submission.resumeOffset
        )
        activeUtteranceId = utteranceId
        activeUtteranceGeneration = submission.generation
        activeUtterancePage = submission.pageIndex
        activeUtteranceBaseOffset = submission.resumeOffset
        val accepted = readAloudController.speak(
            text = submission.text.substring(submission.resumeOffset),
            languageCode = submission.languageCode,
            utteranceId = utteranceId,
            naturalReadingEnabled = registeredSession?.config?.naturalReadingEnabled == true
        )
        if (!accepted) {
            clearActiveUtterance()
            failPlayback(
                userMessage = "当前语言没有可用的离线朗读音色，或系统朗读引擎暂不可用。",
                logMessage = "Ebook TTS rejected the prepared speech request"
            )
        }
    }

    /**
     * 处理系统TTS初始化状态变化，并只在仍有播放意图时继续当前代际。
     *
     * @param newState 控制器回报的初始化或终止状态。
     * @return 无返回值；旧会话已暂停或停止时不会因迟到READY自行发声。
     */
    private fun handleControllerStateChanged(newState: EbookReadAloudState) {
        if (destroyed) return
        controllerState = newState
        when (newState) {
            EbookReadAloudState.READY -> {
                if (playWhenReady &&
                    currentSnapshot.status == EbookReadAloudPlaybackStatus.PREPARING &&
                    preparationJob == null &&
                    pendingSpeechSubmission == null &&
                    activeUtteranceId == null
                ) {
                    prepareCurrentPage(playbackGeneration, currentSnapshot.resumeOffset)
                }
            }

            EbookReadAloudState.MISSING_OFFLINE_VOICE -> {
                if (playWhenReady) {
                    failPlayback(
                        userMessage = "没有可用的离线中英文朗读音色，请先在系统中安装语音包。",
                        logMessage = "Ebook TTS reported missing offline voices"
                    )
                }
            }

            EbookReadAloudState.ERROR -> {
                if (playWhenReady) {
                    failPlayback(
                        userMessage = "系统朗读引擎初始化失败，请稍后重试。",
                        logMessage = "Ebook TTS initialization failed"
                    )
                }
            }

            EbookReadAloudState.INITIALIZING,
            EbookReadAloudState.RELEASED -> Unit
        }
    }

    /**
     * 在TTS真正开始发声后把PREPARING提升为PLAYING。
     *
     * @param speaking true表示控制器当前有效块已经开始；false由完成路径另行处理以避免旧stop误降状态。
     * @return 无返回值；没有当前服务级utterance时忽略迟到回调。
     */
    private fun handleControllerSpeakingChanged(speaking: Boolean) {
        if (!speaking || !playWhenReady || activeUtteranceId == null) return
        if (!isPlaybackGenerationCurrent(activeUtteranceGeneration)) return
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.PLAYING,
                error = null
            ),
            updateSystemSurfaces = true
        )
    }

    /**
     * 把控制器对子串回报的UTF-16范围映射回整页[spokenText]并发布背景高亮。
     *
     * @param utteranceId 控制器原样返回的页面级标识。
     * @param startOffset 当前提交子串内的包含式起点。
     * @param endOffsetExclusive 当前提交子串内的不包含式终点。
     * @return 无返回值；标识、代际或页码不匹配的旧回调会被丢弃。
     */
    private fun handleUtteranceRangeChanged(
        utteranceId: String,
        startOffset: Int,
        endOffsetExclusive: Int
    ) {
        if (!matchesActiveUtterance(utteranceId)) return
        val textLength = currentSnapshot.spokenText.length
        val absoluteStart = (activeUtteranceBaseOffset + startOffset).coerceIn(0, textLength)
        val absoluteEnd = (activeUtteranceBaseOffset + endOffsetExclusive)
            .coerceIn(absoluteStart, textLength)
        if (absoluteEnd <= absoluteStart) return

        // 范围回调可能逐词高频到达，只更新StateFlow；通知和MediaSession无需按词反复重建。
        publishSnapshot(
            currentSnapshot.copy(
                resumeOffset = absoluteStart,
                highlightStart = absoluteStart,
                highlightEnd = absoluteEnd
            ),
            updateSystemSurfaces = false
        )
    }

    /**
     * 处理一页完整朗读完成并自动推进到下一页。
     *
     * @param utteranceId 控制器完成回调携带的页面级标识。
     * @return 无返回值；旧代际完成事件不会翻动当前页面。
     */
    private fun handleUtteranceFinished(utteranceId: String) {
        if (!matchesActiveUtterance(utteranceId)) return
        val generation = activeUtteranceGeneration
        val pageIndex = activeUtterancePage
        clearActiveUtterance()
        handleUtterancePageCompleted(generation, pageIndex)
    }

    /**
     * 处理当前页TTS失败并停止连续推进。
     *
     * @param utteranceId 控制器失败回调携带的页面级标识。
     * @return 无返回值；旧代际失败事件不会覆盖新会话状态。
     */
    private fun handleUtteranceFailed(utteranceId: String) {
        if (!matchesActiveUtterance(utteranceId)) return
        clearActiveUtterance()
        failPlayback(
            userMessage = "系统朗读当前页失败，请检查离线音色后重试。",
            logMessage = "Ebook TTS failed while reading the active page"
        )
    }

    /**
     * 在有效页面朗读结束后继续下一页或完成整本会话。
     *
     * @param generation 完成页面所属的服务级代际。
     * @param pageIndex 完成页面的零基页码。
     * @return 无返回值；播放意图已取消或页码已变化时不再推进。
     */
    private fun handleUtterancePageCompleted(generation: Long, pageIndex: Int) {
        val session = registeredSession ?: return
        if (!isPlaybackGenerationCurrent(generation) ||
            !playWhenReady ||
            pageIndex != currentSnapshot.currentPage
        ) {
            return
        }
        if (pageIndex < session.config.pages.lastIndex) {
            seekToPageInternal(pageIndex + 1, resumeOffset = 0)
        } else {
            completePlayback()
        }
    }

    /**
     * 标记已经读到末页，释放音频焦点但保留媒体通知供用户重新播放或上一页。
     *
     * @return 无返回值；完成状态不是STOP，不会提前销毁服务或重复发布停止回调。
     */
    private fun completePlayback() {
        playWhenReady = false
        resumeAfterAudioFocusGain = false
        invalidateCurrentWork(stopController = false)
        abandonAudioFocus()
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.COMPLETED,
                resumeOffset = currentSnapshot.spokenText.length,
                highlightStart = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                highlightEnd = EbookReadAloudSnapshot.NO_HIGHLIGHT,
                error = null
            ),
            updateSystemSurfaces = true
        )
        Log.i(TAG, "Ebook playback completed")
    }

    /**
     * 处理AudioFocusRequest回调，语音在可降音量场景也选择暂停而不是压低音量继续。
     *
     * @param focusChange AudioManager回报的GAIN、瞬时丢失、可降音量丢失或永久丢失。
     * @return 无返回值；只有瞬时丢失会在GAIN后按安全断点自动恢复。
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        if (!audioFocusRequestActive) {
            Log.i(TAG, "Stale ebook audio focus callback was ignored")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusHeld = true
                val shouldResume = resumeAfterAudioFocusGain
                resumeAfterAudioFocusGain = false
                if (pendingSpeechSubmission != null && playWhenReady) {
                    submitPendingSpeech()
                } else if (shouldResume && registeredSession != null) {
                    playInternal()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioFocusHeld = false
                if (currentSnapshot.status == EbookReadAloudPlaybackStatus.PLAYING ||
                    currentSnapshot.status == EbookReadAloudPlaybackStatus.PREPARING
                ) {
                    pausePlaybackInternal(
                        reason = "transient audio focus loss",
                        resumeOnFocusGain = true
                    )
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusHeld = false
                pausePlaybackInternal(
                    reason = "permanent audio focus loss",
                    resumeOnFocusGain = false
                )
            }
        }
    }

    /**
     * 放弃当前音频焦点并清除自动恢复标志。
     *
     * @return 无返回值；尚未持有焦点时仍会尝试取消可能处于延迟队列的同一请求。
     */
    private fun abandonAudioFocus() {
        resumeAfterAudioFocusGain = false
        if (audioFocusRequestActive) {
            audioFocusRequestActive = false
            runCatching {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            }.onFailure { error ->
                Log.e(TAG, "Failed to abandon ebook audio focus", error)
            }
        }
        audioFocusHeld = false
    }

    /**
     * 统一进入可重试ERROR状态，停止当前代际并释放音频焦点。
     *
     * @param userMessage 快照和通知可显示的中文错误，不包含异常堆栈。
     * @param logMessage Logcat使用的英文错误说明，不包含书籍正文。
     * @param error 可选底层异常，仅写入Logcat便于定位。
     * @return 无返回值；服务继续保留通知，用户可以再次PLAY重试或STOP结束。
     */
    private fun failPlayback(
        userMessage: String,
        logMessage: String,
        error: Throwable? = null
    ) {
        playWhenReady = false
        resumeAfterAudioFocusGain = false
        invalidateCurrentWork(stopController = true)
        abandonAudioFocus()
        publishSnapshot(
            currentSnapshot.copy(
                status = EbookReadAloudPlaybackStatus.ERROR,
                resumeOffset = currentSafeResumeOffset(),
                error = userMessage
            ),
            updateSystemSurfaces = true
        )
        if (error == null) {
            Log.e(TAG, logMessage)
        } else {
            Log.e(TAG, logMessage, error)
        }
    }

    /**
     * 取消翻译、延迟焦点与活动utterance并递增服务代际。
     *
     * @param stopController true表示同时调用控制器stop；完成页时可传false避免多余停止。
     * @return 新代际值，调用方可将其绑定到随后异步准备与TTS请求。
     */
    private fun invalidateCurrentWork(stopController: Boolean): Long {
        preparationJob?.cancel()
        preparationJob = null
        pendingSpeechSubmission = null
        clearActiveUtterance()
        playbackGeneration = if (playbackGeneration == Long.MAX_VALUE) {
            0L
        } else {
            playbackGeneration + 1L
        }
        if (stopController && ::readAloudController.isInitialized) {
            readAloudController.stop()
        }
        return playbackGeneration
    }

    /**
     * 判断异步工作是否仍属于当前服务实例和当前播放代际。
     *
     * @param generation 待验证的代际值。
     * @return 服务未销毁且代际完全匹配返回true，否则返回false。
     */
    private fun isPlaybackGenerationCurrent(generation: Long): Boolean {
        return !destroyed && generation == playbackGeneration
    }

    /**
     * 判断控制器回调是否对应当前页、当前代际和当前utterance。
     *
     * @param utteranceId 控制器回调携带的页面级标识。
     * @return 所有身份均匹配返回true，任何旧回调返回false。
     */
    private fun matchesActiveUtterance(utteranceId: String): Boolean {
        return !destroyed &&
            utteranceId == activeUtteranceId &&
            activeUtterancePage == currentSnapshot.currentPage &&
            isPlaybackGenerationCurrent(activeUtteranceGeneration)
    }

    /**
     * 清除活动TTS标识，使随后到达的旧范围、完成或错误回调全部失效。
     *
     * @return 无返回值；不直接停止控制器。
     */
    private fun clearActiveUtterance() {
        activeUtteranceId = null
        activeUtteranceGeneration = -1L
        activeUtterancePage = -1
        activeUtteranceBaseOffset = 0
    }

    /**
     * 根据最新range起点计算暂停或停止时保存的安全断点。
     *
     * @return 当前[spokenText]中的词首或句首UTF-16位置；没有正文时返回0。
     */
    private fun currentSafeResumeOffset(): Int {
        val reportedStart = currentSnapshot.highlightStart
            .takeIf { offset -> offset != EbookReadAloudSnapshot.NO_HIGHLIGHT }
            ?: currentSnapshot.resumeOffset
        return findEbookReadAloudResumeOffset(currentSnapshot.spokenText, reportedStart)
    }

    /**
     * 构造当前会话的最终停止快照。
     *
     * @return 保留当前页、高亮和安全恢复位置，并把状态设置为STOPPED的快照。
     */
    private fun buildStoppedSnapshot(): EbookReadAloudSnapshot {
        return currentSnapshot.copy(
            status = EbookReadAloudPlaybackStatus.STOPPED,
            resumeOffset = currentSafeResumeOffset(),
            error = null
        )
    }

    /**
     * 返回某页在当前配置下已经可用的实际朗读文本。
     *
     * @param config 当前会话配置。
     * @param pageIndex 目标零基页码。
     * @return 原文模式返回原文；译文模式返回已缓存译文，尚未准备时返回空字符串。
     */
    private fun initialSpokenText(config: EbookReadAloudConfig, pageIndex: Int): String {
        return if (config.translationDirection == null) {
            config.pages[pageIndex].text
        } else {
            translatedPages[pageIndex].orEmpty()
        }
    }

    /**
     * 生成同时包含服务代际、页码和断点的页面级utterance标识。
     *
     * @param generation 当前服务代际。
     * @param pageIndex 当前零基页码。
     * @param resumeOffset 当前页内UTF-16起点。
     * @return 只用于内存回调匹配且不包含书名或正文的标识。
     */
    private fun buildUtteranceId(generation: Long, pageIndex: Int, resumeOffset: Int): String {
        return "ebook_service_${generation}_${pageIndex}_$resumeOffset"
    }

    /**
     * 发布新快照，并按需同步MediaSession和前台通知。
     *
     * @param snapshot 已完成边界检查的新状态。
     * @param updateSystemSurfaces true表示同步锁屏状态与通知；逐词高亮时传false降低系统开销。
     * @return 无返回值；StateFlow始终更新。
     */
    private fun publishSnapshot(
        snapshot: EbookReadAloudSnapshot,
        updateSystemSurfaces: Boolean
    ) {
        currentSnapshot = snapshot
        EbookReadAloudPlayback.publish(snapshot)
        if (updateSystemSurfaces && ::mediaSession.isInitialized) {
            updateMediaMetadata()
            updateMediaPlaybackState()
            if (foregroundStarted && registeredSession != null) {
                runCatching {
                    notificationManager.notify(SERVICE_NOTIFICATION_ID, createMediaNotification())
                }.onFailure { error ->
                    Log.e(TAG, "Failed to update ebook media notification", error)
                }
            }
        }
    }

    /**
     * 安全发布切页回调，隔离宿主实现异常。
     *
     * @param callback 注册时注入的可选低频进度接口。
     * @param snapshot 已包含新页码的权威快照。
     * @return 无返回值；异常只写英文日志。
     */
    private fun publishPageChangedCallback(
        callback: EbookReadAloudProgressCallback?,
        snapshot: EbookReadAloudSnapshot
    ) {
        if (callback == null) return
        runCatching {
            callback.onPageChanged(snapshot)
        }.onFailure { error ->
            Log.e(TAG, "Ebook page progress callback failed", error)
        }
    }

    /**
     * 安全发布暂停回调，隔离宿主实现异常。
     *
     * @param callback 注册时注入的可选低频进度接口。
     * @param snapshot 已包含安全断点的暂停快照。
     * @return 无返回值；异常只写英文日志。
     */
    private fun publishPausedCallback(
        callback: EbookReadAloudProgressCallback?,
        snapshot: EbookReadAloudSnapshot
    ) {
        if (callback == null) return
        runCatching {
            callback.onPlaybackPaused(snapshot)
        }.onFailure { error ->
            Log.e(TAG, "Ebook pause progress callback failed", error)
        }
    }

    /**
     * 安全发布停止回调，隔离宿主实现异常。
     *
     * @param callback 注册时注入的可选低频进度接口。
     * @param snapshot 已包含最终安全断点的停止快照。
     * @return 无返回值；异常只写英文日志。
     */
    private fun publishStoppedCallback(
        callback: EbookReadAloudProgressCallback?,
        snapshot: EbookReadAloudSnapshot
    ) {
        if (callback == null) return
        runCatching {
            callback.onPlaybackStopped(snapshot)
        }.onFailure { error ->
            Log.e(TAG, "Ebook stop progress callback failed", error)
        }
    }

    /**
     * 更新锁屏媒体卡片的书名、作者、页码和虚拟总时长。
     *
     * @return 无返回值；没有活动书籍时发布空元数据。
     */
    private fun updateMediaMetadata() {
        if (!::mediaSession.isInitialized) return
        val pageNumber = if (currentSnapshot.pageCount > 0) {
            currentSnapshot.currentPage + 1
        } else {
            0
        }
        mediaSession.setSessionActivity(createOpenAppPendingIntent())
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentSnapshot.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentSnapshot.author)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentSnapshot.title)
                .putString(
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    if (pageNumber > 0) "第 $pageNumber / ${currentSnapshot.pageCount} 页" else ""
                )
                .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, pageNumber.toLong())
                .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, currentSnapshot.pageCount.toLong())
                .putLong(
                    MediaMetadata.METADATA_KEY_DURATION,
                    currentSnapshot.pageCount.toLong() * PAGE_POSITION_UNIT_MILLIS
                )
                .build()
        )
    }

    /**
     * 更新平台PlaybackState，使锁屏、耳机和MediaController得到完整控制能力。
     *
     * @return 无返回值；SEEK使用一页等于固定毫秒数的虚拟时间轴。
     */
    private fun updateMediaPlaybackState() {
        if (!::mediaSession.isInitialized) return
        val platformState = when (currentSnapshot.status) {
            EbookReadAloudPlaybackStatus.IDLE -> PlaybackState.STATE_NONE
            EbookReadAloudPlaybackStatus.PREPARING -> PlaybackState.STATE_BUFFERING
            EbookReadAloudPlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            EbookReadAloudPlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            EbookReadAloudPlaybackStatus.STOPPED,
            EbookReadAloudPlaybackStatus.COMPLETED -> PlaybackState.STATE_STOPPED
            EbookReadAloudPlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
        }
        val playbackPosition = currentSnapshot.currentPage.toLong() * PAGE_POSITION_UNIT_MILLIS
        val builder = PlaybackState.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(
                platformState,
                playbackPosition,
                // 页码是离散虚拟时间轴，速度保持0可防止系统按真实秒数把进度错误外推到后续页面。
                0f
            )
            .setBufferedPosition(currentSnapshot.pageCount.toLong() * PAGE_POSITION_UNIT_MILLIS)
            .setActiveQueueItemId(currentSnapshot.currentPage.toLong())
        currentSnapshot.error?.let(builder::setErrorMessage)
        mediaSession.setPlaybackState(builder.build())
    }

    /**
     * 创建安静、低重要性且不计角标的电子书媒体通知渠道。
     *
     * @return 无返回值；固定渠道ID由Android安全去重，不会重复打扰用户。
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "电子书连续朗读",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "电子书在后台连续朗读时显示播放控制"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建包含上一页、播放/暂停、下一页和停止按钮的平台媒体通知。
     *
     * @return 与当前快照、MediaSession token一致的常驻公开媒体通知。
     */
    private fun createMediaNotification(): Notification {
        val isActivelyPlaying = currentSnapshot.status == EbookReadAloudPlaybackStatus.PLAYING ||
            currentSnapshot.status == EbookReadAloudPlaybackStatus.PREPARING
        val playPauseAction = if (isActivelyPlaying) {
            createNotificationAction(
                iconRes = R.drawable.ic_ebook_pause,
                title = "暂停",
                action = ACTION_PAUSE,
                requestCode = REQUEST_CODE_PAUSE
            )
        } else {
            createNotificationAction(
                iconRes = R.drawable.ic_ebook_play,
                title = "播放",
                action = ACTION_PLAY,
                requestCode = REQUEST_CODE_PLAY
            )
        }
        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return Notification.Builder(applicationContext, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ebook_notification)
            .setContentTitle(currentSnapshot.title.ifBlank { "电子书连续朗读" })
            .setContentText(notificationContentText())
            .setSubText(
                if (currentSnapshot.pageCount > 0) {
                    "第 ${currentSnapshot.currentPage + 1} / ${currentSnapshot.pageCount} 页"
                } else {
                    null
                }
            )
            .setContentIntent(createOpenAppPendingIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                createNotificationAction(
                    iconRes = R.drawable.ic_ebook_previous,
                    title = "上一页",
                    action = ACTION_PREVIOUS,
                    requestCode = REQUEST_CODE_PREVIOUS
                )
            )
            .addAction(playPauseAction)
            .addAction(
                createNotificationAction(
                    iconRes = R.drawable.ic_ebook_next,
                    title = "下一页",
                    action = ACTION_NEXT,
                    requestCode = REQUEST_CODE_NEXT
                )
            )
            .addAction(
                createNotificationAction(
                    iconRes = R.drawable.ic_ebook_stop,
                    title = "停止",
                    action = ACTION_STOP,
                    requestCode = REQUEST_CODE_STOP
                )
            )
            .setStyle(mediaStyle)
            .build()
    }

    /**
     * 根据当前状态生成简短通知正文，避免把整页书籍内容暴露在通知栏。
     *
     * @return 面向用户的状态说明或可恢复错误信息。
     */
    private fun notificationContentText(): String {
        return when (currentSnapshot.status) {
            EbookReadAloudPlaybackStatus.IDLE -> "等待开始朗读"
            EbookReadAloudPlaybackStatus.PREPARING -> "正在准备当前页朗读…"
            EbookReadAloudPlaybackStatus.PLAYING -> currentSnapshot.author
                .takeIf(String::isNotBlank)
                ?.let { author -> "正在朗读 · $author" }
                ?: "正在连续朗读"
            EbookReadAloudPlaybackStatus.PAUSED -> "朗读已暂停"
            EbookReadAloudPlaybackStatus.STOPPED -> "朗读已停止"
            EbookReadAloudPlaybackStatus.COMPLETED -> "已读完当前书籍"
            EbookReadAloudPlaybackStatus.ERROR -> currentSnapshot.error ?: "朗读发生错误"
        }
    }

    /**
     * 创建一个不可变的显式服务通知动作。
     *
     * @param iconRes 通知按钮使用的矢量图标资源。
     * @param title 无障碍和展开通知显示的中文动作名称。
     * @param action 服务内部识别的显式Action字符串。
     * @param requestCode 区分各PendingIntent的稳定请求码。
     * @return 可直接添加到Notification.Builder的动作。
     */
    private fun createNotificationAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int
    ): Notification.Action {
        val intent = Intent(applicationContext, EbookReadAloudService::class.java)
            .setAction(action)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(iconRes, title, pendingIntent).build()
    }

    /**
     * 创建点击通知后打开HarleyApp的不可变Intent。
     *
     * @return 只打开现有Activity栈、不携带书籍正文的PendingIntent。
     */
    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            currentSnapshot.bookId.takeIf(String::isNotBlank)?.let { bookId ->
                putExtra(EXTRA_OPEN_BOOK_ID, bookId)
            }
        }
        return PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 按Android版本使用带mediaPlayback类型或传统重载建立前台状态。
     *
     * @param notification 已附加当前MediaSession token的常驻媒体通知。
     * @return 无返回值；Manifest未声明对应类型时由调用方捕获系统异常并安全结束。
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    /**
     * 确保Binder命令在Service主线程顺序执行，避免与TTS、MediaSession和广播回调竞态。
     *
     * @param action 要执行的无参状态机操作。
     * @return 无返回值；已在主线程时立即执行，否则投递到主Handler。
     */
    private fun dispatchToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    companion object {

        /**
         * 媒体通知点击后交给MainActivity的当前书籍ID字段。
         *
         * 使用方法：
         * Activity只读取该轻量ID并导航到本地书籍；完整分页正文仍只通过进程内注册token交给服务。
         */
        const val EXTRA_OPEN_BOOK_ID = "ebook_read_aloud_open_book_id"

        /**
         * 请求以前台媒体服务启动一次已经注册的朗读配置。
         *
         * 使用方法：
         * 把[EbookReadAloudPlayback.register]返回的token原样传入；本Intent唯一附加字段就是token，
         * 分页正文、作者、译文选项和回调都从同进程注册表取得。系统拒绝启动时会自动撤销待消费配置。
         *
         * @param context Android上下文，内部转换为Application Context。
         * @param configToken 进程内注册表生成的一次性token。
         * @return 系统接受前台服务启动请求返回true；token为空或启动被系统拒绝返回false。
         */
        fun start(context: Context, configToken: String): Boolean {
            if (configToken.isBlank()) return false
            val applicationContext = context.applicationContext
            val intent = Intent(applicationContext, EbookReadAloudService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG_TOKEN, configToken)
            return runCatching {
                applicationContext.startForegroundService(intent)
                Log.i(TAG, "Ebook read-aloud foreground service requested")
                true
            }.onFailure { error ->
                EbookReadAloudPlayback.unregister(configToken)
                Log.e(TAG, "Failed to request ebook read-aloud foreground service", error)
            }.getOrDefault(false)
        }

        /**
         * 创建供同进程页面绑定现有朗读服务的显式Intent。
         *
         * @param context Android上下文，内部转换为Application Context。
         * @return 不包含书籍正文或其他附加字段的Service Intent。
         */
        fun createBindingIntent(context: Context): Intent {
            return Intent(context.applicationContext, EbookReadAloudService::class.java)
        }

        /**
         * 请求现有后台会话从已记录的安全断点继续播放。
         *
         * 使用方法：
         * 阅读页面已经通过[EbookReadAloudPlayback.snapshot]确认存在活动会话后调用。本函数只发送
         * 轻量显式命令，不会重新创建正文配置；通知栏和蓝牙耳机仍通过同一服务状态机处理。
         *
         * @param context 当前页面或Application上下文。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun play(context: Context): Boolean {
            return requestControl(context, ACTION_PLAY)
        }

        /**
         * 请求现有后台会话暂停，并由服务记录最新词句的安全恢复位置。
         *
         * @param context 当前页面或Application上下文。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun pause(context: Context): Boolean {
            return requestControl(context, ACTION_PAUSE)
        }

        /**
         * 停止当前后台朗读、移除常驻媒体通知并释放TTS与音频焦点。
         *
         * @param context 当前页面或Application上下文。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun stop(context: Context): Boolean {
            return requestControl(context, ACTION_STOP)
        }

        /**
         * 把现有会话切换到上一页，播放态继续播放，暂停态保持暂停。
         *
         * @param context 当前页面或Application上下文。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun previous(context: Context): Boolean {
            return requestControl(context, ACTION_PREVIOUS)
        }

        /**
         * 把现有会话切换到下一页，播放态继续播放，暂停态保持暂停。
         *
         * @param context 当前页面或Application上下文。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun next(context: Context): Boolean {
            return requestControl(context, ACTION_NEXT)
        }

        /**
         * 把现有会话跳到指定页及可选页内断点。
         *
         * 使用方法：
         * 阅读页面手动翻页、目录跳转或笔记跳转后调用；服务会限制页码和文字偏移边界，并严格保留
         * 跳转前的播放或暂停意图。译文尚未缓存时会从目标译文页开头开始，避免套用原文索引。
         *
         * @param context 当前页面或Application上下文。
         * @param pageIndex 目标零基页码，越界值由服务限制到有效范围。
         * @param resumeOffset 目标实际朗读文本内的UTF-16位置，默认从页首开始。
         * @return 系统接受控制命令返回true；服务启动受限或系统拒绝时返回false。
         */
        fun seekTo(context: Context, pageIndex: Int, resumeOffset: Int = 0): Boolean {
            val extras = Intent().apply {
                putExtra(EXTRA_SEEK_PAGE, pageIndex)
                putExtra(EXTRA_SEEK_OFFSET, resumeOffset)
            }
            return requestControl(context, ACTION_SEEK, extras)
        }

        /**
         * 向已经启动的前台朗读服务发送一个轻量显式控制命令。
         *
         * @param context 当前页面或Application上下文，内部统一使用Application Context。
         * @param action 服务内部识别的媒体控制Action。
         * @param extras 可选轻量参数Intent；仅复制其extras，不复用组件、Action或Flags。
         * @return [Context.startService]成功返回true；异常时记录英文日志并返回false。
         */
        private fun requestControl(
            context: Context,
            action: String,
            extras: Intent? = null
        ): Boolean {
            val applicationContext = context.applicationContext
            val intent = Intent(applicationContext, EbookReadAloudService::class.java)
                .setAction(action)
            extras?.extras?.let(intent::putExtras)
            return runCatching {
                applicationContext.startService(intent)
                true
            }.onFailure { error ->
                Log.e(TAG, "Failed to dispatch ebook read-aloud control action", error)
            }.getOrDefault(false)
        }

        private const val TAG = "EbookReadAloudService"
        private const val MEDIA_SESSION_TAG = "HarleyEbookReadAloud"
        private const val SERVICE_CHANNEL_ID = "ebook_read_aloud_service_v1"
        private const val SERVICE_NOTIFICATION_ID = 93_101
        private const val EXTRA_CONFIG_TOKEN = "ebook_read_aloud_config_token"
        private const val ACTION_START = "com.example.harleyapp.ebook.action.START"
        private const val ACTION_PLAY = "com.example.harleyapp.ebook.action.PLAY"
        private const val ACTION_PAUSE = "com.example.harleyapp.ebook.action.PAUSE"
        private const val ACTION_STOP = "com.example.harleyapp.ebook.action.STOP"
        private const val ACTION_PREVIOUS = "com.example.harleyapp.ebook.action.PREVIOUS"
        private const val ACTION_NEXT = "com.example.harleyapp.ebook.action.NEXT"
        private const val ACTION_SEEK = "com.example.harleyapp.ebook.action.SEEK"
        private const val EXTRA_SEEK_PAGE = "ebook_read_aloud_seek_page"
        private const val EXTRA_SEEK_OFFSET = "ebook_read_aloud_seek_offset"
        private const val PAGE_POSITION_UNIT_MILLIS = 1_000L
        private const val TRANSLATION_CACHE_PAGE_COUNT = 8
        private const val TRANSLATION_CACHE_LOAD_FACTOR = 0.75f
        private const val REQUEST_CODE_OPEN_APP = 93_100
        private const val REQUEST_CODE_PREVIOUS = 93_102
        private const val REQUEST_CODE_PLAY = 93_103
        private const val REQUEST_CODE_PAUSE = 93_104
        private const val REQUEST_CODE_NEXT = 93_105
        private const val REQUEST_CODE_STOP = 93_106
        private val MEDIA_SESSION_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SEEK_TO
    }
}

/**
 * 从媒体按钮Intent兼容读取KeyEvent。
 *
 * 使用方法：
 * 仅供[EbookReadAloudService]的MediaSession回调调用；Android 13及以上使用类型安全重载，旧系统
 * 使用兼容重载。函数不读取或保留其他Intent字段。
 *
 * @return 存在合法KeyEvent时返回该事件，否则返回null。
 */
private fun Intent.readMediaKeyEvent(): KeyEvent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }
}

/**
 * 返回离线翻译目标语言对应的TTS语言代码。
 *
 * @return 英译中返回zh，中译英返回en。
 */
private fun EbookTranslationDirection.targetLanguageCode(): String {
    return when (this) {
        EbookTranslationDirection.ENGLISH_TO_CHINESE -> "zh"
        EbookTranslationDirection.CHINESE_TO_ENGLISH -> "en"
    }
}
