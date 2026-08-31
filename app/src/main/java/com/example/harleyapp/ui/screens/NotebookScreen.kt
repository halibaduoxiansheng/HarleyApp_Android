package com.example.harleyapp.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.harleyapp.data.NotebookMediaStore
import com.example.harleyapp.data.NotebookRepository
import com.example.harleyapp.model.NotebookArticle
import com.example.harleyapp.model.NotebookBlockType
import com.example.harleyapp.model.NotebookCardLayout
import com.example.harleyapp.model.NotebookCardTheme
import com.example.harleyapp.model.NotebookContentBlock
import com.example.harleyapp.model.NotebookQueryFilter
import com.example.harleyapp.model.NotebookRecommendation
import com.example.harleyapp.model.NotebookSortOrder
import com.example.harleyapp.model.NotebookTextStyle
import com.example.harleyapp.model.buildNotebookRecommendations
import com.example.harleyapp.model.newNotebookArticle
import com.example.harleyapp.model.newNotebookStableId
import com.example.harleyapp.model.queryNotebookArticles
import com.example.harleyapp.ui.components.HarleyDatePickerDialog
import com.example.harleyapp.ui.components.bouncyClickable
import com.example.harleyapp.system.NotebookShareManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 记事本根页面的三个主分栏。 */
private enum class NotebookRootTab(val displayName: String) {
    ARTICLES("文章"),
    SEARCH("查询"),
    RECOMMENDATIONS("往期推荐")
}

/** 记事本内部的列表、详情和编辑导航状态。 */
private enum class NotebookInternalPage {
    ROOT,
    DETAIL,
    EDITOR
}

/** 查询页日期选择器的一次请求。 */
private data class NotebookDatePickerRequest(
    val title: String,
    val initialEpochDay: Long,
    val onSelected: (Long) -> Unit
)

/**
 * 显示完整的本地富内容记事本。
 *
 * 使用方法：
 * 功能中心切换到NOTEBOOK页面时调用。组件内部管理文章列表、查询、推荐、详情和编辑导航，仓库
 * 只使用本机Room兼容存储和私有媒体目录。用户从根页面返回时调用[onBack]回到功能中心。
 *
 * @param initialArticleId 从全局搜索进入时需要直接打开的文章id；普通进入时传null。
 * @param onInitialArticleConsumed 初始文章已处理后的回调，避免以后重复打开旧目标。
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外部页面安全边距。
 *
 * @return 无返回值。
 */
@Composable
fun NotebookScreen(
    initialArticleId: String? = null,
    onInitialArticleConsumed: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context.applicationContext) {
        NotebookRepository(context.applicationContext)
    }
    var articles by remember {
        mutableStateOf(repository.getArticles())
    }
    var page by rememberSaveable {
        mutableStateOf(NotebookInternalPage.ROOT)
    }
    var rootTab by rememberSaveable {
        mutableStateOf(NotebookRootTab.ARTICLES)
    }
    var selectedArticleId by rememberSaveable {
        mutableStateOf("")
    }
    var editorArticle by remember {
        mutableStateOf<NotebookArticle?>(null)
    }
    var pendingDeletion by remember {
        mutableStateOf<NotebookArticle?>(null)
    }
    var pageMessage by rememberSaveable {
        mutableStateOf("")
    }

    /** 统一刷新文章列表，保证三个根分栏使用同一份最新数据。 */
    val refreshArticles = {
        articles = repository.getArticles()
    }

    /** 打开详情并记录阅读时间，不把阅读误算成内容修改。 */
    val openArticle: (NotebookArticle) -> Unit = { article ->
        repository.markViewed(article.id)
        refreshArticles()
        selectedArticleId = article.id
        page = NotebookInternalPage.DETAIL
    }

    // 全局搜索文章只消费一次；文章已删除时回到根页面，不让旧id持续影响后续进入。
    LaunchedEffect(initialArticleId) {
        if (!initialArticleId.isNullOrBlank()) {
            articles.firstOrNull { article -> article.id == initialArticleId }
                ?.let(openArticle)
            onInitialArticleConsumed()
        }
    }

    /** 打开已有文章或新草稿编辑器。 */
    val openEditor: (NotebookArticle?) -> Unit = { article ->
        editorArticle = article ?: newNotebookArticle()
        page = NotebookInternalPage.EDITOR
    }

    BackHandler(enabled = page != NotebookInternalPage.EDITOR) {
        when (page) {
            NotebookInternalPage.ROOT -> onBack()
            NotebookInternalPage.DETAIL,
            NotebookInternalPage.EDITOR -> {
                refreshArticles()
                page = NotebookInternalPage.ROOT
            }
        }
    }

    pendingDeletion?.let { article ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(text = "删除文章") },
            text = {
                Text(
                    text = "确定删除“${article.title}”吗？文章内容和没有被其他文章使用的图片将被清理，" +
                        "此操作无法撤销。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (repository.deleteArticle(article.id)) {
                            pendingDeletion = null
                            if (selectedArticleId == article.id) {
                                selectedArticleId = ""
                                page = NotebookInternalPage.ROOT
                            }
                            refreshArticles()
                            pageMessage = "文章已删除"
                        } else {
                            pageMessage = "文章删除失败，请重试"
                        }
                    }
                ) {
                    Text(text = "确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) {
                    Text(text = "取消")
                }
            }
        )
    }

    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState == NotebookInternalPage.ROOT) {
                (slideInHorizontally { width -> -width / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { width -> width / 4 } + fadeOut())
            } else {
                (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { width -> -width / 4 } + fadeOut())
            }
        },
        label = "notebookPage"
    ) { currentPage ->
        when (currentPage) {
            NotebookInternalPage.ROOT -> NotebookRootScreen(
                articles = articles,
                selectedTab = rootTab,
                pageMessage = pageMessage,
                mediaStore = repository.mediaStore,
                onTabSelected = {
                    rootTab = it
                    pageMessage = ""
                },
                onBack = onBack,
                onCreate = { openEditor(null) },
                onOpen = openArticle,
                onEdit = openEditor,
                onDelete = { article -> pendingDeletion = article },
                onFavorite = { article ->
                    if (repository.setFavorite(article.id, !article.isFavorite) != null) {
                        refreshArticles()
                    } else {
                        pageMessage = "收藏状态保存失败，请重试"
                    }
                },
                onPin = { article ->
                    if (repository.setPinned(article.id, !article.isPinned) != null) {
                        refreshArticles()
                    } else {
                        pageMessage = "置顶状态保存失败，请重试"
                    }
                }
            )

            NotebookInternalPage.DETAIL -> {
                val article = articles.firstOrNull { item -> item.id == selectedArticleId }
                if (article == null) {
                    LaunchedEffect(selectedArticleId) {
                        page = NotebookInternalPage.ROOT
                    }
                } else {
                    NotebookArticleDetailScreen(
                        article = article,
                        mediaStore = repository.mediaStore,
                        onBack = {
                            refreshArticles()
                            page = NotebookInternalPage.ROOT
                        },
                        onEdit = { openEditor(article) },
                        onDelete = { pendingDeletion = article },
                        onDuplicate = {
                            val duplicated = repository.duplicateArticle(article.id)
                            if (duplicated != null) {
                                refreshArticles()
                                editorArticle = duplicated
                                page = NotebookInternalPage.EDITOR
                            } else {
                                pageMessage = "文章复制失败，请重试"
                            }
                        }
                    )
                }
            }

            NotebookInternalPage.EDITOR -> {
                val article = editorArticle
                if (article == null) {
                    LaunchedEffect(Unit) {
                        page = NotebookInternalPage.ROOT
                    }
                } else {
                    NotebookArticleEditorScreen(
                        initialArticle = article,
                        repository = repository,
                        onBack = { savedDraft ->
                            editorArticle = null
                            refreshArticles()
                            page = NotebookInternalPage.ROOT
                            pageMessage = if (savedDraft) "草稿已自动保存" else "未保存空白草稿"
                        },
                        onCompleted = { savedArticle ->
                            editorArticle = null
                            refreshArticles()
                            selectedArticleId = savedArticle.id
                            page = NotebookInternalPage.DETAIL
                        }
                    )
                }
            }
        }
    }
}

