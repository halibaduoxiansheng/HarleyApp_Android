package com.example.harleyapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.model.AutoReplyStatus
import com.example.harleyapp.model.ScheduledWechatMessage
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus

/**
 * 显示快捷应用自定义页面和本地隐私说明。
 *
 * 使用方法：
 * 由HarleyApp在“更多”导航项选中时调用。用户勾选后立即通过回调持久化，
 * 首页会同步显示仍然安装的已选应用。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param apps 手机中当前可启动的应用列表。
 * @param selectedPackages 当前已选包名集合。
 * @param isLoading 是否仍在后台读取应用列表。
 * @param onSelectionChanged 用户选择变化后的完整集合回调。
 * @param autoReplySettings 微信自动回复当前配置。
 * @param autoReplyStatus 微信快捷回复兼容性和当天发送统计。
 * @param notificationAccessGranted 是否已获得Android通知使用权。
 * @param onSaveAutoReplySettings 保存自动回复配置回调。
 * @param onOpenNotificationAccess 打开系统通知使用权页面回调。
 * @param scheduledMessages 本机图文定时提醒计划。
 * @param notificationPermissionGranted 是否允许本应用显示到点提醒通知。
 * @param onRequestNotificationPermission 请求通知权限回调。
 * @param onSaveScheduledMessage 保存图文提醒计划回调。
 * @param onDeleteScheduledMessage 删除图文提醒计划回调。
 * @param cleanupStatus 本App缓存自动清理状态。
 * @param onSetAutomaticCleanup 修改自动清理开关回调。
 * @param onMeasureAppCache 统计本App缓存大小的挂起回调。
 * @param onCleanAppNow 手动清理本App缓存的挂起回调。
 * @param onOpenSystemStorage 打开系统存储管理页面回调。
 *
 * @return 无返回值，直接输出管理页面。
 */
@Composable
fun MoreScreen(
    apps: List<LaunchableApp>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onSelectionChanged: (Set<String>) -> Unit,
    autoReplySettings: AutoReplySettings,
    autoReplyStatus: AutoReplyStatus,
    notificationAccessGranted: Boolean,
    onSaveAutoReplySettings: (AutoReplySettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    scheduledMessages: List<ScheduledWechatMessage>,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onSaveScheduledMessage: (ScheduledWechatMessage) -> Boolean,
    onDeleteScheduledMessage: (Long) -> Boolean,
    cleanupStatus: LocalCleanupStatus,
    onSetAutomaticCleanup: (Boolean) -> Boolean,
    onMeasureAppCache: suspend () -> Long,
    onCleanAppNow: suspend () -> LocalCleanupResult,
    onOpenSystemStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchText by rememberSaveable {
        mutableStateOf("")
    }
    val filteredApps = remember(apps, searchText) {
        val keyword = searchText.trim()
        if (keyword.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(keyword, ignoreCase = true) ||
                    app.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }
    val availableSelectionCount = selectedPackages.count { selectedPackage ->
        apps.any { it.packageName == selectedPackage }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "更多工具",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "把常用应用固定到首页",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            AutoReplySettingsCard(
                settings = autoReplySettings,
                status = autoReplyStatus,
                notificationAccessGranted = notificationAccessGranted,
                onSaveSettings = onSaveAutoReplySettings,
                onOpenNotificationAccess = onOpenNotificationAccess
            )
        }

        item {
            ScheduledMessageCard(
                messages = scheduledMessages,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onSaveMessage = onSaveScheduledMessage,
                onDeleteMessage = onDeleteScheduledMessage
            )
        }

        item {
            LocalCleanupCard(
                status = cleanupStatus,
                onSetAutomaticEnabled = onSetAutomaticCleanup,
                onMeasureCache = onMeasureAppCache,
                onCleanNow = onCleanAppNow,
                onOpenSystemStorage = onOpenSystemStorage
            )
        }

        item {
            PrivacyCard()
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "快捷应用管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已选 $availableSelectionCount",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = searchText,
                onValueChange = {
                    searchText = it.take(MAX_SEARCH_LENGTH)
                },
                label = {
                    Text(text = "搜索应用名称")
                },
                leadingIcon = {
                    Text(text = "⌕")
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
        }

        when {
            isLoading -> {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(text = "正在读取可启动应用…")
                    }
                }
            }

            filteredApps.isEmpty() -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            modifier = Modifier.padding(22.dp),
                            text = if (searchText.isBlank()) {
                                "没有读取到可启动应用，请重新进入页面后再试。"
                            } else {
                                "没有找到包含“$searchText”的应用。"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            else -> {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    AppSelectionRow(
                        app = app,
                        selected = app.packageName in selectedPackages,
                        onSelectedChanged = { selected ->
                            val updatedSelection = selectedPackages.toMutableSet().apply {
                                if (selected) {
                                    add(app.packageName)
                                } else {
                                    remove(app.packageName)
                                }
                            }
                            onSelectionChanged(updatedSelection)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 显示本应用当前的数据与权限边界。
 *
 * @return 无返回值。
 */
@Composable
private fun PrivacyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "本地优先",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "账目、快捷选择和自动回复设置仅保存在当前手机。授权后，App只读取微信系统通知：普通聊天正文仅用于当次快捷回复且不保存；支付通知摘要可能保留到本机账本或待确认列表。App不读取微信数据库、照片或文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * 显示单个可启动应用及选择框。
 *
 * @param app 待显示应用。
 * @param selected 当前是否已选择。
 * @param onSelectedChanged 选择状态变化回调。
 *
 * @return 无返回值。
 */
@Composable
private fun AppSelectionRow(
    app: LaunchableApp,
    selected: Boolean,
    onSelectedChanged: (Boolean) -> Unit
) {
    val imageBitmap = remember(app.icon) {
        app.icon.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onSelectedChanged(!selected)
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = app.label,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChanged
            )
        }
    }
}

private const val MAX_SEARCH_LENGTH = 50
