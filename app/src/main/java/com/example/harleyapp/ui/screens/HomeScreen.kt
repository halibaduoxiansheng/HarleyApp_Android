package com.example.harleyapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.DeviceSnapshot
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.NetworkSample
import com.example.harleyapp.ui.components.bouncyClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 显示首页概览、实时设备状态和用户快捷应用。
 *
 * 使用方法：
 * 由HarleyApp在首页导航项选中时调用。页面可见期间每秒读取一次轻量系统状态，
 * 离开页面后LaunchedEffect会自动取消，不会继续后台刷新。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param deviceMonitor 设备状态读取服务。
 * @param shortcuts 用户已选且仍然安装的快捷应用。
 * @param onLaunchApp 点击快捷应用后的启动回调，参数为应用包名。
 * @param onManageShortcuts 前往快捷应用管理页面的回调。
 * @param websites 用户保存的首页网站轮播列表。
 * @param defaultWebsiteId 点击底部“网站”时默认打开的网站标识。
 * @param companionProgress 玩偶当前经验、分类和今日任务状态。
 * @param onOpenWebsite 前往内置网站页面的回调，参数为用户点击的网站。
 * @param onSetDefaultWebsite 把指定网站设为底部网站页签默认入口的回调。
 * @param onOpenHotTopics 点击“每日热点”功能卡片后进入独立详情页的回调。
 * @param onOpenMobileData 点击“手机流量”功能卡片后进入蜂窝流量详情页的回调。
 * @param onSaveWebsite 新增或编辑网站的同步保存回调，成功返回true。
 * @param onDeleteWebsite 删除网站的同步回调，成功返回true。
 * @param onSelectCompanionCategory 更换玩偶分类的同步保存回调，成功返回true。
 *
 * @return 无返回值，直接输出首页界面。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    deviceMonitor: DeviceMonitor,
    shortcuts: List<LaunchableApp>,
    onLaunchApp: (String) -> Unit,
    onManageShortcuts: () -> Unit,
    websites: List<WebsiteShortcut>,
    defaultWebsiteId: String?,
    companionProgress: CompanionProgress,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onSetDefaultWebsite: (String) -> Boolean,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onSaveWebsite: (WebsiteShortcut) -> Boolean,
    onDeleteWebsite: (String) -> Boolean,
    onSelectCompanionCategory: (CompanionCategory) -> Boolean
) {
    var snapshot by remember {
        mutableStateOf(DeviceSnapshot())
    }

    // 仅在首页可见时每秒采样，避免无意义地常驻消耗电量。
    LaunchedEffect(deviceMonitor) {
        var previousSample: NetworkSample? = null

        while (isActive) {
            val reading = withContext(Dispatchers.Default) {
                deviceMonitor.read(previousSample)
            }
            snapshot = reading.snapshot
            previousSample = reading.networkSample
            delay(1_000L)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            WebsiteCarousel(
                websites = websites,
                defaultWebsiteId = defaultWebsiteId,
                onOpenWebsite = onOpenWebsite,
                onSetDefaultWebsite = onSetDefaultWebsite,
                onSaveWebsite = onSaveWebsite,
                onDeleteWebsite = onDeleteWebsite
            )
        }

        item {
            SectionTitle(
                title = "功能中心",
                subtitle = "点击卡片进入独立功能页"
            )
        }

        item {
            FeatureEntryCard(
                title = "每日热点",
                description = "查看微博、百度、知乎和抖音热门榜单",
                symbol = "热",
                statusLabel = "实时",
                onClick = onOpenHotTopics
            )
        }

        item {
            FeatureEntryCard(
                title = "手机流量",
                description = "查看今日、本周、本月手机流量和应用排行",
                symbol = "流",
                statusLabel = "仅蜂窝",
                onClick = onOpenMobileData
            )
        }

        item {
            SectionTitle(
                title = "我的伙伴",
                subtitle = "完成每日任务，解锁新形态与技能"
            )
        }

        item {
            CompanionCard(
                progress = companionProgress,
                onSelectCategory = onSelectCompanionCategory
            )
        }

        item {
            SectionTitle(
                title = "实时状态",
                subtitle = "每秒刷新 · 不主动消耗流量"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "↑",
                    title = "上传",
                    value = formatSpeed(snapshot.uploadBytesPerSecond),
                    highlighted = true
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "↓",
                    title = "下载",
                    value = formatSpeed(snapshot.downloadBytesPerSecond),
                    highlighted = false
                )
            }
        }

        item {
            UsageCard(
                title = "物理运行内存",
                totalBytes = snapshot.totalMemoryBytes,
                availableBytes = snapshot.availableMemoryBytes,
                note = physicalMemoryNote(snapshot.totalMemoryBytes)
            )
        }

        if (snapshot.totalSwapBytes > 0L) {
            item {
                UsageCard(
                    title = "交换 / 扩展空间",
                    totalBytes = snapshot.totalSwapBytes,
                    availableBytes = snapshot.availableSwapBytes,
                    note = "由系统用作内存扩展和交换空间，性能不等同于物理RAM"
                )
            }
        }

        item {
            UsageCard(
                title = "内部存储",
                totalBytes = snapshot.totalStorageBytes,
                availableBytes = snapshot.availableStorageBytes,
                note = "手机主用户数据分区的容量"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    title = "快捷应用",
                    subtitle = if (shortcuts.isEmpty()) "还没有选择应用" else "点击即可打开"
                )

                TextButton(onClick = onManageShortcuts) {
                    Text(text = "管理")
                }
            }
        }

        if (shortcuts.isEmpty()) {
            item {
                EmptyShortcutCard(onManageShortcuts = onManageShortcuts)
            }
        } else {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = shortcuts,
                        key = { it.packageName }
                    ) { app ->
                        ShortcutItem(
                            app = app,
                            onClick = {
                                onLaunchApp(app.packageName)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示一个章节标题及可选说明。
 *
 * @param modifier 外部布局修饰器。
 * @param title 章节标题。
 * @param subtitle 辅助说明。
 *
 * @return 无返回值。
 */
@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示单项实时速率指标。
 *
 * @param modifier 外部布局修饰器。
 * @param symbol 上行或下行方向符号。
 * @param title 指标名称。
 * @param value 已格式化的速率文本。
 * @param highlighted 是否使用主色强调。
 *
 * @return 无返回值。
 */
@Composable
private fun MetricCard(
    symbol: String,
    title: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * 显示内存或存储空间的已用量、总量和比例进度条。
 *
 * @param title 指标名称。
 * @param totalBytes 总容量。
 * @param availableBytes 当前可用容量。
 * @param note 指标口径说明。
 *
 * @return 无返回值。
 */
@Composable
private fun UsageCard(
    title: String,
    totalBytes: Long,
    availableBytes: Long,
    note: String
) {
    val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)
    val progress = if (totalBytes > 0L) {
        (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Text(
                text = "已用 ${formatBytes(usedBytes)}  ·  可用 ${formatBytes(availableBytes)}  ·  总计 ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
            )
        }
    }
}

/**
 * 显示尚未选择快捷应用时的引导卡片。
 *
 * @param onManageShortcuts 点击选择按钮后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyShortcutCard(onManageShortcuts: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onManageShortcuts),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "选择微信、相机、音乐等常用应用，之后可以从首页一键打开。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "去选择 →",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 显示单个快捷应用图标和名称。
 *
 * @param app 待显示的可启动应用。
 * @param onClick 点击后的启动回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ShortcutItem(
    app: LaunchableApp,
    onClick: () -> Unit
) {
    val imageBitmap = remember(app.icon) {
        app.icon.asImageBitmap()
    }

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(18.dp))
            .bouncyClickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "打开${app.label}",
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 把字节数转换为便于阅读的容量文本。
 *
 * 使用方法：
 * 传入系统返回的字节数，例如formatBytes(1073741824)返回“1.0 GB”。
 *
 * @param bytes 原始字节数，负值会按0处理。
 *
 * @return 带B、KB、MB、GB或TB单位的文本。
 */
private fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        String.format(Locale.CHINA, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.CHINA, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * 把字节每秒转换为速率文本。
 *
 * @param bytesPerSecond 每秒字节数。
 *
 * @return 带“/s”后缀的易读速率。
 */
private fun formatSpeed(bytesPerSecond: Long): String {
    return "${formatBytes(bytesPerSecond)}/s"
}

/**
 * 根据Android实际可用物理RAM估算常见的厂商标称容量，并生成口径说明。
 *
 * @param totalBytes Android内核报告的物理RAM字节数。
 *
 * @return 包含厂商标称容量和“不含内存扩展”提醒的说明文本。
 */
private fun physicalMemoryNote(totalBytes: Long): String {
    if (totalBytes <= 0L) {
        return "系统物理RAM，不含内存扩展"
    }

    val decimalGigabytes = totalBytes.toDouble() / 1_000_000_000.0
    val commonCapacities = listOf(2, 3, 4, 6, 8, 12, 16, 24, 32)
    val marketingCapacity = commonCapacities.minByOrNull { capacity ->
        kotlin.math.abs(capacity - decimalGigabytes)
    }

    return if (marketingCapacity != null) {
        "约等于厂商标称 $marketingCapacity GB，不含下方内存扩展"
    } else {
        "系统物理RAM，不含内存扩展"
    }
}