/**
 * 显示文章、查询和往期推荐三个根分栏。
 *
 * @param articles 当前全部文章。
 * @param selectedTab 当前分栏。
 * @param pageMessage 最近操作反馈。
 * @param mediaStore 图片和GIF仓库。
 * @param onTabSelected 切换分栏回调。
 * @param onBack 返回功能中心回调。
 * @param onCreate 新建文章回调。
 * @param onOpen 阅读文章回调。
 * @param onEdit 编辑文章回调。
 * @param onDelete 删除文章回调。
 * @param onFavorite 收藏切换回调。
 * @param onPin 置顶切换回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookRootScreen(
    articles: List<NotebookArticle>,
    selectedTab: NotebookRootTab,
    pageMessage: String,
    mediaStore: NotebookMediaStore,
    onTabSelected: (NotebookRootTab) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (NotebookArticle) -> Unit,
    onEdit: (NotebookArticle) -> Unit,
    onDelete: (NotebookArticle) -> Unit,
    onFavorite: (NotebookArticle) -> Unit,
    onPin: (NotebookArticle) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "记事本",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "富内容文章、查询与往期回顾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onCreate) {
                Text(text = "写文章")
            }
        }
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            NotebookRootTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(text = tab.displayName) }
                )
            }
        }
        if (pageMessage.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    text = pageMessage,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        when (selectedTab) {
            NotebookRootTab.ARTICLES -> NotebookArticleList(
                articles = articles,
                mediaStore = mediaStore,
                onCreate = onCreate,
                onOpen = onOpen,
                onEdit = onEdit,
                onDelete = onDelete,
                onFavorite = onFavorite,
                onPin = onPin
            )
            NotebookRootTab.SEARCH -> NotebookSearchPage(
                articles = articles,
                mediaStore = mediaStore,
                onOpen = onOpen,
                onEdit = onEdit,
                onDelete = onDelete,
                onFavorite = onFavorite,
                onPin = onPin
            )
            NotebookRootTab.RECOMMENDATIONS -> NotebookRecommendationPage(
                articles = articles,
                mediaStore = mediaStore,
                onOpen = onOpen,
                onEdit = onEdit,
                onDelete = onDelete,
                onFavorite = onFavorite,
                onPin = onPin
            )
        }
    }
}

/**
 * 显示全部文章、草稿、收藏和置顶状态。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookArticleList(
    articles: List<NotebookArticle>,
    mediaStore: NotebookMediaStore,
    onCreate: () -> Unit,
    onOpen: (NotebookArticle) -> Unit,
    onEdit: (NotebookArticle) -> Unit,
    onDelete: (NotebookArticle) -> Unit,
    onFavorite: (NotebookArticle) -> Unit,
    onPin: (NotebookArticle) -> Unit
) {
    if (articles.isEmpty()) {
        NotebookEmptyState(
            title = "开始写第一篇文章",
            description = "可以加入文字、Emoji、相册图片、GIF表情包和链接，并自由设置卡片主题。",
            actionText = "新建文章",
            onAction = onCreate
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            val publishedCount = articles.count { article -> !article.isDraft }
            val draftCount = articles.size - publishedCount
            Text(
                text = "共${articles.size}篇 · 已完成$publishedCount · 草稿$draftCount",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(articles, key = NotebookArticle::id) { article ->
            NotebookArticleCard(
                article = article,
                mediaStore = mediaStore,
                onOpen = { onOpen(article) },
                onEdit = { onEdit(article) },
                onDelete = { onDelete(article) },
                onFavorite = { onFavorite(article) },
                onPin = { onPin(article) }
            )
        }
    }
}

/**
 * 显示支持关键词、日期、内容类型与排序的文章查询页。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookSearchPage(
    articles: List<NotebookArticle>,
    mediaStore: NotebookMediaStore,
    onOpen: (NotebookArticle) -> Unit,
    onEdit: (NotebookArticle) -> Unit,
    onDelete: (NotebookArticle) -> Unit,
    onFavorite: (NotebookArticle) -> Unit,
    onPin: (NotebookArticle) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var startEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var endEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var favoriteOnly by rememberSaveable { mutableStateOf(false) }
    var imageOnly by rememberSaveable { mutableStateOf(false) }
    var linkOnly by rememberSaveable { mutableStateOf(false) }
    var includeDrafts by rememberSaveable { mutableStateOf(true) }
    var sortOrder by rememberSaveable { mutableStateOf(NotebookSortOrder.UPDATED_DESC) }
    var datePickerRequest by remember { mutableStateOf<NotebookDatePickerRequest?>(null) }
    val filter = NotebookQueryFilter(
        query = query,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        favoriteOnly = favoriteOnly,
        imageOnly = imageOnly,
        linkOnly = linkOnly,
        includeDrafts = includeDrafts,
        sortOrder = sortOrder
    )
    val results = remember(articles, filter) {
        queryNotebookArticles(articles, filter)
    }

    datePickerRequest?.let { request ->
        HarleyDatePickerDialog(
            visible = true,
            title = request.title,
            initialEpochDay = request.initialEpochDay,
            minEpochDay = LocalDate.of(1970, 1, 1).toEpochDay(),
            maxEpochDay = LocalDate.now().toEpochDay(),
            onDismiss = { datePickerRequest = null },
            onDateSelected = { selected ->
                request.onSelected(selected)
                datePickerRequest = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it.take(MAX_NOTEBOOK_QUERY_LENGTH) },
                label = { Text(text = "搜索标题、正文、标签和链接") },
                singleLine = true
            )
        }
        item {
            Text(text = "筛选内容", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = favoriteOnly,
                    onClick = { favoriteOnly = !favoriteOnly },
                    label = { Text(text = "收藏") }
                )
                FilterChip(
                    selected = imageOnly,
                    onClick = { imageOnly = !imageOnly },
                    label = { Text(text = "含图片/GIF") }
                )
                FilterChip(
                    selected = linkOnly,
                    onClick = { linkOnly = !linkOnly },
                    label = { Text(text = "含链接") }
                )
                FilterChip(
                    selected = includeDrafts,
                    onClick = { includeDrafts = !includeDrafts },
                    label = { Text(text = "包含草稿") }
                )
            }
        }
        item {
            Text(text = "创建日期", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        datePickerRequest = NotebookDatePickerRequest(
                            title = "选择开始日期",
                            initialEpochDay = startEpochDay ?: LocalDate.now().minusMonths(1).toEpochDay(),
                            onSelected = { startEpochDay = it }
                        )
                    }
                ) {
                    Text(text = startEpochDay?.let(::formatNotebookDate) ?: "开始日期")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        datePickerRequest = NotebookDatePickerRequest(
                            title = "选择结束日期",
                            initialEpochDay = endEpochDay ?: LocalDate.now().toEpochDay(),
                            onSelected = { endEpochDay = it }
                        )
                    }
                ) {
                    Text(text = endEpochDay?.let(::formatNotebookDate) ?: "结束日期")
                }
                if (startEpochDay != null || endEpochDay != null) {
                    TextButton(
                        onClick = {
                            startEpochDay = null
                            endEpochDay = null
                        }
                    ) {
                        Text(text = "清除")
                    }
                }
            }
        }
        item {
            Text(text = "排序", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotebookSortOrder.entries.forEach { order ->
                    FilterChip(
                        selected = sortOrder == order,
                        onClick = { sortOrder = order },
                        label = { Text(text = order.displayName) }
                    )
                }
            }
        }
        item {
            Text(
                text = "找到 ${results.size} 篇文章",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (results.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        modifier = Modifier.padding(18.dp),
                        text = "没有匹配文章。可以减少筛选条件或更换关键词。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(results, key = NotebookArticle::id) { article ->
                NotebookArticleCard(
                    article = article,
                    mediaStore = mediaStore,
                    highlightedQuery = query,
                    onOpen = { onOpen(article) },
                    onEdit = { onEdit(article) },
                    onDelete = { onDelete(article) },
                    onFavorite = { onFavorite(article) },
                    onPin = { onPin(article) }
                )
            }
        }
    }
}

/**
 * 显示由本机时间、标签、收藏和阅读记录生成的往期推荐。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookRecommendationPage(
    articles: List<NotebookArticle>,
    mediaStore: NotebookMediaStore,
    onOpen: (NotebookArticle) -> Unit,
    onEdit: (NotebookArticle) -> Unit,
    onDelete: (NotebookArticle) -> Unit,
    onFavorite: (NotebookArticle) -> Unit,
    onPin: (NotebookArticle) -> Unit
) {
    val recommendations = remember(articles, LocalDate.now().toEpochDay()) {
        buildNotebookRecommendations(articles)
    }
    if (recommendations.isEmpty()) {
        NotebookEmptyState(
            title = "还没有可以回顾的文章",
            description = "完成文章后，这里会根据时间、收藏、标签和阅读记录生成本地推荐。"
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "今天值得回顾",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "推荐只在本机计算，并在每张卡片上说明原因。",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        items(recommendations, key = { recommendation -> recommendation.article.id }) { item ->
            NotebookRecommendationCard(
                recommendation = item,
                mediaStore = mediaStore,
                onOpen = { onOpen(item.article) },
                onEdit = { onEdit(item.article) },
                onDelete = { onDelete(item.article) },
                onFavorite = { onFavorite(item.article) },
                onPin = { onPin(item.article) }
            )
        }
    }
}

/**
 * 显示一条带推荐原因的文章卡片。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookRecommendationCard(
    recommendation: NotebookRecommendation,
    mediaStore: NotebookMediaStore,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onPin: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                text = "推荐理由：${recommendation.reason}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        NotebookArticleCard(
            article = recommendation.article,
            mediaStore = mediaStore,
            onOpen = onOpen,
            onEdit = onEdit,
            onDelete = onDelete,
            onFavorite = onFavorite,
            onPin = onPin
        )
    }
}

/**
 * 使用文章独立主题和版式显示可操作卡片。
 *
 * @param article 文章。
 * @param mediaStore 媒体仓库。
 * @param highlightedQuery 查询页高亮关键词。
 * @param onOpen 阅读回调。
 * @param onEdit 编辑回调。
 * @param onDelete 删除回调。
 * @param onFavorite 收藏回调。
 * @param onPin 置顶回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookArticleCard(
    article: NotebookArticle,
    mediaStore: NotebookMediaStore,
    highlightedQuery: String = "",
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onPin: () -> Unit
) {
    val coverFileName = article.coverMediaFileName()
    val palette = notebookThemePalette(article.cardTheme)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .bouncyClickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        NotebookThemedBackground(
            theme = article.cardTheme,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (article.cardLayout == NotebookCardLayout.COVER && coverFileName != null) {
                    NotebookMediaImage(
                        mediaStore = mediaStore,
                        fileName = coverFileName,
                        modifier = Modifier.heightIn(min = 190.dp),
                        contentDescription = "${article.title}封面"
                    )
                }
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = highlightNotebookText(
                                    text = article.title.ifBlank { "未命名文章" },
                                    query = highlightedQuery,
                                    highlightColor = palette.accentColor
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = palette.foregroundColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    if (article.isPinned) append("置顶 · ")
                                    if (article.isDraft) append("草稿 · ")
                                    append("修改于${formatNotebookTime(article.updatedAtMillis)}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.secondaryForegroundColor
                            )
                        }
                        if (article.isFavorite) {
                            Text(text = "♥", color = palette.accentColor, fontSize = 22.sp)
                        }
                    }
                    if (article.cardLayout != NotebookCardLayout.MINIMAL) {
                        if (article.cardLayout == NotebookCardLayout.STANDARD && coverFileName != null) {
                            NotebookMediaImage(
                                mediaStore = mediaStore,
                                fileName = coverFileName,
                                modifier = Modifier.heightIn(min = 150.dp),
                                contentDescription = "${article.title}预览图"
                            )
                        }
                        Text(
                            text = highlightNotebookText(
                                text = article.summary(),
                                query = highlightedQuery,
                                highlightColor = palette.accentColor
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.secondaryForegroundColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (article.tags.isNotEmpty()) {
                        Text(
                            text = article.tags.joinToString("  ") { tag -> "#$tag" },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(onClick = onEdit) {
                            Text(text = "编辑", color = palette.accentColor)
                        }
                        TextButton(onClick = onFavorite) {
                            Text(
                                text = if (article.isFavorite) "取消收藏" else "收藏",
                                color = palette.accentColor
                            )
                        }
                        TextButton(onClick = onPin) {
                            Text(
                                text = if (article.isPinned) "取消置顶" else "置顶",
                                color = palette.accentColor
                            )
                        }
                        TextButton(onClick = onDelete) {
                            Text(text = "删除", color = Color(0xFFB3261E))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 显示文章详情、全部内容块以及编辑、复制、分享和删除入口。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookArticleDetailScreen(
    article: NotebookArticle,
    mediaStore: NotebookMediaStore,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shareManager = remember(context, mediaStore) {
        NotebookShareManager(context, mediaStore)
    }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }
    val palette = notebookThemePalette(article.cardTheme)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            NotebookThemedBackground(
                theme = article.cardTheme,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack) {
                            Text(text = "返回", color = palette.accentColor)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onEdit) {
                            Text(text = "编辑", color = palette.accentColor)
                        }
                    }
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.foregroundColor
                    )
                    if (article.tags.isNotEmpty()) {
                        Text(
                            text = article.tags.joinToString("  ") { tag -> "#$tag" },
                            color = palette.accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "创建：${formatNotebookTime(article.createdAtMillis)}\n" +
                            "最近修改：${formatNotebookTime(article.updatedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryForegroundColor
                    )
                }
            }
        }
        itemsIndexed(article.blocks, key = { _, block -> block.id }) { index, block ->
            NotebookBlockReader(
                block = block,
                blockIndex = index,
                mediaStore = mediaStore,
                onOpenLink = { url -> openNotebookLink(context, url) }
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onEdit) { Text(text = "继续编辑") }
                OutlinedButton(onClick = onDuplicate) { Text(text = "复制为草稿") }
                OutlinedButton(onClick = { showShareDialog = true }) {
                    Text(text = "分享与导出")
                }
                OutlinedButton(onClick = onDelete) {
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showShareDialog) {
        NotebookShareChoiceDialog(
            hasMedia = article.hasImage(),
            onDismiss = { showShareDialog = false },
            onSharePlainText = {
                showShareDialog = false
                shareManager.sharePlainText(article)
            },
            onShareRichContent = {
                showShareDialog = false
                shareManager.shareRichContent(article)
            },
            onExportPackage = {
                showShareDialog = false
                shareManager.shareArticlePackage(article)
            }
        )
    }
}

/**
 * 显示记事本文章的分享方式选择窗口。
 *
 * 使用方法：
 * 用户点击详情页“分享与导出”后显示；纯文本适合即时聊天，图文分享附带原图，文章包适合完整迁移。
 *
 * @param hasMedia 当前文章是否声明了图片或GIF，用于提示图文分享是否会回退为纯文本。
 * @param onDismiss 关闭窗口回调。
 * @param onSharePlainText 选择纯文本分享的回调。
 * @param onShareRichContent 选择图文分享的回调。
 * @param onExportPackage 选择ZIP文章包的回调。
 *
 * @return 无返回值，直接输出选择对话框。
 */
