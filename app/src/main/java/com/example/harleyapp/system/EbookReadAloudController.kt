package com.example.harleyapp.system

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/** 电子书自动朗读控制器状态。 */
enum class EbookReadAloudState {
    INITIALIZING,
    READY,
    MISSING_OFFLINE_VOICE,
    ERROR,
    RELEASED
}

/**
 * 页面可显示和持久化的Android TTS音色选项。
 *
 * @param name 系统Voice稳定名称，用于重新选择同一音色。
 * @param displayName 面向用户的语言、地区和音色名称。
 * @param languageCode ISO语言代码，目前阅读器使用zh或en。
 */
data class EbookTtsVoiceOption(
    val name: String,
    val displayName: String,
    val languageCode: String
)

/**
 * 一段可直接交给Android TTS的电子书原文区间。
 *
 * 使用方法：
 * 调用[buildEbookSpeechChunks]取得有序列表后，按顺序提交[text]；TTS回传的块内UTF-16位置加上
 * [startOffset]，即可得到当前阅读页中的准确位置。这里始终保存原文子串，不对空白、标点或大小写做
 * 替换，避免朗读位置与页面文字错位。
 *
 * @param text 当前块的原文子串。
 * @param startOffset 当前块在整页文字中的UTF-16起点，包含该位置。
 * @param endOffsetExclusive 当前块在整页文字中的UTF-16终点，不包含该位置。
 * @param pauseAfterMillis 自然朗读时本块完成后的停顿毫秒数；普通朗读固定为0。
 * @param speechRateMultiplier 相对于用户朗读速度的轻微倍率；普通朗读固定为1。
 * @param pitch 交给Android TTS的音高倍率；普通朗读固定为1。
 */
internal data class EbookSpeechChunk(
    val text: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val pauseAfterMillis: Long,
    val speechRateMultiplier: Float,
    val pitch: Float
) {

    init {
        require(startOffset >= 0) { "Speech chunk start must be non-negative" }
        require(endOffsetExclusive > startOffset) { "Speech chunk range must not be empty" }
        require(endOffsetExclusive - startOffset == text.length) {
            "Speech chunk offsets must match its UTF-16 length"
        }
        require(pauseAfterMillis >= 0L) { "Speech chunk pause must be non-negative" }
        require(speechRateMultiplier > 0f) { "Speech chunk rate must be positive" }
        require(pitch > 0f) { "Speech chunk pitch must be positive" }
    }
}

/**
 * 一段映射到整页原文的朗读范围。
 *
 * @param startOffset 整页UTF-16起点，包含该位置。
 * @param endOffsetExclusive 整页UTF-16终点，不包含该位置。
 */
internal data class EbookSpeechTextRange(
    val startOffset: Int,
    val endOffsetExclusive: Int
)

/**
 * 把Android TTS回传的块内范围转换为整页原文范围。
 *
 * 使用方法：
 * 在onRangeStart中传入当前[EbookSpeechChunk]及引擎给出的块内UTF-16位置。函数会把越界值限制到
 * 当前块，再加上块在整页中的起点；空范围返回null，调用方无需向页面发送无效高亮。
 *
 * @param chunk 当前TTS内部朗读块。
 * @param chunkStart 引擎回传的块内起点。
 * @param chunkEndExclusive 引擎回传的块内不包含式终点。
 * @return 有效的整页范围；夹紧后为空时返回null。
 */
internal fun mapEbookSpeechChunkRange(
    chunk: EbookSpeechChunk,
    chunkStart: Int,
    chunkEndExclusive: Int
): EbookSpeechTextRange? {
    val safeStart = chunkStart.coerceIn(0, chunk.text.length)
    val safeEnd = chunkEndExclusive.coerceIn(safeStart, chunk.text.length)
    if (safeEnd <= safeStart) return null
    return EbookSpeechTextRange(
        startOffset = chunk.startOffset + safeStart,
        endOffsetExclusive = chunk.startOffset + safeEnd
    )
}

/** 自然朗读分句时识别出的原文边界类型。 */
private enum class EbookNaturalSpeechBoundary {
    NONE,
    PERIOD,
    QUESTION,
    EXCLAMATION,
    SEMICOLON,
    LINE_BREAK
}

/**
 * 一个自然边界对应的轻微韵律调整。
 *
 * @param pauseAfterMillis 当前句结束后的停顿毫秒数。
 * @param speechRateMultiplier 当前句相对于用户设置速度的倍率。
 * @param pitch 当前句的音高倍率。
 */
private data class EbookNaturalSpeechCadence(
    val pauseAfterMillis: Long,
    val speechRateMultiplier: Float,
    val pitch: Float
)

