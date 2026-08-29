package com.example.harleyapp.notification

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.harleyapp.data.AutoReplySettingsRepository
import com.example.harleyapp.data.AutoReplyThrottleResult
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.model.AutoReplyCompatibility
import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.WechatCapture
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 监听微信系统通知，并分别处理支付自动记账和普通聊天定时自动回复。
 *
 * 使用方法：
 * 用户必须在App中主动开启相应功能，并在Android“通知使用权”页面授权。服务由系统绑定，
 * 不需要App手动启动。支付通知只进入记账流程；普通聊天只有在设置时段内、通过安全过滤且
 * 微信通知确实提供RemoteInput快捷回复入口时才会发送，微信通话通知始终跳过。
 */
class WechatNotificationListenerService : NotificationListenerService() {

    private val ledgerRepository by lazy {
        LedgerRepository(applicationContext)
    }
    private val autoReplyRepository by lazy {
        AutoReplySettingsRepository(applicationContext)
    }

    /**
     * 系统成功绑定通知监听服务时调用。
     *
     * @return 无返回值。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "WeChat notification listener connected")
    }

    /**
     * 接收新微信通知，并保证支付记账与聊天回复互斥处理。
     *
     * @param sbn 系统状态栏通知；为null或包名不是com.tencent.mm时直接忽略。
     *
     * @return 无返回值。
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null || sbn.packageName != WECHAT_PACKAGE_NAME) {
            return
        }

        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val content = extractNotificationContent(sbn.notification)
        val parsedFinancialResult = WechatNotificationParser.parse(title, content)

        if (parsedFinancialResult.isFinancialNotification) {
            processFinancialNotification(
                sbn = sbn,
                title = title,
                content = content
            )
            return
        }

        processAutoReplyNotification(
            sbn = sbn,
            title = title,
            content = content
        )
    }

    /**
     * 处理一条已经确认具有支付语义的微信通知。
     *
     * 使用方法：
     * 仅由onNotificationPosted调用。金额和方向完整时直接保存到账本，否则保留到待确认列表；
     * 去重键不包含通知明文，避免通知更新造成重复入账。
     *
     * @param sbn 原始微信状态栏通知。
     * @param title 通知标题。
     * @param content 合并后的通知正文。
     *
     * @return 无返回值。
     */
    private fun processFinancialNotification(
        sbn: StatusBarNotification,
        title: String,
        content: String
    ) {
        val parsedResult = WechatNotificationParser.parse(title, content)
        val externalKey = createFinancialExternalKey(sbn)
        val receivedAtMillis = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

        if (parsedResult.canAutoConfirm) {
            val amountCents = parsedResult.amountCents ?: return
            val type = parsedResult.type ?: return
            val dateEpochDay = Instant.ofEpochMilli(receivedAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toEpochDay()
            val saved = ledgerRepository.upsertEntry(
                LedgerEntry(
                    id = 0L,
                    type = type,
                    amountCents = amountCents,
                    category = parsedResult.category,
                    note = "微信通知自动记账",
                    dateEpochDay = dateEpochDay,
                    createdAtMillis = receivedAtMillis,
                    source = LedgerSource.WECHAT_NOTIFICATION,
                    counterparty = parsedResult.counterparty,
                    rawText = "$title | $content".trim(' ', '|'),
                    externalKey = externalKey
                )
            )

            if (saved) {
                ledgerRepository.deletePendingCapture(externalKey)
                Log.i(TAG, "WeChat transaction saved automatically")
            }
            return
        }

        ledgerRepository.upsertPendingCapture(
            WechatCapture(
                externalKey = externalKey,
                title = title.ifBlank { "微信支付通知" },
                content = content,
                parsedAmountCents = parsedResult.amountCents,
                suggestedType = parsedResult.type,
                suggestedCategory = parsedResult.category,
                receivedAtMillis = receivedAtMillis
            )
        )
        Log.i(TAG, "WeChat transaction requires user review")
    }

    /**
     * 按当前设置、安全策略和频率限制处理普通微信聊天通知。
     *
     * @param sbn 原始微信状态栏通知。
     * @param title 通知标题，通常是联系人或群名。
     * @param content 合并后的聊天摘要。
     *
     * @return 无返回值；任一安全条件不满足时静默跳过。
     */
    private fun processAutoReplyNotification(
        sbn: StatusBarNotification,
        title: String,
        content: String
    ) {
        val settings = autoReplyRepository.getSettings()
        if (!settings.enabled) {
            return
        }

        val notification = sbn.notification
        val extras = notification.extras
        val conversationTitle = extras
            .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()
            .orEmpty()
        val isGroupConversation = extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false)
        val contentDecision = WechatAutoReplyPolicy.evaluate(
            title = title,
            content = content,
            conversationTitle = conversationTitle,
            isGroupConversation = isGroupConversation,
            settings = settings,
            now = LocalDateTime.now()
        )
        if (contentDecision != AutoReplyContentDecision.ALLOWED) {
            Log.d(TAG, "Auto-reply skipped by content policy: ${contentDecision.name}")
            return
        }

        val nowMillis = System.currentTimeMillis()
        val replyTarget = findReplyTarget(notification)
        if (replyTarget == null) {
            autoReplyRepository.recordCompatibility(
                compatibility = AutoReplyCompatibility.NO_REPLY_ACTION,
                detailCode = DETAIL_NO_REMOTE_INPUT,
                checkedAtMillis = nowMillis
            )
            Log.i(TAG, "WeChat notification has no free-form reply action")
            return
        }

        autoReplyRepository.recordCompatibility(
            compatibility = AutoReplyCompatibility.SUPPORTED,
            detailCode = DETAIL_REMOTE_INPUT_FOUND,
            checkedAtMillis = nowMillis
        )

        val conversationIdentity = conversationTitle.ifBlank { title }.trim()
        val conversationHash = createPrivacyHash("conversation|$conversationIdentity")
        val notificationFingerprint = createAutoReplyFingerprint(sbn, title, content)
        val throttleResult = autoReplyRepository.evaluateThrottle(
            conversationHash = conversationHash,
            notificationFingerprint = notificationFingerprint,
            settings = settings,
            nowMillis = nowMillis
        )
        if (throttleResult != AutoReplyThrottleResult.ALLOWED) {
            Log.d(TAG, "Auto-reply skipped by throttle: ${throttleResult.name}")
            return
        }

        sendRemoteInputReply(
            replyTarget = replyTarget,
            settings = settings,
            conversationHash = conversationHash,
            notificationFingerprint = notificationFingerprint,
            nowMillis = nowMillis
        )
    }

