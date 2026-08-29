package com.example.harleyapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 一张网站卡片背景图的备份内容。
 *
 * @param fileName App私有目录中的安全文件名。
 * @param bytes 已压缩的JPEG图片字节。
 */
data class WebsiteBackgroundBackupEntry(
    val fileName: String,
    val bytes: ByteArray
)

/**
 * 从相册导入网站卡片背景图的结果。
 *
 * @param fileName 成功时生成的私有图片文件名，失败时为null。
 * @param message 可直接显示给用户的中文结果说明。
 */
data class WebsiteBackgroundImportResult(
    val fileName: String?,
    val message: String
) {
    /** @return true表示图片已经成功复制、压缩并写入App私有目录。 */
    val success: Boolean
        get() = fileName != null
}

/**
 * 管理网站卡片的本机背景图片、压缩限制和备份快照。
 *
 * 使用方法：
 * 编辑器通过[importFromUri]把相册图片复制为受控JPEG，模型只保存返回的文件名；卡片通过
 * [loadBitmap]读取。网站保存或删除成功后调用[cleanupUnused]清理无引用文件。备份管理器使用
 * [snapshotForBackup]和[replaceFromBackup]完成跨手机迁移，不保存容易失效的相册Uri。
 *
 * @param context Android上下文，内部只保留Application Context。
 */
class WebsiteCardBackgroundStore(context: Context) {

    private val applicationContext = context.applicationContext
    private val directory = File(applicationContext.filesDir, DIRECTORY_NAME)

    /**
     * 从系统图片选择器返回的Uri导入、旋转、缩放并压缩背景图。
     *
     * @param uri 用户刚选择且当前仍具备临时读取权限的图片Uri。
     * @return 包含私有文件名或中文失败原因的导入结果。
     */
    suspend fun importFromUri(uri: Uri): WebsiteBackgroundImportResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val sourceBytes = readLimitedSource(uri)
                val decoded = decodeScaledBitmap(sourceBytes)
                    ?: error("Unsupported image data")
                val oriented = applyExifOrientation(decoded, sourceBytes)
                val outputBytes = compressWithinLimit(oriented)
                if (oriented !== decoded) decoded.recycle()
                oriented.recycle()

