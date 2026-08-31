package com.example.harleyapp

import com.example.harleyapp.model.wallClockTriggerToElapsedRealtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证用户绝对时间到AlarmManager开机时钟的纯数值换算。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行本文件全部测试，不需要连接手机。
 */
class AlarmClockModelsTest {

    /**
     * 验证未来十秒的用户时间会映射成开机时钟的未来十秒。
     *
     * @return 无返回值；剩余延迟没有保持一致时由JUnit报告失败。
     */
    @Test
    fun futureWallClockKeepsTheSameDelay() {
        val result = wallClockTriggerToElapsedRealtime(
            triggerAtMillis = 1_010_000L,
            currentTimeMillis = 1_000_000L,
            elapsedRealtimeMillis = 500_000L
        )

        assertEquals(510_000L, result)
    }

    /**
     * 验证等于当前时刻和已经过期的计划不会生成可提交的Alarm时间。
     *
     * @return 无返回值；过期计划被错误接受时由JUnit报告失败。
     */
    @Test
    fun currentOrPastWallClockIsRejected() {
        assertNull(wallClockTriggerToElapsedRealtime(1_000L, 1_000L, 500L))
        assertNull(wallClockTriggerToElapsedRealtime(999L, 1_000L, 500L))
    }

    /**
     * 验证异常的开机时间或加法溢出会安全失败，不会提交不可预测的系统闹钟。
     *
     * @return 无返回值；非法参数生成结果时由JUnit报告失败。
     */
    @Test
    fun invalidOrOverflowingElapsedClockIsRejected() {
        assertNull(wallClockTriggerToElapsedRealtime(2_000L, 1_000L, -1L))
        assertNull(wallClockTriggerToElapsedRealtime(2_000L, 1_000L, Long.MAX_VALUE))
    }
}
