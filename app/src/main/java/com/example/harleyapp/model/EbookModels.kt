package com.example.harleyapp.model

import java.util.Locale

/**
 * 电子书书库当前支持的文件格式。
 *
 * 使用方法：
 * 导入时调用[fromFileName]根据文件扩展名识别格式；PDF保留原始分页，其余格式会在本机提取为
 * 可分页纯文本，同时继续保存原始文件，便于未来升级解析能力。
 *
 * @param displayName 页面显示的格式名称。
 * @param extensions 能够识别的文件扩展名，不包含小数点。
 */
enum class EbookFormat(
    val displayName: String,
    val extensions: Set<String>
) {
    PDF("PDF", setOf("pdf")),
    EPUB("EPUB", setOf("epub")),
    TEXT("纯文本", setOf("txt")),
    MARKDOWN("Markdown", setOf("md", "markdown")),
    HTML("HTML", setOf("html", "htm")),
    DOCX("Word DOCX", setOf("docx")),
    FB2("FictionBook", setOf("fb2")),
    RTF("RTF", setOf("rtf"));

    companion object {

        /**
         * 根据文件名识别电子书格式。
         *
         * @param fileName 系统文件选择器返回的显示名称。
         * @return 找到受支持扩展名时返回格式，否则返回null。
         */
        fun fromFileName(fileName: String): EbookFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return entries.firstOrNull { format -> extension in format.extensions }
        }
    }
}

/**
 * 电子书阅读翻页模式。
 *
 * @param displayName 阅读工具栏显示名称。
 */
enum class EbookReadingMode(val displayName: String) {
    PAGE_CURL("仿真翻书"),
    HORIZONTAL("左右滑页"),
    VERTICAL("上下滑页"),
    FADE("淡入切页")
}

/**
 * 电子书阅读区可选背景。
 *
 * @param displayName 阅读设置中显示的名称。
 */
enum class EbookReadingBackground(val displayName: String) {
    PAPER("纸张白"),
    WARM("护眼羊皮"),
    GREEN("柔和绿"),
    NIGHT("深夜黑")
}

/**
 * WebView捕获到的一次电子书下载请求。
 *
 * 使用方法：
 * 网站页面收到下载事件后构造本模型并交给EbookRepository。仓库只接受网页最终给出的HTTP或
 * HTTPS公开直链，不复制Cookie、Referer或登录凭据，避免把用户会话转发给第三方下载域名。
 *
 * @param url 下载资源的绝对地址。
 * @param contentDisposition 服务器或网页提供的文件名响应头，可为空。
 * @param mimeType 网页报告的MIME类型，可为空。
 */
data class EbookWebDownloadRequest(
    val url: String,
    val contentDisposition: String = "",
    val mimeType: String = ""
)

/**
 * 从电子书正文识别出的一项目录。
 *
 * @param title 章节标题。
 * @param pageIndex 对应的零基阅读页码。
 * @param level Markdown标题层级；普通小说章节为1。
 */
data class EbookChapter(
    val title: String,
    val pageIndex: Int,
    val level: Int = 1
)

/**
 * 书库中的一册本地电子书。
 *
 * 使用方法：
 * 只由EbookRepository创建和更新。页面使用[id]作为稳定键，使用[storedFileName]读取原文件，
 * 非PDF格式使用[extractedTextFileName]读取离线解析结果。
 *
 * @param id 书籍稳定标识。
 * @param title 用户可编辑的书名。
 * @param author 用户可编辑的作者；未知时为空。
 * @param format 原文件格式。
 * @param originalFileName 导入时系统提供的文件名。
 * @param storedFileName App私有目录中的受控原文件名。
 * @param extractedTextFileName 非PDF格式的离线文本文件名；PDF为空。
 * @param fileSizeBytes 原文件大小。
 * @param createdAtMillis 首次导入时间。
 * @param updatedAtMillis 最近编辑元数据时间。
 * @param lastReadAtMillis 最近阅读时间；尚未阅读为0。
 * @param currentPage 上次停留的零基页码。
 * @param pageCount 导入时计算的页数或文本估算页数，至少为1。
 * @param readingMode 上次选择的翻页模式。
 * @param fontScale 文本阅读字号倍率，限制在0.75至1.8。
 * @param fontFamily 文本阅读字体；PDF不应用此字段。
 * @param readingBackground 上次选择的阅读背景。
 * @param isOnShelf 是否由用户选择展示在分页书架中。
 * @param shelfOrder 书架排序值，数值小的优先显示。
 * @param spineColorArgb 用户选择的不透明ARGB书脊颜色；0表示按书籍id自动配色。
 * @param category 书籍类别，用于全部书籍页筛选与说明。
 * @param sourceUrl 内置公版书的来源页；用户导入书籍为空。
 * @param isBundled 是否来自App内置公版书单。
 */
