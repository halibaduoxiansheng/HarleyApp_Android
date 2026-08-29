package com.example.harleyapp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.harleyapp.model.newNotebookStableId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 相册图片或GIF导入结果。
 *
 * @param success 是否已复制到App私有目录。
 * @param fileName 成功时返回受控文件名。
 * @param mimeType 成功时返回图片MIME类型。
 * @param message 页面可直接显示的中文结果。
 */
data class NotebookMediaImportResult(
    val success: Boolean,
    val fileName: String = "",
    val mimeType: String = "",
    val message: String
)

/**
 * 随本地备份迁移的一份记事本媒体文件。
 *
 * @param fileName 经过白名单校验的文件名。
 * @param bytes 原始图片或GIF字节。
 */
data class NotebookMediaBackupEntry(
    val fileName: String,
    val bytes: ByteArray
)

/**
 * 管理记事本文章使用的本地图片和GIF。
 *
 * 使用方法：
 * 编辑器取得系统图片Uri后调用[importFromUri]，文章模型只保存返回的文件名；展示时通过[fileFor]
 * 获取文件。文章删除或替换图片后调用[deleteUnusedMedia]清理孤立文件；本地备份使用
 * [snapshotForBackup]和[replaceFromBackup]迁移全部媒体。
 *
 * @param context Android上下文，内部只保存Application Context。
 */
class NotebookMediaStore(context: Context) {

    private val applicationContext = context.applicationContext
    private val mediaDirectory = File(applicationContext.filesDir, MEDIA_DIRECTORY)

    /**
     * 把系统选择器返回的图片或GIF复制到App私有目录。
     *
     * @param uri 当前仍有读取权限的Content Uri。
     * @return 导入结果；单文件、总容量或格式不符合限制时不会留下半成品。
     */
    suspend fun importFromUri(uri: Uri): NotebookMediaImportResult {
        return withContext(Dispatchers.IO) {
            var temporaryFile: File? = null
            runCatching {
                val mimeType = applicationContext.contentResolver.getType(uri)
                    ?.lowercase()
                    ?.takeIf { value -> value.startsWith("image/") }
                    ?: error("Unsupported media type")
                val bytes = readLimitedBytes(uri)
                if (bytes.isEmpty()) error("Empty media file")
                ensureDirectory()
                val currentFiles = mediaDirectory.listFiles()
                    .orEmpty()
                    .filter { file ->
                        file.isFile && isValidNotebookMediaFileName(file.name)
                    }
                require(currentFiles.size < MAX_MEDIA_COUNT) {
                    "Notebook media count limit reached"
                }
                val currentBytes = currentFiles
                    .sumOf(File::length)
                require(currentBytes + bytes.size <= MAX_TOTAL_MEDIA_BYTES) {
                    "Notebook media storage limit reached"
                }
                val extension = resolveExtension(mimeType)
                val fileName = "${newNotebookStableId("media")}.$extension"
                val target = File(mediaDirectory, fileName)
                val temporary = File(mediaDirectory, "$fileName.tmp")
                temporaryFile = temporary
                temporary.outputStream().use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
                require(temporary.renameTo(target)) { "Unable to finalize media file" }
                temporaryFile = null
                NotebookMediaImportResult(
                    success = true,
                    fileName = fileName,
                    mimeType = mimeType,
                    message = if (mimeType == GIF_MIME_TYPE) {
                        "GIF表情包已插入"
                    } else {
                        "图片已插入"
                    }
                )
            }.getOrElse { error ->
                temporaryFile?.delete()
                Log.e(TAG, "Failed to import notebook media", error)
                NotebookMediaImportResult(
                    success = false,
                    message = when (error) {
                        is MediaTooLargeException -> "单张图片或GIF不能超过8MB"
                        else -> "图片导入失败，请检查格式或存储空间后重试"
                    }
                )
            }
        }
    }

    /**
     * 根据文章保存的受控文件名读取媒体文件。
     *
     * @param fileName 文章块中的文件名。
     * @return 文件名合法且文件存在时返回File，否则返回null。
     */
    fun fileFor(fileName: String): File? {
        if (!isValidNotebookMediaFileName(fileName)) return null
        return File(mediaDirectory, fileName).takeIf(File::isFile)
    }

