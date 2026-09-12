package com.example.harleyapp.system.livetranslation

import android.os.SystemClock
import android.util.Log
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 一次PCM输入后产生的识别字幕。
 *
 * @param text 识别原文；为空的识别结果不会从本类返回。
 * @param isFinal true表示VAD已经确认一句结束，false表示仍可能修订的临时字幕。
 */
data class LiveRecognitionEmission(
    val text: String,
    val isFinal: Boolean
)

/**
 * 使用SenseVoice int8和Silero VAD把连续PCM模拟成低延迟流式字幕。
 *
 * 使用方法：
 * 在单一后台工作线程创建实例，持续调用[acceptPcm16]，并依次处理返回的partial/final结果。停止时先
 * 调用[flush]取得尾句，再调用[release]。所有JNI调用必须留在同一串行工作流，禁止一边decode一边
 * release。本类不保存原始音频到磁盘，内存音频最多保留约十秒。
 *
 * @param modelDirectory 包含model.int8.onnx、tokens.txt和silero_vad.onnx的已校验目录。
 * @param sourceLanguage 用户明确选择的英语或日语；固定提示比自动检测更适合短电影对白。
 */
class SenseVoiceCaptionRecognizer(
    modelDirectory: File,
    sourceLanguage: LiveTranslationSourceLanguage
) {

    private val recognizer: OfflineRecognizer
    private val vad: Vad
    private val partialRefreshIntervalMillis = recommendedPartialRefreshIntervalMillis(sourceLanguage)

    init {
        /*
         * 两个JNI对象不能直接写成连续属性初始化：如果较小的VAD在ASR创建后失败，Kotlin对象尚未
         * 构造完成，调用方拿不到ASR引用，也就无法释放约数百MB的原生会话。
         */
        val createdRecognizer = createOfflineRecognizer(modelDirectory, sourceLanguage)
        try {
            val createdVad = createVad(modelDirectory, sourceLanguage)
            recognizer = createdRecognizer
            vad = createdVad
        } catch (error: Throwable) {
            runCatching(createdRecognizer::release).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private val pendingVadFrame = FloatArray(VAD_WINDOW_SAMPLES)
    private var pendingVadSampleCount = 0
    private val preRoll = FloatRingBuffer(PRE_ROLL_SAMPLES)
    private val currentSpeech = BoundedFloatBuffer(MAX_PARTIAL_AUDIO_SAMPLES)
    private var speechWasDetected = false
    private var lastPartialDecodeAtMillis = 0L
    private var released = false

    /**
     * 接收AudioRecord本次实际读取的PCM16样本并推进VAD、partial和final识别。
     *
     * 使用方法：
     * 复用ShortArray时务必传实际[sampleCount]；本类会归一化到-1到1，并按Silero要求拼成512样本
     * 窗口。通常每200ms调用一次，只有达到刷新间隔或确认句末时才执行较重的ASR。
     *
     * @param samples PCM16单声道数组。
     * @param sampleCount 从数组起始位置有效的样本数。
     * @return 按发生顺序排列的零个或多个临时/最终字幕。
     */
    fun acceptPcm16(
        samples: ShortArray,
        sampleCount: Int
    ): List<LiveRecognitionEmission> {
        check(!released) { "Recognizer has already been released" }
        require(sampleCount in 0..samples.size) { "PCM sample count is outside the array" }
        if (sampleCount == 0) return emptyList()

        val emissions = mutableListOf<LiveRecognitionEmission>()
        for (index in 0 until sampleCount) {
            pendingVadFrame[pendingVadSampleCount] = samples[index] / PCM_16_SCALE
            pendingVadSampleCount += 1
            if (pendingVadSampleCount == pendingVadFrame.size) {
                processVadFrame(pendingVadFrame.copyOf(), emissions)
                pendingVadSampleCount = 0
            }
        }
        return emissions
    }

    /**
     * 告知VAD输入已经结束并尽量输出最后一条完整字幕。
     *
     * 使用方法：
     * AudioRecord停止、投屏授权撤销或正常结束会话后调用一次。剩余不足512样本会补零送入VAD，
     * 随后flush并清空所有已完成语音段。调用后仍需[release]释放原生对象。
     *
     * @return 尚未发布的最终字幕列表。
     */
    fun flush(): List<LiveRecognitionEmission> {
        check(!released) { "Recognizer has already been released" }
        val emissions = mutableListOf<LiveRecognitionEmission>()
        if (pendingVadSampleCount > 0) {
            pendingVadFrame.fill(0f, pendingVadSampleCount, pendingVadFrame.size)
            processVadFrame(pendingVadFrame.copyOf(), emissions)
            pendingVadSampleCount = 0
        }
        vad.flush()
        drainCompletedSegments(emissions)
        if (emissions.none { emission -> emission.isFinal } && currentSpeech.size >= MIN_PARTIAL_SAMPLES) {
            decode(currentSpeech.toFloatArray())?.let { text ->
                emissions += LiveRecognitionEmission(text = text, isFinal = true)
            }
        }
        currentSpeech.clear()
        return emissions
    }

    /**
     * 在采集队列发生丢帧后结束断层前的尾句，并重置全部流式状态后继续接收新音频。
     *
     * 使用方法：
     * 音频生产者因有界队列已满而丢弃一个或多个PCM块时，在下一块成功入队的数据前设置断层
     * 标记；识别工作线程看到标记后先调用本函数，再处理该块。返回的final仍属于断层前的语音，
     * 调用方应先发布这些结果。重置后VAD不会把缺失时段两侧的词错误拼成同一句。
     *
     * @return 断层前尚未发布的最终字幕列表；没有足够语音时为空。
     */
    fun flushAndResetAfterDiscontinuity(): List<LiveRecognitionEmission> {
        val emissions = flush()
        vad.reset()
        pendingVadFrame.fill(0f)
        pendingVadSampleCount = 0
        preRoll.clear()
        currentSpeech.clear()
        speechWasDetected = false
        lastPartialDecodeAtMillis = 0L
        return emissions
    }

    /**
     * 释放VAD和ASR原生资源。
     *
     * 使用方法：
     * 必须等待最后一次[acceptPcm16]或[flush]返回后调用；重复调用安全。
     *
     * @return 无返回值。
     */
    fun release() {
        if (released) return
        released = true
        runCatching(vad::release).onFailure { error ->
            Log.e(TAG, "Failed to release the live translation VAD", error)
        }
        runCatching(recognizer::release).onFailure { error ->
            Log.e(TAG, "Failed to release the live translation recognizer", error)
        }
        currentSpeech.clear()
        preRoll.clear()
    }

    /** @return 处理一个完整VAD窗口后新增的partial/final通过[emissions]返回。 */
    private fun processVadFrame(
        frame: FloatArray,
        emissions: MutableList<LiveRecognitionEmission>
    ) {
        val speechBeforeFrame = speechWasDetected
        vad.acceptWaveform(frame)
        val speechAfterFrame = vad.isSpeechDetected()

        if (!speechBeforeFrame && speechAfterFrame) {
            currentSpeech.clear()
            currentSpeech.append(preRoll.toFloatArray())
        }
        if (speechAfterFrame) {
            currentSpeech.append(frame)
        }
        preRoll.append(frame)
        speechWasDetected = speechAfterFrame

        val completedBeforeDrain = emissions.size
        drainCompletedSegments(emissions)
        if (emissions.size > completedBeforeDrain) {
            currentSpeech.clear()
            if (speechAfterFrame) currentSpeech.append(preRoll.toFloatArray())
            lastPartialDecodeAtMillis = 0L
        }

        val nowMillis = SystemClock.elapsedRealtime()
        if (speechAfterFrame &&
            currentSpeech.size >= MIN_PARTIAL_SAMPLES &&
            nowMillis - lastPartialDecodeAtMillis >= partialRefreshIntervalMillis
        ) {
            val partialText = decode(currentSpeech.toFloatArray())
            // 同步推理本身可能超过600ms，必须从推理结束后重新计时，避免下一帧立即再次推理。
            lastPartialDecodeAtMillis = SystemClock.elapsedRealtime()
            partialText?.let { text ->
                emissions += LiveRecognitionEmission(text = text, isFinal = false)
            }
        }
    }

    /** @return 清空VAD中所有已确认片段，并向[emissions]追加非空final。 */
    private fun drainCompletedSegments(emissions: MutableList<LiveRecognitionEmission>) {
        while (!vad.empty()) {
            val segment = vad.front()
            decode(segment.samples)?.let { text ->
                emissions += LiveRecognitionEmission(text = text, isFinal = true)
            }
            vad.pop()
        }
    }

    /**
     * 对一个内存语音段执行同步离线识别，并保证临时Stream总会释放。
     *
     * @param samples 已归一化的16kHz单声道浮点PCM。
     * @return 去除首尾空白后的字幕；没有有效文本时返回null。
     */
    private fun decode(samples: FloatArray): String? {
        if (samples.size < MIN_DECODE_SAMPLES) return null
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim().takeIf(String::isNotBlank)
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val TAG = "SenseVoiceCaption"
        const val SAMPLE_RATE_HZ = 16_000
        const val FEATURE_DIMENSION = 80
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
        const val VAD_MODEL_FILE_NAME = "silero_vad.onnx"
        const val VAD_WINDOW_SAMPLES = 512
        const val VAD_THREAD_COUNT = 1
        const val VAD_THRESHOLD = 0.5f
        const val ENGLISH_VAD_MIN_SILENCE_SECONDS = 0.25f
        const val JAPANESE_VAD_MIN_SILENCE_SECONDS = 0.55f
        const val VAD_MIN_SPEECH_SECONDS = 0.25f
        const val VAD_MAX_SPEECH_SECONDS = 8f
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE_HZ * 4 / 10
        const val MIN_DECODE_SAMPLES = SAMPLE_RATE_HZ / 4
        const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE_HZ * 4 / 5
        const val MAX_PARTIAL_AUDIO_SAMPLES = SAMPLE_RATE_HZ * 10
        const val ENGLISH_PARTIAL_REFRESH_INTERVAL_MILLIS = 600L
        const val JAPANESE_PARTIAL_REFRESH_INTERVAL_MILLIS = 1_500L
        const val PCM_16_SCALE = 32_768f

        /** @return 在移动CPU上限制为2到4线程的ASR线程数。 */
        fun recommendedAsrThreads(): Int {
            return Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }

        /**
         * 返回适合当前语言自然停顿的VAD句末静音阈值。
         *
         * @param sourceLanguage 用户为当前会话固定选择的英语或日语。
         * @return 英语使用0.25秒保证响应速度；日语使用0.55秒，减少短停顿和助词附近的错误断句。
         */
        fun vadMinSilenceSeconds(sourceLanguage: LiveTranslationSourceLanguage): Float {
            return when (sourceLanguage) {
                LiveTranslationSourceLanguage.ENGLISH -> ENGLISH_VAD_MIN_SILENCE_SECONDS
                LiveTranslationSourceLanguage.JAPANESE -> JAPANESE_VAD_MIN_SILENCE_SECONDS
            }
        }

        /**
         * 返回当前语言两次partial离线推理之间的最短间隔。
         *
         * @param sourceLanguage 用户为当前会话固定选择的英语或日语。
         * @return 英语为600毫秒；日语为1500毫秒，兼顾较长语序上下文和移动设备持续推理负载。
         */
        fun recommendedPartialRefreshIntervalMillis(
            sourceLanguage: LiveTranslationSourceLanguage
        ): Long {
            return when (sourceLanguage) {
                LiveTranslationSourceLanguage.ENGLISH -> ENGLISH_PARTIAL_REFRESH_INTERVAL_MILLIS
                LiveTranslationSourceLanguage.JAPANESE -> JAPANESE_PARTIAL_REFRESH_INTERVAL_MILLIS
            }
        }

        /**
         * 创建SenseVoice离线识别JNI对象。
         *
         * @param modelDirectory 已校验模型目录。
         * @param sourceLanguage 当前固定源语言。
         * @return 尚未开始解码的原生离线识别器。
         */
        fun createOfflineRecognizer(
            modelDirectory: File,
            sourceLanguage: LiveTranslationSourceLanguage
        ): OfflineRecognizer {
            return OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE_HZ,
                        featureDim = FEATURE_DIMENSION,
                        dither = 0f
                    ),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(modelDirectory, MODEL_FILE_NAME).absolutePath,
                            language = sourceLanguage.senseVoiceLanguageCode(),
                            useInverseTextNormalization = true
                        ),
                        tokens = File(modelDirectory, TOKENS_FILE_NAME).absolutePath,
                        numThreads = recommendedAsrThreads(),
                        debug = false,
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
            )
        }

        /**
         * 创建Silero VAD JNI对象。
         *
         * @param modelDirectory 已校验模型目录。
         * @param sourceLanguage 当前固定源语言；日语使用更长的停顿边界，避免把助词后的自然停顿
         * 误切成多个短句。
         * @return 尚未接收PCM的VAD实例。
         */
        fun createVad(
            modelDirectory: File,
            sourceLanguage: LiveTranslationSourceLanguage
        ): Vad {
            return Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(modelDirectory, VAD_MODEL_FILE_NAME).absolutePath,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = vadMinSilenceSeconds(sourceLanguage),
                        minSpeechDuration = VAD_MIN_SPEECH_SECONDS,
                        windowSize = VAD_WINDOW_SAMPLES,
                        maxSpeechDuration = VAD_MAX_SPEECH_SECONDS
                    ),
                    sampleRate = SAMPLE_RATE_HZ,
                    numThreads = VAD_THREAD_COUNT,
                    provider = "cpu",
                    debug = false
                )
            )
        }
    }
}

