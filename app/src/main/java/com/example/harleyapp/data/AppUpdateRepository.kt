package com.example.harleyapp.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.example.harleyapp.model.AppUpdateCheckResult
import com.example.harleyapp.model.AppUpdateDownloadPhase
import com.example.harleyapp.model.AppUpdateDownloadState
import com.example.harleyapp.model.AppUpdateInfo
import com.example.harleyapp.model.AppUpdateInstallResult
import com.example.harleyapp.model.compareNumericVersions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 负责检查OTA清单、交给Android DownloadManager后台下载APK并打开系统安装界面。
 *
 * 使用方法：
 * 1. 在IO协程中调用checkForUpdate并根据结果询问用户。
 * 2. 用户确认后调用enqueueUpdate，将返回的下载编号交给queryDownloadState轮询进度。
 * 3. 下载成功后调用openInstaller。Android 8及以上若未允许“安装未知应用”，函数会先打开设置页，
 *    用户授权并返回后再次调用即可打开安装确认界面。
 *
 * @param context Android上下文，内部统一转换为Application Context。
 */
class AppUpdateRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val downloadManager = applicationContext.getSystemService(DownloadManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 从固定HTTP地址读取OTA清单并与当前版本进行数字段比较。
     *
     * @param currentVersion 当前安装包的versionName。
     *
     * @return 远端版本更高时返回UpdateAvailable；相等或更低返回UpToDate；网络或格式异常返回Failure。
     */
    suspend fun checkForUpdate(currentVersion: String): AppUpdateCheckResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val manifestJson = downloadUpdateManifest()
                val latestVersion = manifestJson.optString(JSON_LATEST_VERSION).trim()
                val firmwareUrl = manifestJson.optString(JSON_FIRMWARE_URL).trim()
                val versionComparison = compareNumericVersions(latestVersion, currentVersion)

                require(latestVersion.isNotBlank()) {
                    "OTA清单缺少latest_version"
                }
                require(versionComparison != null) {
                    "版本号必须使用数字和英文句点，例如1.2.3"
                }
                require(isSupportedDownloadUrl(firmwareUrl)) {
                    "firmware_url必须是有效的HTTP或HTTPS地址"
                }

                if (versionComparison > 0) {
                    AppUpdateCheckResult.UpdateAvailable(
                        AppUpdateInfo(
                            latestVersion = latestVersion,
                            firmwareUrl = firmwareUrl
                        )
                    )
                } else {
                    AppUpdateCheckResult.UpToDate(latestVersion = latestVersion)
                }
            }.getOrElse { error ->
                Log.e(TAG, "Failed to check app update", error)
                AppUpdateCheckResult.Failure(
                    message = error.message.orEmpty().ifBlank {
                        "检查更新失败，请稍后重试"
                    }
                )
            }
        }
    }

    /**
     * 创建可跨页面和后台继续运行的系统下载任务。
     *
     * @param updateInfo 用户已经确认下载的远端版本和APK地址。
     *
     * @return 成功时包含DownloadManager编号；地址或系统服务异常时返回失败Result。
     */
    fun enqueueUpdate(updateInfo: AppUpdateInfo): Result<Long> {
        return runCatching {
            require(isSupportedDownloadUrl(updateInfo.firmwareUrl)) {
                "下载地址必须使用HTTP或HTTPS协议"
            }
            require(updateInfo.latestVersion.isNotBlank()) {
                "目标版本不能为空"
            }

            val previousDownloadId = getTrackedDownloadId()
            if (previousDownloadId != null) {
                downloadManager.remove(previousDownloadId)
            }

            val fileName = buildDownloadFileName(updateInfo.latestVersion)
            val destinationDirectory = applicationContext.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            ) ?: error("无法创建App专属下载目录")
            val destinationFile = File(destinationDirectory, fileName)
            val request = DownloadManager.Request(Uri.parse(updateInfo.firmwareUrl))
                .setTitle("Harley生活助手 ${updateInfo.latestVersion}")
                .setDescription("正在后台下载应用更新")
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    applicationContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )

            val downloadId = downloadManager.enqueue(request)
            val saved = preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_TARGET_VERSION, updateInfo.latestVersion)
                .putString(KEY_FIRMWARE_URL, updateInfo.firmwareUrl)
                .putString(KEY_LOCAL_FILE_PATH, destinationFile.absolutePath)
                .commit()
            if (!saved) {
                downloadManager.remove(downloadId)
                error("无法保存下载任务，请重试")
            }

            downloadId
        }.onFailure { error ->
            Log.e(TAG, "Failed to enqueue app update", error)
        }
    }

    /**
     * 读取当前持久化下载编号对应的最新进度。
     *
     * @return 没有下载任务时返回IDLE；存在任务时返回DownloadManager报告的阶段和字节进度。
     */
    fun getTrackedDownloadState(): AppUpdateDownloadState {
        val downloadId = getTrackedDownloadId() ?: return AppUpdateDownloadState()
        return queryDownloadState(downloadId)
    }

    /**
     * 查询指定系统下载任务的阶段、字节数和失败原因。
     *
     * @param downloadId DownloadManager任务编号。
     *
     * @return 可供Compose进度条直接使用的下载状态。
     */
    fun queryDownloadState(downloadId: Long): AppUpdateDownloadState {
        val targetVersion = preferences.getString(KEY_TARGET_VERSION, "").orEmpty()
        val query = DownloadManager.Query().setFilterById(downloadId)

        return runCatching {
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use AppUpdateDownloadState(
                        downloadId = downloadId,
                        targetVersion = targetVersion,
                        phase = AppUpdateDownloadPhase.FAILED,
                        failureMessage = "下载记录不存在，请重新检查更新"
                    )
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                val downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    )
                ).coerceAtLeast(0L)
                val totalBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                )
                val phase = when (status) {
                    DownloadManager.STATUS_PENDING -> AppUpdateDownloadPhase.PENDING
                    DownloadManager.STATUS_RUNNING -> AppUpdateDownloadPhase.RUNNING
                    DownloadManager.STATUS_PAUSED -> AppUpdateDownloadPhase.PAUSED
                    DownloadManager.STATUS_SUCCESSFUL -> AppUpdateDownloadPhase.SUCCESSFUL
                    DownloadManager.STATUS_FAILED -> AppUpdateDownloadPhase.FAILED
                    else -> AppUpdateDownloadPhase.FAILED
                }

                AppUpdateDownloadState(
                    downloadId = downloadId,
                    targetVersion = targetVersion,
                    phase = phase,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    failureMessage = if (phase == AppUpdateDownloadPhase.FAILED) {
                        "下载失败（系统代码 $reason），请重新检查更新"
                    } else {
                        ""
                    }
                )
            } ?: AppUpdateDownloadState(
                downloadId = downloadId,
                targetVersion = targetVersion,
                phase = AppUpdateDownloadPhase.FAILED,
                failureMessage = "无法连接系统下载服务"
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to query app update download", error)
            AppUpdateDownloadState(
                downloadId = downloadId,
                targetVersion = targetVersion,
                phase = AppUpdateDownloadPhase.FAILED,
                failureMessage = "读取下载进度失败，请稍后重试"
            )
        }
    }

    /**
     * 取消并清除当前由本App创建的OTA下载任务。
     *
     * @return 系统下载记录已移除且本地状态已清除时返回true，否则返回false。
     */
    fun cancelTrackedDownload(): Boolean {
        return removeTrackedDownload(operationName = "cancel")
    }

    /**
     * 删除已经下载完成但用户不准备安装的OTA安装包。
     *
     * 使用方法：
     * 仅在[getTrackedDownloadState]返回SUCCESSFUL且用户在界面确认删除后调用。函数会移除
     * DownloadManager任务和对应文件，并清空本App保存的下载编号、版本、地址及本地路径。
     * 删除范围严格限制为本App专属Downloads目录中的HarleyApp APK。
     *
     * @return 系统下载记录、本地APK和持久化状态均已清理返回true；任何一步失败返回false。
     */
    fun deleteTrackedDownload(): Boolean {
        return removeTrackedDownload(operationName = "delete")
    }

    /**
     * 校验下载APK并打开Android系统安装确认界面。
     *
     * 使用方法：
     * 仅在queryDownloadState返回SUCCESSFUL后调用。函数会验证APK包名、versionName和versionCode；
     * Android 8及以上未允许本App安装未知来源应用时会先打开对应系统设置页。
     *
     * @param downloadId 已完成的DownloadManager任务编号。
     *
     * @return 安装界面已打开、需要授权或明确失败原因。
     */
    fun openInstaller(downloadId: Long): AppUpdateInstallResult {
        val state = queryDownloadState(downloadId)
        if (state.phase != AppUpdateDownloadPhase.SUCCESSFUL) {
            return AppUpdateInstallResult.Failure("更新包尚未下载完成")
        }

        val validationFailure = validateDownloadedApk(downloadId, state.targetVersion)
        if (validationFailure != null) {
            return AppUpdateInstallResult.Failure(validationFailure)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !applicationContext.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${applicationContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                applicationContext.startActivity(settingsIntent)
                AppUpdateInstallResult.PermissionRequired
            }.getOrElse { error ->
                Log.e(TAG, "Failed to open unknown app sources settings", error)
                AppUpdateInstallResult.Failure("无法打开安装权限设置")
            }
        }

        val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
            ?: return AppUpdateInstallResult.Failure("无法读取已下载的更新包")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(apkUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return runCatching {
            applicationContext.startActivity(installIntent)
            AppUpdateInstallResult.InstallerOpened
        }.getOrElse { error ->
            Log.e(TAG, "Failed to open package installer", error)
            AppUpdateInstallResult.Failure("系统无法打开APK安装界面")
        }
    }

    /**
     * 读取SharedPreferences中仍由本App跟踪的DownloadManager编号。
     *
     * @return 有效正数编号；从未开始下载时返回null。
     */
    private fun getTrackedDownloadId(): Long? {
        val storedId = preferences.getLong(KEY_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)
        return storedId.takeIf { downloadId -> downloadId > 0L }
    }

    /**
     * 统一移除当前OTA下载任务、App专属APK文件和跟踪状态。
     *
     * @param operationName 写入英文日志的操作名称，只允许cancel或delete等固定调用方文本。
     *
     * @return 三部分状态均已清理返回true；系统服务、文件系统或SharedPreferences失败返回false。
     */
    private fun removeTrackedDownload(operationName: String): Boolean {
        val downloadId = getTrackedDownloadId()
        val storedFilePath = preferences.getString(KEY_LOCAL_FILE_PATH, null)
        val downloadRecordRemoved = if (downloadId == null) {
            true
        } else {
            runCatching {
                downloadManager.remove(downloadId)
                true
            }.getOrElse { error ->
                Log.e(TAG, "Failed to $operationName app update download record", error)
                false
            }
        }
        val localFileRemoved = deleteTrackedApkFile(storedFilePath)
        val stateCleared = preferences.edit().clear().commit().also { success ->
            if (!success) {
                Log.e(TAG, "Failed to clear app update download state")
            }
        }

        return downloadRecordRemoved && localFileRemoved && stateCleared
    }

    /**
     * 在DownloadManager记录缺失或系统未清理文件时，安全删除App专属目录中的已跟踪APK。
     *
     * @param storedFilePath 下载开始时保存的绝对文件路径；没有路径时无需补充删除。
     *
     * @return 文件不存在或成功删除返回true；路径越界、名称异常或删除失败返回false。
     */
    private fun deleteTrackedApkFile(storedFilePath: String?): Boolean {
        if (storedFilePath.isNullOrBlank()) {
            return true
        }

        return runCatching {
            val downloadDirectory = applicationContext.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS
            )?.canonicalFile ?: return@runCatching false
            val trackedFile = File(storedFilePath).canonicalFile
            val isExpectedFile = trackedFile.parentFile == downloadDirectory &&
                trackedFile.name.startsWith(DOWNLOAD_FILE_PREFIX) &&
                trackedFile.name.endsWith(APK_FILE_SUFFIX, ignoreCase = true)
            if (!isExpectedFile) {
                Log.e(TAG, "Rejected app update file deletion outside managed directory")
                return@runCatching false
            }

            !trackedFile.exists() || trackedFile.delete()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to delete tracked app update file", error)
            false
        }
    }

    /**
     * 通过HttpURLConnection读取并解析服务器OTA JSON，限制响应大小以避免异常大响应占用内存。
     *
     * @return 已解析的JSONObject。
     */
    private fun downloadUpdateManifest(): JSONObject {
        val connection = URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "HarleyApp-Android-Update")

            val responseCode = connection.responseCode
            require(responseCode in 200..299) {
                "更新服务器返回HTTP $responseCode"
            }
            JSONObject(readLimitedUtf8(connection.inputStream))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 读取最多64KiB UTF-8文本，超过上限立即拒绝。
     *
     * @param inputStream HTTP响应输入流。
     *
     * @return 完整UTF-8文本。
     */
    private fun readLimitedUtf8(inputStream: java.io.InputStream): String {
        inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            var totalBytes = 0

            while (true) {
                val readBytes = stream.read(buffer)
                if (readBytes < 0) {
                    break
                }
                totalBytes += readBytes
                require(totalBytes <= MAX_MANIFEST_BYTES) {
                    "OTA清单内容过大"
                }
                output.write(buffer, 0, readBytes)
            }

            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    /**
     * 校验下载文件确实是本App更高版本的APK，避免错误链接打开其他应用安装包。
     *
     * @param downloadId DownloadManager任务编号。
     * @param targetVersion OTA清单声明的目标版本。
     *
     * @return 校验通过返回null，否则返回可直接展示的失败原因。
     */
    private fun validateDownloadedApk(downloadId: Long, targetVersion: String): String? {
        val filePath = getDownloadedFilePath(downloadId)
            ?: return "无法定位已下载的APK文件"
        val archiveInfo = getArchivePackageInfo(filePath)
            ?: return "下载文件不是有效的Android APK"
        val currentInfo = runCatching {
            applicationContext.packageManager.getPackageInfo(
                applicationContext.packageName,
                0
            )
        }.getOrNull() ?: return "无法读取当前应用版本"

        if (archiveInfo.packageName != applicationContext.packageName) {
            return "更新包的应用包名与当前App不一致"
        }
        if (archiveInfo.versionName.orEmpty() != targetVersion) {
            return "更新包版本与服务器latest_version不一致"
        }

        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        val currentVersionCode = PackageInfoCompat.getLongVersionCode(currentInfo)
        if (archiveVersionCode <= currentVersionCode) {
            return "更新包versionCode必须高于当前版本"
        }

        return null
    }

    /**
     * 从DownloadManager记录中获取App专属下载目录内的真实文件路径。
     *
     * @param downloadId DownloadManager任务编号。
     *
     * @return APK绝对路径；记录缺失或不是file URI时返回null。
     */
    private fun getDownloadedFilePath(downloadId: Long): String? {
        val storedPath = preferences.getString(KEY_LOCAL_FILE_PATH, null)
            ?.takeIf { filePath -> File(filePath).isFile }
        if (storedPath != null) {
            return storedPath
        }

        val query = DownloadManager.Query().setFilterById(downloadId)
        return downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }
            val localUri = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            ).orEmpty()
            Uri.parse(localUri).takeIf { uri -> uri.scheme == "file" }?.path
        }
    }

    /**
     * 兼容不同Android版本读取未安装APK的PackageInfo。
     *
     * @param filePath APK绝对路径。
     *
     * @return 可读取到的包信息；文件无效时返回null。
     */
    private fun getArchivePackageInfo(filePath: String): android.content.pm.PackageInfo? {
        if (!File(filePath).isFile) {
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.packageManager.getPackageArchiveInfo(
                filePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.packageManager.getPackageArchiveInfo(filePath, 0)
        }
    }

    /**
     * 判断字符串是否为DownloadManager支持的HTTP或HTTPS绝对地址。
     *
     * @param url 待验证地址。
     *
     * @return 协议和主机均有效时返回true。
     */
    private fun isSupportedDownloadUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme.orEmpty().lowercase()
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    /**
     * 为每次下载生成不冲突且不包含路径字符的APK文件名。
     *
     * @param version 目标版本名称。
     *
     * @return App专属Downloads目录中的安全文件名。
     */
    private fun buildDownloadFileName(version: String): String {
        val safeVersion = version.replace(Regex("[^0-9A-Za-z._-]"), "_")
        return "$DOWNLOAD_FILE_PREFIX$safeVersion-${System.currentTimeMillis()}$APK_FILE_SUFFIX"
    }

    private companion object {
        const val TAG = "AppUpdateRepository"
        const val UPDATE_MANIFEST_URL = "http://www.halibaduo.cn/harley_app.json"
        const val JSON_LATEST_VERSION = "latest_version"
        const val JSON_FIRMWARE_URL = "firmware_url"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val DOWNLOAD_FILE_PREFIX = "HarleyApp-"
        const val APK_FILE_SUFFIX = ".apk"
        const val PREFERENCE_NAME = "harley_app_update"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_TARGET_VERSION = "target_version"
        const val KEY_FIRMWARE_URL = "firmware_url"
        const val KEY_LOCAL_FILE_PATH = "local_file_path"
        const val INVALID_DOWNLOAD_ID = -1L
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
        const val MAX_MANIFEST_BYTES = 64 * 1_024
    }
}
