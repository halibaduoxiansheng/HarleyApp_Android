package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.LocalDocumentStore
import com.example.harleyapp.model.ChineseReadingArticle
import com.example.harleyapp.model.ChineseReadingLoadResult
import com.example.harleyapp.model.ChineseReadingTopic
import com.example.harleyapp.model.PrimarySchoolGrade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * 保存语文成长年级选择，并读取、缓存白名单百科文章摘要。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面启动时调用[getSelectedGrade]恢复年级，点击主题后在
 * 协程中调用[loadArticle]。仓库只请求[ChineseReadingTopic.wikipediaTitle]提供的固定标题，用户
 * 输入不会进入URL；联网失败时返回上次缓存或原创离线导读，页面始终有内容可读。
 *
 * @param context Android上下文，内部只保留Application Context对应的Room文档存储。
 */
class ChineseGrowthRepository(context: Context) {

    private val documentStore = LocalDocumentStore.create(context.applicationContext)

    /**
     * 读取用户上次选择的语文学习年级。
     *
     * @return 已保存且仍受支持的年级；首次使用或数据损坏时返回三年级。
     */
    fun getSelectedGrade(): PrimarySchoolGrade {
        val storedName = documentStore.getString(NAMESPACE, SELECTED_GRADE_KEY)
            ?: return PrimarySchoolGrade.GRADE_THREE
        return PrimarySchoolGrade.entries.firstOrNull { grade ->
            grade.name == storedName
        } ?: PrimarySchoolGrade.GRADE_THREE
    }

    /**
     * 保存语文写作和阅读共同使用的年级。
     *
     * @param grade 用户刚选择的有效年级。
     * @return Room文档保存成功返回true，否则返回false。
     */
    fun saveSelectedGrade(grade: PrimarySchoolGrade): Boolean {
        return documentStore.putString(NAMESPACE, SELECTED_GRADE_KEY, grade.name)
    }

    /**
     * 取得一个白名单主题的在线摘要、缓存或离线导读。
     *
     * 使用方法：
     * 页面首次打开主题时把[forceRefresh]设为false，24小时内直接读取缓存；用户点击“重新联网”时
     * 传true。请求在IO线程执行，异常不会清除旧缓存，也不会把堆栈或服务端内容直接显示给孩子。
     *
     * @param topic [ChineseGrowthCatalog]提供的固定阅读主题。
     * @param forceRefresh 是否忽略新鲜缓存并重新联网。
     * @param nowMillis 当前Unix毫秒时间戳，默认读取系统时间，测试或诊断时可传固定值。
     * @return 始终包含可展示文章的加载结果；回退缓存或离线导读时附带中文提示。
     */
    suspend fun loadArticle(
        topic: ChineseReadingTopic,
        forceRefresh: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): ChineseReadingLoadResult = withContext(Dispatchers.IO) {
        val cachedArticle = readCachedArticle(topic)
        if (!forceRefresh && cachedArticle != null && isFresh(cachedArticle, nowMillis)) {
            return@withContext ChineseReadingLoadResult(
                article = cachedArticle.copy(isFromCache = true)
            )
        }

        runCatching {
            val responseJson = downloadSummary(topic.wikipediaTitle)
            parseSummary(topic, responseJson, nowMillis).also { article ->
                saveCachedArticle(article)
            }
        }.fold(
            onSuccess = { article ->
                ChineseReadingLoadResult(article = article)
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to load Chinese reading article", error)
                if (cachedArticle != null) {
                    ChineseReadingLoadResult(
                        article = cachedArticle.copy(isFromCache = true),
                        notice = "联网失败，正在显示上次成功缓存的内容"
                    )
                } else {
                    ChineseReadingLoadResult(
                        article = createOfflineArticle(topic),
                        notice = "暂时无法联网，正在显示内置导读"
                    )
                }
            }
        )
    }

