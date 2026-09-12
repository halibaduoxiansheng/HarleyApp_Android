package com.example.harleyapp.model

/** 实时翻译字幕允许的最小字号，避免字幕小到无法辨认。 */
const val MIN_LIVE_TRANSLATION_TEXT_SIZE_SP = 16

/** 实时翻译字幕允许的最大字号，避免浮层完全遮住影片。 */
const val MAX_LIVE_TRANSLATION_TEXT_SIZE_SP = 36

/** 实时翻译字幕背景允许的最小不透明度，保证浅色画面上仍能看清文字。 */
const val MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT = 35

/** 实时翻译字幕背景允许的最大不透明度，保留少量影片画面作为位置参照。 */
const val MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT = 95

/**
 * 实时翻译当前支持的音频源语言。
 *
 * 目标语言首版固定为简体中文，调用方根据用户选择把本枚举保存在[LiveTranslationSettings]中，
 * 并在准备离线语音识别模型及启动会话时读取同一个值，避免界面选择与实际识别模型不一致。
 */
enum class LiveTranslationSourceLanguage {
    /** 英语音频，识别后翻译为简体中文。 */
    ENGLISH,

    /** 日语音频，识别后翻译为简体中文。 */
    JAPANESE
}

/**
 * 用户可以调整的实时翻译字幕显示设置。
 *
 * 使用方法：
 * 设置页读取保存值或用户输入后先调用[normalized]，再把结果保存并交给字幕浮层。这样旧版本、
 * 手工恢复或异常数据即使超出界面滑块范围，也不会生成不可读或完全遮挡画面的字幕。
 *
 * @param showSourceText true同时显示识别原文，false只显示中文译文。
 * @param textSizeSp 字幕字号，规范范围为16到36sp。
 * @param backgroundOpacityPercent 字幕背景不透明度百分比，规范范围为35到95。
 * @param sourceLanguage 待识别音频的源语言；首版支持英语或日语，目标语言固定为简体中文。
 */
data class LiveTranslationSettings(
    val showSourceText: Boolean = true,
    val textSizeSp: Int = 22,
    val backgroundOpacityPercent: Int = 72,
    val sourceLanguage: LiveTranslationSourceLanguage = LiveTranslationSourceLanguage.ENGLISH
) {

    /**
     * 把可能来自旧配置或外部恢复的数据限制到当前界面支持范围。
     *
     * 使用方法：
     * 仓库读取配置后和保存用户配置前均可调用；合法配置会按数据类值语义原样返回等价结果。
     *
     * @return 字号位于16到36、背景不透明度位于35到95的安全设置。
     */
    fun normalized(): LiveTranslationSettings {
        return copy(
            textSizeSp = textSizeSp.coerceIn(
                MIN_LIVE_TRANSLATION_TEXT_SIZE_SP,
                MAX_LIVE_TRANSLATION_TEXT_SIZE_SP
            ),
            backgroundOpacityPercent = backgroundOpacityPercent.coerceIn(
                MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT,
                MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT
            )
        )
    }

    /**
     * 把百分比背景不透明度转换成Android绘制可直接使用的0到1浮点数。
     *
     * 使用方法：
     * 浮层绘制前读取本属性即可；属性内部会先限制异常输入，不要求调用方提前调用[normalized]。
     *
     * @return 0.35到0.95之间的背景Alpha值。
     */
    val backgroundOpacityFraction: Float
        get() = backgroundOpacityPercent.coerceIn(
            MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT,
            MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT
        ) / 100f
}

/**
 * 本地语音识别和翻译模型的准备阶段。
 *
 * 页面通过[LiveTranslationModelSnapshot]观察本枚举。下载与安装必须发生在用户主动操作后；
 * READY表示后续音频和文字均可在本机处理，ERROR表示应向用户展示可重试的具体原因。
 */
enum class LiveTranslationModelStage {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLING,
    PREPARING_TRANSLATION,
    READY,
    ERROR
}

/**
 * 本地模型准备过程对界面公开的不可变快照。
 *
 * 使用方法：
 * 模型仓库在阶段、进度或说明变化时创建新实例并发布给界面。确定百分比时传0到100；无法计算
 * 总进度的安装或翻译模型准备阶段传null，让界面显示不定进度指示器。
 *
 * @param stage 当前模型阶段。
 * @param progressPercent 可确定的0到100进度；无法确定时为null。
 * @param message 可直接展示给用户的中文状态或错误说明。
 */
