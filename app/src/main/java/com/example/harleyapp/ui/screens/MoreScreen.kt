package com.example.harleyapp.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.example.harleyapp.data.AppUpdateRepository
import com.example.harleyapp.data.AppLockRepository
import com.example.harleyapp.model.AppUpdateCheckResult
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.model.AppUpdateDownloadPhase
import com.example.harleyapp.model.AppUpdateDownloadState
import com.example.harleyapp.model.AppUpdateInfo
import com.example.harleyapp.model.AppUpdateInstallResult
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.DeviceSnapshot
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.NetworkSample
import com.example.harleyapp.ui.components.bouncyClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 显示“我的”页面，包括用户资料、健康建议、外观、紧凑设备状态、快捷应用和本地隐私信息。
 *
 * 使用方法：
 * 由HarleyApp在底部“我的”选中时调用。用户切换主题或勾选快捷应用后立即通过回调持久化，
 * 首页会同步显示仍然安装的已选应用；版本信息直接从当前已安装包读取。页面可见期间每5秒
 * 更新一次物理内存、交换空间和内部存储，离开页面后协程自动取消。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param isDarkTheme 当前是否启用黑夜模式。
 * @param visualTheme 当前整体角色风格主题。
 * @param startupAnimationEnabled 下次进入App时是否播放启动动画。
 * @param onSetDarkTheme 保存并应用白天或黑夜模式的回调，成功返回true。
 * @param onSetVisualTheme 保存并应用角色风格主题的回调，成功返回true。
 * @param onSetStartupAnimationEnabled 保存启动动画开关的回调，成功返回true。
 * @param deviceMonitor 读取Android公开内存和内部存储状态的服务。
 * @param apps 手机中当前可启动的应用列表。
 * @param selectedPackages 当前已选包名集合。
 * @param isLoading 是否仍在后台读取应用列表。
 * @param onSelectionChanged 用户选择变化后的完整集合回调。
 * @param onOpenProjectSource 在App内置网站页打开GitHub源码仓库的回调。
 * @param developerModeConfigured 当前安装包是否配置了有效开发者密钥摘要。
 * @param developerModeEnabled 当前设备是否已通过开发者密钥验证。
 * @param companionProgress 当前伙伴等级和金币状态。
 * @param onVerifyDeveloperKey 校验并启用开发者模式的回调。
 * @param onDisableDeveloperMode 退出开发者模式的回调。
 * @param onAddDeveloperLevels 增加伙伴等级的开发者回调。
 * @param onAddDeveloperCoins 增加伙伴金币的开发者回调。
 *
 * @return 无返回值，直接输出“我的”页面。
 */
