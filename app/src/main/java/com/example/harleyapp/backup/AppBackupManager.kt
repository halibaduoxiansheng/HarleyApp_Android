package com.example.harleyapp.backup

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import com.example.harleyapp.data.NotebookMediaBackupEntry
import com.example.harleyapp.data.NotebookMediaStore
import com.example.harleyapp.data.WebsiteBackgroundBackupEntry
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.data.isValidNotebookMediaFileName
import com.example.harleyapp.data.local.LocalDocumentEntity
import com.example.harleyapp.data.local.LocalDocumentStore
import com.example.harleyapp.model.isValidWebsiteBackgroundFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用户选择的备份文件内容摘要。
 *
 * @param createdAtMillis 备份创建时间。
 * @param encrypted 是否使用密码和AES-GCM加密。
 * @param preferenceFileCount 包含的本机设置文件数量。
 * @param preferenceValueCount 包含的设置键值数量。
 * @param localDocumentCount Room本地文档数量。
 * @param ledgerEntryCount 账目数量。
 * @param reminderCount 通知提醒数量。
 * @param fitnessRecordCount 运动历史天数。
 * @param websiteCount 网站快捷入口数量。
 * @param websiteBackgroundCount 随备份迁移的网站卡片背景图片数量。
 * @param notebookArticleCount 记事本文章数量。
 * @param notebookMediaCount 随备份迁移的文章图片和GIF数量。
 */
data class BackupPreview(
    val createdAtMillis: Long,
    val encrypted: Boolean,
    val preferenceFileCount: Int,
    val preferenceValueCount: Int,
    val localDocumentCount: Int,
    val ledgerEntryCount: Int,
    val reminderCount: Int,
    val fitnessRecordCount: Int,
    val websiteCount: Int,
    val websiteBackgroundCount: Int,
    val notebookArticleCount: Int,
    val notebookMediaCount: Int
)

/**
 * 一次本地备份、预览或恢复操作的结果。
 *
 * @param success 操作是否完整成功。
 * @param message 可直接显示给用户的中文结果说明。
 * @param preview 成功解析备份时的内容摘要，其他情况为null。
 * @param passwordRequired 是否因为文件已加密但未提供密码而暂停。
 */
data class BackupOperationResult(
    val success: Boolean,
    val message: String,
    val preview: BackupPreview? = null,
    val passwordRequired: Boolean = false
)

/**
 * 创建和恢复可跨手机传输的Harley本地备份文件。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面通过系统文件选择器取得Uri后调用[exportTo]导出，
 * 先调用[inspect]预览用户选择的文件，再经过明确确认调用[restoreFrom]恢复。恢复前本类会在
 * 应用私有目录写入一份安全快照；任何步骤失败都会尝试回滚，不会主动上传或共享文件。
 *
 * @param context Android上下文，内部只保存Application Context。
 */
class AppBackupManager(context: Context) {

    private val applicationContext = context.applicationContext
    private val documentStore = LocalDocumentStore.create(applicationContext)
    private val websiteBackgroundStore = WebsiteCardBackgroundStore(applicationContext)
    private val notebookMediaStore = NotebookMediaStore(applicationContext)

