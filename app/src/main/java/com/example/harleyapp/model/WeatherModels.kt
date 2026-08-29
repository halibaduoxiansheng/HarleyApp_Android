package com.example.harleyapp.model

/**
 * 一次前台粗略定位结果。
 *
 * @param latitude WGS84纬度。
 * @param longitude WGS84经度。
 * @param accuracyMeters Android系统报告的估算精度，未知时为0。
 * @param obtainedAtMillis 定位结果产生时间。
 */
data class ApproximateLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val obtainedAtMillis: Long
)

/**
 * 当前天气和今日预报的本地缓存快照。
 *
 * 使用方法：
 * [WeatherRepository][com.example.harleyapp.weather.WeatherRepository]刷新成功后创建本对象，
 * 今日总览和桌面小组件只读取这些已缓存字段，不会自行在后台请求位置。
 *
 * @param temperatureCelsius 当前气温，摄氏度。
 * @param apparentTemperatureCelsius 当前体感温度，摄氏度。
 * @param relativeHumidityPercent 当前相对湿度百分比。
 * @param weatherCode WMO天气代码。
 * @param windSpeedKmh 当前10米风速，千米每小时。
 * @param maxTemperatureCelsius 今日最高温度。
 * @param minTemperatureCelsius 今日最低温度。
 * @param precipitationProbabilityPercent 今日最大降水概率。
 * @param fetchedAtMillis 天气接口成功返回时间。
 */
data class WeatherSnapshot(
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val relativeHumidityPercent: Int,
    val weatherCode: Int,
    val windSpeedKmh: Double,
    val maxTemperatureCelsius: Double,
    val minTemperatureCelsius: Double,
    val precipitationProbabilityPercent: Int,
    val fetchedAtMillis: Long
) {

    /**
     * 判断缓存是否仍在自动刷新间隔内。
     *
     * @param nowMillis 当前Unix毫秒时间戳。
     * @param maxAgeMillis 允许的最大缓存年龄。
     * @return 缓存时间有效且未超过最大年龄时返回true。
     */
    fun isFresh(nowMillis: Long, maxAgeMillis: Long): Boolean {
        return fetchedAtMillis > 0L &&
            nowMillis >= fetchedAtMillis &&
            nowMillis - fetchedAtMillis <= maxAgeMillis
    }
}

/**
 * 把Open-Meteo返回的WMO天气代码映射为简短中文说明。
 *
 * @param code WMO天气代码。
 * @return 用户可读天气说明；未知代码返回“天气变化”。
 */
fun weatherDescription(code: Int): String {
    return when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "局部多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨"
        66, 67 -> "冻雨"
        71, 73, 75, 77 -> "雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95 -> "雷雨"
        96, 99 -> "雷雨伴冰雹"
        else -> "天气变化"
    }
}

/**
 * 把天气代码映射为不依赖图片资源的简洁字符。
 *
 * @param code WMO天气代码。
 * @return 适合卡片和RemoteViews显示的单字符图标。
 */
fun weatherSymbol(code: Int): String {
    return when (code) {
        0, 1 -> "☀"
        2, 3 -> "☁"
        45, 48 -> "雾"
        in 51..67, in 80..82, 95, 96, 99 -> "雨"
        in 71..77, 85, 86 -> "雪"
        else -> "天"
    }
}
