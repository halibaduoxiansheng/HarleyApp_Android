package com.example.harleyapp.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.harleyapp.backup.AppBackupManager
import com.example.harleyapp.backup.BackupPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 显示跨手机本地备份的导出、校验和恢复流程。
 *
 * 使用方法：
 * 从功能卡片进入本页面。用户可直接导出明文备份，也可先输入密码生成AES-GCM加密备份；
 * 导入时先选择文件并校验摘要，只有再次确认后才会替换数据。所有文件读写均通过Android
 * 系统文件选择器完成，本页面不会自动上传文件。
 *
 * @param manager 本地备份管理器。
 * @param coroutineScope 宿主页面协程作用域，用于执行文件IO。
 * @param onRestoreCompleted 恢复成功后的回调，宿主应重新读取仓库并恢复提醒计划。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出备份管理页面。
 */
@Composable
fun BackupScreen(
    manager: AppBackupManager,
    coroutineScope: CoroutineScope,
    onRestoreCompleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var password by rememberSaveable { mutableStateOf("") }
    var selectedBackupUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var message by rememberSaveable { mutableStateOf("尚未选择备份文件") }
    var isBusy by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }

    /**
     * 校验当前选中的备份并刷新摘要。
     *
     * @param uri 用户选择的备份Uri。
     */
    fun inspectBackup(uri: Uri) {
        isBusy = true
        preview = null
        coroutineScope.launch {
            val result = manager.inspect(uri, password)
            preview = result.preview
            message = result.message
            isBusy = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            isBusy = true
            coroutineScope.launch {
                val result = manager.exportTo(uri, password)
                message = result.message
                preview = result.preview
                isBusy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedBackupUri = uri
        if (uri != null) {
            inspectBackup(uri)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text(text = "‹ 返回功能中心")
            }
            Text(
                text = "本地备份与换机恢复",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "备份由你保存和传输，App不会自动上传。导入前会在本机保留安全快照。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "备份密码（可选）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { value -> password = value.take(MAX_PASSWORD_LENGTH) },
                        label = { Text("输入密码后文件将加密") },
                        supportingText = {
                            Text(
                                if (password.isBlank()) {
                                    "不填写也可以导出，但文件中的账目和提醒可被直接读取"
                                } else {
                                    "密码不会保存；遗忘后无法恢复加密备份"
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isBusy,
                        onClick = {
                            exportLauncher.launch(defaultBackupFileName())
                        }
                    ) {
                        Text(text = if (password.isBlank()) "导出本地备份" else "导出加密备份")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "从旧手机备份恢复",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "先选择.harleybackup文件。加密文件需要在上方输入原密码，然后重新校验。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy,
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(BACKUP_MIME_TYPE, "application/json", "application/octet-stream")
                                )
                            }
                        ) {
                            Text("选择备份")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy && selectedBackupUri != null,
                            onClick = {
                                selectedBackupUri?.let(::inspectBackup)
                            }
                        ) {
                            Text("重新校验")
                        }
                    }

                    if (isBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("正在处理本地文件…")
                        }
                    } else {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    preview?.let { backupPreview ->
                        BackupPreviewCard(preview = backupPreview)
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedBackupUri != null,
                            onClick = { showRestoreConfirmation = true }
                        ) {
                            Text("确认导入此备份")
                        }
                    }
                }
            }
        }

        item {
            Text(
                modifier = Modifier.padding(bottom = 24.dp),
                text = "不会迁移旧手机的计步传感器基线、微信未读状态、定位坐标或天气缓存，避免新手机出现错误提醒。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text("确认替换当前数据？") },
            text = {
                Text("导入会替换账目、提醒、运动、网站和相关设置。当前数据会先保存到App私有安全快照。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmation = false
                        val uri = selectedBackupUri ?: return@TextButton
                        isBusy = true
                        coroutineScope.launch {
                            val result = manager.restoreFrom(uri, password)
                            message = result.message
                            preview = result.preview
                            isBusy = false
                            if (result.success) {
                                onRestoreCompleted()
                            }
                        }
                    }
                ) {
                    Text("导入并替换")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 展示经过校验的备份模块数量。
 *
 * @param preview 备份摘要。
 * @return 无返回值。
 */
@Composable
private fun BackupPreviewCard(preview: BackupPreview) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = if (preview.encrypted) "已加密备份" else "未加密备份",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text("账目 ${preview.ledgerEntryCount} 条 · 提醒 ${preview.reminderCount} 条")
            Text("运动记录 ${preview.fitnessRecordCount} 天 · 网站 ${preview.websiteCount} 个")
            Text("网站卡片背景 ${preview.websiteBackgroundCount} 张")
            Text("记事本 ${preview.notebookArticleCount} 篇 · 文章媒体 ${preview.notebookMediaCount} 个")
            Text("本地设置 ${preview.preferenceValueCount} 项 · 数据文档 ${preview.localDocumentCount} 项")
            Text(
                text = "创建时间：${formatBackupTime(preview.createdAtMillis)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** @return 当前时间生成的稳定备份文件名。 */
private fun defaultBackupFileName(): String {
    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
    return "Harley_$date.harleybackup"
}

/** @return 可显示的备份创建时间。 */
private fun formatBackupTime(timestamp: Long): String {
    if (timestamp <= 0L) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
}

private const val BACKUP_MIME_TYPE = "application/vnd.harley.backup+json"
private const val MAX_PASSWORD_LENGTH = 128
