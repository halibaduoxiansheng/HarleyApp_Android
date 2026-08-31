package com.example.harleyapp.data

import kotlin.math.min

/**
 * 把大型HTML或XML正文线性转换为适合离线阅读的纯文本。
 *
 * 使用方法：
 * MOBI解压完成后把完整HTML正文传给[extract]。本解析器只保留可见文字、常用字符实体和段落边界，
 * 不构造Android富文本对象，因此处理数千万字符的长篇小说时不会产生大量Span，也可以持续报告
 * 已扫描字符数。它不是浏览器排版引擎，不负责执行脚本、CSS或还原复杂表格样式。
 */
internal object EbookHtmlTextExtractor {

    /**
     * 扫描HTML正文并生成规范化纯文本。
     *
     * @param source 需要处理的完整HTML、XHTML或XML文字。
     * @param onCharacterProgress 字符扫描进度回调；第一个参数为已处理字符数，第二个参数为总字符数。
     * 默认空实现，普通调用方可忽略进度。
     * @return 去除标签、脚本、样式并把连续空白规范化后的纯文本。
     */
    fun extract(
        source: String,
        onCharacterProgress: (completedCharacters: Int, totalCharacters: Int) -> Unit = { _, _ -> }
    ): String {
        if (source.isEmpty()) {
            onCharacterProgress(0, 0)
            return ""
        }

        val output = StringBuilder(min(source.length, INITIAL_OUTPUT_CAPACITY))
        var index = 0
        var ignoredElement = ""
        var pendingSpace = false
        var pendingLineBreaks = 0
        var nextProgressAt = 0
        onCharacterProgress(0, source.length)

        // 空格和换行先延迟到下一段可见文字出现时再写入，可避免标签之间生成大段无意义空白。
        fun appendVisibleCharacter(character: Char) {
            if (character.isWhitespace() || character == '\u00A0') {
                pendingSpace = true
            } else {
                if (pendingLineBreaks > 0 && output.isNotEmpty()) {
                    while (output.isNotEmpty() && output.last() == ' ') output.setLength(output.length - 1)
                    var existingBreaks = 0
                    var outputIndex = output.lastIndex
                    while (outputIndex >= 0 && output[outputIndex] == '\n') {
                        existingBreaks += 1
                        outputIndex -= 1
                    }
                    repeat((pendingLineBreaks - existingBreaks).coerceAtLeast(0)) {
                        output.append('\n')
                    }
                } else if (
                    pendingSpace &&
                    output.isNotEmpty() &&
                    output.last() != '\n' &&
                    output.last() != ' '
                ) {
                    output.append(' ')
                }
                pendingSpace = false
                pendingLineBreaks = 0
                output.append(character)
            }
        }

        // 字符实体可能解码为代理对，因此字符串入口逐字符复用上面的无额外分配写入逻辑。
        fun appendVisibleText(value: String) {
            value.forEach { character -> appendVisibleCharacter(character) }
        }

        // 块级标签只记录最多两个待写换行，既保留段落层次，又不会让复杂排版撑出大片空行。
        fun requestLineBreaks(count: Int) {
            pendingLineBreaks = maxOf(pendingLineBreaks, count.coerceIn(1, 2))
            pendingSpace = false
        }

        while (index < source.length) {
            val current = source[index]
            when {
                current == '<' && source.startsWith("<!--", index) -> {
                    val commentEnd = source.indexOf("-->", startIndex = index + 4)
                    index = if (commentEnd >= 0) commentEnd + 3 else source.length
                }

                current == '<' -> {
                    val tagEnd = source.indexOf('>', startIndex = index + 1)
                    if (tagEnd < 0) {
                        appendVisibleText("<")
                        index += 1
                    } else {
                        val rawTag = source.substring(index + 1, min(tagEnd, index + MAX_TAG_SCAN_LENGTH))
                            .trim()
                        val closing = rawTag.startsWith('/')
                        val tagName = rawTag
                            .removePrefix("/")
                            .trimStart()
                            .takeWhile { character -> character.isLetterOrDigit() }
                            .lowercase()

                        if (ignoredElement.isNotEmpty()) {
                            if (closing && tagName == ignoredElement) ignoredElement = ""
                        } else if (!closing && tagName in IGNORED_CONTENT_TAGS) {
                            ignoredElement = tagName
                        } else {
                            when (tagName) {
                                in DOUBLE_BREAK_TAGS -> requestLineBreaks(2)
                                in SINGLE_BREAK_TAGS -> requestLineBreaks(1)
                            }
                        }
                        index = tagEnd + 1
                    }
                }

                ignoredElement.isNotEmpty() -> index += 1

                current == '&' -> {
                    val entityEnd = source.indexOf(';', startIndex = index + 1)
                        .takeIf { end -> end in (index + 2)..min(source.lastIndex, index + MAX_ENTITY_LENGTH) }
                    if (entityEnd == null) {
                        appendVisibleText("&")
                        index += 1
                    } else {
                        val entityName = source.substring(index + 1, entityEnd)
                        appendVisibleText(decodeEntity(entityName) ?: "&$entityName;")
                        index = entityEnd + 1
                    }
                }

                else -> {
                    appendVisibleCharacter(current)
                    index += 1
                }
            }

            if (index >= nextProgressAt || index >= source.length) {
                onCharacterProgress(index.coerceAtMost(source.length), source.length)
                nextProgressAt = index + PROGRESS_CHARACTER_INTERVAL
            }
        }

        return output.toString().trim()
    }

    /**
     * 解码HTML中常见的命名实体和十进制、十六进制数字实体。
     *
     * @param name 不包含开头&与结尾分号的实体名称。
     * @return 可见字符；名称无效或Unicode码点不合法时返回null，让上层保留原文。
     */
    private fun decodeEntity(name: String): String? {
        NAMED_ENTITIES[name.lowercase()]?.let { value -> return value }
        val codePoint = when {
            name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)
            name.startsWith('#') -> name.drop(1).toIntOrNull()
            else -> null
        } ?: return null
        if (!Character.isValidCodePoint(codePoint) || codePoint in SURROGATE_CODE_POINT_RANGE) {
            return null
        }
        return String(Character.toChars(codePoint))
    }

    private const val INITIAL_OUTPUT_CAPACITY = 1024 * 1024
    private const val PROGRESS_CHARACTER_INTERVAL = 256 * 1024
    private const val MAX_TAG_SCAN_LENGTH = 256
    private const val MAX_ENTITY_LENGTH = 16
    private val SURROGATE_CODE_POINT_RANGE = 0xD800..0xDFFF

    private val IGNORED_CONTENT_TAGS = setOf("script", "style", "svg")
    private val DOUBLE_BREAK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "div", "footer", "h1", "h2", "h3",
        "h4", "h5", "h6", "header", "main", "nav", "p", "section", "table"
    )
    private val SINGLE_BREAK_TAGS = setOf("br", "hr", "li", "tr")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "apos" to "'",
        "gt" to ">",
        "hellip" to "…",
        "ldquo" to "“",
        "lsquo" to "‘",
        "lt" to "<",
        "mdash" to "—",
        "nbsp" to " ",
        "ndash" to "–",
        "quot" to "\"",
        "rdquo" to "”",
        "rsquo" to "’"
    )
}