@Composable
private fun NotebookShareChoiceDialog(
    hasMedia: Boolean,
    onDismiss: () -> Unit,
    onSharePlainText: () -> Unit,
    onShareRichContent: () -> Unit,
    onExportPackage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享与导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "纯文本适合微信聊天；图文分享会同时附带原图；文章包包含Markdown、HTML和媒体文件。",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!hasMedia) {
                    Text(
                        text = "这篇文章没有图片，选择图文分享时会自动发送纯文本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                TextButton(onClick = onSharePlainText) { Text("分享纯文本") }
                TextButton(onClick = onShareRichContent) { Text("分享图文") }
                TextButton(onClick = onExportPackage) { Text("导出文章包") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 按类型渲染详情页的一个内容块。
 *
 * @param block 内容块。
 * @param blockIndex 块在文章中的位置，用于编号列表。
 * @param mediaStore 媒体仓库。
 * @param onOpenLink 打开安全链接的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookBlockReader(
    block: NotebookContentBlock,
    blockIndex: Int,
    mediaStore: NotebookMediaStore,
    onOpenLink: (String) -> Unit
) {
    when (block.type) {
        NotebookBlockType.TEXT -> {
            val prefix = when (block.textStyle) {
                NotebookTextStyle.BULLET -> "• "
                NotebookTextStyle.NUMBERED -> "${blockIndex + 1}. "
                else -> ""
            }
            val textStyle = when (block.textStyle) {
                NotebookTextStyle.HEADING -> MaterialTheme.typography.headlineSmall
                NotebookTextStyle.QUOTE -> MaterialTheme.typography.bodyLarge
                else -> MaterialTheme.typography.bodyLarge
            }
            if (block.textStyle == NotebookTextStyle.QUOTE) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = "“${block.text}”",
                        style = textStyle,
                        fontWeight = if (block.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (block.underline) TextDecoration.Underline else null
                    )
                }
            } else if (block.text.isNotBlank()) {
                Text(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    text = prefix + block.text,
                    style = textStyle,
                    fontWeight = if (block.bold || block.textStyle == NotebookTextStyle.HEADING) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    },
                    fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (block.underline) TextDecoration.Underline else null
                )
            }
        }
        NotebookBlockType.IMAGE -> {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NotebookMediaImage(
                    mediaStore = mediaStore,
                    fileName = block.mediaFileName,
                    contentDescription = block.mediaCaption.ifBlank { "文章图片" }
                )
                if (block.mediaCaption.isNotBlank()) {
                    Text(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        text = block.mediaCaption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        NotebookBlockType.LINK -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .bouncyClickable { onOpenLink(block.linkUrl) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = block.linkTitle.ifBlank { "打开链接" },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = block.linkUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        NotebookBlockType.DIVIDER -> {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)
            )
        }
    }
}

/**
 * 编辑一篇文章并自动保存草稿。
 *
 * 使用方法：
 * 新建或编辑时传入初始文章。任一字段改变900毫秒后自动保存；返回前再同步保存一次。点击“完成”
 * 会校验标题、正文和链接，把草稿状态改为已完成后交给[onCompleted]。
 *
 * @param initialArticle 初始文章。
 * @param repository 文章仓库。
 * @param onBack 返回列表回调，参数表示是否保存了有效草稿。
 * @param onCompleted 正式保存成功后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookArticleEditorScreen(
    initialArticle: NotebookArticle,
    repository: NotebookRepository,
    onBack: (Boolean) -> Unit,
    onCompleted: (NotebookArticle) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var article by remember(initialArticle.id) {
        mutableStateOf(
            initialArticle.copy(
                title = initialArticle.title.takeUnless { title -> title == "未命名文章" }.orEmpty()
            )
        )
    }
    var tagsText by rememberSaveable(initialArticle.id) {
        mutableStateOf(initialArticle.tags.joinToString("，"))
    }
    var hasUserChanged by rememberSaveable(initialArticle.id) {
        mutableStateOf(false)
    }
    var statusMessage by rememberSaveable(initialArticle.id) {
        mutableStateOf(if (initialArticle.isDraft) "草稿会自动保存" else "修改会自动保存为草稿")
    }
    var emojiTargetBlockId by rememberSaveable(initialArticle.id) {
        mutableStateOf("")
    }
    var replaceMediaBlockId by rememberSaveable(initialArticle.id) {
        mutableStateOf("")
    }

    /** 统一更新文章并启动防抖自动保存。 */
    val updateArticle: ((NotebookArticle) -> NotebookArticle) -> Unit = { transform ->
        article = transform(article)
        hasUserChanged = true
        statusMessage = "正在编辑…"
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                statusMessage = "正在导入图片…"
                val result = repository.mediaStore.importFromUri(uri)
                if (result.success) {
                    updateArticle { current ->
                        val newBlock = NotebookContentBlock(
                            id = replaceMediaBlockId.ifBlank { newNotebookStableId("block") },
                            type = NotebookBlockType.IMAGE,
                            mediaFileName = result.fileName,
                            mediaMimeType = result.mimeType
                        )
                        if (replaceMediaBlockId.isBlank()) {
                            current.copy(blocks = current.blocks + newBlock)
                        } else {
                            current.copy(
                                blocks = current.blocks.map { block ->
                                    if (block.id == replaceMediaBlockId) {
                                        newBlock.copy(
                                            mediaCaption = block.mediaCaption
                                        )
                                    } else {
                                        block
                                    }
                                }
                            )
                        }
                    }
                    statusMessage = result.message
                } else {
                    statusMessage = result.message
                }
                replaceMediaBlockId = ""
            }
        } else {
            replaceMediaBlockId = ""
        }
    }

    /**
     * 同步保存当前有效内容并退出编辑器。
     *
     * @return 无返回值；保存结果通过[onBack]参数交给根页面显示。
     */
    val saveDraftAndBack: () -> Unit = {
        val hasContent = article.title.isNotBlank() ||
            article.blocks.any(::notebookBlockHasUserContent)
        val saved = if (hasContent) {
            repository.saveArticle(
                article.copy(tags = parseNotebookTags(tagsText)),
                asDraft = true
            ) != null
        } else {
            false
        }
        onBack(saved)
    }

    BackHandler(onBack = saveDraftAndBack)

    LaunchedEffect(article, tagsText, hasUserChanged) {
        if (!hasUserChanged) return@LaunchedEffect
        delay(AUTO_SAVE_DELAY_MILLIS)
        val articleWithTags = article.copy(tags = parseNotebookTags(tagsText))
        val saved = repository.saveArticle(articleWithTags, asDraft = true)
        statusMessage = if (saved != null) {
            "草稿已自动保存 · ${formatNotebookTime(saved.updatedAtMillis)}"
        } else {
            "自动保存失败，请点击完成前重试"
        }
    }

    if (emojiTargetBlockId.isNotBlank()) {
        NotebookEmojiPickerDialog(
            onDismiss = { emojiTargetBlockId = "" },
            onEmojiSelected = { emoji ->
                updateArticle { current ->
                    current.copy(
                        blocks = current.blocks.map { block ->
                            if (block.id == emojiTargetBlockId) {
                                block.copy(text = (block.text + emoji).take(MAX_NOTEBOOK_TEXT_LENGTH))
                            } else {
                                block
                            }
                        }
                    )
                }
                emojiTargetBlockId = ""
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = saveDraftAndBack
                ) {
                    Text(text = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (initialArticle.isDraft) "编辑文章" else "修改文章",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        val normalizedBlocks = normalizeNotebookLinkBlocks(article.blocks)
                        val invalidLink = normalizedBlocks.any { block ->
                            block.type == NotebookBlockType.LINK &&
                                normalizeNotebookLink(block.linkUrl) == null
                        }
                        val hasPublishableContent = normalizedBlocks.any { block ->
                            when (block.type) {
                                NotebookBlockType.TEXT -> block.text.isNotBlank()
                                NotebookBlockType.IMAGE -> block.mediaFileName.isNotBlank()
                                NotebookBlockType.LINK -> block.linkUrl.isNotBlank()
                                NotebookBlockType.DIVIDER -> false
                            }
                        }
                        statusMessage = when {
                            article.title.trim().isBlank() -> "请先填写文章标题"
                            !hasPublishableContent -> "请至少加入一段文字、图片或链接"
                            invalidLink -> "链接地址无效，请使用http或https地址"
                            else -> {
                                val saved = repository.saveArticle(
                                    article = article.copy(
                                        title = article.title.trim(),
                                        blocks = normalizedBlocks,
                                        tags = parseNotebookTags(tagsText)
                                    ),
                                    asDraft = false
                                )
                                if (saved != null) {
                                    onCompleted(saved)
                                    ""
                                } else {
                                    "文章保存失败，请重试"
                                }
                            }
                        }
                    }
                ) {
                    Text(text = "完成")
                }
            }
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = article.title,
                onValueChange = { title ->
                    updateArticle { current -> current.copy(title = title.take(MAX_NOTEBOOK_TITLE_LENGTH)) }
                },
                label = { Text(text = "文章标题") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = tagsText,
                onValueChange = {
                    tagsText = it.take(MAX_NOTEBOOK_TAG_INPUT_LENGTH)
                    hasUserChanged = true
                },
                label = { Text(text = "标签，用逗号分隔") },
                supportingText = { Text(text = "例如：旅行，灵感，工作") },
                singleLine = true
            )
        }
        item {
            NotebookAppearanceEditor(
                article = article,
                mediaStore = repository.mediaStore,
                onChanged = { changed -> updateArticle { changed } }
            )
        }
        itemsIndexed(
            items = article.blocks,
            key = { _, block -> block.id }
        ) { index, block ->
            NotebookBlockEditorCard(
                block = block,
                index = index,
                totalCount = article.blocks.size,
                isCover = article.coverBlockId == block.id,
                mediaStore = repository.mediaStore,
                onChanged = { changedBlock ->
                    updateArticle { current ->
                        current.copy(
                            blocks = current.blocks.map { item ->
                                if (item.id == changedBlock.id) changedBlock else item
                            }
                        )
                    }
                },
                onMoveUp = {
                    updateArticle { current ->
                        current.copy(blocks = moveNotebookBlock(current.blocks, index, index - 1))
                    }
                },
                onMoveDown = {
                    updateArticle { current ->
                        current.copy(blocks = moveNotebookBlock(current.blocks, index, index + 1))
                    }
                },
                onDelete = {
                    updateArticle { current ->
                        val remaining = current.blocks.filterNot { item -> item.id == block.id }
                        current.copy(
                            blocks = remaining.ifEmpty { listOf(NotebookContentBlock.emptyText()) },
                            coverBlockId = current.coverBlockId.takeUnless { it == block.id }.orEmpty()
                        )
                    }
                },
                onEmoji = { emojiTargetBlockId = block.id },
                onReplaceMedia = {
                    replaceMediaBlockId = block.id
                    mediaPicker.launch("image/*")
                },
                onSetCover = {
                    updateArticle { current -> current.copy(coverBlockId = block.id) }
                }
            )
        }
        item {
            NotebookInsertToolbar(
                onAddText = {
                    updateArticle { current ->
                        current.copy(blocks = current.blocks + NotebookContentBlock.emptyText())
                    }
                },
                onAddImage = {
                    replaceMediaBlockId = ""
                    mediaPicker.launch("image/*")
                },
                onAddLink = {
                    updateArticle { current ->
                        current.copy(
                            blocks = current.blocks + NotebookContentBlock(
                                id = newNotebookStableId("block"),
                                type = NotebookBlockType.LINK
                            )
                        )
                    }
                },
                onAddDivider = {
                    updateArticle { current ->
                        current.copy(
                            blocks = current.blocks + NotebookContentBlock(
                                id = newNotebookStableId("block"),
                                type = NotebookBlockType.DIVIDER
                            )
                        )
                    }
                }
            )
        }
        item {
            Text(
                text = "创建于 ${formatNotebookTime(initialArticle.createdAtMillis)} · " +
                    "图片和GIF仅保存在本机并随本地备份迁移",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 编辑并预览文章卡片主题和版式。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookAppearanceEditor(
    article: NotebookArticle,
    mediaStore: NotebookMediaStore,
    onChanged: (NotebookArticle) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "文章卡片外观", fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(NotebookCardTheme.entries) { theme ->
                    val palette = notebookThemePalette(theme)
                    Surface(
                        modifier = Modifier
                            .size(width = 88.dp, height = 58.dp)
                            .bouncyClickable { onChanged(article.copy(cardTheme = theme)) },
                        shape = RoundedCornerShape(14.dp),
                        color = palette.backgroundColors.first(),
                        border = if (theme == article.cardTheme) {
                            androidx.compose.foundation.BorderStroke(2.dp, palette.accentColor)
                        } else {
                            null
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = theme.displayName,
                                color = palette.foregroundColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotebookCardLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = article.cardLayout == layout,
                        onClick = { onChanged(article.copy(cardLayout = layout)) },
                        label = { Text(text = layout.displayName) }
                    )
                }
            }
            Text(
                text = "实时预览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NotebookArticlePreview(
                article = article,
                mediaStore = mediaStore
            )
        }
    }
}