@Composable
fun ProfileScreen(
    isDarkTheme: Boolean,
    visualTheme: AppVisualTheme,
    startupAnimationEnabled: Boolean,
    onSetDarkTheme: (Boolean) -> Boolean,
    onSetVisualTheme: (AppVisualTheme) -> Boolean,
    onSetStartupAnimationEnabled: (Boolean) -> Boolean,
    deviceMonitor: DeviceMonitor,
    apps: List<LaunchableApp>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onSelectionChanged: (Set<String>) -> Unit,
    onOpenProjectSource: () -> Unit,
    developerModeConfigured: Boolean,
    developerModeEnabled: Boolean,
    companionProgress: CompanionProgress,
    onVerifyDeveloperKey: (String) -> Boolean,
    onDisableDeveloperMode: () -> Boolean,
    onAddDeveloperLevels: (Int) -> Boolean,
    onAddDeveloperCoins: (Int) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appInfo = remember(context) {
        readAppInfo(context)
    }
    val appLockRepository = remember(context) {
        AppLockRepository(context.applicationContext)
    }
    var showAppPicker by rememberSaveable {
        mutableStateOf(false)
    }
    val selectedApps = remember(apps, selectedPackages) {
        apps.filter { app -> app.packageName in selectedPackages }
    }
    var deviceSnapshot by remember {
        mutableStateOf(DeviceSnapshot())
    }

    // “我的”页面可见时低频更新容量信息，既保持数值新鲜，也避免为缓慢变化的数据每秒采样。
    LaunchedEffect(deviceMonitor) {
        var previousSample: NetworkSample? = null

        while (isActive) {
            val reading = withContext(Dispatchers.Default) {
                deviceMonitor.read(previousSample)
            }
            deviceSnapshot = reading.snapshot
            previousSample = reading.networkSample
            delay(DEVICE_STATUS_REFRESH_INTERVAL_MILLIS)
        }
    }

    if (showAppPicker) {
        ShortcutAppPickerDialog(
            apps = apps,
            selectedPackages = selectedPackages,
            isLoading = isLoading,
            onDismiss = {
                showAppPicker = false
            },
            onSelectionChanged = onSelectionChanged
        )
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
                text = "我的",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "个人资料、健康建议、外观与快捷方式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            UserProfileHealthCard()
        }

        item {
            AppearanceCard(
                isDarkTheme = isDarkTheme,
                visualTheme = visualTheme,
                onSetDarkTheme = onSetDarkTheme,
                onSetVisualTheme = onSetVisualTheme
            )
        }

        item {
            StartupAnimationSettingsCard(
                enabled = startupAnimationEnabled,
                onEnabledChanged = onSetStartupAnimationEnabled
            )
        }

        item {
            ChargingEffectSettingsCard()
        }

        item {
            BrowserBookmarkSettingsCard()
        }

        item {
            AppLockSettingsCard(repository = appLockRepository)
        }

        item {
            DeveloperModeCard(
                configured = developerModeConfigured,
                enabled = developerModeEnabled,
                progress = companionProgress,
                onVerifyKey = onVerifyDeveloperKey,
                onDisable = onDisableDeveloperMode,
                onAddLevels = onAddDeveloperLevels,
                onAddCoins = onAddDeveloperCoins
            )
        }

        item {
            AppInfoCard(appInfo = appInfo)
        }

        item {
            CompactDeviceStatusCard(snapshot = deviceSnapshot)
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
                    text = "已添加 ${selectedApps.size}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = {
                        showAppPicker = true
                    },
                    enabled = !isLoading
                ) {
                    Text(text = if (isLoading) "读取中" else "添加应用")
                }
            }
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

            selectedApps.isEmpty() -> {
                item {
                    EmptyShortcutSelectionCard(
                        onAddApp = {
                            showAppPicker = true
                        }
                    )
                }
            }

            else -> {
                items(
                    items = selectedApps,
                    key = { it.packageName }
                ) { app ->
                    AppSelectionRow(
                        app = app,
                        selected = true,
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

        item {
            PrivacyCard()
        }

        item {
            ProjectSourceCodeCard(
                onOpenSource = onOpenProjectSource
            )
        }
    }
}

/**
 * 在“我的”页面最底部显示项目源码入口。
 *
 * 使用方法：
 * 由[ProfileScreen]作为最后一个列表项调用。用户点击卡片或按钮时触发[onOpenSource]，外层
 * HarleyApp会切换到底部“网站”页面，并把GitHub仓库地址交给App自带WebView加载。
 *
 * @param onOpenSource 在App内打开项目GitHub页面的回调。
 * @return 无返回值，直接输出源码说明、仓库地址和操作按钮。
 */
@Composable
private fun ProjectSourceCodeCard(onOpenSource: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSource),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "项目源码",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "查看完整代码、版本标签与最新发布记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(text = "GitHub ↗", color = MaterialTheme.colorScheme.primary)
            }

            Text(
                text = "halibaduoxiansheng/HarleyApp_Android",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = onOpenSource) {
                Text("查看项目源码")
            }
        }
    }
}

