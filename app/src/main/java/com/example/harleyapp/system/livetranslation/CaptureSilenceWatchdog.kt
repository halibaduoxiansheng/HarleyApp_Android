package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationCaptureStatus
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 判断播放声音长期不可用时使用的阈值和时间策略。
 *
 * 使用方法：
 * 创建[CaptureSilenceWatchdog]时传入；默认值适合只做“是否收到可捕获声音”的提示，不应用作
 * 人声VAD。影片背景音乐和环境声是否属于语音，应继续交给本地识别器的端点检测处理。
 *
 * @param silenceThresholdDb 音量小于或等于该dBFS值时计入静音时长。
 * @param enterSilenceAfterMillis 连续静音达到该时长后切换成SILENT。
 * @param recoverAfterMillis SILENT后连续检测到有效信号达到该时长才恢复CAPTURING，避免边界抖动。
 */
data class CaptureSilencePolicy(
    val silenceThresholdDb: Float = -55f,
    val enterSilenceAfterMillis: Long = 5_000L,
    val recoverAfterMillis: Long = 200L
) {

    init {
        require(silenceThresholdDb.isFinite() && silenceThresholdDb <= 0f) {
            "Silence threshold must be a finite non-positive dBFS value"
        }
        require(enterSilenceAfterMillis > 0L) {
            "Silence timeout must be positive"
        }
        require(recoverAfterMillis > 0L) {
            "Silence recovery timeout must be positive"
        }
    }
}

/**
 * 每次音量观察后的完整静音判定结果。
 *
 * @param captureStatus 当前应发布给界面的采集状态。
 * @param audioLevelDb 本窗口实际传入的RMS dBFS。
 * @param silentDurationMillis 当前连续静音累计时长。
 * @param recoveryDurationMillis SILENT状态下连续有效信号累计时长。
 * @param statusChanged 本次观察是否导致CAPTURING与SILENT互相切换。
 */
data class CaptureSilenceObservation(
    val captureStatus: LiveTranslationCaptureStatus,
    val audioLevelDb: Float,
    val silentDurationMillis: Long,
    val recoveryDurationMillis: Long,
    val statusChanged: Boolean
)

/**
 * 用连续时间和迟滞规则识别“长时间没有可捕获声音”，并在声音恢复后自动退出静音状态。
 *
 * 使用方法：
 * AudioRecord每读完一个PCM窗口，计算窗口持续毫秒数并调用[observePcm]；已有音量值时可直接调用
 * [observe]. 开始新会话或重新创建AudioRecord后必须调用[reset]，本类不持有音频字节或Android对象。
 * 同一实例应只由单个采集协程顺序调用。
 *
 * @param policy 静音阈值、进入时长和恢复时长。
 */