/**
 * 在编辑器中显示不可操作的轻量卡片预览。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookArticlePreview(
    article: NotebookArticle,
    mediaStore: NotebookMediaStore
) {
    val palette = notebookThemePalette(article.cardTheme)
    NotebookThemedBackground(
        theme = article.cardTheme,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Transparent)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = article.title.ifBlank { "文章标题预览" },
                color = palette.foregroundColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            if (article.cardLayout != NotebookCardLayout.MINIMAL) {
                Text(
                    text = article.summary(60),
                    color = palette.secondaryForegroundColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (article.cardLayout == NotebookCardLayout.COVER) {
                article.coverMediaFileName()?.let { fileName ->
                    NotebookMediaImage(
                        mediaStore = mediaStore,
                        fileName = fileName,
                        modifier = Modifier.heightIn(min = 120.dp),
                        contentDescription = "卡片封面预览"
                    )
                }
            }
        }
    }
}

/**
 * 编辑一个文字、图片、链接或分隔线内容块。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookBlockEditorCard(
    block: NotebookContentBlock,
    index: Int,
    totalCount: Int,
    isCover: Boolean,
    mediaStore: NotebookMediaStore,
    onChanged: (NotebookContentBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onEmoji: () -> Unit,
    onReplaceMedia: () -> Unit,
    onSetCover: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = when (block.type) {
                        NotebookBlockType.TEXT -> "文字段落 ${index + 1}"
                        NotebookBlockType.IMAGE -> "图片/GIF ${index + 1}"
                        NotebookBlockType.LINK -> "链接卡片 ${index + 1}"
                        NotebookBlockType.DIVIDER -> "分隔线 ${index + 1}"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(enabled = index > 0, onClick = onMoveUp) { Text(text = "上移") }
                TextButton(enabled = index < totalCount - 1, onClick = onMoveDown) {
                    Text(text = "下移")
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            }
            when (block.type) {
                NotebookBlockType.TEXT -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NotebookTextStyle.entries.forEach { style ->
                            FilterChip(
                                selected = block.textStyle == style,
                                onClick = { onChanged(block.copy(textStyle = style)) },
                                label = { Text(text = style.displayName) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = block.bold,
                            onClick = { onChanged(block.copy(bold = !block.bold)) },
                            label = { Text(text = "加粗", fontWeight = FontWeight.Bold) }
                        )
                        FilterChip(
                            selected = block.italic,
                            onClick = { onChanged(block.copy(italic = !block.italic)) },
                            label = { Text(text = "斜体", fontStyle = FontStyle.Italic) }
                        )
                        FilterChip(
                            selected = block.underline,
                            onClick = { onChanged(block.copy(underline = !block.underline)) },
                            label = {
                                Text(text = "下划线", textDecoration = TextDecoration.Underline)
                            }
                        )
                        OutlinedButton(onClick = onEmoji) {
                            Text(text = "😊")
                        }
                    }
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 130.dp),
                        value = block.text,
                        onValueChange = { text ->
                            onChanged(block.copy(text = text.take(MAX_NOTEBOOK_TEXT_LENGTH)))
                        },
                        label = { Text(text = "输入正文") }
                    )
                }
                NotebookBlockType.IMAGE -> {
                    NotebookMediaImage(
                        mediaStore = mediaStore,
                        fileName = block.mediaFileName,
                        contentDescription = block.mediaCaption.ifBlank { "编辑中的图片" }
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = block.mediaCaption,
                        onValueChange = { caption ->
                            onChanged(block.copy(mediaCaption = caption.take(MAX_NOTEBOOK_CAPTION_LENGTH)))
                        },
                        label = { Text(text = "图片说明（可选）") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReplaceMedia) {
                            Text(text = "更换图片/GIF")
                        }
                        OutlinedButton(onClick = onSetCover) {
                            Text(text = if (isCover) "当前封面" else "设为封面")
                        }
                    }
                }
                NotebookBlockType.LINK -> {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = block.linkTitle,
                        onValueChange = { title ->
                            onChanged(block.copy(linkTitle = title.take(MAX_NOTEBOOK_LINK_TITLE_LENGTH)))
                        },
                        label = { Text(text = "链接标题") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = block.linkUrl,
                        onValueChange = { url ->
                            onChanged(block.copy(linkUrl = url.take(MAX_NOTEBOOK_LINK_LENGTH)))
                        },
                        label = { Text(text = "链接地址，例如 https://example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                }
                NotebookBlockType.DIVIDER -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                    Text(
                        text = "分隔线会在详情页划分文章章节。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 显示新增正文、图片/GIF、链接和分隔线工具栏。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookInsertToolbar(
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onAddLink: () -> Unit,
    onAddDivider: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "插入新内容", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onAddText) { Text(text = "文字") }
                Button(onClick = onAddImage) { Text(text = "图片/GIF") }
                Button(onClick = onAddLink) { Text(text = "链接") }
                Button(onClick = onAddDivider) { Text(text = "分隔线") }
            }
        }
    }
}

/**
 * 显示内置Emoji选择器，选择后追加到当前文字块末尾。
 *
 * @param onDismiss 关闭回调。
 * @param onEmojiSelected 返回选中Emoji的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookEmojiPickerDialog(
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "选择Emoji表情") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.heightIn(max = 360.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                gridItems(NOTEBOOK_EMOJIS) { emoji ->
                    TextButton(onClick = { onEmojiSelected(emoji) }) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        }
    )
}

/**
 * 显示记事本空状态。
 *
 * @param title 标题。
 * @param description 说明。
 * @param actionText 可选操作文字。
 * @param onAction 可选操作回调。
 *
 * @return 无返回值。
 */