/**
 * 用一张紧凑卡片集中展示CPU温度、物理内存、交换空间和内部存储，避免多个大卡片占据页面。
 *
 * 使用方法：
 * ProfileScreen把DeviceMonitor最新快照传入本函数。每个指标只显示已用量、总量和一条细进度条；
 * 系统没有启用交换空间时明确显示“未启用”，不会生成无意义的百分比。
 *
 * @param snapshot Android系统当前公开的CPU温度、物理内存、交换空间和内部存储快照。
 *
 * @return 无返回值，直接输出“我的”页面中的紧凑设备状态卡片。
 */
@Composable
private fun CompactDeviceStatusCard(snapshot: DeviceSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "设备状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "每5秒更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CompactTemperatureRow(
                temperatureCelsius = snapshot.cpuTemperatureCelsius
            )

            CompactUsageRow(
                title = "物理运行内存",
                totalBytes = snapshot.totalMemoryBytes,
                availableBytes = snapshot.availableMemoryBytes
            )
            CompactUsageRow(
                title = "交换 / 扩展空间",
                totalBytes = snapshot.totalSwapBytes,
                availableBytes = snapshot.availableSwapBytes,
                emptyText = "未启用"
            )
            CompactUsageRow(
                title = "内部存储",
                totalBytes = snapshot.totalStorageBytes,
                availableBytes = snapshot.availableStorageBytes
            )
        }
    }
}

/**
 * 显示设备状态中的当前CPU温度。
 *
 * 使用方法：
 * [CompactDeviceStatusCard]把DeviceMonitor读取到的摄氏温度传入。Android未向普通应用开放CPU热区时
 * 显示“系统未开放”，不会退而显示电池温度或虚构估算值。
 *
 * @param temperatureCelsius 当前可读CPU/SOC热区中的最高摄氏温度，无法读取时为null。
 *
 * @return 无返回值，直接输出温度名称、数值和数据来源说明。
 */
@Composable
private fun CompactTemperatureRow(temperatureCelsius: Float?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CPU 当前温度",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "读取CPU / SOC可访问热区，不使用电池温度代替",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = temperatureCelsius?.let { value ->
                String.format(Locale.CHINA, "%.1f°C", value)
            } ?: "系统未开放",
            style = MaterialTheme.typography.labelLarge,
            color = if (temperatureCelsius == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 显示设备状态卡片中的单行容量指标。
 *
 * @param title 指标名称。
 * @param totalBytes 系统报告的总容量，单位为字节。
 * @param availableBytes 当前可用容量，单位为字节。
 * @param emptyText 总容量不可用或为0时显示的说明文本。
 *
 * @return 无返回值，直接输出名称、已用/总量文本和细进度条。
 */
@Composable
private fun CompactUsageRow(
    title: String,
    totalBytes: Long,
    availableBytes: Long,
    emptyText: String = "读取中"
) {
    val safeTotalBytes = totalBytes.coerceAtLeast(0L)
    val usedBytes = (safeTotalBytes - availableBytes.coerceAtLeast(0L))
        .coerceIn(0L, safeTotalBytes)
    val progress = if (safeTotalBytes > 0L) {
        (usedBytes.toDouble() / safeTotalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (safeTotalBytes > 0L) {
                    "已用 ${formatDeviceBytes(usedBytes)} / ${formatDeviceBytes(safeTotalBytes)}"
                } else {
                    emptyText
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(5.dp))
        )
    }
}

/**
 * 把系统容量字节数转换为紧凑、易读的二进制容量文本。
 *
 * @param bytes 原始字节数，负值按0处理。
 *
 * @return 带B、KiB、MiB、GiB或TiB单位的文本。
 */
private fun formatDeviceBytes(bytes: Long): String {
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
 * 显示尚未添加任何快捷应用时的空状态，不主动展开手机中的全部应用。
 *
 * 使用方法：
 * ProfileScreen确认当前没有可用的已选应用后调用。用户只有点击卡片中的“添加应用”按钮，
 * 才会打开完整应用选择器，避免把系统应用列表误认为已经自动添加的快捷方式。
 *
 * @param onAddApp 用户主动要求打开应用选择器时的回调。
 *
 * @return 无返回值，直接输出空状态卡片。
 */
@Composable
private fun EmptyShortcutSelectionCard(onAddApp: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "还没有添加快捷应用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "这里只会显示你主动选择的应用，不会自动添加手机里的其他应用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onAddApp) {
                Text(text = "添加应用")
            }
        }
    }
}

