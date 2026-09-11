package com.example.harleyapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.harleyapp.model.MAX_WEBSITE_BROWSER_TAB_COUNT
import com.example.harleyapp.model.WebsiteBrowserSession
import com.example.harleyapp.model.WebsiteBrowserTab
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.normalizeWebsiteBrowserIdentifier
import com.example.harleyapp.model.normalizeWebsiteBrowserTitle
import com.example.harleyapp.model.normalizeWebsiteBrowserUrl
import java.util.UUID

/**
 * 在应用私有SharedPreferences中保存网站浏览器的轻量会话。
 *
 * 使用方法：
 * 使用Application Context创建实例，浏览器初始化时调用[getSession]恢复标签；标签新增、切换、
 * 关闭或页面加载完成后调用[enqueueSessionSave]保存最新会话。只有后台任务确实需要同步确认写盘时
 * 才调用[saveSession]。这里只保存标签标识、来源网站标识、标题、最后一个HTTP或HTTPS网址和活动
 * 标签标识，不保存WebView、网页正文、表单、Cookie或历史快照。
 *
 * @param context Android上下文，用于打开应用私有SharedPreferences。
 */
class WebsiteBrowserSessionRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取并修复上一次网站浏览会话。
     *
     * 使用方法：
     * App冷启动或网站页面首次创建时调用。旧数据版本不支持、JSON损坏、标签非法或标签全部被
     * 过滤时，会使用[fallbackWebsite]创建一个默认网站标签；没有可用默认网站时创建空白标签。
     * 重复标签只保留第一项，超过八项的有效标签会被丢弃，活动标签不存在时自动选择首项。
     *
     * @param fallbackWebsite 无有效持久化数据时准备打开的默认网站；null表示恢复为空白标签。
     * @return 至少包含一个有效标签，并且活动标签一定存在于标签列表中的浏览会话。
     */
    fun getSession(fallbackWebsite: WebsiteShortcut?): WebsiteBrowserSession {
        val fallbackTabId = UUID.randomUUID().toString()
        val encodedSession = runCatching {
            preferences.getString(KEY_SESSION, null)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read website browser session", error)
            null
        }

        return decodeWebsiteBrowserSession(
            encodedSession = encodedSession,
            fallbackWebsite = fallbackWebsite,
            fallbackTabId = fallbackTabId
        )
    }

    /**
     * 原子保存网站浏览器当前轻量会话。
     *
     * 使用方法：
     * 仅从IO线程或其他后台任务传入完整会话。保存前会再次过滤非法、重复和超量标签并修复活动项；
     * 若过滤后没有任何有效标签则拒绝覆盖旧数据。该函数使用commit同步确认写入结果，不应直接从
     * Compose回调或Android主线程调用；常规页面保存请使用[enqueueSessionSave]。
     *
     * @param session 待保存的标签列表与活动标签标识。
     * @return 数据成功写入SharedPreferences返回true；会话无有效标签或写入失败返回false。
     */
    @Synchronized
    @SuppressLint("UseKtx")
    fun saveSession(session: WebsiteBrowserSession): Boolean {
        val encodedSession = encodeWebsiteBrowserSession(session)
        if (encodedSession == null) {
            Log.e(TAG, "Refusing to persist an invalid website browser session")
            return false
        }

        return runCatching {
            preferences.edit()
                .putString(KEY_SESSION, encodedSession)
                .commit()
        }.fold(
            onSuccess = { saved ->
                if (!saved) {
                    Log.e(TAG, "Failed to persist website browser session")
                }
                saved
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to persist website browser session", error)
                false
            }
        )
    }

    /**
     * 不阻塞调用线程地提交网站浏览器轻量会话。
     *
     * 使用方法：
     * Compose页面的短延迟自动保存和Activity进入后台时调用。函数会先同步完成纯内存编码，再通过
     * SharedPreferences.apply更新进程内快照并排队写盘；Android生命周期会等待尚未完成的apply任务，
     * 因此适合在ON_STOP和组合释放前保存最后一次标签切换，同时避免主线程直接执行磁盘commit。
     *
     * @param session 待提交的标签列表、标题、最后有效网址及活动标签标识。
     * @return 会话有效且已成功交给SharedPreferences返回true；编码或提交抛出异常时返回false。
     */
    @SuppressLint("UseKtx")
    fun enqueueSessionSave(session: WebsiteBrowserSession): Boolean {
        val encodedSession = encodeWebsiteBrowserSession(session)
        if (encodedSession == null) {
            Log.e(TAG, "Refusing to enqueue an invalid website browser session")
            return false
        }

        return runCatching {
            preferences.edit()
                .putString(KEY_SESSION, encodedSession)
                .apply()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to enqueue website browser session", error)
            false
        }
    }

    private companion object {
        const val PREFERENCE_NAME = "harley_website_browser_session"
        const val KEY_SESSION = "session_json"

        const val TAG = "WebsiteBrowserSession"
    }
}

