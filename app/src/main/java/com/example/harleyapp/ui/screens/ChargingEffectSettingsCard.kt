package com.example.harleyapp.ui.screens

import android.Manifest
import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.charging.ChargingEffectController

/**
 * 在“我的”页面显示全天充电动画开关、必要权限状态、预览和后台设置入口。
 *
 * 使用方法：
 * [ProfileScreen]直接调用，不需要外层传递状态。用户首次开启时先进入悬浮窗授权页，再申请通知
 * 权限；两项均满足后才保存开关并启动常驻服务。关闭开关会立即停止服务和移除常驻通知。
 *
 * @return 无返回值，直接输出完整设置卡片。
 */
@Composable
fun ChargingEffectSettingsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(context) {
        ChargingEffectController(context.applicationContext)
    }
    var enabled by remember {
        mutableStateOf(controller.isEnabled())
    }
    var overlayPermissionGranted by remember {
        mutableStateOf(controller.hasOverlayPermission())
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(controller.hasNotificationPermission())
    }
    var pendingEnable by rememberSaveable {
        mutableStateOf(false)
    }
    var statusMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = controller.hasNotificationPermission()
        if (pendingEnable && granted && notificationPermissionGranted) {
            enabled = controller.setEnabled(true)
            statusMessage = if (enabled) {
                "充电动画监控已开启，插入电源会自动显示2.5秒"
            } else {
                "系统未能启动常驻监控，请检查后台运行设置"
            }
        } else if (pendingEnable) {
            statusMessage = "需要通知权限才能清楚显示常驻监控状态"
        }
        pendingEnable = false
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = controller.hasOverlayPermission()
        if (!pendingEnable) {
            return@rememberLauncherForActivityResult
        }
        if (!overlayPermissionGranted) {
            pendingEnable = false
            statusMessage = "需要允许显示在其他应用上层，才能在任何界面弹出充电效果"
            return@rememberLauncherForActivityResult
        }

        notificationPermissionGranted = controller.hasNotificationPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionGranted
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enabled = controller.setEnabled(true)
            pendingEnable = false
            statusMessage = if (enabled) {
                "充电动画监控已开启，插入电源会自动显示2.5秒"
            } else {
                "系统未能启动常驻监控，请检查后台运行设置"
            }
        }
    }

    val appDetailsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = controller.hasOverlayPermission()
        notificationPermissionGranted = controller.hasNotificationPermission()
        enabled = controller.isEnabled()
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
                        text = "未来充电效果",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "插入电源后点亮屏幕并显示2.5秒实时电池数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { requestedEnabled ->
                        statusMessage = null
                        if (!requestedEnabled) {
                            pendingEnable = false
                            enabled = !controller.setEnabled(false)
                            statusMessage = if (!enabled) {
                                "充电动画监控已关闭"
                            } else {
                                "设置保存失败，请重试"
                            }
                            return@Switch
                        }

                        pendingEnable = true
                        overlayPermissionGranted = controller.hasOverlayPermission()
                        if (!overlayPermissionGranted) {
                            overlayPermissionLauncher.launch(
                                controller.createOverlayPermissionIntent()
                            )
                        } else {
                            notificationPermissionGranted =
                                controller.hasNotificationPermission()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !notificationPermissionGranted
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                enabled = controller.setEnabled(true)
                                pendingEnable = false
                                statusMessage = if (enabled) {
                                    "充电动画监控已开启，插入电源会自动显示2.5秒"
                                } else {
                                    "系统未能启动常驻监控，请检查后台运行设置"
                                }
                            }
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
                    PermissionStatusRow(
                        title = "覆盖其他应用",
                        granted = overlayPermissionGranted
                    )
                    PermissionStatusRow(
                        title = "常驻通知",
                        granted = notificationPermissionGranted
                    )
                    PermissionStatusRow(
                        title = "息屏点亮",
                        granted = true,
                        grantedText = "已随功能启用"
                    )
                }
            }

            Text(
                text = "效果显示电量百分比、电池净电流、电池电压和电池温度；手机未开放的指标会明确标注。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        statusMessage = if (controller.showPreview()) {
                            "正在预览2.5秒充电效果"
                        } else {
                            "系统未能打开预览"
                        }
                    }
                ) {
                    Text(text = "预览效果")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        appDetailsLauncher.launch(controller.createAppDetailsIntent())
                    }
                ) {
                    Text(text = "后台设置")
                }
            }

            if (enabled && !overlayPermissionGranted) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        overlayPermissionLauncher.launch(
                            controller.createOverlayPermissionIntent()
                        )
                    }
                ) {
                    Text(text = "重新授予悬浮窗权限")
                }
            }
        }
    }
}

/**
 * 显示一个系统能力的授权状态。
 *
 * @param title 权限或能力名称。
 * @param granted 当前是否满足。
 * @param grantedText 满足时显示的说明，默认显示“已允许”。
 * @return 无返回值。
 */
@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    grantedText: String = "已允许"
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
            text = if (granted) grantedText else "需要授权",
            style = MaterialTheme.typography.labelMedium,
            color = if (granted) {
                Color(0xFF008C72)
            } else {
                MaterialTheme.colorScheme.error
            },
            fontWeight = FontWeight.Bold
        )
    }
}
