package com.example.harleyapp.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

/** 毛主席语录TXT允许导入的最大字节数，避免一次性解析异常大文件造成内存压力。 */
const val MAX_MAO_QUOTE_FILE_BYTES = 2 * 1024 * 1024

/** 单份文档允许解析的最大章节数量，避免异常文件制造过多列表分组。 */
const val MAX_MAO_QUOTE_CHAPTER_COUNT = 512

/** 单份文档允许解析的最大语录段落总数。 */
const val MAX_MAO_QUOTE_COUNT = 20_000

/** 单段语录允许保留的最大字符数。 */
const val MAX_MAO_QUOTE_TEXT_CHARS = 20_000

/** 文档显示标题允许的最大字符数，避免异常元数据拖慢标题卡片测量。 */
const val MAX_MAO_QUOTE_TITLE_CHARS = 120

/** 来源或许可说明允许的最大字符数，兼顾完整说明与界面渲染成本。 */
const val MAX_MAO_QUOTE_METADATA_CHARS = 500

/** 单个章节标题允许的最大字符数，避免章节筛选Chip被超长文字撑坏。 */
const val MAX_MAO_QUOTE_CHAPTER_TITLE_CHARS = 120

/** TXT未声明标题时使用的中性书名；该常量不包含任何作品正文。 */
const val DEFAULT_MAO_QUOTE_DOCUMENT_TITLE = "毛主席语录"

/** TXT没有章节标题时使用的统一章节名。 */
const val DEFAULT_MAO_QUOTE_CHAPTER_TITLE = "全文"

/**
 * 一份本地语录TXT声明的来源与许可元数据。
 *
 * 使用方法：
 * TXT开头可依次书写`@title:`、`@source:`、`@license:`和
 * `@redistribution-authorized: true`。元数据只描述用户提供的文件，不代表App替用户取得授权。
 * Release包仅在[redistributionAuthorized]为true且来源、许可说明均非空时允许读取随APK分发的资产；
 * 用户自己导入的私有文件不会因为这些字段为空而被拒绝。
 *
 * @param title 文档显示标题；未声明时为“毛主席语录”。
 * @param source 内容来源说明；未声明时为空。
 * @param license 许可或授权说明；未声明时为空。
 * @param redistributionAuthorized true表示文件明确声明允许随APK再分发；仅用于内置资产发布门禁。
 */
data class MaoQuoteMetadata(
    val title: String = DEFAULT_MAO_QUOTE_DOCUMENT_TITLE,
    val source: String = "",
    val license: String = "",
    val redistributionAuthorized: Boolean = false
)

/**
 * 一段按空行切分的语录内容。
 *
 * @param id 根据章节标题、正文和同内容出现次数生成的稳定SHA-256短标识。
 * @param chapterId 所属章节的稳定标识。
 * @param chapterTitle 所属章节显示标题，便于搜索结果脱离章节列表后仍能显示来源位置。
 * @param indexInChapter 本段在章节内从0开始的位置。
 * @param globalIndex 本段在整份文档内从0开始的位置。
 * @param text 完整段落正文；同一段内的换行会保留。
 */
data class MaoQuote(
    val id: String,
    val chapterId: String,
    val chapterTitle: String,
    val indexInChapter: Int,
    val globalIndex: Int,
    val text: String
) {
    /** 预先归一化的章节名与正文，用于连续输入搜索时避免反复复制并转换同一段文字。 */
    val searchableText: String = "$chapterTitle\n$text".lowercase(Locale.ROOT)
}

/**
 * 由`# `标题开始的章节。
 *
 * @param id 根据章节标题及同名章节出现次数生成的稳定标识。
 * @param title 章节显示标题；整份TXT没有标题行时为“全文”。
 * @param index 章节在文档内从0开始的位置。
 * @param quotes 本章节按原顺序保存的语录段落。
 */
data class MaoQuoteChapter(
    val id: String,
    val title: String,
    val index: Int,
    val quotes: List<MaoQuote>
)

/**
 * 已通过大小、UTF-8和结构校验的完整语录文档。
 *
 * @param id 仅由章节与段落内容生成的稳定文档标识，修改许可元数据不会改变它。
 * @param metadata TXT头部解析出的来源和许可信息。
 * @param chapters 非空章节列表，空章节不会进入结果。
 */
data class MaoQuoteDocument(
    val id: String,
    val metadata: MaoQuoteMetadata,
    val chapters: List<MaoQuoteChapter>
) {
    /** 按全书原始顺序一次性展平并缓存的全部语录，避免列表和搜索重复分配相同集合。 */
    val quotes: List<MaoQuote> = chapters.flatMap(MaoQuoteChapter::quotes)
}

