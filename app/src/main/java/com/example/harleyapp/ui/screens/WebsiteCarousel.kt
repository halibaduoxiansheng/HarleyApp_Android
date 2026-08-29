package com.example.harleyapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.nextWebsiteCarouselPage
import com.example.harleyapp.model.normalizeWebsiteUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 显示首页可左右滑动的网站卡片，并承载新增、编辑、删除入口。
 *
 * 使用方法：
 * HomeScreen传入当前有序网站列表和持久化回调。存在两个及以上网站时，每隔固定时间自动
 * 切换到下一张；用户手动滑动后会从当前卡片继续轮转。离开首页或打开编辑、删除对话框时
 * 协程会暂停，不会在后台持续轮转。新增或编辑只有在回调返回true后才关闭。
 *
 * @param websites 当前网站列表，顺序就是轮播顺序。
 * @param defaultWebsiteId 点击底部“网站”时优先打开的网站标识。
 * @param onOpenWebsite 点击访问按钮后的回调，参数为当前网站。
 * @param onOpenDetails 打开完整网站收藏与文件夹管理页的回调。
 * @param onSetDefaultWebsite 把指定网站设置为默认网站的回调，成功返回true。
 * @param onSaveWebsite 新增或编辑网站的保存回调，成功返回true。
 * @param onDeleteWebsite 删除指定网站的回调，成功返回true。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出网站轮播与管理对话框。
 */
