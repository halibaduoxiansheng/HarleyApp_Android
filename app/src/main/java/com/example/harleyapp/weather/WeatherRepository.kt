package com.example.harleyapp.weather

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.harleyapp.model.ApproximateLocation
import com.example.harleyapp.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.round

/**
 * 使用粗略坐标读取Open-Meteo当前天气，并把最后一次成功结果缓存到本机。
 *
 * 使用方法：
 * 页面先通过DeviceLocationProvider取得前台位置，再调用[refresh]。本仓库只向固定HTTPS域名
 * 发送约化后的经纬度和天气字段，不携带账目、提醒、设备标识或位置历史；[getCached]
 * 可供今日页面和桌面小组件离线显示最后一次成功数据。
 *
 * @param context Android上下文，用于读取应用私有天气缓存。
 */
class WeatherRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取最后一次成功天气缓存。
     *
     * @return 字段完整时返回天气快照；没有缓存或缓存损坏时返回null。
     */
    fun getCached(): WeatherSnapshot? {
        if (!preferences.contains(KEY_FETCHED_AT)) return null
        return runCatching {
            WeatherSnapshot(
                temperatureCelsius = preferences.getFloat(KEY_TEMPERATURE, 0f).toDouble(),
                apparentTemperatureCelsius = preferences.getFloat(KEY_APPARENT_TEMPERATURE, 0f).toDouble(),
                relativeHumidityPercent = preferences.getInt(KEY_HUMIDITY, 0),
                weatherCode = preferences.getInt(KEY_WEATHER_CODE, -1),
                windSpeedKmh = preferences.getFloat(KEY_WIND_SPEED, 0f).toDouble(),
                maxTemperatureCelsius = preferences.getFloat(KEY_MAX_TEMPERATURE, 0f).toDouble(),
                minTemperatureCelsius = preferences.getFloat(KEY_MIN_TEMPERATURE, 0f).toDouble(),
                precipitationProbabilityPercent = preferences.getInt(KEY_PRECIPITATION_PROBABILITY, 0),
                fetchedAtMillis = preferences.getLong(KEY_FETCHED_AT, 0L)
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read cached weather", error)
            null
        }
    }

    /**
     * 判断自动进入页面时是否需要重新定位并请求天气。
     *
     * @param nowMillis 当前时间。
     * @return 没有缓存或缓存超过三十分钟时返回true。
     */
    fun isRefreshDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return getCached()?.isFresh(nowMillis, WEATHER_CACHE_MILLIS) != true
    }

    /**
     * 使用当前粗略位置刷新天气。
     *
     * 坐标发送前保留两位小数，约为一公里网格；接口只请求当前气温、湿度、风速、天气代码和
     * 今日高低温及降水概率。成功结果保存到应用私有缓存，失败不会覆盖旧缓存。
     *
     * @param location Android前台粗略位置。
     * @return 成功天气快照或失败原因。
     */
    suspend fun refresh(location: ApproximateLocation): Result<WeatherSnapshot> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val latitude = roundCoordinate(location.latitude)
                val longitude = roundCoordinate(location.longitude)
                require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
                val requestUrl = String.format(
                    Locale.US,
                    API_URL_FORMAT,
                    latitude,
                    longitude
                )
                val connection = URL(requestUrl).openConnection() as HttpsURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
                    connection.readTimeout = NETWORK_TIMEOUT_MILLIS
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "HarleyApp-Weather/1.0")
                    val responseCode = connection.responseCode
                    require(responseCode == HttpsURLConnection.HTTP_OK) {
                        "Unexpected weather response: $responseCode"
                    }
                    val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
                        it.readText()
                    }
                    parseWeather(body).also { snapshot ->
                        require(persist(snapshot)) { "Unable to persist weather cache" }
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to refresh weather", error)
            }
        }
    }

    /** @return 保留两位小数后的近似坐标。 */
    private fun roundCoordinate(value: Double): Double = round(value * 100.0) / 100.0

    /**
     * 解析Open-Meteo天气JSON。
     *
     * @param body HTTPS响应正文。
     * @return 完整天气快照；缺少必需字段时抛出异常。
     */
    private fun parseWeather(body: String): WeatherSnapshot {
        val root = JSONObject(body)
        val current = root.getJSONObject(JSON_CURRENT)
        val daily = root.getJSONObject(JSON_DAILY)
        return WeatherSnapshot(
            temperatureCelsius = current.getDouble(JSON_TEMPERATURE),
            apparentTemperatureCelsius = current.getDouble(JSON_APPARENT_TEMPERATURE),
            relativeHumidityPercent = current.getInt(JSON_HUMIDITY).coerceIn(0, 100),
            weatherCode = current.getInt(JSON_WEATHER_CODE),
            windSpeedKmh = current.getDouble(JSON_WIND_SPEED).coerceAtLeast(0.0),
            maxTemperatureCelsius = daily.getJSONArray(JSON_MAX_TEMPERATURE).getDouble(0),
            minTemperatureCelsius = daily.getJSONArray(JSON_MIN_TEMPERATURE).getDouble(0),
            precipitationProbabilityPercent = daily
                .getJSONArray(JSON_PRECIPITATION_PROBABILITY)
                .getInt(0)
                .coerceIn(0, 100),
            fetchedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * 同步保存天气缓存；不保存经纬度。
     *
     * @param snapshot 待保存天气。
     * @return 写入成功返回true。
     */
    @SuppressLint("UseKtx")
    private fun persist(snapshot: WeatherSnapshot): Boolean {
        return preferences.edit()
            .putFloat(KEY_TEMPERATURE, snapshot.temperatureCelsius.toFloat())
            .putFloat(KEY_APPARENT_TEMPERATURE, snapshot.apparentTemperatureCelsius.toFloat())
            .putInt(KEY_HUMIDITY, snapshot.relativeHumidityPercent)
            .putInt(KEY_WEATHER_CODE, snapshot.weatherCode)
            .putFloat(KEY_WIND_SPEED, snapshot.windSpeedKmh.toFloat())
            .putFloat(KEY_MAX_TEMPERATURE, snapshot.maxTemperatureCelsius.toFloat())
            .putFloat(KEY_MIN_TEMPERATURE, snapshot.minTemperatureCelsius.toFloat())
            .putInt(KEY_PRECIPITATION_PROBABILITY, snapshot.precipitationProbabilityPercent)
            .putLong(KEY_FETCHED_AT, snapshot.fetchedAtMillis)
            .commit()
    }

    companion object {
        const val PREFERENCE_NAME = "harley_weather"
        const val WEATHER_CACHE_MILLIS = 30 * 60 * 1000L

        private const val TAG = "WeatherRepository"
        private const val NETWORK_TIMEOUT_MILLIS = 10_000
        private const val API_URL_FORMAT =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=%.2f&longitude=%.2f" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1"

        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_APPARENT_TEMPERATURE = "apparent_temperature"
        private const val KEY_HUMIDITY = "humidity"
        private const val KEY_WEATHER_CODE = "weather_code"
        private const val KEY_WIND_SPEED = "wind_speed"
        private const val KEY_MAX_TEMPERATURE = "max_temperature"
        private const val KEY_MIN_TEMPERATURE = "min_temperature"
        private const val KEY_PRECIPITATION_PROBABILITY = "precipitation_probability"
        private const val KEY_FETCHED_AT = "fetched_at"

        private const val JSON_CURRENT = "current"
        private const val JSON_DAILY = "daily"
        private const val JSON_TEMPERATURE = "temperature_2m"
        private const val JSON_APPARENT_TEMPERATURE = "apparent_temperature"
        private const val JSON_HUMIDITY = "relative_humidity_2m"
        private const val JSON_WEATHER_CODE = "weather_code"
        private const val JSON_WIND_SPEED = "wind_speed_10m"
        private const val JSON_MAX_TEMPERATURE = "temperature_2m_max"
        private const val JSON_MIN_TEMPERATURE = "temperature_2m_min"
        private const val JSON_PRECIPITATION_PROBABILITY = "precipitation_probability_max"
    }
}