    /**
     * 建立限时HTTPS连接并读取维基百科公开摘要接口。
     *
     * @param pageTitle 白名单中固定的中文百科标题。
     * @return 服务端UTF-8 JSON文本。
     * @throws IOException 网络异常、HTTP状态异常、空响应或响应过大时抛出。
     */
    private fun downloadSummary(pageTitle: String): String {
        val encodedTitle = URLEncoder.encode(pageTitle, Charsets.UTF_8.name())
            .replace("+", "%20")
        val connection = URL("$SUMMARY_API_PREFIX$encodedTitle").openConnection()
            as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected Wikipedia HTTP status: ${connection.responseCode}")
            }
            readLimitedUtf8(connection.inputStream).also { response ->
                if (response.isBlank()) throw IOException("Wikipedia response was empty")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 把公开接口JSON转换成App内文章，并校验来源仍是中文维基百科HTTPS页面。
     *
     * @param topic 当前固定主题。
     * @param responseJson 接口返回的原始JSON。
     * @param fetchedAtMillis 本次成功取得内容的时间。
     * @return 可缓存和展示的在线文章。
     * @throws IOException 缺少标题、正文或合法来源地址时抛出。
     */
    private fun parseSummary(
        topic: ChineseReadingTopic,
        responseJson: String,
        fetchedAtMillis: Long
    ): ChineseReadingArticle {
        val root = JSONObject(responseJson)
        val title = root.optString(JSON_TITLE).trim()
        val body = root.optString(JSON_EXTRACT).trim()
        val description = root.optString(JSON_DESCRIPTION).trim()
        val sourceUrl = root.optJSONObject(JSON_CONTENT_URLS)
            ?.optJSONObject(JSON_DESKTOP)
            ?.optString(JSON_PAGE)
            ?.trim()
            .orEmpty()
        if (title.isBlank() || body.isBlank()) {
            throw IOException("Wikipedia response missed title or extract")
        }
        if (!sourceUrl.startsWith(ALLOWED_SOURCE_PREFIX)) {
            throw IOException("Wikipedia response contained an unexpected source URL")
        }
        return ChineseReadingArticle(
            topicId = topic.id,
            title = title,
            description = description,
            body = body.take(MAX_ARTICLE_CHARACTERS),
            sourceName = SOURCE_NAME,
            sourceUrl = sourceUrl,
            licenseLabel = SOURCE_LICENSE,
            fetchedAtMillis = fetchedAtMillis,
            isOnlineContent = true,
            isFromCache = false
        )
    }

    /**
     * 在固定字符上限内读取响应，防止异常服务端占满内存。
     *
     * @param inputStream HTTPS响应流，函数结束时自动关闭。
     * @return 未超过上限的完整JSON文本。
     * @throws IOException 响应超过上限时抛出。
     */
    private fun readLimitedUtf8(inputStream: InputStream): String {
        return InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(RESPONSE_BUFFER_CHARACTERS)
            while (true) {
                val readCount = reader.read(buffer)
                if (readCount < 0) break
                if (result.length + readCount > MAX_RESPONSE_CHARACTERS) {
                    throw IOException("Wikipedia response exceeded size limit")
                }
                result.append(buffer, 0, readCount)
            }
            result.toString()
        }
    }

