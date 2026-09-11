package com.example.harleyapp.model

import kotlin.math.roundToInt

/** 爆闪单次亮灯或灭灯允许的最短时长，避免普通Android调度下产生不可控的超短切换。 */
const val FLASHLIGHT_MIN_PHASE_DURATION_MILLIS = 50

/** 爆闪一个完整明灭周期允许的最长时长，对应最低0.5Hz。 */
const val FLASHLIGHT_MAX_PERIOD_MILLIS = 2_000

/** 爆闪允许的最低频率。 */
const val FLASHLIGHT_MIN_FREQUENCY_HZ = 0.5f

/** 爆闪允许的最高频率。 */
const val FLASHLIGHT_MAX_FREQUENCY_HZ = 10f

/** 单次爆闪允许的最短总运行时长。 */
const val FLASHLIGHT_MIN_TOTAL_DURATION_SECONDS = 1

/** 单次爆闪允许的最长总运行时长，避免误操作后长时间持续闪烁。 */
const val FLASHLIGHT_MAX_TOTAL_DURATION_SECONDS = 300

/**
 * 描述一个完整爆闪周期内的亮灯和灭灯时长。
 *
 * 使用方法：
 * 页面修改任一阶段时长后创建本对象，并读取[frequencyHz]向用户展示实际频率；调整频率时把
 * 当前对象传给[scaleFlashlightTimingForFrequency]，即可在尽量保持明暗占比的同时获得新时长。
 *
 * @param onDurationMillis 每个周期保持点亮的毫秒数。
 * @param offDurationMillis 每个周期保持熄灭的毫秒数。
 */