@Composable
fun WebsiteCarousel(
    websites: List<WebsiteShortcut>,
    defaultWebsiteId: String?,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onOpenDetails: () -> Unit,
    onSetDefaultWebsite: (String) -> Boolean,
    onSaveWebsite: (WebsiteShortcut) -> Boolean,
    onDeleteWebsite: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backgroundStore = remember {
        WebsiteCardBackgroundStore(context.applicationContext)
    }
    val pagerState = rememberPagerState(pageCount = { websites.size })
    var editorVisible by remember {
        mutableStateOf(false)
    }
    var editingWebsite by remember {
        mutableStateOf<WebsiteShortcut?>(null)
    }
    var deletingWebsite by remember {
        mutableStateOf<WebsiteShortcut?>(null)
    }

    // 删除当前最后一页后，把页码拉回新的有效范围，避免指示器短暂越界。
    LaunchedEffect(websites.size) {
        if (websites.isNotEmpty() && pagerState.currentPage > websites.lastIndex) {
            pagerState.scrollToPage(websites.lastIndex)
        }
    }

    // 只在首页可见且没有管理弹窗时轮播；手动滑动中的页面不会被动画抢占。
    LaunchedEffect(
        websites.map { website -> website.id },
        editorVisible,
        deletingWebsite?.id
    ) {
        while (isActive && websites.size > 1) {
            delay(AUTO_CAROUSEL_INTERVAL_MILLIS)
            if (!pagerState.isScrollInProgress && !editorVisible && deletingWebsite == null) {
                nextWebsiteCarouselPage(
                    currentPage = pagerState.currentPage,
                    pageCount = websites.size
                )?.let { nextPage ->
                    pagerState.animateScrollToPage(nextPage)
                }
            }
        }
    }

    if (editorVisible) {
        WebsiteEditorDialog(
            website = editingWebsite,
            backgroundStore = backgroundStore,
            onDismiss = {
                editorVisible = false
                editingWebsite = null
            },
            onSave = { website ->
                val saved = onSaveWebsite(website)
                if (saved) {
                    editorVisible = false
                    editingWebsite = null
                }
                saved
            }
        )
    }

    deletingWebsite?.let { website ->
        WebsiteDeleteDialog(
            website = website,
            onDismiss = {
                deletingWebsite = null
            },
            onConfirm = {
                val deleted = onDeleteWebsite(website.id)
                if (deleted) {
                    deletingWebsite = null
                }
                deleted
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "我的网站",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (websites.isEmpty()) {
                        "尚未选择需要在首页轮播的网站"
                    } else {
                        "精选轮播 · 也可左右滑动 · ${websites.size} 个网站"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenDetails) {
                    Text(text = "详情")
                }
                TextButton(
                    onClick = {
                        editingWebsite = null
                        editorVisible = true
                    }
                ) {
                    Text(text = "＋ 添加")
                }
            }
        }

        if (websites.isEmpty()) {
            EmptyWebsiteCard(
                onAddWebsite = {
                    editingWebsite = null
                    editorVisible = true
                }
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(236.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1
            ) { page ->
                val website = websites[page]
                WebsiteCarouselCard(
                    website = website,
                    backgroundStore = backgroundStore,
                    isDefault = website.id == defaultWebsiteId,
                    onOpen = {
                        onOpenWebsite(website)
                    },
                    onSetDefault = {
                        onSetDefaultWebsite(website.id)
                    },
                    onEdit = {
                        editingWebsite = website
                        editorVisible = true
                    },
                    onDelete = {
                        deletingWebsite = website
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                websites.indices.forEach { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (selected) 9.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }
        }
    }
}

/**
 * 显示一张带预设渐变色的网站卡片。
 *
 * @param website 当前网站数据。
 * @param backgroundStore 网站卡片私有背景图存储。
 * @param isDefault 当前网站是否为底部“网站”页签的默认入口。
 * @param onOpen 进入内置网页页签的回调。
 * @param onSetDefault 把当前网站设为默认入口的回调。
 * @param onEdit 打开编辑对话框的回调。
 * @param onDelete 打开删除确认框的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteCarouselCard(
    website: WebsiteShortcut,
    backgroundStore: WebsiteCardBackgroundStore,
    isDefault: Boolean,
    onOpen: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val style = websiteCardVisualStyle(website.palette, website.customColorArgb)
    val backgroundImage = rememberWebsiteBackgroundImage(
        store = backgroundStore,
        fileName = website.backgroundImageFileName
    )
    val dateText = remember {
        LocalDate.now().format(
            DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(style.colors))
        ) {
            backgroundImage?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }
            if (backgroundImage != null || website.customColorArgb != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Color.Black.copy(
                                alpha = if (backgroundImage != null) 0.38f else 0.12f
                            )
                        )
                )
            }

            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = dateText,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelLarge
                )
                Surface(
                    onClick = onSetDefault,
                    enabled = !isDefault,
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = if (isDefault) 0.24f else 0.92f)
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        text = if (isDefault) "默认网站" else "设为默认",
                        color = if (isDefault) Color.White else style.actionColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = website.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = websiteHost(website.url),
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.94f),
                        contentColor = style.actionColor
                    )
                ) {
                    Text(text = "访问网站")
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onEdit) {
                        Text(text = "编辑", color = Color.White)
                    }
                    TextButton(onClick = onDelete) {
                        Text(text = "删除", color = Color.White)
                    }
                }
            }
            }
        }
    }
}

/**
 * 在用户删除全部网站后显示可恢复的空状态。
 *
 * @param onAddWebsite 打开新增网站对话框的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyWebsiteCard(onAddWebsite: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        )
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "还没有网站卡片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "添加常用网站并选择喜欢的颜色，之后就能左右滑动访问。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddWebsite) {
                Text(text = "添加第一个网站")
            }
        }
    }
}

/**
 * 新增或编辑网站名称、网址、主题色和私有图片背景。
 *
 * @param website 编辑目标；传null表示新增网站。
 * @param backgroundStore 网站卡片私有背景图存储。
 * @param onDismiss 取消编辑回调。
 * @param onSave 保存规范化网站的回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteEditorDialog(
    website: WebsiteShortcut?,
    backgroundStore: WebsiteCardBackgroundStore,
    onDismiss: () -> Unit,
    onSave: (WebsiteShortcut) -> Boolean
) {
    var title by remember(website?.id) {
        mutableStateOf(website?.title.orEmpty())
    }
    var url by remember(website?.id) {
        mutableStateOf(website?.url.orEmpty())
    }
    var selectedPalette by remember(website?.id) {
        mutableStateOf(website?.palette ?: WebsitePalette.OCEAN)
    }
    var customColorArgb by remember(website?.id) {
        mutableStateOf(website?.customColorArgb)
    }
    var backgroundImageFileName by remember(website?.id) {
        mutableStateOf(website?.backgroundImageFileName)
    }
    var titleError by remember(website?.id) {
        mutableStateOf("")
    }
    var urlError by remember(website?.id) {
        mutableStateOf("")
    }
    var saveError by remember(website?.id) {
        mutableStateOf("")
    }
    val dismissEditor = {
        discardUncommittedWebsiteBackground(
            store = backgroundStore,
            originalFileName = website?.backgroundImageFileName,
            currentFileName = backgroundImageFileName
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismissEditor,
        title = {
            Text(text = if (website == null) "添加网站" else "编辑网站")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { newValue ->
                        title = newValue
                        titleError = ""
                        saveError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(text = "网站名称")
                    },
                    singleLine = true,
                    isError = titleError.isNotBlank(),
                    supportingText = if (titleError.isBlank()) null else {
                        {
                            Text(text = titleError)
                        }
                    }
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { newValue ->
                        url = newValue
                        urlError = ""
                        saveError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(text = "HTTPS网址")
                    },
                    placeholder = {
                        Text(text = "example.com")
                    },
                    singleLine = true,
                    isError = urlError.isNotBlank(),
                    supportingText = {
                        Text(
                            text = if (urlError.isBlank()) {
                                "支持 http:// 和 https://，未填写协议时自动补充 https://"
                            } else {
                                urlError
                            }
                        )
                    }
                )

                WebsiteCardAppearanceEditor(
                    palette = selectedPalette,
                    customColorArgb = customColorArgb,
                    backgroundImageFileName = backgroundImageFileName,
                    originalBackgroundImageFileName = website?.backgroundImageFileName,
                    backgroundStore = backgroundStore,
                    onPaletteChanged = { palette -> selectedPalette = palette },
                    onCustomColorChanged = { color -> customColorArgb = color },
                    onBackgroundImageChanged = { fileName ->
                        backgroundImageFileName = fileName
                    },
                    onMessageChanged = { message -> saveError = message }
                )

                if (saveError.isNotBlank()) {
                    Text(
                        text = saveError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedTitle = title.trim()
                    val normalizedUrl = normalizeWebsiteUrl(url)
                    titleError = if (trimmedTitle.isBlank()) "请输入网站名称" else ""
                    urlError = if (normalizedUrl == null) "请输入有效的HTTP或HTTPS网址" else ""

                    if (titleError.isBlank() && urlError.isBlank() && normalizedUrl != null) {
                        // 编辑首页卡片时保留所属文件夹、首页开关和排序，避免扁平编辑破坏收藏树。
                        val saved = onSave(
                            website?.copy(
                                title = trimmedTitle,
                                url = normalizedUrl,
                                palette = selectedPalette,
                                customColorArgb = customColorArgb,
                                backgroundImageFileName = backgroundImageFileName
                            ) ?: WebsiteShortcut(
                                id = UUID.randomUUID().toString(),
                                title = trimmedTitle,
                                url = normalizedUrl,
                                palette = selectedPalette,
                                customColorArgb = customColorArgb,
                                backgroundImageFileName = backgroundImageFileName
                            )
                        )
                        if (!saved) {
                            saveError = "保存失败，请重试"
                        }
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = dismissEditor) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 确认删除网站，同时允许删除最后一个和默认网站。
 *
 * @param website 待删除网站。
 * @param onDismiss 取消回调。
 * @param onConfirm 执行删除回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteDeleteDialog(
    website: WebsiteShortcut,
    onDismiss: () -> Unit,
    onConfirm: () -> Boolean
) {
    var deleteError by remember(website.id) {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "删除网站")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "确定从首页删除“${website.title}”吗？之后仍可重新添加。")
                if (deleteError.isNotBlank()) {
                    Text(
                        text = deleteError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onConfirm()) {
                        deleteError = "删除失败，请重试"
                    }
                }
            ) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}


/**
 * 从完整网址中提取适合卡片显示的主机名。
 *
 * @param url 完整HTTPS网址。
 *
 * @return 去掉www.前缀的主机名；解析失败时返回原网址。
 */
private fun websiteHost(url: String): String {
    return runCatching {
        URI(url).host?.removePrefix("www.").orEmpty()
    }.getOrDefault("").ifBlank { url }
}

/** 首页存在多个网站时自动切换下一张卡片的间隔。 */
private const val AUTO_CAROUSEL_INTERVAL_MILLIS = 5_000L
