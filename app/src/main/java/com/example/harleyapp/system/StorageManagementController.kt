package com.example.harleyapp.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.harleyapp.model.DuplicateFileGroup
import com.example.harleyapp.model.StorageCategorySummary
import com.example.harleyapp.model.StorageFileCategory
import com.example.harleyapp.model.StorageFileEntry
import com.example.harleyapp.model.StorageOperationResult
import com.example.harleyapp.model.StorageScanError
import com.example.harleyapp.model.StorageScanResult
import com.example.harleyapp.model.StorageScanSnapshot
import com.example.harleyapp.model.STORAGE_DUPLICATE_MINIMUM_BYTES
import com.example.harleyapp.model.STORAGE_LARGE_FILE_THRESHOLD_BYTES
import com.example.harleyapp.model.STORAGE_SCAN_FILE_LIMIT
import com.example.harleyapp.model.STORAGE_STALE_FILE_DAYS
import com.example.harleyapp.model.classifyStorageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * 扫描和整理用户共享存储中的文件，并集中处理Android文件管理权限。
 *
 * 使用方法：
 * 页面先调用[hasFullAccess]。Android 11及以上未授权时，用[createFullAccessIntent]打开本应用的
 * “所有文件访问权限”页面；Android 10及以下则申请[requiredLegacyPermissions]返回的运行时权限。
 * 授权后在协程调用[scan]。删除和下载整理不会自动执行，必须在用户确认后分别调用[deleteFile]
 * 或[organizeDownloads]。
 *
 * 扫描仅读取文件名、路径、大小和最后修改时间；只有大小相同且至少1MB的重复候选会完整读取内容
 * 计算SHA-256。不会读取文档语义、照片内容或媒体播放记录，也不会上传数据。
 *
 * @param context Android上下文，内部统一保存Application Context。
 */