/**
 * 把浏览会话编码成当前版本JSON。
 *
 * 使用方法：
 * 仓库写入前调用；单元测试也可直接调用以验证编码内容不包含网页正文、Cookie等额外字段。
 * 编码前会按与读取相同的规则过滤非法标签、去重、限制最多八项并修复活动标签。
 *
 * @param session 待编码的网站浏览会话。
 * @return 可直接写入SharedPreferences的版本化JSON；没有有效标签时返回null。
 */
internal fun encodeWebsiteBrowserSession(session: WebsiteBrowserSession): String? {
    val normalizedSession = normalizeWebsiteBrowserSession(session) ?: return null

    return buildString {
        append('{')
        appendJsonPropertyName(JSON_VERSION)
        append(WEBSITE_BROWSER_SESSION_VERSION)
        append(',')
        appendJsonPropertyName(JSON_ACTIVE_TAB_ID)
        appendJsonString(normalizedSession.activeTabId.orEmpty())
        append(',')
        appendJsonPropertyName(JSON_TABS)
        append('[')

        normalizedSession.tabs.forEachIndexed { index, tab ->
            if (index > 0) append(',')
            append('{')
            appendJsonPropertyName(JSON_TAB_ID)
            appendJsonString(tab.id)
            append(',')
            appendJsonPropertyName(JSON_WEBSITE_ID)
            appendJsonNullableString(tab.websiteId)
            append(',')
            appendJsonPropertyName(JSON_TITLE)
            appendJsonString(tab.title)
            append(',')
            appendJsonPropertyName(JSON_URL)
            appendJsonNullableString(tab.url)
            append('}')
        }

        append(']')
        append('}')
    }
}

/**
 * 从版本化JSON恢复并修复浏览会话。
 *
 * 使用方法：
 * 仓库读取SharedPreferences后调用。该函数不访问Android Context或文件系统，所有过滤和回退行为
 * 都由输入决定，便于通过本地JUnit测试覆盖损坏数据、重复标签、数量上限和活动项修复。
 *
 * @param encodedSession SharedPreferences读取的JSON；null或空白表示没有历史会话。
 * @param fallbackWebsite 无可恢复标签时用于创建首个标签的默认网站；null表示创建空白标签。
 * @param fallbackTabId 回退标签使用的调用方生成标识；无效时函数会重新生成UUID。
 * @return 至少包含一个有效标签，且活动标签标识一定指向列表成员的浏览会话。
 */
internal fun decodeWebsiteBrowserSession(
    encodedSession: String?,
    fallbackWebsite: WebsiteShortcut?,
    fallbackTabId: String
): WebsiteBrowserSession {
    val fallbackSession = createFallbackWebsiteBrowserSession(
        fallbackWebsite = fallbackWebsite,
        fallbackTabId = fallbackTabId
    )
    if (encodedSession.isNullOrBlank() || encodedSession.length > MAX_SESSION_JSON_LENGTH) {
        return fallbackSession
    }

    val root = parseJsonObject(encodedSession) ?: return fallbackSession
    val version = root.values[JSON_VERSION]
        ?.let { value -> value as? JsonNumberValue }
        ?.rawValue
        ?.toIntOrNull()
    if (version != WEBSITE_BROWSER_SESSION_VERSION) return fallbackSession

    val tabsJson = (root.values[JSON_TABS] as? JsonArrayValue)?.values
        ?: return fallbackSession
    val decodedTabs = buildList {
        val seenTabIds = mutableSetOf<String>()

        for (value in tabsJson) {
            if (size >= MAX_WEBSITE_BROWSER_TAB_COUNT) break

            val tab = (value as? JsonObjectValue)
                ?.let(::decodeWebsiteBrowserTab)
                ?: continue
            if (seenTabIds.add(tab.id)) {
                add(tab)
            }
        }
    }
    if (decodedTabs.isEmpty()) return fallbackSession

    val requestedActiveTabId = root.optionalString(JSON_ACTIVE_TAB_ID)
        ?.let(::normalizeWebsiteBrowserIdentifier)
    return WebsiteBrowserSession(
        tabs = decodedTabs,
        activeTabId = requestedActiveTabId
            ?.takeIf { activeId -> decodedTabs.any { tab -> tab.id == activeId } }
            ?: decodedTabs.first().id
    )
}

