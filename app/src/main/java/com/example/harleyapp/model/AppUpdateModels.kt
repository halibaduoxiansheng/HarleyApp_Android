package com.example.harleyapp.model

/**
 * OTA清单中描述的一项可下载应用版本。
 *
 * 使用方法：
 * AppUpdateRepository读取服务器JSON后创建本对象，界面只在latestVersion严格高于当前版本时
 * 展示升级确认弹窗；firmwareUrl仅允许HTTP或HTTPS地址。
 *
 * @param latestVersion 服务器声明的最新可用版本名称。
 * @param firmwareUrl 对应Android APK的下载地址。
 */
data class AppUpdateInfo(
    val latestVersion: String,
    val firmwareUrl: String
)

/**
 * 检查服务器OTA清单后的业务结果。
 *
 * 使用方法：
 * 界面根据具体子类型分别显示升级确认、已经最新或错误提示，不应仅通过异常文本猜测状态。
 */
sealed interface AppUpdateCheckResult {

    /** 服务器版本严格高于当前版本，可以询问用户是否下载。 */
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult

    /** 服务器版本等于或低于当前版本，不执行下载。 */
    data class UpToDate(val latestVersion: String) : AppUpdateCheckResult

    /** 网络、JSON字段或版本号格式不符合要求。 */
    data class Failure(val message: String) : AppUpdateCheckResult
}

/**
 * DownloadManager中一项OTA下载的当前阶段。
 */
enum class AppUpdateDownloadPhase {
    IDLE,
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED
}

/**
 * 可供Compose界面展示的OTA下载状态。
 *
 * @param downloadId Android DownloadManager分配的下载编号；没有任务时为null。
 * @param targetVersion 本次下载准备安装的版本名称。
 * @param phase 当前下载阶段。
 * @param downloadedBytes 已经下载的字节数。
 * @param totalBytes 服务端报告的总字节数，未知时为-1。
 * @param failureMessage 下载失败时显示的中文原因，其他状态为空。
 */
data class AppUpdateDownloadState(
    val downloadId: Long? = null,
    val targetVersion: String = "",
    val phase: AppUpdateDownloadPhase = AppUpdateDownloadPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val failureMessage: String = ""
) {
    /**
     * 计算确定总大小时的下载比例。
     *
     * @return 0到1之间的进度；总大小未知时返回null，界面应显示不确定进度条。
     */
    fun progressFraction(): Float? {
        if (totalBytes <= 0L) {
            return null
        }

        return (downloadedBytes.toDouble() / totalBytes.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

/**
 * 请求系统安装下载APK后的结果。
 */
sealed interface AppUpdateInstallResult {

    /** 已成功打开Android系统安装确认界面。 */
    data object InstallerOpened : AppUpdateInstallResult

    /** 已打开“允许来自此来源”设置页，用户授权后需要再次点击安装。 */
    data object PermissionRequired : AppUpdateInstallResult

    /** APK无效、下载未完成或没有可处理安装请求的系统组件。 */
    data class Failure(val message: String) : AppUpdateInstallResult
}

/**
 * 按数字段比较两个对用户展示的版本名称。
 *
 * 使用方法：
 * 支持`1`、`1.0`、`1.0.3`及带前缀`v2.1`的形式。比较时末尾缺失数字按0处理，
 * 因此`1.0`与`1.0.0`相等，`1.10`高于`1.9`。为了避免错误升级，不接受字母后缀或空段。
 *
 * @param firstVersion 第一个版本名称。
 * @param secondVersion 第二个版本名称。
 *
 * @return 第一个版本更高返回正数，更低返回负数，相等返回0；格式无效返回null。
 */
fun compareNumericVersions(
    firstVersion: String,
    secondVersion: String
): Int? {
    val firstParts = parseNumericVersion(firstVersion) ?: return null
    val secondParts = parseNumericVersion(secondVersion) ?: return null
    val longestSize = maxOf(firstParts.size, secondParts.size)

    repeat(longestSize) { index ->
        val firstPart = firstParts.getOrElse(index) { "0" }
        val secondPart = secondParts.getOrElse(index) { "0" }
        val partComparison = compareNumericVersionPart(firstPart, secondPart)
        if (partComparison != 0) {
            return partComparison
        }
    }

    return 0
}

/**
 * 判断服务器版本是否严格高于当前版本。
 *
 * @param currentVersion 当前安装版本名称。
 * @param latestVersion 服务器OTA清单中的版本名称。
 *
 * @return 两个版本格式有效且服务器版本更高时返回true，否则返回false。
 */
fun isRemoteVersionNewer(
    currentVersion: String,
    latestVersion: String
): Boolean {
    return compareNumericVersions(latestVersion, currentVersion)?.let { comparison ->
        comparison > 0
    } == true
}

/**
 * 把版本名称拆分为不会溢出的纯数字字符串列表。
 *
 * @param version 待解析版本名称。
 *
 * @return 解析成功的数字段；格式无效返回null。
 */
private fun parseNumericVersion(version: String): List<String>? {
    val normalized = version.trim().removePrefix("v").removePrefix("V")
    if (!NUMERIC_VERSION_PATTERN.matches(normalized)) {
        return null
    }

    return normalized.split('.').map { part ->
        part.trimStart('0').ifEmpty { "0" }
    }
}

/**
 * 比较两个已经规范化的非负整数字符串，不转换为Long以避免超长数字溢出。
 *
 * @param firstPart 第一个数字段。
 * @param secondPart 第二个数字段。
 *
 * @return 第一个数字段更大返回正数，更小返回负数，相等返回0。
 */
private fun compareNumericVersionPart(firstPart: String, secondPart: String): Int {
    if (firstPart.length != secondPart.length) {
        return firstPart.length.compareTo(secondPart.length)
    }

    return firstPart.compareTo(secondPart)
}

private val NUMERIC_VERSION_PATTERN = Regex("^\\d+(?:\\.\\d+)*$")
