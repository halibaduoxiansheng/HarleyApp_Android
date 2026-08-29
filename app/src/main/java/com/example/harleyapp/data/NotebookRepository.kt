package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.NotebookArticle
import com.example.harleyapp.model.NotebookBlockType
import com.example.harleyapp.model.NotebookCardLayout
import com.example.harleyapp.model.NotebookCardTheme
import com.example.harleyapp.model.NotebookContentBlock
import com.example.harleyapp.model.NotebookTextStyle
import com.example.harleyapp.model.newNotebookStableId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理本地富内容文章及其创建、修改和阅读时间。
 *
 * 使用方法：
 * 使用Application Context创建实例。列表和查询调用[getArticles]；编辑器自动保存或完成发布时调用
 * [saveArticle]；详情页调用[markViewed]；删除、复制、收藏和置顶分别调用对应接口。文章结构保存到
 * Room兼容本地文档，图片和GIF由[mediaStore]保存在App私有目录，全程不访问网络。
 *
 * @param context Android上下文。
 */
class NotebookRepository(context: Context) {

    private val preferences = RoomBackedPreferences.create(
        context = context,
        preferenceName = PREFERENCE_NAME
    )

    /** 供编辑器导入和页面读取私有图片、GIF的媒体存储。 */
    val mediaStore = NotebookMediaStore(context)

