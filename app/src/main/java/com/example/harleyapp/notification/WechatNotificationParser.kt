package com.example.harleyapp.notification

import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.WechatParseResult
import java.math.BigDecimal

/**
 * 解析微信支付相关通知中的金额、方向、分类和交易对方。
 *
 * 使用方法：
 * 将系统通知标题和合并正文传入parse。普通微信聊天会返回isFinancialNotification=false；
 * 金额或方向缺失时canAutoConfirm=false，应进入待确认队列而不是直接计入汇总。
 */
object WechatNotificationParser {

    /**
     * 解析一条微信通知。
     *
     * @param title 通知标题，可能为空。
     * @param content 通知正文，可由text、bigText和textLines合并而来。
     *
     * @return 微信通知解析结果。
     */
    fun parse(title: String, content: String): WechatParseResult {
        val normalizedText = "$title $content"
            .replace(Regex("\\s+"), " ")
            .trim()
        val amountCents = parseAmountCents(normalizedText)
        val type = inferLedgerType(normalizedText)
        val category = inferCategory(normalizedText)
        val isFinancial = isFinancialText(normalizedText, amountCents)
        val counterparty = inferCounterparty(title, normalizedText)

        return WechatParseResult(
            isFinancialNotification = isFinancial,
            amountCents = amountCents,
            type = type,
            category = category,
            counterparty = counterparty,
            canAutoConfirm = isFinancial && amountCents != null && amountCents > 0L && type != null
        )
    }

    /**
     * 判断通知是否包含足够强的微信支付语义，避免把普通聊天内容保存到App。
     *
     * @param text 标准化后的标题与正文。
     * @param amountCents 已解析金额。
     *
     * @return 属于支付、红包、转账或提现等通知返回true。
     */
    private fun isFinancialText(text: String, amountCents: Long?): Boolean {
        if (strongFinancialPhrases.any(text::contains)) {
            return true
        }

        return amountCents != null && financialKeywords.any(text::contains)
    }

    /**
     * 解析人民币金额并转换为分。
     *
     * @param text 完整通知文本。
     *
     * @return 首个合法金额的分单位数值；没有金额时返回null。
     */
    private fun parseAmountCents(text: String): Long? {
        val match = currencyPrefixRegex.find(text) ?: currencySuffixRegex.find(text)
            ?: return null
        val numberText = match.groupValues[1].replace(",", "")

        return runCatching {
            BigDecimal(numberText).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /**
     * 根据通知词语推断收入、支出或内部转账。
     *
     * @param text 完整通知文本。
     *
     * @return 能确定时返回LedgerType，否则返回null。
     */
    private fun inferLedgerType(text: String): LedgerType? {
        return when {
            transferPhrases.any(text::contains) -> LedgerType.TRANSFER
            incomePhrases.any(text::contains) -> LedgerType.INCOME
            expensePhrases.any(text::contains) -> LedgerType.EXPENSE
            else -> null
        }
    }

    /**
     * 根据交易关键词生成便于分析的分类。
     *
     * @param text 完整通知文本。
     *
     * @return 红包、转账、提现、充值、商户消费或微信支付分类。
     */
    private fun inferCategory(text: String): String {
        return when {
            text.contains("红包") -> "红包"
            text.contains("提现") -> "提现"
            text.contains("充值") -> "充值"
            text.contains("转账") || text.contains("收款") -> "转账"
            text.contains("支付") || text.contains("付款") || text.contains("扣款") -> "商户消费"
            else -> "微信支付"
        }
    }

    /**
     * 从常见微信通知句式或非通用标题中提取交易对方。
     *
     * @param title 原始通知标题。
     * @param text 合并后的完整文本。
     *
     * @return 推断出的交易对方；无法确定时返回空字符串。
     */
    private fun inferCounterparty(title: String, text: String): String {
        counterpartyRegexes.forEach { regex ->
            val value = regex.find(text)?.groupValues?.getOrNull(1)?.trim(' ', '，', ',', '。')
            if (!value.isNullOrBlank()) {
                return value.take(MAX_COUNTERPARTY_LENGTH)
            }
        }

        return title.trim()
            .takeIf { candidate ->
                candidate.isNotBlank() &&
                    candidate !in genericWechatTitles &&
                    candidate.length <= MAX_COUNTERPARTY_LENGTH
            }
            .orEmpty()
    }

    private val currencyPrefixRegex = Regex("[¥￥]\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
    private val currencySuffixRegex = Regex("([0-9,]+(?:\\.[0-9]{1,2})?)\\s*元")
    private val strongFinancialPhrases = listOf(
        "微信支付",
        "收款到账",
        "收到转账",
        "转账到账",
        "收到红包",
        "红包到账",
        "支付成功",
        "付款成功",
        "扣款成功",
        "发出红包",
        "转账给",
        "提现",
        "充值",
        "零钱通"
    )
    private val financialKeywords = listOf(
        "支付",
        "付款",
        "收款",
        "转账",
        "红包",
        "提现",
        "充值",
        "零钱",
        "银行卡"
    )
    private val transferPhrases = listOf(
        "提现",
        "充值",
        "转入零钱",
        "转出零钱",
        "零钱通转入",
        "零钱通转出"
    )
    private val incomePhrases = listOf(
        "收款到账",
        "已收款",
        "收到转账",
        "转账到账",
        "收到红包",
        "红包到账",
        "已存入零钱",
        "收入"
    )
    private val expensePhrases = listOf(
        "支付成功",
        "付款成功",
        "扣款成功",
        "已支付",
        "已付款",
        "转账给",
        "发出红包",
        "支出"
    )
    private val counterpartyRegexes = listOf(
        Regex("来自(.{1,30}?)的(?:转账|红包|付款)"),
        Regex("向(.{1,30}?)(?:转账|付款)"),
        Regex("付款给(.{1,30}?)(?:[，,。]|$)"),
        Regex("商户[：:]\\s*(.{1,30}?)(?:[，,。]|$)")
    )
    private val genericWechatTitles = setOf("微信", "微信支付", "服务通知", "支付通知")
    private const val MAX_COUNTERPARTY_LENGTH = 30
}