class StorageManagementController(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * 判断当前Android版本是否已经提供完整共享存储访问能力。
     *
     * @return Android 11及以上在系统特殊权限已开启时返回true；旧版本读写运行时权限均已授予时返回true。
     */
    fun hasFullAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            requiredLegacyPermissions().all { permission ->
                ContextCompat.checkSelfPermission(applicationContext, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * 返回Android 10及以下扫描和整理共享存储需要申请的权限。
     *
     * @return Android 10及以下返回读取和写入权限；Android 11及以上返回空数组，改走系统特殊权限页。
     */
    fun requiredLegacyPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            emptyArray()
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    /**
     * 创建本应用的“所有文件访问权限”设置Intent。
     *
     * @return 优先定位到本应用的特殊权限页；设备不支持该入口时退回所有应用列表页。
     */
    @SuppressLint("InlinedApi")
    fun createFullAccessIntent(): Intent {
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${applicationContext.packageName}".toUri()
        )
        return if (appSpecificIntent.resolveActivity(applicationContext.packageManager) != null) {
            appSpecificIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /**
     * 扫描共享存储并生成分类、大文件、重复文件、下载目录和长期未修改文件快照。
     *
     * 调用方式：先确认[hasFullAccess]为true，再从页面协程调用。本函数固定切换到IO线程；协程被
     * 取消时会在目录遍历和文件摘要读取之间及时停止。为了保护内存，最多收集十万个文件；
     * Android/data和Android/obb按系统限制主动跳过。
     *
     * @return 成功时返回[StorageScanSnapshot]；权限、存储状态或异常问题转换为稳定错误类型。
     */
    suspend fun scan(): StorageScanResult = withContext(Dispatchers.IO) {
        if (!hasFullAccess()) {
            return@withContext StorageScanResult(error = StorageScanError.FULL_ACCESS_REQUIRED)
        }
        if (!isStorageReadable()) {
            return@withContext StorageScanResult(error = StorageScanError.STORAGE_UNAVAILABLE)
        }

        runCatching {
            buildSnapshot()
        }.fold(
            onSuccess = { snapshot ->
                StorageScanResult(snapshot = snapshot)
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to scan shared storage", error)
                StorageScanResult(error = StorageScanError.SCAN_FAILED)
            }
        )
    }

    /**
     * 永久删除一个已经展示给用户并由用户再次确认的普通文件。
     *
     * @param entry 最近一次扫描快照中的文件条目。
     * @return 删除成功、文件已变化或安全校验失败的中文结果；不会递归删除目录。
     */
    suspend fun deleteFile(entry: StorageFileEntry): StorageOperationResult =
        withContext(Dispatchers.IO) {
            if (!hasFullAccess()) {
                return@withContext StorageOperationResult(
                    success = false,
                    affectedCount = 0,
                    skippedCount = 1,
                    message = "文件权限已关闭，请重新授权"
                )
            }
            val root = getStorageRoot() ?: return@withContext StorageOperationResult(
                success = false,
                affectedCount = 0,
                skippedCount = 1,
                message = "共享存储当前不可用"
            )
            val target = runCatching { File(entry.absolutePath).canonicalFile }.getOrNull()
            if (target == null || !isSafeChildFile(target, root)) {
                return@withContext StorageOperationResult(
                    success = false,
                    affectedCount = 0,
                    skippedCount = 1,
                    message = "文件路径安全校验未通过"
                )
            }
            if (!target.exists() || !target.isFile) {
                return@withContext StorageOperationResult(
                    success = false,
                    affectedCount = 0,
                    skippedCount = 1,
                    message = "文件已经移动或不存在"
                )
            }

            val deleted = runCatching { target.delete() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to delete confirmed storage file", error)
                }
                .getOrDefault(false)
            if (deleted) {
                StorageOperationResult(
                    success = true,
                    affectedCount = 1,
                    skippedCount = 0,
                    message = "已永久删除 ${entry.displayName}"
                )
            } else {
                StorageOperationResult(
                    success = false,
                    affectedCount = 0,
                    skippedCount = 1,
                    message = "删除失败，文件可能正被占用"
                )
            }
        }

    /**
     * 把Download根目录的直接子文件移动到“Harley整理”下的类型文件夹。
     *
     * 使用方法：页面应先展示待整理数量，并在用户确认后传入最近扫描快照的downloads列表。
     * 本函数不会移动Download已有子文件夹内的文件，不覆盖同名文件；遇到重名时自动添加序号。
     *
     * @param entries 最近一次扫描得到的Download目录文件列表。
     * @return 移动成功数、跳过数及可直接展示的操作结果。
     */
    suspend fun organizeDownloads(entries: List<StorageFileEntry>): StorageOperationResult =
        withContext(Dispatchers.IO) {
            if (!hasFullAccess()) {
                return@withContext StorageOperationResult(
                    success = false,
                    affectedCount = 0,
                    skippedCount = entries.size,
                    message = "文件权限已关闭，请重新授权"
                )
            }
            val root = getStorageRoot() ?: return@withContext StorageOperationResult(
                success = false,
                affectedCount = 0,
                skippedCount = entries.size,
                message = "共享存储当前不可用"
            )
            val downloadRoot = File(root, Environment.DIRECTORY_DOWNLOADS).canonicalFile
            val organizeRoot = File(downloadRoot, ORGANIZE_ROOT_DIRECTORY)
            var movedCount = 0
            var skippedCount = 0

            entries.filter(StorageFileEntry::directlyInDownloads).forEach { entry ->
                currentCoroutineContext().ensureActive()
                val source = runCatching { File(entry.absolutePath).canonicalFile }.getOrNull()
                if (
                    source == null ||
                    source.parentFile?.canonicalFile != downloadRoot ||
                    !isSafeChildFile(source, root) ||
                    !source.isFile
                ) {
                    skippedCount += 1
                    return@forEach
                }
                val categoryDirectory = File(organizeRoot, directoryName(entry.category))
                if (!categoryDirectory.exists() && !categoryDirectory.mkdirs()) {
                    skippedCount += 1
                    return@forEach
                }
                val destination = findAvailableDestination(categoryDirectory, source.name)
                val moved = runCatching { source.renameTo(destination) }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to move a Downloads file", error)
                    }
                    .getOrDefault(false)
                if (moved) {
                    movedCount += 1
                } else {
                    skippedCount += 1
                }
            }

            val message = when {
                movedCount > 0 && skippedCount == 0 -> "已整理${movedCount}个下载文件"
                movedCount > 0 -> "已整理${movedCount}个，跳过${skippedCount}个"
                else -> "没有可移动的下载文件"
            }
            StorageOperationResult(
                success = movedCount > 0,
                affectedCount = movedCount,
                skippedCount = skippedCount,
                message = message
            )
        }

    /**
     * 完成一次目录遍历和重复内容校验。
     *
     * @return 已排序、可直接供页面使用的共享存储快照。
     */
    private suspend fun buildSnapshot(): StorageScanSnapshot {
        val root = getStorageRoot() ?: error("Shared storage root is unavailable")
        val nowMillis = System.currentTimeMillis()
        val staleBoundaryMillis = nowMillis - TimeUnit.DAYS.toMillis(STORAGE_STALE_FILE_DAYS)
        val files = mutableListOf<StorageFileEntry>()
        val directories = ArrayDeque<DirectoryScanNode>()
        val visitedDirectories = mutableSetOf<String>()
        var skippedPathCount = 0
        var reachedFileLimit = false
        directories.add(DirectoryScanNode(root, 0))

        while (directories.isNotEmpty() && files.size < STORAGE_SCAN_FILE_LIMIT) {
            currentCoroutineContext().ensureActive()
            val node = directories.removeLast()
            val directory = runCatching { node.file.canonicalFile }.getOrNull()
            if (directory == null || !isPathInsideRoot(directory, root)) {
                skippedPathCount += 1
                continue
            }
            if (!visitedDirectories.add(directory.absolutePath)) {
                continue
            }
            if (shouldSkipDirectory(directory, root) || node.depth > MAX_DIRECTORY_DEPTH) {
                skippedPathCount += 1
                continue
            }
            val children = runCatching { directory.listFiles() }.getOrNull()
            if (children == null) {
                skippedPathCount += 1
                continue
            }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (files.size >= STORAGE_SCAN_FILE_LIMIT) {
                    reachedFileLimit = true
                    break
                }
                when {
                    child.isDirectory -> directories.add(
                        DirectoryScanNode(child, node.depth + 1)
                    )
                    child.isFile -> createFileEntry(child, root)?.let(files::add)
                    else -> skippedPathCount += 1
                }
            }
        }
        if (directories.isNotEmpty()) {
            reachedFileLimit = true
        }

        val categorySummaries = StorageFileCategory.entries.map { category ->
            val categoryFiles = files.filter { entry -> entry.category == category }
            StorageCategorySummary(
                category = category,
                fileCount = categoryFiles.size,
                totalBytes = categoryFiles.sumOf(StorageFileEntry::sizeBytes)
            )
        }
        val duplicates = findDuplicateGroups(files)

        return StorageScanSnapshot(
            generatedAtMillis = nowMillis,
            totalSpaceBytes = root.totalSpace.coerceAtLeast(0L),
            freeSpaceBytes = root.freeSpace.coerceAtLeast(0L),
            scannedFileCount = files.size,
            scannedBytes = files.sumOf(StorageFileEntry::sizeBytes),
            categories = categorySummaries,
            largeFiles = files
                .filter { entry -> entry.sizeBytes >= STORAGE_LARGE_FILE_THRESHOLD_BYTES }
                .sortedByDescending(StorageFileEntry::sizeBytes),
            duplicateGroups = duplicates,
            downloads = files
                .filter(StorageFileEntry::inDownloads)
                .sortedByDescending(StorageFileEntry::sizeBytes),
            staleFiles = files
                .filter { entry ->
                    entry.lastModifiedMillis in 1..staleBoundaryMillis
                }
                .sortedBy(StorageFileEntry::lastModifiedMillis),
            skippedPathCount = skippedPathCount,
            reachedFileLimit = reachedFileLimit
        )
    }

    /**
     * 把磁盘上的普通文件转换为不包含文件正文的扫描条目。
     *
     * @param file 当前遍历到的文件。
     * @param root 共享存储根目录。
     * @return 安全位于根目录内时返回条目，否则返回null。
     */
    private fun createFileEntry(file: File, root: File): StorageFileEntry? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!isSafeChildFile(canonical, root)) {
            return null
        }
        val relativePath = canonical.relativeTo(root).invariantSeparatorsPath
        val downloadPrefix = "${Environment.DIRECTORY_DOWNLOADS}/"
        val inDownloads = relativePath == Environment.DIRECTORY_DOWNLOADS ||
            relativePath.startsWith(downloadPrefix)
        return StorageFileEntry(
            absolutePath = canonical.absolutePath,
            relativePath = relativePath,
            displayName = canonical.name,
            sizeBytes = canonical.length().coerceAtLeast(0L),
            lastModifiedMillis = canonical.lastModified().coerceAtLeast(0L),
            category = classifyStorageFile(canonical.name),
            inDownloads = inDownloads,
            directlyInDownloads = canonical.parentFile == File(
                root,
                Environment.DIRECTORY_DOWNLOADS
            )
        )
    }

    /**
     * 对大小相同的候选文件计算完整SHA-256，确认内容是否真正重复。
     *
     * @param files 本次扫描收集的全部文件条目。
     * @return 至少包含两个内容相同文件的重复组，按理论可释放空间降序排列。
     */
    private suspend fun findDuplicateGroups(
        files: List<StorageFileEntry>
    ): List<DuplicateFileGroup> {
        val sizeCandidateGroups = files.asSequence()
            .filter { entry -> entry.sizeBytes >= STORAGE_DUPLICATE_MINIMUM_BYTES }
            .groupBy(StorageFileEntry::sizeBytes)
            .values
            .filter { candidates -> candidates.size > 1 }
        val duplicates = mutableListOf<DuplicateFileGroup>()

        sizeCandidateGroups.forEach { sameSizeFiles ->
            currentCoroutineContext().ensureActive()
            val byDigest = mutableMapOf<String, MutableList<StorageFileEntry>>()
            sameSizeFiles.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val digest = runCatching {
                    calculateSha256(File(entry.absolutePath))
                }.onFailure { error ->
                    Log.w(TAG, "Failed to hash a duplicate candidate", error)
                }.getOrNull()
                if (digest != null) {
                    byDigest.getOrPut(digest) { mutableListOf() }.add(entry)
                }
            }
            byDigest.forEach { (digest, matchingFiles) ->
                if (matchingFiles.size > 1) {
                    val sizeBytes = matchingFiles.first().sizeBytes
                    duplicates += DuplicateFileGroup(
                        sha256 = digest,
                        sizeBytes = sizeBytes,
                        files = matchingFiles.sortedBy(StorageFileEntry::relativePath),
                        reclaimableBytes = sizeBytes * (matchingFiles.size - 1L)
                    )
                }
            }
        }

        return duplicates.sortedByDescending(DuplicateFileGroup::reclaimableBytes)
    }

    /**
     * 流式读取单个文件并计算SHA-256，避免把大文件一次性载入内存。
     *
     * @param file 需要校验内容的普通文件。
     * @return 64个小写十六进制字符组成的SHA-256摘要。
     */
    private suspend fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        FileInputStream(file).buffered().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count <= 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * 判断共享存储是否至少可读。
     *
     * @return 已挂载可读写或只读介质时返回true。
     */
    private fun isStorageReadable(): Boolean {
        return Environment.getExternalStorageState() in setOf(
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY
        )
    }

    /**
     * 取得共享存储规范根目录。
     *
     * @return 根目录存在时返回规范File，否则返回null。
     */
    @Suppress("DEPRECATION")
    private fun getStorageRoot(): File? {
        return runCatching {
            Environment.getExternalStorageDirectory().canonicalFile
        }.getOrNull()?.takeIf(File::exists)
    }

    /**
     * 判断普通文件是否严格位于共享存储根目录以下。
     *
     * @param file 已规范化的目标文件。
     * @param root 已规范化的共享存储根目录。
     * @return 文件不是根本身、位于根目录内且是普通文件时返回true。
     */
    private fun isSafeChildFile(file: File, root: File): Boolean {
        return file != root && isPathInsideRoot(file, root) && file.isFile
    }

    /**
     * 判断路径是否位于指定根目录内，防止符号链接或构造路径越界。
     *
     * @param file 已规范化的待检查路径。
     * @param root 已规范化的允许根目录。
     * @return 根目录本身或其子路径返回true，其他路径返回false。
     */
    private fun isPathInsideRoot(file: File, root: File): Boolean {
        return file.path == root.path || file.path.startsWith(root.path + File.separator)
    }

    /**
     * 判断目录是否属于Android明确限制的应用私有共享目录。
     *
     * @param directory 当前规范目录。
     * @param root 共享存储根目录。
     * @return Android/data或Android/obb及其子目录返回true。
     */
    private fun shouldSkipDirectory(directory: File, root: File): Boolean {
        val relativePath = runCatching {
            directory.relativeTo(root).invariantSeparatorsPath
        }.getOrDefault("")
        return relativePath == "Android/data" ||
            relativePath.startsWith("Android/data/") ||
            relativePath == "Android/obb" ||
            relativePath.startsWith("Android/obb/")
    }

    /**
     * 返回分类在“Harley整理”目录下使用的中文文件夹名。
     *
     * @param category 文件分类。
     * @return 稳定的单层目录名称。
     */
    private fun directoryName(category: StorageFileCategory): String {
        return when (category) {
            StorageFileCategory.IMAGE -> "图片"
            StorageFileCategory.VIDEO -> "视频"
            StorageFileCategory.AUDIO -> "音频"
            StorageFileCategory.DOCUMENT -> "文档"
            StorageFileCategory.ARCHIVE -> "压缩包"
            StorageFileCategory.INSTALLER -> "安装包"
            StorageFileCategory.OTHER -> "其他"
        }
    }

    /**
     * 为移动目标生成不会覆盖现有文件的可用名称。
     *
     * @param directory 分类目标目录。
     * @param originalName 原始文件名。
     * @return 原名可用时直接返回；冲突时依次添加“(1)”“(2)”序号。
     */
    private fun findAvailableDestination(directory: File, originalName: String): File {
        val original = File(directory, originalName)
        if (!original.exists()) {
            return original
        }
        val extension = originalName.substringAfterLast('.', missingDelimiterValue = "")
        val baseName = if (extension.isBlank()) {
            originalName
        } else {
            originalName.removeSuffix(".$extension")
        }
        var index = 1
        while (true) {
            val candidateName = if (extension.isBlank()) {
                "$baseName ($index)"
            } else {
                "$baseName ($index).$extension"
            }
            val candidate = File(directory, candidateName)
            if (!candidate.exists()) {
                return candidate
            }
            index += 1
        }
    }

    /** 遍历队列中的目录和当前深度。 */
    private data class DirectoryScanNode(
        val file: File,
        val depth: Int
    )

    private companion object {
        const val TAG = "StorageManagement"
        const val DIGEST_BUFFER_BYTES = 128 * 1024
        const val MAX_DIRECTORY_DEPTH = 64
        const val ORGANIZE_ROOT_DIRECTORY = "Harley整理"
    }
}