@Composable
private fun NotebookEmptyState(
    title: String,
    description: String,
    actionText: String = "",
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "✦", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (actionText.isNotBlank() && onAction != null) {
                    Button(onClick = onAction) { Text(text = actionText) }
                }
            }
        }
    }
}

/**
 * 在查询结果中高亮第一个匹配关键词。
 *
 * @param text 原始文字。
 * @param query 查询词。
 * @param highlightColor 高亮色。
 * @return Compose可直接显示的AnnotatedString。
 */
private fun highlightNotebookText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    val safeQuery = query.trim()
    if (safeQuery.isBlank()) return AnnotatedString(text)
    val start = text.indexOf(safeQuery, ignoreCase = true)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(
            style = SpanStyle(
                color = highlightColor,
                background = highlightColor.copy(alpha = 0.16f),
                fontWeight = FontWeight.Bold
            ),
            start = start,
            end = start + safeQuery.length
        )
    }
}

/**
 * 把文章标签输入拆分、去重并限制数量。
 *
 * @param rawTags 用户输入。
 * @return 最多12个非空标签。
 */
private fun parseNotebookTags(rawTags: String): List<String> {
    return rawTags
        .split(',', '，', '\n')
        .map { tag -> tag.trim().take(20) }
        .filter(String::isNotBlank)
        .distinctBy { tag -> tag.lowercase(Locale.ROOT) }
        .take(12)
}