/**
 * 语录列表的组合筛选条件。
 *
 * @param query 关键词；空白表示不限制，匹配正文或章节标题且忽略英文大小写。
 * @param chapterId 指定章节稳定标识；null表示全部章节。
 * @param favoritesOnly true时只保留收藏标识集合中的段落。
 */
data class MaoQuoteFilter(
    val query: String = "",
    val chapterId: String? = null,
    val favoritesOnly: Boolean = false
)

/**
 * 最后一次阅读位置。
 *
 * @param quoteId 最后显示或点击的语录稳定标识。
 * @param characterOffset 段落内从0开始的字符偏移；仅按条目恢复时可保持0。
 */
data class MaoQuoteReadingPosition(
    val quoteId: String,
    val characterOffset: Int = 0
)

/** 读取成功后用于区分用户导入文件和不同许可状态内置资产的来源类型。 */
enum class MaoQuoteContentSource {
    IMPORTED,
    BUNDLED_AUTHORIZED,
    BUNDLED_DEVELOPMENT
}

/** 可供界面稳定判断和翻译的语录读取、解析或保存失败类型。 */
enum class MaoQuoteFailureReason {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    INVALID_UTF8,
    NO_CONTENT,
    CONTENT_LIMIT_EXCEEDED,
    READ_FAILED,
    SAVE_FAILED,
    REDISTRIBUTION_NOT_AUTHORIZED
}

/**
 * 纯解析函数的返回结果。
 *
 * 成功分支包含完整文档；失败分支同时提供稳定原因和可直接展示的中文说明。
 */
sealed interface MaoQuoteParseResult {
    data class Success(val document: MaoQuoteDocument) : MaoQuoteParseResult

    data class Failure(
        val reason: MaoQuoteFailureReason,
        val message: String
    ) : MaoQuoteParseResult
}

/** Repository读取当前可用内容时返回的完整状态。 */
sealed interface MaoQuoteLoadResult {
    data class Success(
        val document: MaoQuoteDocument,
        val source: MaoQuoteContentSource
    ) : MaoQuoteLoadResult

    data class Empty(val message: String) : MaoQuoteLoadResult

    data class Failure(
        val reason: MaoQuoteFailureReason,
        val message: String
    ) : MaoQuoteLoadResult
}

/** 用户从系统文件选择器导入TXT时返回的完整状态。 */
sealed interface MaoQuoteImportResult {
    data class Success(val document: MaoQuoteDocument) : MaoQuoteImportResult

    data class Failure(
        val reason: MaoQuoteFailureReason,
        val message: String
    ) : MaoQuoteImportResult
}

/**
 * 严格按UTF-8解码并解析一份语录TXT。
 *
 * 使用方法：
 * Repository先以受限字节流读取文件，再把字节交给本函数。解码器会拒绝损坏或采用GBK等其他编码的
 * 文件，避免用替换字符悄悄破坏正文和稳定id。
 *
 * @param bytes TXT原始字节，可包含UTF-8 BOM。
 * @return 解析成功文档，或文件为空、过大、编码错误、结构超限等明确失败结果。
 */
fun parseMaoQuoteUtf8(bytes: ByteArray): MaoQuoteParseResult {
    if (bytes.isEmpty()) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.EMPTY_FILE,
            message = "所选TXT为空"
        )
    }
    if (bytes.size > MAX_MAO_QUOTE_FILE_BYTES) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.FILE_TOO_LARGE,
            message = "TXT文件过大，请选择不超过2MB的文件"
        )
    }

    val text = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.INVALID_UTF8,
            message = "无法按UTF-8读取TXT，请先转换文件编码"
        )
    }

    return parseMaoQuoteText(text)
}

/**
 * 解析已经解码的语录TXT文本。
 *
 * 使用方法：
 * 元数据必须放在首段正文之前；章节标题使用`# 章节名`；两个段落之间至少保留一个空行。同一段内
 * 连续的非空行会用换行符连接。若全文没有章节标题，所有段落自动归入“全文”。
 *
 * @param text 已解码的UTF-8文本；测试或可信内存文本可直接传入。
 * @return 包含稳定章节/语录id的文档，或可展示的结构失败结果。
 */
