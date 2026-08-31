package com.example.harleyapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.notification.AppReminderSound
import com.example.harleyapp.notification.AppReminderSoundPlayer
import com.example.harleyapp.notification.ReminderSoundTarget

/**
 * 在App内选择、试听并保存普通提醒或微信提醒的独立提示音。
 *
 * 使用方法：
 * 用户点击提醒卡片的“选择提示音”后显示本弹窗。点击任意非静音选项会立即试听，点击“保存”
 * 调用[onConfirm]持久化；取消、保存成功或弹窗销毁时会停止尚未结束的试听。选项均由HarleyApp
 * 实时合成，不打开Android来电铃声页面。
 *
 * @param target 当前为普通定时提醒还是微信等待提醒选择声音。
 * @param initialSound 打开弹窗时已经保存的提示音。
 * @param onDismiss 用户取消或保存成功后关闭弹窗的回调。
 * @param onConfirm 保存新选择的回调，成功返回true，失败返回false并保留弹窗。
 *
 * @return 无返回值，直接显示Material 3选择弹窗。
 */
@Composable
fun ReminderSoundPickerDialog(
    target: ReminderSoundTarget,
    initialSound: AppReminderSound,
    onDismiss: () -> Unit,
    onConfirm: (AppReminderSound) -> Boolean
) {
    val soundPlayer = remember { AppReminderSoundPlayer() }
    var selectedSound by remember(initialSound) {
        mutableStateOf(initialSound)
    }
    var feedbackText by remember {
        mutableStateOf("")
    }

    DisposableEffect(soundPlayer) {
        onDispose(soundPlayer::stop)
    }

    AlertDialog(
        onDismissRequest = {
            soundPlayer.stop()
            onDismiss()
        },
        title = {
            Text(text = "${target.displayName}提示音")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "这些声音只属于HarleyApp，与手机来电铃声分开；试听和正式提醒使用闹钟音量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                AppReminderSound.entries.forEach { sound ->
                    ReminderSoundOption(
                        sound = sound,
                        selected = sound == selectedSound,
                        onSelect = {
                            selectedSound = sound
                            feedbackText = if (soundPlayer.play(sound)) {
                                if (sound == AppReminderSound.VIBRATION_ONLY) {
                                    "已选择仅振动，保存后不播放声音"
                                } else {
                                    "正在试听：${sound.displayName}"
                                }
                            } else {
                                "试听失败，请检查闹钟音量后重试"
                            }
                        }
                    )
                }

                if (feedbackText.isNotBlank()) {
                    Text(
                        text = feedbackText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    soundPlayer.stop()
                    if (onConfirm(selectedSound)) {
                        onDismiss()
                    } else {
                        feedbackText = "保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    soundPlayer.stop()
                    onDismiss()
                }
            ) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示单个App内提示音选项。
 *
 * @param sound 当前提示音的名称、说明和合成音序。
 * @param selected 是否为当前准备保存的选项。
 * @param onSelect 点击整行或单选按钮时更新选择并试听的回调。
 *
 * @return 无返回值，直接输出可点击的单选卡片。
 */
@Composable
private fun ReminderSoundOption(
    sound: AppReminderSound,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = sound.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = sound.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (sound != AppReminderSound.VIBRATION_ONLY) {
                Text(
                    text = "试听",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
