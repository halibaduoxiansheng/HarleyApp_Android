package com.example.harleyapp.data

import android.content.Context
import android.graphics.Bitmap
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
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.roundToInt

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
     * @return 完整导入结果；格式不支持、超过250MB、内容为空或解析失败时不会登记半成品。
     */
    suspend fun importFromUri(uri: Uri): EbookImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        importEbook(displayName) { temporary ->
            copyUriWithLimit(uri, temporary)
        }
    }

    /**
     * 使用统一流程复制、解析并登记一本书，供文件选择器与网页直链下载共同复用。
     *
     * @param displayName 带有效扩展名的原始显示文件名。
     * @param copyIntoTemporary 把来源流写入受控临时文件并返回字节数的函数。
     * @return 完整电子书导入结果。
     */
    private fun importEbook(
        displayName: String,
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
            val original = File(originalDirectory, storedFileName)
            require(temporary.renameTo(original)) { "Unable to finalize ebook file" }
            temporaryOriginal = null
            finalOriginal = original

            val extractedTextFileName: String
            val pageCount: Int
            if (format == EbookFormat.PDF) {
                extractedTextFileName = ""
                pageCount = readPdfPageCount(original)
            } else {
                val content = extractText(original, format).trim()
                require(content.isNotBlank()) { "No readable ebook text" }
                extractedTextFileName = "$id.txt"
                val textFile = File(textDirectory, extractedTextFileName)
                extractedText = textFile
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
            require(saveBooks(updated)) { "Unable to save ebook catalog" }
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
                message = when (error.message) {
                    "Unsupported ebook format" -> "暂不支持该格式，可导入PDF、EPUB、TXT、Markdown、HTML、DOCX、FB2或RTF"
                    "Ebook file too large" -> "单本书不能超过250MB"
                    "No readable ebook text" -> "文件中没有解析到可阅读文字，可能带有加密或特殊排版"
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
                        "该下载不是可直接阅读的电子书。支持PDF、EPUB、TXT、Markdown、HTML、DOCX、FB2和RTF；压缩包请先解压后手动导入"
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
     * 保存阅读页码、翻页模式、字号、字体和阅读背景。
     *
     * @param bookId 目标书籍id。
     * @param currentPage 当前零基页码。
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
     * @return 目录、原文件和解析文本均处理成功返回true；书籍不存在返回false。
     */
    fun deleteBook(bookId: String): Boolean {
        val books = getBooks()
        val target = books.firstOrNull { book -> book.id == bookId } ?: return false
        val catalogSaved = saveBooks(books.filterNot { book -> book.id == bookId })
        if (!catalogSaved) return false
        val originalDeleted = originalFileFor(target)?.delete() ?: true
        val textDeleted = extractedTextFileFor(target)?.delete() ?: true
        if (!originalDeleted || !textDeleted) {
            Log.w(TAG, "Failed to delete one or more ebook files")
        }
        return originalDeleted && textDeleted
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

    /** 把Uri流式复制到临时文件，同时执行单文件上限校验。 */
    private fun copyUriWithLimit(uri: Uri, target: File): Long {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open ebook Uri")
        var total = 0L
        input.buffered().use { source ->
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
        return total
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

    /** @return 指定格式原文件解析出的纯文本。 */
    private fun extractText(file: File, format: EbookFormat): String {
        return when (format) {
            EbookFormat.TEXT,
            EbookFormat.MARKDOWN -> decodeTextBytes(file.readBytes())
            EbookFormat.HTML -> htmlToText(decodeTextBytes(file.readBytes()))
            EbookFormat.DOCX -> extractDocx(file)
            EbookFormat.EPUB -> extractEpub(file)
            EbookFormat.FB2 -> htmlToText(decodeTextBytes(file.readBytes()))
            EbookFormat.RTF -> extractRtf(decodeTextBytes(file.readBytes()))
            EbookFormat.PDF -> error("PDF uses native page renderer")
        }.replace(Regex("[ \\t]+\\n"), "\n")
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
            put("pageCount", book.pageCount)
            put("readingMode", book.readingMode.name)
            put("fontScale", book.fontScale.toDouble())
            put("fontFamily", book.fontFamily.name)
            put("readingBackground", book.readingBackground.name)
            put("isOnShelf", book.isOnShelf)
            put("shelfOrder", book.shelfOrder)
            put("spineColorArgb", book.spineColorArgb.toLong())
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
                currentPage = json.optInt("currentPage").coerceAtLeast(0),
                pageCount = json.optInt("pageCount", 1).coerceAtLeast(1),
                readingMode = readingMode,
                fontScale = json.optDouble("fontScale", 1.0).toFloat()
                    .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
                fontFamily = fontFamily,
                readingBackground = readingBackground,
                isOnShelf = json.optBoolean("isOnShelf", true),
                shelfOrder = json.optLong("shelfOrder", createdAtMillis),
                spineColorArgb = json.optLong("spineColorArgb", 0L).toInt(),
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
        const val WEB_DOWNLOAD_CACHE_DIRECTORY = "ebook_web_downloads"
        const val CATALOG_FILE_NAME = "catalog.json"
        const val PREFERENCES_NAME = "harley_ebook_preferences"
        const val STARTER_BOOKS_INSTALLED_KEY = "starter_books_v1_installed"
        const val STARTER_ASSET_DIRECTORY = "starter_ebooks"
        const val STARTER_BOOK_ID_PREFIX = "starter_gutenberg_"
        const val DEFAULT_STARTER_SHELF_COUNT = 6
        const val MAX_EBOOK_BYTES = 250L * 1024L * 1024L
        const val MAX_EXTRACTED_TEXT_BYTES = 80L * 1024L * 1024L
        const val MAX_SINGLE_CHAPTER_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_PAGE_CHARACTERS = 1_300
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