/**
 * 移动一个文章块并保持其他块相对顺序。
 *
 * @param blocks 当前块列表。
 * @param fromIndex 原索引。
 * @param toIndex 目标索引。
 * @return 移动后的新列表；索引无效时返回原列表副本。
 */
private fun moveNotebookBlock(
    blocks: List<NotebookContentBlock>,
    fromIndex: Int,
    toIndex: Int
): List<NotebookContentBlock> {
    if (fromIndex !in blocks.indices || toIndex !in blocks.indices || fromIndex == toIndex) {
        return blocks.toList()
    }
    return blocks.toMutableList().apply {
        val moved = removeAt(fromIndex)
        add(toIndex, moved)
    }
}

/**
 * 判断编辑块是否包含用户实际输入内容。
 *
 * @param block 内容块。
 * @return 有文字、媒体或链接输入时返回true。
 */
private fun notebookBlockHasUserContent(block: NotebookContentBlock): Boolean {
    return when (block.type) {
        NotebookBlockType.TEXT -> block.text.isNotBlank()
        NotebookBlockType.IMAGE -> block.mediaFileName.isNotBlank()
        NotebookBlockType.LINK -> block.linkTitle.isNotBlank() || block.linkUrl.isNotBlank()
        NotebookBlockType.DIVIDER -> false
    }
}

