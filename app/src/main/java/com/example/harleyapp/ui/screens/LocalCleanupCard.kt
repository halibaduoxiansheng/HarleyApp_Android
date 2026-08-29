package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 显示本App缓存自动清理、手动清理和系统存储管理入口。
 *
 * 使用方法：
 * MoreScreen传入LocalCleanupManager状态与回调。页面只显示和清理Harley生活助手自身缓存；
 * 点击“系统存储管理”交由Android或小米系统页面处理全机文件，不申请清理其他App的权限。
 *
 * @param status 自动清理开关和最近一次结果。
 * @param onSetAutomaticEnabled 修改启动时自动清理开关的回调。
 * @param onMeasureCache 重新统计本App可清理缓存字节数的挂起回调。
 * @param onCleanNow 立即清理本App缓存的挂起回调。
 * @param onOpenSystemStorage 打开系统存储管理页面回调。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出Compose卡片。
 */
@Composable
fun LocalCleanupCard(
    status: LocalCleanupStatus,
    onSetAutomaticEnabled: (Boolean) -> Boolean,
    onMeasureCache: suspend () -> Long,
    onCleanNow: suspend () -> LocalCleanupResult,
    onOpenSystemStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var automaticEnabled by remember(status.automaticEnabled) {
        mutableStateOf(status.automaticEnabled)
    }
    var reclaimableBytes by remember {
        mutableLongStateOf(0L)
    }
    var isCleaning by remember {
        mutableStateOf(false)
    }
    var feedbackText by remember {
        mutableStateOf("")
    }

    LaunchedEffect(status.lastCleanupAtMillis) {
        reclaimableBytes = onMeasureCache()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "安全缓存清理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "本App可清理缓存：${formatStorageBytes(reclaimableBytes)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启动时自动清理过期缓存",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "每天最多检查一次，只删除7天前的本App缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = automaticEnabled,
                    onCheckedChange = { enabled ->
                        if (onSetAutomaticEnabled(enabled)) {
                            automaticEnabled = enabled
                            feedbackText = if (enabled) "自动清理已开启" else "自动清理已关闭"
                        } else {
                            feedbackText = "设置保存失败，请重试"
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isCleaning,
                    onClick = {
                        coroutineScope.launch {
                            isCleaning = true
                            val result = onCleanNow()
                            reclaimableBytes = onMeasureCache()
                            feedbackText = if (result.errorMessage.isNotBlank()) {
                                result.errorMessage
                            } else {
                                "已释放${formatStorageBytes(result.freedBytes)}，清理${result.removedExpiredCaptures}条过期微信摘要"
                            }
                            isCleaning = false
                        }
                    }
                ) {
                    Text(text = if (isCleaning) "清理中…" else "立即清理本App")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSystemStorage
                ) {
                    Text(text = "系统存储管理")
                }
            }

            if (feedbackText.isNotBlank()) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val lastCleanupText = formatCleanupTime(status.lastCleanupAtMillis)
            Text(
                text = if (lastCleanupText.isBlank()) {
                    "尚未执行清理。"
                } else {
                    "上次清理：$lastCleanupText，释放${formatStorageBytes(status.lastFreedBytes)}。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Android会自行管理运行内存。本功能不会强制结束微信或其他App，也不会删除它们的数据；全机清理由系统页面完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 将字节数格式化为B、KiB、MiB或GiB。
 *
 * @param bytes 非负字节数。
 *
 * @return 保留一位小数的易读容量文本。
 */
private fun formatStorageBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    return when {
        safeBytes >= GIB -> "%.1f GiB".format(safeBytes / GIB)
        safeBytes >= MIB -> "%.1f MiB".format(safeBytes / MIB)
        safeBytes >= KIB -> "%.1f KiB".format(safeBytes / KIB)
        else -> "${safeBytes.toLong()} B"
    }
}

/**
 * 格式化最近一次缓存清理时间。
 *
 * @param timestampMillis Unix毫秒时间戳，0表示尚未清理。
 *
 * @return MM-dd HH:mm格式本地时间；没有记录时返回空字符串。
 */
private fun formatCleanupTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return ""
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(CLEANUP_TIME_FORMATTER)
}

private val CLEANUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val KIB = 1024.0
private const val MIB = 1024.0 * KIB
private const val GIB = 1024.0 * MIB
