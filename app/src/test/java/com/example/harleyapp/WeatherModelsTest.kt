package com.example.harleyapp

import com.example.harleyapp.model.WeatherSnapshot
import com.example.harleyapp.model.weatherDescription
import com.example.harleyapp.model.weatherSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证天气代码展示和缓存时效判断，不依赖手机定位或真实天气网络。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，JUnit会自动运行本测试类。
 */
class WeatherModelsTest {

    /**
     * 验证常见晴、雨、雪及未知代码都有稳定的中文展示结果。
     *
     * @return 无返回值；任一映射变化时由JUnit报告失败。
     */
    @Test
    fun weatherCodesHaveStableChineseLabels() {
        assertEquals("晴", weatherDescription(0))
        assertEquals("阵雨", weatherDescription(81))
        assertEquals("雪", weatherDescription(75))
        assertEquals("天气变化", weatherDescription(1_000))

        assertEquals("☀", weatherSymbol(0))
        assertEquals("雨", weatherSymbol(95))
        assertEquals("雪", weatherSymbol(86))
        assertEquals("天", weatherSymbol(1_000))
    }

    /**
     * 验证缓存处于允许时间窗口内时可以复用，从而避免频繁定位和联网。
     *
     * @return 无返回值；新鲜缓存被误判时由JUnit报告失败。
     */
    @Test
    fun weatherCacheIsFreshInsideAllowedWindow() {
        val snapshot = sampleSnapshot(fetchedAtMillis = 10_000L)

        assertTrue(snapshot.isFresh(nowMillis = 39_999L, maxAgeMillis = 30_000L))
        assertTrue(snapshot.isFresh(nowMillis = 40_000L, maxAgeMillis = 30_000L))
    }

    /**
     * 验证过期、未来时间或无效时间戳不会被当作可用的新鲜缓存。
     *
     * @return 无返回值；异常缓存被接受时由JUnit报告失败。
     */
    @Test
    fun invalidOrExpiredWeatherCacheIsRejected() {
        assertFalse(
            sampleSnapshot(fetchedAtMillis = 10_000L)
                .isFresh(nowMillis = 40_001L, maxAgeMillis = 30_000L)
        )
        assertFalse(
            sampleSnapshot(fetchedAtMillis = 50_000L)
                .isFresh(nowMillis = 40_000L, maxAgeMillis = 30_000L)
        )
        assertFalse(
            sampleSnapshot(fetchedAtMillis = 0L)
                .isFresh(nowMillis = 40_000L, maxAgeMillis = 30_000L)
        )
    }

    /**
     * 构造只改变抓取时间的测试天气快照。
     *
     * @param fetchedAtMillis 待验证的缓存生成时间。
     * @return 字段完整、可直接调用isFresh的天气快照。
     */
    private fun sampleSnapshot(fetchedAtMillis: Long): WeatherSnapshot {
        return WeatherSnapshot(
            temperatureCelsius = 26.0,
            apparentTemperatureCelsius = 27.0,
            relativeHumidityPercent = 65,
            weatherCode = 1,
            windSpeedKmh = 8.0,
            maxTemperatureCelsius = 29.0,
            minTemperatureCelsius = 22.0,
            precipitationProbabilityPercent = 20,
            fetchedAtMillis = fetchedAtMillis
        )
    }
}
