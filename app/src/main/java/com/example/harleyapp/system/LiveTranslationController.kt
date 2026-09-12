package com.example.harleyapp.system

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.harleyapp.model.LiveTranslationSettings
import com.example.harleyapp.model.LiveTranslationSnapshot
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import com.example.harleyapp.system.livetranslation.LiveTranslationModelManager
import com.example.harleyapp.system.livetranslation.LiveTranslationService
import com.example.harleyapp.system.livetranslation.LiveTranslationSessionStore
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接Compose页面、Android授权页面和实时翻译前台服务的轻量控制器。
 *
 * 使用方法：
 * 页面用应用Context创建一次并观察[sessionSnapshot]、[modelSnapshot]。先调用[prepareModels]准备模型，
 * 再按录音权限、悬浮窗权限和[mediaProjectionIntent]顺序取得用户授权，最后把结果交给[start]。
 * 控制器不持有MediaProjection或音频对象，页面销毁不会意外终止正在运行的服务。
 *
 * @param context 任意Android Context，内部只保存applicationContext。
 */
class LiveTranslationController(context: Context) {

    private val appContext = context.applicationContext
    private val modelManager = LiveTranslationModelManager.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 前台服务发布的当前采集、识别和字幕状态。 */
    val sessionSnapshot: StateFlow<LiveTranslationSnapshot> = LiveTranslationSessionStore.snapshot

    /** 本地ASR及英日翻译模型的准备状态。 */
    val modelSnapshot = modelManager.snapshot

    /**
     * 重新读取磁盘上的模型事实；服务状态由进程级状态仓库保持，无需轮询。
     *
     * @return 无返回值。
     */
    fun refresh() {
        modelManager.refresh()
    }

    /**
     * 响应用户操作，后台下载并校验共享英日ASR以及两套本地翻译模型。
     *
     * @return 无返回值，进度由[modelSnapshot]持续发布。
     */
    fun prepareModels() {
        modelManager.prepareModels()
    }

    /**
     * 取消模型准备任务，保留可续传下载片段。
     *
     * @return 无返回值；没有活动任务时重复调用安全。
     */
    fun cancelModelPreparation() {
        modelManager.cancelPreparation()
    }