    /**
     * 查询全部文章。
     *
     * @return 置顶优先、其余按最近修改时间倒序的安全文章列表；损坏单条会跳过。
     */
    fun getArticles(): List<NotebookArticle> {
        val rawArticles = preferences.getString(KEY_ARTICLES, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(rawArticles)
            buildList<NotebookArticle> {
                for (index in 0 until jsonArray.length().coerceAtMost(MAX_ARTICLE_COUNT)) {
                    runCatching {
                        sanitizeArticle(jsonArray.getJSONObject(index).toArticle())
                    }.onSuccess { article ->
                        if (article != null && none { existing -> existing.id == article.id }) {
                            add(article)
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Skipped malformed notebook article", error)
                    }
                }
            }.sortedWith(articleOrder())
        }.onFailure { error ->
            Log.e(TAG, "Failed to parse notebook articles", error)
        }.getOrDefault(emptyList())
    }

    /**
     * 查询指定文章。
     *
     * @param articleId 文章稳定id。
     * @return 找到时返回文章，否则返回null。
     */
    fun getArticle(articleId: String): NotebookArticle? {
        return getArticles().firstOrNull { article -> article.id == articleId }
    }

    /**
     * 新增或更新文章，并由仓库统一维护时间戳。
     *
     * 使用方法：
     * 自动保存传asDraft=true，允许空标题并显示为“未命名文章”；用户点击完成时传false，仓库要求
     * 标题和至少一个有效内容块。已有文章始终保留原创建时间、阅读时间和阅读次数，修改只更新
     * updatedAtMillis。全部文章和媒体引用一次提交后才清理孤立图片。
     *
     * @param article 编辑器提交的文章。
     * @param asDraft 是否作为草稿保存。
     * @param nowMillis 当前时间，测试可传固定值。
     * @return 保存成功的安全文章；校验或写入失败返回null。
     */
    @Synchronized
    fun saveArticle(
        article: NotebookArticle,
        asDraft: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): NotebookArticle? {
        val articles = getArticles().toMutableList()
        val existingIndex = articles.indexOfFirst { existing -> existing.id == article.id }
        if (existingIndex < 0 && articles.size >= MAX_ARTICLE_COUNT) return null
        val existing = articles.getOrNull(existingIndex)
        val safeArticle = sanitizeArticle(
            article.copy(
                title = if (asDraft && article.title.trim().isBlank()) {
                    UNTITLED_ARTICLE_TITLE
                } else {
                    article.title
                },
                isDraft = asDraft,
                createdAtMillis = existing?.createdAtMillis
                    ?: article.createdAtMillis.takeIf { value -> value > 0L }
                    ?: nowMillis,
                updatedAtMillis = nowMillis,
                lastViewedAtMillis = existing?.lastViewedAtMillis
                    ?: article.lastViewedAtMillis.coerceAtLeast(0L),
                viewCount = existing?.viewCount
                    ?: article.viewCount.coerceAtLeast(0)
            )
        ) ?: return null
        if (!asDraft && (
                safeArticle.title == UNTITLED_ARTICLE_TITLE ||
                    safeArticle.blocks.none(::isMeaningfulBlock)
                )
        ) {
            return null
        }
        if (existingIndex >= 0) {
            articles[existingIndex] = safeArticle
        } else {
            articles.add(safeArticle)
        }
        if (!persistArticles(articles)) return null
        cleanupUnusedMedia(articles)
        return safeArticle
    }

    /**
     * 删除一篇文章并清理不再被任何文章引用的媒体。
     *
     * @param articleId 需要删除的文章id。
     * @return 文章不存在或删除成功返回true，写入失败返回false。
     */
    @Synchronized
    fun deleteArticle(articleId: String): Boolean {
        val articles = getArticles().toMutableList()
        val removed = articles.removeAll { article -> article.id == articleId }
        if (!removed) return true
        if (!persistArticles(articles)) return false
        cleanupUnusedMedia(articles)
        return true
    }

    /**
     * 复制一篇文章为新的草稿。
     *
     * 使用方法：
     * 列表或详情页点击“复制”后调用；媒体文件可以安全共享引用，删除其中一篇时只有没有任何文章
     * 再引用的文件才会被清理。
     *
     * @param articleId 原文章id。
     * @param nowMillis 新文章创建时间。
     * @return 复制并保存成功的新草稿，否则返回null。
     */
    fun duplicateArticle(
        articleId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): NotebookArticle? {
        val original = getArticle(articleId) ?: return null
        val duplicatedBlocks = original.blocks.map { block ->
            block.copy(id = newNotebookStableId("block"))
        }
        return saveArticle(
            article = original.copy(
                id = newNotebookStableId("article"),
                title = "${original.title}（副本）".take(MAX_TITLE_LENGTH),
                blocks = duplicatedBlocks,
                isPinned = false,
                isDraft = true,
                coverBlockId = "",
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
                lastViewedAtMillis = 0L,
                viewCount = 0
            ),
            asDraft = true,
            nowMillis = nowMillis
        )
    }

    /**
     * 更新文章收藏状态。
     *
     * @param articleId 文章id。
     * @param favorite 新收藏状态。
     * @return 保存成功后的文章，否则返回null。
     */
    fun setFavorite(articleId: String, favorite: Boolean): NotebookArticle? {
        val article = getArticle(articleId) ?: return null
        return saveArticle(article.copy(isFavorite = favorite), article.isDraft)
    }

    /**
     * 更新文章置顶状态。
     *
     * @param articleId 文章id。
     * @param pinned 新置顶状态。
     * @return 保存成功后的文章，否则返回null。
     */
    fun setPinned(articleId: String, pinned: Boolean): NotebookArticle? {
        val article = getArticle(articleId) ?: return null
        return saveArticle(article.copy(isPinned = pinned), article.isDraft)
    }

    /**
     * 记录文章详情被打开，不改变最近修改时间。
     *
     * @param articleId 文章id。
     * @param nowMillis 本次阅读时间。
     * @return 更新成功后的文章，找不到或写入失败返回null。
     */
    @Synchronized
    fun markViewed(
        articleId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): NotebookArticle? {
        val articles = getArticles().toMutableList()
        val index = articles.indexOfFirst { article -> article.id == articleId }
        if (index < 0) return null
        val updated = articles[index].copy(
            lastViewedAtMillis = nowMillis,
            viewCount = (articles[index].viewCount + 1).coerceAtMost(Int.MAX_VALUE)
        )
        articles[index] = updated
        return if (persistArticles(articles)) updated else null
    }

    /**
     * 对文章和块字段进行长度、id、标签及媒体文件名校验。
     *
     * @param article 原始文章。
     * @return 可持久化文章；id或标题无效时返回null。
     */
    private fun sanitizeArticle(article: NotebookArticle): NotebookArticle? {
        val safeId = article.id.trim().take(MAX_ID_LENGTH)
        val safeTitle = article.title.trim().take(MAX_TITLE_LENGTH)
        if (safeId.isBlank() || safeTitle.isBlank()) return null
        val safeBlocks = article.blocks
            .take(MAX_BLOCK_COUNT)
            .mapNotNull(::sanitizeBlock)
            .distinctBy(NotebookContentBlock::id)
        val safeTags = article.tags
            .map { tag -> tag.trim().take(MAX_TAG_LENGTH) }
            .filter(String::isNotBlank)
            .distinctBy { tag -> tag.lowercase() }
            .take(MAX_TAG_COUNT)
        val validCoverId = article.coverBlockId.takeIf { coverId ->
            safeBlocks.any { block ->
                block.id == coverId && block.type == NotebookBlockType.IMAGE
            }
        }.orEmpty()
        return article.copy(
            id = safeId,
            title = safeTitle,
            blocks = safeBlocks,
            tags = safeTags,
            coverBlockId = validCoverId,
            createdAtMillis = article.createdAtMillis.coerceAtLeast(1L),
            updatedAtMillis = article.updatedAtMillis.coerceAtLeast(1L),
            lastViewedAtMillis = article.lastViewedAtMillis.coerceAtLeast(0L),
            viewCount = article.viewCount.coerceAtLeast(0)
        )
    }

    /**
     * 校验单个内容块并清理未使用字段。
     *
     * @param block 原始内容块。
     * @return 可保存块；id或媒体文件名非法时返回null。
     */
    private fun sanitizeBlock(block: NotebookContentBlock): NotebookContentBlock? {
        val safeId = block.id.trim().take(MAX_ID_LENGTH)
        if (safeId.isBlank()) return null
        return when (block.type) {
            NotebookBlockType.TEXT -> block.copy(
                id = safeId,
                text = block.text.take(MAX_TEXT_BLOCK_LENGTH),
                mediaFileName = "",
                mediaMimeType = "",
                mediaCaption = "",
                linkTitle = "",
                linkUrl = ""
            )
            NotebookBlockType.IMAGE -> {
                if (!isValidNotebookMediaFileName(block.mediaFileName)) return null
                block.copy(
                    id = safeId,
                    text = "",
                    mediaMimeType = block.mediaMimeType.trim().take(MAX_MIME_LENGTH),
                    mediaCaption = block.mediaCaption.trim().take(MAX_CAPTION_LENGTH),
                    linkTitle = "",
                    linkUrl = ""
                )
            }
            NotebookBlockType.LINK -> block.copy(
                id = safeId,
                text = "",
                mediaFileName = "",
                mediaMimeType = "",
                mediaCaption = "",
                linkTitle = block.linkTitle.trim().take(MAX_LINK_TITLE_LENGTH),
                linkUrl = block.linkUrl.trim().take(MAX_LINK_URL_LENGTH)
            )
            NotebookBlockType.DIVIDER -> NotebookContentBlock(
                id = safeId,
                type = NotebookBlockType.DIVIDER
            )
        }
    }

    /**
     * 把完整文章列表原子写入Room兼容存储。
     *
     * @param articles 待保存列表。
     * @return 写入成功返回true。
     */
    private fun persistArticles(articles: List<NotebookArticle>): Boolean {
        val jsonArray = JSONArray()
        articles.sortedWith(articleOrder()).forEach { article ->
            jsonArray.put(article.toJson())
        }
        val success = preferences.edit()
            .putString(KEY_ARTICLES, jsonArray.toString())
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist notebook articles")
        }
        return success
    }

