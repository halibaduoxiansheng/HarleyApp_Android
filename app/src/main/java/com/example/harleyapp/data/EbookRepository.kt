package com.example.harleyapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import android.util.AtomicFile
import android.util.Log
import android.webkit.URLUtil
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookFontFamily
import com.example.harleyapp.model.EbookFormat
import com.example.harleyapp.model.EbookImportProgress
import com.example.harleyapp.model.EbookImportResult
import com.example.harleyapp.model.EbookReadingBackground
import com.example.harleyapp.model.EbookReadingMode
import com.example.harleyapp.model.EbookWebDownloadRequest
import com.example.harleyapp.system.ChineseScriptConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * 用户设置或删除电子书自定义封面后的结果。
 *
 * @param success 封面文件与书库目录是否已经同时更新成功。
 * @param message 页面可直接放入反馈弹窗的中文信息。
 */
data class EbookCoverUpdateResult(
    val success: Boolean,
    val message: String
)

/**
 * 管理电子书原文件、离线解析文本、书库元数据和阅读进度。
 *
 * 使用方法：
 * 页面通过[importFromUri]导入系统选择器返回的文档，通过[getBooks]刷新书架，通过[updateMetadata]
 * 和[deleteBook]完成修改、删除；阅读页使用[readExtractedText]或[renderPdfPage]显示内容，并调用
 * [saveReadingProgress]保存页码、翻页方式和字号。
 *
 * @param context Android上下文，内部只保存Application Context。
 */
class EbookRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val rootDirectory = File(applicationContext.filesDir, ROOT_DIRECTORY)
    private val originalDirectory = File(rootDirectory, ORIGINAL_DIRECTORY)
    private val textDirectory = File(rootDirectory, TEXT_DIRECTORY)
    private val coverDirectory = File(rootDirectory, COVER_DIRECTORY)
    private val catalogFile = AtomicFile(File(rootDirectory, CATALOG_FILE_NAME))
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 返回书库当前全部书籍。
     *
     * @return 按最近阅读、最近修改和导入时间倒序排列；目录损坏时记录英文日志并返回空列表。
     */
    fun getBooks(): List<EbookBook> {
        return runCatching {
            ensureDirectories()
            if (!catalogFile.baseFile.isFile) return emptyList()
            val json = catalogFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    decodeBook(array.optJSONObject(index))?.let(::add)
                }
            }.sortedWith(
                compareByDescending<EbookBook> { book -> book.lastReadAtMillis }
                    .thenByDescending { book -> book.updatedAtMillis }
                    .thenByDescending { book -> book.createdAtMillis }
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to read ebook catalog", error)
        }.getOrDefault(emptyList())
    }

    /**
     * 把系统文件选择器返回的电子书复制到App私有目录并建立离线阅读索引。
     *
     * @param uri 当前具备读取权限的Content Uri。
     * @param onProgress 导入总进度回调，页面可据此显示真实字节、MOBI记录与字符扫描进度。
     * @return 完整导入结果；格式不支持、超过250MB、内容为空或解析失败时不会登记半成品。
     */
    suspend fun importFromUri(
        uri: Uri,
        onProgress: (EbookImportProgress) -> Unit = {}
    ): EbookImportResult = withContext(Dispatchers.IO) {
        runCatching {
            reportImportProgress(onProgress, 0.01f, "正在读取文件信息…")
            val displayName = queryDisplayName(uri)
            val declaredBytes = queryContentSize(uri)
            importEbook(displayName, onProgress) { temporary ->
                copyUriWithLimit(
                    uri = uri,
                    target = temporary,
                    declaredBytes = declaredBytes,
                    onProgress = { copiedBytes, totalBytes ->
                        val copyFraction = if (totalBytes > 0L) {
                            copiedBytes.toDouble() / totalBytes.toDouble()
                        } else {
                            copiedBytes.toDouble() / MAX_EBOOK_BYTES.toDouble()
                        }.coerceIn(0.0, 1.0)
                        reportImportProgress(
                            onProgress = onProgress,
                            overallFraction = COPY_PROGRESS_START +
                                (COPY_PROGRESS_END - COPY_PROGRESS_START) * copyFraction.toFloat(),
                            message = if (totalBytes > 0L) {
                                "正在复制文件：${formatImportBytes(copiedBytes)} / ${formatImportBytes(totalBytes)}"
                            } else {
                                "正在复制文件：已复制${formatImportBytes(copiedBytes)}"
                            },
                            completedUnits = copiedBytes,
                            totalUnits = totalBytes
                        )
                    }
                )
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to prepare ebook import", error)
            EbookImportResult(
                success = false,
                message = "无法读取所选文件，请检查文件权限后重试"
            )
        }
    }

    /**
     * 使用统一流程复制、解析并登记一本书，供文件选择器与网页直链下载共同复用。
     *
     * @param displayName 带有效扩展名的原始显示文件名。
     * @param onProgress 从复制到书库登记的总进度回调。
     * @param copyIntoTemporary 把来源流写入受控临时文件并返回字节数的函数。
     * @return 完整电子书导入结果。
     */
    private fun importEbook(
        displayName: String,
        onProgress: (EbookImportProgress) -> Unit = {},
        copyIntoTemporary: (File) -> Long
    ): EbookImportResult {
        var temporaryOriginal: File? = null
        var finalOriginal: File? = null
        var extractedText: File? = null
        return runCatching {
            ensureDirectories()
            val format = EbookFormat.fromFileName(displayName)
                ?: error("Unsupported ebook format")
            val id = UUID.randomUUID().toString()
            val extension = format.extensions.firstOrNull { value ->
                displayName.lowercase().endsWith(".$value")
            } ?: format.extensions.first()
            val storedFileName = "$id.$extension"
            val temporary = File(originalDirectory, "$storedFileName.tmp")
            temporaryOriginal = temporary
            val copiedBytes = copyIntoTemporary(temporary)
            require(copiedBytes > 0L) { "Empty ebook file" }
            reportImportProgress(
                onProgress,
                COPY_PROGRESS_END,
                "文件复制完成，正在校验格式…",
                copiedBytes,
                copiedBytes
            )
            val original = File(originalDirectory, storedFileName)
            require(temporary.renameTo(original)) { "Unable to finalize ebook file" }
            temporaryOriginal = null
            finalOriginal = original

            val extractedTextFileName: String
            val pageCount: Int
            if (format == EbookFormat.PDF) {
                reportImportProgress(onProgress, 0.88f, "正在读取PDF页数…")
                extractedTextFileName = ""
                pageCount = readPdfPageCount(original)
            } else {
                val content = extractText(original, format, onProgress).trim()
                require(content.isNotBlank()) { "No readable ebook text" }
                extractedTextFileName = "$id.txt"
                val textFile = File(textDirectory, extractedTextFileName)
                extractedText = textFile
                reportImportProgress(onProgress, 0.95f, "正在保存离线正文…")
                textFile.writeText(content, Charsets.UTF_8)
                pageCount = estimateTextPageCount(content)
            }

            val now = System.currentTimeMillis()
            val book = EbookBook(
                id = id,
                title = displayName.substringBeforeLast('.').trim().ifBlank { "未命名书籍" },
                author = "",
                format = format,
                originalFileName = displayName,
                storedFileName = storedFileName,
                extractedTextFileName = extractedTextFileName,
                fileSizeBytes = copiedBytes,
                createdAtMillis = now,
                updatedAtMillis = now,
                pageCount = pageCount.coerceAtLeast(1)
            )
            val updated = getBooks().filterNot { current -> current.id == id } + book
            reportImportProgress(onProgress, 0.99f, "正在登记到书库…")
            require(saveBooks(updated)) { "Unable to save ebook catalog" }
            reportImportProgress(onProgress, 1f, "导入完成")
            EbookImportResult(
                success = true,
                book = book,
                message = "《${book.title}》已导入，共${book.pageCount}页"
            )
        }.getOrElse { error ->
            temporaryOriginal?.delete()
            finalOriginal?.delete()
            extractedText?.delete()
            Log.e(TAG, "Failed to import ebook", error)
            EbookImportResult(
                success = false,
                message = when {
                    error.message == "Unsupported ebook format" -> {
                        "暂不支持该格式，可导入PDF、EPUB、MOBI、AZW、AZW3、TXT、Markdown、HTML、DOCX、FB2或RTF"
                    }
                    error.message == "Ebook file too large" -> "单本书不能超过250MB"
                    error.message == "No readable ebook text" -> {
                        "文件中没有解析到可阅读文字，可能带有加密或特殊排版"
                    }
                    error is MobiParseException -> mobiFailureMessage(error.failure)
                    else -> "书籍导入失败，请检查文件是否完整或存储空间是否充足"
                }
            )
        }
    }

    /**
     * 下载WebView提供的公开电子书直链并直接加入书库。
     *
     * 使用方法：
     * 网站页面捕获下载事件后传入[EbookWebDownloadRequest]。本函数不会复制WebView Cookie、Referer
     * 或登录凭据，只使用最终公开URL；需要会话验证、返回网页或压缩包的资源会安全拒绝。
     *
     * @param request 网页报告的下载URL、文件名响应头和MIME类型。
     * @return 下载、格式校验与导入均成功时返回书籍；失败时返回可直接展示的中文提示。
     */
    suspend fun importFromWebDownload(
        request: EbookWebDownloadRequest
    ): EbookImportResult = withContext(Dispatchers.IO) {
        val cacheDirectory = File(applicationContext.cacheDir, WEB_DOWNLOAD_CACHE_DIRECTORY)
        val downloadedFile = File(cacheDirectory, "${UUID.randomUUID()}.download")
        try {
            require(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
                "Unable to create web download cache"
            }
            val displayName = downloadWebFile(request, downloadedFile)
            importEbook(displayName) { temporary ->
                copyFileWithLimit(downloadedFile, temporary)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to import website ebook download", error)
            EbookImportResult(
                success = false,
                message = when (error.message) {
                    "Unsupported download URL" -> "下载地址不是可用的HTTP或HTTPS直链"
                    "Unsupported web ebook format" ->
                        "该下载不是可直接阅读的电子书。支持PDF、EPUB、MOBI、AZW、AZW3、TXT、Markdown、HTML、DOCX、FB2和RTF；压缩包请先解压后手动导入"
                    "Website returned HTML" -> "网站返回了验证页面而不是书籍，请先在网页完成真人验证后重新下载"
                    "Ebook file too large" -> "单本书不能超过250MB"
                    else -> "书籍下载或导入失败，请检查链接、验证状态和网络后重试"
                }
            )
        } finally {
            downloadedFile.delete()
        }
    }

    /**
     * 首次使用电子书功能时把随App发布的公版书一次性登记到全部书籍。
     *
     * 使用方法：
     * 页面或HarleyApp在后台协程中调用。方法使用稳定id跳过已经存在的内置书，并只在完整目录写入成功后
     * 保存播种标记；用户以后删除某本内置书，后续启动不会强行恢复。
     *
     * @return 已经完成过播种或本次全部写入成功返回true；资源复制或目录保存失败返回false。
     */
    suspend fun ensureStarterBooksInstalled(): Boolean = withContext(Dispatchers.IO) {
        if (preferences.getBoolean(STARTER_BOOKS_INSTALLED_KEY, false)) {
            return@withContext true
        }
        runCatching {
            ensureDirectories()
            val existingBooks = getBooks().toMutableList()
            val existingIds = existingBooks.mapTo(hashSetOf(), EbookBook::id)
            val now = System.currentTimeMillis()

            STARTER_BOOKS.forEachIndexed { index, starter ->
                val stableId = "$STARTER_BOOK_ID_PREFIX${starter.gutenbergId}"
                if (stableId !in existingIds) {
                    val textFileName = "$stableId.txt"
                    val textFile = File(textDirectory, textFileName)
                    applicationContext.assets.open("$STARTER_ASSET_DIRECTORY/${starter.assetFileName}")
                        .buffered()
                        .use { input ->
                            textFile.outputStream().buffered().use { output -> input.copyTo(output) }
                        }
                    require(textFile.isFile && textFile.length() > 0L) {
                        "Starter ebook asset is empty"
                    }
                    val characterCount = textFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        var totalCharacters = 0
                        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            totalCharacters += read
                        }
                        totalCharacters
                    }
                    existingBooks += EbookBook(
                        id = stableId,
                        title = starter.title,
                        author = starter.author,
                        format = EbookFormat.TEXT,
                        originalFileName = starter.assetFileName,
                        storedFileName = "",
                        extractedTextFileName = textFileName,
                        fileSizeBytes = textFile.length(),
                        createdAtMillis = now + index,
                        updatedAtMillis = now + index,
                        pageCount = estimateTextPageCountByLength(characterCount),
                        isOnShelf = index < DEFAULT_STARTER_SHELF_COUNT,
                        shelfOrder = now + index,
                        category = starter.category,
                        sourceUrl = "https://www.gutenberg.org/ebooks/${starter.gutenbergId}",
                        isBundled = true
                    )
                }
            }

            require(saveBooks(existingBooks)) { "Unable to save starter ebook catalog" }
            require(
                preferences.edit().putBoolean(STARTER_BOOKS_INSTALLED_KEY, true).commit()
            ) { "Unable to save starter ebook state" }
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to install starter ebooks", error)
        }.getOrDefault(false)
    }

    /**
     * 把一本书加入或移出用户书架；书本仍保留在“全部书籍”中。
     *
     * @param bookId 目标书籍id。
     * @param isOnShelf true表示加入书架，false表示移出书架。
     * @return 书籍存在且目录写入成功返回true。
     */
    fun setOnShelf(bookId: String, isOnShelf: Boolean): Boolean {
        val books = getBooks()
        val target = books.firstOrNull { book -> book.id == bookId } ?: return false
        if (target.isOnShelf == isOnShelf) return true
        val nextOrder = books.maxOfOrNull(EbookBook::shelfOrder)?.plus(1L)
            ?: System.currentTimeMillis()
        return saveBooks(
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        isOnShelf = isOnShelf,
                        shelfOrder = if (isOnShelf) nextOrder else book.shelfOrder,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                } else {
                    book
                }
            }
        )
    }

    /**
     * 按用户长按拖动后的顺序重新排列“我的书架”。
     *
     * 使用方法：
     * 书架完成一次拖动后传入当前全部书脊id。方法会去除重复和无效id，并把可能因界面刷新而
     * 未出现在参数中的在架书籍依次补到末尾；不在书架中的书不会被改变。
     *
     * @param orderedBookIds 拖动结束后的书架id顺序。
     * @return 顺序合法且目录保存成功返回true；没有在架书籍或写入失败返回false。
     */
    fun reorderShelfBooks(orderedBookIds: List<String>): Boolean {
        val books = getBooks()
        val shelfBooks = books.filter(EbookBook::isOnShelf).sortedBy(EbookBook::shelfOrder)
        if (shelfBooks.isEmpty()) return false
        val shelfIds = shelfBooks.mapTo(linkedSetOf(), EbookBook::id)
        val normalizedIds = buildList {
            orderedBookIds.forEach { id ->
                if (id in shelfIds && id !in this) add(id)
            }
            shelfBooks.forEach { book ->
                if (book.id !in this) add(book.id)
            }
        }
        if (normalizedIds == shelfBooks.map(EbookBook::id)) return true
        val orderById = normalizedIds.withIndex().associate { indexed ->
            indexed.value to indexed.index.toLong()
        }
        return saveBooks(
            books.map { book ->
                val order = orderById[book.id]
                if (book.isOnShelf && order != null) {
                    book.copy(shelfOrder = order)
                } else {
                    book
                }
            }
        )
    }

    /**
     * 修改书名、作者和书架书脊颜色。
     *
     * @param bookId 目标书籍id。
     * @param title 新书名，去除首尾空格后不能为空。
     * @param author 新作者名，允许为空。
     * @param spineColorArgb 不透明ARGB颜色；0表示恢复按书籍id自动配色。
     * @return 成功写入目录返回true；书籍不存在或书名为空返回false。
     */
    fun updateMetadata(
        bookId: String,
        title: String,
        author: String,
        spineColorArgb: Int
    ): Boolean {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return false
        val normalizedSpineColor = if (spineColorArgb == 0) {
            0
        } else {
            spineColorArgb or OPAQUE_ALPHA_MASK
        }
        val books = getBooks()
        if (books.none { book -> book.id == bookId }) return false
        val now = System.currentTimeMillis()
        return saveBooks(
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        title = normalizedTitle.take(MAX_TITLE_LENGTH),
                        author = author.trim().take(MAX_AUTHOR_LENGTH),
                        spineColorArgb = normalizedSpineColor,
                        updatedAtMillis = now
                    )
                } else {
                    book
                }
            }
        )
    }

    /**
     * 将用户选择的图片复制为指定书籍的自定义封面。
     *
     * 使用方法：
     * 页面通过系统图片选择器取得Uri后调用本函数。函数会限制图片大小、验证图片确实可以解码，
     * 先保存新文件并原子更新书库目录，全部成功后才删除旧封面，避免更换失败导致原封面丢失。
     *
     * @param bookId 需要设置封面的书籍id。
     * @param uri 系统文件选择器返回且当前可读取的图片Uri。
     * @return 成功时返回可直接提示用户的结果；书籍不存在、图片过大、格式损坏或空间不足时返回失败。
     */
    suspend fun updateCoverFromUri(bookId: String, uri: Uri): EbookCoverUpdateResult =
        withContext(Dispatchers.IO) {
            var temporary: File? = null
            var finalCover: File? = null
            runCatching {
                ensureDirectories()
                val books = getBooks()
                val targetBook = books.firstOrNull { book -> book.id == bookId }
                    ?: error("Book not found")
                val coverFileName = "cover_${UUID.randomUUID()}.img"
                val temporaryFile = File(coverDirectory, "$coverFileName.tmp")
                temporary = temporaryFile
                copyUriWithCustomLimit(
                    uri = uri,
                    target = temporaryFile,
                    maximumBytes = MAX_COVER_SOURCE_BYTES,
                    tooLargeMessage = "Cover image too large"
                )
                validateCoverImage(temporaryFile)

                val installedFile = File(coverDirectory, coverFileName)
                require(temporaryFile.renameTo(installedFile)) { "Unable to finalize cover image" }
                temporary = null
                finalCover = installedFile
                val saved = saveBooks(
                    books.map { book ->
                        if (book.id == bookId) {
                            book.copy(
                                coverFileName = coverFileName,
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        } else {
                            book
                        }
                    }
                )
                require(saved) { "Unable to save ebook catalog" }
                coverFileFor(targetBook)?.let { oldCover ->
                    if (oldCover != installedFile && !oldCover.delete()) {
                        Log.w(TAG, "Failed to delete replaced ebook cover")
                    }
                }
                finalCover = null
                EbookCoverUpdateResult(true, "《${targetBook.title}》的自定义封面已保存")
            }.getOrElse { error ->
                temporary?.delete()
                finalCover?.delete()
                Log.e(TAG, "Failed to update ebook cover", error)
                EbookCoverUpdateResult(
                    success = false,
                    message = when (error.message) {
                        "Cover image too large" -> "封面图片不能超过15MB"
                        "Invalid cover image" -> "选择的文件不是可识别的图片，或图片已经损坏"
                        "Cover dimensions too large" -> "图片尺寸过大，请选择边长不超过12000像素的图片"
                        "Book not found" -> "没有找到需要设置封面的书籍"
                        else -> "封面保存失败，请检查图片文件或手机存储空间"
                    }
                )
            }
        }

    /**
     * 删除一本书的自定义封面并恢复默认书脊封面。
     *
     * @param bookId 目标书籍id。
     * @return 目录更新及封面清理结果，页面可直接使用其中消息反馈用户。
     */
    fun removeCustomCover(bookId: String): EbookCoverUpdateResult {
        val books = getBooks()
        val targetBook = books.firstOrNull { book -> book.id == bookId }
            ?: return EbookCoverUpdateResult(false, "没有找到需要修改封面的书籍")
        if (targetBook.coverFileName.isBlank()) {
            return EbookCoverUpdateResult(true, "《${targetBook.title}》当前使用的就是默认封面")
        }
        val coverFile = coverFileFor(targetBook)
        val saved = saveBooks(
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        coverFileName = "",
                        updatedAtMillis = System.currentTimeMillis()
                    )
                } else {
                    book
                }
            }
        )
        if (!saved) return EbookCoverUpdateResult(false, "恢复默认封面失败")
        if (coverFile != null && !coverFile.delete()) {
            Log.w(TAG, "Failed to delete removed ebook cover")
        }
        return EbookCoverUpdateResult(true, "《${targetBook.title}》已恢复默认封面")
    }

    /**
     * 保存阅读页码、翻页模式、字号、字体和阅读背景。
     *
     * @param bookId 目标书籍id。
     * @param currentPage 当前零基页码。
     * @param currentTextOffset 文本书籍当前页在完整正文中的字符起点；PDF传入0。
     * @param pageCount 阅读器实际分页总数。
     * @param readingMode 当前翻页模式。
     * @param fontScale 当前文本字号倍率。
     * @param fontFamily 当前文本字体族。
     * @param readingBackground 当前阅读背景。
     * @return 书籍存在且目录写入成功返回true。
     */
    fun saveReadingProgress(
        bookId: String,
        currentPage: Int,
        currentTextOffset: Int,
        pageCount: Int,
        readingMode: EbookReadingMode,
        fontScale: Float,
        fontFamily: EbookFontFamily,
        readingBackground: EbookReadingBackground
    ): Boolean {
        val books = getBooks()
        if (books.none { book -> book.id == bookId }) return false
        val safePageCount = pageCount.coerceAtLeast(1)
        return saveBooks(
            books.map { book ->
                if (book.id == bookId) {
                    book.copy(
                        currentPage = currentPage.coerceIn(0, safePageCount - 1),
                        currentTextOffset = currentTextOffset.coerceAtLeast(0),
                        pageCount = safePageCount,
                        readingMode = readingMode,
                        fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
                        fontFamily = fontFamily,
                        readingBackground = readingBackground,
                        lastReadAtMillis = System.currentTimeMillis()
                    )
                } else {
                    book
                }
            }
        )
    }

    /**
     * 从书库及App私有目录永久删除一本书。
     *
     * @param bookId 目标书籍id。
     * @return 目录、原文件、解析文本和自定义封面均处理成功返回true；书籍不存在返回false。
     */
    fun deleteBook(bookId: String): Boolean {
        val books = getBooks()
        val target = books.firstOrNull { book -> book.id == bookId } ?: return false
        val catalogSaved = saveBooks(books.filterNot { book -> book.id == bookId })
        if (!catalogSaved) return false
        val originalDeleted = originalFileFor(target)?.delete() ?: true
        val textDeleted = extractedTextFileFor(target)?.delete() ?: true
        val coverDeleted = coverFileFor(target)?.delete() ?: true
        if (!originalDeleted || !textDeleted || !coverDeleted) {
            Log.w(TAG, "Failed to delete one or more ebook files")
        }
        return originalDeleted && textDeleted && coverDeleted
    }

    /**
     * 读取非PDF书籍的离线解析文本。
     *
     * @param book 目标书籍。
     * @return 文本文件存在且大小合法时返回完整内容，否则返回空字符串。
     */
    suspend fun readExtractedText(book: EbookBook): String = withContext(Dispatchers.IO) {
        runCatching {
            val file = extractedTextFileFor(book) ?: return@runCatching ""
            require(file.length() <= MAX_EXTRACTED_TEXT_BYTES) { "Extracted text too large" }
            val text = file.readText(Charsets.UTF_8)
            if (book.isBundled && !book.category.contains("英文")) {
                ChineseScriptConverter.toSimplified(text)
            } else {
                text
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to read extracted ebook text", error)
        }.getOrDefault("")
    }

    /**
     * 把PDF指定页面渲染为适合屏幕显示的位图。
     *
     * @param book PDF书籍模型。
     * @param pageIndex 零基页码。
     * @param targetWidthPixels 期望宽度像素，内部限制在480至1600避免过大内存。
     * @return 成功返回ARGB位图，格式错误、页码越界或文件损坏时返回null。
     */
    suspend fun renderPdfPage(
        book: EbookBook,
        pageIndex: Int,
        targetWidthPixels: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            require(book.format == EbookFormat.PDF) { "Not a PDF book" }
            val file = originalFileFor(book) ?: error("Missing PDF file")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "PDF page out of bounds" }
                    renderer.openPage(pageIndex).use { page ->
                        val width = targetWidthPixels.coerceIn(MIN_PDF_WIDTH, MAX_PDF_WIDTH)
                        val height = (width * page.height.toFloat() / page.width.toFloat())
                            .roundToInt()
                            .coerceIn(MIN_PDF_HEIGHT, MAX_PDF_HEIGHT)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to render PDF page", error)
        }.getOrNull()
    }

    /** @return 模型引用的受控原文件，文件不存在时返回null。 */
    fun originalFileFor(book: EbookBook): File? {
        if (!isSafeStoredFileName(book.storedFileName)) return null
        return File(originalDirectory, book.storedFileName).takeIf(File::isFile)
    }

    /**
     * 读取书籍自定义封面并按列表显示尺寸安全缩小。
     *
     * 使用方法：
     * 书架或全部书籍卡片以[book.coverFileName]为remember键调用本函数；没有自定义封面、文件缺失
     * 或图片损坏时返回null，由页面绘制默认书脊封面。
     *
     * @param book 目标书籍。
     * @return 适合Compose卡片显示的采样Bitmap，无法读取时返回null。
     */
    fun loadCoverBitmap(book: EbookBook): Bitmap? {
        val file = coverFileFor(book) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid cover image" }
            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > MAX_COVER_DECODE_WIDTH * 2 ||
                bounds.outHeight / sampleSize > MAX_COVER_DECODE_HEIGHT * 2
            ) {
                sampleSize *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to decode ebook cover", error)
        }.getOrNull()
    }

    /** @return 模型引用的受控自定义封面，未设置、文件名非法或文件缺失时返回null。 */
    private fun coverFileFor(book: EbookBook): File? {
        if (!isSafeStoredFileName(book.coverFileName)) return null
        return File(coverDirectory, book.coverFileName).takeIf(File::isFile)
    }

    /** @return 模型引用的受控解析文本，文件不存在或文件名为空时返回null。 */
    private fun extractedTextFileFor(book: EbookBook): File? {
        if (!isSafeStoredFileName(book.extractedTextFileName)) return null
        return File(textDirectory, book.extractedTextFileName).takeIf(File::isFile)
    }

    /** @return Content Uri可读文件名，无法查询时返回稳定默认名。 */
    private fun queryDisplayName(uri: Uri): String {
        val queriedName = applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return queriedName?.trim()?.takeIf(String::isNotBlank) ?: "imported_book"
    }

    /**
     * 查询系统文档提供者声明的文件总字节数。
     *
     * @param uri 系统文件选择器返回的文档Uri。
     * @return 正数大小；提供者未返回、查询失败或数值异常时返回0。
     */
    private fun queryContentSize(uri: Uri): Long {
        return runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }?.coerceIn(0L, MAX_EBOOK_BYTES) ?: 0L
        }.onFailure { error ->
            Log.w(TAG, "Failed to query ebook content size", error)
        }.getOrDefault(0L)
    }

    /**
     * 把Uri流式复制到临时文件，同时执行单文件上限校验并报告真实复制字节数。
     *
     * @param uri 系统文件选择器返回的文档Uri。
     * @param target App私有目录中的受控临时文件。
     * @param declaredBytes 文档提供者声明的总大小，未知时为0。
     * @param onProgress 已复制字节数与总字节数回调；总大小未知时第二个参数为0。
     * @return 实际复制的总字节数。
     */
    private fun copyUriWithLimit(
        uri: Uri,
        target: File,
        declaredBytes: Long,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): Long {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open ebook Uri")
        var total = 0L
        var lastReportedBytes = 0L
        onProgress(0L, declaredBytes)
        input.buffered().use { source ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_EBOOK_BYTES) error("Ebook file too large")
                    output.write(buffer, 0, read)
                    if (total - lastReportedBytes >= COPY_PROGRESS_REPORT_BYTES) {
                        onProgress(total, declaredBytes)
                        lastReportedBytes = total
                    }
                }
            }
        }
        onProgress(total, declaredBytes.takeIf { size -> size > 0L } ?: total)
        return total
    }

    /**
     * 按调用方指定上限复制Content Uri，供封面等非书籍文件复用。
     *
     * @param uri 当前可读取的来源Uri。
     * @param target App私有目录中的临时目标文件。
     * @param maximumBytes 最大允许字节数。
     * @param tooLargeMessage 超限时抛出的稳定英文错误文本。
     * @return 实际复制字节数，空文件会直接失败。
     */
    private fun copyUriWithCustomLimit(
        uri: Uri,
        target: File,
        maximumBytes: Long,
        tooLargeMessage: String
    ): Long {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open content Uri")
        var total = 0L
        input.buffered().use { source ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maximumBytes) error(tooLargeMessage)
                    output.write(buffer, 0, read)
                }
            }
        }
        require(total > 0L) { "Empty content file" }
        return total
    }

    /** 验证临时封面是尺寸安全且Android可识别的图片。 */
    private fun validateCoverImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid cover image" }
        require(
            bounds.outWidth <= MAX_COVER_SOURCE_EDGE &&
                bounds.outHeight <= MAX_COVER_SOURCE_EDGE
        ) { "Cover dimensions too large" }
    }

    /**
     * 把网页公开直链下载到App缓存，并返回可用于格式识别的安全文件名。
     *
     * @param request WebView报告的公开下载信息，不包含Cookie或登录凭据。
     * @param target App缓存目录中的临时目标文件。
     * @return 根据响应文件名、URL与MIME类型推断出的电子书文件名。
     */
    private fun downloadWebFile(request: EbookWebDownloadRequest, target: File): String {
        val sourceUri = Uri.parse(request.url)
        require(sourceUri.scheme.equals("http", ignoreCase = true) ||
            sourceUri.scheme.equals("https", ignoreCase = true)
        ) { "Unsupported download URL" }

        val connection = (URL(request.url).openConnection() as? HttpURLConnection)
            ?: error("Unsupported download URL")
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = WEB_DOWNLOAD_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = WEB_DOWNLOAD_READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()

            require(connection.responseCode in 200..299) {
                "HTTP download failed"
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_EBOOK_BYTES) {
                error("Ebook file too large")
            }

            val responseDisposition = connection.getHeaderField("Content-Disposition").orEmpty()
            val responseMimeType = connection.contentType.orEmpty().substringBefore(';').trim()
            val explicitCandidate = URLUtil.guessFileName(
                request.url,
                responseDisposition.ifBlank { request.contentDisposition },
                responseMimeType.ifBlank { request.mimeType }
            )
            val explicitFormat = EbookFormat.fromFileName(explicitCandidate)
            if (
                responseMimeType.equals("text/html", ignoreCase = true) &&
                explicitFormat != EbookFormat.HTML &&
                EbookFormat.fromFileName(sourceUri.lastPathSegment.orEmpty()) == null
            ) {
                error("Website returned HTML")
            }

            val displayName = resolveWebDownloadFileName(
                candidate = explicitCandidate,
                mimeType = responseMimeType.ifBlank { request.mimeType }
            )
            require(EbookFormat.fromFileName(displayName) != null) {
                "Unsupported web ebook format"
            }

            var total = 0L
            connection.inputStream.buffered().use { source ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_EBOOK_BYTES) error("Ebook file too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total > 0L) { "Empty ebook file" }
            displayName
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 为没有扩展名的下载链接按MIME类型补充受支持扩展名。
     *
     * @param candidate Android根据URL和响应头推断的原始文件名。
     * @param mimeType 服务器返回的MIME类型。
     * @return 不含目录字符且最长180字符的文件名；无法识别时保留原名供上层拒绝。
     */
    private fun resolveWebDownloadFileName(candidate: String, mimeType: String): String {
        val safeCandidate = candidate
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n]"), "")
            .take(MAX_WEB_DOWNLOAD_DISPLAY_NAME_LENGTH)
            .ifBlank { "downloaded_book" }
        if (EbookFormat.fromFileName(safeCandidate) != null) return safeCandidate

        val extension = when (mimeType.lowercase().substringBefore(';').trim()) {
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "text/plain" -> "txt"
            "text/markdown" -> "md"
            "text/html", "application/xhtml+xml" -> "html"
            "application/rtf", "text/rtf" -> "rtf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/x-mobipocket-ebook", "application/vnd.amazon.ebook" -> "mobi"
            else -> null
        }
        return if (extension == null) safeCandidate else "$safeCandidate.$extension"
    }

    /** 把已下载临时文件复制到书库临时文件，并再次执行大小上限校验。 */
    private fun copyFileWithLimit(source: File, target: File): Long {
        if (source.length() > MAX_EBOOK_BYTES) error("Ebook file too large")
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return target.length().also { size ->
            if (size > MAX_EBOOK_BYTES) error("Ebook file too large")
        }
    }

    /**
     * 从指定格式原文件解析纯文本，并把耗时阶段映射到稳定递增的总进度。
     *
     * @param file 已复制到书库私有目录的原文件。
     * @param format 已根据扩展名识别出的书籍格式。
     * @param onProgress 导入页面进度回调。
     * @return 可直接保存并分页的纯文本。
     */
    private fun extractText(
        file: File,
        format: EbookFormat,
        onProgress: (EbookImportProgress) -> Unit
    ): String {
        if (format == EbookFormat.MOBI) {
            reportImportProgress(onProgress, MOBI_EXTRACT_PROGRESS_START, "正在读取MOBI正文记录…")
            val mobiHtml = MobiTextExtractor.extract(file) { completedRecords, totalRecords ->
                val recordFraction = completedRecords.toFloat() / totalRecords.coerceAtLeast(1)
                reportImportProgress(
                    onProgress = onProgress,
                    overallFraction = MOBI_EXTRACT_PROGRESS_START +
                        (MOBI_EXTRACT_PROGRESS_END - MOBI_EXTRACT_PROGRESS_START) * recordFraction,
                    message = "正在解压MOBI正文：$completedRecords / $totalRecords 条记录",
                    completedUnits = completedRecords.toLong(),
                    totalUnits = totalRecords.toLong()
                )
            }
            reportImportProgress(onProgress, MOBI_CLEAN_PROGRESS_START, "正在整理MOBI排版…")
            return EbookHtmlTextExtractor.extract(mobiHtml) { completedCharacters, totalCharacters ->
                val characterFraction = completedCharacters.toFloat() / totalCharacters.coerceAtLeast(1)
                reportImportProgress(
                    onProgress = onProgress,
                    overallFraction = MOBI_CLEAN_PROGRESS_START +
                        (MOBI_CLEAN_PROGRESS_END - MOBI_CLEAN_PROGRESS_START) * characterFraction,
                    message = "正在整理正文：${formatImportCharacters(completedCharacters)} / " +
                        formatImportCharacters(totalCharacters),
                    completedUnits = completedCharacters.toLong(),
                    totalUnits = totalCharacters.toLong()
                )
            }
        }

        reportImportProgress(onProgress, 0.35f, "正在解析${format.displayName}正文…")
        val extracted = when (format) {
            EbookFormat.TEXT,
            EbookFormat.MARKDOWN -> decodeTextBytes(file.readBytes())
            EbookFormat.HTML -> htmlToText(decodeTextBytes(file.readBytes()))
            EbookFormat.DOCX -> extractDocx(file)
            EbookFormat.EPUB -> extractEpub(file)
            EbookFormat.FB2 -> htmlToText(decodeTextBytes(file.readBytes()))
            EbookFormat.RTF -> extractRtf(decodeTextBytes(file.readBytes()))
            EbookFormat.MOBI -> error("MOBI uses progress-aware parser")
            EbookFormat.PDF -> error("PDF uses native page renderer")
        }
        reportImportProgress(onProgress, 0.90f, "正在规范正文排版…")
        return extracted.replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{4,}"), "\n\n\n")
    }

    /** @return UTF-8或常见中文GB18030解码后的文本。 */
    private fun decodeTextBytes(bytes: ByteArray): String {
        require(bytes.size.toLong() <= MAX_EXTRACTED_TEXT_BYTES) { "Source text too large" }
        val utf8 = bytes.toString(Charsets.UTF_8)
        val replacementCount = utf8.count { character -> character == '\uFFFD' }
        return if (replacementCount > (utf8.length / MAX_REPLACEMENT_DIVISOR).coerceAtLeast(3)) {
            bytes.toString(Charset.forName("GB18030"))
        } else {
            utf8
        }
    }

    /** @return HTML或XML正文转换后的可阅读纯文本。 */
    private fun htmlToText(source: String): String {
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    /** @return DOCX中word/document.xml的文字，段落和换行保持可读。 */
    private fun extractDocx(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("Missing DOCX document.xml")
            val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return htmlToText(
                xml.replace("</w:p>", "</p>")
                    .replace("<w:tab/>", "\t")
                    .replace("<w:br/>", "<br>")
            )
        }
    }

    /** @return EPUB中全部HTML/XHTML章节按压缩包顺序合并后的纯文本。 */
    private fun extractEpub(file: File): String {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.substringAfterLast('.', "")
                        .lowercase() in setOf("html", "htm", "xhtml")
                }
                .sortedBy { entry -> entry.name }
                .toList()
            require(entries.isNotEmpty()) { "No readable EPUB chapters" }
            return buildString {
                entries.forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { input -> readLimitedEntry(input) }
                    append(htmlToText(decodeTextBytes(bytes))).append("\n\n")
                }
            }
        }
    }

    /** @return 去除常用控制字和转义符后的RTF纯文本。 */
    private fun extractRtf(source: String): String {
        return source
            .replace(Regex("\\\\'([0-9a-fA-F]{2})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            .replace(Regex("\\\\par[d]?\\s?"), "\n")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), "")
            .replace("\\{", "{")
            .replace("\\}", "}")
            .replace(Regex("[{}]"), "")
    }

    /** @return 一个压缩包条目的有限字节，避免异常EPUB解压占用过多内存。 */
    private fun readLimitedEntry(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        input.buffered().use { source ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_SINGLE_CHAPTER_BYTES) { "EPUB chapter too large" }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    /** @return PDF有效页数，空PDF或损坏文件会抛出异常阻止导入。 */
    private fun readPdfPageCount(file: File): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                return renderer.pageCount.coerceAtLeast(1)
            }
        }
    }

    /** @return 按固定可读字符数估算的初始页数。 */
    private fun estimateTextPageCount(text: String): Int {
        return estimateTextPageCountByLength(text.length)
    }

    /** @return 根据字符数计算的初始分页总数。 */
    private fun estimateTextPageCountByLength(characterCount: Int): Int {
        return ((characterCount + DEFAULT_PAGE_CHARACTERS - 1) / DEFAULT_PAGE_CHARACTERS)
            .coerceAtLeast(1)
    }

    /** 把完整目录原子写入catalog.json。 */
    private fun saveBooks(books: List<EbookBook>): Boolean {
        return runCatching {
            ensureDirectories()
            val output = catalogFile.startWrite()
            try {
                val writer = output.writer(Charsets.UTF_8)
                val array = JSONArray()
                books.forEach { book -> array.put(encodeBook(book)) }
                writer.write(array.toString())
                writer.flush()
                catalogFile.finishWrite(output)
            } catch (error: Throwable) {
                catalogFile.failWrite(output)
                throw error
            }
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to save ebook catalog", error)
        }.getOrDefault(false)
    }

    /** @return 书籍模型对应的JSON对象。 */
    private fun encodeBook(book: EbookBook): JSONObject {
        return JSONObject().apply {
            put("id", book.id)
            put("title", book.title)
            put("author", book.author)
            put("format", book.format.name)
            put("originalFileName", book.originalFileName)
            put("storedFileName", book.storedFileName)
            put("extractedTextFileName", book.extractedTextFileName)
            put("fileSizeBytes", book.fileSizeBytes)
            put("createdAtMillis", book.createdAtMillis)
            put("updatedAtMillis", book.updatedAtMillis)
            put("lastReadAtMillis", book.lastReadAtMillis)
            put("currentPage", book.currentPage)
            put("currentTextOffset", book.currentTextOffset)
            put("pageCount", book.pageCount)
            put("readingMode", book.readingMode.name)
            put("fontScale", book.fontScale.toDouble())
            put("fontFamily", book.fontFamily.name)
            put("readingBackground", book.readingBackground.name)
            put("isOnShelf", book.isOnShelf)
            put("shelfOrder", book.shelfOrder)
            put("spineColorArgb", book.spineColorArgb.toLong())
            put("coverFileName", book.coverFileName)
            put("category", book.category)
            put("sourceUrl", book.sourceUrl)
            put("isBundled", book.isBundled)
        }
    }

    /** @return JSON字段有效时恢复书籍模型，否则返回null并忽略损坏项。 */
    private fun decodeBook(json: JSONObject?): EbookBook? {
        if (json == null) return null
        return runCatching {
            val format = EbookFormat.valueOf(json.getString("format"))
            val readingMode = runCatching {
                EbookReadingMode.valueOf(json.optString("readingMode"))
            }.getOrDefault(EbookReadingMode.HORIZONTAL)
            val readingBackground = runCatching {
                EbookReadingBackground.valueOf(json.optString("readingBackground"))
            }.getOrDefault(EbookReadingBackground.PAPER)
            val fontFamily = runCatching {
                EbookFontFamily.valueOf(json.optString("fontFamily"))
            }.getOrDefault(EbookFontFamily.SERIF)
            val createdAtMillis = json.optLong("createdAtMillis")
            val currentPage = json.optInt("currentPage").coerceAtLeast(0)
            val legacyTextOffset = (currentPage.toLong() * LEGACY_READER_PAGE_CHARACTERS)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            EbookBook(
                id = json.getString("id"),
                title = json.optString("title").ifBlank { "未命名书籍" },
                author = json.optString("author"),
                format = format,
                originalFileName = json.optString("originalFileName"),
                storedFileName = json.getString("storedFileName"),
                extractedTextFileName = json.optString("extractedTextFileName"),
                fileSizeBytes = json.optLong("fileSizeBytes").coerceAtLeast(0L),
                createdAtMillis = createdAtMillis,
                updatedAtMillis = json.optLong("updatedAtMillis"),
                lastReadAtMillis = json.optLong("lastReadAtMillis"),
                currentPage = currentPage,
                currentTextOffset = json.optInt("currentTextOffset", legacyTextOffset)
                    .coerceAtLeast(0),
                pageCount = json.optInt("pageCount", 1).coerceAtLeast(1),
                readingMode = readingMode,
                fontScale = json.optDouble("fontScale", 1.0).toFloat()
                    .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
                fontFamily = fontFamily,
                readingBackground = readingBackground,
                isOnShelf = json.optBoolean("isOnShelf", true),
                shelfOrder = json.optLong("shelfOrder", createdAtMillis),
                spineColorArgb = json.optLong("spineColorArgb", 0L).toInt(),
                coverFileName = json.optString("coverFileName")
                    .takeIf(::isSafeStoredFileName)
                    .orEmpty(),
                category = json.optString("category", "导入书籍"),
                sourceUrl = json.optString("sourceUrl"),
                isBundled = json.optBoolean("isBundled", false)
            )
        }.onFailure { error ->
            Log.w(TAG, "Skipped invalid ebook catalog item", error)
        }.getOrNull()
    }

    /** 确保电子书根目录、原文件目录和解析文本目录存在。 */
    private fun ensureDirectories() {
        require(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "Unable to create ebook root directory"
        }
        require(originalDirectory.exists() || originalDirectory.mkdirs()) {
            "Unable to create ebook original directory"
        }
        require(textDirectory.exists() || textDirectory.mkdirs()) {
            "Unable to create ebook text directory"
        }
        require(coverDirectory.exists() || coverDirectory.mkdirs()) {
            "Unable to create ebook cover directory"
        }
    }

    /**
     * 安全发布一次电子书总进度，页面回调自身异常不会中断文件导入。
     *
     * @param onProgress 页面提供的进度接收器。
     * @param overallFraction 当前总进度，超出0到1时会自动限制。
     * @param message 当前阶段的中文说明。
     * @param completedUnits 当前阶段已处理单位数。
     * @param totalUnits 当前阶段总单位数，未知时为0。
     * @return 无返回值。
     */
    private fun reportImportProgress(
        onProgress: (EbookImportProgress) -> Unit,
        overallFraction: Float,
        message: String,
        completedUnits: Long = 0L,
        totalUnits: Long = 0L
    ) {
        runCatching {
            onProgress(
                EbookImportProgress(
                    overallFraction = overallFraction.coerceIn(0f, 1f),
                    message = message,
                    completedUnits = completedUnits.coerceAtLeast(0L),
                    totalUnits = totalUnits.coerceAtLeast(0L)
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Ebook import progress callback failed", error)
        }
    }

    /**
     * 把导入字节数格式化为简短可读值。
     *
     * @param bytes 原始字节数。
     * @return 以B、KB或MB表示的英文单位字符串。
     */
    private fun formatImportBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        return when {
            safeBytes >= 1024L * 1024L -> String.format(
                Locale.CHINA,
                "%.1f MB",
                safeBytes / (1024.0 * 1024.0)
            )
            safeBytes >= 1024L -> String.format(Locale.CHINA, "%.1f KB", safeBytes / 1024.0)
            else -> "$safeBytes B"
        }
    }

    /**
     * 把大正文字符数量格式化为便于在手机上查看的中文数值。
     *
     * @param characters 字符数量。
     * @return 小于一万时返回整数，大于等于一万时返回保留一位小数的“万字符”。
     */
    private fun formatImportCharacters(characters: Int): String {
        val safeCharacters = characters.coerceAtLeast(0)
        return if (safeCharacters >= 10_000) {
            String.format(Locale.CHINA, "%.1f万字符", safeCharacters / 10_000.0)
        } else {
            "${safeCharacters}字符"
        }
    }

    /** @return MOBI解析失败分类对应的明确中文导入反馈。 */
    private fun mobiFailureMessage(failure: MobiParseFailure): String {
        return when (failure) {
            MobiParseFailure.DRM_PROTECTED -> {
                "这本Kindle书受DRM保护，App不能绕过加密；请导入无DRM的MOBI、AZW或AZW3文件"
            }
            MobiParseFailure.UNSUPPORTED_COMPRESSION -> {
                "这本Kindle书使用了暂不支持的HUFF/CDIC压缩，可先转换为EPUB后再导入"
            }
            MobiParseFailure.UNSUPPORTED_ENCODING -> {
                "这本Kindle书使用了暂不支持的文字编码，可先转换为UTF-8 EPUB后再导入"
            }
            MobiParseFailure.TEXT_TOO_LARGE -> "这本Kindle书解压后的正文过大，已停止导入以保护手机内存"
            MobiParseFailure.INVALID_FILE -> "MOBI/Kindle文件结构损坏或正文不完整，无法导入"
        }
    }

    /** @return 文件名不含目录分隔符且只包含安全字符时返回true。 */
    private fun isSafeStoredFileName(fileName: String): Boolean {
        return fileName.isNotBlank() &&
            fileName.length <= MAX_STORED_FILE_NAME_LENGTH &&
            fileName.matches(SAFE_FILE_NAME_REGEX)
    }

    private companion object {
        const val TAG = "EbookRepository"
        const val ROOT_DIRECTORY = "harley_ebooks"
        const val ORIGINAL_DIRECTORY = "original"
        const val TEXT_DIRECTORY = "text"
        const val COVER_DIRECTORY = "covers"
        const val WEB_DOWNLOAD_CACHE_DIRECTORY = "ebook_web_downloads"
        const val CATALOG_FILE_NAME = "catalog.json"
        const val PREFERENCES_NAME = "harley_ebook_preferences"
        const val STARTER_BOOKS_INSTALLED_KEY = "starter_books_v1_installed"
        const val STARTER_ASSET_DIRECTORY = "starter_ebooks"
        const val STARTER_BOOK_ID_PREFIX = "starter_gutenberg_"
        const val DEFAULT_STARTER_SHELF_COUNT = 6
        const val MAX_EBOOK_BYTES = 250L * 1024L * 1024L
        const val MAX_EXTRACTED_TEXT_BYTES = 80L * 1024L * 1024L
        const val COPY_PROGRESS_REPORT_BYTES = 256L * 1024L
        const val COPY_PROGRESS_START = 0.02f
        const val COPY_PROGRESS_END = 0.25f
        const val MOBI_EXTRACT_PROGRESS_START = 0.28f
        const val MOBI_EXTRACT_PROGRESS_END = 0.70f
        const val MOBI_CLEAN_PROGRESS_START = 0.70f
        const val MOBI_CLEAN_PROGRESS_END = 0.94f
        const val MAX_COVER_SOURCE_BYTES = 15L * 1024L * 1024L
        const val MAX_COVER_SOURCE_EDGE = 12_000
        const val MAX_COVER_DECODE_WIDTH = 800
        const val MAX_COVER_DECODE_HEIGHT = 1_200
        const val MAX_SINGLE_CHAPTER_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_PAGE_CHARACTERS = 1_300
        const val LEGACY_READER_PAGE_CHARACTERS = 1_050L
        const val MAX_REPLACEMENT_DIVISOR = 100
        const val MAX_TITLE_LENGTH = 120
        const val MAX_AUTHOR_LENGTH = 80
        const val MAX_STORED_FILE_NAME_LENGTH = 120
        const val MAX_WEB_DOWNLOAD_DISPLAY_NAME_LENGTH = 180
        const val WEB_DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 20_000
        const val WEB_DOWNLOAD_READ_TIMEOUT_MILLIS = 60_000
        const val MIN_PDF_WIDTH = 480
        const val MAX_PDF_WIDTH = 1_600
        const val MIN_PDF_HEIGHT = 640
        const val MAX_PDF_HEIGHT = 4_096
        const val MIN_FONT_SCALE = 0.75f
        const val MAX_FONT_SCALE = 1.8f
        const val OPAQUE_ALPHA_MASK: Int = -0x1000000
        val SAFE_FILE_NAME_REGEX = Regex("[a-zA-Z0-9._-]+")

        val STARTER_BOOKS = listOf(
            StarterEbook("24264", "dream_of_red_chamber.txt", "红楼梦", "曹雪芹", "中国文学"),
            StarterEbook("23950", "romance_of_three_kingdoms.txt", "三国演义", "罗贯中", "中国文学"),
            StarterEbook("23962", "journey_to_the_west.txt", "西游记", "吴承恩", "中国文学"),
            StarterEbook("23863", "water_margin.txt", "水浒传", "施耐庵", "中国文学"),
            StarterEbook("23864", "art_of_war_zh.txt", "孙子兵法", "孙武", "谋略"),
            StarterEbook("23839", "analects_zh.txt", "论语", "孔子及弟子", "哲学"),
            StarterEbook("7337", "dao_de_jing_zh.txt", "道德经", "老子", "哲学"),
            StarterEbook("52323", "three_hundred_tang_poems.txt", "唐诗三百首", "蘅塘退士 编", "诗歌"),
            StarterEbook("51828", "strange_tales_zh.txt", "聊斋志异", "蒲松龄", "志怪文学"),
            StarterEbook("11", "alice_in_wonderland.txt", "Alice's Adventures in Wonderland", "Lewis Carroll", "英文文学"),
            StarterEbook("1661", "sherlock_holmes.txt", "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", "英文侦探"),
            StarterEbook("1342", "pride_and_prejudice.txt", "Pride and Prejudice", "Jane Austen", "英文文学"),
            StarterEbook("2680", "meditations.txt", "Meditations", "Marcus Aurelius", "哲学·英文"),
            StarterEbook("1228", "origin_of_species.txt", "On the Origin of Species", "Charles Darwin", "自然科学·英文")
        )
    }
}

/**
 * 一册随App发布的Project Gutenberg公版书元数据。
 *
 * @param gutenbergId Project Gutenberg稳定电子书编号。
 * @param assetFileName assets/starter_ebooks中的UTF-8原文文件名。
 * @param title 书架显示标题。
 * @param author 作者或编者。
 * @param category 简短类别。
 */
private data class StarterEbook(
    val gutenbergId: String,
    val assetFileName: String,
    val title: String,
    val author: String,
    val category: String
)