    /** @return Android 10及以上支持AudioPlaybackCapture时返回true。 */
    fun isPlaybackCaptureSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /** @return 系统当前允许本应用显示跨应用字幕浮层时返回true。 */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /**
     * 创建只针对本应用的系统悬浮窗授权页Intent。
     *
     * @return 可交给Activity Result Launcher启动的Intent。
     */
    fun overlayPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        )
    }

    /**
     * 创建本应用详情设置页Intent，用于恢复被系统禁止再次询问的录音权限。
     *
     * 使用方法：
     * 页面确认录音权限未授予，且系统不再显示标准权限弹窗时，把本Intent交给Activity Result
     * Launcher。用户返回后必须重新读取真实权限状态，不能把设置页返回本身当作授权成功。
     *
     * @return 只定位到HarleyApp的系统应用详情设置页Intent。
     */
    fun applicationSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${appContext.packageName}")
        )
    }

    /**
     * 判断本应用是否曾主动发起过录音权限请求。
     *
     * @return 曾请求过返回true；首次进入或应用数据已清除返回false。
     */
    fun hasRequestedRecordAudioPermission(): Boolean {
        return preferences.getBoolean(KEY_RECORD_AUDIO_PERMISSION_REQUESTED, false)
    }

    /**
     * 在启动Android录音权限弹窗前记录已经请求，供页面区分“首次尚未询问”和“系统不再询问”。
     *
     * @return 无返回值。
     */
    fun markRecordAudioPermissionRequested() {
        preferences.edit()
            .putBoolean(KEY_RECORD_AUDIO_PERMISSION_REQUESTED, true)
            .apply()
    }

    /**
     * 创建Android系统播放音频捕获授权Intent。
     *
     * 使用方法：
     * 每次会话都必须从可见Activity启动并取得新的结果，不能复用旧令牌。
     *
     * @return 系统MediaProjection授权Intent。
     * @throws IllegalStateException Android 10以下调用时抛出。
     */
    fun mediaProjectionIntent(): Intent {
        check(isPlaybackCaptureSupported()) { "Playback capture requires Android 10 or newer" }
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    /**
     * 用本次新取得的MediaProjection授权启动实时翻译前台服务。
     *
     * 使用方法：
     * 只在Activity Result为[Activity.RESULT_OK]且data非空时调用。函数会再次检查模型、权限和会话
     * 状态，保存规范化字幕设置，并立刻启动声明为mediaProjection类型的前台服务。
     *
     * @param resultCode 系统授权Activity返回码，必须为RESULT_OK。
     * @param data 系统本次返回且尚未消费的投屏授权Intent。
     * @param settings 用户选择的源语言和字幕显示设置。
     * @return 无返回值。
     */
    fun start(
        resultCode: Int,
        data: Intent,
        settings: LiveTranslationSettings
    ) {
        check(resultCode == Activity.RESULT_OK) { "Media projection permission was not granted" }
        check(isPlaybackCaptureSupported()) { "Playback capture requires Android 10 or newer" }
        check(hasOverlayPermission()) { "Overlay permission is not granted" }
        check(
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Record audio permission is not granted" }
        check(modelSnapshot.value.isReady) { "Live translation models are not ready" }
        check(sessionSnapshot.value.canStart) {
            "A live translation session is already running"
        }

        val normalizedSettings = settings.normalized()
        saveSettings(normalizedSettings)
        val serviceIntent = LiveTranslationService.createStartIntent(
            context = appContext,
            resultCode = resultCode,
            projectionData = data,
            settings = normalizedSettings
        )
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    /**
     * 请求当前服务有序停止并立即使后续异步结果失效。
     *
     * @return 无返回值；服务未运行时会把页面状态恢复为空闲。
     */
    fun stop() {
        val stopIntent = LiveTranslationService.createStopIntent(appContext)
        if (runCatching { appContext.startService(stopIntent) }.getOrNull() == null) {
            LiveTranslationSessionStore.resetToIdle()
        }
    }

    /**
     * 从应用私有偏好读取并规范化字幕配置。
     *
     * @return 可直接交给页面和前台服务的安全设置。
     */
    fun loadSettings(): LiveTranslationSettings {
        val sourceLanguage = runCatching {
            LiveTranslationSourceLanguage.valueOf(
                preferences.getString(KEY_SOURCE_LANGUAGE, null)
                    ?: LiveTranslationSourceLanguage.ENGLISH.name
            )
        }.getOrDefault(LiveTranslationSourceLanguage.ENGLISH)
        return LiveTranslationSettings(
            showSourceText = preferences.getBoolean(KEY_SHOW_SOURCE_TEXT, true),
            textSizeSp = preferences.getInt(KEY_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP),
            backgroundOpacityPercent = preferences.getInt(
                KEY_BACKGROUND_OPACITY_PERCENT,
                DEFAULT_BACKGROUND_OPACITY_PERCENT
            ),
            sourceLanguage = sourceLanguage
        ).normalized()
    }

    /**
     * 保存完整且已规范化的实时字幕配置。
     *
     * @param settings 页面当前选择；异常数值会在写入前自动收敛到支持范围。
     * @return 无返回值。
     */
    fun saveSettings(settings: LiveTranslationSettings) {
        val normalizedSettings = settings.normalized()
        preferences.edit()
            .putBoolean(KEY_SHOW_SOURCE_TEXT, normalizedSettings.showSourceText)
            .putInt(KEY_TEXT_SIZE_SP, normalizedSettings.textSizeSp)
            .putInt(
                KEY_BACKGROUND_OPACITY_PERCENT,
                normalizedSettings.backgroundOpacityPercent
            )
            .putString(KEY_SOURCE_LANGUAGE, normalizedSettings.sourceLanguage.name)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "harley_live_translation_settings"
        const val KEY_SHOW_SOURCE_TEXT = "show_source_text"
        const val KEY_TEXT_SIZE_SP = "text_size_sp"
        const val KEY_BACKGROUND_OPACITY_PERCENT = "background_opacity_percent"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_RECORD_AUDIO_PERMISSION_REQUESTED = "record_audio_permission_requested"
        const val DEFAULT_TEXT_SIZE_SP = 22
        const val DEFAULT_BACKGROUND_OPACITY_PERCENT = 72
    }
}