    /**
     * 在通知动作中寻找允许自由文本输入的Android快捷回复入口。
     *
     * @param notification 微信普通聊天通知。
     *
     * @return 优先返回语义为回复或标题包含“回复”的动作；没有RemoteInput时返回null。
     */
    private fun findReplyTarget(notification: Notification): ReplyTarget? {
        val candidates = notification.actions
            ?.mapNotNull { action ->
                val freeFormInputs = action.remoteInputs
                    ?.filter(RemoteInput::getAllowFreeFormInput)
                    .orEmpty()
                if (freeFormInputs.isEmpty()) {
                    null
                } else {
                    ReplyTarget(action, freeFormInputs.toTypedArray())
                }
            }
            .orEmpty()

        return candidates.firstOrNull { target ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                target.action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.firstOrNull { target ->
            target.action.title?.toString()?.contains("回复", ignoreCase = true) == true ||
                target.action.title?.toString()?.contains("reply", ignoreCase = true) == true
        } ?: candidates.firstOrNull()
    }

    /**
     * 使用微信通知自身提供的PendingIntent发送RemoteInput结果。
     *
     * @param replyTarget 快捷回复动作及其自由文本输入参数。
     * @param settings 当前自动回复设置。
     * @param conversationHash 匿名会话摘要，用于成功后的冷却记录。
     * @param notificationFingerprint 当前通知匿名指纹，用于成功后的防重复记录。
     * @param nowMillis 本次发送时间戳。
     *
     * @return 无返回值；发送失败时只更新兼容状态，不占用冷却或每日次数。
     */
    private fun sendRemoteInputReply(
        replyTarget: ReplyTarget,
        settings: AutoReplySettings,
        conversationHash: String,
        notificationFingerprint: String,
        nowMillis: Long
    ) {
        val resultBundle = Bundle().apply {
            replyTarget.remoteInputs.forEach { remoteInput ->
                putCharSequence(remoteInput.resultKey, settings.replyText)
            }
        }
        val replyIntent = Intent()
        RemoteInput.addResultsToIntent(replyTarget.remoteInputs, replyIntent, resultBundle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(replyIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }

        try {
            replyTarget.action.actionIntent.send(applicationContext, 0, replyIntent)
            autoReplyRepository.recordSuccessfulReply(
                conversationHash = conversationHash,
                notificationFingerprint = notificationFingerprint,
                nowMillis = nowMillis
            )
            Log.i(TAG, "WeChat auto-reply sent through notification action")
        } catch (error: PendingIntent.CanceledException) {
            autoReplyRepository.recordCompatibility(
                compatibility = AutoReplyCompatibility.SEND_FAILED,
                detailCode = DETAIL_PENDING_INTENT_CANCELED,
                checkedAtMillis = nowMillis
            )
            Log.e(TAG, "WeChat reply PendingIntent was canceled", error)
        } catch (error: SecurityException) {
            autoReplyRepository.recordCompatibility(
                compatibility = AutoReplyCompatibility.SEND_FAILED,
                detailCode = DETAIL_SECURITY_REJECTED,
                checkedAtMillis = nowMillis
            )
            Log.e(TAG, "System rejected WeChat reply action", error)
        }
    }

    /**
     * 合并通知的短文本、长文本、副标题和多行文本，并去除重复内容。
     *
     * @param notification 微信系统通知。
     *
     * @return 用分隔符连接的非空通知正文。
     */
    private fun extractNotificationContent(notification: Notification): String {
        val extras = notification.extras
        val values = mutableListOf<String>()

        listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT
        ).forEach { key ->
            extras.getCharSequence(key)?.toString()?.let(values::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapTo(values) { it.toString() }

        return values
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" | ")
    }

    /**
     * 生成微信支付通知的稳定匿名去重键。
     *
     * @param sbn 状态栏通知，提供系统通知键和通知自身时间。
     *
     * @return 以wechat_notification开头的SHA-256十六进制键。
     */
    private fun createFinancialExternalKey(sbn: StatusBarNotification): String {
        val notificationTime = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime
        return "wechat_notification:${createPrivacyHash("${sbn.key}|$notificationTime")}"
    }

    /**
     * 生成聊天通知指纹，防止系统重复回调造成二次回复。
     *
     * @param sbn 状态栏通知。
     * @param title 通知标题。
     * @param content 通知正文。
     *
     * @return 不包含明文消息的SHA-256十六进制指纹。
     */
    private fun createAutoReplyFingerprint(
        sbn: StatusBarNotification,
        title: String,
        content: String
    ): String {
        val notificationTime = sbn.notification.`when`.takeIf { it > 0L } ?: sbn.postTime
        return createPrivacyHash("${sbn.key}|$notificationTime|$title|$content")
    }

    /**
     * 计算UTF-8文本的SHA-256十六进制摘要。
     *
     * @param source 仅在内存中参与计算的原始文本。
     *
     * @return 固定64字符的小写十六进制摘要。
     */
    private fun createPrivacyHash(source: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * 一个可用于发送自由文本的通知动作。
     *
     * @param action 微信通知动作及其PendingIntent。
     * @param remoteInputs 允许自由文本输入的RemoteInput数组。
     */
    private data class ReplyTarget(
        val action: Notification.Action,
        val remoteInputs: Array<RemoteInput>
    )

    private companion object {
        const val TAG = "WechatNotification"
        const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
        const val EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation"
        const val DETAIL_NO_REMOTE_INPUT = "no_remote_input"
        const val DETAIL_REMOTE_INPUT_FOUND = "remote_input_found"
        const val DETAIL_PENDING_INTENT_CANCELED = "pending_intent_canceled"
        const val DETAIL_SECURITY_REJECTED = "security_rejected"
    }
}
