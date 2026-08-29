package com.example.harleyapp.ui.screens

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toDrawable
import com.example.harleyapp.data.NotebookMediaStore
import com.example.harleyapp.model.NotebookCardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一套文章卡片主题的背景、前景和强调色。
 *
 * @param backgroundColors 渐变背景色。
 * @param foregroundColor 主要文字色。
 * @param secondaryForegroundColor 次要文字色。
 * @param accentColor 按钮、标签和状态强调色。
 */
data class NotebookThemePalette(
    val backgroundColors: List<Color>,
    val foregroundColor: Color,
    val secondaryForegroundColor: Color,
    val accentColor: Color
)

/**
 * 取得文章卡片预设主题的可读配色。
 *
 * @param theme 文章保存的主题枚举。
 * @return 至少包含两种渐变色以及经过对比度选择的文字色。
 */
fun notebookThemePalette(theme: NotebookCardTheme): NotebookThemePalette {
    return when (theme) {
        NotebookCardTheme.PAPER -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFFFFBF2), Color(0xFFF4EAD7)),
            foregroundColor = Color(0xFF352B20),
            secondaryForegroundColor = Color(0xFF6F6253),
            accentColor = Color(0xFF9A6731)
        )
        NotebookCardTheme.OCEAN -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFB9E6FF), Color(0xFF7BC6E8), Color(0xFF5797C4)),
            foregroundColor = Color(0xFF082B40),
            secondaryForegroundColor = Color(0xFF204B63),
            accentColor = Color(0xFF075985)
        )
        NotebookCardTheme.FOREST -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFD6F2D4), Color(0xFF9FD3A3), Color(0xFF72A47B)),
            foregroundColor = Color(0xFF12351E),
            secondaryForegroundColor = Color(0xFF315B3C),
            accentColor = Color(0xFF256D3B)
        )
        NotebookCardTheme.SUNSET -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFFFD6B8), Color(0xFFFFA98C), Color(0xFFE8797B)),
            foregroundColor = Color(0xFF4B2020),
            secondaryForegroundColor = Color(0xFF713B38),
            accentColor = Color(0xFFB3424A)
        )
        NotebookCardTheme.LAVENDER -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFE9DEFF), Color(0xFFC9B4F4), Color(0xFFAA91DD)),
            foregroundColor = Color(0xFF302044),
            secondaryForegroundColor = Color(0xFF5B4770),
            accentColor = Color(0xFF7047A8)
        )
        NotebookCardTheme.CANDY -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFFFFD8EB), Color(0xFFFFBBD0), Color(0xFFF3A7B8)),
            foregroundColor = Color(0xFF4F1D36),
            secondaryForegroundColor = Color(0xFF77445D),
            accentColor = Color(0xFFB8326A)
        )
        NotebookCardTheme.NIGHT -> NotebookThemePalette(
            backgroundColors = listOf(Color(0xFF20203A), Color(0xFF34345B), Color(0xFF4C3F68)),
            foregroundColor = Color(0xFFF7F2FF),
            secondaryForegroundColor = Color(0xFFD6CBE4),
            accentColor = Color(0xFFE0B5FF)
        )
    }
}

/**
 * 使用文章主题绘制统一渐变卡片背景。
 *
 * @param theme 卡片主题。
 * @param modifier 外部布局修饰器。
 * @param content 在渐变背景上绘制的卡片内容。
 *
 * @return 无返回值。
 */
@Composable
fun NotebookThemedBackground(
    theme: NotebookCardTheme,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(NotebookThemePalette) -> Unit
) {
    val palette = notebookThemePalette(theme)
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(palette.backgroundColors)
        )
    ) {
        content(palette)
    }
}

/**
 * 显示记事本私有目录中的静态图片或动画GIF。
 *
 * 使用方法：
 * 列表封面、详情页和编辑器统一传入媒体仓库及文件名。Android 9及以上使用ImageDecoder保留
 * GIF动画，Android 8回退为静态首帧；文件损坏或不存在时显示空容器而不会导致页面崩溃。
 *
 * @param mediaStore 记事本媒体仓库。
 * @param fileName 受控文件名。
 * @param modifier 尺寸和布局修饰器。
 * @param contentDescription 无障碍说明。
 *
 * @return 无返回值。
 */
@Composable
fun NotebookMediaImage(
    mediaStore: NotebookMediaStore,
    fileName: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "文章图片"
) {
    val file = mediaStore.fileFor(fileName)
    val drawable by produceState<Drawable?>(
        initialValue = null,
        key1 = file?.absolutePath,
        key2 = file?.lastModified()
    ) {
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                decodeNotebookDrawable(file)
            }
        }
    }
    DisposableEffect(drawable) {
        (drawable as? Animatable)?.start()
        onDispose {
            (drawable as? Animatable)?.stop()
        }
    }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .clip(RoundedCornerShape(16.dp)),
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                this.contentDescription = contentDescription
            }
        },
        update = { imageView ->
            imageView.setImageDrawable(drawable)
            imageView.contentDescription = contentDescription
            (drawable as? Animatable)?.start()
        }
    )
}

/**
 * 从App私有文件解码静态或动画Drawable。
 *
 * @param file 已通过媒体仓库校验的文件。
 * @return 解码成功的Drawable；格式损坏时返回null并写入英文日志。
 */
private fun decodeNotebookDrawable(file: File): Drawable? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        } else {
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                bitmap.toDrawable(Resources.getSystem())
            }
        }
    }.onFailure { error ->
        Log.e(NOTEBOOK_APPEARANCE_TAG, "Failed to decode notebook media", error)
    }.getOrNull()
}

private const val NOTEBOOK_APPEARANCE_TAG = "NotebookAppearance"
