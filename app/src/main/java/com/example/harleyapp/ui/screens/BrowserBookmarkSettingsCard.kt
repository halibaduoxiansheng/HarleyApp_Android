package com.example.harleyapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.harleyapp.browser.BrowserBookmarkController

/**
 * 在“我的”页面显示浏览器一键收藏悬浮球的开关、权限说明与授权入口。
 *
 * 使用方法：
 * [ProfileScreen]直接调用。首次开启会先展示显著数据访问说明，用户明确同意后才保存总开关；
 * 无障碍服务尚未启用时进入Android系统设置，必须由用户亲自确认。关闭开关会立即隐藏悬浮球，
 * 但不会擅自修改系统无障碍授权，也不会删除已经保存的网站收藏。
 *
 * @return 无返回值，直接输出完整设置卡片和首次授权说明弹窗。
 */
@Composable
fun BrowserBookmarkSettingsCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) {
        BrowserBookmarkController(context.applicationContext)
    }
    var enabled by remember {
        mutableStateOf(controller.isEnabled())
    }
    var accessibilityEnabled by remember {
        mutableStateOf(controller.isAccessibilityServiceEnabled())
    }
    var showDisclosure by rememberSaveable {
        mutableStateOf(false)
    }
    var statusMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    /**
     * 从系统设置返回后同步刷新无障碍授权和本地总开关。
     *
     * @param openedSettings true表示本次刷新发生在系统授权页返回后。
     */
    fun refreshState(openedSettings: Boolean) {
        enabled = controller.isEnabled()
        accessibilityEnabled = controller.isAccessibilityServiceEnabled()
        if (openedSettings && enabled) {
            statusMessage = if (accessibilityEnabled) {
                "浏览器悬浮球已就绪，打开Edge或Google网页即可使用"
            } else {
                "尚未启用HarleyApp无障碍服务，悬浮球暂时不会显示"
            }
        }
    }

    val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState(openedSettings = true)
    }

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshState(openedSettings = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = {
                showDisclosure = false
            },
            title = {
                Text(text = "启用浏览器一键收藏")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Android会把这项权限描述为可读取屏幕内容和代你执行操作。HarleyApp只在浏览器前台显示悬浮球；只有你亲自点击后，才查找并操作浏览器的共享入口，在系统分享面板选择“收藏到HarleyApp”，用完整网址完成收藏。"
                    )
                    Text(
                        text = "若浏览器未公开可识别的分享入口，只会在本次点击后尝试读取网页正文外的地址栏。不会读取密码或网页正文，不记录连续浏览历史，不上传网址，也不会替你点击网页内容；收藏仍只写入本机现有的网站收藏库。",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisclosure = false
                        if (!controller.setEnabled(true)) {
                            statusMessage = "悬浮球开关保存失败，请重试"
                            return@TextButton
                        }

                        enabled = true
                        accessibilityEnabled = controller.isAccessibilityServiceEnabled()
                        if (accessibilityEnabled) {
                            statusMessage = "浏览器悬浮球已开启"
                        } else {
                            statusMessage = "请在系统页面找到Harley生活助手并允许服务"
                            accessibilitySettingsLauncher.launch(
                                controller.createAccessibilitySettingsIntent()
                            )
                        }
                    }
                ) {
                    Text(text = "同意并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text(text = "取消")
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
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "浏览器一键收藏",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "在Edge或彩色G网页上点击悬浮球，保存到网站收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { requestedEnabled ->
                        statusMessage = null
                        if (requestedEnabled) {
                            showDisclosure = true
                            return@Switch
                        }

                        if (controller.setEnabled(false)) {
                            enabled = false
                            statusMessage = "浏览器悬浮球已关闭，已有收藏保持不变"
                        } else {
                            statusMessage = "悬浮球开关保存失败，请重试"
                        }
                    }
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    BrowserBookmarkStatusRow(
                        title = "功能总开关",
                        ready = enabled,
                        readyText = "已开启",
                        missingText = "已关闭"
                    )
                    BrowserBookmarkStatusRow(
                        title = "无障碍服务",
                        ready = accessibilityEnabled,
                        readyText = "已允许",
                        missingText = "需要手动授权"
                    )
                    BrowserBookmarkStatusRow(
                        title = "明确兼容",
                        ready = true,
                        readyText = "Edge、Google"
                    )
                }
            }

            Text(
                text = "Edge已使用完整网址分享链路；彩色G内置网页会尝试同样的分享按钮规则。其他浏览器即使悬浮球未适配，也可用原生“分享”选择“收藏到HarleyApp”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "悬浮球闲置3秒会缩到屏幕边缘；缩边后的第一次点击只展开，第二次点击才收藏。拖动时也会立即展开并重新计时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    accessibilitySettingsLauncher.launch(
                        controller.createAccessibilitySettingsIntent()
                    )
                }
            ) {
                Text(
                    text = if (accessibilityEnabled) {
                        "查看无障碍服务设置"
                    } else {
                        "前往无障碍服务授权"
                    }
                )
            }

            if (!enabled) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showDisclosure = true }
                ) {
                    Text(text = "了解并开启")
                }
            }
        }
    }
}

/**
 * 显示浏览器收藏功能的一项开关、权限或兼容状态。
 *
 * @param title 状态名称。
 * @param ready 当前是否满足。
 * @param readyText 满足时显示的文字。
 * @param missingText 未满足时显示的文字。
 * @return 无返回值。
 */
@Composable
private fun BrowserBookmarkStatusRow(
    title: String,
    ready: Boolean,
    readyText: String,
    missingText: String = "需要处理"
) {
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
            text = if (ready) readyText else missingText,
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) Color(0xFF008C72) else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
    }
}