data class LiveTranslationModelSnapshot(
    val stage: LiveTranslationModelStage = LiveTranslationModelStage.NOT_INSTALLED,
    val progressPercent: Int? = null,
    val message: String = "尚未准备本地模型"
) {

    init {
        require(progressPercent == null || progressPercent in 0..100) {
            "Live translation model progress must be between 0 and 100"
        }
    }

    /** @return 模型已经可以执行本地识别和翻译时返回true，否则返回false。 */
    val isReady: Boolean
        get() = stage == LiveTranslationModelStage.READY

    /** @return 当前正在下载、安装或准备翻译模型时返回true，否则返回false。 */
    val isPreparing: Boolean
        get() = stage == LiveTranslationModelStage.DOWNLOADING ||
            stage == LiveTranslationModelStage.INSTALLING ||
            stage == LiveTranslationModelStage.PREPARING_TRANSLATION
}

/**
 * 一次跨应用实时翻译会话的整体生命周期。
 *
 * STARTING包含前台服务、投屏令牌、音频和模型初始化；RUNNING表示服务已经接管会话；STOPPING
 * 表示正在有序释放资源；ERROR表示会话已经失败且可以由用户重新开始。
 */
enum class LiveTranslationSessionStatus {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * 跨应用播放声音的采集状态。
 *
 * SILENT只表示一段时间内没有读到可用声音，不能据此断定影片App禁止内录；PROJECTION_REVOKED
 * 表示用户或系统已经撤销本次MediaProjection令牌，会话必须停止并重新授权。
 */
enum class LiveTranslationCaptureStatus {
    IDLE,
    CAPTURING,
    SILENT,
    PROJECTION_REVOKED
}

/**
 * 实时翻译服务向设置页和跨应用字幕浮层公开的单一事实快照。
 *
 * 使用方法：
 * 服务把本模型放入StateFlow，界面只收集快照，不直接持有AudioRecord、识别器或投屏令牌。异步
 * 识别和翻译结果必须先校验当前会话代际，再创建新快照，防止上一会话的迟到结果覆盖当前字幕。
 *
 * @param sessionStatus 会话整体生命周期。
 * @param captureStatus 当前声音采集状态。
 * @param sourceText 最近一条稳定或正在修订的外语原文。
 * @param translatedText 与当前原文对应的本地译文。
 * @param errorMessage 最近一次用户可见错误；正常状态为null。
 * @param audioLevelDb 最近音频窗口的RMS dBFS；尚无采样或完全静音时为负无穷。
 */
data class LiveTranslationSnapshot(
    val sessionStatus: LiveTranslationSessionStatus = LiveTranslationSessionStatus.IDLE,
    val captureStatus: LiveTranslationCaptureStatus = LiveTranslationCaptureStatus.IDLE,
    val sourceText: String = "",
    val translatedText: String = "",
    val errorMessage: String? = null,
    val audioLevelDb: Float = Float.NEGATIVE_INFINITY
) {

    /** @return 服务已经完成启动并处于持续会话时返回true。 */
    val isRunning: Boolean
        get() = sessionStatus == LiveTranslationSessionStatus.RUNNING

    /** @return 服务正启动、运行或停止且仍需保留会话控制时返回true。 */
    val isSessionActive: Boolean
        get() = sessionStatus == LiveTranslationSessionStatus.STARTING ||
            sessionStatus == LiveTranslationSessionStatus.RUNNING ||
            sessionStatus == LiveTranslationSessionStatus.STOPPING

    /** @return 当前状态允许用户发起新的投屏授权和翻译会话时返回true。 */
    val canStart: Boolean
        get() = sessionStatus == LiveTranslationSessionStatus.IDLE ||
            sessionStatus == LiveTranslationSessionStatus.ERROR

    /** @return 当前启动中或运行中的会话可以响应用户停止操作时返回true。 */
    val canStop: Boolean
        get() = sessionStatus == LiveTranslationSessionStatus.STARTING ||
            sessionStatus == LiveTranslationSessionStatus.RUNNING

    /** @return AudioPlaybackCapture正在读取可检测信号时返回true。 */
    val isCapturing: Boolean
        get() = captureStatus == LiveTranslationCaptureStatus.CAPTURING

    /** @return 采集仍有效但暂时没有检测到可用声音时返回true。 */
    val isSilent: Boolean
        get() = captureStatus == LiveTranslationCaptureStatus.SILENT

    /** @return 原文或译文至少有一项可供字幕浮层显示时返回true。 */
    val hasCaptionText: Boolean
        get() = sourceText.isNotBlank() || translatedText.isNotBlank()
}
