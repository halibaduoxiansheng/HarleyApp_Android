package com.example.harleyapp.notification

import com.example.harleyapp.model.AutoReplySettings
import java.time.LocalDateTime

/**
 * 微信聊天通知在进入系统快捷回复前的内容过滤结果。
 *
 * @property ALLOWED 文本满足自动回复条件。
 * @property OUTSIDE_SCHEDULE 当前时间不在用户设置的时间段内。
 * @property EMPTY_MESSAGE 通知没有可判断的标题或正文。
 * @property CALL_NOTIFICATION 微信语音或视频通话通知，必须保持原样且不得回复。
 * @property SYSTEM_NOTIFICATION 微信团队、公众号、支付或系统类通知。
 * @property GROUP_DISABLED 检测到群聊，但用户没有开启群聊自动回复。
 * @property HIDDEN_CONTENT 微信通知隐藏了联系人或正文，无法安全确定回复对象。
 * @property POSSIBLE_REPLY_LOOP 通知正文与自动回复内容相同，可能是通知回显。
 */
enum class AutoReplyContentDecision {
    ALLOWED,
    OUTSIDE_SCHEDULE,
    EMPTY_MESSAGE,
    CALL_NOTIFICATION,
    SYSTEM_NOTIFICATION,
    GROUP_DISABLED,
    HIDDEN_CONTENT,
    POSSIBLE_REPLY_LOOP
}

/**
 * 微信普通聊天通知的纯文本安全策略。
 *
 * 使用方法：
 * 通知服务提取标题、正文和Android群聊标记后调用evaluate。只有返回ALLOWED时，
 * 才允许继续寻找RemoteInput快捷回复入口。该对象不访问系统服务，便于单元测试。
 */
object WechatAutoReplyPolicy {

    /**
     * 判断一条微信聊天通知是否可以进入快捷回复阶段。
     *
     * @param title 微信通知标题，通常是联系人或群名。
     * @param content 微信通知合并后的正文。
     * @param conversationTitle Android MessagingStyle提供的会话标题，可能为空。
     * @param isGroupConversation Android通知提供的群聊标记。
     * @param settings 当前自动回复设置。
     * @param now 手机当前本地日期和时间。
     *
     * @return ALLOWED或最先命中的安全拦截原因。
     */
    fun evaluate(
        title: String,
        content: String,
        conversationTitle: String,
        isGroupConversation: Boolean,
        settings: AutoReplySettings,
        now: LocalDateTime
    ): AutoReplyContentDecision {
        if (!isWithinSchedule(
                minuteOfDay = now.hour * MINUTES_PER_HOUR + now.minute,
                startMinuteOfDay = settings.startMinuteOfDay,
                endMinuteOfDay = settings.endMinuteOfDay
            )
        ) {
            return AutoReplyContentDecision.OUTSIDE_SCHEDULE
        }

        val normalizedTitle = title.trim()
        val normalizedContent = content.replace(Regex("\\s+"), " ").trim()
        val combinedText = "$normalizedTitle $normalizedContent".trim()

        if (normalizedTitle.isBlank() || normalizedContent.isBlank()) {
            return AutoReplyContentDecision.EMPTY_MESSAGE
        }
        if (callPhrases.any(combinedText::contains)) {
            return AutoReplyContentDecision.CALL_NOTIFICATION
        }
        if (systemTitles.any { normalizedTitle.contains(it) } ||
            systemPhrases.any(combinedText::contains)
        ) {
            return AutoReplyContentDecision.SYSTEM_NOTIFICATION
        }
        if (normalizedTitle in hiddenTitles || hiddenPhrases.any(normalizedContent::contains)) {
            return AutoReplyContentDecision.HIDDEN_CONTENT
        }
        if (normalizedContent.trimNotificationSeparators() ==
            settings.replyText.trimNotificationSeparators()
        ) {
            return AutoReplyContentDecision.POSSIBLE_REPLY_LOOP
        }

        val likelyGroup = isGroupConversation ||
            groupPhrases.any(combinedText::contains) ||
            groupMessagePrefixRegex.containsMatchIn(normalizedContent)
        if (likelyGroup && !settings.replyToGroups) {
            return AutoReplyContentDecision.GROUP_DISABLED
        }

        return AutoReplyContentDecision.ALLOWED
    }

    /**
     * 判断一天中的分钟数是否落在用户设置的时间窗口内。
     *
     * 使用方法：
     * 开始与结束相同时表示全天；开始早于结束时按同一天区间判断；开始晚于结束时
     * 视为跨午夜区间，例如22:00到07:00。
     *
     * @param minuteOfDay 当前时间从00:00起计算的分钟数。
     * @param startMinuteOfDay 自动回复开始分钟数。
     * @param endMinuteOfDay 自动回复结束分钟数。
     *
     * @return 当前处于启用时间段返回true，否则返回false。
     */
    fun isWithinSchedule(
        minuteOfDay: Int,
        startMinuteOfDay: Int,
        endMinuteOfDay: Int
    ): Boolean {
        val current = minuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)
        val start = startMinuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)
        val end = endMinuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)

        return when {
            start == end -> true
            start < end -> current in start until end
            else -> current >= start || current < end
        }
    }

    /**
     * 去除通知拼接符和常见标点，用于检测自动回复文本是否被原样回显。
     *
     * @return 适合做相等比较的文本，不改变原始通知内容。
     */
    private fun String.trimNotificationSeparators(): String {
        return trim(' ', '|', '：', ':', '。', '.', '！', '!', '？', '?')
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
    private val hiddenTitles = setOf("微信", "WeChat")
    private val hiddenPhrases = listOf(
        "你收到了一条消息",
        "您收到了一条消息",
        "你有一条新消息",
        "你收到了新消息"
    )
    private val groupPhrases = listOf("群聊", "@所有人", "@你", "条新消息")
    private val groupMessagePrefixRegex = Regex("^[^|：:]{1,30}[：:]\\s*.+")
    private const val MINUTES_PER_HOUR = 60
    private const val MIN_MINUTE_OF_DAY = 0
    private const val MAX_MINUTE_OF_DAY = 23 * 60 + 59
}