/**
 * 把整页原文切成Android TTS能够完整接收的有序块。
 *
 * 使用方法：
 * 控制器在每次[speak][EbookReadAloudController.speak]前调用。普通模式只按[maxChunkLength]切分，
 * 不附加停顿、变速或变调；自然模式优先在中英文句号、问号、感叹号、分号和换行处分句，单句仍然
 * 过长时再在长度限制内寻找空白或逗号等安全位置。所有[text][EbookSpeechChunk.text]均为输入原文
 * 的准确子串，区间使用与Android TTS范围回调一致的UTF-16索引。
 *
 * @param text 当前阅读页的完整原文。
 * @param maxChunkLength Android TTS允许单次提交的最大UTF-16长度，必须至少为2。
 * @param naturalReadingEnabled true表示启用自然分句、轻微韵律和句间停顿；false表示普通安全分块。
 * @return 按原文顺序排列且每块不超过[maxChunkLength]的列表；空白正文返回空列表。
 */
internal fun buildEbookSpeechChunks(
    text: String,
    maxChunkLength: Int,
    naturalReadingEnabled: Boolean
): List<EbookSpeechChunk> {
    require(maxChunkLength >= MIN_TTS_CHUNK_LENGTH) {
        "TTS chunk length must allow one UTF-16 surrogate pair"
    }
    if (text.isBlank()) return emptyList()

    val chunks = mutableListOf<EbookSpeechChunk>()
    if (!naturalReadingEnabled) {
        appendBoundedEbookSpeechRange(
            sourceText = text,
            rangeStart = 0,
            rangeEndExclusive = text.length,
            maxChunkLength = maxChunkLength,
            naturalReadingEnabled = false,
            boundary = EbookNaturalSpeechBoundary.NONE,
            destination = chunks
        )
        return chunks
    }

    var sentenceStart = 0
    var scanIndex = 0
    while (scanIndex < text.length) {
        val boundary = ebookNaturalSpeechBoundaryAt(text, scanIndex)
        if (boundary == null) {
            scanIndex += 1
            continue
        }

        val sentenceEnd = consumeEbookNaturalSpeechBoundary(
            text = text,
            boundaryIndex = scanIndex,
            boundary = boundary
        )
        val effectiveBoundary = if (
            text.substring(scanIndex, sentenceEnd).any { character ->
                character == '\r' || character == '\n'
            }
        ) {
            EbookNaturalSpeechBoundary.LINE_BREAK
        } else {
            boundary
        }
        appendBoundedEbookSpeechRange(
            sourceText = text,
            rangeStart = sentenceStart,
            rangeEndExclusive = sentenceEnd,
            maxChunkLength = maxChunkLength,
            naturalReadingEnabled = true,
            boundary = effectiveBoundary,
            destination = chunks
        )
        sentenceStart = sentenceEnd
        scanIndex = sentenceEnd
    }

    if (sentenceStart < text.length) {
        appendBoundedEbookSpeechRange(
            sourceText = text,
            rangeStart = sentenceStart,
            rangeEndExclusive = text.length,
            maxChunkLength = maxChunkLength,
            naturalReadingEnabled = true,
            boundary = EbookNaturalSpeechBoundary.NONE,
            destination = chunks
        )
    }
    return chunks
}

/**
 * 把一个句子区间继续限制为TTS允许的长度，并追加到目标列表。
 *
 * 使用方法：
 * 只由[buildEbookSpeechChunks]调用。自然模式下，过长句子优先在后半段的空白或弱标点后切开；普通
 * 模式严格按最大长度前进。函数会避开UTF-16代理项和CRLF中间位置，并把句末韵律只放到该句最后
 * 一个实际可朗读块上。
 *
 * @param sourceText 当前阅读页完整原文。
 * @param rangeStart 待切区间起点，包含该位置。
 * @param rangeEndExclusive 待切区间终点，不包含该位置。
 * @param maxChunkLength 单块最大UTF-16长度。
 * @param naturalReadingEnabled 是否允许寻找自然的块内切点。
 * @param boundary 当前句子的结束边界，用于生成最后一块的韵律。
 * @param destination 接收结果的列表。
 * @return 无返回值，结果直接追加到[destination]。
 */
private fun appendBoundedEbookSpeechRange(
    sourceText: String,
    rangeStart: Int,
    rangeEndExclusive: Int,
    maxChunkLength: Int,
    naturalReadingEnabled: Boolean,
    boundary: EbookNaturalSpeechBoundary,
    destination: MutableList<EbookSpeechChunk>
) {
    if (rangeStart >= rangeEndExclusive) return

    val firstDestinationIndex = destination.size
    var chunkStart = rangeStart
    while (chunkStart < rangeEndExclusive) {
        val proposedEnd = (chunkStart + maxChunkLength).coerceAtMost(rangeEndExclusive)
        val hardSafeEnd = safeEbookSpeechChunkEnd(
            text = sourceText,
            chunkStart = chunkStart,
            proposedEnd = proposedEnd,
            rangeEndExclusive = rangeEndExclusive
        )
        val chunkEnd = if (naturalReadingEnabled && hardSafeEnd < rangeEndExclusive) {
            findPreferredNaturalSpeechChunkEnd(
                text = sourceText,
                chunkStart = chunkStart,
                hardSafeEnd = hardSafeEnd
            )
        } else {
            hardSafeEnd
        }
        val chunkText = sourceText.substring(chunkStart, chunkEnd)
        if (chunkText.isNotBlank()) {
            destination += EbookSpeechChunk(
                text = chunkText,
                startOffset = chunkStart,
                endOffsetExclusive = chunkEnd,
                pauseAfterMillis = 0L,
                speechRateMultiplier = 1f,
                pitch = 1f
            )
        }
        chunkStart = chunkEnd
    }

    if (naturalReadingEnabled && destination.size > firstDestinationIndex) {
        val cadence = ebookNaturalSpeechCadence(boundary)
        val lastIndex = destination.lastIndex
        destination[lastIndex] = destination[lastIndex].copy(
            pauseAfterMillis = cadence.pauseAfterMillis,
            speechRateMultiplier = cadence.speechRateMultiplier,
            pitch = cadence.pitch
        )
    }
}