/**
 * 创建没有可恢复数据时的稳定首个标签。
 *
 * @param fallbackWebsite 可用的默认网站；标识、标题或网址非法时按没有默认网站处理。
 * @param fallbackTabId 首个标签标识；无效时自动生成UUID。
 * @return 包含默认网站标签或空白标签的有效会话。
 */
private fun createFallbackWebsiteBrowserSession(
    fallbackWebsite: WebsiteShortcut?,
    fallbackTabId: String
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(fallbackTabId)
        ?: UUID.randomUUID().toString()
    val normalizedWebsiteId = fallbackWebsite?.id?.let(::normalizeWebsiteBrowserIdentifier)
    val normalizedTitle = fallbackWebsite?.title?.let(::normalizeWebsiteBrowserTitle)
    val normalizedUrl = fallbackWebsite?.url?.let(::normalizeWebsiteBrowserUrl)
    val fallbackTab = if (
        fallbackWebsite != null &&
        normalizedWebsiteId != null &&
        normalizedTitle != null &&
        normalizedUrl != null
    ) {
        WebsiteBrowserTab(
            id = normalizedTabId,
            websiteId = normalizedWebsiteId,
            title = normalizedTitle,
            url = normalizedUrl
        )
    } else {
        WebsiteBrowserTab(id = normalizedTabId)
    }

    return WebsiteBrowserSession(
        tabs = listOf(fallbackTab),
        activeTabId = fallbackTab.id
    )
}

/**
 * 修复内存会话，使其满足持久化格式约束。
 *
 * @param session 调用方当前持有的浏览会话。
 * @return 已过滤并修复的会话；没有有效标签时返回null。
 */
private fun normalizeWebsiteBrowserSession(
    session: WebsiteBrowserSession
): WebsiteBrowserSession? {
    val seenTabIds = mutableSetOf<String>()
    val normalizedTabs = session.tabs.asSequence()
        .mapNotNull(::normalizeWebsiteBrowserTab)
        .filter { tab -> seenTabIds.add(tab.id) }
        .take(MAX_WEBSITE_BROWSER_TAB_COUNT)
        .toList()
    if (normalizedTabs.isEmpty()) return null

    val requestedActiveTabId = session.activeTabId?.let(::normalizeWebsiteBrowserIdentifier)
    return WebsiteBrowserSession(
        tabs = normalizedTabs,
        activeTabId = requestedActiveTabId
            ?.takeIf { activeId -> normalizedTabs.any { tab -> tab.id == activeId } }
            ?: normalizedTabs.first().id
    )
}

/**
 * 从JSON对象读取一个标签并执行全部字段校验。
 *
 * @param json 单个标签JSON对象。
 * @return 字段合法的标签；任一已提供字段类型或内容非法时返回null。
 */
private fun decodeWebsiteBrowserTab(json: JsonObjectValue): WebsiteBrowserTab? {
    val tabId = json.requiredString(JSON_TAB_ID)
        ?.let(::normalizeWebsiteBrowserIdentifier)
        ?: return null
    val websiteId = when (val value = json.values[JSON_WEBSITE_ID]) {
        null,
        JsonNullValue -> null
        is JsonStringValue -> normalizeWebsiteBrowserIdentifier(value.value) ?: return null
        else -> return null
    }
    val title = json.requiredString(JSON_TITLE)
        ?.let(::normalizeWebsiteBrowserTitle)
        ?: return null
    val url = when (val value = json.values[JSON_URL]) {
        null,
        JsonNullValue -> null
        is JsonStringValue -> normalizeWebsiteBrowserUrl(value.value) ?: return null
        else -> return null
    }

    return WebsiteBrowserTab(
        id = tabId,
        websiteId = websiteId,
        title = title,
        url = url
    )
}

/**
 * 校验并规范化内存中的一个标签。
 *
 * @param tab 待写入持久化数据的标签。
 * @return 所有字段符合格式约束的标签；否则返回null。
 */
