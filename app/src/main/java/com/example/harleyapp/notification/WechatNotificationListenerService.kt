package com.example.harleyapp.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.WechatCapture
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * 监听微信系统通知，并分别处理支付自动记账和普通聊天未查看重复提醒。
 *
 * 使用方法：
 * 用户必须在App中主动开启相应功能，并在Android“通知使用权”页面授权。服务由系统绑定，
 * 不需要App手动启动。支付通知只进入记账流程；普通聊天通知通过安全过滤后只保存Android
 * 通知键，并安排通用提醒。联系人、群名和聊天正文不会写入提醒仓库。
 */
class WechatNotificationListenerService : NotificationListenerService() {

    private val ledgerRepository by lazy {
        LedgerRepository(applicationContext)
    }
    private val wechatReminderRepository by lazy {
        WechatReminderRepository(applicationContext)
    }
    private val wechatReminderScheduler by lazy {
        WechatReminderScheduler(applicationContext)
    }

    /**
     * 系统成功绑定通知监听服务时调用。
     *
     * @return 无返回值。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        wechatReminderRepository.recordListenerConnectionChange(
            connected = true,
            changedAtMillis = System.currentTimeMillis()
        )
        Log.i(TAG, "WeChat notification listener connected")
        synchronizeActiveWechatNotifications()
    }

    /**
     * Android主动断开通知监听服务时更新进程内连接标记。
     *
     * @return 无返回值。
     */
    override fun onListenerDisconnected() {
        listenerConnected = false
        wechatReminderRepository.recordListenerConnectionChange(
            connected = false,
            changedAtMillis = System.currentTimeMillis()
        )
        Log.w(TAG, "WeChat notification listener disconnected")
        super.onListenerDisconnected()
    }

    /**
     * 服务实例销毁时清除进程内连接标记，防止设置页继续显示旧在线状态。
     *
     * @return 无返回值。
     */
    override fun onDestroy() {
        listenerConnected = false
        super.onDestroy()
    }

    /**
     * 接收新微信通知，并保证支付记账与普通聊天提醒互斥处理。
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

        processMessageReminderNotification(
            sbn = sbn,
            title = title,
            content = content
        )
    }

    /**
     * 接收原微信通知移除事件，但不再用该事件取消本应用已经开始的等待倒计时。
     *
     * 微信会在会话合并、角标更新或通知内容刷新时主动撤换旧通知，这些移除事件并不代表用户
     * 已经查看消息。旧实现会因此提前取消Alarm，导致倒计时凭空消失。现在本轮倒计时只在本
     * 应用成功发出提醒后结束，确保退出设置页或微信更新通知都不会丢失配置与计划。
     *
     * @param sbn 已从通知栏移除的原微信通知；包名不匹配时直接忽略。
     *
     * @return 无返回值。
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)

        if (sbn == null || sbn.packageName != WECHAT_PACKAGE_NAME) {
            return
        }

        Log.d(TAG, "WeChat notification removal ignored while waiting reminder is pending")
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
     * 按当前设置和内容策略记录普通微信聊天通知，并从最新消息重新安排提醒间隔。
     *
     * @param sbn 原始微信状态栏通知，通知键用于判断它之后是否仍然存在。
     * @param title 通知标题，只在内存中参与消息类型判断。
     * @param content 合并后的通知正文，只在内存中参与消息类型判断。
     *
     * @return 无返回值；功能关闭或通知属于通话、支付、系统消息时不会安排提醒。
     */
    private fun processMessageReminderNotification(
        sbn: StatusBarNotification,
        title: String,
        content: String
    ) {
        val settings = wechatReminderRepository.getSettings()
        if (!settings.enabled) {
            return
        }

        val decision = WechatMessageReminderPolicy.evaluate(title, content)
        if (decision != WechatReminderDecision.REMIND) {
            Log.d(TAG, "WeChat reminder skipped by content policy: ${decision.name}")
            return
        }

        val receivedAtMillis = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        wechatReminderRepository.trackNotification(
            notificationKey = sbn.key,
            receivedAtMillis = receivedAtMillis
        )
        wechatReminderScheduler.schedule(settings.intervalMinutes)
        Log.i(TAG, "WeChat unread notification tracked")
    }

    /**
     * 在通知监听服务重新连接时用当前通知栏校准待提醒集合。
     *
     * 使用方法：
     * 仅由onListenerConnected调用。校准会排除支付、通话和系统通知，并把当前仍可见的通知
     * 合并进已经持久化的待提醒集合。没有符合条件的当前通知时也保留此前倒计时，因为微信的
     * 通知撤换不能可靠代表用户已经查看消息。
     *
     * @return 无返回值；系统暂时拒绝读取活跃通知时保留原状态并等待下一次通知回调。
     */
    private fun synchronizeActiveWechatNotifications() {
        val settings = wechatReminderRepository.getSettings()
        if (!settings.enabled) {
            wechatReminderRepository.clearPendingNotifications()
            wechatReminderScheduler.cancel()
            return
        }

        val currentNotifications = runCatching {
            activeNotifications.orEmpty()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read active notifications after listener connection", error)
            return
        }
        val eligibleNotifications = currentNotifications.filter { notification ->
            if (notification.packageName != WECHAT_PACKAGE_NAME) {
                return@filter false
            }

            val title = notification.notification.extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
            val content = extractNotificationContent(notification.notification)
            !WechatNotificationParser.parse(title, content).isFinancialNotification &&
                WechatMessageReminderPolicy.evaluate(title, content) ==
                WechatReminderDecision.REMIND
        }
        val latestMessageAtMillis = eligibleNotifications
            .maxOfOrNull(StatusBarNotification::getPostTime)
            ?: 0L
        val status = wechatReminderRepository.syncActiveNotifications(
            notificationKeys = eligibleNotifications.mapTo(mutableSetOf()) { it.key },
            latestMessageAtMillis = latestMessageAtMillis
        )

        if (status.pendingNotificationCount > 0) {
            wechatReminderScheduler.restore(
                intervalMinutes = settings.intervalMinutes,
                savedTriggerAtMillis = status.nextReminderAtMillis
            )
        } else {
            wechatReminderScheduler.cancelPendingAlarm()
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

    companion object {
        const val TAG = "WechatNotification"
        const val WECHAT_PACKAGE_NAME = "com.tencent.mm"

        @Volatile
        private var listenerConnected = false

        /**
         * 查询当前App进程中的微信通知监听服务是否已经收到Android连接回调。
         *
         * 使用方法：
         * 设置页通过NotificationAccessController读取本状态，区分“授权记录存在”和“服务实际在线”。
         * 进程重启时默认false，系统成功绑定后由onListenerConnected更新为true。
         *
         * @return 当前监听服务实际在线返回true，否则返回false。
         */
        fun isListenerConnected(): Boolean {
            return listenerConnected
        }
    }
}