/**
 * 在用户主动点击“添加应用”后显示可搜索的系统应用选择器。
 *
 * 使用方法：
 * ProfileScreen通过showAppPicker控制本对话框是否出现。勾选或取消勾选应用后，会把完整包名集合
 * 交给onSelectionChanged持久化；点击“完成”只关闭弹窗，不会额外改变选择结果。
 *
 * @param apps 手机中当前可启动的应用列表。
 * @param selectedPackages 当前已经保存的快捷应用包名集合。
 * @param isLoading 是否仍在后台读取可启动应用。
 * @param onDismiss 关闭选择器的回调。
 * @param onSelectionChanged 用户修改选择后提交完整包名集合的回调。
 *
 * @return 无返回值，直接输出应用选择对话框。
 */
@Composable
private fun ShortcutAppPickerDialog(
    apps: List<LaunchableApp>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelectionChanged: (Set<String>) -> Unit
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "添加快捷应用")
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "只有在这里勾选的应用才会出现在首页和“我的”页面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(text = "正在读取可启动应用…")
                        }
                    }

                    filteredApps.isEmpty() -> {
                        Text(
                            modifier = Modifier.padding(vertical = 20.dp),
                            text = if (searchText.isBlank()) {
                                "没有读取到可启动应用，请重新进入页面后再试。"
                            } else {
                                "没有找到包含“$searchText”的应用。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 390.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = filteredApps,
                                key = { app -> app.packageName }
                            ) { app ->
                                val selected = app.packageName in selectedPackages
                                AppSelectionRow(
                                    app = app,
                                    selected = selected,
                                    onSelectedChanged = { shouldSelect ->
                                        val updatedSelection = selectedPackages.toMutableSet().apply {
                                            if (shouldSelect) {
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "完成")
            }
        }
    )
}

/** 当前安装包与手机系统的基础展示信息。 */
private data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val androidVersion: String,
    val apiLevel: Int
)

/**
 * 读取当前已安装应用版本和Android系统版本。
 *
 * 使用方法：
 * ProfileScreen首次组合时调用一次并通过remember缓存。升级APK或重新创建页面后会读取新版本；
 * 读取失败时使用明确的未知版本占位，不影响“我的”页面其他设置。
 *
 * @param context 当前应用上下文，用于读取PackageManager和包名。
 *
 * @return 可直接显示的应用与系统基础信息。
 */
private fun readAppInfo(context: Context): AppInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    return AppInfo(
        versionName = packageInfo?.versionName.orEmpty().ifBlank { "未知" },
        versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: 0L,
        packageName = context.packageName,
        androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "未知" },
        apiLevel = Build.VERSION.SDK_INT
    )
}

/**
 * 显示应用版本信息，并承载由用户主动触发的OTA检查、后台下载进度和安装入口。
 *
 * 使用方法：
 * 用户点击版本号后才访问OTA清单。远端版本严格更高时先显示确认对话框，用户拒绝不会创建下载；
 * 用户确认后由DownloadManager在后台继续下载，本卡片轮询并展示进度，完成后提供系统安装按钮。
 *
 * @param appInfo 已读取并规范化的基础信息。
 *
 * @return 无返回值，直接输出版本信息、OTA状态和相关对话框。
 */