private fun normalizeWebsiteBrowserTab(tab: WebsiteBrowserTab): WebsiteBrowserTab? {
    val tabId = normalizeWebsiteBrowserIdentifier(tab.id) ?: return null
    val websiteId = when (val rawWebsiteId = tab.websiteId) {
        null -> null
        else -> normalizeWebsiteBrowserIdentifier(rawWebsiteId) ?: return null
    }
    val title = normalizeWebsiteBrowserTitle(tab.title) ?: return null
    val url = when (val rawUrl = tab.url) {
        null -> null
        else -> normalizeWebsiteBrowserUrl(rawUrl) ?: return null
    }

    return WebsiteBrowserTab(
        id = tabId,
        websiteId = websiteId,
        title = title,
        url = url
    )
}

/**
 * 读取必须为字符串的JSON字段，不把数字或布尔值隐式转换为文本。
 *
 * @param key JSON字段名。
 * @return 原始字符串；字段缺失、为null或类型不匹配时返回null。
 */
private fun JsonObjectValue.requiredString(key: String): String? {
    return (values[key] as? JsonStringValue)?.value
}

/**
 * 读取允许缺失或为null的JSON字符串字段。
 *
 * @param key JSON字段名。
 * @return 原始字符串；字段缺失、为null或类型不匹配时返回null。
 */
private fun JsonObjectValue.optionalString(key: String): String? {
    return when (val value = values[key]) {
        null,
        JsonNullValue -> null
        is JsonStringValue -> value.value
        else -> null
    }
}

/**
 * 给JSON对象追加已经转义的属性名和冒号。
 *
 * @param name 固定JSON字段名。
 * @return 无返回值；内容直接追加到当前StringBuilder。
 */
private fun StringBuilder.appendJsonPropertyName(name: String) {
    appendJsonString(name)
    append(':')
}

/**
 * 给JSON输出追加字符串或null字面量。
 *
 * @param value 允许为空的字段值。
 * @return 无返回值；null写为JSON null，其他值按字符串转义。
 */
private fun StringBuilder.appendJsonNullableString(value: String?) {
    if (value == null) {
        append(JSON_NULL_LITERAL)
    } else {
        appendJsonString(value)
    }
}

/**
 * 按JSON字符串规则转义文本，保证标题中的引号、反斜杠和换行不会破坏会话结构。
 *
 * @param value 需要编码的原始字符串。
 * @return 无返回值；带首尾双引号的JSON字符串直接追加到当前StringBuilder。
 */
