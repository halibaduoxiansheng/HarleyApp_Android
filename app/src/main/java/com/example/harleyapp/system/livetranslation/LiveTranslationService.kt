package com.example.harleyapp.system.livetranslation

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationSettings
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 捕获其他播放器允许内录的声音，并在本机完成英/日识别、中文翻译和悬浮字幕显示的前台服务。
 *
 * 使用方法：
 * 可见页面每次通过系统MediaProjection授权后调用[createStartIntent]并启动前台服务。服务采用
 * [START_NOT_STICKY]，不会在进程被回收后拿旧令牌自动恢复。AudioRecord、VAD、ASR、翻译器和浮层
 * 由本服务单独持有；停止按钮、通知动作或投屏撤销最终都进入同一清理路径。PCM只存在有界内存
 * 缓冲中，不写文件、不上传，日志也不包含原文或译文。
 */
class LiveTranslationService : Service() {

    /** 待翻译final字幕及其会话代际，防止迟到结果跨会话发布。 */
    private data class TranslationRequest(
        val generation: Long,
        val sourceText: String
    )

    /**
     * 识别线程消费的一块PCM及其前方是否存在被丢弃的时间断层。
     *
     * @param samples 连续的16位单声道PCM。
     * @param discontinuityBefore true表示本块之前至少有一块因队列满而丢弃，VAD必须先收尾并重置。
     */
    private data class AudioPacket(
        val samples: ShortArray,
        val discontinuityBefore: Boolean
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recognitionDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Harley-Live-ASR").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val modelManager by lazy {
        LiveTranslationModelManager.get(applicationContext)
    }