    /**
     * 删除没有被任何文章引用的媒体文件。
     *
     * @param usedFileNames 当前全部文章仍在使用的文件名集合。
     * @return 全部孤立文件删除成功返回true；目录不存在也返回true。
     */
    fun deleteUnusedMedia(usedFileNames: Set<String>): Boolean {
        val safeUsedNames = usedFileNames.filterTo(hashSetOf(), ::isValidNotebookMediaFileName)
        var success = true
        mediaDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name !in safeUsedNames) {
                success = file.delete() && success
            }
        }
        if (!success) {
            Log.w(TAG, "Failed to delete one or more unused notebook media files")
        }
        return success
    }

    /**
     * 读取全部合法媒体供备份管理器编码。
     *
     * @return 按文件名排序、总量经过限制的媒体快照。
     */
    fun snapshotForBackup(): List<NotebookMediaBackupEntry> {
        var totalBytes = 0L
        return mediaDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && isValidNotebookMediaFileName(file.name) }
            .sortedBy(File::getName)
            .take(MAX_MEDIA_COUNT)
            .mapNotNull { file ->
                val length = file.length()
                if (length <= 0L || length > MAX_SINGLE_MEDIA_BYTES ||
                    totalBytes + length > MAX_TOTAL_MEDIA_BYTES
                ) {
                    null
                } else {
                    totalBytes += length
                    NotebookMediaBackupEntry(fileName = file.name, bytes = file.readBytes())
                }
            }
            .toList()
    }

    /**
     * 使用已经校验的备份媒体完整替换本机目录。
     *
     * @param entries 备份解析出的媒体列表。
     * @return 原子目录切换成功返回true；失败时尽量恢复原目录并返回false。
     */
    fun replaceFromBackup(entries: List<NotebookMediaBackupEntry>): Boolean {
        return runCatching {
            validateBackupEntries(entries)
            val staging = File(applicationContext.filesDir, "$MEDIA_DIRECTORY.import")
            val previous = File(applicationContext.filesDir, "$MEDIA_DIRECTORY.previous")
            staging.deleteRecursively()
            previous.deleteRecursively()
            require(staging.mkdirs()) { "Unable to create notebook media staging directory" }
            entries.forEach { entry ->
                File(staging, entry.fileName).writeBytes(entry.bytes)
            }
            if (mediaDirectory.exists()) {
                require(mediaDirectory.renameTo(previous)) {
                    "Unable to move current notebook media directory"
                }
            }
            val installed = staging.renameTo(mediaDirectory)
            if (!installed) {
                previous.renameTo(mediaDirectory)
                error("Unable to install restored notebook media directory")
            }
            previous.deleteRecursively()
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to restore notebook media", error)
        }.getOrDefault(false)
    }

    /**
     * 校验备份媒体的数量、文件名和容量。
     *
     * @param entries 待恢复条目。
     */
    private fun validateBackupEntries(entries: List<NotebookMediaBackupEntry>) {
        require(entries.size <= MAX_MEDIA_COUNT)
        require(entries.map { entry -> entry.fileName }.distinct().size == entries.size)
        var totalBytes = 0L
        entries.forEach { entry ->
            require(isValidNotebookMediaFileName(entry.fileName))
            require(entry.bytes.isNotEmpty() && entry.bytes.size <= MAX_SINGLE_MEDIA_BYTES)
            totalBytes += entry.bytes.size
            require(totalBytes <= MAX_TOTAL_MEDIA_BYTES)
        }
    }

    /**
     * 从ContentResolver读取有限大小的媒体字节。
     *
     * @param uri 系统图片Uri。
     * @return 不超过单文件限制的完整字节。
     */
    private fun readLimitedBytes(uri: Uri): ByteArray {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open notebook media")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_SINGLE_MEDIA_BYTES) {
                    throw MediaTooLargeException()
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    /** 确保私有媒体目录存在。 */
    private fun ensureDirectory() {
        require(mediaDirectory.exists() || mediaDirectory.mkdirs()) {
            "Unable to create notebook media directory"
        }
    }

    /**
     * 根据MIME类型得到安全扩展名。
     *
     * @param mimeType 图片MIME类型。
     * @return 常见扩展名；系统无法识别时返回img。
     */
    private fun resolveExtension(mimeType: String): String {
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.lowercase()
            ?.takeIf { extension -> extension.matches(EXTENSION_PATTERN) }
            ?: if (mimeType == GIF_MIME_TYPE) "gif" else "img"
    }

    private class MediaTooLargeException : IllegalArgumentException()

    companion object {
        const val MAX_MEDIA_COUNT = 120
        const val MAX_SINGLE_MEDIA_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_MEDIA_BYTES = 48L * 1024L * 1024L
        private const val TAG = "NotebookMediaStore"
        private const val MEDIA_DIRECTORY = "notebook_media"
        private const val GIF_MIME_TYPE = "image/gif"
        private val EXTENSION_PATTERN = Regex("[a-z0-9]{1,8}")
    }
}

/**
 * 判断文件名是否属于记事本受控媒体。
 *
 * @param fileName 待检查文件名。
 * @return 只包含安全字符、没有路径分隔符且长度受限时返回true。
 */
fun isValidNotebookMediaFileName(fileName: String): Boolean {
    return fileName.length in 8..96 &&
        fileName.startsWith("media_") &&
        fileName.matches(Regex("[A-Za-z0-9_.-]+")) &&
        '/' !in fileName &&
        '\\' !in fileName
}
