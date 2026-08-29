package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.MAX_NIGHT_OVERLAY_ALPHA
import com.example.harleyapp.model.MAX_TEXT_ZOOM_PERCENT
import com.example.harleyapp.model.MAX_WEBSITE_PLAYBACK_RATE
import com.example.harleyapp.model.MIN_NIGHT_OVERLAY_ALPHA
import com.example.harleyapp.model.MIN_TEXT_ZOOM_PERCENT
import com.example.harleyapp.model.MIN_WEBSITE_PLAYBACK_RATE
import com.example.harleyapp.model.WEBSITE_PLAYBACK_RATE_STEP
import com.example.harleyapp.model.WebsiteToolSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 以卡片形式集中展示当前网站可用的视频、阅读和浏览工具。
 *
 * 使用方法：
 * WebsiteScreen点击“网页工具”后显示本对话框。开关和按钮立即生效；连续滑块只在用户结束
 * 拖动时保存，避免每一帧都写入本地数据库。
 *
 * @param settings 当前网站已经保存的工具设置。
 * @param findQuery 页内查找关键词。
 * @param findResultText WebView报告的当前匹配位置和总数。
 * @param onSettingsChanged 设置变更回调。
 * @param onFindQueryChanged 页内查找关键词变更回调。
 * @param onFindPrevious 跳到上一个匹配项。
 * @param onFindNext 跳到下一个匹配项。
 * @param onTogglePlayback 切换当前主要媒体播放状态。
 * @param onSeekBy 相对跳转秒数回调。
 * @param onRequestFullscreen 请求当前主要视频全屏的回调。
 * @param onDismiss 关闭工具箱回调。
 * @return 无返回值，直接输出网页工具箱对话框。
 */