/**
 * 修正一次硬长度切分的终点，避免破坏UTF-16代理项或Windows换行。
 *
 * @param text 当前阅读页完整原文。
 * @param chunkStart 当前块起点。
 * @param proposedEnd 按长度直接计算出的候选终点。
 * @param rangeEndExclusive 当前句子区间终点。
 * @return 大于[chunkStart]且不超过[proposedEnd]的安全终点。
 */
private fun safeEbookSpeechChunkEnd(
    text: String,
    chunkStart: Int,
    proposedEnd: Int,
    rangeEndExclusive: Int
): Int {
    var safeEnd = proposedEnd.coerceIn(chunkStart + 1, rangeEndExclusive)
    if (
        safeEnd < rangeEndExclusive &&
        Character.isHighSurrogate(text[safeEnd - 1]) &&
        Character.isLowSurrogate(text[safeEnd])
    ) {
        safeEnd -= 1
    }
    if (safeEnd < rangeEndExclusive && safeEnd > chunkStart && text[safeEnd - 1] == '\r' && text[safeEnd] == '\n') {
        safeEnd -= 1
    }
    if (safeEnd <= chunkStart) {
        safeEnd = (chunkStart + MIN_TTS_CHUNK_LENGTH).coerceAtMost(rangeEndExclusive)
    }
    return safeEnd
}

/**
 * 在自然朗读的超长句中寻找更接近人类停顿习惯的安全切点。
 *
 * @param text 当前阅读页完整原文。
 * @param chunkStart 当前块起点。
 * @param hardSafeEnd 长度上限对应的安全终点。
 * @return 优先位于空白、逗号、顿号或冒号后的终点；没有合适位置时返回[hardSafeEnd]。
 */
private fun findPreferredNaturalSpeechChunkEnd(
    text: String,
    chunkStart: Int,
    hardSafeEnd: Int
): Int {
    val minimumPreferredEnd = chunkStart + ((hardSafeEnd - chunkStart) * 2 / 3).coerceAtLeast(1)
    for (index in hardSafeEnd - 1 downTo minimumPreferredEnd) {
        if (text[index].isWhitespace() || text[index] in NATURAL_SPEECH_SOFT_BREAK_CHARACTERS) {
            return index + 1
        }
    }
    return hardSafeEnd
}

/**
 * 判断指定UTF-16位置是否为自然朗读句末。
 *
 * @param text 当前阅读页完整原文。
 * @param index 待检查的UTF-16位置。
 * @return 对应边界类型；普通字符或小数点返回null。
 */
private fun ebookNaturalSpeechBoundaryAt(
    text: String,
    index: Int
): EbookNaturalSpeechBoundary? {
    return when (text[index]) {
        '.', '。', '…' -> {
            val isDecimalPoint = text[index] == '.' &&
                index > 0 && index + 1 < text.length &&
                text[index - 1].isDigit() && text[index + 1].isDigit()
            if (isDecimalPoint) null else EbookNaturalSpeechBoundary.PERIOD
        }
        '?', '？' -> EbookNaturalSpeechBoundary.QUESTION
        '!', '！' -> EbookNaturalSpeechBoundary.EXCLAMATION
        ';', '；' -> EbookNaturalSpeechBoundary.SEMICOLON
        '\r', '\n' -> EbookNaturalSpeechBoundary.LINE_BREAK
        else -> null
    }
}

/**
 * 从句末标点开始，吸收右引号、水平空白和连续换行，使下一块直接从正文字符开始。
 *
 * @param text 当前阅读页完整原文。
 * @param boundaryIndex 已识别的句末位置。
 * @param boundary 当前句末类型。
 * @return 当前句子区间的不包含式终点。
 */
private fun consumeEbookNaturalSpeechBoundary(
    text: String,
    boundaryIndex: Int,
    boundary: EbookNaturalSpeechBoundary
): Int {
    var end = boundaryIndex + 1
    if (text[boundaryIndex] == '\r' && end < text.length && text[end] == '\n') {
        end += 1
    }
    if (boundary != EbookNaturalSpeechBoundary.LINE_BREAK) {
        while (end < text.length && text[end] in NATURAL_SPEECH_CLOSING_CHARACTERS) {
            end += 1
        }
    }
    while (end < text.length && text[end].isWhitespace()) {
        end += 1
    }
    return end
}