data class EbookBook(
    val id: String,
    val title: String,
    val author: String,
    val format: EbookFormat,
    val originalFileName: String,
    val storedFileName: String,
    val extractedTextFileName: String,
    val fileSizeBytes: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastReadAtMillis: Long = 0L,
    val currentPage: Int = 0,
    val pageCount: Int = 1,
    val readingMode: EbookReadingMode = EbookReadingMode.HORIZONTAL,
    val fontScale: Float = 1f,
    val fontFamily: EbookFontFamily = EbookFontFamily.SERIF,
    val readingBackground: EbookReadingBackground = EbookReadingBackground.PAPER,
    val isOnShelf: Boolean = true,
    val shelfOrder: Long = createdAtMillis,
    val spineColorArgb: Int = 0,
    val category: String = "导入书籍",
    val sourceUrl: String = "",
    val isBundled: Boolean = false
)

/**
 * 文本电子书可选择的正文字体族。
 *
 * 使用方法：
 * 阅读设置使用[displayName]展示选项，阅读页根据枚举值映射到Compose字体族；仓库使用稳定的
 * 枚举名称保存，下次打开同一本书时自动恢复。PDF自身已包含排版，因此不应用本设置。
 *
 * @param displayName 阅读设置中显示的中文名称。
 */
enum class EbookFontFamily(val displayName: String) {
    SERIF("衬线宋体"),
    SANS_SERIF("现代黑体"),
    MONOSPACE("等宽字体"),
    CURSIVE("手写风格")
}

/**
 * 一条与笔记中心文章完全同步的电子书摘录笔记。
 *
 * 使用方法：
 * 由EbookNoteRepository从带电子书元数据标签的NotebookArticle转换得到。阅读器使用[pageIndex]
 * 显示页边标记并跳回原页，使用[articleId]删除或更新笔记中心中的同一篇文章。
 *
 * @param articleId 笔记中心文章的稳定id，也是本条摘录的唯一id。
 * @param bookId 来源电子书id。
 * @param bookTitle 创建笔记时的书名快照。
 * @param pageIndex 来源零基页码。
 * @param chapterTitle 创建笔记时所在章节；无法识别时为空。
 * @param excerpt 用户长按选择的原文。
 * @param comment 用户填写的个人想法，可为空。
 * @param createdAtMillis 笔记创建时间。
 * @param updatedAtMillis 笔记中心最后修改时间。
 */
data class EbookNote(
    val articleId: String,
    val bookId: String,
    val bookTitle: String,
    val pageIndex: Int,
    val chapterTitle: String,
    val excerpt: String,
    val comment: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

/**
 * 阅读页翻译的语言方向。
 *
 * @param displayName 工具栏显示名称。
 */
enum class EbookTranslationDirection(val displayName: String) {
    ENGLISH_TO_CHINESE("英译中"),
    CHINESE_TO_ENGLISH("中译英")
}

/**
 * 阅读页原文和译文的组合方式。
 *
 * @param displayName 工具栏显示名称。
 */
enum class EbookTranslationDisplayMode(val displayName: String) {
    ORIGINAL("原文"),
    BILINGUAL("对照"),
    TRANSLATED("译文")
}

/**
 * 一次电子书导入结果。
 *
 * @param success 是否成功复制、解析并登记到书库。
 * @param book 成功时的新书模型。
 * @param message 页面可直接显示的中文结果信息。
 */
data class EbookImportResult(
    val success: Boolean,
    val book: EbookBook? = null,
    val message: String
)
