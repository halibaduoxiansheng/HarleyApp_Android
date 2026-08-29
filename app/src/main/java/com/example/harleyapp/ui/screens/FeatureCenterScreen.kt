package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.ScheduledWechatMessage
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.ui.components.bouncyClickable

/**
 * 功能中心内部可以切换的概览页和工具详情页。
 *
 * 使用方法：
 * HarleyApp通过rememberSaveable保存枚举名称。用户点击功能卡片后传入对应详情值，点击返回或
 * 系统返回键时恢复OVERVIEW。记账和运动属于独立全屏详情，因此由HarleyApp单独导航。
 */
enum class FeatureCenterPage {
    OVERVIEW,
    WECHAT_REMINDER,
    GENERAL_REMINDER,
    SCHEDULED_MESSAGE,
    LOCAL_CLEANUP
}

/**
 * 集中展示记账、运动、提醒与清理功能，并承载轻量工具的详情页面。
 *
 * 使用方法：
 * 由HarleyApp在底部“功能”被选中时调用。记账和运动通过独立回调打开原有完整页面；其他工具
 * 在功能中心内部切换详情，所有仓库、权限和保存回调仍由HarleyApp统一提供。
 *
 * @param page 当前功能中心页面。
 * @param onPageChanged 切换功能中心概览或内部详情的回调。
 * @param onOpenLedger 打开原有完整记账页面的回调。
 * @param onOpenFitness 打开原有完整运动页面的回调。
 * @param onOpenHotTopics 打开每日热点完整页面的回调。
 * @param onOpenMobileData 打开手机流量统计页面的回调。
 * @param wechatReminderSettings 微信未查看消息提醒设置。
 * @param wechatReminderStatus 微信提醒监听状态。
 * @param notificationAccessGranted 是否已授予通知使用权。
 * @param notificationListenerConnected 通知监听服务是否已连接。
 * @param onSaveWechatReminderSettings 保存微信提醒设置的回调。
 * @param onOpenNotificationAccess 打开通知使用权设置的回调。
 * @param reminders 普通通知提醒计划。
 * @param scheduledMessages 图文定时提醒计划。
 * @param notificationPermissionGranted 是否具备发送通知权限。
 * @param onRequestNotificationPermission 请求发送通知权限的回调。
 * @param onSaveReminder 新增或更新普通提醒的回调。
 * @param onDeleteReminder 删除普通提醒的回调。
 * @param onSaveScheduledMessage 新增或更新图文提醒的回调。
 * @param onDeleteScheduledMessage 删除图文提醒的回调。
 * @param cleanupStatus 本App缓存清理状态。
 * @param onSetAutomaticCleanup 修改自动清理开关的回调。
 * @param onMeasureAppCache 统计可清理缓存的回调。
 * @param onCleanAppNow 立即执行本App缓存清理的回调。
 * @param onOpenSystemStorage 打开系统存储管理页面的回调。
 * @param modifier 外部传入的安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出功能中心概览或选中的详情页面。
 */
@Composable
fun FeatureCenterScreen(
    page: FeatureCenterPage,
    onPageChanged: (FeatureCenterPage) -> Unit,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    wechatReminderSettings: WechatReminderSettings,
    wechatReminderStatus: WechatReminderStatus,
    notificationAccessGranted: Boolean,
    notificationListenerConnected: Boolean,
    onSaveWechatReminderSettings: (WechatReminderSettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    reminders: List<ScheduledReminder>,
    scheduledMessages: List<ScheduledWechatMessage>,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onSaveReminder: (ScheduledReminder) -> Boolean,
    onDeleteReminder: (Long) -> Boolean,
    onSaveScheduledMessage: (ScheduledWechatMessage) -> Boolean,
    onDeleteScheduledMessage: (Long) -> Boolean,
    cleanupStatus: LocalCleanupStatus,
    onSetAutomaticCleanup: (Boolean) -> Boolean,
    onMeasureAppCache: suspend () -> Long,
    onCleanAppNow: suspend () -> LocalCleanupResult,
    onOpenSystemStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (page) {
        FeatureCenterPage.OVERVIEW -> FeatureCenterOverview(
            modifier = modifier,
            onOpenLedger = onOpenLedger,
            onOpenFitness = onOpenFitness,
            onOpenHotTopics = onOpenHotTopics,
            onOpenMobileData = onOpenMobileData,
            onOpenWechatReminder = {
                onPageChanged(FeatureCenterPage.WECHAT_REMINDER)
            },
            onOpenGeneralReminder = {
                onPageChanged(FeatureCenterPage.GENERAL_REMINDER)
            },
            onOpenScheduledMessage = {
                onPageChanged(FeatureCenterPage.SCHEDULED_MESSAGE)
            },
            onOpenLocalCleanup = {
                onPageChanged(FeatureCenterPage.LOCAL_CLEANUP)
            }
        )

        FeatureCenterPage.WECHAT_REMINDER -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "微信消息提醒",
            subtitle = "按时间段提醒仍未查看的微信消息",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            WechatReminderSettingsCard(
                settings = wechatReminderSettings,
                status = wechatReminderStatus,
                notificationAccessGranted = notificationAccessGranted,
                notificationListenerConnected = notificationListenerConnected,
                notificationPermissionGranted = notificationPermissionGranted,
                onSaveSettings = onSaveWechatReminderSettings,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onRequestNotificationPermission = onRequestNotificationPermission
            )
        }

        FeatureCenterPage.GENERAL_REMINDER -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "通知提醒",
            subtitle = "管理一次性或按天重复的本机提醒",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            ReminderCard(
                reminders = reminders,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onSaveReminder = onSaveReminder,
                onDeleteReminder = onDeleteReminder
            )
        }

        FeatureCenterPage.SCHEDULED_MESSAGE -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "图文定时提醒",
            subtitle = "到点提醒并准备需要发送的文字和图片",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            ScheduledMessageCard(
                messages = scheduledMessages,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onSaveMessage = onSaveScheduledMessage,
                onDeleteMessage = onDeleteScheduledMessage
            )
        }

        FeatureCenterPage.LOCAL_CLEANUP -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "手机清理",
            subtitle = "清理本App缓存或打开系统存储管理",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            LocalCleanupCard(
                status = cleanupStatus,
                onSetAutomaticEnabled = onSetAutomaticCleanup,
                onMeasureCache = onMeasureAppCache,
                onCleanNow = onCleanAppNow,
                onOpenSystemStorage = onOpenSystemStorage
            )
        }
    }
}