fun parseMaoQuoteText(text: String): MaoQuoteParseResult {
    if (text.toByteArray(Charsets.UTF_8).size > MAX_MAO_QUOTE_FILE_BYTES) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.FILE_TOO_LARGE,
            message = "TXT文件过大，请选择不超过2MB的文件"
        )
    }

    val normalizedText = text
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    if (normalizedText.isBlank()) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.EMPTY_FILE,
            message = "所选TXT为空"
        )
    }

    var title = DEFAULT_MAO_QUOTE_DOCUMENT_TITLE
    var source = ""
    var license = ""
    var redistributionAuthorized = false
    var contentStarted = false
    var sawChapterHeading = false
    var currentChapterTitle: String? = null
    val currentParagraphLines = mutableListOf<String>()
    val currentChapterParagraphs = mutableListOf<String>()
    val parsedChapters = mutableListOf<Pair<String?, List<String>>>()
    var limitFailure: MaoQuoteParseResult.Failure? = null

    /** 把当前非空行集合固化为一个段落，并执行单段长度限制。 */
    fun flushParagraph() {
        if (currentParagraphLines.isEmpty() || limitFailure != null) return
        val paragraph = currentParagraphLines.joinToString("\n").trim()
        currentParagraphLines.clear()
        if (paragraph.isEmpty()) return
        if (paragraph.length > MAX_MAO_QUOTE_TEXT_CHARS) {
            limitFailure = MaoQuoteParseResult.Failure(
                reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                message = "存在过长段落，请把单段控制在20000字以内"
            )
            return
        }
        currentChapterParagraphs += paragraph
    }

    /** 把已有段落固化为章节；只有标题但没有正文的章节不会进入阅读列表。 */
    fun flushChapter() {
        flushParagraph()
        if (currentChapterParagraphs.isEmpty() || limitFailure != null) return
        parsedChapters += currentChapterTitle to currentChapterParagraphs.toList()
        currentChapterParagraphs.clear()
    }

    normalizedText.split('\n').forEach { rawLine ->
        if (limitFailure != null) return@forEach
        val line = rawLine.trimEnd()
        if (!contentStarted) {
            val metadata = MAO_QUOTE_METADATA_PATTERN.matchEntire(line.trim())
            if (metadata != null) {
                val value = metadata.groupValues[2].trim()
                when (metadata.groupValues[1].lowercase(Locale.ROOT)) {
                    "title" -> {
                        if (value.length > MAX_MAO_QUOTE_TITLE_CHARS) {
                            limitFailure = MaoQuoteParseResult.Failure(
                                reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                                message = "TXT标题过长，请控制在120字以内"
                            )
                        } else {
                            title = value.ifEmpty { DEFAULT_MAO_QUOTE_DOCUMENT_TITLE }
                        }
                    }

                    "source" -> {
                        if (value.length > MAX_MAO_QUOTE_METADATA_CHARS) {
                            limitFailure = MaoQuoteParseResult.Failure(
                                reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                                message = "TXT来源说明过长，请控制在500字以内"
                            )
                        } else {
                            source = value
                        }
                    }

                    "license" -> {
                        if (value.length > MAX_MAO_QUOTE_METADATA_CHARS) {
                            limitFailure = MaoQuoteParseResult.Failure(
                                reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                                message = "TXT许可说明过长，请控制在500字以内"
                            )
                        } else {
                            license = value
                        }
                    }

                    "redistribution-authorized" -> {
                        if (value.length > 16) {
                            limitFailure = MaoQuoteParseResult.Failure(
                                reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                                message = "TXT再分发授权字段格式无效"
                            )
                        } else {
                            redistributionAuthorized = value.equals("true", ignoreCase = true)
                        }
                    }
                }
                return@forEach
            }
            if (line.isBlank()) return@forEach
            contentStarted = true
        }

        if (line.startsWith("# ")) {
            flushChapter()
            sawChapterHeading = true
            val chapterTitle = line.removePrefix("# ").trim().ifEmpty {
                DEFAULT_MAO_QUOTE_CHAPTER_TITLE
            }
            if (chapterTitle.length > MAX_MAO_QUOTE_CHAPTER_TITLE_CHARS) {
                limitFailure = MaoQuoteParseResult.Failure(
                    reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                    message = "TXT章节标题过长，请控制在120字以内"
                )
                return@forEach
            }
            currentChapterTitle = chapterTitle
        } else if (line.isBlank()) {
            flushParagraph()
        } else {
            currentParagraphLines += line.trim()
        }
    }
    flushChapter()

    limitFailure?.let { failure -> return failure }
    val quoteCount = parsedChapters.sumOf { (_, paragraphs) -> paragraphs.size }
    if (quoteCount == 0) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.NO_CONTENT,
            message = "TXT中没有可阅读的正文段落"
        )
    }
    if (parsedChapters.size > MAX_MAO_QUOTE_CHAPTER_COUNT || quoteCount > MAX_MAO_QUOTE_COUNT) {
        return MaoQuoteParseResult.Failure(
            reason = MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
            message = "TXT章节或段落数量过多，请拆分后重新导入"
        )
    }

    val chapterTitleOccurrences = mutableMapOf<String, Int>()
    val quoteOccurrences = mutableMapOf<String, Int>()
    var globalQuoteIndex = 0
    val chapters = parsedChapters.mapIndexed { chapterIndex, (rawTitle, paragraphs) ->
        val chapterTitle = if (!sawChapterHeading) {
            DEFAULT_MAO_QUOTE_CHAPTER_TITLE
        } else {
            rawTitle ?: DEFAULT_MAO_QUOTE_CHAPTER_TITLE
        }
        val chapterOccurrence = chapterTitleOccurrences.getOrDefault(chapterTitle, 0)
        chapterTitleOccurrences[chapterTitle] = chapterOccurrence + 1
        val chapterId = stableMaoQuoteId("chapter", chapterTitle, chapterOccurrence.toString())
        val quotes = paragraphs.mapIndexed { paragraphIndex, paragraph ->
            val quoteKey = "$chapterTitle\u0000$paragraph"
            val quoteOccurrence = quoteOccurrences.getOrDefault(quoteKey, 0)
            quoteOccurrences[quoteKey] = quoteOccurrence + 1
            MaoQuote(
                id = stableMaoQuoteId("quote", chapterTitle, paragraph, quoteOccurrence.toString()),
                chapterId = chapterId,
                chapterTitle = chapterTitle,
                indexInChapter = paragraphIndex,
                globalIndex = globalQuoteIndex++,
                text = paragraph
            )
        }
        MaoQuoteChapter(
            id = chapterId,
            title = chapterTitle,
            index = chapterIndex,
            quotes = quotes
        )
    }
    val documentId = stableMaoQuoteId(
        "document",
        chapters.flatMap(MaoQuoteChapter::quotes).joinToString("\u0000", transform = MaoQuote::id)
    )

    return MaoQuoteParseResult.Success(
        MaoQuoteDocument(
            id = documentId,
            metadata = MaoQuoteMetadata(
                title = title,
                source = source,
                license = license,
                redistributionAuthorized = redistributionAuthorized
            ),
            chapters = chapters
        )
    )
}