data class FlashlightTiming(
    val onDurationMillis: Int = 100,
    val offDurationMillis: Int = 100
) {
    /** 当前亮灭时长换算得到的实际频率，单位Hz。 */
    val frequencyHz: Float
        get() = calculateFlashlightFrequencyHz(onDurationMillis, offDurationMillis)

    /** 当前周期中保持点亮的整数百分比，仅用于界面说明。 */
    val dutyCyclePercent: Int
        get() {
            val periodMillis = onDurationMillis + offDurationMillis
            if (periodMillis <= 0) return 0

            return (onDurationMillis.toDouble() / periodMillis * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
}

/**
 * 一次爆闪运行所需的完整、不可变参数。
 *
 * 使用方法：
 * 用户点击开始时把界面输入冻结成此对象，先调用[validateFlashlightStrobeSettings]校验，再交给
 * 执行循环使用。运行过程中继续编辑界面不会改变已经启动的一次任务。
 *
 * @param totalDurationSeconds 本次爆闪从开始到强制关灯的总秒数。
 * @param timing 单个明灭周期的亮灯和灭灯时长。
 * @param strengthLevel 当前设备使用的手电筒亮度档位，从1开始。
 */
data class FlashlightStrobeSettings(
    val totalDurationSeconds: Int,
    val timing: FlashlightTiming,
    val strengthLevel: Int
)

/** 爆闪参数校验失败的稳定类型，由界面负责转换成面向用户的说明。 */
enum class FlashlightSettingsIssue {
    TOTAL_DURATION_OUT_OF_RANGE,
    ON_DURATION_TOO_SHORT,
    OFF_DURATION_TOO_SHORT,
    PERIOD_TOO_LONG,
    FREQUENCY_OUT_OF_RANGE
}

/**
 * 根据亮灯和灭灯时长计算一个完整周期的频率。
 *
 * @param onDurationMillis 每周期点亮毫秒数。
 * @param offDurationMillis 每周期熄灭毫秒数。
 *
 * @return `1000 / (亮灯毫秒数 + 灭灯毫秒数)`；周期不为正数时返回0Hz。
 */
fun calculateFlashlightFrequencyHz(
    onDurationMillis: Int,
    offDurationMillis: Int
): Float {
    val periodMillis = onDurationMillis + offDurationMillis
    return if (periodMillis > 0) 1_000f / periodMillis else 0f
}

/**
 * 调整频率时同步缩放亮灯和灭灯时长，并尽量保持原来的明暗占比。
 *
 * 使用方法：
 * 频率滑块变化时传入当前时序和目标频率。目标值会被限制在0.5～10Hz，每个阶段至少保留50ms；
 * 在最高频率等边界上无法同时保持占比时，以阶段安全下限优先。
 *
 * @param currentTiming 调整前的亮灭时序。
 * @param targetFrequencyHz 用户选择的目标频率。
 *
 * @return 已满足频率范围和最短阶段时长限制的新时序。
 */
fun scaleFlashlightTimingForFrequency(
    currentTiming: FlashlightTiming,
    targetFrequencyHz: Float
): FlashlightTiming {
    val safeFrequencyHz = targetFrequencyHz.coerceIn(
        FLASHLIGHT_MIN_FREQUENCY_HZ,
        FLASHLIGHT_MAX_FREQUENCY_HZ
    )
    val targetPeriodMillis = (1_000f / safeFrequencyHz)
        .roundToInt()
        .coerceIn(
            FLASHLIGHT_MIN_PHASE_DURATION_MILLIS * 2,
            FLASHLIGHT_MAX_PERIOD_MILLIS
        )
    val safeCurrentOnMillis = currentTiming.onDurationMillis.coerceAtLeast(
        FLASHLIGHT_MIN_PHASE_DURATION_MILLIS
    )
    val safeCurrentOffMillis = currentTiming.offDurationMillis.coerceAtLeast(
        FLASHLIGHT_MIN_PHASE_DURATION_MILLIS
    )
    val currentDutyRatio = safeCurrentOnMillis.toDouble() /
        (safeCurrentOnMillis + safeCurrentOffMillis)
    val maximumOnMillis = targetPeriodMillis - FLASHLIGHT_MIN_PHASE_DURATION_MILLIS
    val targetOnMillis = (targetPeriodMillis * currentDutyRatio)
        .roundToInt()
        .coerceIn(FLASHLIGHT_MIN_PHASE_DURATION_MILLIS, maximumOnMillis)

    return FlashlightTiming(
        onDurationMillis = targetOnMillis,
        offDurationMillis = targetPeriodMillis - targetOnMillis
    )
}

/**
 * 校验一次爆闪运行参数是否处于界面和调度器共同支持的范围内。
 *
 * @param settings 准备启动的爆闪参数。
 *
 * @return 参数有效时返回null；否则返回遇到的第一个稳定问题类型。
 */
fun validateFlashlightStrobeSettings(
    settings: FlashlightStrobeSettings
): FlashlightSettingsIssue? {
    if (settings.totalDurationSeconds !in
        FLASHLIGHT_MIN_TOTAL_DURATION_SECONDS..FLASHLIGHT_MAX_TOTAL_DURATION_SECONDS
    ) {
        return FlashlightSettingsIssue.TOTAL_DURATION_OUT_OF_RANGE
    }
    if (settings.timing.onDurationMillis < FLASHLIGHT_MIN_PHASE_DURATION_MILLIS) {
        return FlashlightSettingsIssue.ON_DURATION_TOO_SHORT
    }
    if (settings.timing.offDurationMillis < FLASHLIGHT_MIN_PHASE_DURATION_MILLIS) {
        return FlashlightSettingsIssue.OFF_DURATION_TOO_SHORT
    }
    if (settings.timing.onDurationMillis + settings.timing.offDurationMillis >
        FLASHLIGHT_MAX_PERIOD_MILLIS
    ) {
        return FlashlightSettingsIssue.PERIOD_TOO_LONG
    }
    if (settings.timing.frequencyHz !in
        FLASHLIGHT_MIN_FREQUENCY_HZ..FLASHLIGHT_MAX_FREQUENCY_HZ
    ) {
        return FlashlightSettingsIssue.FREQUENCY_OUT_OF_RANGE
    }

    return null
}
