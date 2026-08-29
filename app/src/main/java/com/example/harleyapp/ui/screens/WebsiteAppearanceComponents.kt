package com.example.harleyapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.formatWebsiteThemeColor
import com.example.harleyapp.model.parseWebsiteThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预设或自定义主题色解析后的卡片渐变、名称和按钮前景色。 */
internal data class WebsiteCardVisualStyle(
    val name: String,
    val colors: List<Color>,
    val actionColor: Color
)

/**
 * 把网站卡片预设和可选自定义色转换为稳定的实际配色。
 *
 * @param palette 没有自定义色时使用的预设枚举。
 * @param customColorArgb 用户自定义的不透明ARGB色；null表示使用预设。
 * @return 卡片渐变、显示名称和在白色按钮上可读的操作色。
 */
internal fun websiteCardVisualStyle(
    palette: WebsitePalette,
    customColorArgb: Long?
): WebsiteCardVisualStyle {
    if (customColorArgb != null) {
        val baseColor = Color(customColorArgb.toInt())
        val actionColor = if (baseColor.luminance() > 0.48f) {
            lerp(baseColor, Color.Black, 0.55f)
        } else {
            baseColor
        }
        return WebsiteCardVisualStyle(
            name = "自定义",
            colors = listOf(
                lerp(baseColor, Color.Black, 0.22f),
                lerp(baseColor, Color.White, 0.18f)
            ),
            actionColor = actionColor
        )
    }

    val (name, colors) = when (palette) {
        WebsitePalette.OCEAN -> "海蓝" to listOf(Color(0xFF0B5CAD), Color(0xFF00A8A8))
        WebsitePalette.VIOLET -> "星紫" to listOf(Color(0xFF5B3CC4), Color(0xFF9B4DCA))
        WebsitePalette.SUNSET -> "日落" to listOf(Color(0xFFD64B4B), Color(0xFFF28C45))
        WebsitePalette.FOREST -> "森林" to listOf(Color(0xFF176B4D), Color(0xFF4B8F45))
        WebsitePalette.ROSE -> "玫瑰" to listOf(Color(0xFFA63462), Color(0xFFD9577D))
        WebsitePalette.AMBER -> "琥珀" to listOf(Color(0xFF9A5B00), Color(0xFFD98900))
    }
    return WebsiteCardVisualStyle(
        name = name,
        colors = colors,
        actionColor = colors.first()
    )
}

/**
 * 显示网站编辑器共用的预设色、自定义色和本机背景图片设置。
 *
 * 使用方法：
 * 首页快捷编辑器和收藏详情编辑器共同调用。选择相册图片后会立即复制并压缩到App私有目录；
 * 父编辑器取消时应调用[discardUncommittedWebsiteBackground]删除尚未保存的新文件。
 *
 * @param palette 当前预设配色。
 * @param customColorArgb 当前自定义主题色。
 * @param backgroundImageFileName 当前背景图文件名。
 * @param originalBackgroundImageFileName 打开编辑器前已保存的背景图，用于区分临时新文件。
 * @param backgroundStore 私有背景图存储。
 * @param onPaletteChanged 选择预设配色后的回调。
 * @param onCustomColorChanged 自定义色变化回调；null表示恢复预设色。
 * @param onBackgroundImageChanged 背景图文件变化回调。
 * @param onMessageChanged 图片导入或颜色校验提示回调。
 * @return 无返回值。
 */
