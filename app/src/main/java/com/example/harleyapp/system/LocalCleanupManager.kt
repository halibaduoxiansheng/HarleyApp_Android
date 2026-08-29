package com.example.harleyapp.system

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安全统计并清理Harley生活助手自身缓存和过期待确认微信通知。
 *
 * 使用方法：
 * 使用Application Context和LedgerRepository创建实例。App启动时调用runAutomaticCleanupIfDue；
 * “更多”页面调用cleanNow执行手动清理。该类只访问context.cacheDir、externalCacheDir和本应用账本，
 * 不尝试结束其他App或删除其他App文件，手机运行内存仍由Android系统管理。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 * @param ledgerRepository 本机账目仓库，用于删除超过保留期限的待确认通知摘要。
 */
class LocalCleanupManager(
    context: Context,
    private val ledgerRepository: LedgerRepository
) {

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取自动清理开关和最近一次结果。
     *
     * @return 可直接显示在设置页面的LocalCleanupStatus。
     */
    fun getStatus(): LocalCleanupStatus {
        return LocalCleanupStatus(
            automaticEnabled = preferences.getBoolean(KEY_AUTOMATIC_ENABLED, true),
            lastCleanupAtMillis = preferences.getLong(KEY_LAST_CLEANUP_AT, 0L),
            lastFreedBytes = preferences.getLong(KEY_LAST_FREED_BYTES, 0L)
        )
    }

    /**
     * 设置是否在App启动时自动检查过期缓存。
     *
     * @param enabled true表示启用每天最多一次的启动检查，false表示仅允许手动清理。
     *
     * @return 同步保存成功返回true，否则返回false。
     */
    fun setAutomaticEnabled(enabled: Boolean): Boolean {
        val success = preferences.edit()
            .putBoolean(KEY_AUTOMATIC_ENABLED, enabled)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist automatic cleanup setting")
        }
        return success
    }

    /**
     * 统计本应用内部和外部缓存目录当前占用空间。
     *
     * @return 可安全清理的缓存字节数；读取异常时忽略对应文件并继续统计。
     */
    suspend fun getReclaimableBytes(): Long = withContext(Dispatchers.IO) {
        cacheRoots().sumOf(::directorySizeSafely)
    }

    /**
     * App启动时按开关和24小时间隔清理七天前的缓存文件。
     *
     * @param nowMillis 当前系统时间戳，默认使用实时值，测试时可传入固定值。
     *
     * @return 执行清理时返回结果；开关关闭或距离上次不足24小时时返回null。
     */
    suspend fun runAutomaticCleanupIfDue(
        nowMillis: Long = System.currentTimeMillis()
    ): LocalCleanupResult? {
        val status = getStatus()
        if (!status.automaticEnabled ||
            nowMillis - status.lastCleanupAtMillis < AUTOMATIC_CHECK_INTERVAL_MILLIS
        ) {
            return null
        }

        return cleanInternal(
            deleteBeforeMillis = nowMillis - AUTOMATIC_CACHE_RETENTION_MILLIS,
            nowMillis = nowMillis
        )
    }

    /**
     * 手动清理本应用全部缓存和超过90天的待确认微信通知摘要。
     *
     * @return 本次释放空间、删除摘要数量或错误信息。
     */
    suspend fun cleanNow(): LocalCleanupResult {
        val nowMillis = System.currentTimeMillis()
        return cleanInternal(
            deleteBeforeMillis = nowMillis + 1L,
            nowMillis = nowMillis
        )
    }

    /**
     * 在后台线程执行限定目录的缓存清理并保存结果。
     *
     * @param deleteBeforeMillis 只删除最后修改时间早于该值的文件。
     * @param nowMillis 本次清理状态记录时间戳。
     *
     * @return 本次清理结果。
     */
    private suspend fun cleanInternal(
        deleteBeforeMillis: Long,
        nowMillis: Long
    ): LocalCleanupResult = withContext(Dispatchers.IO) {
        runCatching {
            val beforeBytes = cacheRoots().sumOf(::directorySizeSafely)
            cacheRoots().forEach { root ->
                deleteDirectoryContentsSafely(
                    root = root,
                    deleteBeforeMillis = deleteBeforeMillis
                )
            }
            val afterBytes = cacheRoots().sumOf(::directorySizeSafely)
            val freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L)
            val removedCaptures = ledgerRepository.cleanExpiredCaptures()
            val saved = preferences.edit()
                .putLong(KEY_LAST_CLEANUP_AT, nowMillis)
                .putLong(KEY_LAST_FREED_BYTES, freedBytes)
                .commit()
            if (!saved) {
                Log.e(TAG, "Failed to persist cleanup status")
            }
            Log.i(TAG, "Local app cache cleanup completed")

            LocalCleanupResult(
                freedBytes = freedBytes,
                removedExpiredCaptures = removedCaptures
            )
        }.getOrElse { error ->
            Log.e(TAG, "Local app cache cleanup failed", error)
            LocalCleanupResult(errorMessage = "本App缓存清理失败，请稍后重试")
        }
    }

    /**
     * 返回本应用允许清理的缓存根目录并去重。
     *
     * @return context.cacheDir和可用externalCacheDir组成的列表。
     */
    private fun cacheRoots(): List<File> {
        return listOfNotNull(applicationContext.cacheDir, applicationContext.externalCacheDir)
            .distinctBy { it.absolutePath }
    }

    /**
     * 递归计算目录中的普通文件大小。
     *
     * @param root 本应用缓存根目录。
     *
     * @return 可读取文件大小之和；目录不存在时返回0。
     */
    private fun directorySizeSafely(root: File): Long {
        if (!root.exists()) {
            return 0L
        }

        return runCatching {
            root.walkTopDown()
                .filter(File::isFile)
                .sumOf(File::length)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to calculate app cache size", error)
            0L
        }
    }

    /**
     * 只在指定缓存根目录内部删除满足时间条件的文件和空子目录。
     *
     * @param root Android返回的本应用缓存根目录，根目录自身不会删除。
     * @param deleteBeforeMillis 允许删除的最晚文件修改时间。
     *
     * @return 无返回值；单个文件删除失败时继续处理其余文件。
     */
    private fun deleteDirectoryContentsSafely(root: File, deleteBeforeMillis: Long) {
        if (!root.exists() || !root.isDirectory) {
            return
        }

        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        root.walkBottomUp().forEach { candidate ->
            if (candidate == root) {
                return@forEach
            }
            val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull()
                ?: return@forEach
            val insideRoot = canonicalCandidate.path.startsWith(
                canonicalRoot.path + File.separator
            )
            if (!insideRoot) {
                Log.w(TAG, "Skipped cache entry outside canonical root")
                return@forEach
            }

            when {
                canonicalCandidate.isFile &&
                    canonicalCandidate.lastModified() < deleteBeforeMillis -> {
                    if (!canonicalCandidate.delete()) {
                        Log.w(TAG, "Failed to delete an app cache file")
                    }
                }
                canonicalCandidate.isDirectory &&
                    canonicalCandidate.listFiles().isNullOrEmpty() -> {
                    canonicalCandidate.delete()
                }
            }
        }
    }

    private companion object {
        const val TAG = "LocalCleanup"
        const val PREFERENCE_NAME = "harley_local_cleanup"
        const val KEY_AUTOMATIC_ENABLED = "automatic_enabled"
        const val KEY_LAST_CLEANUP_AT = "last_cleanup_at"
        const val KEY_LAST_FREED_BYTES = "last_freed_bytes"
        const val AUTOMATIC_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
        const val AUTOMATIC_CACHE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}
