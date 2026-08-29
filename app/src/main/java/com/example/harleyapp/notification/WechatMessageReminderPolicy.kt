package com.example.harleyapp.notification

/**
 * 微信通知是否应该进入未查看消息重复提醒的判断结果。
 *
 * @property REMIND 普通单聊、群聊或隐藏正文的聊天消息，可以开始等待查看。
 * @property EMPTY_NOTIFICATION 通知没有标题和正文，无法判断为聊天消息。
 * @property CALL_NOTIFICATION 微信语音或视频通话通知，不应反复提醒。
 * @property SYSTEM_NOTIFICATION 支付、公众号、微信团队或其他系统类通知，不应反复提醒。
 */
enum class WechatReminderDecision {
    REMIND,
    EMPTY_NOTIFICATION,
    CALL_NOTIFICATION,
    SYSTEM_NOTIFICATION
}

/**
 * 微信未查看消息提醒的纯文本过滤策略。
 *
 * 使用方法：
 * 通知监听服务提取微信通知标题和正文后调用evaluate。只有返回REMIND时才记录Android通知键。
 * 本策略允许群聊和隐藏正文的聊天通知，因为提醒文案始终使用通用文字，不会保存或展示消息内容。
 */
object WechatMessageReminderPolicy {

    /**
     * 判断一条非支付微信通知是否属于需要等待查看的普通聊天消息。
     *
     * @param title 微信通知标题，通常为联系人、群名或“微信”。
     * @param content 微信通知合并后的正文，可能是隐藏内容后的通用提示。
     *
     * @return REMIND或最先命中的排除原因。
     */
    fun evaluate(title: String, content: String): WechatReminderDecision {
        val normalizedTitle = title.trim()
        val normalizedContent = content.replace(Regex("\\s+"), " ").trim()
        val combinedText = "$normalizedTitle $normalizedContent".trim()

        if (combinedText.isBlank()) {
            return WechatReminderDecision.EMPTY_NOTIFICATION
        }
        if (callPhrases.any(combinedText::contains)) {
            return WechatReminderDecision.CALL_NOTIFICATION
        }
        if (systemTitles.any { normalizedTitle.contains(it) } ||
            systemPhrases.any(combinedText::contains)
        ) {
            return WechatReminderDecision.SYSTEM_NOTIFICATION
        }

        return WechatReminderDecision.REMIND
    }

    private val callPhrases = listOf(
        "语音通话",
        "视频通话",
        "语音电话",
        "视频电话",
        "邀请你加入通话",
        "邀请您加入通话",
        "通话邀请",
        "正在呼叫",
        "未接来电"
    )
    private val systemTitles = listOf(
        "微信支付",
        "服务通知",
        "微信团队",
        "订阅号消息",
        "腾讯新闻",
        "看一看",
        "文件传输助手"
    )
    private val systemPhrases = listOf(
        "收款到账",
        "支付成功",
        "付款成功",
        "转账到账",
        "红包到账",
        "登录确认",
        "安全提醒",
        "微信运动"
    )
}