/**
 * 为链接补充https协议并执行安全校验。
 *
 * @param rawUrl 用户输入地址。
 * @return 只允许http或https且包含主机名的标准地址，否则返回null。
 */
private fun normalizeNotebookLink(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    return runCatching {
        val uri = URI(candidate)
        require(uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true))
        require(!uri.host.isNullOrBlank())
        uri.toASCIIString()
    }.getOrNull()
}

/**
 * 规范文章中的全部链接块。
 *
 * @param blocks 原始块列表。
 * @return 有效链接替换为标准地址后的列表；无效链接保留供上层显示校验错误。
 */
private fun normalizeNotebookLinkBlocks(
    blocks: List<NotebookContentBlock>
): List<NotebookContentBlock> {
    return blocks.map { block ->
        if (block.type == NotebookBlockType.LINK) {
            block.copy(linkUrl = normalizeNotebookLink(block.linkUrl) ?: block.linkUrl.trim())
        } else {
            block
        }
    }
}

/**
 * 使用系统浏览器打开经过校验的文章链接。
 *
 * @param context Android上下文。
 * @param rawUrl 文章链接。
 * @return 成功启动浏览器返回true，否则返回false。
 */
private fun openNotebookLink(context: Context, rawUrl: String): Boolean {
    val url = normalizeNotebookLink(rawUrl) ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    }.onFailure { error ->
        Log.e(NOTEBOOK_UI_TAG, "Failed to open notebook link", error)
    }.getOrDefault(false)
}

