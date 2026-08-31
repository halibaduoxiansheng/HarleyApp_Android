package com.example.harleyapp.system

import android.content.Context
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
 * @param onUtteranceFinished 一页完整朗读结束后的回调，用于连续翻到下一页。
 * @param onUtteranceFailed 系统朗读当前页失败后的回调，用于停止连续阅读并提示用户。
 */
class EbookReadAloudController(
    context: Context,
    private val onStateChanged: (EbookReadAloudState) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onUtteranceFinished: (String) -> Unit,
    private val onUtteranceFailed: (String) -> Unit
) {

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
     * 用匹配正文语言的已选音色朗读当前页。
     *
     * @param text 要朗读的当前页正文。
     * @param languageCode 正文语言代码zh或en。
     * @param utteranceId 本次朗读唯一标识，完成回调会原样返回。
     * @return 已提交给系统TTS返回true；未就绪、缺少音色或正文为空返回false。
     */
    fun speak(text: String, languageCode: String, utteranceId: String): Boolean {
        val currentEngine = engine
        if (released || state != EbookReadAloudState.READY || currentEngine == null || text.isBlank()) {
            return false
        }
        val languageVoices = voicesForLanguage(languageCode)
        val selectedName = selectedVoiceName(languageCode)
        val selectedVoice = languageVoices.firstOrNull { voice -> voice.name == selectedName }
            ?: languageVoices.firstOrNull()
            ?: return false
        if (currentEngine.setVoice(selectedVoice) != TextToSpeech.SUCCESS) return false
        if (currentEngine.setSpeechRate(speechRate()) != TextToSpeech.SUCCESS) return false

        val safeText = text.take(TextToSpeech.getMaxSpeechInputLength())
        val result = currentEngine.speak(
            safeText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to submit ebook text to Android TTS")
            return false
        }
        return true
    }

    /**
     * 停止当前朗读但保留TTS实例和用户设置。
     *
     * @return 系统成功停止返回true；引擎尚未建立返回false。
     */
    fun stop(): Boolean {
        val currentEngine = engine ?: return false
        val stopped = currentEngine.stop() == TextToSpeech.SUCCESS
        mainHandler.post { onSpeakingChanged(false) }
        return stopped
    }

    /**
     * 停止朗读并释放系统TTS连接。
     *
     * @return 无返回值；重复调用安全。
     */
    fun shutdown() {
        if (released) return
        released = true
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
                mainHandler.post { onSpeakingChanged(true) }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onSpeakingChanged(false)
                    utteranceId?.let(onUtteranceFinished)
                }
            }

            @Deprecated("Android仍会在旧引擎上调用该回调")
            override fun onError(utteranceId: String?) {
                handleUtteranceError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleUtteranceError(utteranceId)
            }
        })
        currentEngine.setSpeechRate(speechRate())
        updateState(EbookReadAloudState.READY)
    }

    /**
     * 统一处理新旧Android TTS错误回调。
     *
     * @param utteranceId 失败朗读标识，仅用于英文日志定位，不包含正文。
     * @return 无返回值。
     */
    private fun handleUtteranceError(utteranceId: String?) {
        Log.e(TAG, "Android TTS failed for utterance: ${utteranceId.orEmpty()}")
        mainHandler.post {
            onSpeakingChanged(false)
            onUtteranceFailed(utteranceId.orEmpty())
        }
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
private val CHINESE_UNICODE_RANGE = 0x4E00..0x9FFF
