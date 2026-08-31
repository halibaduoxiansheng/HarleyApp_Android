package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.CompanionProgress

/**
 * 在“我的”页面显示本机开发者模式入口和已验证后的伙伴调试工具。
 *
 * 使用方法：
 * 外层传入DeveloperModeRepository的配置及启用状态。密钥输入只使用[remember]保留在当前组合
 * 内存中，不使用rememberSaveable，旋转屏幕、页面销毁或对话框关闭时都会清空；验证和权限判断
 * 必须由[onVerifyKey]在数据层完成。等级、金币按钮只在[enabled]为true时显示，但外层回调仍应
 * 再次检查权限，避免仅依赖界面隐藏。
 *
 * @param configured 当前安装包是否已经注入有效的开发者密钥摘要。
 * @param enabled 本机是否已经通过当前摘要验证。
 * @param progress 当前伙伴等级和金币，用于调试前后核对。
 * @param onVerifyKey 校验密钥并启用开发者模式的回调，成功返回true。
 * @param onDisable 退出开发者模式回调，成功返回true。
 * @param onAddLevels 增加指定正整数等级的回调，成功返回true。
 * @param onAddCoins 增加指定正整数金币的回调，成功返回true。
 * @return 无返回值，直接输出开发者模式卡片和密钥输入对话框。
 */
@Composable
fun DeveloperModeCard(
    configured: Boolean,
    enabled: Boolean,
    progress: CompanionProgress,
    onVerifyKey: (String) -> Boolean,
    onDisable: () -> Boolean,
    onAddLevels: (Int) -> Boolean,
    onAddCoins: (Int) -> Boolean
) {
    var showKeyDialog by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var levelsInput by rememberSaveable { mutableStateOf("1") }
    var coinsInput by rememberSaveable { mutableStateOf("1") }

    if (showKeyDialog) {
        DeveloperKeyDialog(
            onDismiss = { showKeyDialog = false },
            onVerify = { key ->
                val success = onVerifyKey(key)
                feedback = if (success) "开发者模式已启用" else "密钥不正确"
                if (success) showKeyDialog = false
                success
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "开发者模式",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    !configured -> "当前安装包没有配置开发者密钥摘要。"
                    enabled -> "已验证 · 伙伴Lv.${progress.level} · 金币${progress.coins}"
                    else -> "输入开发者密钥后，可快速增加伙伴等级和金币。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (enabled) {
                DeveloperNumberControl(
                    label = "增加等级",
                    value = levelsInput,
                    onValueChanged = { value -> levelsInput = value.onlyPositiveIntegerInput() },
                    onSubmit = {
                        val amount = levelsInput.toIntOrNull()
                        val success = amount != null && amount > 0 && onAddLevels(amount)
                        feedback = if (success) "伙伴等级已更新" else "请输入有效数量或确认尚未满级"
                    }
                )
                DeveloperNumberControl(
                    label = "增加金币",
                    value = coinsInput,
                    onValueChanged = { value -> coinsInput = value.onlyPositiveIntegerInput() },
                    onSubmit = {
                        val amount = coinsInput.toIntOrNull()
                        val success = amount != null && amount > 0 && onAddCoins(amount)
                        feedback = if (success) "伙伴金币已更新" else "请输入有效数量或确认未达上限"
                    }
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (onDisable()) {
                            feedback = "已退出开发者模式"
                        } else {
                            feedback = "退出失败，请重试"
                        }
                    }
                ) {
                    Text(text = "退出开发者模式")
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = configured,
                    onClick = { showKeyDialog = true }
                ) {
                    Text(text = "输入开发者密钥")
                }
            }

            if (feedback.isNotBlank()) {
                Text(
                    text = feedback,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 显示一个开发者数值调整行。
 *
 * @param label 输入框和按钮使用的操作名称。
 * @param value 当前文本值。
 * @param onValueChanged 用户修改数值后的回调。
 * @param onSubmit 用户确认增加后的回调。
 * @return 无返回值。
 */
@Composable
private fun DeveloperNumberControl(
    label: String,
    value: String,
    onValueChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = onValueChanged,
            label = { Text(text = label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(onClick = onSubmit) {
            Text(text = "增加")
        }
    }
}

/**
 * 收集并提交一次开发者密钥，关闭后立即清除输入内容。
 *
 * @param onDismiss 取消输入回调。
 * @param onVerify 提交密钥回调，校验成功返回true。
 * @return 无返回值。
 */
@Composable
private fun DeveloperKeyDialog(
    onDismiss: () -> Unit,
    onVerify: (String) -> Boolean
) {
    var keyInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            keyInput = ""
            onDismiss()
        },
        title = { Text(text = "验证开发者身份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "密钥只在本机内存中完成摘要校验，不会保存明文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        errorMessage = ""
                    },
                    label = { Text(text = "开发者密钥") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    // 仅使用密码视觉掩码隐藏内容；普通文本键盘允许中文输入法提交中文密钥。
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    isError = errorMessage.isNotBlank(),
                    supportingText = if (errorMessage.isNotBlank()) {
                        { Text(text = errorMessage) }
                    } else {
                        null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = keyInput.isNotEmpty(),
                onClick = {
                    val submittedKey = keyInput
                    keyInput = ""
                    if (!onVerify(submittedKey)) {
                        errorMessage = "密钥不正确"
                    }
                }
            ) {
                Text(text = "验证")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    keyInput = ""
                    onDismiss()
                }
            ) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 过滤开发者面板的正整数输入，避免负号、小数和超长数字进入业务层。
 *
 * @return 最多六位的纯数字文本；空文本用于允许用户重新输入。
 */
private fun String.onlyPositiveIntegerInput(): String {
    return filter(Char::isDigit).take(MAX_DEVELOPER_NUMBER_DIGITS)
}

private const val MAX_DEVELOPER_NUMBER_DIGITS = 6
