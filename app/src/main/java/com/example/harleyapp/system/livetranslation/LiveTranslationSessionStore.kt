package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationSessionStatus
import com.example.harleyapp.model.LiveTranslationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 在页面控制器与前台服务之间共享实时翻译事实快照的进程内状态仓库。
 *
 * 使用方法：
 * 页面只观察[snapshot]；服务通过语义明确的发布函数更新。每次更新都在锁内基于最新快照复制，
 * 避免音量、字幕和停止事件互相覆盖。仓库不持有音频、投屏令牌或字幕历史，进程结束后自然清空。
 */
object LiveTranslationSessionStore {

    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(LiveTranslationSnapshot())

    /** 当前实时翻译会话的只读快照。 */
    val snapshot: StateFlow<LiveTranslationSnapshot> = mutableSnapshot.asStateFlow()

    /** @return 发布服务正在初始化，并清除上一会话字幕和错误。 */
    fun publishStarting() = update {
        LiveTranslationSnapshot(sessionStatus = LiveTranslationSessionStatus.STARTING)
    }

    /** @return 发布服务已经开始读取播放声音。 */
    fun publishRunning() = update { current ->
        current.copy(
            sessionStatus = LiveTranslationSessionStatus.RUNNING,
            captureStatus = LiveTranslationCaptureStatus.CAPTURING,
            errorMessage = null
        )
    }

    /**
     * 发布最近声音电平及静音状态，不改动现有字幕。
     *
     * @param captureStatus CAPTURING或SILENT。
     * @param audioLevelDb 当前PCM窗口dBFS。
     * @return 无返回值。
     */
    fun publishAudio(
        captureStatus: LiveTranslationCaptureStatus,
        audioLevelDb: Float
    ) = update { current ->
        current.copy(captureStatus = captureStatus, audioLevelDb = audioLevelDb)
    }

    /**
     * 发布当前外语原文；partial更新时保留上一条中文直到新final翻译完成。
     *
     * @param sourceText 最新识别原文。
     * @param clearTranslation true表示新final已确认，应清除不再对应的上一条译文。
     * @return 无返回值。
     */
    fun publishSource(sourceText: String, clearTranslation: Boolean) = update { current ->
        current.copy(
            sourceText = sourceText,
            translatedText = if (clearTranslation) "" else current.translatedText
        )
    }

    /**
     * 发布与指定原文对应的简体中文译文。
     *
     * @param sourceText 提交翻译时的原文，用于防止旧异步结果覆盖新字幕。
     * @param translatedText 本地翻译结果。
     * @return 当前原文仍匹配且成功发布时返回true，否则返回false。
     */
    fun publishTranslation(sourceText: String, translatedText: String): Boolean {
        synchronized(lock) {
            val current = mutableSnapshot.value
            if (current.sourceText != sourceText) return false
            mutableSnapshot.value = current.copy(translatedText = translatedText)
            return true
        }
    }

    /**
     * 仅在当前字幕仍对应指定原文时清空原文和译文，供服务安全执行字幕到期任务。
     *
     * 使用方法：
     * final或译文发布后，服务保存当时的原文并延迟3到5秒调用。若期间已经出现新partial/final，
     * 原文比较会拒绝旧任务，避免上一句的定时器清掉下一句字幕。采集状态、错误和音量不会改变。
     *
     * @param sourceText 创建到期任务时对应的非持久化原文。
     * @return 原文仍匹配并已经清空时返回true；字幕已经更新时返回false。
     */
    fun clearCaptionIfSourceMatches(sourceText: String): Boolean {
        synchronized(lock) {
            val current = mutableSnapshot.value
            if (current.sourceText != sourceText) return false
            mutableSnapshot.value = current.copy(sourceText = "", translatedText = "")
            return true
        }
    }

    /** @return 发布服务正在按顺序释放采集、识别和悬浮窗资源。 */
    fun publishStopping() = update { current ->
        current.copy(sessionStatus = LiveTranslationSessionStatus.STOPPING)
    }

    /** @return 发布系统撤销投屏令牌，保留最后字幕供页面查看。 */
    fun publishProjectionRevoked() = update { current ->
        current.copy(captureStatus = LiveTranslationCaptureStatus.PROJECTION_REVOKED)
    }

    /**
     * 发布用户可见运行错误，同时不暴露内部路径、URL或字幕正文。
     *
     * @param message 面向用户的中文错误说明。
     * @return 无返回值。
     */
    fun publishError(message: String) = update { current ->
        current.copy(
            sessionStatus = LiveTranslationSessionStatus.ERROR,
            captureStatus = LiveTranslationCaptureStatus.IDLE,
            errorMessage = message
        )
    }

    /**
     * 恢复完全空闲状态并清除上一会话内存字幕。
     *
     * @return 无返回值；重复调用安全。
     */
    fun resetToIdle() = update { LiveTranslationSnapshot() }

    /** @return 在互斥锁内基于最新值执行一次不可变快照替换。 */
    private inline fun update(transform: (LiveTranslationSnapshot) -> LiveTranslationSnapshot) {
        synchronized(lock) {
            mutableSnapshot.value = transform(mutableSnapshot.value)
        }
    }
}
