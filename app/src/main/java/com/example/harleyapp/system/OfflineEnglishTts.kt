package com.example.harleyapp.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Android离线英语TTS当前可用状态。 */
enum class OfflineEnglishTtsState {
    INITIALIZING,
    READY,
    MISSING_OFFLINE_VOICE,
    ERROR
}

/**
 * 只使用Android系统中已安装离线英语语音的朗读控制器。
 *
 * 使用方法：
 * 页面上层创建一个进程内实例，把状态回调保存到Compose状态；[speak]可朗读单词或英文例句，
 * 页面整体销毁时必须调用[shutdown]释放TTS服务。控制器会明确排除需要网络连接的Voice，找不到
 * 离线英语语音时返回不可用状态，不会静默切换成联网朗读。
 *
 * @param context Android上下文，内部只保留Application Context。
 * @param onStateChanged 初始化或释放时的状态回调。
 */
class OfflineEnglishTts(
    context: Context,
    private val onStateChanged: (OfflineEnglishTtsState) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var state = OfflineEnglishTtsState.INITIALIZING
    private var released = false

    init {
        val applicationContext = context.applicationContext
        engine = TextToSpeech(applicationContext) { status ->
            // 即使某个TTS实现同步回调，也推迟到主线程队列，确保engine已经完成赋值。
            mainHandler.post {
                configureEngine(status)
            }
        }
    }

    /**
     * 使用当前离线英语语音朗读文本，并中止上一段尚未完成的朗读。
     *
     * @param text 英文单词或完整英文例句；空白文本不会提交给系统TTS。
     * @return 已成功提交朗读请求返回true；尚未就绪、缺少离线语音或提交失败返回false。
     */
    fun speak(text: String): Boolean {
        val currentEngine = engine
        if (released || state != OfflineEnglishTtsState.READY ||
            currentEngine == null || text.isBlank()
        ) {
            return false
        }

        val result = currentEngine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "english_${System.nanoTime()}"
        )
        return result == TextToSpeech.SUCCESS
    }

    /**
     * 停止当前朗读并释放系统TTS连接。
     *
     * @return 无返回值；重复调用是安全的。
     */
    fun shutdown() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        engine?.stop()
        engine?.shutdown()
        engine = null
        state = OfflineEnglishTtsState.ERROR
    }

    /**
     * 在TTS引擎完成初始化后选择不依赖网络的英语Voice。
     *
     * @param initializationStatus Android TTS初始化结果码。
     * @return 无返回值，通过状态回调报告最终结果。
     */
    private fun configureEngine(initializationStatus: Int) {
        if (released) return

        val currentEngine = engine
        if (initializationStatus != TextToSpeech.SUCCESS || currentEngine == null) {
            Log.e(TAG, "Failed to initialize Android TTS")
            updateState(OfflineEnglishTtsState.ERROR)
            return
        }

        val offlineVoice = currentEngine.voices
            .orEmpty()
            .asSequence()
            .filter { voice ->
                voice.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired
            }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> { voice ->
                    voice.locale.country.equals(Locale.US.country, ignoreCase = true)
                }.thenBy { voice -> voice.name }
            )
            .firstOrNull()

        if (offlineVoice == null) {
            Log.w(TAG, "No offline English TTS voice is installed")
            updateState(OfflineEnglishTtsState.MISSING_OFFLINE_VOICE)
            return
        }

        if (currentEngine.setVoice(offlineVoice) != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to select offline English TTS voice")
            updateState(OfflineEnglishTtsState.ERROR)
            return
        }

        currentEngine.setSpeechRate(DEFAULT_SPEECH_RATE)
        updateState(OfflineEnglishTtsState.READY)
    }

    /**
     * 保存并通知最新TTS状态，避免同一状态被重复分发。
     *
     * @param newState 新的离线TTS状态。
     * @return 无返回值。
     */
    private fun updateState(newState: OfflineEnglishTtsState) {
        if (state == newState) return
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        const val TAG = "OfflineEnglishTts"
        const val DEFAULT_SPEECH_RATE = 0.9f
    }
}