/**
 * 返回某类句末使用的轻微韵律。
 *
 * @param boundary 当前句末类型。
 * @return 可直接写入[EbookSpeechChunk]的停顿、速度倍率和音高。
 */
private fun ebookNaturalSpeechCadence(
    boundary: EbookNaturalSpeechBoundary
): EbookNaturalSpeechCadence {
    return when (boundary) {
        EbookNaturalSpeechBoundary.PERIOD -> EbookNaturalSpeechCadence(160L, 0.98f, 0.995f)
        EbookNaturalSpeechBoundary.QUESTION -> EbookNaturalSpeechCadence(180L, 0.98f, 1.02f)
        EbookNaturalSpeechBoundary.EXCLAMATION -> EbookNaturalSpeechCadence(140L, 1.01f, 1.02f)
        EbookNaturalSpeechBoundary.SEMICOLON -> EbookNaturalSpeechCadence(90L, 0.99f, 1f)
        EbookNaturalSpeechBoundary.LINE_BREAK -> EbookNaturalSpeechCadence(210L, 0.98f, 0.995f)
        EbookNaturalSpeechBoundary.NONE -> EbookNaturalSpeechCadence(0L, 1f, 1f)
    }
}

/**
 * 复用Android系统TTS的电子书连续朗读控制器。
 *
 * 使用方法：
 * 阅读器创建实例并监听状态、朗读状态和段落完成回调；调用[availableVoices]展示指定语言的离线音色，
 * 调用[selectVoice]和[setSpeechRate]保存选择，调用[speak]朗读当前页。页面离开时必须调用[shutdown]。
 * 中英文音色分别保存，阅读速度全局保存，重新进入电子书时继续沿用。
 *
 * @param context Android上下文，内部只保留Application Context。
 * @param onStateChanged 初始化结果回调。
 * @param onSpeakingChanged 开始、结束或停止朗读时的状态回调。
 * @param onUtteranceRangeChanged 当前朗读块或精确字词范围变化回调。参数依次为阅读页级朗读标识、
 * 整页UTF-16起点和不包含式终点；不支持精确范围的TTS引擎至少会在每块开始时回传整块范围。
 * @param onUtteranceFinished 一页完整朗读结束后的回调，用于连续翻到下一页。
 * @param onUtteranceFailed 系统朗读当前页失败后的回调，用于停止连续阅读并提示用户。
 */
