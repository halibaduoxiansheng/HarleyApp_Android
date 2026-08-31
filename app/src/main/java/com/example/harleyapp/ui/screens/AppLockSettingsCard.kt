package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.AppLockRepository

/** 密码锁设置对话框当前操作。 */
private enum class AppLockDialogMode {
    ENABLE,
    CHANGE,
    DISABLE
}

/**
 * 在“我的”页面显示App密码锁的启用、修改和关闭闭环。
 *
 * 使用方法：
 * ProfileScreen直接传入Application Context创建的[AppLockRepository]。首次启用输入两次新密码；
 * 修改需要当前密码和两次新密码；关闭必须验证当前密码。设置保存后下次冷启动生效，当前已经
 * 解锁的会话不会突然遮挡用户正在编辑的内容。
 *
 * @param repository 本地密码摘要仓库。
 * @return 无返回值，直接输出密码锁设置卡和必要对话框。
 */
@Composable
fun AppLockSettingsCard(repository: AppLockRepository) {
    var lockState by remember { mutableStateOf(repository.getState()) }
    var dialogModeName by rememberSaveable { mutableStateOf("") }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val dialogMode = AppLockDialogMode.entries.firstOrNull { mode ->
        mode.name == dialogModeName
    }

    /** 清理对话框输入，避免密码在关闭后继续留在Compose状态中。 */
    fun closeDialog() {
        currentPassword = ""
        newPassword = ""
        confirmPassword = ""
        dialogModeName = ""
    }

    dialogMode?.let { mode ->
        AlertDialog(
            onDismissRequest = ::closeDialog,
            title = {
                Text(
                    when (mode) {
                        AppLockDialogMode.ENABLE -> "开启App密码锁"
                        AppLockDialogMode.CHANGE -> "修改App密码"
                        AppLockDialogMode.DISABLE -> "关闭App密码锁"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode != AppLockDialogMode.ENABLE) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = currentPassword,
                            onValueChange = { currentPassword = it.take(32) },
                            label = { Text("当前密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    if (mode != AppLockDialogMode.DISABLE) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = newPassword,
                            onValueChange = { newPassword = it.take(32) },
                            label = { Text("新密码（4至32字符）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it.take(32) },
                            label = { Text("再次输入新密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    Text(
                        text = if (mode == AppLockDialogMode.DISABLE) {
                            "关闭后，下次启动不再要求验证。"
                        } else {
                            "密码只保存为本机随机盐摘要。忘记密码后需要等待24小时自动解锁。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val saved = when (mode) {
                            AppLockDialogMode.ENABLE -> {
                                newPassword == confirmPassword && repository.enable(newPassword)
                            }

                            AppLockDialogMode.CHANGE -> {
                                newPassword == confirmPassword && repository.changePassword(
                                    currentPassword = currentPassword,
                                    newPassword = newPassword
                                )
                            }

                            AppLockDialogMode.DISABLE -> repository.disable(currentPassword)
                        }
                        if (saved) {
                            lockState = repository.getState()
                            message = when (mode) {
                                AppLockDialogMode.ENABLE -> "密码锁已开启，下次冷启动生效"
                                AppLockDialogMode.CHANGE -> "密码已修改"
                                AppLockDialogMode.DISABLE -> "密码锁已关闭"
                            }
                            closeDialog()
                        } else {
                            message = when {
                                mode != AppLockDialogMode.DISABLE && newPassword != confirmPassword -> {
                                    "两次新密码不一致"
                                }

                                mode != AppLockDialogMode.DISABLE &&
                                    !repository.isValidPassword(newPassword) -> {
                                    "新密码必须为4至32字符，且不能含首尾空格"
                                }

                                else -> "当前密码不正确或保存失败"
                            }
                        }
                    }
                ) {
                    Text(if (mode == AppLockDialogMode.DISABLE) "确认关闭" else "保存")
                }
            },
            dismissButton = {
                TextButton(onClick = ::closeDialog) {
                    Text("取消")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "App密码锁",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (lockState.enabled) {
                    "已开启：启动动画结束后需要输入密码"
                } else {
                    "未开启：可保护账目、笔记和个人内容免于直接查看"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            if (lockState.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { dialogModeName = AppLockDialogMode.CHANGE.name }
                    ) {
                        Text("修改密码")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { dialogModeName = AppLockDialogMode.DISABLE.name }
                    ) {
                        Text("关闭")
                    }
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dialogModeName = AppLockDialogMode.ENABLE.name }
                ) {
                    Text("开启密码锁")
                }
            }

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("不正确") || message.contains("不一致") ||
                        message.contains("失败") || message.contains("必须")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            Text(
                text = "说明：这是本机隐私锁。清除App数据或卸载会同时清除密码和App本地数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
