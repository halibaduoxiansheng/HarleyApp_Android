package com.example.harleyapp.system

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.harleyapp.data.NotebookMediaStore
import com.example.harleyapp.model.NotebookArticle
import com.example.harleyapp.model.NotebookBlockType
import com.example.harleyapp.model.NotebookTextStyle
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 管理记事本文章的纯文本、图文和可迁移文章包分享。
 *
 * 使用方法：
 * 详情页创建本类后，根据用户选择调用[sharePlainText]、[shareRichContent]或[shareArticlePackage]。
 * 媒体只会复制到App缓存目录，再由FileProvider签发临时只读Uri；接收方无法访问App其他私有文件。
 *
 * @param context Android上下文，内部只保存Application Context。
 * @param mediaStore 记事本私有媒体仓库，用于查找文章实际引用的图片或GIF。
 */
class NotebookShareManager(
    context: Context,
    private val mediaStore: NotebookMediaStore
) {

    private val applicationContext = context.applicationContext
    private val shareRoot = File(applicationContext.cacheDir, SHARE_ROOT_DIRECTORY)

    /**
     * 通过系统分享面板发送文章标题、标签和纯文本正文。
     *
     * @param article 待分享文章。
     * @return 成功启动系统分享面板返回true；失败返回false。
     */
    fun sharePlainText(article: NotebookArticle): Boolean {
        return startChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, article.title)
                putExtra(Intent.EXTRA_TEXT, buildShareText(article))
            },
            chooserTitle = "分享文章文字"
        )
    }

    /**
     * 把文章文字和其中仍存在的图片或GIF一起交给系统分享面板。
     *
     * 使用方法：
     * 用户希望在微信、邮件或网盘中保留图片时调用。接收App是否把文字与多张图片组合成同一条消息，
     * 由目标App自身能力决定；没有有效媒体时自动回退到纯文本分享。
     *
     * @param article 待分享文章。
     * @return 成功启动系统分享面板返回true；准备缓存或启动失败返回false。
     */
    fun shareRichContent(article: NotebookArticle): Boolean {
        return runCatching {
            val shareDirectory = prepareArticleDirectory(article)
            val mediaUris = copyArticleMedia(article, shareDirectory)
            if (mediaUris.isEmpty()) {
                return sharePlainText(article)
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_SUBJECT, article.title)
                putExtra(Intent.EXTRA_TEXT, buildShareText(article))
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(mediaUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = buildClipData(mediaUris)
            }
            startChooser(intent, chooserTitle = "分享文章图文")
        }.onFailure { error ->
            Log.e(TAG, "Failed to prepare rich notebook share", error)
        }.getOrDefault(false)
    }

    /**
     * 导出包含Markdown、HTML和原始媒体的ZIP文章包并打开系统分享面板。
     *
     * 使用方法：
     * 用户需要完整存档、跨设备迁移或交给电脑继续编辑时调用。ZIP中使用相对media目录引用图片，
     * 不依赖App私有路径；该文章包是通用文件，不会修改记事本中的原文章。
     *
     * @param article 待导出的文章。
     * @return 文章包创建并成功启动系统分享面板返回true；失败返回false。
     */
    fun shareArticlePackage(article: NotebookArticle): Boolean {
        return runCatching {
            val shareDirectory = prepareArticleDirectory(article)
            val packageFile = File(
                shareDirectory,
                "${safeFileBase(article.title)}.harleynote.zip"
            )
            writeArticlePackage(article, packageFile)
            val packageUri = uriFor(packageFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = ZIP_MIME_TYPE
                putExtra(Intent.EXTRA_SUBJECT, article.title)
                putExtra(Intent.EXTRA_TEXT, "Harley记事本文章包：${article.title}")
                putExtra(Intent.EXTRA_STREAM, packageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(article.title, packageUri)
            }
            startChooser(intent, chooserTitle = "导出文章包")
        }.onFailure { error ->
            Log.e(TAG, "Failed to export notebook package", error)
        }.getOrDefault(false)
    }

    /** @return 创建并清空当前文章专用缓存目录。 */
    private fun prepareArticleDirectory(article: NotebookArticle): File {
        require(shareRoot.exists() || shareRoot.mkdirs()) {
            "Unable to create notebook share root"
        }
        val safeArticleId = article.id.replace(UNSAFE_FILE_CHARACTER_REGEX, "_").take(80)
        val directory = File(shareRoot, safeArticleId.ifBlank { "article" })
        if (directory.exists()) {
            require(directory.deleteRecursively()) { "Unable to reset notebook share cache" }
        }
        require(directory.mkdirs()) { "Unable to create notebook share directory" }
        return directory
    }

    /** @return 复制成功的媒体FileProvider Uri列表。 */
    private fun copyArticleMedia(article: NotebookArticle, directory: File): List<Uri> {
        val mediaDirectory = File(directory, "media").apply {
            require(exists() || mkdirs()) { "Unable to create shared media directory" }
        }
        return referencedMedia(article).map { media ->
            val target = File(mediaDirectory, media.name)
            media.copyTo(target, overwrite = true)
            uriFor(target)
        }
    }

    /** @return 文章引用且当前仍真实存在的媒体文件，文件名去重并保持正文顺序。 */
    private fun referencedMedia(article: NotebookArticle): List<File> {
        val usedNames = linkedSetOf<String>()
        return article.blocks.mapNotNull { block ->
            block.mediaFileName
                .takeIf { name ->
                    block.type == NotebookBlockType.IMAGE &&
                        name.isNotBlank() &&
                        usedNames.add(name)
                }
                ?.let(mediaStore::fileFor)
        }
    }

    /** 把一篇文章写为同时适合人类阅读和后续编辑的ZIP包。 */
    private fun writeArticlePackage(article: NotebookArticle, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.writeTextEntry("article.md", buildMarkdown(article))
            zip.writeTextEntry("article.html", buildHtml(article))
            referencedMedia(article).forEach { media ->
                zip.putNextEntry(ZipEntry("media/${media.name}"))
                media.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        require(target.isFile && target.length() > 0L) { "Notebook package is empty" }
    }

    /** @return 适合聊天工具和剪贴板的文章纯文本。 */
    private fun buildShareText(article: NotebookArticle): String {
        return buildString {
            append(article.title.ifBlank { "未命名文章" })
            if (article.tags.isNotEmpty()) {
                append('\n')
                append(article.tags.joinToString(" ") { tag -> "#$tag" })
            }
            article.plainText().takeIf(String::isNotBlank)?.let { body ->
                append("\n\n")
                append(body)
            }
        }
    }

    /** @return 保留标题、列表、引用、链接和媒体相对路径的Markdown文本。 */
    private fun buildMarkdown(article: NotebookArticle): String {
        return buildString {
            append("# ").append(article.title.ifBlank { "未命名文章" }).append("\n\n")
            if (article.tags.isNotEmpty()) {
                append(article.tags.joinToString(" ") { tag -> "#$tag" }).append("\n\n")
            }
            var numberedIndex = 1
            article.blocks.forEach { block ->
                when (block.type) {
                    NotebookBlockType.TEXT -> {
                        val prefix = when (block.textStyle) {
                            NotebookTextStyle.HEADING -> "## "
                            NotebookTextStyle.QUOTE -> "> "
                            NotebookTextStyle.BULLET -> "- "
                            NotebookTextStyle.NUMBERED -> "${numberedIndex++}. "
                            NotebookTextStyle.PARAGRAPH -> ""
                        }
                        append(prefix).append(block.text.trim()).append("\n\n")
                    }

                    NotebookBlockType.IMAGE -> {
                        val caption = block.mediaCaption.ifBlank { "图片" }
                        append("![$caption](media/${block.mediaFileName})\n\n")
                    }

                    NotebookBlockType.LINK -> append('[')
                        .append(block.linkTitle.ifBlank { block.linkUrl })
                        .append("](").append(block.linkUrl).append(")\n\n")

                    NotebookBlockType.DIVIDER -> append("---\n\n")
                }
            }
        }
    }

    /** @return 可由浏览器直接打开、只引用包内相对媒体路径的HTML文本。 */
    private fun buildHtml(article: NotebookArticle): String {
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{max-width:760px;margin:auto;padding:24px;font:18px/1.75 sans-serif}")
            append("img{max-width:100%;border-radius:12px}blockquote{border-left:4px solid #668;padding-left:12px}</style>")
            append("</head><body><h1>").append(escapeHtml(article.title.ifBlank { "未命名文章" })).append("</h1>")
            if (article.tags.isNotEmpty()) {
                append("<p>").append(escapeHtml(article.tags.joinToString(" ") { tag -> "#$tag" })).append("</p>")
            }
            article.blocks.forEach { block ->
                when (block.type) {
                    NotebookBlockType.TEXT -> {
                        val tag = when (block.textStyle) {
                            NotebookTextStyle.HEADING -> "h2"
                            NotebookTextStyle.QUOTE -> "blockquote"
                            NotebookTextStyle.BULLET,
                            NotebookTextStyle.NUMBERED -> "li"
                            NotebookTextStyle.PARAGRAPH -> "p"
                        }
                        append('<').append(tag).append('>')
                            .append(escapeHtml(block.text).replace("\n", "<br>"))
                            .append("</").append(tag).append('>')
                    }

                    NotebookBlockType.IMAGE -> append("<figure><img src=\"media/")
                        .append(escapeHtml(block.mediaFileName)).append("\" alt=\"")
                        .append(escapeHtml(block.mediaCaption)).append("\">")
                        .append("<figcaption>").append(escapeHtml(block.mediaCaption))
                        .append("</figcaption></figure>")

                    NotebookBlockType.LINK -> append("<p><a href=\"")
                        .append(escapeHtml(block.linkUrl)).append("\">")
                        .append(escapeHtml(block.linkTitle.ifBlank { block.linkUrl }))
                        .append("</a></p>")

                    NotebookBlockType.DIVIDER -> append("<hr>")
                }
            }
            append("</body></html>")
        }
    }

    /** @return 多个Uri组成的授权ClipData，供Android向目标App传递读取权限。 */
    private fun buildClipData(uris: List<Uri>): ClipData {
        return ClipData.newRawUri("notebook-media", uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }
    }

    /** @return 指定缓存文件对应的临时只读Content Uri。 */
    private fun uriFor(file: File): Uri {
        return FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.fileprovider",
            file
        )
    }

    /** @return 成功启动系统分享面板返回true。 */
    private fun startChooser(intent: Intent, chooserTitle: String): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to launch notebook share chooser", error)
        }.getOrDefault(false)
    }

    /** @return 适合作为导出文件名的短名称。 */
    private fun safeFileBase(rawTitle: String): String {
        return rawTitle.trim()
            .replace(UNSAFE_FILE_CHARACTER_REGEX, "_")
            .trim('_')
            .take(60)
            .ifBlank { "HarleyNote" }
    }

    /** @return 已转义HTML保留字符的文本。 */
    private fun escapeHtml(value: String): String {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /** 向当前ZIP输出一个UTF-8文本条目。 */
    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        const val TAG = "NotebookShare"
        const val SHARE_ROOT_DIRECTORY = "shared/notebook"
        const val ZIP_MIME_TYPE = "application/zip"
        val UNSAFE_FILE_CHARACTER_REGEX = Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]")
    }
}