/** @return SenseVoice识别提示使用的标准语言代码。 */
private fun LiveTranslationSourceLanguage.senseVoiceLanguageCode(): String {
    return when (this) {
        LiveTranslationSourceLanguage.ENGLISH -> "en"
        LiveTranslationSourceLanguage.JAPANESE -> "ja"
    }
}

/**
 * 固定容量的浮点顺序缓冲；超限时丢弃最早样本，保证长台词不会无限占用Java堆。
 *
 * @param capacity 最大样本数。
 */
private class BoundedFloatBuffer(private val capacity: Int) {
    private var values = FloatArray(capacity)
    private var start = 0

    /** 当前有效样本数。 */
    var size: Int = 0
        private set

    /** @return 在尾部追加数组，必要时移除最旧样本。 */
    fun append(samples: FloatArray) {
        samples.forEach(::append)
    }

    /** @return 在尾部追加单个样本。 */
    private fun append(sample: Float) {
        if (size < capacity) {
            values[(start + size) % capacity] = sample
            size += 1
        } else {
            values[start] = sample
            start = (start + 1) % capacity
        }
    }

    /** @return 按时间顺序复制当前有效样本。 */
    fun toFloatArray(): FloatArray {
        return FloatArray(size) { index -> values[(start + index) % capacity] }
    }

    /** @return 清空逻辑长度，不重新分配大数组。 */
    fun clear() {
        start = 0
        size = 0
    }
}

/**
 * 保存VAD开始前短暂音频的固定容量环形缓冲。
 *
 * @param capacity 最大前滚样本数。
 */
private class FloatRingBuffer(private val capacity: Int) {
    private val values = FloatArray(capacity)
    private var start = 0
    private var size = 0

    /** @return 逐个追加一批样本并始终只保留最近[capacity]个。 */
    fun append(samples: FloatArray) {
        samples.forEach { sample ->
            if (size < capacity) {
                values[(start + size) % capacity] = sample
                size += 1
            } else {
                values[start] = sample
                start = (start + 1) % capacity
            }
        }
    }

    /** @return 按时间顺序复制当前前滚音频。 */
    fun toFloatArray(): FloatArray {
        return FloatArray(size) { index -> values[(start + index) % capacity] }
    }

    /** @return 清除全部前滚样本。 */
    fun clear() {
        start = 0
        size = 0
    }
}