private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < JSON_MIN_UNESCAPED_CHARACTER_CODE) {
                    append("\\u")
                    repeat(JSON_UNICODE_ESCAPE_DIGIT_COUNT - 1) { digitOffset ->
                        val shift = (JSON_UNICODE_ESCAPE_DIGIT_COUNT - 1 - digitOffset) * 4
                        append(JSON_HEX_DIGITS[(character.code shr shift) and 0xF])
                    }
                    append(JSON_HEX_DIGITS[character.code and 0xF])
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

/**
 * 解析SharedPreferences中的JSON根对象。
 *
 * @param source 待解析的完整JSON文本。
 * @return 语法完整且没有尾随内容的对象；格式损坏或嵌套过深时返回null。
 */
private fun parseJsonObject(source: String): JsonObjectValue? {
    return runCatching {
        WebsiteBrowserJsonParser(source).parseRootObject()
    }.getOrNull()
}

/** JSON解析后的最小值类型，只覆盖会话格式和安全跳过未知字段所需的标准JSON类型。 */
private sealed interface WebsiteBrowserJsonValue

/** @param values JSON对象按字段名保存的值。 */
private data class JsonObjectValue(
    val values: Map<String, WebsiteBrowserJsonValue>
) : WebsiteBrowserJsonValue

/** @param values JSON数组按原顺序保存的值。 */
private data class JsonArrayValue(
    val values: List<WebsiteBrowserJsonValue>
) : WebsiteBrowserJsonValue

/** @param value 已完成反转义的JSON字符串。 */
private data class JsonStringValue(
    val value: String
) : WebsiteBrowserJsonValue

/** @param rawValue 保留原始格式的JSON数字。 */
private data class JsonNumberValue(
    val rawValue: String
) : WebsiteBrowserJsonValue

/** @param value JSON布尔值；会话当前不读取，但解析未知字段时需要安全消费。 */
private data class JsonBooleanValue(
    val value: Boolean
) : WebsiteBrowserJsonValue

/** JSON null的唯一内存表示。 */
private data object JsonNullValue : WebsiteBrowserJsonValue

/**
 * 不依赖Android运行时的严格JSON读取器。
 *
 * 使用方法：
 * 仅由[parseJsonObject]创建并解析单个会话字符串。读取器支持标准对象、数组、字符串、数字、
 * 布尔和null，同时限制嵌套深度；任何语法错误都会抛出异常并由外层转为安全回退。
 *
 * @param source SharedPreferences中的JSON文本。
 */
private class WebsiteBrowserJsonParser(
    private val source: String
) {
    private var index = 0

    /**
     * 解析唯一根对象并拒绝尾随内容。
     *
     * @return 完整JSON根对象。
     */
    fun parseRootObject(): JsonObjectValue {
        val value = readValue(depth = 0) as? JsonObjectValue
            ?: throw IllegalArgumentException("Website browser session root must be an object")
        skipWhitespace()
        if (index != source.length) {
            throw IllegalArgumentException("Website browser session contains trailing JSON data")
        }
        return value
    }

    /**
     * 读取任意标准JSON值。
     *
     * @param depth 当前对象或数组嵌套层级。
     * @return 已解析的JSON值。
     */
    private fun readValue(depth: Int): WebsiteBrowserJsonValue {
        if (depth > MAX_JSON_NESTING_DEPTH) {
            throw IllegalArgumentException("Website browser session JSON is nested too deeply")
        }
        skipWhitespace()
        return when (peekCharacter()) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> JsonStringValue(readString())
            't' -> {
                readLiteral(JSON_TRUE_LITERAL)
                JsonBooleanValue(true)
            }
            'f' -> {
                readLiteral(JSON_FALSE_LITERAL)
                JsonBooleanValue(false)
            }
            'n' -> {
                readLiteral(JSON_NULL_LITERAL)
                JsonNullValue
            }
            '-', in '0'..'9' -> JsonNumberValue(readNumber())
            else -> throw IllegalArgumentException("Website browser session contains invalid JSON")
        }
    }

    /**
     * 读取JSON对象，重复字段采用最后一次出现的值。
     *
     * @param depth 子值使用的嵌套层级。
     * @return 对象字段映射。
     */
    private fun readObject(depth: Int): JsonObjectValue {
        expectCharacter('{')
        skipWhitespace()
        if (consumeCharacterIf('}')) return JsonObjectValue(emptyMap())

        val values = linkedMapOf<String, WebsiteBrowserJsonValue>()
        while (true) {
            skipWhitespace()
            if (peekCharacter() != '"') {
                throw IllegalArgumentException("Website browser session object key must be a string")
            }
            val key = readString()
            skipWhitespace()
            expectCharacter(':')
            values[key] = readValue(depth)
            skipWhitespace()

            if (consumeCharacterIf('}')) break
            expectCharacter(',')
        }
        return JsonObjectValue(values)
    }

    /**
     * 读取JSON数组并保留元素顺序。
     *
     * @param depth 子值使用的嵌套层级。
     * @return 数组元素列表。
     */
    private fun readArray(depth: Int): JsonArrayValue {
        expectCharacter('[')
        skipWhitespace()
        if (consumeCharacterIf(']')) return JsonArrayValue(emptyList())

        val values = mutableListOf<WebsiteBrowserJsonValue>()
        while (true) {
            values += readValue(depth)
            skipWhitespace()

            if (consumeCharacterIf(']')) break
            expectCharacter(',')
        }
        return JsonArrayValue(values)
    }

    /**
     * 读取并反转义一个JSON字符串。
     *
     * @return 不含首尾引号的原始文本。
     */
    private fun readString(): String {
        expectCharacter('"')
        return buildString {
            while (true) {
                val character = nextCharacter()
                when {
                    character == '"' -> return@buildString
                    character == '\\' -> append(readEscapedCharacter())
                    character.code < JSON_MIN_UNESCAPED_CHARACTER_CODE -> {
                        throw IllegalArgumentException(
                            "Website browser session string contains a control character"
                        )
                    }
                    else -> append(character)
                }
            }
        }
    }

    /**
     * 读取反斜杠后的JSON转义字符。
     *
     * @return 对应的单个UTF-16字符。
     */
    private fun readEscapedCharacter(): Char {
        return when (val escaped = nextCharacter()) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> throw IllegalArgumentException(
                "Website browser session contains an invalid JSON escape"
            )
        }
    }

    /**
     * 读取四位十六进制Unicode转义。
     *
     * @return 转义表示的UTF-16字符。
     */
    private fun readUnicodeEscape(): Char {
        var value = 0
        repeat(JSON_UNICODE_ESCAPE_DIGIT_COUNT) {
            val digit = nextCharacter().digitToIntOrNull(radix = 16)
                ?: throw IllegalArgumentException(
                    "Website browser session contains an invalid Unicode escape"
                )
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    /**
     * 读取标准JSON数字但不做浮点转换，版本字段稍后再按整数解析。
     *
     * @return JSON数字的原始子串。
     */
    private fun readNumber(): String {
        val startIndex = index
        consumeCharacterIf('-')

        if (consumeCharacterIf('0')) {
            if (peekCharacter() in '0'..'9') {
                throw IllegalArgumentException("Website browser session number has a leading zero")
            }
        } else {
            readRequiredDigits()
        }

        if (consumeCharacterIf('.')) {
            readRequiredDigits()
        }
        if (peekCharacter() == 'e' || peekCharacter() == 'E') {
            index++
            if (peekCharacter() == '+' || peekCharacter() == '-') index++
            readRequiredDigits()
        }
        return source.substring(startIndex, index)
    }

    /**
     * 消费至少一个十进制数字。
     *
     * @return 无返回值；当前位置不是数字时抛出格式异常。
     */
    private fun readRequiredDigits() {
        val startIndex = index
        while (peekCharacter() in '0'..'9') index++
        if (startIndex == index) {
            throw IllegalArgumentException("Website browser session number is incomplete")
        }
    }

    /**
     * 消费true、false或null固定字面量。
     *
     * @param literal 当前位置预期出现的标准JSON文本。
     * @return 无返回值。
     */
    private fun readLiteral(literal: String) {
        if (!source.regionMatches(index, literal, 0, literal.length)) {
            throw IllegalArgumentException("Website browser session contains an invalid literal")
        }
        index += literal.length
    }

    /** 跳过JSON语法允许的空格、制表符和换行。 */
    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index++
    }

    /**
     * 要求当前位置为指定字符并前进一步。
     *
     * @param expected 语法要求的字符。
     */
    private fun expectCharacter(expected: Char) {
        if (nextCharacter() != expected) {
            throw IllegalArgumentException("Website browser session contains invalid punctuation")
        }
    }

    /**
     * 当前位置等于指定字符时消费它。
     *
     * @param expected 可选字符。
     * @return 成功消费返回true，否则返回false。
     */
    private fun consumeCharacterIf(expected: Char): Boolean {
        if (peekCharacter() != expected) return false
        index++
        return true
    }

    /** @return 当前字符；已经到达末尾时返回null字符作为哨兵。 */
    private fun peekCharacter(): Char {
        return source.getOrNull(index) ?: JSON_END_OF_INPUT
    }

    /** @return 当前字符并前进一步；意外到达末尾时抛出格式异常。 */
    private fun nextCharacter(): Char {
        return source.getOrNull(index++)
            ?: throw IllegalArgumentException("Website browser session JSON ended unexpectedly")
    }
}

private const val WEBSITE_BROWSER_SESSION_VERSION = 1
private const val MAX_SESSION_JSON_LENGTH = 128 * 1024
private const val MAX_JSON_NESTING_DEPTH = 24
private const val JSON_UNICODE_ESCAPE_DIGIT_COUNT = 4
private const val JSON_MIN_UNESCAPED_CHARACTER_CODE = 0x20
private const val JSON_END_OF_INPUT = '\u0000'

private const val JSON_VERSION = "version"
private const val JSON_ACTIVE_TAB_ID = "active_tab_id"
private const val JSON_TABS = "tabs"
private const val JSON_TAB_ID = "id"
private const val JSON_WEBSITE_ID = "website_id"
private const val JSON_TITLE = "title"
private const val JSON_URL = "url"

private const val JSON_TRUE_LITERAL = "true"
private const val JSON_FALSE_LITERAL = "false"
private const val JSON_NULL_LITERAL = "null"
private const val JSON_HEX_DIGITS = "0123456789abcdef"

private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
