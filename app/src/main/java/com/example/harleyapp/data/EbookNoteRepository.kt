package com.example.harleyapp.data

import android.content.Context
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookNote
import com.example.harleyapp.model.NotebookArticle
import com.example.harleyapp.model.NotebookBlockType
import com.example.harleyapp.model.NotebookCardLayout
import com.example.harleyapp.model.NotebookCardTheme
import com.example.harleyapp.model.NotebookContentBlock
import com.example.harleyapp.model.NotebookTextStyle
import com.example.harleyapp.model.newNotebookStableId
import java.security.MessageDigest

/**
 * 把电子书摘录直接保存为现有笔记中心文章，并恢复阅读页所需的页码索引。
 *
 * 使用方法：
 * 使用Application Context创建实例。阅读器长按选中文字后调用[createNote]；再次打开书籍时调用
 * [getBookNotes]恢复页边标记；阅读器删除摘录时调用[deleteNote]。本仓库不另建第二套正文数据库，
 * 所有摘录都以NotebookRepository文章为唯一数据源，因此会自然出现在“功能中心→笔记”中。
 *
 * @param context Android上下文。
 */
class EbookNoteRepository(context: Context) {

    private val notebookRepository = NotebookRepository(context.applicationContext)

    /**
     * 查询一本书的全部摘录笔记。
     *
     * @param book 当前电子书，用于生成稳定来源标签和恢复书名。
     * @return 按页码、创建时间排列的笔记；用户在笔记中心删除文章后，该项会自动消失。
     */
    fun getBookNotes(book: EbookBook): List<EbookNote> {
        val bookTag = createBookTag(book.id)
        return notebookRepository.getArticles()
            .asSequence()
            .filter { article ->
                NOTEBOOK_TAG in article.tags && bookTag in article.tags
            }
            .mapNotNull { article -> article.toEbookNote(book) }
            .sortedWith(compareBy(EbookNote::pageIndex, EbookNote::createdAtMillis))
            .toList()
    }

