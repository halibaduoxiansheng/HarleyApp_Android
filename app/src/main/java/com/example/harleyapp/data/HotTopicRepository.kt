package com.example.harleyapp.data

import android.util.Log
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.HotTopicPlatform
import com.example.harleyapp.model.HotTopicSnapshot
import com.example.harleyapp.model.normalizeHotTopicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 拉取并缓存公开热点标题与原平台链接。
 *
 * 使用方法：
 * 在应用入口创建并长期复用仓库。页面首次显示时先调用[getCachedSnapshot]取得当前进程内
 * 最后一次成功数据，再根据[isAutomaticRefreshDue]决定是否调用[refresh]。用户点击刷新按钮时
 * 也调用[refresh]；10分钟内会直接返回内存快照，不重复访问上游。接口响应不会写入手机存储。
 */
class HotTopicRepository {

    @Volatile
    private var memorySnapshot: HotTopicSnapshot? = null

    /**
     * 读取当前App进程内最后一次成功的热点快照。
     *
     * @return 当前进程中存在成功快照时返回；首次使用或进程重新启动后返回null。
     */
    fun getCachedSnapshot(): HotTopicSnapshot? {
        return memorySnapshot
    }

    /**
     * 判断当前缓存是否需要自动联网更新。
     *
     * 使用方法：
     * 热点详情页进入或用户手动刷新时调用本函数。缓存不足10分钟时直接显示缓存，避免页面反复进入
     * 或连续点击刷新按钮造成高频请求。
     *
     * @param snapshot 当前缓存快照；null表示没有可显示的数据。
     * @param nowMillis 当前Unix毫秒时间戳，默认读取系统时间，测试时可传入固定值。
     *
     * @return 没有缓存、时间戳异常或缓存达到自动刷新间隔时返回true，否则返回false。
     */
    fun isAutomaticRefreshDue(
        snapshot: HotTopicSnapshot?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (snapshot == null || snapshot.fetchedAtMillis <= 0L) {
            return true
        }

        val cacheAgeMillis = nowMillis - snapshot.fetchedAtMillis
        return cacheAgeMillis < 0L || cacheAgeMillis >= AUTO_REFRESH_INTERVAL_MILLIS
    }

    /**
     * 从允许外部站点集成的公开接口拉取热点，并保留当前进程内最后一次成功结果。
     *
     * 使用方法：
     * 必须在协程中调用。成功快照不足10分钟时直接返回，不重复请求；需要联网时会切换到IO线程，
     * 执行HTTPS请求、响应大小限制和JSON解析。网络或单次数据异常不会清除当前内存快照。
     *
     * @return 成功时Result中包含新快照；失败时Result包含异常，由界面转换为简洁中文提示。
     */
    suspend fun refresh(): Result<HotTopicSnapshot> = withContext(Dispatchers.IO) {
        val cachedSnapshot = memorySnapshot
        if (cachedSnapshot != null && !isAutomaticRefreshDue(cachedSnapshot)) {
            return@withContext Result.success(cachedSnapshot)
        }

        runCatching {
            val responseJson = downloadResponseJson()
            val fetchedAtMillis = System.currentTimeMillis()
            val snapshot = parseSnapshot(
                responseJson = responseJson,
                fetchedAtMillis = fetchedAtMillis
            )

            memorySnapshot = snapshot
            snapshot
        }.onFailure { error ->
            Log.e(TAG, "Failed to refresh hot topics", error)
        }
    }