    /**
     * 把当前全部可迁移数据写入用户选择的文件。
     *
     * @param uri 系统CreateDocument返回的目标Uri。
     * @param password 可选备份密码；空字符串表示生成带SHA-256校验的明文JSON备份。
     * @return 导出结果和内容摘要。
     */
    suspend fun exportTo(uri: Uri, password: String): BackupOperationResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = capturePayload()
                val envelope = createEnvelope(payload, password)
                val output = applicationContext.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Unable to open backup output")
                output.buffered().use { stream ->
                    stream.write(envelope.toString().toByteArray(StandardCharsets.UTF_8))
                }
                BackupOperationResult(
                    success = true,
                    message = if (password.isBlank()) {
                        "本地备份已导出，请妥善保管文件"
                    } else {
                        "加密备份已导出，请牢记密码"
                    },
                    preview = buildPreview(payload, password.isNotBlank())
                )
            }.getOrElse { error ->
                Log.e(TAG, "Failed to export local backup", error)
                BackupOperationResult(
                    success = false,
                    message = "备份导出失败，请确认文件位置可写后重试"
                )
            }
        }
    }

    /**
     * 只读取并验证备份，不修改当前手机数据。
     *
     * @param uri 系统OpenDocument返回的备份Uri。
     * @param password 加密备份的密码；明文备份可传空字符串。
     * @return 完整性验证结果和内容摘要。
     */
    suspend fun inspect(uri: Uri, password: String): BackupOperationResult {
        return withContext(Dispatchers.IO) {
            parseBackup(uri, password).fold(
                onSuccess = { parsed ->
                    BackupOperationResult(
                        success = true,
                        message = "备份校验通过，可以确认导入",
                        preview = buildPreview(parsed.payload, parsed.encrypted)
                    )
                },
                onFailure = { error ->
                    parseFailure(error)
                }
            )
        }
    }

    /**
     * 恢复经过用户确认的备份文件。
     *
     * 恢复策略是“整机替换”：当前可迁移模块先保存安全快照，再用备份内容替换。传感器步数
     * 基线、微信未读通知键和监听器连接状态属于旧手机瞬时状态，不会跨手机恢复。
     *
     * @param uri 已经预览过的备份Uri。
     * @param password 加密备份密码。
     * @return 恢复结果；失败时会自动尝试回滚恢复前快照。
     */
    suspend fun restoreFrom(uri: Uri, password: String): BackupOperationResult {
        return withContext(Dispatchers.IO) {
            val parsed = parseBackup(uri, password).getOrElse { error ->
                return@withContext parseFailure(error)
            }
            val currentPayload = runCatching { capturePayload() }.getOrElse { error ->
                Log.e(TAG, "Failed to capture rollback backup", error)
                return@withContext BackupOperationResult(
                    success = false,
                    message = "无法生成导入前安全备份，已取消导入"
                )
            }

            if (!writeSafetyBackup(currentPayload)) {
                return@withContext BackupOperationResult(
                    success = false,
                    message = "无法保存导入前安全备份，已取消导入"
                )
            }

            if (!applyPayload(parsed.payload)) {
                val rollbackSuccess = applyPayload(currentPayload)
                return@withContext BackupOperationResult(
                    success = false,
                    message = if (rollbackSuccess) {
                        "导入失败，已经恢复导入前的数据"
                    } else {
                        "导入和自动回滚均失败，请保留备份文件并重新启动App"
                    }
                )
            }

            BackupOperationResult(
                success = true,
                message = "数据已恢复，提醒计划和页面内容将重新加载",
                preview = buildPreview(parsed.payload, parsed.encrypted)
            )
        }
    }

    /**
     * 采集当前可迁移设置和Room文档。
     *
     * @return 不包含密码、定位历史和网络缓存的备份载荷。
     */
    private fun capturePayload(): JSONObject {
        val preferencesJson = JSONObject()
        KNOWN_PREFERENCE_NAMES.forEach { preferenceName ->
            val preferences = applicationContext.getSharedPreferences(
                preferenceName,
                Context.MODE_PRIVATE
            )
            val valuesJson = JSONObject()
            preferences.all
                .filterKeys { key -> shouldExportPreference(preferenceName, key) }
                .toSortedMap()
                .forEach { (key, value) ->
                    encodePreferenceValue(value)?.let { encoded ->
                        valuesJson.put(key, encoded)
                    }
                }
            if (valuesJson.length() > 0) {
                preferencesJson.put(preferenceName, valuesJson)
            }
        }

        val documentsJson = JSONArray()
        documentStore.snapshotAll().forEach { document ->
            documentsJson.put(
                JSONObject()
                    .put(JSON_NAMESPACE, document.namespace)
                    .put(JSON_KEY, document.key)
                    .put(JSON_VALUE, document.value)
                    .put(JSON_UPDATED_AT, document.updatedAtMillis)
            )
        }
        val websiteBackgroundsJson = JSONArray()
        websiteBackgroundStore.snapshotForBackup().forEach { entry ->
            websiteBackgroundsJson.put(
                JSONObject()
                    .put(JSON_FILE_NAME, entry.fileName)
                    .put(
                        JSON_IMAGE_BASE64,
                        Base64.encodeToString(entry.bytes, Base64.NO_WRAP)
                    )
            )
        }
        val notebookMediaJson = JSONArray()
        notebookMediaStore.snapshotForBackup().forEach { entry ->
            notebookMediaJson.put(
                JSONObject()
                    .put(JSON_FILE_NAME, entry.fileName)
                    .put(
                        JSON_IMAGE_BASE64,
                        Base64.encodeToString(entry.bytes, Base64.NO_WRAP)
                    )
            )
        }

        return JSONObject()
            .put(JSON_FORMAT, PAYLOAD_FORMAT)
            .put(JSON_SCHEMA_VERSION, BACKUP_SCHEMA_VERSION)
            .put(JSON_PACKAGE_NAME, applicationContext.packageName)
            .put(JSON_CREATED_AT, System.currentTimeMillis())
            .put(JSON_PREFERENCES, preferencesJson)
            .put(JSON_DOCUMENTS, documentsJson)
            .put(JSON_WEBSITE_BACKGROUNDS, websiteBackgroundsJson)
            .put(JSON_NOTEBOOK_MEDIA, notebookMediaJson)
    }

    /**
     * 根据密码创建明文校验封套或AES-GCM加密封套。
     *
     * @param payload 完整备份载荷。
     * @param password 用户可选密码。
     * @return 可直接写入.harleybackup文件的JSON封套。
     */
    private fun createEnvelope(payload: JSONObject, password: String): JSONObject {
        val payloadBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        if (password.isBlank()) {
            return JSONObject()
                .put(JSON_FORMAT, ENVELOPE_FORMAT)
                .put(JSON_SCHEMA_VERSION, BACKUP_SCHEMA_VERSION)
                .put(JSON_ENCRYPTED, false)
                .put(JSON_SHA256, sha256Hex(payloadBytes))
                .put(JSON_PAYLOAD, payload)
        }

        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(ENVELOPE_AAD.toByteArray(StandardCharsets.UTF_8))
        val encryptedPayload = cipher.doFinal(payloadBytes)

        return JSONObject()
            .put(JSON_FORMAT, ENVELOPE_FORMAT)
            .put(JSON_SCHEMA_VERSION, BACKUP_SCHEMA_VERSION)
            .put(JSON_ENCRYPTED, true)
            .put(JSON_KDF, KDF_NAME)
            .put(JSON_ITERATIONS, PBKDF2_ITERATIONS)
            .put(JSON_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .put(JSON_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .put(JSON_CIPHERTEXT, Base64.encodeToString(encryptedPayload, Base64.NO_WRAP))
    }

    /**
     * 从文件读取、解密并校验备份载荷。
     *
     * @param uri 备份文件Uri。
     * @param password 用户输入密码。
     * @return 成功时返回解析结果，失败时保留明确异常类型供页面提示。
     */
    private fun parseBackup(uri: Uri, password: String): Result<ParsedBackup> {
        return runCatching {
            val bytes = readLimitedBytes(uri)
            val envelope = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(envelope.optString(JSON_FORMAT) == ENVELOPE_FORMAT) {
                "Unsupported backup format"
            }
            require(envelope.optInt(JSON_SCHEMA_VERSION) in 1..BACKUP_SCHEMA_VERSION) {
                "Unsupported backup version"
            }

            val encrypted = envelope.optBoolean(JSON_ENCRYPTED, false)
            val payload = if (encrypted) {
                if (password.isBlank()) {
                    throw BackupPasswordRequiredException()
                }
                decryptPayload(envelope, password)
            } else {
                val plainPayload = envelope.getJSONObject(JSON_PAYLOAD)
                val plainBytes = plainPayload.toString().toByteArray(StandardCharsets.UTF_8)
                require(
                    MessageDigest.isEqual(
                        envelope.getString(JSON_SHA256).lowercase().toByteArray(),
                        sha256Hex(plainBytes).toByteArray()
                    )
                ) {
                    "Backup checksum mismatch"
                }
                plainPayload
            }

            validatePayload(payload)
            ParsedBackup(payload = payload, encrypted = encrypted)
        }
    }

    /**
     * 解密AES-GCM载荷；认证失败通常表示密码错误或文件被修改。
     *
     * @param envelope 加密备份封套。
     * @param password 用户输入密码。
     * @return 解密后的备份载荷。
     */
    private fun decryptPayload(envelope: JSONObject, password: String): JSONObject {
        return runCatching {
            require(envelope.optString(JSON_KDF) == KDF_NAME)
            require(envelope.optInt(JSON_ITERATIONS) == PBKDF2_ITERATIONS)
            val salt = Base64.decode(envelope.getString(JSON_SALT), Base64.DEFAULT)
            val iv = Base64.decode(envelope.getString(JSON_IV), Base64.DEFAULT)
            val ciphertext = Base64.decode(envelope.getString(JSON_CIPHERTEXT), Base64.DEFAULT)
            require(salt.size == SALT_BYTES && iv.size == GCM_IV_BYTES)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(ENVELOPE_AAD.toByteArray(StandardCharsets.UTF_8))
            JSONObject(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
        }.getOrElse { error ->
            throw BackupPasswordInvalidException(error)
        }
    }

    /**
     * 校验载荷身份、版本和大小边界，阻止任意JSON覆盖本机数据。
     *
     * @param payload 待校验载荷。
     */
    private fun validatePayload(payload: JSONObject) {
        require(payload.optString(JSON_FORMAT) == PAYLOAD_FORMAT)
        require(payload.optString(JSON_PACKAGE_NAME) == applicationContext.packageName)
        require(payload.optInt(JSON_SCHEMA_VERSION) in 1..BACKUP_SCHEMA_VERSION)
        require(payload.has(JSON_PREFERENCES) && payload.has(JSON_DOCUMENTS))
        require(payload.getJSONObject(JSON_PREFERENCES).length() <= MAX_PREFERENCE_FILES)
        require(payload.getJSONArray(JSON_DOCUMENTS).length() <= MAX_DOCUMENT_COUNT)
        val backgrounds = payload.optJSONArray(JSON_WEBSITE_BACKGROUNDS) ?: JSONArray()
        require(backgrounds.length() <= WebsiteCardBackgroundStore.MAX_IMAGE_COUNT)
        decodeWebsiteBackgrounds(backgrounds)
        val notebookMedia = payload.optJSONArray(JSON_NOTEBOOK_MEDIA) ?: JSONArray()
        require(notebookMedia.length() <= NotebookMediaStore.MAX_MEDIA_COUNT)
        decodeNotebookMedia(notebookMedia)
    }

    /**
     * 把备份载荷替换到本机设置和Room数据库。
     *
     * @param payload 已通过完整性校验的载荷。
     * @return 所有设置和文档均成功写入时返回true。
     */
    @SuppressLint("UseKtx")
    private fun applyPayload(payload: JSONObject): Boolean {
        val preferencesJson = payload.getJSONObject(JSON_PREFERENCES)
        var preferencesSuccess = true

        KNOWN_PREFERENCE_NAMES.forEach { preferenceName ->
            val target = applicationContext.getSharedPreferences(
                preferenceName,
                Context.MODE_PRIVATE
            )
            val editor = target.edit().clear()
            val fileJson = preferencesJson.optJSONObject(preferenceName)
            if (fileJson != null) {
                fileJson.keys().asSequence().forEach { key ->
                    if (shouldExportPreference(preferenceName, key)) {
                        decodePreferenceValue(
                            editor = editor,
                            key = key,
                            encoded = fileJson.getJSONObject(key)
                        )
                    }
                }
            }
            preferencesSuccess = editor.commit() && preferencesSuccess
        }

        val importedDocuments = decodeDocuments(payload.getJSONArray(JSON_DOCUMENTS))
            .ifEmpty { buildDocumentsFromPreferences(preferencesJson) }
        val documentsSuccess = documentStore.replaceAll(importedDocuments)
        val backgroundsSuccess = websiteBackgroundStore.replaceFromBackup(
            decodeWebsiteBackgrounds(
                payload.optJSONArray(JSON_WEBSITE_BACKGROUNDS) ?: JSONArray()
            )
        )
        val notebookMediaSuccess = notebookMediaStore.replaceFromBackup(
            decodeNotebookMedia(
                payload.optJSONArray(JSON_NOTEBOOK_MEDIA) ?: JSONArray()
            )
        )

        return preferencesSuccess && documentsSuccess && backgroundsSuccess && notebookMediaSuccess
    }

    /**
     * 保存最近一次导入前安全快照到应用私有目录。
     *
     * @param payload 当前手机数据载荷。
     * @return 原子写入成功返回true，否则返回false。
     */
    private fun writeSafetyBackup(payload: JSONObject): Boolean {
        val directory = File(applicationContext.filesDir, SAFETY_BACKUP_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            return false
        }
        val atomicFile = AtomicFile(File(directory, SAFETY_BACKUP_FILE))
        var stream = atomicFile.startWrite()
        return runCatching {
            val bytes = createEnvelope(payload, "")
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
            stream.write(bytes)
            atomicFile.finishWrite(stream)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to persist import rollback backup", error)
            atomicFile.failWrite(stream)
            false
        }
    }

    /**
     * 读取文件并限制最大尺寸，避免异常文件耗尽内存。
     *
     * @param uri 用户选择的文件Uri。
     * @return 文件完整字节内容。
     */
    private fun readLimitedBytes(uri: Uri): ByteArray {
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open backup input")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "Backup file is too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    /**
     * 编码一个SharedPreferences值并保留原始类型。
     *
     * @param value 待备份值。
     * @return 带type和value字段的JSON；未知类型返回null。
     */
    private fun encodePreferenceValue(value: Any?): JSONObject? {
        val encoded = JSONObject()
        return when (value) {
            is String -> encoded.put(JSON_TYPE, TYPE_STRING).put(JSON_VALUE, value)
            is Int -> encoded.put(JSON_TYPE, TYPE_INT).put(JSON_VALUE, value)
            is Long -> encoded.put(JSON_TYPE, TYPE_LONG).put(JSON_VALUE, value)
            is Float -> encoded.put(JSON_TYPE, TYPE_FLOAT).put(JSON_VALUE, value.toDouble())
            is Boolean -> encoded.put(JSON_TYPE, TYPE_BOOLEAN).put(JSON_VALUE, value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>().sorted()
                if (strings.size != value.size) null else encoded
                    .put(JSON_TYPE, TYPE_STRING_SET)
                    .put(JSON_VALUE, JSONArray(strings))
            }
            else -> null
        }
    }

    /**
     * 把带类型的备份值写入SharedPreferences编辑器。
     *
     * @param editor 目标编辑器。
     * @param key 目标键名。
     * @param encoded 已校验备份值。
     */
    private fun decodePreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        encoded: JSONObject
    ) {
        when (encoded.getString(JSON_TYPE)) {
            TYPE_STRING -> editor.putString(key, encoded.getString(JSON_VALUE))
            TYPE_INT -> editor.putInt(key, encoded.getInt(JSON_VALUE))
            TYPE_LONG -> editor.putLong(key, encoded.getLong(JSON_VALUE))
            TYPE_FLOAT -> editor.putFloat(key, encoded.getDouble(JSON_VALUE).toFloat())
            TYPE_BOOLEAN -> editor.putBoolean(key, encoded.getBoolean(JSON_VALUE))
            TYPE_STRING_SET -> {
                val array = encoded.getJSONArray(JSON_VALUE)
                val values = buildSet {
                    for (index in 0 until array.length()) {
                        add(array.getString(index))
                    }
                }
                editor.putStringSet(key, values)
            }
            else -> error("Unsupported preference type")
        }
    }

    /**
     * 解码备份中的Room文档并拒绝超大或重复主键。
     *
     * @param documentsJson 文档数组。
     * @return 可交给Room事务恢复的文档列表。
     */
    private fun decodeDocuments(documentsJson: JSONArray): List<LocalDocumentEntity> {
        val identities = mutableSetOf<String>()
        return buildList {
            for (index in 0 until documentsJson.length()) {
                val item = documentsJson.getJSONObject(index)
                val namespace = item.getString(JSON_NAMESPACE)
                val key = item.getString(JSON_KEY)
                val value = item.getString(JSON_VALUE)
                require(namespace in STRUCTURED_PREFERENCE_NAMES)
                require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH)
                require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES)
                require(identities.add("$namespace\u0000$key"))
                add(
                    LocalDocumentEntity(
                        namespace = namespace,
                        key = key,
                        value = value,
                        updatedAtMillis = item.optLong(JSON_UPDATED_AT, 0L).coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    /**
     * 解码并限制备份中的网站卡片背景图片。
     *
     * @param backgroundsJson 包含安全文件名和Base64 JPEG内容的数组；旧备份传空数组。
     * @return 可交给WebsiteCardBackgroundStore原子替换的图片列表。
     */
    private fun decodeWebsiteBackgrounds(
        backgroundsJson: JSONArray
    ): List<WebsiteBackgroundBackupEntry> {
        val usedFileNames = mutableSetOf<String>()
        var totalBytes = 0L
        return buildList {
            for (index in 0 until backgroundsJson.length()) {
                val item = backgroundsJson.getJSONObject(index)
                val fileName = item.getString(JSON_FILE_NAME)
                val bytes = Base64.decode(item.getString(JSON_IMAGE_BASE64), Base64.DEFAULT)
                require(isValidWebsiteBackgroundFileName(fileName))
                require(usedFileNames.add(fileName))
                require(bytes.size <= WebsiteCardBackgroundStore.MAX_SINGLE_IMAGE_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= WebsiteCardBackgroundStore.MAX_TOTAL_IMAGE_BYTES)
                add(WebsiteBackgroundBackupEntry(fileName = fileName, bytes = bytes))
            }
        }
    }

    /**
     * 解码并限制备份中的记事本图片和GIF。
     *
     * 使用方法：
     * 载荷校验和正式恢复均调用本函数。每个条目必须使用记事本媒体仓库生成的安全文件名，
     * 文件数量、单文件大小和总容量都不能超过[NotebookMediaStore]声明的边界。
     *
     * @param mediaJson 包含安全文件名和Base64图片内容的数组；旧版本备份传空数组。
     * @return 可交给[NotebookMediaStore.replaceFromBackup]原子替换的媒体列表。
     */
    private fun decodeNotebookMedia(
        mediaJson: JSONArray
    ): List<NotebookMediaBackupEntry> {
        val usedFileNames = mutableSetOf<String>()
        var totalBytes = 0L
        return buildList {
            for (index in 0 until mediaJson.length()) {
                val item = mediaJson.getJSONObject(index)
                val fileName = item.getString(JSON_FILE_NAME)
                val bytes = Base64.decode(item.getString(JSON_IMAGE_BASE64), Base64.DEFAULT)
                require(isValidNotebookMediaFileName(fileName))
                require(usedFileNames.add(fileName))
                require(bytes.isNotEmpty() && bytes.size <= NotebookMediaStore.MAX_SINGLE_MEDIA_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= NotebookMediaStore.MAX_TOTAL_MEDIA_BYTES)
                add(NotebookMediaBackupEntry(fileName = fileName, bytes = bytes))
            }
        }
    }

    /**
     * 兼容尚未使用Room的旧备份：把结构化SharedPreferences字符串转换为Room文档。
     *
     * @param preferencesJson 旧备份设置对象。
     * @return 可恢复到Room的文档列表。
     */
    private fun buildDocumentsFromPreferences(preferencesJson: JSONObject): List<LocalDocumentEntity> {
        return buildList {
            STRUCTURED_PREFERENCE_NAMES.forEach { namespace ->
                val fileJson = preferencesJson.optJSONObject(namespace) ?: return@forEach
                fileJson.keys().asSequence().forEach { key ->
                    val encoded = fileJson.getJSONObject(key)
                    if (encoded.optString(JSON_TYPE) == TYPE_STRING) {
                        add(
                            LocalDocumentEntity(
                                namespace = namespace,
                                key = key,
                                value = encoded.getString(JSON_VALUE),
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 生成供用户确认的模块计数摘要。
     *
     * @param payload 已校验载荷。
     * @param encrypted 文件是否加密。
     * @return 备份内容摘要。
     */
    private fun buildPreview(payload: JSONObject, encrypted: Boolean): BackupPreview {
        val preferences = payload.getJSONObject(JSON_PREFERENCES)
        val documents = payload.getJSONArray(JSON_DOCUMENTS)
        val websiteBackgrounds = payload.optJSONArray(JSON_WEBSITE_BACKGROUNDS) ?: JSONArray()
        val notebookMedia = payload.optJSONArray(JSON_NOTEBOOK_MEDIA) ?: JSONArray()
        val roomValues = buildMap {
            for (index in 0 until documents.length()) {
                val item = documents.getJSONObject(index)
                put(
                    "${item.optString(JSON_NAMESPACE)}\u0000${item.optString(JSON_KEY)}",
                    item.optString(JSON_VALUE)
                )
            }
        }
        fun storedString(namespace: String, key: String): String? {
            roomValues["$namespace\u0000$key"]?.let { return it }
            val encoded = preferences.optJSONObject(namespace)?.optJSONObject(key) ?: return null
            return encoded.takeIf { it.optString(JSON_TYPE) == TYPE_STRING }
                ?.optString(JSON_VALUE)
        }
        fun arrayCount(namespace: String, key: String): Int {
            val value = storedString(namespace, key) ?: return 0
            return runCatching { JSONArray(value).length() }.getOrDefault(0)
        }

        var preferenceValueCount = 0
        preferences.keys().asSequence().forEach { name ->
            preferenceValueCount += preferences.optJSONObject(name)?.length() ?: 0
        }

        return BackupPreview(
            createdAtMillis = payload.optLong(JSON_CREATED_AT, 0L),
            encrypted = encrypted,
            preferenceFileCount = preferences.length(),
            preferenceValueCount = preferenceValueCount,
            localDocumentCount = documents.length(),
            ledgerEntryCount = arrayCount(PREF_LEDGER, KEY_LEDGER_ENTRIES),
            reminderCount = arrayCount(PREF_REMINDERS, KEY_REMINDERS),
            fitnessRecordCount = arrayCount(PREF_FITNESS, KEY_FITNESS_RECORDS),
            websiteCount = arrayCount(PREF_WEBSITES, KEY_WEBSITES),
            websiteBackgroundCount = websiteBackgrounds.length(),
            notebookArticleCount = arrayCount(PREF_NOTEBOOK, KEY_NOTEBOOK_ARTICLES),
            notebookMediaCount = notebookMedia.length()
        )
    }

    /**
     * 根据异常类型生成稳定用户提示，详细堆栈只写英文Logcat。
     *
     * @param error 解析或解密异常。
     * @return 页面可显示的失败结果。
     */
    private fun parseFailure(error: Throwable): BackupOperationResult {
        Log.e(TAG, "Failed to inspect local backup", error)
        return when (error) {
            is BackupPasswordRequiredException -> BackupOperationResult(
                success = false,
                message = "该备份已加密，请输入备份密码后重新校验",
                passwordRequired = true
            )
            is BackupPasswordInvalidException -> BackupOperationResult(
                success = false,
                message = "密码错误，或备份文件已经损坏"
            )
            else -> BackupOperationResult(
                success = false,
                message = "无法识别该备份，请确认文件来自同一款Harley App"
            )
        }
    }

    /**
     * 使用PBKDF2-HMAC-SHA256从用户密码派生AES密钥。
     *
     * @param password 用户密码。
     * @param salt 每个文件随机生成的盐。
     * @return 256位AES密钥。
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KDF_NAME).generateSecret(spec).encoded
            SecretKeySpec(encoded, AES_ALGORITHM)
        } finally {
            spec.clearPassword()
        }
    }

    /** @return 输入字节的十六进制SHA-256摘要。 */
    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** @return true表示该设置允许跨手机迁移。 */
    private fun shouldExportPreference(preferenceName: String, key: String): Boolean {
        return key !in EXCLUDED_KEYS[preferenceName].orEmpty()
    }

    private data class ParsedBackup(
        val payload: JSONObject,
        val encrypted: Boolean
    )

    private class BackupPasswordRequiredException : IllegalArgumentException()

    private class BackupPasswordInvalidException(cause: Throwable) : IllegalArgumentException(cause)

    private companion object {
        const val TAG = "AppBackupManager"
        const val BACKUP_SCHEMA_VERSION = 1
        const val ENVELOPE_FORMAT = "harley_backup_envelope"
        const val PAYLOAD_FORMAT = "harley_local_data"
        const val ENVELOPE_AAD = "harley_backup_v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_ALGORITHM = "AES"
        const val KDF_NAME = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 210_000
        const val AES_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val SALT_BYTES = 16
        // 图片经过Base64编码且加密备份会再次编码，128MB可覆盖当前全部受控媒体上限。
        const val MAX_BACKUP_BYTES = 128 * 1024 * 1024
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
        const val MAX_DOCUMENT_COUNT = 100
        const val MAX_PREFERENCE_FILES = 30
        const val MAX_KEY_LENGTH = 128
        const val SAFETY_BACKUP_DIRECTORY = "backup_safety"
        const val SAFETY_BACKUP_FILE = "last_before_import.harleybackup"

        const val JSON_FORMAT = "format"
        const val JSON_SCHEMA_VERSION = "schema_version"
        const val JSON_PACKAGE_NAME = "package_name"
        const val JSON_CREATED_AT = "created_at"
        const val JSON_ENCRYPTED = "encrypted"
        const val JSON_SHA256 = "sha256"
        const val JSON_PAYLOAD = "payload"
        const val JSON_KDF = "kdf"
        const val JSON_ITERATIONS = "iterations"
        const val JSON_SALT = "salt"
        const val JSON_IV = "iv"
        const val JSON_CIPHERTEXT = "ciphertext"
        const val JSON_PREFERENCES = "preferences"
        const val JSON_DOCUMENTS = "documents"
        const val JSON_WEBSITE_BACKGROUNDS = "website_card_backgrounds"
        const val JSON_NOTEBOOK_MEDIA = "notebook_media"
        const val JSON_FILE_NAME = "file_name"
        const val JSON_IMAGE_BASE64 = "image_base64"
        const val JSON_NAMESPACE = "namespace"
        const val JSON_KEY = "key"
        const val JSON_VALUE = "value"
        const val JSON_UPDATED_AT = "updated_at"
        const val JSON_TYPE = "type"

        const val TYPE_STRING = "string"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_STRING_SET = "string_set"

        const val PREF_APPEARANCE = "harley_appearance"
        const val PREF_COMPANION = "harley_companion"
        const val PREF_FITNESS = "harley_fitness"
        const val PREF_HOME_FEATURES = "harley_home_features"
        const val PREF_LEDGER = "harley_ledger"
        const val PREF_REMINDERS = "scheduled_reminders"
        const val PREF_SHORTCUTS = "harley_shortcuts"
        const val PREF_WEBSITES = "harley_websites"
        const val PREF_WEBSITE_TOOLS = "harley_website_tools"
        const val PREF_NOTEBOOK = "harley_notebook"
        const val DOCUMENT_ENGLISH_WORD_PROGRESS = "harley_english_words"
        const val PREF_WECHAT_REMINDER = "harley_wechat_message_reminder"
        const val PREF_WECHAT_LEGACY = "harley_wechat_auto_reply"
        const val PREF_CLEANUP = "harley_local_cleanup"
        const val PREF_WEATHER = "harley_weather"

        const val KEY_LEDGER_ENTRIES = "entries"
        const val KEY_REMINDERS = "reminders_json"
        const val KEY_FITNESS_RECORDS = "records"
        const val KEY_WEBSITES = "websites"
        const val KEY_NOTEBOOK_ARTICLES = "articles_v1"

        val KNOWN_PREFERENCE_NAMES = listOf(
            PREF_APPEARANCE,
            PREF_COMPANION,
            PREF_FITNESS,
            PREF_HOME_FEATURES,
            PREF_LEDGER,
            PREF_REMINDERS,
            PREF_SHORTCUTS,
            PREF_WEBSITES,
            PREF_WEBSITE_TOOLS,
            PREF_NOTEBOOK,
            PREF_WECHAT_REMINDER,
            PREF_WECHAT_LEGACY,
            PREF_CLEANUP,
            PREF_WEATHER
        )

        val STRUCTURED_PREFERENCE_NAMES = setOf(
            PREF_FITNESS,
            PREF_LEDGER,
            PREF_REMINDERS,
            PREF_WEBSITES,
            PREF_WEBSITE_TOOLS,
            PREF_NOTEBOOK,
            DOCUMENT_ENGLISH_WORD_PROGRESS
        )

        val EXCLUDED_KEYS = mapOf(
            PREF_FITNESS to setOf("last_sensor_total", "last_sensor_day"),
            PREF_WECHAT_REMINDER to setOf(
                "pending_notification_keys",
                "last_message_at",
                "last_reminder_at",
                "next_reminder_at",
                "notifications_shown_in_cycle",
                "listener_connected",
                "listener_changed_at"
            ),
            // 天气包含近似定位坐标和短期网络缓存，不应随备份转移到另一台手机。
            PREF_WEATHER to setOf(
                "latitude",
                "longitude",
                "temperature",
                "apparent_temperature",
                "humidity",
                "weather_code",
                "wind_speed",
                "max_temperature",
                "min_temperature",
                "precipitation_probability",
                "fetched_at"
            )
        )

        val secureRandom = SecureRandom()
    }
}