    private var generation = 0L
    private var foregroundStarted = false
    private var stopping = false
    private var cleanupCompleted = false
    private var terminalErrorMessage: String? = null
    private var mediaProjection: MediaProjection? = null
    private var playbackCapture: PlaybackAudioCapture? = null
    private var overlayController: LiveTranslationOverlayController? = null
    private var overlayJob: Job? = null
    private var captureJob: Job? = null
    private var recognitionJob: Job? = null
    private var translationJob: Job? = null
    private var captionExpiryJob: Job? = null
    private var audioChannel: Channel<AudioPacket>? = null
    private var translationChannel: Channel<TranslationRequest>? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        /**
         * 系统或用户撤销本次内录授权时立即结束整个翻译会话。
         *
        * @return 无返回值。
         */
        override fun onStop() {
            if (stopping) return
            LiveTranslationSessionStore.publishProjectionRevoked()
            requestStop(
                reason = "media projection was revoked",
                errorMessage = "系统内录授权已结束，请返回实时翻译页面重新开始"
            )
        }
    }

    /**
     * 接收启动或停止命令；启动命令会先满足Android前台服务时限，再异步加载大模型。
     *
     * @param intent 控制器或通知创建的服务Intent。
     * @param flags Android服务启动标志，本实现不依赖。
     * @param startId 当前启动序号。
     * @return [START_NOT_STICKY]，禁止系统用失效投屏令牌自动重启。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop(reason = "user requested stop")
            ACTION_START -> startSession(intent)
            else -> {
                Log.w(TAG, "Ignoring live translation service command without a known action")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** @return 本服务不支持绑定，始终返回null。 */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 播放器横竖屏切换时把字幕重新限制在新屏幕范围内。
     *
     * @param newConfig Android发布的新配置。
     * @return 无返回值。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.ensureVisibleAfterConfigurationChange()
    }

    /**
     * 启动一次全新的捕获与翻译会话。
     *
     * @param intent 包含本次投屏授权与完整设置的启动Intent。
     * @return 无返回值；初始化失败会自动进入统一错误清理。
     */
    private fun startSession(intent: Intent) {
        if (foregroundStarted || !LiveTranslationSessionStore.snapshot.value.canStart) {
            Log.w(TAG, "Ignoring duplicate live translation start request")
            return
        }
        try {
            createNotificationChannel()
            startForegroundForProjection(buildNotification("正在初始化本地模型…"))
            foregroundStarted = true
            LiveTranslationSessionStore.publishStarting()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw UnsupportedOperationException("Playback capture requires Android 10 or newer")
            }
            check(
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "Record audio permission is not granted" }
            check(Settings.canDrawOverlays(this)) { "Overlay permission is not granted" }
            check(modelManager.snapshot.value.isReady) { "Offline models are not ready" }

            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
            val projectionData = intent.readProjectionData()
            check(resultCode == Activity.RESULT_OK && projectionData != null) {
                "Media projection permission data is missing"
            }
            val settings = intent.readSettings()
            val currentGeneration = nextGeneration()
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, projectionData)
                ?: error("Unable to create MediaProjection")
            projection.registerCallback(projectionCallback, mainHandler)
            mediaProjection = projection

            val overlay = LiveTranslationOverlayController(
                context = applicationContext,
                settings = settings,
                onStopRequested = {
                    mainHandler.post { requestStop(reason = "overlay stop button") }
                }
            )
            overlay.show()
            overlayController = overlay
            overlayJob = serviceScope.launch {
                LiveTranslationSessionStore.snapshot.collect { snapshot ->
                    overlayController?.update(snapshot)
                }
            }

            beginWorkers(
                projection = projection,
                settings = settings,
                currentGeneration = currentGeneration
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to initialize live translation service", error)
            requestStop(
                reason = "session initialization failed",
                errorMessage = startupErrorMessage(error)
            )
        }
    }

    /**
     * 创建有界通道并启动ASR、翻译与AudioRecord工作流。
     *
     * @param projection 本次有效MediaProjection。
     * @param settings 本次会话冻结的语言和字幕样式。
     * @param currentGeneration 当前会话代际。
     * @return 无返回值。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun beginWorkers(
        projection: MediaProjection,
        settings: LiveTranslationSettings,
        currentGeneration: Long
    ) {
        // PCM满载时让trySend明确失败，由生产者记录断层；不能静默把不连续音频拼给VAD。
        val newAudioChannel = Channel<AudioPacket>(capacity = AUDIO_CHANNEL_CAPACITY)
        val newTranslationChannel = Channel<TranslationRequest>(
            capacity = TRANSLATION_CHANNEL_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        audioChannel = newAudioChannel
        translationChannel = newTranslationChannel

        // 翻译模型必须先完整加载；识别协程等待本屏障后才创建ASR并打开AudioRecord。
        val translatorReady = CompletableDeferred<LiveTranslationReadyModelDirectories>()

        translationJob = serviceScope.launch(Dispatchers.Default) {
            var translator: OnDeviceLiveTranslator? = null
            try {
                val readyDirectories = modelManager.requireReadyModelDirectories()
                val createdTranslator = OnDeviceLiveTranslator(
                    modelDirectory = readyDirectories.translationDirectory
                )
                translator = createdTranslator
                createdTranslator.initialize()
                translatorReady.complete(readyDirectories)

                for (request in newTranslationChannel) {
                    if (request.generation != generation) continue
                    try {
                        val translatedText = createdTranslator.translate(
                            text = request.sourceText,
                            sourceLanguage = settings.sourceLanguage
                        )
                        if (request.generation == generation) {
                            val published = LiveTranslationSessionStore.publishTranslation(
                                sourceText = request.sourceText,
                                translatedText = translatedText
                            )
                            if (published) {
                                scheduleCaptionExpiry(request.generation, request.sourceText)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // 单句翻译失败不终止后续识别；正文不会进入日志。
                        Log.e(TAG, "Failed to translate one live caption", error)
                        if (request.generation == generation) {
                            val published = LiveTranslationSessionStore.publishTranslation(
                                sourceText = request.sourceText,
                                translatedText = "（本句暂时无法翻译）"
                            )
                            if (published) {
                                scheduleCaptionExpiry(request.generation, request.sourceText)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                translatorReady.cancel(error)
                throw error
            } catch (error: Throwable) {
                translatorReady.completeExceptionally(error)
                if (!stopping && currentGeneration == generation) {
                    Log.e(TAG, "Local translation worker failed", error)
                    withContext(Dispatchers.Main.immediate) {
                        requestStop(
                            reason = "local translation failed",
                            errorMessage = translationErrorMessage(error)
                        )
                    }
                }
            } finally {
                translator?.close()
            }
        }

        recognitionJob = serviceScope.launch(recognitionDispatcher) {
            var recognizer: SenseVoiceCaptionRecognizer? = null
            try {
                val readyDirectories = try {
                    translatorReady.await()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // 翻译协程已经发布具体启动错误并负责停止会话，避免识别协程重复覆盖提示。
                    return@launch
                }
                recognizer = SenseVoiceCaptionRecognizer(
                    modelDirectory = readyDirectories.asrDirectory,
                    sourceLanguage = settings.sourceLanguage
                )
                withContext(Dispatchers.Main.immediate) {
                    check(currentGeneration == generation && !stopping) {
                        "Live translation session became stale during model loading"
                    }
                    startPlaybackCapture(projection, newAudioChannel)
                }

                val assembler = CaptionCueAssembler()
                for (packet in newAudioChannel) {
                    if (currentGeneration != generation) break
                    if (packet.discontinuityBefore) {
                        val flushedEmissions = recognizer.flushAndResetAfterDiscontinuity()
                        if (currentGeneration != generation) break
                        flushedEmissions.forEach { emission ->
                            handleRecognitionEmission(
                                emission = emission,
                                assembler = assembler,
                                currentGeneration = currentGeneration,
                                translations = newTranslationChannel
                            )
                        }
                    }
                    val emissions = recognizer.acceptPcm16(packet.samples, packet.samples.size)
                    if (currentGeneration != generation) break
                    emissions.forEach { emission ->
                        handleRecognitionEmission(
                            emission = emission,
                            assembler = assembler,
                            currentGeneration = currentGeneration,
                            translations = newTranslationChannel
                        )
                    }
                }
                if (currentGeneration == generation) {
                    recognizer.flush().forEach { emission ->
                        handleRecognitionEmission(
                            emission = emission,
                            assembler = assembler,
                            currentGeneration = currentGeneration,
                            translations = newTranslationChannel
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!stopping && currentGeneration == generation) {
                    Log.e(TAG, "Live speech recognition worker failed", error)
                    mainHandler.post {
                        requestStop(
                            reason = "speech recognition failed",
                            errorMessage = recognitionErrorMessage(error)
                        )
                    }
                }
            } finally {
                recognizer?.release()
            }
        }
    }

    /**
     * 在ASR加载完成后创建并启动系统播放音频捕获循环。
     *
     * @param projection 本次MediaProjection。
     * @param destination 有界PCM通道。
     * @return 无返回值。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startPlaybackCapture(
        projection: MediaProjection,
        destination: Channel<AudioPacket>
    ) {
        val capture = PlaybackAudioCapture.create(this, projection)
        capture.start()
        playbackCapture = capture
        LiveTranslationSessionStore.publishRunning()
        notificationManager.notify(NOTIFICATION_ID, buildNotification("正在本地识别并翻译"))

        captureJob = serviceScope.launch(Dispatchers.IO) {
            val watchdog = CaptureSilenceWatchdog()
            watchdog.reset()
            val buffer = ShortArray(capture.recommendedBufferSamples)
            var discontinuityPending = false
            try {
                while (isActive && !stopping) {
                    val sampleCount = capture.read(buffer)
                    if (!isActive || stopping) break
                    when {
                        sampleCount > 0 -> {
                            val elapsedMillis = (sampleCount * 1_000L / capture.sampleRate)
                                .coerceAtLeast(1L)
                            val observation = watchdog.observePcm(
                                samples = buffer,
                                sampleCount = sampleCount,
                                elapsedMillis = elapsedMillis
                            )
                            LiveTranslationSessionStore.publishAudio(
                                captureStatus = observation.captureStatus,
                                audioLevelDb = observation.audioLevelDb
                            )
                            val sendResult = destination.trySend(
                                AudioPacket(
                                    samples = buffer.copyOf(sampleCount),
                                    discontinuityBefore = discontinuityPending
                                )
                            )
                            if (sendResult.isSuccess) {
                                discontinuityPending = false
                            } else if (!stopping) {
                                if (!discontinuityPending) {
                                    Log.w(TAG, "Dropping playback audio because recognition is behind")
                                }
                                discontinuityPending = true
                            }
                        }

                        sampleCount == 0 -> Unit
                        sampleCount == AudioRecord.ERROR_INVALID_OPERATION ||
                            sampleCount == AudioRecord.ERROR_BAD_VALUE ||
                            sampleCount == AudioRecord.ERROR_DEAD_OBJECT -> {
                            error("AudioRecord read failed with code $sampleCount")
                        }

                        else -> error("Unexpected AudioRecord read result $sampleCount")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!stopping) {
                    Log.e(TAG, "Playback audio capture worker failed", error)
                    mainHandler.post {
                        requestStop(
                            reason = "audio capture failed",
                            errorMessage = "播放器声音内录已中断，请重新授权后再试"
                        )
                    }
                }
            } finally {
                destination.close()
            }
        }
    }

    /**
     * 合并识别器partial/final，并只把新final提交给本地翻译工作流。
     *
     * @param emission 本次识别结果。
     * @param assembler 当前会话字幕合并器。
     * @param currentGeneration 当前会话代际。
     * @param translations 有界翻译请求通道。
     * @return 无返回值。
     */
    private fun handleRecognitionEmission(
        emission: LiveRecognitionEmission,
        assembler: CaptionCueAssembler,
        currentGeneration: Long,
        translations: Channel<TranslationRequest>
    ) {
        val assembly = if (emission.isFinal) {
            assembler.acceptFinal(emission.text)
        } else {
            assembler.acceptPartial(emission.text)
        }
        if (assembly.displayTextChanged || (emission.isFinal && assembly.accepted)) {
            LiveTranslationSessionStore.publishSource(
                sourceText = assembly.displayText,
                clearTranslation = emission.isFinal && assembly.accepted
            )
        }
        if (!emission.isFinal && assembly.displayText.isNotBlank()) {
            // 相同partial也代表当前话语仍在继续，需要从最后一次活动重新计算可见时间。
            scheduleCaptionExpiry(currentGeneration, assembly.displayText)
        }
        assembly.finalTextForTranslation?.let { finalText ->
            scheduleCaptionExpiry(currentGeneration, finalText)
            translations.trySend(
                TranslationRequest(
                    generation = currentGeneration,
                    sourceText = finalText
                )
            )
        }
    }

    /**
     * 取消当前字幕到期任务。
     *
     * 使用方法：
     * 会话进入停止流程时调用，避免定时任务与最终资源清理同时更新状态。正常partial/final更新改用
     * [scheduleCaptionExpiry]覆盖旧任务，使长句在持续识别期间不会提前消失。
     *
     * @return 无返回值；可从识别线程调用，实际Job字段只在主线程修改。
     */
    private fun cancelCaptionExpiry() {
        runOnMainThread {
            captionExpiryJob?.cancel()
            captionExpiryJob = null
        }
    }

    /**
     * 从指定final或其译文发布时刻重新计算字幕可见期。
     *
     * 使用方法：
     * partial活动、final确认时调用，本地译文稍后成功发布时再调用一次，让用户获得完整的
     * 译文阅读时间。到期时同时核对会话代际和原文；旧任务不能清除新句或下一次会话。
     *
     * @param expectedGeneration 创建任务时的会话代际。
     * @param expectedSourceText 创建任务时的final原文，仅用于进程内比较，不持久化。
     * @return 无返回值；可从识别或翻译线程调用。
     */
    private fun scheduleCaptionExpiry(
        expectedGeneration: Long,
        expectedSourceText: String
    ) {
        runOnMainThread {
            if (stopping || expectedGeneration != generation) return@runOnMainThread
            captionExpiryJob?.cancel()
            captionExpiryJob = serviceScope.launch {
                delay(CAPTION_VISIBLE_MILLIS)
                if (!stopping && expectedGeneration == generation) {
                    LiveTranslationSessionStore.clearCaptionIfSourceMatches(expectedSourceText)
                }
                captionExpiryJob = null
            }
        }
    }

    /**
     * 保证服务生命周期字段只在Android主线程修改。
     *
     * @param block 不执行阻塞操作的短任务。
     * @return 无返回值；主线程调用时同步执行，其他线程调用时按顺序投递。
     */
    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /**
     * 从主线程发起幂等停止，并异步等待各工作流释放原生对象。
     *
     * @param reason 仅写入英文日志的停止原因，不含字幕正文。
     * @param errorMessage 非空时最终保留ERROR状态及中文重试说明。
     * @return 无返回值。
     */
    private fun requestStop(reason: String, errorMessage: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestStop(reason, errorMessage) }
            return
        }
        if (stopping) {
            if (terminalErrorMessage == null && errorMessage != null) {
                terminalErrorMessage = errorMessage
            }
            return
        }
        stopping = true
        terminalErrorMessage = errorMessage
        cancelCaptionExpiry()
        Log.i(TAG, "Stopping live translation: $reason")
        if (errorMessage == null) {
            LiveTranslationSessionStore.publishStopping()
        }

        serviceScope.launch {
            try {
                stopWorkersWithinBounds()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed while stopping live translation workers", error)
            } finally {
                completeStopCleanup()
            }
        }
    }

    /**
     * 按采集、识别、翻译的生产关系有序停止工作流，并给末尾未闭合语音一次final与翻译机会。
     *
     * 使用方法：
     * 仅由主线程上的停止协程调用。每个可能等待原生推理或系统读取的阶段都有硬超时；超时后递增
     * 会话代际并取消对应协程，迟到的识别或译文无法再发布到状态仓库。函数不移除浮层或投屏资源，
     * 这些无论成功、异常或协程取消都由[completeStopCleanup]统一完成。
     *
     * @return 无返回值；单个阶段超时会记录英文诊断并继续清理，不会无限阻塞前台服务停止。
     */
    private suspend fun stopWorkersWithinBounds() {
        val capture = playbackCapture
        captureJob?.cancel()
        val captureStopped = capture?.stop() ?: true
        if (!captureStopped) {
            Log.w(TAG, "Playback audio capture did not stop cleanly")
        }
        audioChannel?.close()

        var captureCompleted = awaitJobWithin(
            job = captureJob,
            timeoutMillis = CAPTURE_STOP_TIMEOUT_MILLIS
        )
        if (!captureCompleted) {
            Log.w(TAG, "Playback audio capture worker exceeded stop timeout")
            runCatching { capture?.release() }.onFailure { error ->
                Log.e(TAG, "Failed to force release playback audio capture", error)
            }
            captureCompleted = awaitJobWithin(
                job = captureJob,
                timeoutMillis = CAPTURE_FORCE_RELEASE_TIMEOUT_MILLIS
            )
            if (!captureCompleted) {
                Log.e(TAG, "Playback audio capture worker remained blocked after release")
            }
        } else {
            runCatching { capture?.release() }.onFailure { error ->
                Log.e(TAG, "Failed to release playback audio capture", error)
            }
        }
        playbackCapture = null
        captureJob = null
        audioChannel = null

        val recognitionCompleted = awaitJobWithin(
            job = recognitionJob,
            timeoutMillis = RECOGNITION_STOP_TIMEOUT_MILLIS
        )
        if (!recognitionCompleted) {
            Log.w(TAG, "Speech recognition worker exceeded graceful stop timeout")
            nextGeneration()
            recognitionJob?.cancel()
            if (!awaitJobWithin(recognitionJob, RECOGNITION_CANCEL_TIMEOUT_MILLIS)) {
                Log.e(TAG, "Speech recognition worker remained blocked after cancellation")
            }
        }
        recognitionJob = null

        translationChannel?.close()
        val translationCompleted = awaitJobWithin(
            job = translationJob,
            timeoutMillis = TRANSLATION_STOP_TIMEOUT_MILLIS
        )
        if (!translationCompleted) {
            Log.w(TAG, "Translation worker exceeded graceful stop timeout")
            nextGeneration()
            translationJob?.cancel()
            if (!awaitJobWithin(translationJob, TRANSLATION_CANCEL_TIMEOUT_MILLIS)) {
                Log.e(TAG, "Translation worker remained blocked after cancellation")
            }
        }
        translationJob = null
        translationChannel = null

        overlayJob?.cancel()
        if (!awaitJobWithin(overlayJob, OVERLAY_STOP_TIMEOUT_MILLIS)) {
            Log.w(TAG, "Overlay observer exceeded stop timeout")
        }
        overlayJob = null
    }

    /**
     * 在限定时间内等待一个协程结束。
     *
     * @param job 待等待的工作协程；null表示对应阶段从未启动。
     * @param timeoutMillis 最大等待毫秒数，必须为正数。
     * @return 协程为空或已在期限内结束时返回true，超时返回false。
     */
    private suspend fun awaitJobWithin(job: Job?, timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L) { "Stop timeout must be positive" }
        if (job == null) return true
        return withTimeoutOrNull(timeoutMillis) {
            job.join()
            true
        } ?: false
    }

    /**
     * 完成不挂起的服务资源兜底清理，并发布唯一终态。
     *
     * 使用方法：
     * 必须放在停止协程的finally中调用。函数会先递增会话代际，使仍卡在JNI或本地推理中的迟到
     * 工作即使稍后恢复也无法发布；每项系统资源独立清理，某一项失败不会跳过其余资源。
     *
     * @return 无返回值；完成后请求Android销毁本服务。
     */
    private fun completeStopCleanup() {
        nextGeneration()
        captionExpiryJob?.cancel()
        captionExpiryJob = null
        captureJob?.cancel()
        recognitionJob?.cancel()
        translationJob?.cancel()
        overlayJob?.cancel()
        audioChannel?.close()
        translationChannel?.close()

        runCatching { playbackCapture?.stop() }.onFailure { error ->
            Log.e(TAG, "Failed to stop playback audio capture during final cleanup", error)
        }
        runCatching { playbackCapture?.release() }.onFailure { error ->
            Log.e(TAG, "Failed to release playback audio capture during final cleanup", error)
        }
        playbackCapture = null
        captureJob = null
        recognitionJob = null
        translationJob = null
        overlayJob = null
        audioChannel = null
        translationChannel = null

        runCatching { overlayController?.remove() }.onFailure { error ->
            Log.e(TAG, "Failed to remove live translation overlay", error)
        }
        overlayController = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }.onFailure { error ->
            Log.e(TAG, "Failed to unregister media projection callback", error)
        }
        runCatching { mediaProjection?.stop() }.onFailure { error ->
            Log.e(TAG, "Failed to stop media projection", error)
        }
        mediaProjection = null

        terminalErrorMessage?.let(LiveTranslationSessionStore::publishError)
            ?: LiveTranslationSessionStore.resetToIdle()
        if (foregroundStarted) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }.onFailure { error ->
                Log.e(TAG, "Failed to remove live translation foreground notification", error)
            }
            foregroundStarted = false
        }
        cleanupCompleted = true
        stopSelf()
    }

    /**
     * Android销毁服务时执行最终兜底，正常路径中的资源均已为空。
     *
     * @return 无返回值。
     */
    override fun onDestroy() {
        stopping = true
        nextGeneration()
        captionExpiryJob?.cancel()
        captureJob?.cancel()
        recognitionJob?.cancel()
        translationJob?.cancel()
        overlayJob?.cancel()
        playbackCapture?.stop()
        audioChannel?.close()
        translationChannel?.close()
        serviceScope.cancel()
        runCatching { playbackCapture?.release() }.onFailure { error ->
            Log.e(TAG, "Failed to release playback audio capture during service destruction", error)
        }
        runCatching { overlayController?.remove() }.onFailure { error ->
            Log.e(TAG, "Failed to remove live translation overlay during service destruction", error)
        }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }.onFailure { error ->
            Log.e(TAG, "Failed to unregister media projection callback during destruction", error)
        }
        runCatching { mediaProjection?.stop() }.onFailure { error ->
            Log.e(TAG, "Failed to stop media projection during service destruction", error)
        }
        recognitionDispatcher.close()
        if (!cleanupCompleted &&
            LiveTranslationSessionStore.snapshot.value.sessionStatus !=
            com.example.harleyapp.model.LiveTranslationSessionStatus.ERROR
        ) {
            LiveTranslationSessionStore.resetToIdle()
        }
        super.onDestroy()
    }

    /** @return 创建低重要性、持续显示的实时翻译通知渠道。 */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "实时翻译",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在进行的本地播放器音频翻译，并提供停止入口"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建持续通知；只显示处理阶段，不包含电影对白。
     *
     * @param statusText 当前服务状态短句。
     * @return 可用于startForeground或更新的Notification。
     */
    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, LiveTranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live_translation_notification)
            .setContentTitle("英日语实时翻译")
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_live_translation_notification),
                    "停止",
                    stopIntent
                ).build()
            )
            .build()
    }

    /** @return 以前台mediaProjection类型启动通知，满足Android 10及以上服务契约。 */
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

    /** @return 把内部启动异常收敛成不泄漏实现细节的中文提示。 */
    private fun startupErrorMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            "models are not ready" in message -> "本地模型尚未准备完成，请返回页面重新准备"
            "Overlay permission" in message -> "悬浮字幕权限已失效，请重新授权"
            "Record audio permission" in message -> "录音权限已失效，请重新授权"
            "projection" in message.lowercase() -> "系统内录授权无效，请重新开始并允许录制"
            else -> "实时翻译启动失败，请检查权限和可用内存后重试"
        }
    }

    /**
     * 把模型二次核验或识别运行异常转换为可执行的中文恢复提示。
     *
     * @param error 识别工作协程捕获的原始异常；异常正文只在进程内比较，不直接展示给用户。
     * @return 模型缺失时引导重新准备，其他识别故障引导降低负载后重试的中文提示。
     */
    private fun recognitionErrorMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            "ASR model" in message -> "本地识别模型已缺失，请返回页面重新准备"
            "model preparation is still running" in message -> "本地模型仍在准备，请稍后重试"
            else -> "本地语音识别启动或运行失败，请停止其他高负载应用后重试"
        }
    }

    /**
     * 把M2M100文件、内存或会话初始化错误转换成用户可以直接执行的恢复提示。
     *
     * @param error 翻译工作协程捕获的异常；不会把文件路径或字幕正文展示给用户。
     * @return 模型缺失时提示重新准备，内存不足时提示释放内存，其他故障给出统一重试说明。
     */
    private fun translationErrorMessage(error: Throwable): String {
        return when (error) {
            is LiveTranslationModelAssetsMissingException ->
                "本地翻译模型已缺失或损坏，请返回页面重新准备"

            is LiveTranslationAsrAssetsMissingException ->
                "本地识别模型已缺失或损坏，请返回页面重新准备"

            is LiveTranslationModelPreparationInProgressException ->
                "本地模型仍在准备，请稍后重试"

            is LiveTranslationRuntimeMemoryException ->
                "当前可用内存不足，请关闭其他高负载应用后重试"

            else -> "本地翻译模型启动失败，请关闭其他高负载应用后重试"
        }
    }

    /** @return 递增且处理Long上限的会话代际。 */
    private fun nextGeneration(): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return generation
    }

    companion object {
        private const val TAG = "LiveTranslation"
        private const val ACTION_START = "com.example.harleyapp.action.START_LIVE_TRANSLATION"
        private const val ACTION_STOP = "com.example.harleyapp.action.STOP_LIVE_TRANSLATION"
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val EXTRA_SHOW_SOURCE_TEXT = "show_source_text"
        private const val EXTRA_TEXT_SIZE_SP = "text_size_sp"
        private const val EXTRA_BACKGROUND_OPACITY_PERCENT = "background_opacity_percent"
        private const val EXTRA_SOURCE_LANGUAGE = "source_language"
        private const val NOTIFICATION_CHANNEL_ID = "live_translation"
        private const val NOTIFICATION_ID = 4_310
        private const val REQUEST_CODE_OPEN_APP = 4_311
        private const val REQUEST_CODE_STOP = 4_312
        private const val AUDIO_CHANNEL_CAPACITY = 8
        private const val TRANSLATION_CHANNEL_CAPACITY = 2
        private const val CAPTION_VISIBLE_MILLIS = 4_000L
        private const val CAPTURE_STOP_TIMEOUT_MILLIS = 1_500L
        private const val CAPTURE_FORCE_RELEASE_TIMEOUT_MILLIS = 750L
        private const val RECOGNITION_STOP_TIMEOUT_MILLIS = 4_000L
        private const val RECOGNITION_CANCEL_TIMEOUT_MILLIS = 1_000L
        private const val TRANSLATION_STOP_TIMEOUT_MILLIS = 2_000L
        private const val TRANSLATION_CANCEL_TIMEOUT_MILLIS = 500L
        private const val OVERLAY_STOP_TIMEOUT_MILLIS = 500L

        /**
         * 创建只供本应用启动一次实时翻译会话的显式Intent。
         *
         * @param context Android上下文。
         * @param resultCode 系统MediaProjection授权返回码。
         * @param projectionData 系统返回的一次性授权数据。
         * @param settings 已规范化的语言及字幕设置。
         * @return 可交给ContextCompat.startForegroundService的显式Intent。
         */
        fun createStartIntent(
            context: Context,
            resultCode: Int,
            projectionData: Intent,
            settings: LiveTranslationSettings
        ): Intent {
            val normalizedSettings = settings.normalized()
            return Intent(context, LiveTranslationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
                .putExtra(EXTRA_SHOW_SOURCE_TEXT, normalizedSettings.showSourceText)
                .putExtra(EXTRA_TEXT_SIZE_SP, normalizedSettings.textSizeSp)
                .putExtra(
                    EXTRA_BACKGROUND_OPACITY_PERCENT,
                    normalizedSettings.backgroundOpacityPercent
                )
                .putExtra(EXTRA_SOURCE_LANGUAGE, normalizedSettings.sourceLanguage.name)
        }

        /**
         * 创建通知或页面用于有序停止当前会话的显式Intent。
         *
         * @param context Android上下文。
         * @return Action为停止命令且目标固定为本服务的Intent。
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, LiveTranslationService::class.java).setAction(ACTION_STOP)
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

/** @return 从服务Intent读取并规范化完整字幕设置。 */
private fun Intent.readSettings(): LiveTranslationSettings {
    val sourceLanguage = runCatching {
        LiveTranslationSourceLanguage.valueOf(
            getStringExtra("source_language") ?: LiveTranslationSourceLanguage.ENGLISH.name
        )
    }.getOrDefault(LiveTranslationSourceLanguage.ENGLISH)
    return LiveTranslationSettings(
        showSourceText = getBooleanExtra("show_source_text", true),
        textSizeSp = getIntExtra("text_size_sp", 22),
        backgroundOpacityPercent = getIntExtra("background_opacity_percent", 72),
        sourceLanguage = sourceLanguage
    ).normalized()
}