                val existingFiles = directory.listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile && isSafeFileName(file.name) }
                require(existingFiles.size < MAX_IMAGE_COUNT) {
                    "Background image count limit exceeded"
                }
                val existingBytes = existingFiles.sumOf(File::length)
                require(existingBytes + outputBytes.size <= MAX_TOTAL_IMAGE_BYTES) {
                    "Background storage limit exceeded"
                }
                require(directory.exists() || directory.mkdirs()) {
                    "Unable to create background directory"
                }

                val fileName = "${UUID.randomUUID()}.jpg"
                val temporaryFile = File(directory, "$fileName.tmp")
                val targetFile = File(directory, fileName)
                temporaryFile.outputStream().buffered().use { output ->
                    output.write(outputBytes)
                }
                require(temporaryFile.renameTo(targetFile)) {
                    temporaryFile.delete()
                    "Unable to finalize background image"
                }
                WebsiteBackgroundImportResult(
                    fileName = fileName,
                    message = "背景图片已保存"
                )
            }.getOrElse { error ->
                Log.e(TAG, "Failed to import website card background", error)
                WebsiteBackgroundImportResult(
                    fileName = null,
                    message = when (error.message) {
                        "Source image is too large" -> "图片文件过大，请选择小于20MB的图片"
                        "Background image count limit exceeded" ->
                            "卡片背景图片数量已达上限，请先删除不再使用的背景"
                        "Background storage limit exceeded" ->
                            "卡片背景图片总容量已达上限，请先删除不再使用的背景"
                        else -> "无法读取该图片，请换一张后重试"
                    }
                )
            }
        }
    }

    /**
     * 读取一张已保存的背景图。
     *
     * @param fileName 网站模型保存的私有图片文件名。
     * @return 解码成功返回Bitmap；文件名无效、文件不存在或损坏时返回null。
     */
    fun loadBitmap(fileName: String?): Bitmap? {
        if (!isSafeFileName(fileName)) return null
        return runCatching {
            BitmapFactory.decodeFile(File(directory, fileName!!).absolutePath)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to load website card background", error)
            null
        }
    }

    /**
     * 删除一张私有背景图。
     *
     * @param fileName 待删除的安全文件名；null或异常路径会被拒绝。
     * @return 文件原本不存在或删除成功返回true，否则返回false。
     */
    fun delete(fileName: String?): Boolean {
        if (!isSafeFileName(fileName)) return fileName == null
        val file = File(directory, fileName!!)
        return !file.exists() || file.delete()
    }

    /**
     * 清除没有被任何网站引用的背景图和导入中断留下的临时文件。
     *
     * @param activeFileNames 当前全部网站模型正在引用的文件名集合。
     * @return 所有无引用文件均成功删除时返回true，否则返回false并保留失败文件。
     */
    fun cleanupUnused(activeFileNames: Set<String>): Boolean {
        if (!directory.exists()) return true
        var allDeleted = true
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name in activeFileNames && isSafeFileName(file.name)) {
                return@forEach
            } else {
                allDeleted = file.deleteRecursively() && allDeleted
            }
        }
        return allDeleted
    }

    /**
     * 读取全部受控背景图供本地备份编码。
     *
     * @return 文件名排序的图片备份列表；数据超过安全上限时抛出异常并让备份整体失败。
     */
    fun snapshotForBackup(): List<WebsiteBackgroundBackupEntry> {
        if (!directory.exists()) return emptyList()
        val entries = directory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && isSafeFileName(file.name) }
            .sortedBy(File::getName)
            .map { file ->
                require(file.length() <= MAX_SINGLE_IMAGE_BYTES)
                WebsiteBackgroundBackupEntry(
                    fileName = file.name,
                    bytes = file.readBytes()
                )
            }
        require(entries.size <= MAX_IMAGE_COUNT)
        require(entries.sumOf { entry -> entry.bytes.size.toLong() } <= MAX_TOTAL_IMAGE_BYTES)
        return entries
    }

    /**
     * 用备份中的完整图片集合替换本机背景目录。
     *
     * @param entries 已从备份JSON解码的文件名和JPEG字节列表。
     * @return 校验和目录替换全部成功返回true；失败时尽量恢复原目录并返回false。
     */
    fun replaceFromBackup(entries: List<WebsiteBackgroundBackupEntry>): Boolean {
        return runCatching {
            require(entries.size <= MAX_IMAGE_COUNT)
            require(entries.map { entry -> entry.fileName }.distinct().size == entries.size)
            require(entries.all { entry ->
                isSafeFileName(entry.fileName) && entry.bytes.size <= MAX_SINGLE_IMAGE_BYTES
            })
            require(entries.sumOf { entry -> entry.bytes.size.toLong() } <= MAX_TOTAL_IMAGE_BYTES)

            val temporaryDirectory = File(
                applicationContext.filesDir,
                "${DIRECTORY_NAME}_restore_${UUID.randomUUID()}"
            )
            require(temporaryDirectory.mkdirs())
            entries.forEach { entry ->
                File(temporaryDirectory, entry.fileName).writeBytes(entry.bytes)
            }

            val rollbackDirectory = File(
                applicationContext.filesDir,
                "${DIRECTORY_NAME}_rollback_${UUID.randomUUID()}"
            )
            val hadExistingDirectory = directory.exists()
            if (hadExistingDirectory) {
                require(directory.renameTo(rollbackDirectory))
            }
            val replacementSucceeded = temporaryDirectory.renameTo(directory)
            if (!replacementSucceeded) {
                temporaryDirectory.deleteRecursively()
                if (hadExistingDirectory) rollbackDirectory.renameTo(directory)
                error("Unable to replace website background directory")
            }
            rollbackDirectory.deleteRecursively()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to restore website card backgrounds", error)
            false
        }
    }

    /**
     * 从ContentResolver读取图片并限制原文件大小。
     *
     * @param uri 系统图片Uri。
     * @return 不超过20MB的完整源文件字节。
     */
    private fun readLimitedSource(uri: Uri): ByteArray {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open image")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_SOURCE_IMAGE_BYTES) { "Source image is too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    /**
     * 按最大边限制解码图片，降低超大相册图片的峰值内存。
     *
     * @param bytes 原始图片字节。
     * @return 缩小后的可用Bitmap；格式无法识别时返回null。
     */
    private fun decodeScaledBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_IMAGE_EDGE * 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val largestEdge = max(decoded.width, decoded.height)
        if (largestEdge <= MAX_IMAGE_EDGE) return decoded

        val scale = MAX_IMAGE_EDGE.toFloat() / largestEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        decoded.recycle()
        return scaled
    }

    /**
     * 根据JPEG EXIF方向修正横竖和镜像状态。
     *
     * @param bitmap 已缩放解码的图片。
     * @param sourceBytes 原始文件字节，用于读取EXIF方向。
     * @return 方向正确的Bitmap；无需变换时返回原对象。
     */
    private fun applyExifOrientation(bitmap: Bitmap, sourceBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(sourceBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    setRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    setRotate(-90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 逐步降低JPEG质量，确保单张背景图不超过备份安全上限。
     *
     * @param bitmap 已完成缩放和方向修正的图片。
     * @return 小于等于1MB的JPEG字节。
     */
    private fun compressWithinLimit(bitmap: Bitmap): ByteArray {
        for (quality in listOf(84, 74, 64, 54)) {
            val output = ByteArrayOutputStream()
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            val bytes = output.toByteArray()
            if (bytes.size <= MAX_SINGLE_IMAGE_BYTES) return bytes
        }
        error("Compressed image is too large")
    }

    /**
     * 校验文件名只能引用背景目录中的UUID JPEG，阻止路径穿越。
     *
     * @param fileName 待校验文件名。
     * @return 格式安全时返回true，否则返回false。
     */
    private fun isSafeFileName(fileName: String?): Boolean {
        return fileName != null && SAFE_FILE_NAME.matches(fileName)
    }

    companion object {
        const val MAX_IMAGE_COUNT = 100
        const val MAX_SINGLE_IMAGE_BYTES = 1024 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 12L * 1024L * 1024L

        private const val TAG = "WebsiteCardBackground"
        private const val DIRECTORY_NAME = "website_card_backgrounds"
        private const val MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_IMAGE_EDGE = 1280
        private val SAFE_FILE_NAME = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"
        )
    }
}