@Composable
fun WebsiteToolboxDialog(
    settings: WebsiteToolSettings,
    findQuery: String,
    findResultText: String,
    onSettingsChanged: (WebsiteToolSettings) -> Unit,
    onFindQueryChanged: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onRequestFullscreen: () -> Unit,
    onDismiss: () -> Unit
) {
    var playbackRateDraft by remember(settings.playbackRate) {
        mutableFloatStateOf(settings.playbackRate)
    }
    var textZoomDraft by remember(settings.textZoomPercent) {
        mutableIntStateOf(settings.textZoomPercent)
    }
    var nightAlphaDraft by remember(settings.nightOverlayAlpha) {
        mutableFloatStateOf(settings.nightOverlayAlpha)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "网页工具箱")
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ToolboxCard(title = "视频控制") {
                        Text(
                            text = "自定义倍速 ${formatPlaybackRate(playbackRateDraft)}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = playbackRateDraft,
                            onValueChange = { value ->
                                playbackRateDraft = snapPlaybackRate(value)
                            },
                            onValueChangeFinished = {
                                onSettingsChanged(settings.copy(playbackRate = playbackRateDraft))
                            },
                            valueRange = MIN_WEBSITE_PLAYBACK_RATE..MAX_WEBSITE_PLAYBACK_RATE,
                            steps = PLAYBACK_RATE_SLIDER_STEPS
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    playbackRateDraft = snapPlaybackRate(
                                        playbackRateDraft - WEBSITE_PLAYBACK_RATE_STEP
                                    )
                                    onSettingsChanged(
                                        settings.copy(playbackRate = playbackRateDraft)
                                    )
                                }
                            ) {
                                Text("-0.05")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    playbackRateDraft = 1f
                                    onSettingsChanged(settings.copy(playbackRate = 1f))
                                }
                            ) {
                                Text("1.00×")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    playbackRateDraft = snapPlaybackRate(
                                        playbackRateDraft + WEBSITE_PLAYBACK_RATE_STEP
                                    )
                                    onSettingsChanged(
                                        settings.copy(playbackRate = playbackRateDraft)
                                    )
                                }
                            ) {
                                Text("+0.05")
                            }
                        }

                        ToolboxSwitchRow(
                            title = "禁止自动播放",
                            subtitle = "只阻止带autoplay属性的新媒体，手动播放仍可使用",
                            checked = settings.blockAutoplay,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(blockAutoplay = enabled))
                            }
                        )
                        ToolboxSwitchRow(
                            title = "视频静音",
                            subtitle = "对当前页面及后续加载的媒体持续生效",
                            checked = settings.videoMuted,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(videoMuted = enabled))
                            }
                        )
                        ToolboxSwitchRow(
                            title = "循环播放",
                            subtitle = "播放结束后从头继续",
                            checked = settings.videoLoopEnabled,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(videoLoopEnabled = enabled))
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = onTogglePlayback
                            ) {
                                Text("播放/暂停")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = onRequestFullscreen
                            ) {
                                Text("视频全屏")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onSeekBy(-10) }
                            ) {
                                Text("快退10秒")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onSeekBy(10) }
                            ) {
                                Text("快进10秒")
                            }
                        }
                    }
                }

                item {
                    ToolboxCard(title = "阅读增强") {
                        Text(
                            text = "网页文字缩放 $textZoomDraft%",
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = textZoomDraft.toFloat(),
                            onValueChange = { value ->
                                textZoomDraft = (value / TEXT_ZOOM_STEP).roundToInt() * TEXT_ZOOM_STEP
                            },
                            onValueChangeFinished = {
                                onSettingsChanged(settings.copy(textZoomPercent = textZoomDraft))
                            },
                            valueRange = MIN_TEXT_ZOOM_PERCENT.toFloat()..
                                MAX_TEXT_ZOOM_PERCENT.toFloat(),
                            steps = TEXT_ZOOM_SLIDER_STEPS
                        )
                        ToolboxSwitchRow(
                            title = "允许选择网页文字",
                            subtitle = "覆盖常见的user-select限制，不读取或上传所选内容",
                            checked = settings.textSelectionEnabled,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(textSelectionEnabled = enabled))
                            }
                        )
                        ToolboxSwitchRow(
                            title = "网页夜间遮罩",
                            subtitle = "只降低网页亮度，不改变App主题",
                            checked = settings.nightModeEnabled,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(nightModeEnabled = enabled))
                            }
                        )
                        if (settings.nightModeEnabled) {
                            Text(
                                text = "遮罩强度 ${(nightAlphaDraft * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = nightAlphaDraft,
                                onValueChange = { value -> nightAlphaDraft = snapNightAlpha(value) },
                                onValueChangeFinished = {
                                    onSettingsChanged(
                                        settings.copy(nightOverlayAlpha = nightAlphaDraft)
                                    )
                                },
                                valueRange = MIN_NIGHT_OVERLAY_ALPHA..MAX_NIGHT_OVERLAY_ALPHA,
                                steps = NIGHT_ALPHA_SLIDER_STEPS
                            )
                        }
                    }
                }

                item {
                    ToolboxCard(title = "浏览辅助") {
                        ToolboxSwitchRow(
                            title = "保持屏幕常亮",
                            subtitle = "当前网站打开期间不自动熄屏",
                            checked = settings.keepScreenOn,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(keepScreenOn = enabled))
                            }
                        )
                        ToolboxSwitchRow(
                            title = "桌面版网页",
                            subtitle = "切换后会重新加载当前页面",
                            checked = settings.desktopModeEnabled,
                            onCheckedChange = { enabled ->
                                onSettingsChanged(settings.copy(desktopModeEnabled = enabled))
                            }
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = findQuery,
                            onValueChange = { value -> onFindQueryChanged(value.take(MAX_FIND_LENGTH)) },
                            label = { Text("页内查找") },
                            supportingText = {
                                Text(findResultText.ifBlank { "输入当前网页中的文字" })
                            },
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = findQuery.isNotBlank(),
                                onClick = onFindPrevious
                            ) {
                                Text("上一个")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = findQuery.isNotBlank(),
                                onClick = onFindNext
                            ) {
                                Text("下一个")
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "说明：脚本支持标准HTML5媒体和同源页面；跨域iframe、DRM或网站自定义原生播放器可能无法控制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

/**
 * 显示一组网页工具的卡片容器。
 *
 * @param title 工具组名称。
 * @param content 卡片内部控件。
 * @return 无返回值。
 */
@Composable
private fun ToolboxCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

/**
 * 显示带标题、说明和开关的统一设置行。
 *
 * @param title 设置名称。
 * @param subtitle 设置作用说明。
 * @param checked 当前开关状态。
 * @param onCheckedChange 用户切换回调。
 * @return 无返回值。
 */
@Composable
private fun ToolboxSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/** @return 限制范围并对齐0.05步长后的播放倍速。 */
private fun snapPlaybackRate(value: Float): Float {
    return ((value / WEBSITE_PLAYBACK_RATE_STEP).roundToInt() * WEBSITE_PLAYBACK_RATE_STEP)
        .coerceIn(MIN_WEBSITE_PLAYBACK_RATE, MAX_WEBSITE_PLAYBACK_RATE)
}

/** @return 限制范围并对齐5%步长后的夜间遮罩强度。 */
private fun snapNightAlpha(value: Float): Float {
    return ((value / NIGHT_ALPHA_STEP).roundToInt() * NIGHT_ALPHA_STEP)
        .coerceIn(MIN_NIGHT_OVERLAY_ALPHA, MAX_NIGHT_OVERLAY_ALPHA)
}

/** @return 使用两位小数显示的播放倍速。 */
private fun formatPlaybackRate(rate: Float): String {
    return String.format(Locale.CHINA, "%.2f×", rate)
}

private const val PLAYBACK_RATE_SLIDER_STEPS = 94
private const val TEXT_ZOOM_STEP = 5
private const val TEXT_ZOOM_SLIDER_STEPS = 24
private const val NIGHT_ALPHA_STEP = 0.05f
private const val NIGHT_ALPHA_SLIDER_STEPS = 12
private const val MAX_FIND_LENGTH = 80