class EbookReadAloudController(
    context: Context,
    private val onStateChanged: (EbookReadAloudState) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onUtteranceRangeChanged: (String, Int, Int) -> Unit,
    private val onUtteranceFinished: (String) -> Unit,
    private val onUtteranceFailed: (String) -> Unit
) {

    /**
     * 当前正在执行的一次整页朗读任务。
     *
     * @param generation 控制器内递增代际，用于拒绝停止或QUEUE_FLUSH之前遗留的回调。
     * @param parentUtteranceId 页面传入的整页标识，所有对外回调都使用该值。
     * @param chunks 当前页按照原文位置生成的有序朗读块。
     */
    private data class ActiveEbookSpeechRequest(
        val generation: Long,
        val parentUtteranceId: String,
        val chunks: List<EbookSpeechChunk>,
        var currentChunkIndex: Int = -1,
        var currentEngineUtteranceId: String = "",
        var currentChunkFallbackRangeReported: Boolean = false,
        var currentChunkPreciseRangeReported: Boolean = false,
        var speakingReported: Boolean = false
    )

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var state = EbookReadAloudState.INITIALIZING
    private var voices: List<Voice> = emptyList()
    private var released = false
    private var speechGeneration = 0L
    private var activeSpeechRequest: ActiveEbookSpeechRequest? = null
    private var pendingSpeechContinuation: Runnable? = null

    init {
        engine = TextToSpeech(applicationContext) { status ->
            mainHandler.post { configureEngine(status) }
        }
    }

    /**
     * 返回某种语言在当前TTS引擎中可用且不要求联网的音色。
     *
     * @param languageCode ISO语言代码，当前支持传入zh或en。
     * @return 按地区和系统名称排序的音色；初始化未完成或没有语音包时为空。
     */
    fun availableVoices(languageCode: String): List<EbookTtsVoiceOption> {
        return voicesForLanguage(languageCode).map { voice ->
            EbookTtsVoiceOption(
                name = voice.name,
                displayName = buildVoiceDisplayName(voice),
                languageCode = voice.locale.language
            )
        }
    }

    /**
     * 返回指定语言已经保存的音色名称；未选择时返回系统排序后的第一个离线音色。
     *
     * @param languageCode zh或en。
     * @return Voice名称；没有可用音色时返回空字符串。
     */
    fun selectedVoiceName(languageCode: String): String {
        val available = voicesForLanguage(languageCode)
        val savedName = preferences.getString(voicePreferenceKey(languageCode), "").orEmpty()
        return available.firstOrNull { voice -> voice.name == savedName }?.name
            ?: available.firstOrNull()?.name.orEmpty()
    }

    /**
     * 保存指定语言的朗读音色并立即应用。
     *
     * @param languageCode zh或en。
     * @param voiceName [availableVoices]返回的Voice名称。
     * @return 音色存在、系统接受且保存成功返回true，否则返回false。
     */
    fun selectVoice(languageCode: String, voiceName: String): Boolean {
        val targetVoice = voicesForLanguage(languageCode).firstOrNull { voice ->
            voice.name == voiceName
        } ?: return false
        val currentEngine = engine ?: return false
        if (currentEngine.setVoice(targetVoice) != TextToSpeech.SUCCESS) return false
        return preferences.edit()
            .putString(voicePreferenceKey(languageCode), voiceName)
            .commit()
    }

    /**
     * 读取已经保存的朗读速度。
     *
     * @return 0.5至2.0之间的倍速值。
     */
    fun speechRate(): Float {
        return preferences.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }

    /**
     * 应用并持久化朗读速度。
     *
     * @param rate 目标倍速，超出0.5至2.0的值会自动限制。
     * @return 系统引擎接受且本地保存成功返回true。
     */
    fun setSpeechRate(rate: Float): Boolean {
        val safeRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        val currentEngine = engine ?: return false
        if (currentEngine.setSpeechRate(safeRate) != TextToSpeech.SUCCESS) return false
        return preferences.edit().putFloat(KEY_SPEECH_RATE, safeRate).commit()
    }

    /**
     * 用匹配正文语言的已选音色完整朗读当前页。
     *
     * 使用方法：
     * 阅读器在主线程传入整页原文和页面级[utteranceId]。控制器会先让旧代际及其延迟任务失效，再用
     * QUEUE_FLUSH提交第一块；后续块只在前一块完成后继续，因此[onUtteranceFinished]只会在整页最后
     * 一块完成后触发。调用[stop]、[shutdown]或再次调用本函数，旧任务的完成、范围和错误回调都会被
     * 代际及内部块标识拒绝。
     *
     * @param text 要朗读的当前页完整正文，分块时不会改写原文字符。
     * @param languageCode 正文语言代码zh或en。
     * @param utteranceId 本次整页朗读唯一标识，所有对外回调会原样返回。
     * @param naturalReadingEnabled true表示按句分块并应用轻微韵律和句间停顿；false仅做长度安全切块。
     * @return 第一块已经提交给系统TTS返回true；未就绪、缺少音色、正文为空或提交失败返回false。
     */
    fun speak(
        text: String,
        languageCode: String,
        utteranceId: String,
        naturalReadingEnabled: Boolean = false
    ): Boolean {
        val currentEngine = engine
        if (released || state != EbookReadAloudState.READY || currentEngine == null || text.isBlank()) {
            return false
        }
        val chunks = buildEbookSpeechChunks(
            text = text,
            maxChunkLength = TextToSpeech.getMaxSpeechInputLength(),
            naturalReadingEnabled = naturalReadingEnabled
        )
        if (chunks.isEmpty()) return false

        val languageVoices = voicesForLanguage(languageCode)
        val selectedName = selectedVoiceName(languageCode)
        val selectedVoice = languageVoices.firstOrNull { voice -> voice.name == selectedName }
            ?: languageVoices.firstOrNull()
            ?: return false

        // 新整页任务在设置音色和提交前先使旧代际失效；旧引擎回调即使随后到达也找不到匹配任务。
        invalidateActiveSpeechRequest()
        currentEngine.stop()
        if (currentEngine.setVoice(selectedVoice) != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to select ebook Android TTS voice")
            onSpeakingChanged(false)
            return false
        }

        val request = ActiveEbookSpeechRequest(
            generation = speechGeneration,
            parentUtteranceId = utteranceId,
            chunks = chunks
        )
        activeSpeechRequest = request
        if (!submitActiveSpeechChunk(request, chunkIndex = 0, queueMode = TextToSpeech.QUEUE_FLUSH)) {
            invalidateActiveSpeechRequest()
            currentEngine.stop()
            resetEbookSpeechProsody()
            onSpeakingChanged(false)
            Log.e(TAG, "Failed to submit first ebook chunk to Android TTS")
            return false
        }
        return true
    }

    /**
     * 停止当前朗读但保留TTS实例和用户设置。
     *
     * 使用方法：
     * 用户关闭连续朗读、切换书籍或主动取消时调用。函数先清除整页任务并递增代际，再停止引擎，因而
     * 已经排队的onDone、onRangeStart、onError和句间延迟任务都不能继续推进页面。
     *
     * @return 系统成功停止返回true；引擎尚未建立返回false。
     */
    fun stop(): Boolean {
        invalidateActiveSpeechRequest()
        val currentEngine = engine
        if (currentEngine == null) {
            onSpeakingChanged(false)
            return false
        }
        val stopped = currentEngine.stop() == TextToSpeech.SUCCESS
        resetEbookSpeechProsody()
        onSpeakingChanged(false)
        return stopped
    }

    /**
     * 停止朗读并释放系统TTS连接。
     *
     * 使用方法：
     * 阅读器离开组合时调用。函数会取消当前块、句间延迟和尚未处理的主线程回调，然后释放系统引擎；
     * 重复调用不会再次触发外部状态。
     *
     * @return 无返回值；重复调用安全。
     */
    fun shutdown() {
        if (released) return
        released = true
        invalidateActiveSpeechRequest()
        mainHandler.removeCallbacksAndMessages(null)
        engine?.stop()
        engine?.shutdown()
        engine = null
        voices = emptyList()
        state = EbookReadAloudState.RELEASED
    }

    /**
     * 在TTS引擎就绪后收集离线中英文音色并注册朗读进度回调。
     *
     * @param initializationStatus Android TTS初始化结果码。
     * @return 无返回值，通过构造回调报告结果。
     */
    private fun configureEngine(initializationStatus: Int) {
        if (released) return
        val currentEngine = engine
        if (initializationStatus != TextToSpeech.SUCCESS || currentEngine == null) {
            Log.e(TAG, "Failed to initialize ebook Android TTS")
            updateState(EbookReadAloudState.ERROR)
            return
        }

        voices = currentEngine.voices.orEmpty()
            .filter { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale.language in SUPPORTED_LANGUAGE_CODES
            }
            .sortedWith(compareBy({ it.locale.language }, { it.locale.country }, Voice::getName))
        if (voices.isEmpty()) {
            Log.w(TAG, "No offline Chinese or English TTS voice is installed")
            updateState(EbookReadAloudState.MISSING_OFFLINE_VOICE)
            return
        }

        currentEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { handleEbookSpeechChunkStarted(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                mainHandler.post {
                    handleEbookSpeechChunkRangeChanged(utteranceId, start, end)
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post { handleEbookSpeechChunkFinished(utteranceId) }
            }

            @Deprecated("Android仍会在旧引擎上调用该回调")
            override fun onError(utteranceId: String?) {
                mainHandler.post { handleEbookSpeechChunkError(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { handleEbookSpeechChunkError(utteranceId) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                mainHandler.post { handleEbookSpeechChunkStopped(utteranceId, interrupted) }
            }
        })
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (currentEngine.setAudioAttributes(audioAttributes) != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Android TTS rejected ebook media audio attributes")
        }
        currentEngine.setSpeechRate(speechRate())
        updateState(EbookReadAloudState.READY)
    }

    /**
     * 向Android TTS提交当前整页任务中的指定块。
     *
     * 使用方法：
     * 第一块由[speak]使用QUEUE_FLUSH调用，后续块只在上一块完成后使用QUEUE_ADD调用。函数在提交前
     * 写入内部块标识和韵律，回调必须同时匹配任务代际及该内部标识才会被接受。
     *
     * @param request 当前整页任务。
     * @param chunkIndex 要提交的零基块序号。
     * @param queueMode Android TTS的QUEUE_FLUSH或QUEUE_ADD模式。
     * @return 韵律设置和块提交均成功返回true，否则返回false。
     */
    private fun submitActiveSpeechChunk(
        request: ActiveEbookSpeechRequest,
        chunkIndex: Int,
        queueMode: Int
    ): Boolean {
        if (
            released ||
            activeSpeechRequest !== request ||
            request.generation != speechGeneration ||
            chunkIndex !in request.chunks.indices
        ) {
            return false
        }
        val currentEngine = engine ?: return false
        val chunk = request.chunks[chunkIndex]
        val effectiveRate = (speechRate() * chunk.speechRateMultiplier)
            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        if (currentEngine.setPitch(chunk.pitch) != TextToSpeech.SUCCESS) return false
        if (currentEngine.setSpeechRate(effectiveRate) != TextToSpeech.SUCCESS) return false

        val engineUtteranceId = "${INTERNAL_UTTERANCE_PREFIX}${request.generation}_$chunkIndex"
        request.currentChunkIndex = chunkIndex
        request.currentEngineUtteranceId = engineUtteranceId
        request.currentChunkFallbackRangeReported = false
        request.currentChunkPreciseRangeReported = false
        val result = currentEngine.speak(chunk.text, queueMode, null, engineUtteranceId)
        if (result != TextToSpeech.SUCCESS) {
            request.currentEngineUtteranceId = ""
            return false
        }
        return true
    }

    /**
     * 处理当前块开始事件，并提供不依赖onRangeStart的整块高亮后备。
     *
     * @param engineUtteranceId Android TTS内部块标识。
     * @return 无返回值；旧代际或非当前块事件会被忽略。
     */
    private fun handleEbookSpeechChunkStarted(engineUtteranceId: String?) {
        val request = matchingActiveSpeechRequest(engineUtteranceId) ?: return
        val chunk = request.chunks.getOrNull(request.currentChunkIndex) ?: return
        if (!request.speakingReported) {
            request.speakingReported = true
            onSpeakingChanged(true)
        }
        if (
            !request.currentChunkFallbackRangeReported &&
            !request.currentChunkPreciseRangeReported
        ) {
            request.currentChunkFallbackRangeReported = true
            onUtteranceRangeChanged(
                request.parentUtteranceId,
                chunk.startOffset,
                chunk.endOffsetExclusive
            )
        }
    }

    /**
     * 把当前块的精确范围回调映射为整页位置并分发给阅读器。
     *
     * @param engineUtteranceId Android TTS内部块标识。
     * @param chunkStart 块内UTF-16起点。
     * @param chunkEndExclusive 块内UTF-16不包含式终点。
     * @return 无返回值；无效或过期范围会被忽略。
     */
    private fun handleEbookSpeechChunkRangeChanged(
        engineUtteranceId: String?,
        chunkStart: Int,
        chunkEndExclusive: Int
    ) {
        val request = matchingActiveSpeechRequest(engineUtteranceId) ?: return
        val chunk = request.chunks.getOrNull(request.currentChunkIndex) ?: return
        val mappedRange = mapEbookSpeechChunkRange(
            chunk = chunk,
            chunkStart = chunkStart,
            chunkEndExclusive = chunkEndExclusive
        ) ?: return
        request.currentChunkPreciseRangeReported = true
        if (!request.speakingReported) {
            request.speakingReported = true
            onSpeakingChanged(true)
        }
        onUtteranceRangeChanged(
            request.parentUtteranceId,
            mappedRange.startOffset,
            mappedRange.endOffsetExclusive
        )
    }

    /**
     * 处理一个块完成事件，并在自然停顿后继续下一块或结束整页。
     *
     * @param engineUtteranceId Android TTS内部块标识。
     * @return 无返回值；整页完成回调只会由最后一块的有效事件触发一次。
     */
    private fun handleEbookSpeechChunkFinished(engineUtteranceId: String?) {
        val request = matchingActiveSpeechRequest(engineUtteranceId) ?: return
        val completedChunk = request.chunks.getOrNull(request.currentChunkIndex) ?: return
        val nextChunkIndex = request.currentChunkIndex + 1
        request.currentEngineUtteranceId = ""
        request.currentChunkFallbackRangeReported = false
        request.currentChunkPreciseRangeReported = false

        val continuation = {
            if (activeSpeechRequest !== request || request.generation != speechGeneration) {
                Unit
            } else if (nextChunkIndex >= request.chunks.size) {
                finishActiveSpeechRequest(request)
            } else if (!submitActiveSpeechChunk(request, nextChunkIndex, TextToSpeech.QUEUE_ADD)) {
                failActiveSpeechRequest(request, "Failed to submit next ebook chunk to Android TTS")
            }
        }
        if (completedChunk.pauseAfterMillis <= 0L) {
            continuation()
            return
        }

        val runnable = Runnable {
            pendingSpeechContinuation = null
            continuation()
        }
        pendingSpeechContinuation = runnable
        if (!mainHandler.postDelayed(runnable, completedChunk.pauseAfterMillis)) {
            pendingSpeechContinuation = null
            failActiveSpeechRequest(request, "Failed to schedule ebook speech continuation")
        }
    }

    /**
     * 处理Android TTS对当前块报告的错误。
     *
     * @param engineUtteranceId Android TTS内部块标识。
     * @return 无返回值；首次有效错误会终止整页，随后重复错误因任务已清除而被忽略。
     */
    private fun handleEbookSpeechChunkError(engineUtteranceId: String?) {
        val request = matchingActiveSpeechRequest(engineUtteranceId) ?: return
        failActiveSpeechRequest(request, "Android TTS failed while reading an ebook chunk")
    }

    /**
     * 处理并非由本控制器主动失效产生的TTS停止事件。
     *
     * @param engineUtteranceId Android TTS内部块标识。
     * @param interrupted true表示系统报告本块被中断。
     * @return 无返回值；主动stop、shutdown或新QUEUE_FLUSH已经清除任务，因此不会误报失败。
     */
    private fun handleEbookSpeechChunkStopped(engineUtteranceId: String?, interrupted: Boolean) {
        val request = matchingActiveSpeechRequest(engineUtteranceId) ?: return
        val message = if (interrupted) {
            "Android TTS interrupted an active ebook chunk"
        } else {
            "Android TTS stopped an active ebook chunk"
        }
        failActiveSpeechRequest(request, message)
    }

    /**
     * 返回内部标识、任务代际和当前块均匹配的活动整页任务。
     *
     * @param engineUtteranceId Android TTS回传的内部块标识。
     * @return 匹配任务；空标识、旧代际或旧块返回null。
     */
    private fun matchingActiveSpeechRequest(
        engineUtteranceId: String?
    ): ActiveEbookSpeechRequest? {
        val request = activeSpeechRequest ?: return null
        return request.takeIf {
            !released &&
                request.generation == speechGeneration &&
                engineUtteranceId != null &&
                request.currentEngineUtteranceId == engineUtteranceId
        }
    }

    /**
     * 正常结束当前整页任务并且只分发一次页面级完成事件。
     *
     * @param request 待结束的活动任务。
     * @return 无返回值；任务已经被替换时不会产生回调。
     */
    private fun finishActiveSpeechRequest(request: ActiveEbookSpeechRequest) {
        if (activeSpeechRequest !== request || request.generation != speechGeneration) return
        val parentUtteranceId = request.parentUtteranceId
        invalidateActiveSpeechRequest()
        resetEbookSpeechProsody()
        onSpeakingChanged(false)
        onUtteranceFinished(parentUtteranceId)
    }

    /**
     * 异常结束当前整页任务并且只分发一次页面级失败事件。
     *
     * @param request 待结束的活动任务。
     * @param logMessage 不含正文的英文错误日志。
     * @return 无返回值；任务已经被清除时不会重复记录或回调。
     */
    private fun failActiveSpeechRequest(
        request: ActiveEbookSpeechRequest,
        logMessage: String
    ) {
        if (activeSpeechRequest !== request || request.generation != speechGeneration) return
        val parentUtteranceId = request.parentUtteranceId
        invalidateActiveSpeechRequest()
        engine?.stop()
        resetEbookSpeechProsody()
        Log.e(TAG, logMessage)
        onSpeakingChanged(false)
        onUtteranceFailed(parentUtteranceId)
    }

    /**
     * 取消句间延迟、清除活动任务并递增回调代际。
     *
     * 使用方法：
     * 在stop、shutdown、新整页QUEUE_FLUSH以及任何终态之前调用。函数不直接停止TTS，调用方可先让
     * 状态失效，再安全调用引擎stop而不会把随后到达的onStop误判为错误。
     *
     * @return 无返回值。
     */
    private fun invalidateActiveSpeechRequest() {
        pendingSpeechContinuation?.let { runnable -> mainHandler.removeCallbacks(runnable) }
        pendingSpeechContinuation = null
        activeSpeechRequest = null
        speechGeneration = if (speechGeneration == Long.MAX_VALUE) 0L else speechGeneration + 1L
    }

    /**
     * 把引擎韵律恢复为用户保存的速度和标准音高。
     *
     * @return 无返回值；引擎不存在或拒绝设置时无需额外报错，因为当前朗读已经结束。
     */
    private fun resetEbookSpeechProsody() {
        engine?.setPitch(1f)
        engine?.setSpeechRate(speechRate())
    }

    /** @return 指定语言的离线系统Voice。 */
    private fun voicesForLanguage(languageCode: String): List<Voice> {
        val normalizedLanguage = languageCode.lowercase(Locale.ROOT)
        return voices.filter { voice -> voice.locale.language == normalizedLanguage }
    }

    /** @return 面向中文界面的音色说明。 */
    private fun buildVoiceDisplayName(voice: Voice): String {
        val localeName = voice.locale.getDisplayName(Locale.CHINA).ifBlank { voice.locale.toLanguageTag() }
        return "$localeName · ${voice.name}"
    }

    /** @return 中英文各自独立的首选音色键。 */
    private fun voicePreferenceKey(languageCode: String): String {
        return if (languageCode.equals(Locale.CHINESE.language, ignoreCase = true)) {
            KEY_CHINESE_VOICE
        } else {
            KEY_ENGLISH_VOICE
        }
    }

    /** @return 无返回值，通过回调分发新状态。 */
    private fun updateState(newState: EbookReadAloudState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val DEFAULT_SPEECH_RATE = 0.9f

        private const val TAG = "EbookReadAloud"
        private const val PREFERENCES_NAME = "harley_ebook_reader_settings"
        private const val KEY_CHINESE_VOICE = "chinese_voice"
        private const val KEY_ENGLISH_VOICE = "english_voice"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val INTERNAL_UTTERANCE_PREFIX = "ebook_internal_chunk_"
        private val SUPPORTED_LANGUAGE_CODES = setOf(
            Locale.CHINESE.language,
            Locale.ENGLISH.language
        )
    }
}

/**
 * 根据正文中中日韩统一表意文字的占比推测朗读语言。
 *
 * 使用方法：
 * 阅读器在每页开始朗读前调用；外文页面默认按英语处理。
 *
 * @param text 当前页正文。
 * @return 中文页面返回zh，否则返回en。
 */
internal fun detectEbookLanguageCode(text: String): String {
    val meaningful = text.asSequence().filter(Char::isLetter).take(LANGUAGE_SAMPLE_SIZE).toList()
    if (meaningful.isEmpty()) return Locale.ENGLISH.language
    val chineseCount = meaningful.count { character -> character.code in CHINESE_UNICODE_RANGE }
    return if (chineseCount * 2 >= meaningful.size) {
        Locale.CHINESE.language
    } else {
        Locale.ENGLISH.language
    }
}

private const val LANGUAGE_SAMPLE_SIZE = 240
private const val MIN_TTS_CHUNK_LENGTH = 2
private val CHINESE_UNICODE_RANGE = 0x4E00..0x9FFF
private val NATURAL_SPEECH_SOFT_BREAK_CHARACTERS = setOf(',', '，', '、', ':', '：')
private val NATURAL_SPEECH_CLOSING_CHARACTERS = setOf(
    '"', '\'', '”', '’', '」', '』', '》', ')', '）', ']', '】'
)
