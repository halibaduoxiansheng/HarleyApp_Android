package com.example.harleyapp.ui.screens

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.ui.components.bouncyClickable

/**
 * 显示“我的”页面，包括外观、快捷应用、版本和本地隐私信息。
 *
 * 使用方法：
 * 由HarleyApp在底部“我的”选中时调用。用户切换主题或勾选快捷应用后立即通过回调持久化，
 * 首页会同步显示仍然安装的已选应用；版本信息直接从当前已安装包读取。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param isDarkTheme 当前是否启用黑夜模式。
 * @param onSetDarkTheme 保存并应用白天或黑夜模式的回调，成功返回true。
 * @param apps 手机中当前可启动的应用列表。
 * @param selectedPackages 当前已选包名集合。
 * @param isLoading 是否仍在后台读取应用列表。
 * @param onSelectionChanged 用户选择变化后的完整集合回调。
 *
 * @return 无返回值，直接输出“我的”页面。
 */
@Composable
fun ProfileScreen(
    isDarkTheme: Boolean,
    onSetDarkTheme: (Boolean) -> Boolean,
    apps: List<LaunchableApp>,
    selectedPackages: Set<String>,
    isLoading: Boolean,
    onSelectionChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appInfo = remember(context) {
        readAppInfo(context)
    }
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
                text = "我的",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "外观、快捷方式与应用信息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            AppearanceCard(
                isDarkTheme = isDarkTheme,
                onSetDarkTheme = onSetDarkTheme
            )
        }

        item {
            AppInfoCard(appInfo = appInfo)
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

        item {
            PrivacyCard()
        }
    }
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
 * 显示应用名称、版本号、包名和当前Android系统版本。
 *
 * @param appInfo 已读取并规范化的基础信息。
 *
 * @return 无返回值，直接输出版本信息卡片。
 */
@Composable
private fun AppInfoCard(appInfo: AppInfo) {
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Harley生活助手",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "版本 ${appInfo.versionName}（${appInfo.versionCode}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
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
 * 显示当前主题并提供一次点击即可完成的白天、黑夜模式切换。
 *
 * 使用方法：
 * ProfileScreen传入当前主题状态和持久化回调。用户点击按钮后立即保存并重组整个应用界面；
 * 保存失败时保留当前主题并在卡片中显示提示。
 *
 * @param isDarkTheme 当前是否为黑夜模式。
 * @param onSetDarkTheme 保存目标主题的回调，成功返回true。
 *
 * @return 无返回值，直接输出外观设置卡片。
 */
@Composable
private fun AppearanceCard(
    isDarkTheme: Boolean,
    onSetDarkTheme: (Boolean) -> Boolean
) {
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
        Row(
            modifier = Modifier.padding(18.dp),
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
                if (saveError.isNotBlank()) {
                    Text(
                        text = saveError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