class CaptureSilenceWatchdog(
    private val policy: CaptureSilencePolicy = CaptureSilencePolicy()
) {

    private var captureStatus = LiveTranslationCaptureStatus.CAPTURING
    private var silentDurationMillis = 0L
    private var recoveryDurationMillis = 0L

    /**
     * 开始新的声音采集周期并清除上一次累计时间。
     *
     * 使用方法：
     * 服务获得新的MediaProjection并成功启动AudioRecord后调用一次；重复调用是安全的。
     *
     * @return 重置后的CAPTURING观察结果，音量为负无穷且累计时间为零。
     */
    fun reset(): CaptureSilenceObservation {
        captureStatus = LiveTranslationCaptureStatus.CAPTURING
        silentDurationMillis = 0L
        recoveryDurationMillis = 0L
        return currentObservation(
            audioLevelDb = Float.NEGATIVE_INFINITY,
            statusChanged = false
        )
    }

    /**
     * 根据一个已经计算好的音量窗口推进静音状态机。
     *
     * 使用方法：
     * 每个窗口严格调用一次并传入真实窗口时长。NaN和负无穷按静音处理；检测到有效声音时，
     * CAPTURING会立即清零静音累计，SILENT则等待恢复迟滞时间后再切回CAPTURING。
     *
     * @param audioLevelDb 当前PCM窗口的RMS dBFS。
     * @param elapsedMillis 当前窗口覆盖的正毫秒数。
     * @return 包含最新采集状态、累计时间和状态变化标记的观察结果。
     */
    fun observe(
        audioLevelDb: Float,
        elapsedMillis: Long
    ): CaptureSilenceObservation {
        require(elapsedMillis > 0L) { "Observed audio duration must be positive" }

        val previousStatus = captureStatus
        val isSilentWindow = audioLevelDb.isNaN() ||
            audioLevelDb == Float.NEGATIVE_INFINITY ||
            audioLevelDb <= policy.silenceThresholdDb

        if (isSilentWindow) {
            recoveryDurationMillis = 0L
            silentDurationMillis = saturatedAdd(silentDurationMillis, elapsedMillis)
            if (silentDurationMillis >= policy.enterSilenceAfterMillis) {
                captureStatus = LiveTranslationCaptureStatus.SILENT
            }
        } else {
            silentDurationMillis = 0L
            if (captureStatus == LiveTranslationCaptureStatus.SILENT) {
                recoveryDurationMillis = saturatedAdd(recoveryDurationMillis, elapsedMillis)
                if (recoveryDurationMillis >= policy.recoverAfterMillis) {
                    captureStatus = LiveTranslationCaptureStatus.CAPTURING
                    recoveryDurationMillis = 0L
                }
            } else {
                recoveryDurationMillis = 0L
            }
        }

        return currentObservation(
            audioLevelDb = audioLevelDb,
            statusChanged = previousStatus != captureStatus
        )
    }

    /**
     * 从16位PCM窗口计算音量并推进静音状态机。
     *
     * 使用方法：
     * AudioRecord复用较大的ShortArray时，把本次实际读取数量传给[sampleCount]，未写入的尾部不会
     * 参与音量计算。传入空窗口会得到负无穷并按静音窗口累计。
     *
     * @param samples 包含单声道或已经下混后16位PCM的数组。
     * @param sampleCount 本次有效样本数量，范围为0到数组长度。
     * @param elapsedMillis 本批有效音频覆盖的正毫秒数。
     * @return 计算音量后产生的最新静音观察结果。
     */
    fun observePcm(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        elapsedMillis: Long
    ): CaptureSilenceObservation {
        return observe(
            audioLevelDb = calculatePcm16RmsDb(samples, sampleCount),
            elapsedMillis = elapsedMillis
        )
    }

    /** @return 使用当前累计字段创建一份不可变观察快照。 */
    private fun currentObservation(
        audioLevelDb: Float,
        statusChanged: Boolean
    ): CaptureSilenceObservation {
        return CaptureSilenceObservation(
            captureStatus = captureStatus,
            audioLevelDb = audioLevelDb,
            silentDurationMillis = silentDurationMillis,
            recoveryDurationMillis = recoveryDurationMillis,
            statusChanged = statusChanged
        )
    }
}

/**
 * 计算16位有符号PCM窗口的均方根音量。
 *
 * 使用方法：
 * 采集层可先调用本函数发布音量计，再把同一结果交给[CaptureSilenceWatchdog.observe]。计算不会
 * 修改数组；零长度或全部为零返回负无穷，满幅信号接近0dBFS。
 *
 * @param samples PCM样本数组。
 * @param sampleCount 从数组起始位置参与计算的有效样本数。
 * @return 当前窗口RMS dBFS；无有效能量时返回[Float.NEGATIVE_INFINITY]。
 */
internal fun calculatePcm16RmsDb(
    samples: ShortArray,
    sampleCount: Int = samples.size
): Float {
    require(sampleCount in 0..samples.size) {
        "PCM sample count must be within the array bounds"
    }
    if (sampleCount == 0) return Float.NEGATIVE_INFINITY

    var sumOfSquares = 0.0
    for (index in 0 until sampleCount) {
        val normalizedSample = samples[index].toDouble() / PCM_16_FULL_SCALE
        sumOfSquares += normalizedSample * normalizedSample
    }
    if (sumOfSquares <= 0.0) return Float.NEGATIVE_INFINITY

    val rms = sqrt(sumOfSquares / sampleCount)
    return (DB_MULTIPLIER * log10(rms)).toFloat()
}

/** @return 对正时长做饱和加法，防止极长会话中的毫秒累计溢出。 */
private fun saturatedAdd(current: Long, increment: Long): Long {
    return if (current > Long.MAX_VALUE - increment) Long.MAX_VALUE else current + increment
}

private const val PCM_16_FULL_SCALE = 32_768.0
private const val DB_MULTIPLIER = 20.0
