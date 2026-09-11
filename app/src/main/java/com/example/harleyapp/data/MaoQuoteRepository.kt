package com.example.harleyapp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.harleyapp.BuildConfig
import com.example.harleyapp.model.MAX_MAO_QUOTE_FILE_BYTES
import com.example.harleyapp.model.MaoQuoteDocument
import com.example.harleyapp.model.MaoQuoteFailureReason
import com.example.harleyapp.model.MaoQuoteImportResult
import com.example.harleyapp.model.MaoQuoteLoadResult
import com.example.harleyapp.model.MaoQuoteContentSource
import com.example.harleyapp.model.MaoQuoteParseResult
import com.example.harleyapp.model.MaoQuoteReadingPosition
import com.example.harleyapp.model.normalizeMaoQuoteFavoriteIds
import com.example.harleyapp.model.normalizeMaoQuoteReadingPosition
import com.example.harleyapp.model.parseMaoQuoteUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 管理毛主席语录TXT的本地导入、许可受控内置资产、收藏和最后阅读位置。
 *
 * 使用方法：
 * 使用Application Context创建实例，在协程中调用[load]读取内容。用户通过系统文件选择器选定TXT后
 * 调用[importFromUri]，仓库会严格校验UTF-8与2MB上限，再把文件复制到App私有目录；因此后续无需依赖
 * 原Uri权限。页面通过[getFavoriteIds]、[setFavorite]、[getReadingPosition]和[saveReadingPosition]
 * 维护轻量状态。
 *
 * 内容加载顺序固定为“用户导入文件优先，其次可选的assets/mao_quotes.txt”。Release构建读取内置
 * 资产时必须在TXT头部同时填写`@source:`、`@license:`并明确声明
 * `@redistribution-authorized: true`，且完整文件SHA-256必须进入代码审核白名单；Debug构建允许加载
 * 未完成声明的开发占位资产，但页面仍可根据[MaoQuoteContentSource.BUNDLED_DEVELOPMENT]显示非生产提示。
 *
 * @param context Android上下文，内部只保留Application Context。
 */
class MaoQuoteRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )
    private val importedDirectory = File(applicationContext.filesDir, IMPORTED_DIRECTORY_NAME)
    private val importedFile = File(importedDirectory, IMPORTED_FILE_NAME)
    private val importedTemporaryFile = File(importedDirectory, "$IMPORTED_FILE_NAME.tmp")
    private val importedBackupFile = File(importedDirectory, "$IMPORTED_FILE_NAME.backup")
    private val importedFileMutex = Mutex()
    private val favoriteLock = Any()

    /**
     * 读取当前可用语录内容。
     *
     * 使用方法：
     * 页面首次进入或导入/清除文件后，在LaunchedEffect等协程环境调用。若私有导入文件存在，仓库不会
     * 静默回退到内置资产掩盖损坏，而是返回该文件的具体错误，让用户决定重新导入或清除。
     *
     * @return 成功时包含文档和实际来源；没有导入文件且未打包资产时返回Empty；其余情况返回明确失败。
     */
    suspend fun load(): MaoQuoteLoadResult = withContext(Dispatchers.IO) {
        importedFileMutex.withLock {
            if (!recoverInterruptedImport()) {
                return@withLock MaoQuoteLoadResult.Failure(
                    reason = MaoQuoteFailureReason.READ_FAILED,
                    message = "上次语录导入未完成，旧内容恢复失败"
                )
            }
            if (importedFile.isFile) {
                return@withLock loadImportedFile()
            }

            loadBundledAsset()
        }
    }

    /**
     * 从系统文件选择器返回的Uri导入UTF-8 TXT，并持久复制到App私有目录。
     *
     * 新文件完成解析之前不会改动旧导入文件；解析成功后先写临时文件，再用备份交换方式安装，尽量避免
     * 进程中断导致原内容丢失。导入成功会清除已不属于新文档的收藏和阅读位置。
     *
     * @param uri 用户明确选择且当前拥有读取权限的Content Uri。
     * @return 成功时包含可立即显示的文档；失败时包含大小、编码、结构、读取或保存原因。
     */
    suspend fun importFromUri(uri: Uri): MaoQuoteImportResult = withContext(Dispatchers.IO) {
        val bytes = try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                readBytesWithLimit(input)
            } ?: return@withContext MaoQuoteImportResult.Failure(
                reason = MaoQuoteFailureReason.READ_FAILED,
                message = "无法读取所选TXT文件"
            )
        } catch (_: MaoQuoteFileTooLargeException) {
            return@withContext MaoQuoteImportResult.Failure(
                reason = MaoQuoteFailureReason.FILE_TOO_LARGE,
                message = "TXT文件过大，请选择不超过2MB的文件"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to read imported Mao quote file", error)
            return@withContext MaoQuoteImportResult.Failure(
                reason = MaoQuoteFailureReason.READ_FAILED,
                message = "无法读取所选TXT文件"
            )
        }

        when (val parseResult = parseMaoQuoteUtf8(bytes)) {
            is MaoQuoteParseResult.Failure -> MaoQuoteImportResult.Failure(
                reason = parseResult.reason,
                message = parseResult.message
            )

            is MaoQuoteParseResult.Success -> {
                val persisted = importedFileMutex.withLock {
                    recoverInterruptedImport() && persistImportedBytes(bytes)
                }
                if (!persisted) {
                    MaoQuoteImportResult.Failure(
                        reason = MaoQuoteFailureReason.SAVE_FAILED,
                        message = "TXT校验成功，但无法保存到本机"
                    )
                } else {
                    normalizeStoredReadingState(parseResult.document)
                    MaoQuoteImportResult.Success(parseResult.document)
                }
            }
        }
    }

    /**
     * 清除用户导入的私有TXT，使下次[load]重新尝试读取可选内置资产。
     *
     * @return 文件原本不存在或已成功删除时返回true；删除失败时记录英文日志并返回false。
     */
    suspend fun clearImportedContent(): Boolean = withContext(Dispatchers.IO) {
        val success = importedFileMutex.withLock {
            listOf(importedFile, importedTemporaryFile, importedBackupFile)
                .map { file -> !file.exists() || file.delete() }
                .all { deleted -> deleted }
        }
        if (!success) {
            Log.e(TAG, "Failed to delete imported Mao quote file")
        }
        success
    }

    /**
     * 判断App私有目录中是否存在已导入TXT。
     *
     * @return 存在普通文件时返回true；目录缺失、路径异常或文件已清除时返回false。
     */
    fun hasImportedContent(): Boolean = importedFile.isFile

    /**
     * 读取收藏语录id集合，并可选地按当前文档清除失效id。
     *
     * @param document 当前文档；传null时返回持久化原始集合，传入文档时只返回仍存在的语录id。
     * @return 与SharedPreferences内部集合脱离的不可变副本。
     */
    fun getFavoriteIds(document: MaoQuoteDocument? = null): Set<String> {
        val storedIds = preferences.getStringSet(KEY_FAVORITE_IDS, emptySet())
            .orEmpty()
            .toSet()
        return if (document == null) {
            storedIds
        } else {
            normalizeMaoQuoteFavoriteIds(document, storedIds)
        }
    }

    /**
     * 新增或移除一个收藏语录id。
     *
     * @param quoteId [com.example.harleyapp.model.MaoQuote.id]提供的稳定标识。
     * @param favorite true表示加入收藏，false表示移除收藏。
     * @return 参数有效且同步写入成功时返回true；无效id或写入失败时返回false。
     */
    fun setFavorite(quoteId: String, favorite: Boolean): Boolean {
        if (!MAO_QUOTE_ID_PATTERN.matches(quoteId)) return false
        return synchronized(favoriteLock) {
            val updatedIds = getFavoriteIds().toMutableSet().apply {
                if (favorite) add(quoteId) else remove(quoteId)
            }
            val success = preferences.edit()
                .putStringSet(KEY_FAVORITE_IDS, updatedIds)
                .commit()
            if (!success) {
                Log.e(TAG, "Failed to persist Mao quote favorites")
            }
            success
        }
    }

    /**
     * 读取最后阅读位置，并可选地对当前文档执行有效性校验。
     *
     * @param document 当前文档；传入后会丢弃已不存在的语录id并修正字符偏移，null表示仅解码原始位置。
     * @return 可恢复位置；首次阅读、持久化数据损坏或目标已不存在时返回null。
     */
    fun getReadingPosition(document: MaoQuoteDocument? = null): MaoQuoteReadingPosition? {
        val quoteId = preferences.getString(KEY_READING_QUOTE_ID, null)
            ?.takeIf(MAO_QUOTE_ID_PATTERN::matches)
            ?: return null
        val position = MaoQuoteReadingPosition(
            quoteId = quoteId,
            characterOffset = preferences.getInt(KEY_READING_CHARACTER_OFFSET, 0).coerceAtLeast(0)
        )
        return if (document == null) position else normalizeMaoQuoteReadingPosition(document, position)
    }

    /**
     * 保存或清除最后阅读位置。
     *
     * @param position 当前可见语录及字符偏移；传null会清除两个位置字段。
     * @return 参数有效且同步写入成功时返回true，否则返回false并在写入失败时记录英文日志。
     */
    fun saveReadingPosition(position: MaoQuoteReadingPosition?): Boolean {
        if (position != null &&
            (!MAO_QUOTE_ID_PATTERN.matches(position.quoteId) || position.characterOffset < 0)
        ) {
            return false
        }
        val editor = preferences.edit()
        if (position == null) {
            editor.remove(KEY_READING_QUOTE_ID)
            editor.remove(KEY_READING_CHARACTER_OFFSET)
        } else {
            editor.putString(KEY_READING_QUOTE_ID, position.quoteId)
            editor.putInt(KEY_READING_CHARACTER_OFFSET, position.characterOffset)
        }
        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to persist Mao quote reading position")
        }
        return success
    }

    /** 读取并解析优先级最高的用户私有导入文件。 */
    private fun loadImportedFile(): MaoQuoteLoadResult {
        val bytes = try {
            importedFile.inputStream().buffered().use { input ->
                readBytesWithLimit(input)
            }
        } catch (_: MaoQuoteFileTooLargeException) {
            return MaoQuoteLoadResult.Failure(
                reason = MaoQuoteFailureReason.FILE_TOO_LARGE,
                message = "已导入TXT超过2MB，请清除后重新导入"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to read persisted Mao quote file", error)
            return MaoQuoteLoadResult.Failure(
                reason = MaoQuoteFailureReason.READ_FAILED,
                message = "已导入TXT无法读取，请重新导入"
            )
        }
        return parseLoadResult(bytes, MaoQuoteContentSource.IMPORTED)
    }

    /** 读取可选内置资产，并执行Release再分发授权门禁。 */
    private fun loadBundledAsset(): MaoQuoteLoadResult {
        val bytes = try {
            applicationContext.assets.open(BUNDLED_ASSET_NAME).buffered().use { input ->
                readBytesWithLimit(input)
            }
        } catch (_: FileNotFoundException) {
            return MaoQuoteLoadResult.Empty("尚未导入语录TXT")
        } catch (_: MaoQuoteFileTooLargeException) {
            return MaoQuoteLoadResult.Failure(
                reason = MaoQuoteFailureReason.FILE_TOO_LARGE,
                message = "内置语录TXT超过2MB安全上限"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Failed to read bundled Mao quote asset", error)
            return MaoQuoteLoadResult.Failure(
                reason = MaoQuoteFailureReason.READ_FAILED,
                message = "内置语录TXT无法读取"
            )
        }

        val parseResult = parseMaoQuoteUtf8(bytes)
        if (parseResult is MaoQuoteParseResult.Failure) {
            return MaoQuoteLoadResult.Failure(parseResult.reason, parseResult.message)
        }
        val document = (parseResult as MaoQuoteParseResult.Success).document
        // Release门禁必须同时具备明确授权布尔值、来源和许可说明，避免只写一个true就把尚未完成
        // 来源审核的开发文件误标为可生产分发内容；同时要求文件哈希进入代码审核清单，不能只靠
        // TXT自声明绕过。用户自行导入的私有文件不经过这一门禁。
        val hasCompleteRedistributionDeclaration =
            document.metadata.redistributionAuthorized &&
                document.metadata.source.isNotBlank() &&
                document.metadata.license.isNotBlank()
        val assetSha256 = bytes.sha256Hex()
        val isAuthorizedBundledAsset =
            hasCompleteRedistributionDeclaration && assetSha256 in AUTHORIZED_BUNDLED_ASSET_SHA256
        if (!BuildConfig.DEBUG && !isAuthorizedBundledAsset) {
            Log.e(TAG, "Bundled Mao quote asset is not approved for redistribution")
            return MaoQuoteLoadResult.Failure(
                reason = MaoQuoteFailureReason.REDISTRIBUTION_NOT_AUTHORIZED,
                message = "内置内容缺少完整来源或再分发许可，Release版本已拒绝加载"
            )
        }
        val source = if (isAuthorizedBundledAsset) {
            MaoQuoteContentSource.BUNDLED_AUTHORIZED
        } else {
            MaoQuoteContentSource.BUNDLED_DEVELOPMENT
        }
        normalizeStoredReadingState(document)
        return MaoQuoteLoadResult.Success(document, source)
    }

    /** 把解析结果转换为Repository加载结果，并在成功时清理失效轻量状态。 */
    private fun parseLoadResult(
        bytes: ByteArray,
        source: MaoQuoteContentSource
    ): MaoQuoteLoadResult {
        return when (val result = parseMaoQuoteUtf8(bytes)) {
            is MaoQuoteParseResult.Failure -> MaoQuoteLoadResult.Failure(
                reason = result.reason,
                message = result.message
            )

            is MaoQuoteParseResult.Success -> {
                normalizeStoredReadingState(result.document)
                MaoQuoteLoadResult.Success(result.document, source)
            }
        }
    }

    /**
     * 受限读取输入流，达到上限后立即终止，避免先分配无限ByteArray。
     *
     * @param input 任意TXT输入流，调用方负责use关闭。
     * @return 不超过[MAX_MAO_QUOTE_FILE_BYTES]的完整字节。
     */
    private fun readBytesWithLimit(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val readBytes = input.read(buffer)
            if (readBytes < 0) break
            totalBytes += readBytes
            if (totalBytes > MAX_MAO_QUOTE_FILE_BYTES) throw MaoQuoteFileTooLargeException()
            output.write(buffer, 0, readBytes)
        }
        return output.toByteArray()
    }

    /**
     * 把已验证字节以“临时文件、旧文件备份、新文件切换”的顺序安装到私有目录。
     *
     * @param bytes 已通过parseMaoQuoteUtf8校验的原始UTF-8字节。
     * @return 新文件完成切换返回true；任一步失败会尝试恢复旧文件并返回false。
     */
    private fun persistImportedBytes(bytes: ByteArray): Boolean {
        return runCatching {
            require(importedDirectory.exists() || importedDirectory.mkdirs())
            if (importedTemporaryFile.exists()) require(importedTemporaryFile.delete())
            if (importedBackupFile.exists()) require(importedBackupFile.delete())

            FileOutputStream(importedTemporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }

            val hadExistingFile = importedFile.exists()
            if (hadExistingFile) require(importedFile.renameTo(importedBackupFile))
            if (!importedTemporaryFile.renameTo(importedFile)) {
                if (hadExistingFile) importedBackupFile.renameTo(importedFile)
                error("Unable to install imported Mao quote file")
            }
            if (importedBackupFile.exists() && !importedBackupFile.delete()) {
                Log.w(TAG, "Failed to delete old Mao quote backup")
            }
            true
        }.getOrElse { error ->
            importedTemporaryFile.delete()
            if (!importedFile.exists() && importedBackupFile.exists()) {
                importedBackupFile.renameTo(importedFile)
            }
            Log.e(TAG, "Failed to persist imported Mao quote file", error)
            false
        }
    }

    /**
     * 恢复上次进程中断留下的备份，并清理不再需要的临时文件。
     *
     * @return 当前正式导入文件可安全继续使用时返回true；旧文件存在但无法恢复时返回false。
     */
    private fun recoverInterruptedImport(): Boolean {
        if (importedFile.isFile) {
            if (importedBackupFile.exists() && !importedBackupFile.delete()) {
                Log.w(TAG, "Failed to delete stale Mao quote backup")
            }
            if (importedTemporaryFile.exists() && !importedTemporaryFile.delete()) {
                Log.w(TAG, "Failed to delete stale Mao quote temporary file")
            }
            return true
        }

        if (importedBackupFile.isFile && !importedBackupFile.renameTo(importedFile)) {
            Log.e(TAG, "Failed to restore interrupted Mao quote import")
            return false
        }
        if (importedTemporaryFile.exists() && !importedTemporaryFile.delete()) {
            Log.w(TAG, "Failed to delete interrupted Mao quote temporary file")
        }
        return true
    }

    /** 清除与新文档不再对应的收藏和最后阅读位置，不影响仍然有效的稳定id。 */
    private fun normalizeStoredReadingState(document: MaoQuoteDocument) {
        synchronized(favoriteLock) {
            val storedFavorites = getFavoriteIds()
            val normalizedFavorites = normalizeMaoQuoteFavoriteIds(document, storedFavorites)
            if (normalizedFavorites != storedFavorites) {
                val success = preferences.edit()
                    .putStringSet(KEY_FAVORITE_IDS, normalizedFavorites)
                    .commit()
                if (!success) Log.e(TAG, "Failed to normalize Mao quote favorites")
            }
        }
        val storedPosition = getReadingPosition()
        val normalizedPosition = normalizeMaoQuoteReadingPosition(document, storedPosition)
        if (storedPosition != normalizedPosition) {
            saveReadingPosition(normalizedPosition)
        }
    }

    /** 仅用于中断超限字节流读取，不携带正文或文件路径。 */
    private class MaoQuoteFileTooLargeException : Exception()

    /** 计算内置资产完整SHA-256十六进制值，结果仅用于发布许可白名单匹配。 */
    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    private companion object {
        const val TAG = "MaoQuoteRepository"
        const val PREFERENCE_NAME = "harley_mao_quotes"
        const val KEY_FAVORITE_IDS = "favorite_ids"
        const val KEY_READING_QUOTE_ID = "reading_quote_id"
        const val KEY_READING_CHARACTER_OFFSET = "reading_character_offset"
        const val IMPORTED_DIRECTORY_NAME = "mao_quotes"
        const val IMPORTED_FILE_NAME = "imported.txt"
        const val BUNDLED_ASSET_NAME = "mao_quotes.txt"
        const val HEX_DIGITS = "0123456789ABCDEF"
        val MAO_QUOTE_ID_PATTERN = Regex("^quote-[0-9a-f]{24}$")

        // 当前尚未为任何内置正文完成生产再分发许可审核，因此白名单必须保持为空。后续取得许可时，
        // 需要同时迁移经审核资产、补齐TXT元数据，并在代码审查中加入该文件的完整SHA-256。
        val AUTHORIZED_BUNDLED_ASSET_SHA256: Set<String> = emptySet()
    }
}