/**
 * 显示功能中心六个主要入口。
 *
 * @param modifier 外部布局修饰器。
 * @param onOpenLedger 打开记账功能的回调。
 * @param onOpenFitness 打开运动功能的回调。
 * @param onOpenHotTopics 打开每日热点的回调。
 * @param onOpenMobileData 打开手机流量统计的回调。
 * @param onOpenWechatReminder 打开微信消息提醒的回调。
 * @param onOpenGeneralReminder 打开普通通知提醒的回调。
 * @param onOpenScheduledMessage 打开图文定时提醒的回调。
 * @param onOpenLocalCleanup 打开手机清理的回调。
 *
 * @return 无返回值，直接输出功能入口网格。
 */
@Composable
private fun FeatureCenterOverview(
    modifier: Modifier,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenWechatReminder: () -> Unit,
    onOpenGeneralReminder: () -> Unit,
    onOpenScheduledMessage: () -> Unit,
    onOpenLocalCleanup: () -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 22.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "功能中心",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "记录、提醒和设备工具都集中在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            FeatureEntryRow(
                first = FeatureEntry(
                    symbol = "¥",
                    title = "记账",
                    subtitle = "账目、汇总与微信账单",
                    onClick = onOpenLedger
                ),
                second = FeatureEntry(
                    symbol = "动",
                    title = "运动",
                    subtitle = "目标、记录与区间分析",
                    onClick = onOpenFitness
                )
            )
        }

        item {
            FeatureEntryRow(
                first = FeatureEntry(
                    symbol = "热",
                    title = "每日热点",
                    subtitle = "查看今日网络热点摘要",
                    onClick = onOpenHotTopics
                ),
                second = FeatureEntry(
                    symbol = "流",
                    title = "手机流量",
                    subtitle = "蜂窝流量统计与排行",
                    onClick = onOpenMobileData
                )
            )
        }

        item {
            FeatureEntryRow(
                first = FeatureEntry(
                    symbol = "微",
                    title = "微信消息提醒",
                    subtitle = "未查看消息重复提醒",
                    onClick = onOpenWechatReminder
                ),
                second = FeatureEntry(
                    symbol = "铃",
                    title = "通知提醒",
                    subtitle = "一次或重复本机通知",
                    onClick = onOpenGeneralReminder
                )
            )
        }

        item {
            FeatureEntryRow(
                first = FeatureEntry(
                    symbol = "图",
                    title = "图文定时提醒",
                    subtitle = "定时准备文字和图片",
                    onClick = onOpenScheduledMessage
                ),
                second = FeatureEntry(
                    symbol = "清",
                    title = "手机清理",
                    subtitle = "缓存统计与存储管理",
                    onClick = onOpenLocalCleanup
                )
            )
        }
    }
}

/** 单个功能入口所需的展示内容和点击回调。 */
private data class FeatureEntry(
    val symbol: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

/**
 * 把两个功能入口等宽排列为一行。
 *
 * @param first 左侧功能入口。
 * @param second 右侧功能入口。
 *
 * @return 无返回值。
 */
@Composable
private fun FeatureEntryRow(
    first: FeatureEntry,
    second: FeatureEntry
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FeatureEntryCard(
            modifier = Modifier.weight(1f),
            entry = first
        )
        FeatureEntryCard(
            modifier = Modifier.weight(1f),
            entry = second
        )
    }
}

/**
 * 显示一个可点击的功能入口卡片。
 *
 * @param entry 功能名称、说明、图标字符和点击回调。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun FeatureEntryCard(
    entry: FeatureEntry,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.bouncyClickable(onClick = entry.onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    text = entry.symbol,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 为记账、运动和功能中心内部工具提供统一的返回标题栏与内容容器。
 *
 * 使用方法：
 * 独立详情页把原页面作为content传入；用户点击“返回功能中心”或系统返回键都会执行onBack。
 *
 * @param title 当前详情标题。
 * @param subtitle 当前详情的简短说明。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部传入的安全边距。
 * @param content 详情主体，参数是已经填满剩余区域的Modifier。
 *
 * @return 无返回值，直接输出统一详情框架。
 */
@Composable
fun FeatureDetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "← 功能中心")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
    }
}

/**
 * 把原有功能卡片放入统一详情框架，并提供独立滚动空间。
 *
 * @param title 详情标题。
 * @param subtitle 详情说明。
 * @param onBack 返回概览的回调。
 * @param modifier 外部安全边距。
 * @param cardContent 原有功能卡片内容。
 *
 * @return 无返回值。
 */
@Composable
private fun FeatureCardDetailScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable () -> Unit
) {
    FeatureDetailScaffold(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        onBack = onBack
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                cardContent()
            }
        }
    }
}