    /**
     * 清理文章列表不再引用的图片和GIF。
     *
     * @param articles 当前全部文章。
     */
    private fun cleanupUnusedMedia(articles: List<NotebookArticle>) {
        val usedFiles = articles.asSequence()
            .flatMap { article -> article.blocks.asSequence() }
            .filter { block -> block.type == NotebookBlockType.IMAGE }
            .map(NotebookContentBlock::mediaFileName)
            .filter(String::isNotBlank)
            .toSet()
        mediaStore.deleteUnusedMedia(usedFiles)
    }

    /** @return 文章列表的统一置顶与修改时间排序器。 */
    private fun articleOrder(): Comparator<NotebookArticle> {
        return compareByDescending<NotebookArticle> { article -> article.isPinned }
            .thenByDescending { article -> article.updatedAtMillis }
    }

    /** @return JSON对象对应的文章。 */
    private fun JSONObject.toArticle(): NotebookArticle {
        val blocksArray = optJSONArray(JSON_BLOCKS) ?: JSONArray()
        val blocks = buildList {
            for (index in 0 until blocksArray.length().coerceAtMost(MAX_BLOCK_COUNT)) {
                add(blocksArray.getJSONObject(index).toBlock())
            }
        }
        val tagsArray = optJSONArray(JSON_TAGS) ?: JSONArray()
        val tags = buildList {
            for (index in 0 until tagsArray.length().coerceAtMost(MAX_TAG_COUNT)) {
                add(tagsArray.optString(index))
            }
        }
        return NotebookArticle(
            id = optString(JSON_ID),
            title = optString(JSON_TITLE, UNTITLED_ARTICLE_TITLE),
            blocks = blocks,
            tags = tags,
            isFavorite = optBoolean(JSON_FAVORITE, false),
            isPinned = optBoolean(JSON_PINNED, false),
            isDraft = optBoolean(JSON_DRAFT, false),
            cardTheme = enumValueOrDefault(
                rawValue = optString(JSON_CARD_THEME),
                defaultValue = NotebookCardTheme.PAPER
            ),
            cardLayout = enumValueOrDefault(
                rawValue = optString(JSON_CARD_LAYOUT),
                defaultValue = NotebookCardLayout.STANDARD
            ),
            coverBlockId = optString(JSON_COVER_BLOCK_ID),
            createdAtMillis = optLong(JSON_CREATED_AT, 1L),
            updatedAtMillis = optLong(JSON_UPDATED_AT, 1L),
            lastViewedAtMillis = optLong(JSON_LAST_VIEWED_AT, 0L),
            viewCount = optInt(JSON_VIEW_COUNT, 0)
        )
    }