/**
 * 按章节、收藏和关键词筛选语录，同时保持文档原始顺序。
 *
 * @param document 当前已经解析的文档。
 * @param filter 页面当前筛选条件。
 * @param favoriteQuoteIds Repository读取的收藏语录id集合。
 * @return 所有条件同时满足的语录；无匹配时返回空列表。
 */
fun filterMaoQuotes(
    document: MaoQuoteDocument,
    filter: MaoQuoteFilter,
    favoriteQuoteIds: Set<String> = emptySet()
): List<MaoQuote> {
    val normalizedQuery = filter.query.trim().lowercase(Locale.ROOT)
    return document.quotes.filter { quote ->
        val matchesChapter = filter.chapterId == null || quote.chapterId == filter.chapterId
        val matchesFavorite = !filter.favoritesOnly || quote.id in favoriteQuoteIds
        val matchesQuery = normalizedQuery.isEmpty() || quote.searchableText.contains(normalizedQuery)
        matchesChapter && matchesFavorite && matchesQuery
    }
}

/**
 * 删除不属于当前文档的收藏id。
 *
 * @param document 当前内容文档。
 * @param favoriteQuoteIds 持久化层读取的原始收藏集合。
 * @return 仍能在当前文档中找到且保持去重的收藏集合。
 */
fun normalizeMaoQuoteFavoriteIds(
    document: MaoQuoteDocument,
    favoriteQuoteIds: Set<String>
): Set<String> {
    val validIds = document.quotes.mapTo(mutableSetOf(), MaoQuote::id)
    return favoriteQuoteIds.filterTo(linkedSetOf()) { quoteId -> quoteId in validIds }
}

/**
 * 校验并修正最后阅读位置。
 *
 * @param document 当前内容文档。
 * @param position 持久化读取的位置；首次使用时为null。
 * @return 段落仍存在时返回字符偏移已限制到正文范围的位置，否则返回null。
 */
fun normalizeMaoQuoteReadingPosition(
    document: MaoQuoteDocument,
    position: MaoQuoteReadingPosition?
): MaoQuoteReadingPosition? {
    position ?: return null
    val quote = document.quotes.firstOrNull { candidate -> candidate.id == position.quoteId }
        ?: return null
    return position.copy(characterOffset = position.characterOffset.coerceIn(0, quote.text.length))
}

/** 根据内容字段生成不含原文、跨进程稳定的96比特（24位十六进制）短标识。 */
private fun stableMaoQuoteId(prefix: String, vararg parts: String): String {
    val input = parts.joinToString("\u0000")
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .take(12)
    val hex = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
    return "$prefix-$hex"
}

private val MAO_QUOTE_METADATA_PATTERN = Regex(
    pattern = "^@(title|source|license|redistribution-authorized)\\s*:(.*)$",
    option = RegexOption.IGNORE_CASE
)

private const val HEX_DIGITS = "0123456789abcdef"