@Composable
private fun AppInfoCard(appInfo: AppInfo) {
    val context = LocalContext.current
    val updateRepository = remember(context) {
        AppUpdateRepository(context)
    }
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember {
        mutableStateOf(false)
    }
    var updateCandidate by remember {
        mutableStateOf<AppUpdateInfo?>(null)
    }
    var feedbackTitle by remember {
        mutableStateOf("")
    }
    var feedbackMessage by remember {
        mutableStateOf("")
    }
    var downloadState by remember {
        mutableStateOf(AppUpdateDownloadState())
    }
    var confirmDeleteDownload by remember {
        mutableStateOf(false)
    }

    // 页面重新进入时恢复系统下载任务，避免App退到后台后进度条丢失。
    LaunchedEffect(updateRepository) {
        downloadState = withContext(Dispatchers.IO) {
            updateRepository.getTrackedDownloadState()
        }
    }

    // DownloadManager负责真正的后台传输；页面可见时低频查询进度供用户查看。
    LaunchedEffect(downloadState.downloadId, downloadState.phase) {
        val downloadId = downloadState.downloadId ?: return@LaunchedEffect
        while (
            downloadState.phase == AppUpdateDownloadPhase.PENDING ||
            downloadState.phase == AppUpdateDownloadPhase.RUNNING ||
            downloadState.phase == AppUpdateDownloadPhase.PAUSED
        ) {
            delay(UPDATE_DOWNLOAD_REFRESH_INTERVAL_MILLIS)
            downloadState = withContext(Dispatchers.IO) {
                updateRepository.queryDownloadState(downloadId)
            }
        }
    }

    updateCandidate?.let { updateInfo ->
        AlertDialog(
            onDismissRequest = {
                updateCandidate = null
            },
            title = {
                Text(text = "发现新版本 ${updateInfo.latestVersion}")
            },
            text = {
                Text(
                    text = "当前版本 ${appInfo.versionName}。确认后会在后台下载安装包，" +
                        "下载期间可以继续使用App；完成后仍需在Android系统界面确认安装。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateCandidate = null
                        coroutineScope.launch {
                            val enqueueResult = withContext(Dispatchers.IO) {
                                updateRepository.enqueueUpdate(updateInfo)
                            }
                            enqueueResult.onSuccess { downloadId ->
                                downloadState = withContext(Dispatchers.IO) {
                                    updateRepository.queryDownloadState(downloadId)
                                }
                            }.onFailure { error ->
                                feedbackTitle = "下载未开始"
                                feedbackMessage = error.message.orEmpty().ifBlank {
                                    "无法创建后台下载任务，请稍后重试"
                                }
                            }
                        }
                    }
                ) {
                    Text(text = "下载升级")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        updateCandidate = null
                    }
                ) {
                    Text(text = "暂不升级")
                }
            }
        )
    }

    if (confirmDeleteDownload) {
        AlertDialog(
            onDismissRequest = {
                confirmDeleteDownload = false
            },
            title = {
                Text(text = "删除已下载安装包？")
            },
            text = {
                Text(
                    text = "将删除版本 ${downloadState.targetVersion} 的APK和系统下载记录。" +
                        "以后仍可重新检查并下载。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDeleteDownload = false
                        coroutineScope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                updateRepository.deleteTrackedDownload()
                            }
                            if (deleted) {
                                downloadState = AppUpdateDownloadState()
                                feedbackTitle = "安装包已删除"
                                feedbackMessage = "已清理下载文件，需要时可重新检查更新。"
                            } else {
                                downloadState = withContext(Dispatchers.IO) {
                                    updateRepository.getTrackedDownloadState()
                                }
                                feedbackTitle = "删除失败"
                                feedbackMessage = "无法完整删除安装包，请稍后重试。"
                            }
                        }
                    }
                ) {
                    Text(text = "确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmDeleteDownload = false
                    }
                ) {
                    Text(text = "保留安装包")
                }
            }
        )
    }

    if (feedbackMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = {
                feedbackTitle = ""
                feedbackMessage = ""
            },
            title = {
                Text(text = feedbackTitle.ifBlank { "版本检查" })
            },
            text = {
                Text(text = feedbackMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        feedbackTitle = ""
                        feedbackMessage = ""
                    }
                ) {
                    Text(text = "知道了")
                }
            }
        )
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
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "Harley生活助手",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.bouncyClickable {
                    when (downloadState.phase) {
                        AppUpdateDownloadPhase.PENDING,
                        AppUpdateDownloadPhase.RUNNING,
                        AppUpdateDownloadPhase.PAUSED -> {
                            feedbackTitle = "正在下载"
                            feedbackMessage = "版本 ${downloadState.targetVersion} 正在后台下载。"
                        }

                        AppUpdateDownloadPhase.SUCCESSFUL -> {
                            feedbackTitle = "下载已完成"
                            feedbackMessage = "版本 ${downloadState.targetVersion} 已下载，请点击“安装更新”。"
                        }

                        else -> if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                when (
                                    val result = updateRepository.checkForUpdate(
                                        currentVersion = appInfo.versionName
                                    )
                                ) {
                                    is AppUpdateCheckResult.UpdateAvailable -> {
                                        updateCandidate = result.info
                                    }

                                    is AppUpdateCheckResult.UpToDate -> {
                                        feedbackTitle = "已是最新版本"
                                        feedbackMessage = if (
                                            result.latestVersion == appInfo.versionName
                                        ) {
                                            "当前版本 ${appInfo.versionName} 已是最新版本。"
                                        } else {
                                            "服务器版本 ${result.latestVersion} 不高于当前版本 " +
                                                "${appInfo.versionName}，无需升级。"
                                        }
                                    }

                                    is AppUpdateCheckResult.Failure -> {
                                        feedbackTitle = "检查更新失败"
                                        feedbackMessage = result.message
                                    }
                                }
                                isCheckingUpdate = false
                            }
                        }
                    }
                },
                text = if (isCheckingUpdate) {
                    "正在检查版本…"
                } else {
                    "版本 ${appInfo.versionName}（${appInfo.versionCode}）"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isCheckingUpdate) {
                    "正在访问更新服务器"
                } else {
                    "点击版本号检查更新"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AppUpdateDownloadStatus(
                state = downloadState,
                onCancelDownload = {
                    if (updateRepository.cancelTrackedDownload()) {
                        downloadState = AppUpdateDownloadState()
                    } else {
                        feedbackTitle = "取消失败"
                        feedbackMessage = "无法取消下载，请稍后重试。"
                    }
                },
                onDeleteDownload = {
                    confirmDeleteDownload = true
                },
                onInstallUpdate = { downloadId ->
                    when (val result = updateRepository.openInstaller(downloadId)) {
                        AppUpdateInstallResult.InstallerOpened -> Unit
                        AppUpdateInstallResult.PermissionRequired -> {
                            feedbackTitle = "需要安装权限"
                            feedbackMessage = "请在系统页面允许此App安装未知应用，" +
                                "返回后再次点击“安装更新”。"
                        }

                        is AppUpdateInstallResult.Failure -> {
                            feedbackTitle = "无法安装更新"
                            feedbackMessage = result.message
                        }
                    }
                }
            )

            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Android ${appInfo.androidVersion} · API ${appInfo.apiLevel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 根据DownloadManager状态显示确定或不确定进度条，并提供取消或安装操作。
 *
 * @param state 当前下载状态。
 * @param onCancelDownload 用户取消后台下载时的回调。
 * @param onDeleteDownload 下载完成后请求删除APK的回调。
 * @param onInstallUpdate 下载完成后请求安装的回调，参数为DownloadManager编号。
 *
 * @return 无返回值；IDLE状态下不输出任何内容。
 */
@Composable
private fun AppUpdateDownloadStatus(
    state: AppUpdateDownloadState,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onInstallUpdate: (Long) -> Unit
) {
    if (state.phase == AppUpdateDownloadPhase.IDLE) {
        return
    }

    val progress = state.progressFraction()
    val isActiveDownload = state.phase == AppUpdateDownloadPhase.PENDING ||
        state.phase == AppUpdateDownloadPhase.RUNNING ||
        state.phase == AppUpdateDownloadPhase.PAUSED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isActiveDownload) {
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (state.phase == AppUpdateDownloadPhase.SUCCESSFUL) {
            LinearProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = when (state.phase) {
                AppUpdateDownloadPhase.PENDING -> "版本 ${state.targetVersion} 等待开始下载"
                AppUpdateDownloadPhase.RUNNING -> buildString {
                    append("正在后台下载 ${formatUpdateBytes(state.downloadedBytes)}")
                    if (state.totalBytes > 0L) {
                        append(" / ${formatUpdateBytes(state.totalBytes)}")
                    }
                    progress?.let { fraction ->
                        append(" · ${(fraction * 100).toInt()}%")
                    }
                }

                AppUpdateDownloadPhase.PAUSED -> "下载已暂停，网络恢复后系统会自动继续"
                AppUpdateDownloadPhase.SUCCESSFUL -> "版本 ${state.targetVersion} 下载完成"
                AppUpdateDownloadPhase.FAILED -> state.failureMessage
                AppUpdateDownloadPhase.IDLE -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.phase == AppUpdateDownloadPhase.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        when {
            isActiveDownload -> {
                TextButton(onClick = onCancelDownload) {
                    Text(text = "取消下载")
                }
            }

            state.phase == AppUpdateDownloadPhase.SUCCESSFUL && state.downloadId != null -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDeleteDownload) {
                        Text(text = "删除安装包")
                    }
                    Button(
                        onClick = {
                            onInstallUpdate(state.downloadId)
                        }
                    ) {
                        Text(text = "安装更新")
                    }
                }
            }

            state.phase == AppUpdateDownloadPhase.FAILED -> {
                Text(
                    text = "点击上方版本号可重新检查并下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 把下载字节数转换为紧凑的B、KB、MB或GB文本。
 *
 * @param bytes 原始字节数，负值按0处理。
 *
 * @return 适合显示在OTA进度条下方的容量文本。
 */
private fun formatUpdateBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB")
    var unitIndex = 0

    while (value >= 1_024.0 && unitIndex < units.lastIndex) {
        value /= 1_024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        String.format(Locale.CHINA, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.CHINA, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * 显示日夜模式和可离线使用的原创角色风格主题选择器。
 *
 * 使用方法：
 * ProfileScreen传入当前主题状态和持久化回调。用户点击按钮后立即保存并重组整个应用界面；
 * 保存失败时保留当前主题并在卡片中显示提示。
 *
 * @param isDarkTheme 当前是否为黑夜模式。
 * @param visualTheme 当前选中的角色风格主题。
 * @param onSetDarkTheme 保存目标主题的回调，成功返回true。
 * @param onSetVisualTheme 保存角色风格主题的回调，成功返回true。
 *
 * @return 无返回值，直接输出外观设置卡片。
 */
@Composable
private fun AppearanceCard(
    isDarkTheme: Boolean,
    visualTheme: AppVisualTheme,
    onSetDarkTheme: (Boolean) -> Boolean,
    onSetVisualTheme: (AppVisualTheme) -> Boolean
) {
    val context = LocalContext.current
    var saveError by rememberSaveable {
        mutableStateOf("")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "外观模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isDarkTheme) {
                            "当前为黑夜模式，设置会在下次启动时保留"
                        } else {
                            "当前为白天模式，设置会在下次启动时保留"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Button(
                    onClick = {
                        if (onSetDarkTheme(!isDarkTheme)) {
                            saveError = ""
                        } else {
                            saveError = "主题保存失败，请重试"
                        }
                    }
                ) {
                    Text(
                        text = if (isDarkTheme) {
                            "切换到白天"
                        } else {
                            "切换到黑夜"
                        }
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "角色风格主题",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppVisualTheme.entries.forEach { theme ->
                        val themeArtResourceId = remember(theme.artResourceName) {
                            context.resources.getIdentifier(
                                theme.artResourceName,
                                "drawable",
                                context.packageName
                            )
                        }
                        FilterChip(
                            selected = visualTheme == theme,
                            onClick = {
                                if (onSetVisualTheme(theme)) {
                                    saveError = ""
                                } else {
                                    saveError = "主题保存失败，请重试"
                                }
                            },
                            label = { Text(text = theme.displayName) },
                            leadingIcon = {
                                if (themeArtResourceId != 0) {
                                    Image(
                                        painter = painterResource(themeArtResourceId),
                                        contentDescription = "${theme.displayName}人物预览",
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(9.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(text = theme.symbol)
                                }
                            }
                        )
                    }
                }
                val selectedArtResourceId = remember(visualTheme.artResourceName) {
                    context.resources.getIdentifier(
                        visualTheme.artResourceName,
                        "drawable",
                        context.packageName
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedArtResourceId != 0) {
                        Image(
                            painter = painterResource(selectedArtResourceId),
                            contentDescription = "${visualTheme.displayName}大图预览",
                            modifier = Modifier
                                .size(94.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "${visualTheme.symbol} ${visualTheme.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (visualTheme.artResourceName.isBlank()) {
                                "该主题为纯色界面，不使用角色或App图标"
                            } else {
                                "角色素材：${visualTheme.artCredit}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = "共10个人物主题与1个无角色纯色主题；App图标不会作为角色主题素材。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (saveError.isNotBlank()) {
                Text(
                    text = saveError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 显示进入App时是否播放启动动画的持久化开关。
 *
 * 使用方法：
 * ProfileScreen传入当前保存值和仓库回调。用户点击整行或选择框后立即保存，但只影响下一次重新
 * 启动App，当前会话不会突然补播或中断动画；首次安装和旧版本升级默认开启。
 *
 * @param enabled true表示下次启动播放动画，false表示直接进入密码锁或首页。
 * @param onEnabledChanged 保存新状态的回调，成功返回true。
 *
 * @return 无返回值，直接输出启动体验设置卡片。
 */
@Composable
private fun StartupAnimationSettingsCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Boolean
) {
    var saveError by rememberSaveable {
        mutableStateOf("")
    }

    /** 保存目标状态并在当前卡片中反馈失败结果。 */
    fun saveEnabled(targetEnabled: Boolean) {
        saveError = if (onEnabledChanged(targetEnabled)) {
            ""
        } else {
            "启动动画设置保存失败，请重试"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                saveEnabled(!enabled)
            },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启动动画",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (enabled) {
                            "已开启，下次进入App仍会播放逐字动画"
                        } else {
                            "已关闭，下次将直接进入密码锁或首页"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Checkbox(
                    checked = enabled,
                    onCheckedChange = ::saveEnabled
                )
            }

            Text(
                text = "设置从下一次重新启动生效，默认开启。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (saveError.isNotBlank()) {
                Text(
                    text = saveError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
                text = "账目、快捷选择和提醒设置仅保存在当前手机。授权后，App只读取微信系统通知：普通聊天标题和正文只在内存中用于排除通话、支付和系统消息，提醒仓库仅保存Android通知键；支付通知摘要可能保留到本机账本或待确认列表。App不读取微信数据库、照片或文件。",
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
            .bouncyClickable {
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

/** OTA下载进度刷新间隔；实际传输由系统负责，本间隔只影响前台进度条更新频率。 */
private const val UPDATE_DOWNLOAD_REFRESH_INTERVAL_MILLIS = 800L

/** “我的”页面容量信息刷新间隔；内存和存储变化较慢，无需沿用首页网络速率的一秒采样。 */
private const val DEVICE_STATUS_REFRESH_INTERVAL_MILLIS = 5_000L
