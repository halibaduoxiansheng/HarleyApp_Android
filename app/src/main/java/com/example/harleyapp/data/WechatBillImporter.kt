package com.example.harleyapp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.harleyapp.model.BillImportResult
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.LedgerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 读取用户通过系统文件选择器授权的微信账单CSV或未加密ZIP，并批量导入本地账本。
 *
 * 使用方法：
 * 使用Application Context和LedgerRepository创建实例，在协程中调用importFromUri。
 * 微信加密ZIP需要用户先用账单密码解压，再选择其中CSV文件。
 *
 * @param context Android上下文，内部使用Application Context的ContentResolver。
 * @param ledgerRepository 本地账目仓库。
 */
class WechatBillImporter(
    context: Context,
    private val ledgerRepository: LedgerRepository
) {

    private val applicationContext = context.applicationContext

    /**
     * 从用户选择的文件读取、解析、去重并保存微信账单。
     *
     * @param uri 系统文件选择器返回的只读Content Uri。
     *
     * @return 包含扫描、新增、更新、跳过数量或错误信息的导入结果。
     */
    suspend fun importFromUri(uri: Uri): BillImportResult = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryDisplayName(uri)
            val sourceBytes = applicationContext.contentResolver
                .openInputStream(uri)
                ?.use { inputStream -> readStreamWithLimit(inputStream, MAX_SOURCE_BYTES) }
                ?: return@withContext BillImportResult(errorMessage = "无法读取所选文件")
            val billBytes = if (isZipFile(fileName, sourceBytes)) {
                extractCsvFromZip(sourceBytes)
            } else {
                sourceBytes
            }
            val parseResult = parseCsvText(decodeBillText(billBytes))

            if (parseResult.errorMessage.isNotBlank()) {
                return@withContext BillImportResult(
                    scannedRows = parseResult.scannedRows,
                    skippedRows = parseResult.skippedRows,
                    errorMessage = parseResult.errorMessage
                )
            }

            val saveResult = ledgerRepository.importEntries(parseResult.entries)
            saveResult.copy(
                scannedRows = parseResult.scannedRows,
                skippedRows = parseResult.skippedRows + saveResult.skippedRows
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to import WeChat bill", error)
            val message = when (error) {
                is ZipException -> "ZIP可能已加密，请先用微信提供的密码解压，再选择CSV文件"
                is FileTooLargeException -> "账单文件过大，请按较短时间范围重新导出"
                else -> "账单导入失败：${error.message ?: "文件格式不支持"}"
            }
            BillImportResult(errorMessage = message)
        }
    }

    /**
     * 解析微信账单CSV文本。
     *
     * 使用方法：
     * importFromUri内部调用；单元测试也可直接提供脱敏账单文本验证解析规则。
     *
     * @param text 已完成字符集转换的CSV全文。
     *
     * @return 解析后的账目、扫描行数、跳过行数和错误信息。
     */
    internal fun parseCsvText(text: String): WechatBillParseResult {
        val records = parseCsvRecords(text)
        val headerIndex = records.indexOfFirst { record ->
            record.any { normalizeHeader(it) == HEADER_TRANSACTION_TIME } &&
                record.any { normalizeHeader(it) == HEADER_DIRECTION } &&
                record.any { normalizeHeader(it).startsWith(HEADER_AMOUNT) }
        }

        if (headerIndex < 0) {
            return WechatBillParseResult(errorMessage = "未找到微信账单明细表头，请选择官方导出的CSV")
        }

        val header = records[headerIndex].map(::normalizeHeader)
        val columnMap = header.withIndex().associate { it.value to it.index }
        val timeIndex = columnMap[HEADER_TRANSACTION_TIME] ?: return WechatBillParseResult(
            errorMessage = "账单缺少交易时间列"
        )
        val directionIndex = columnMap[HEADER_DIRECTION] ?: return WechatBillParseResult(
            errorMessage = "账单缺少收支方向列"
        )
        val amountIndex = header.indexOfFirst { it.startsWith(HEADER_AMOUNT) }
        if (amountIndex < 0) {
            return WechatBillParseResult(errorMessage = "账单缺少金额列")
        }

        val entries = mutableListOf<LedgerEntry>()
        var scannedRows = 0
        var skippedRows = 0

        records.drop(headerIndex + 1).forEach { row ->
            if (row.size <= maxOf(timeIndex, directionIndex, amountIndex)) {
                return@forEach
            }

            val timeText = row.getOrBlank(timeIndex)
            if (timeText.isBlank()) {
                return@forEach
            }
            scannedRows++

            val parsedEntry = parseBillRow(
                row = row,
                columnMap = columnMap,
                timeIndex = timeIndex,
                directionIndex = directionIndex,
                amountIndex = amountIndex
            )
            if (parsedEntry == null) {
                skippedRows++
            } else {
                entries.add(parsedEntry)
            }
        }

        return WechatBillParseResult(
            entries = entries,
            scannedRows = scannedRows,
            skippedRows = skippedRows,
            errorMessage = if (scannedRows == 0) "账单中没有可读取的交易记录" else ""
        )
    }

    /**
     * 解析账单中的一行交易。
     *
     * @param row 已按CSV规则拆分的字段。
     * @param columnMap 标题到列下标的映射。
     * @param timeIndex 交易时间列下标。
     * @param directionIndex 收支方向列下标。
     * @param amountIndex 金额列下标。
     *
     * @return 可导入LedgerEntry；金额、时间或方向无法识别时返回null。
     */
    private fun parseBillRow(
        row: List<String>,
        columnMap: Map<String, Int>,
        timeIndex: Int,
        directionIndex: Int,
        amountIndex: Int
    ): LedgerEntry? {
        val timeText = row.getOrBlank(timeIndex)
        val dateTime = parseTransactionTime(timeText) ?: return null
        val amountCents = parseAmountCents(row.getOrBlank(amountIndex)) ?: return null
        if (amountCents <= 0L) {
            return null
        }

        val tradeType = row.valueForHeader(columnMap, HEADER_TRANSACTION_TYPE)
        val direction = row.getOrBlank(directionIndex)
        val counterparty = row.valueForHeader(columnMap, HEADER_COUNTERPARTY)
        val product = row.valueForHeader(columnMap, HEADER_PRODUCT)
        val paymentMethod = row.valueForHeader(columnMap, HEADER_PAYMENT_METHOD)
        val status = row.valueForHeader(columnMap, HEADER_STATUS)
        val transactionId = row.valueForHeader(columnMap, HEADER_TRANSACTION_ID)
        val merchantId = row.valueForHeader(columnMap, HEADER_MERCHANT_ID)
        val remark = row.valueForHeader(columnMap, HEADER_REMARK)
        val combinedText = listOf(tradeType, counterparty, product, paymentMethod, status, remark)
            .filter(String::isNotBlank)
            .joinToString(" | ")
        val type = inferImportedLedgerType(direction, combinedText) ?: return null
        val createdAtMillis = dateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val externalKeySource = transactionId.ifBlank {
            merchantId.ifBlank {
                "$timeText|$direction|$amountCents|$counterparty|$product"
            }
        }

        return LedgerEntry(
            id = 0L,
            type = type,
            amountCents = amountCents,
            category = inferImportedCategory(combinedText),
            note = listOf(product, remark, paymentMethod)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" · ")
                .take(MAX_NOTE_LENGTH),
            dateEpochDay = dateTime.toLocalDate().toEpochDay(),
            createdAtMillis = createdAtMillis,
            source = LedgerSource.WECHAT_IMPORT,
            counterparty = counterparty.take(MAX_COUNTERPARTY_LENGTH),
            rawText = combinedText.take(MAX_RAW_TEXT_LENGTH),
            externalKey = "wechat_bill:${sha256(externalKeySource)}"
        )
    }

    /**
     * 根据微信账单“收/支”列及交易类型确定记账方向。
     *
     * @param direction 微信账单原始收支方向。
     * @param combinedText 交易类型、商品和状态等合并文本。
     *
     * @return 收入、支出或内部转账；完全无法识别时返回null。
     */
    private fun inferImportedLedgerType(
        direction: String,
        combinedText: String
    ): LedgerType? {
        if (internalTransferKeywords.any(combinedText::contains)) {
            return LedgerType.TRANSFER
        }

        return when {
            direction.contains("收入") -> LedgerType.INCOME
            direction.contains("支出") -> LedgerType.EXPENSE
            direction.contains("不计收支") || direction.contains("中性") -> LedgerType.TRANSFER
            else -> null
        }
    }

    /**
     * 根据微信交易文本归类，供汇总和分析页面使用。
     *
     * @param text 合并后的交易摘要。
     *
     * @return 红包、转账、提现、充值、餐饮、交通等分类。
     */
    private fun inferImportedCategory(text: String): String {
        return categoryRules.firstOrNull { rule ->
            rule.keywords.any(text::contains)
        }?.category ?: "其他"
    }

    /**
     * 解析微信账单交易时间，兼容带秒和不带秒的格式。
     *
     * @param text 交易时间文本。
     *
     * @return LocalDateTime；格式不支持时返回null。
     */
    private fun parseTransactionTime(text: String): LocalDateTime? {
        transactionTimeFormatters.forEach { formatter ->
            runCatching {
                return LocalDateTime.parse(text.trim(), formatter)
            }
        }
        return null
    }

    /**
     * 解析微信账单金额文本为分。
     *
     * @param text 可能包含¥、￥、逗号和空格的金额。
     *
     * @return 分单位金额；不合法时返回null。
     */
    private fun parseAmountCents(text: String): Long? {
        val numberText = text
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .trim()

        return runCatching {
            java.math.BigDecimal(numberText).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /**
     * 按RFC风格处理引号、转义双引号、逗号和换行，拆分CSV记录。
     *
     * @param text CSV全文。
     *
     * @return 每条记录及其字段列表。
     */
    private fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val currentRecord = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var index = 0

        while (index < text.length) {
            val character = text[index]

            when {
                character == '"' && insideQuotes && index + 1 < text.length && text[index + 1] == '"' -> {
                    currentField.append('"')
                    index++
                }

                character == '"' -> insideQuotes = !insideQuotes
                character == ',' && !insideQuotes -> {
                    currentRecord.add(currentField.toString())
                    currentField.clear()
                }

                (character == '\n' || character == '\r') && !insideQuotes -> {
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index++
                    }
                    currentRecord.add(currentField.toString())
                    currentField.clear()
                    if (currentRecord.any { it.isNotBlank() }) {
                        records.add(currentRecord.toList())
                    }
                    currentRecord.clear()
                }

                else -> currentField.append(character)
            }

            index++
        }

        if (currentField.isNotEmpty() || currentRecord.isNotEmpty()) {
            currentRecord.add(currentField.toString())
            if (currentRecord.any { it.isNotBlank() }) {
                records.add(currentRecord.toList())
            }
        }

        return records
    }

    /**
     * 从ZIP中找到首个CSV文件并限制解压后大小。
     *
     * @param zipBytes ZIP原始字节。
     *
     * @return CSV文件字节。
     */
    private fun extractCsvFromZip(zipBytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInputStream ->
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                    return readStreamWithLimit(zipInputStream, MAX_UNCOMPRESSED_BYTES)
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        }

        throw ZipException("No CSV entry found")
    }

    /**
     * 根据扩展名或PK文件头判断是否为ZIP。
     *
     * @param fileName 文件显示名称。
     * @param bytes 文件前部字节。
     *
     * @return ZIP文件返回true。
     */
    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
    }

    /**
     * 自动识别UTF-8 BOM、UTF-8和GB18030编码。
     *
     * @param bytes CSV文件字节。
     *
     * @return 可用于CSV解析的Unicode文本。
     */
    private fun decodeBillText(bytes: ByteArray): String {
        val utf8Bytes = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val utf8Text = utf8Bytes.toString(Charsets.UTF_8)

        return if (utf8Text.contains(HEADER_TRANSACTION_TIME) && !utf8Text.contains('\uFFFD')) {
            utf8Text
        } else {
            utf8Bytes.toString(Charset.forName("GB18030"))
        }
    }

    /**
     * 查询Content Uri对应的显示名称。
     *
     * @param uri 用户选择的文件Uri。
     *
     * @return 文件显示名称；无法读取时返回空字符串。
     */
    private fun queryDisplayName(uri: Uri): String {
        return applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }

    /**
     * 对已经读入的文件字节做上限检查。
     *
     * @param bytes 原始文件字节。
     *
     * @return 未超过限制时原样返回。
     */
    private fun readLimitedBytes(bytes: ByteArray): ByteArray {
        if (bytes.size > MAX_SOURCE_BYTES) {
            throw FileTooLargeException()
        }
        return bytes
    }

    /**
     * 从流中读取不超过指定上限的字节。
     *
     * @param inputStream ZIP内部CSV输入流。
     * @param maximumBytes 最大允许字节数。
     *
     * @return 完整读取的字节数组。
     */
    private fun readStreamWithLimit(
        inputStream: java.io.InputStream,
        maximumBytes: Int
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0

        while (true) {
            val readCount = inputStream.read(buffer)
            if (readCount < 0) {
                break
            }
            totalBytes += readCount
            if (totalBytes > maximumBytes) {
                throw FileTooLargeException()
            }
            outputStream.write(buffer, 0, readCount)
        }

        return outputStream.toByteArray()
    }

    /**
     * 统一标题文字，移除BOM、空格和引号差异。
     *
     * @param value 原始标题字段。
     *
     * @return 标准化标题。
     */
    private fun normalizeHeader(value: String): String {
        return value.trim().trimStart('\uFEFF').replace(" ", "")
    }

    /**
     * 安全按下标读取字段。
     *
     * @param index 字段下标。
     *
     * @return 去除首尾空白后的字段；越界时返回空字符串。
     */
    private fun List<String>.getOrBlank(index: Int): String {
        return getOrNull(index)?.trim().orEmpty()
    }

    /**
     * 根据标准化标题获取一行中的字段。
     *
     * @param columnMap 标题到下标映射。
     * @param header 目标标题。
     *
     * @return 对应字段；标题或字段不存在时返回空字符串。
     */
    private fun List<String>.valueForHeader(
        columnMap: Map<String, Int>,
        header: String
    ): String {
        val index = columnMap[header] ?: return ""
        return getOrBlank(index)
    }

    /**
     * 对交易标识计算SHA-256，避免把原始交易号直接作为内部键传播。
     *
     * @param value 原始交易号或回退组合字段。
     *
     * @return 十六进制SHA-256摘要。
     */
    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TAG = "WechatBillImporter"
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 40 * 1024 * 1024
        const val MAX_NOTE_LENGTH = 100
        const val MAX_COUNTERPARTY_LENGTH = 30
        const val MAX_RAW_TEXT_LENGTH = 500
        const val HEADER_TRANSACTION_TIME = "交易时间"
        const val HEADER_TRANSACTION_TYPE = "交易类型"
        const val HEADER_COUNTERPARTY = "交易对方"
        const val HEADER_PRODUCT = "商品"
        const val HEADER_DIRECTION = "收/支"
        const val HEADER_AMOUNT = "金额"
        const val HEADER_PAYMENT_METHOD = "支付方式"
        const val HEADER_STATUS = "当前状态"
        const val HEADER_TRANSACTION_ID = "交易单号"
        const val HEADER_MERCHANT_ID = "商户单号"
        const val HEADER_REMARK = "备注"
    }

    private val transactionTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss", Locale.CHINA)
    )
    private val internalTransferKeywords = listOf("提现", "充值", "零钱通转入", "零钱通转出")
    private val categoryRules = listOf(
        CategoryRule("红包", listOf("红包")),
        CategoryRule("提现", listOf("提现")),
        CategoryRule("充值", listOf("充值")),
        CategoryRule("转账", listOf("转账", "收款")),
        CategoryRule("餐饮", listOf("餐", "饭", "咖啡", "奶茶", "美团", "饿了么")),
        CategoryRule("交通", listOf("滴滴", "公交", "地铁", "铁路", "加油", "停车", "高速")),
        CategoryRule("购物", listOf("超市", "淘宝", "京东", "拼多多", "商店")),
        CategoryRule("居住", listOf("房租", "物业", "水费", "电费", "燃气")),
        CategoryRule("娱乐", listOf("游戏", "电影", "视频", "音乐")),
        CategoryRule("医疗", listOf("医院", "药房", "医疗")),
        CategoryRule("教育", listOf("学校", "培训", "课程", "教育"))
    )
}

/**
 * 微信账单CSV解析阶段结果。
 *
 * @param entries 成功解析的账目。
 * @param scannedRows 扫描到的交易行数。
 * @param skippedRows 无法安全导入的行数。
 * @param errorMessage 解析失败原因，成功时为空。
 */
internal data class WechatBillParseResult(
    val entries: List<LedgerEntry> = emptyList(),
    val scannedRows: Int = 0,
    val skippedRows: Int = 0,
    val errorMessage: String = ""
)

/**
 * 自动分类规则。
 *
 * @param category 目标分类。
 * @param keywords 任一命中即可使用该分类的关键词。
 */
private data class CategoryRule(
    val category: String,
    val keywords: List<String>
)

/**
 * 账单文件超过安全内存上限时使用的内部异常。
 */
private class FileTooLargeException : Exception()