@Composable
internal fun WebsiteCardAppearanceEditor(
    palette: WebsitePalette,
    customColorArgb: Long?,
    backgroundImageFileName: String?,
    originalBackgroundImageFileName: String?,
    backgroundStore: WebsiteCardBackgroundStore,
    onPaletteChanged: (WebsitePalette) -> Unit,
    onCustomColorChanged: (Long?) -> Unit,
    onBackgroundImageChanged: (String?) -> Unit,
    onMessageChanged: (String) -> Unit
) {
    var customColorText by remember {
        mutableStateOf(formatWebsiteThemeColor(customColorArgb))
    }
    var importingImage by remember {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val backgroundImage = rememberWebsiteBackgroundImage(
        store = backgroundStore,
        fileName = backgroundImageFileName
    )
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importingImage = true
            coroutineScope.launch {
                val result = backgroundStore.importFromUri(uri)
                importingImage = false
                if (result.success) {
                    if (backgroundImageFileName != originalBackgroundImageFileName) {
                        backgroundStore.delete(backgroundImageFileName)
                    }
                    onBackgroundImageChanged(result.fileName)
                    onMessageChanged("")
                } else {
                    onMessageChanged(result.message)
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "卡片主题色",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            items(
                items = WebsitePalette.entries,
                key = WebsitePalette::name
            ) { candidate ->
                val style = websiteCardVisualStyle(candidate, customColorArgb = null)
                Surface(
                    onClick = {
                        onPaletteChanged(candidate)
                        onCustomColorChanged(null)
                        customColorText = ""
                        onMessageChanged("")
                    },
                    modifier = Modifier
                        .size(width = 70.dp, height = 52.dp)
                        .border(
                            width = if (customColorArgb == null && palette == candidate) 3.dp else 1.dp,
                            color = if (customColorArgb == null && palette == candidate) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(14.dp)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .background(Brush.linearGradient(style.colors))
                            .padding(5.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = style.name,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Text(
            text = "常用自定义色",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(
                items = CUSTOM_THEME_COLORS,
                key = { color -> color }
            ) { colorArgb ->
                val selected = customColorArgb == colorArgb
                Surface(
                    onClick = {
                        customColorText = formatWebsiteThemeColor(colorArgb)
                        onCustomColorChanged(colorArgb)
                        onMessageChanged("")
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        ),
                    shape = CircleShape,
                    color = Color(colorArgb.toInt())
                ) {}
            }
        }

        OutlinedTextField(
            value = customColorText,
            onValueChange = { newValue ->
                customColorText = newValue.take(7)
                val parsedColor = parseWebsiteThemeColor(customColorText)
                if (parsedColor != null) {
                    onCustomColorChanged(parsedColor)
                    onMessageChanged("")
                } else {
                    // 输入未形成完整颜色时立即取消旧的自定义色，避免保存上一次的有效值。
                    onCustomColorChanged(null)
                    onMessageChanged(
                        if (customColorText.isBlank()) {
                            ""
                        } else {
                            "请输入6位十六进制颜色，例如 #2F80ED"
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "自定义颜色 #RRGGBB") },
            placeholder = { Text(text = "例如 #2F80ED") },
            singleLine = true,
            trailingIcon = {
                val previewColor = parseWebsiteThemeColor(customColorText)
                Surface(
                    modifier = Modifier.size(26.dp),
                    shape = CircleShape,
                    color = previewColor?.let { value -> Color(value.toInt()) }
                        ?: MaterialTheme.colorScheme.surfaceVariant
                ) {}
            }
        )

        Text(
            text = "卡片图片背景",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        websiteCardVisualStyle(palette, customColorArgb).colors
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            backgroundImage?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "网站卡片背景预览",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.36f))
                )
            }
            Text(
                text = if (backgroundImage == null) "当前使用主题色渐变" else "图片将居中裁剪显示",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !importingImage,
                onClick = { imagePicker.launch("image/*") }
            ) {
                Text(text = if (importingImage) "正在处理…" else "选择图片")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = backgroundImageFileName != null && !importingImage,
                onClick = {
                    if (backgroundImageFileName != originalBackgroundImageFileName) {
                        backgroundStore.delete(backgroundImageFileName)
                    }
                    onBackgroundImageChanged(null)
                    onMessageChanged("")
                }
            ) {
                Text(text = "移除图片")
            }
        }
        Text(
            text = "图片会压缩到App私有目录，并随本地备份迁移；单张最多1MB。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 在后台读取私有背景图片并转换为Compose位图。
 *
 * @param store 背景图片存储。
 * @param fileName 当前图片文件名。
 * @return 图片成功解码时返回ImageBitmap，否则返回null。
 */
@Composable
internal fun rememberWebsiteBackgroundImage(
    store: WebsiteCardBackgroundStore,
    fileName: String?
): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = fileName) {
        value = withContext(Dispatchers.IO) {
            store.loadBitmap(fileName)?.asImageBitmap()
        }
    }
    return image
}

/**
 * 关闭编辑器时删除尚未写入网站模型的新背景图。
 *
 * @param store 背景图片存储。
 * @param originalFileName 打开编辑器前已保存的文件名。
 * @param currentFileName 编辑器当前文件名。
 * @return 无返回值；原有已保存图片不会被提前删除。
 */
internal fun discardUncommittedWebsiteBackground(
    store: WebsiteCardBackgroundStore,
    originalFileName: String?,
    currentFileName: String?
) {
    if (currentFileName != null && currentFileName != originalFileName) {
        store.delete(currentFileName)
    }
}

private val CUSTOM_THEME_COLORS = listOf(
    0xFF1565C0L,
    0xFF00897BL,
    0xFF2E7D32L,
    0xFF6A1B9AL,
    0xFFC62828L,
    0xFFEF6C00L,
    0xFF37474FL,
    0xFF6D4C41L
)