    /**
     * 创建一条带原文、感想和来源位置的正式笔记文章。
     *
     * 使用方法：
     * 阅读页在用户完成长按选择后，把选择文字和当前页传入。原文保存为引用块，个人感想保存为
     * 正文块，章节和页码保存为小标题；文章不是草稿，会立即显示在笔记中心并参与全局搜索。
     *
     * @param book 来源电子书。
     * @param pageIndex 来源零基页码。
     * @param chapterTitle 当前章节标题，无法识别时可为空。
     * @param excerpt 用户选择的原文。
     * @param comment 用户填写的个人想法，可为空。
     * @param nowMillis 创建时间，测试时可传固定值。
     * @return 保存成功后的同步笔记；原文为空或写入失败返回null。
     */
    fun createNote(
        book: EbookBook,
        pageIndex: Int,
        chapterTitle: String,
        excerpt: String,
        comment: String,
        nowMillis: Long = System.currentTimeMillis()
    ): EbookNote? {
        val safeExcerpt = excerpt.trim().take(MAX_EXCERPT_LENGTH)
        if (safeExcerpt.isBlank()) return null
        val safeComment = comment.trim().take(MAX_COMMENT_LENGTH)
        val safePage = pageIndex.coerceAtLeast(0)
        val safeChapter = chapterTitle.trim().take(MAX_CHAPTER_LENGTH)
        val locationTitle = buildString {
            if (safeChapter.isNotBlank()) append(safeChapter).append(" · ")
            append("第${safePage + 1}页")
        }
        val article = NotebookArticle(
            id = newNotebookStableId("ebook_note"),
            title = "《${book.title}》阅读摘录".take(MAX_NOTEBOOK_TITLE_LENGTH),
            blocks = buildList {
                add(
                    NotebookContentBlock(
                        id = newNotebookStableId("block"),
                        type = NotebookBlockType.TEXT,
                        text = locationTitle,
                        textStyle = NotebookTextStyle.HEADING,
                        bold = true
                    )
                )
                add(
                    NotebookContentBlock(
                        id = newNotebookStableId("block"),
                        type = NotebookBlockType.TEXT,
                        text = safeExcerpt,
                        textStyle = NotebookTextStyle.QUOTE
                    )
                )
                if (safeComment.isNotBlank()) {
                    add(
                        NotebookContentBlock(
                            id = newNotebookStableId("block"),
                            type = NotebookBlockType.TEXT,
                            text = safeComment,
                            textStyle = NotebookTextStyle.PARAGRAPH
                        )
                    )
                }
            },
            tags = listOf(
                NOTEBOOK_TAG,
                createBookTag(book.id),
                "$PAGE_TAG_PREFIX$safePage"
            ),
            isDraft = false,
            cardTheme = NotebookCardTheme.PAPER,
            cardLayout = NotebookCardLayout.STANDARD,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        val saved = notebookRepository.saveArticle(
            article = article,
            asDraft = false,
            nowMillis = nowMillis
        ) ?: return null
        return saved.toEbookNote(book)
    }

    /**
     * 删除阅读器中的摘录及笔记中心同一篇文章。
     *
     * @param articleId 目标摘录对应的笔记文章id。
     * @return 目标不存在或删除成功返回true，底层写入失败返回false。
     */
    fun deleteNote(articleId: String): Boolean {
        return notebookRepository.deleteArticle(articleId)
    }

    /**
     * 把笔记文章恢复为阅读器摘录模型。
     *
     * @param book 当前书籍，用于补全来源信息。
     * @return 元数据和引用原文均有效时返回摘录，否则返回null。
     */
    private fun NotebookArticle.toEbookNote(book: EbookBook): EbookNote? {
        val pageIndex = tags.firstOrNull { tag -> tag.startsWith(PAGE_TAG_PREFIX) }
            ?.removePrefix(PAGE_TAG_PREFIX)
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: return null
        val heading = blocks.firstOrNull { block ->
            block.type == NotebookBlockType.TEXT && block.textStyle == NotebookTextStyle.HEADING
        }?.text.orEmpty()
        val excerpt = blocks.firstOrNull { block ->
            block.type == NotebookBlockType.TEXT && block.textStyle == NotebookTextStyle.QUOTE
        }?.text?.trim().orEmpty()
        if (excerpt.isBlank()) return null
        val comment = blocks.firstOrNull { block ->
            block.type == NotebookBlockType.TEXT && block.textStyle == NotebookTextStyle.PARAGRAPH
        }?.text?.trim().orEmpty()
        val pageSuffix = " · 第${pageIndex + 1}页"
        val chapterTitle = heading.removeSuffix(pageSuffix)
            .takeUnless { value -> value == "第${pageIndex + 1}页" }
            .orEmpty()
        return EbookNote(
            articleId = id,
            bookId = book.id,
            bookTitle = book.title,
            pageIndex = pageIndex,
            chapterTitle = chapterTitle,
            excerpt = excerpt,
            comment = comment,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }

    /**
     * 由完整书籍id生成不泄漏原id、且满足笔记标签长度限制的稳定来源标签。
     *
     * @param bookId 电子书稳定id。
     * @return 由SHA-256前12个十六进制字符组成的短标签。
     */
    private fun createBookTag(bookId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8))
            .take(BOOK_HASH_BYTES)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "$BOOK_TAG_PREFIX$digest"
    }

    private companion object {
        const val NOTEBOOK_TAG = "电子书笔记"
        const val BOOK_TAG_PREFIX = "书籍_"
        const val PAGE_TAG_PREFIX = "页码_"
        const val BOOK_HASH_BYTES = 6
        const val MAX_EXCERPT_LENGTH = 8_000
        const val MAX_COMMENT_LENGTH = 8_000
        const val MAX_CHAPTER_LENGTH = 100
        const val MAX_NOTEBOOK_TITLE_LENGTH = 100
    }
}
