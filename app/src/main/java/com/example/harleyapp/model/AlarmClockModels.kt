package com.example.harleyapp.model

/**
 * 把用户选择的本地绝对时间转换为AlarmManager开机时钟时间。
 *
 * 使用方法：
 * 调度提醒前分别传入目标Unix毫秒时间、当前Unix毫秒时间和
 * `SystemClock.elapsedRealtime()`。函数只负责纯数值换算，不读取Android系统状态，因此可以在
 * JVM单元测试中验证。应用的数据仓库仍保存用户可读的绝对时间，只有提交给AlarmManager时使用
 * 本函数的返回值，从而避开部分系统把RTC闹钟错误映射到很久以后的问题。
 *
 * @param triggerAtMillis 用户计划触发的Unix毫秒时间戳。
 * @param currentTimeMillis 换算瞬间的当前Unix毫秒时间戳。
 * @param elapsedRealtimeMillis 换算瞬间从开机到现在的毫秒数，不包含深度睡眠差异以外的人为改时。
 *
 * @return 目标时间有效时返回可交给`ELAPSED_REALTIME_WAKEUP`的开机时钟毫秒值；目标已经到期、
 * 参数无效或数值溢出时返回null。
 */
fun wallClockTriggerToElapsedRealtime(
    triggerAtMillis: Long,
    currentTimeMillis: Long,
    elapsedRealtimeMillis: Long
): Long? {
    if (triggerAtMillis <= currentTimeMillis || elapsedRealtimeMillis < 0L) {
        return null
    }

    val delayMillis = runCatching {
        Math.subtractExact(triggerAtMillis, currentTimeMillis)
    }.getOrNull() ?: return null

    return runCatching {
        Math.addExact(elapsedRealtimeMillis, delayMillis)
    }.getOrNull()
}