    /**
     * 建立限时HTTPS连接并读取受大小限制的UTF-8响应。
     *
     * @return 服务端返回的完整JSON文本。
     *
     * @throws IOException 网络失败、HTTP状态异常、响应为空或响应超过大小上限时抛出。
     */
    private fun downloadResponseJson(): String {
        val connection = URL(API_URL).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)

        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected hot topic HTTP status: $responseCode")
            }

            readLimitedUtf8(connection.inputStream).also { responseJson ->
                if (responseJson.isBlank()) {
                    throw IOException("Hot topic response was empty")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 在固定字符上限内读取接口响应，防止异常服务端返回无限或超大内容占满内存。
     *
     * @param inputStream HTTPS连接返回的响应流，函数结束时会关闭。
     *
     * @return 未超过上限的UTF-8文本。
     *
     * @throws IOException 响应超过[MAX_RESPONSE_CHARACTERS]时抛出。
     */
    private fun readLimitedUtf8(inputStream: InputStream): String {
        return InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(RESPONSE_BUFFER_CHARACTERS)

            while (true) {
                val readCount = reader.read(buffer)
                if (readCount < 0) {
                    break
                }
                if (result.length + readCount > MAX_RESPONSE_CHARACTERS) {
                    throw IOException("Hot topic response exceeded size limit")
                }

                result.append(buffer, 0, readCount)
            }

            result.toString()
        }
    }

    /**
     * 把公开接口响应转换为本App仅含标题索引的安全数据结构。
     *
     * @param responseJson 接口返回或本机缓存的原始JSON。
     * @param fetchedAtMillis 本App取得本次响应的Unix毫秒时间戳。
     *
     * @return 至少包含一个受支持平台有效热点的快照。
     *
     * @throws IOException JSON结构缺失或所有受支持平台都没有合法热点时抛出。
     */
    private fun parseSnapshot(
        responseJson: String,
        fetchedAtMillis: Long
    ): HotTopicSnapshot {
        val rootObject = JSONObject(responseJson)
        val platformArray = rootObject.optJSONArray(JSON_PLATFORMS)
            ?: throw IOException("Hot topic platform list was missing")
        val topicsByPlatform = linkedMapOf<HotTopicPlatform, List<HotTopic>>()

        HotTopicPlatform.entries.forEach { expectedPlatform ->
            for (platformIndex in 0 until platformArray.length()) {
                val platformObject = platformArray.optJSONObject(platformIndex) ?: continue
                if (platformObject.optString(JSON_PLATFORM_ID) != expectedPlatform.sourceId) {
                    continue
                }

                val itemArray = platformObject.optJSONArray(JSON_ITEMS)
                val topics = buildList {
                    if (itemArray != null) {
                        for (itemIndex in 0 until itemArray.length()) {
                            val itemObject = itemArray.optJSONObject(itemIndex) ?: continue
                            val title = itemObject.optString(JSON_TITLE).trim()
                            val normalizedUrl = normalizeHotTopicUrl(
                                platform = expectedPlatform,
                                rawUrl = itemObject.optString(JSON_URL),
                                title = title
                            ) ?: continue
                            val rank = itemObject.optInt(JSON_RANK, itemIndex + 1)
                                .takeIf { value -> value > 0 }
                                ?: itemIndex + 1

                            add(
                                HotTopic(
                                    platform = expectedPlatform,
                                    rank = rank,
                                    title = title,
                                    url = normalizedUrl
                                )
                            )
                            if (size >= MAX_TOPICS_PER_PLATFORM) {
                                break
                            }
                        }
                    }
                }

                if (topics.isNotEmpty()) {
                    topicsByPlatform[expectedPlatform] = topics
                }
                break
            }
        }

        if (topicsByPlatform.isEmpty()) {
            throw IOException("Hot topic response contained no supported items")
        }

        return HotTopicSnapshot(
            updatedAt = rootObject.optString(JSON_LAST_UPDATE).trim(),
            fetchedAtMillis = fetchedAtMillis,
            topicsByPlatform = topicsByPlatform
        )
    }

    private companion object {
        const val TAG = "HotTopicRepository"
        const val API_URL = "https://www.douyinhuo.cn/api/data"
        const val USER_AGENT = "HarleyApp/1.0 Android"
        const val JSON_LAST_UPDATE = "last_update"
        const val JSON_PLATFORMS = "platforms"
        const val JSON_PLATFORM_ID = "id"
        const val JSON_ITEMS = "items"
        const val JSON_RANK = "rank"
        const val JSON_TITLE = "title"
        const val JSON_URL = "url"
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val AUTO_REFRESH_INTERVAL_MILLIS = 10 * 60 * 1_000L
        const val RESPONSE_BUFFER_CHARACTERS = 8_192
        const val MAX_RESPONSE_CHARACTERS = 1_000_000
        const val MAX_TOPICS_PER_PLATFORM = 50
    }
}