/**
 * 格式化毫秒时间用于文章列表和详情。
 *
 * @param timeMillis 时间戳。
 * @return 中国地区年月日和24小时制时间。
 */
private fun formatNotebookTime(timeMillis: Long): String {
    return Instant.ofEpochMilli(timeMillis.coerceAtLeast(0L))
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm", Locale.CHINA))
}

/**
 * 格式化查询日期按钮。
 *
 * @param epochDay 日期序号。
 * @return 月日短文本。
 */
private fun formatNotebookDate(epochDay: Long): String {
    return LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("yyyy/M/d", Locale.CHINA)
    )
}

private val NOTEBOOK_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😊", "🥰", "😍", "🤩", "😘", "😋", "😎", "🥳",
    "🤔", "🤗", "🤭", "🫢", "😴", "🥺", "😭", "😂", "🤣", "😅", "😇", "🙃",
    "👍", "👏", "🙌", "🤝", "💪", "🙏", "❤️", "🧡", "💛", "💚", "💙", "💜",
    "✨", "⭐", "🌈", "🔥", "🎉", "🎁", "🌸", "🌿", "☕", "🍰", "📚", "✍️",
    "💡", "📌", "✅", "⚠️", "🚀", "🏡", "🌍", "📷", "🎵", "💬", "🔗", "📝"
)

private const val NOTEBOOK_UI_TAG = "NotebookScreen"
private const val AUTO_SAVE_DELAY_MILLIS = 900L
private const val MAX_NOTEBOOK_QUERY_LENGTH = 100
private const val MAX_NOTEBOOK_TITLE_LENGTH = 100
private const val MAX_NOTEBOOK_TAG_INPUT_LENGTH = 280
private const val MAX_NOTEBOOK_TEXT_LENGTH = 20_000
private const val MAX_NOTEBOOK_CAPTION_LENGTH = 300
private const val MAX_NOTEBOOK_LINK_TITLE_LENGTH = 200
private const val MAX_NOTEBOOK_LINK_LENGTH = 2_048
