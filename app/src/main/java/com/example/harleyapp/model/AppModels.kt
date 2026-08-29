package com.example.harleyapp.model

import android.graphics.Bitmap

/**
 * 账目收支类型。
 *
 * @property INCOME 收入记录。
 * @property EXPENSE 支出记录。
 * @property TRANSFER 提现、充值等账户之间的内部资金转移，不计入收入或支出。
 */
enum class LedgerType {
    INCOME,
    EXPENSE,
    TRANSFER
}

/**
 * 账目产生来源。
 *
 * @property MANUAL 用户手动录入。
 * @property WECHAT_NOTIFICATION 从微信支付相关系统通知自动识别。
 * @property WECHAT_IMPORT 从用户选择的微信账单文件导入。
 */
enum class LedgerSource {
    MANUAL,
    WECHAT_NOTIFICATION,
    WECHAT_IMPORT
}

/**
 * 本应用安全缓存清理的最近状态。
 *
 * @param automaticEnabled 是否在App启动时每天最多自动检查一次过期缓存。
 * @param lastCleanupAtMillis 最近一次自动或手动清理时间戳；尚未执行时为0。
 * @param lastFreedBytes 最近一次实际释放的缓存字节数。
 */
data class LocalCleanupStatus(
    val automaticEnabled: Boolean = true,
    val lastCleanupAtMillis: Long = 0L,
    val lastFreedBytes: Long = 0L
)

/**
 * 一次本应用缓存清理的执行结果。
 *
 * @param freedBytes 本次实际释放的缓存字节数。
 * @param removedExpiredCaptures 删除的过期待确认微信支付通知数量。
 * @param errorMessage 执行错误说明，成功时为空。
 */
data class LocalCleanupResult(
    val freedBytes: Long = 0L,
    val removedExpiredCaptures: Int = 0,
    val errorMessage: String = ""
)

/**
 * 单条账目数据。
 *
 * 使用方法：
 * 新增账目时将id设置为0，LedgerRepository会自动生成唯一编号；编辑时保留原id。
 * 金额使用“分”为单位保存，避免使用浮点数造成金额精度误差。
 *
 * @param id 账目唯一编号，0表示尚未持久化的新记录。
 * @param type 收入或支出类型。
 * @param amountCents 金额，单位为分，必须大于0。
 * @param category 用户选择或输入的分类。
 * @param note 可选备注。
 * @param dateEpochDay 账目日期，从1970-01-01开始计算的天数。
 * @param createdAtMillis 创建时间戳，用于保证同一天记录的稳定排序。
 * @param source 账目来源。
 * @param counterparty 交易对方或商户名称。
 * @param rawText 自动识别时保留的原始摘要，仅存本机，手动记录通常为空。
 * @param externalKey 微信通知或账单交易的去重键，手动记录为空。
 */
data class LedgerEntry(
    val id: Long,
    val type: LedgerType,
    val amountCents: Long,
    val category: String,
    val note: String,
    val dateEpochDay: Long,
    val createdAtMillis: Long,
    val source: LedgerSource = LedgerSource.MANUAL,
    val counterparty: String = "",
    val rawText: String = "",
    val externalKey: String = ""
)

/**
 * 无法安全自动入账的微信支付相关通知。
 *
 * 使用方法：
 * 通知金额缺失或收支方向不明确时保存为WechatCapture，在“待确认”页面由用户补充后转为账目。
 *
 * @param externalKey 通知去重键。
 * @param title 微信通知标题。
 * @param content 合并后的通知正文。
 * @param parsedAmountCents 已解析金额，无法解析时为null。
 * @param suggestedType 建议收支方向，无法判断时为null。
 * @param suggestedCategory 建议分类。
 * @param receivedAtMillis 通知收到时间。
 */
data class WechatCapture(
    val externalKey: String,
    val title: String,
    val content: String,
    val parsedAmountCents: Long?,
    val suggestedType: LedgerType?,
    val suggestedCategory: String,
    val receivedAtMillis: Long
)

/**
 * 微信通知文本解析结果。
 *
 * @param isFinancialNotification 是否属于可能的微信支付通知；false表示普通聊天通知并应忽略。
 * @param amountCents 解析出的金额，缺失时为null。
 * @param type 收入、支出或内部转账方向，无法判断时为null。
 * @param category 识别出的账目分类。
 * @param counterparty 推断出的交易对方，无法提取时为空。
 * @param canAutoConfirm 是否具备足够信息可以直接自动入账。
 */
data class WechatParseResult(
    val isFinancialNotification: Boolean,
    val amountCents: Long?,
    val type: LedgerType?,
    val category: String,
    val counterparty: String,
    val canAutoConfirm: Boolean
)

/**
 * 微信账单文件导入结果。
 *
 * @param scannedRows 扫描到的有效交易行数。
 * @param insertedRows 新增账目数。
 * @param updatedRows 与已有去重键相同并完成更新的账目数。
 * @param skippedRows 无法识别或无需入账的行数。
 * @param errorMessage 失败原因，成功时为空。
 */
data class BillImportResult(
    val scannedRows: Int = 0,
    val insertedRows: Int = 0,
    val updatedRows: Int = 0,
    val skippedRows: Int = 0,
    val errorMessage: String = ""
)

/**
 * 手机状态的一次采样结果。
 *
 * @param uploadBytesPerSecond 当前估算上传速率，单位为字节每秒。
 * @param downloadBytesPerSecond 当前估算下载速率，单位为字节每秒。
 * @param totalMemoryBytes 系统RAM总量，单位为字节。
 * @param availableMemoryBytes 当前可用RAM，单位为字节。
 * @param totalStorageBytes 主内部存储总量，单位为字节。
 * @param availableStorageBytes 主内部存储可用量，单位为字节。
 * @param totalSwapBytes 系统交换或内存扩展空间总量，单位为字节。
 * @param availableSwapBytes 系统交换或内存扩展空间可用量，单位为字节。
 */
data class DeviceSnapshot(
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val totalMemoryBytes: Long = 0,
    val availableMemoryBytes: Long = 0,
    val totalStorageBytes: Long = 0,
    val availableStorageBytes: Long = 0,
    val totalSwapBytes: Long = 0,
    val availableSwapBytes: Long = 0
)

/**
 * 一个能够从桌面启动的手机应用。
 *
 * @param packageName 应用包名，用于持久化选择和启动应用。
 * @param label 应用向用户显示的名称。
 * @param icon 应用图标位图。
 */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap
)
