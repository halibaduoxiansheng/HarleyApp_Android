package com.example.harleyapp.model

/** 大文件默认阈值：100MB。 */
const val STORAGE_LARGE_FILE_THRESHOLD_BYTES = 100L * 1024L * 1024L

/** 长期未修改文件默认阈值：90个自然日。 */
const val STORAGE_STALE_FILE_DAYS = 90L

/** 重复扫描只对大于等于1MB且大小相同的候选文件计算SHA-256，减少无意义的磁盘读取。 */
const val STORAGE_DUPLICATE_MINIMUM_BYTES = 1L * 1024L * 1024L

/** 单次扫描的文件数量上限，避免异常目录结构无限占用内存。 */
const val STORAGE_SCAN_FILE_LIMIT = 100_000

/**
 * 文件空间页使用的稳定容量分类。
 *
 * 分类只依据文件扩展名，不读取图片内容、媒体元数据或文档正文；无法识别时归入[OTHER]。
 */
enum class StorageFileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    INSTALLER,
    OTHER
}

/**
 * 单个共享存储文件的只读扫描快照。
 *
 * @param absolutePath 扫描时解析出的绝对路径，只用于本机展示和用户确认后的操作。
 * @param relativePath 相对共享存储根目录的路径，便于用户识别位置。
 * @param displayName 文件名。
 * @param sizeBytes 文件扫描时的字节数。
 * @param lastModifiedMillis 文件系统最后修改时间；为0表示设备没有提供可靠时间。
 * @param category 依据扩展名得到的容量分类。
 * @param inDownloads 是否位于系统Download目录或其子目录。
 * @param directlyInDownloads 是否是Download目录的直接子文件，可用于安全的一键分类整理。
 */
data class StorageFileEntry(
    val absolutePath: String,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val category: StorageFileCategory,
    val inDownloads: Boolean,
    val directlyInDownloads: Boolean
)

/**
 * 一个容量分类的文件数量和总字节数。
 *
 * @param category 文件分类。
 * @param fileCount 分类中的文件数量。
 * @param totalBytes 分类文件总大小。
 */
data class StorageCategorySummary(
    val category: StorageFileCategory,
    val fileCount: Int,
    val totalBytes: Long
)

/**
 * 内容完全相同的一组重复文件。
 *
 * @param sha256 对完整文件内容计算的SHA-256十六进制摘要。
 * @param sizeBytes 组内每个文件的字节数。
 * @param files 内容摘要相同的文件列表。
 * @param reclaimableBytes 保留一个文件、删除其余副本后理论可释放的字节数。
 */
data class DuplicateFileGroup(
    val sha256: String,
    val sizeBytes: Long,
    val files: List<StorageFileEntry>,
    val reclaimableBytes: Long
)

/**
 * 一次共享存储扫描得到的完整快照。
 *
 * @param generatedAtMillis 快照生成Unix毫秒时间。
 * @param totalSpaceBytes 共享存储分区总容量。
 * @param freeSpaceBytes 共享存储分区剩余容量。
 * @param scannedFileCount 实际完成扫描的文件数量。
 * @param scannedBytes 实际扫描文件的总字节数，不代表分区已用总量。
 * @param categories 各文件类型的容量汇总，始终包含全部分类和零值。
 * @param largeFiles 大于等于100MB的大文件，按大小降序。
 * @param duplicateGroups 经完整SHA-256确认的重复文件组，按可释放空间降序。
 * @param downloads Download目录中的文件，按大小降序。
 * @param staleFiles 超过90天未修改的文件，按最后修改时间升序。
 * @param skippedPathCount 因权限、深度或数量保护而跳过的路径数量。
 * @param reachedFileLimit 是否达到单次十万个文件的保护上限。
 */
data class StorageScanSnapshot(
    val generatedAtMillis: Long,
    val totalSpaceBytes: Long,
    val freeSpaceBytes: Long,
    val scannedFileCount: Int,
    val scannedBytes: Long,
    val categories: List<StorageCategorySummary>,
    val largeFiles: List<StorageFileEntry>,
    val duplicateGroups: List<DuplicateFileGroup>,
    val downloads: List<StorageFileEntry>,
    val staleFiles: List<StorageFileEntry>,
    val skippedPathCount: Int,
    val reachedFileLimit: Boolean
) {
    /** 已使用分区容量，来自分区总量减去剩余量。 */
    val usedSpaceBytes: Long
        get() = (totalSpaceBytes - freeSpaceBytes).coerceAtLeast(0L)

    /** 全部已确认重复组理论可释放的总字节数。 */
    val duplicateReclaimableBytes: Long
        get() = duplicateGroups.sumOf(DuplicateFileGroup::reclaimableBytes)
}

/** 文件扫描失败时页面使用的稳定错误类型。 */
enum class StorageScanError {
    FULL_ACCESS_REQUIRED,
    STORAGE_UNAVAILABLE,
    SCAN_FAILED
}

/**
 * 文件扫描结果。
 *
 * @param snapshot 成功时的扫描快照。
 * @param error 失败时的稳定错误；成功时为null。
 */
data class StorageScanResult(
    val snapshot: StorageScanSnapshot? = null,
    val error: StorageScanError? = null
)

/**
 * 用户确认后的删除或下载整理结果。
 *
 * @param success 操作是否按预期完成。
 * @param affectedCount 成功删除或移动的文件数量。
 * @param skippedCount 因冲突、权限或文件状态变化而跳过的数量。
 * @param message 可直接展示给用户的简洁中文结果。
 */
data class StorageOperationResult(
    val success: Boolean,
    val affectedCount: Int,
    val skippedCount: Int,
    val message: String
)

/**
 * 根据文件名扩展名判断容量分类。
 *
 * 使用方法：传入文件名或完整路径均可；函数仅取最后一个英文句点后的扩展名，不访问磁盘。
 *
 * @param fileName 文件名或路径，大小写不限。
 * @return 已知图片、视频、音频、文档、压缩包、安装包分类；未知或无扩展名返回[StorageFileCategory.OTHER]。
 */
fun classifyStorageFile(fileName: String): StorageFileCategory {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return when (extension) {
        in IMAGE_EXTENSIONS -> StorageFileCategory.IMAGE
        in VIDEO_EXTENSIONS -> StorageFileCategory.VIDEO
        in AUDIO_EXTENSIONS -> StorageFileCategory.AUDIO
        in DOCUMENT_EXTENSIONS -> StorageFileCategory.DOCUMENT
        in ARCHIVE_EXTENSIONS -> StorageFileCategory.ARCHIVE
        in INSTALLER_EXTENSIONS -> StorageFileCategory.INSTALLER
        else -> StorageFileCategory.OTHER
    }
}

/** 图片文件扩展名白名单。 */
private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "svg", "avif"
)

/** 视频文件扩展名白名单。 */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "m4v", "3gp", "ts", "mts"
)

/** 音频文件扩展名白名单。 */
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "amr", "wma", "ape"
)

/** 常用文档、表格、演示、电子书和纯文本扩展名白名单。 */
private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "rtf",
    "epub", "mobi", "azw", "azw3", "wps", "ofd", "json", "xml"
)

/** 压缩包和磁盘镜像扩展名白名单。 */
private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"
)

/** Android安装包扩展名白名单。 */
private val INSTALLER_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm")