    /** 读取并校验一个主题的本地在线内容缓存。 */
    private fun readCachedArticle(topic: ChineseReadingTopic): ChineseReadingArticle? {
        val storedValue = documentStore.getString(NAMESPACE, "$ARTICLE_KEY_PREFIX${topic.id}")
            ?: return null
        return runCatching {
            val root = JSONObject(storedValue)
            val article = ChineseReadingArticle(
                topicId = root.optString(JSON_TOPIC_ID),
                title = root.optString(JSON_TITLE),
                description = root.optString(JSON_DESCRIPTION),
                body = root.optString(JSON_BODY),
                sourceName = root.optString(JSON_SOURCE_NAME),
                sourceUrl = root.optString(JSON_SOURCE_URL),
                licenseLabel = root.optString(JSON_LICENSE),
                fetchedAtMillis = root.optLong(JSON_FETCHED_AT, 0L),
                isOnlineContent = true,
                isFromCache = true
            )
            article.takeIf {
                it.topicId == topic.id &&
                    it.title.isNotBlank() &&
                    it.body.isNotBlank() &&
                    it.sourceUrl.startsWith(ALLOWED_SOURCE_PREFIX)
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse Chinese reading cache", error)
            null
        }
    }

    /** 保存一次成功联网取得的文章缓存。 */
    private fun saveCachedArticle(article: ChineseReadingArticle): Boolean {
        val root = JSONObject()
            .put(JSON_TOPIC_ID, article.topicId)
            .put(JSON_TITLE, article.title)
            .put(JSON_DESCRIPTION, article.description)
            .put(JSON_BODY, article.body)
            .put(JSON_SOURCE_NAME, article.sourceName)
            .put(JSON_SOURCE_URL, article.sourceUrl)
            .put(JSON_LICENSE, article.licenseLabel)
            .put(JSON_FETCHED_AT, article.fetchedAtMillis)
        return documentStore.putString(
            namespace = NAMESPACE,
            key = "$ARTICLE_KEY_PREFIX${article.topicId}",
            value = root.toString()
        )
    }

    /** 创建不依赖网络、明确标记为原创导读的文章。 */
    private fun createOfflineArticle(topic: ChineseReadingTopic): ChineseReadingArticle {
        return ChineseReadingArticle(
            topicId = topic.id,
            title = topic.title,
            description = "${topic.category} · 离线导读",
            body = topic.offlineGuide,
            sourceName = "HarleyApp原创导读",
            sourceUrl = "",
            licenseLabel = "仅用于本机学习",
            fetchedAtMillis = 0L,
            isOnlineContent = false,
            isFromCache = false
        )
    }

    /** 判断在线文章缓存是否处于24小时有效期内。 */
    private fun isFresh(article: ChineseReadingArticle, nowMillis: Long): Boolean {
        val ageMillis = nowMillis - article.fetchedAtMillis
        return article.fetchedAtMillis > 0L && ageMillis in 0 until CACHE_VALID_MILLIS
    }

    companion object {
        const val NAMESPACE = "harley_chinese_growth"

        private const val TAG = "ChineseGrowthRepository"
        private const val SELECTED_GRADE_KEY = "selected_grade"
        private const val ARTICLE_KEY_PREFIX = "reading_article:"
        private const val SUMMARY_API_PREFIX = "https://zh.wikipedia.org/api/rest_v1/page/summary/"
        private const val ALLOWED_SOURCE_PREFIX = "https://zh.wikipedia.org/wiki/"
        private const val USER_AGENT = "HarleyApp/1.0 Android educational reader"
        private const val SOURCE_NAME = "中文维基百科参与者"
        private const val SOURCE_LICENSE = "CC BY-SA 4.0（内容可能经过截短）"
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 12_000
        private const val CACHE_VALID_MILLIS = 24 * 60 * 60 * 1_000L
        private const val RESPONSE_BUFFER_CHARACTERS = 4_096
        private const val MAX_RESPONSE_CHARACTERS = 256_000
        private const val MAX_ARTICLE_CHARACTERS = 8_000
        private const val JSON_TOPIC_ID = "topicId"
        private const val JSON_TITLE = "title"
        private const val JSON_DESCRIPTION = "description"
        private const val JSON_EXTRACT = "extract"
        private const val JSON_CONTENT_URLS = "content_urls"
        private const val JSON_DESKTOP = "desktop"
        private const val JSON_PAGE = "page"
        private const val JSON_BODY = "body"
        private const val JSON_SOURCE_NAME = "sourceName"
        private const val JSON_SOURCE_URL = "sourceUrl"
        private const val JSON_LICENSE = "license"
        private const val JSON_FETCHED_AT = "fetchedAtMillis"
    }
}