    /** @return JSON对象对应的文章内容块。 */
    private fun JSONObject.toBlock(): NotebookContentBlock {
        return NotebookContentBlock(
            id = optString(JSON_ID),
            type = enumValueOrDefault(
                rawValue = optString(JSON_TYPE),
                defaultValue = NotebookBlockType.TEXT
            ),
            text = optString(JSON_TEXT),
            textStyle = enumValueOrDefault(
                rawValue = optString(JSON_TEXT_STYLE),
                defaultValue = NotebookTextStyle.PARAGRAPH
            ),
            bold = optBoolean(JSON_BOLD, false),
            italic = optBoolean(JSON_ITALIC, false),
            underline = optBoolean(JSON_UNDERLINE, false),
            mediaFileName = optString(JSON_MEDIA_FILE_NAME),
            mediaMimeType = optString(JSON_MEDIA_MIME_TYPE),
            mediaCaption = optString(JSON_MEDIA_CAPTION),
            linkTitle = optString(JSON_LINK_TITLE),
            linkUrl = optString(JSON_LINK_URL)
        )
    }

    /** @return 文章及全部块字段对应的JSON对象。 */
    private fun NotebookArticle.toJson(): JSONObject {
        val blocksArray = JSONArray()
        blocks.forEach { block -> blocksArray.put(block.toJson()) }
        val tagsArray = JSONArray()
        tags.forEach(tagsArray::put)
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_TITLE, title)
            .put(JSON_BLOCKS, blocksArray)
            .put(JSON_TAGS, tagsArray)
            .put(JSON_FAVORITE, isFavorite)
            .put(JSON_PINNED, isPinned)
            .put(JSON_DRAFT, isDraft)
            .put(JSON_CARD_THEME, cardTheme.name)
            .put(JSON_CARD_LAYOUT, cardLayout.name)
            .put(JSON_COVER_BLOCK_ID, coverBlockId)
            .put(JSON_CREATED_AT, createdAtMillis)
            .put(JSON_UPDATED_AT, updatedAtMillis)
            .put(JSON_LAST_VIEWED_AT, lastViewedAtMillis)
            .put(JSON_VIEW_COUNT, viewCount)
    }

    /** @return 内容块对应的JSON对象。 */
    private fun NotebookContentBlock.toJson(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_TYPE, type.name)
            .put(JSON_TEXT, text)
            .put(JSON_TEXT_STYLE, textStyle.name)
            .put(JSON_BOLD, bold)
            .put(JSON_ITALIC, italic)
            .put(JSON_UNDERLINE, underline)
            .put(JSON_MEDIA_FILE_NAME, mediaFileName)
            .put(JSON_MEDIA_MIME_TYPE, mediaMimeType)
            .put(JSON_MEDIA_CAPTION, mediaCaption)
            .put(JSON_LINK_TITLE, linkTitle)
            .put(JSON_LINK_URL, linkUrl)
    }

    /**
     * 安全解析枚举，旧版本未知值回退到默认项。
     *
     * @param rawValue JSON枚举名称。
     * @param defaultValue 默认值。
     * @return 匹配枚举或默认值。
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        rawValue: String,
        defaultValue: T
    ): T {
        return enumValues<T>().firstOrNull { value -> value.name == rawValue } ?: defaultValue
    }

    private companion object {
        const val TAG = "NotebookRepository"
        const val PREFERENCE_NAME = "harley_notebook"
        const val KEY_ARTICLES = "articles_v1"
        const val UNTITLED_ARTICLE_TITLE = "未命名文章"
        const val MAX_ARTICLE_COUNT = 500
        const val MAX_BLOCK_COUNT = 300
        const val MAX_ID_LENGTH = 64
        const val MAX_TITLE_LENGTH = 100
        const val MAX_TEXT_BLOCK_LENGTH = 20_000
        const val MAX_CAPTION_LENGTH = 300
        const val MAX_LINK_TITLE_LENGTH = 200
        const val MAX_LINK_URL_LENGTH = 2_048
        const val MAX_MIME_LENGTH = 80
        const val MAX_TAG_COUNT = 12
        const val MAX_TAG_LENGTH = 20

        const val JSON_ID = "id"
        const val JSON_TITLE = "title"
        const val JSON_BLOCKS = "blocks"
        const val JSON_TAGS = "tags"
        const val JSON_FAVORITE = "favorite"
        const val JSON_PINNED = "pinned"
        const val JSON_DRAFT = "draft"
        const val JSON_CARD_THEME = "card_theme"
        const val JSON_CARD_LAYOUT = "card_layout"
        const val JSON_COVER_BLOCK_ID = "cover_block_id"
        const val JSON_CREATED_AT = "created_at"
        const val JSON_UPDATED_AT = "updated_at"
        const val JSON_LAST_VIEWED_AT = "last_viewed_at"
        const val JSON_VIEW_COUNT = "view_count"
        const val JSON_TYPE = "type"
        const val JSON_TEXT = "text"
        const val JSON_TEXT_STYLE = "text_style"
        const val JSON_BOLD = "bold"
        const val JSON_ITALIC = "italic"
        const val JSON_UNDERLINE = "underline"
        const val JSON_MEDIA_FILE_NAME = "media_file_name"
        const val JSON_MEDIA_MIME_TYPE = "media_mime_type"
        const val JSON_MEDIA_CAPTION = "media_caption"
        const val JSON_LINK_TITLE = "link_title"
        const val JSON_LINK_URL = "link_url"
    }
}

/**
 * 判断内容块是否具有可发布内容。
 *
 * @param block 待检查块。
 * @return 文字非空、媒体文件名有效、链接地址非空或分隔线时返回true。
 */
private fun isMeaningfulBlock(block: NotebookContentBlock): Boolean {
    return when (block.type) {
        NotebookBlockType.TEXT -> block.text.isNotBlank()
        NotebookBlockType.IMAGE -> block.mediaFileName.isNotBlank()
        NotebookBlockType.LINK -> block.linkUrl.isNotBlank()
        NotebookBlockType.DIVIDER -> true
    }
}
