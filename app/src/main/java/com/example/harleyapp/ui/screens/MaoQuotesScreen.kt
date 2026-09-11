package com.example.harleyapp.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.harleyapp.data.MaoQuoteRepository
import com.example.harleyapp.model.MaoQuote
import com.example.harleyapp.model.MaoQuoteContentSource
import com.example.harleyapp.model.MaoQuoteDocument
import com.example.harleyapp.model.MaoQuoteFilter
import com.example.harleyapp.model.MaoQuoteImportResult
import com.example.harleyapp.model.MaoQuoteLoadResult
import com.example.harleyapp.model.MaoQuoteReadingPosition
import com.example.harleyapp.model.filterMaoQuotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面日志标签；日志内容只使用英文，避免把用户导入的正文写入日志。 */
private const val MAO_QUOTES_SCREEN_TAG = "MaoQuotesScreen"

/** 连续输入关键词时等待的毫秒数，减少大文件上无意义的重复全文筛选。 */
private const val MAO_QUOTE_SEARCH_DEBOUNCE_MILLIS = 160L

/**
 * 中共中央党史和文献研究院的毛泽东著作目录页面。
 *
 * 本地址只会在用户主动点击“官方著作目录”后交给系统浏览器；页面不会自动访问、抓取或缓存网页。
 */
private const val MAO_QUOTES_OFFICIAL_CATALOG_URL =
    "https://www.dswxyjy.org.cn/GB/427196/423771/428219/index.html"

/**
 * 显示《毛主席语录》的本地导入、搜索、章节筛选、随机阅读和收藏页面。
 *
 * 使用方法：
 * 功能中心进入本功能时直接调用本函数。页面内部创建[MAO_QUOTES_SCREEN_TAG]对应的本地仓库，首次进入
 * 时异步读取App私有目录。当前Debug版本会读取由用户提供PDF转换得到的离线资产，也允许用户主动
 * 选择UTF-8 TXT覆盖阅读内容；页面不会自动联网。正式生产前仍需为拟随APK分发的内容补齐许可并
 * 完成来源审核，Release仓库门禁会拒绝不完整的内置授权声明。
 *
 * @param onBack 返回功能中心的回调，顶部返回按钮和系统返回键都会调用。
 * @param modifier 外层传入的安全边距和页面布局修饰器。
 * @return 无返回值，直接输出完整的语录阅读页面。
 */
@Composable
fun MaoQuotesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context.applicationContext) {
        MaoQuoteRepository(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    val quoteListState = rememberLazyListState()

    var loadResult by remember { mutableStateOf<MaoQuoteLoadResult?>(null) }
    var favoriteQuoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lastReadQuoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isImporting by remember { mutableStateOf(false) }
    var hasImportedContent by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var randomQuote by remember { mutableStateOf<MaoQuote?>(null) }
    var displayedQuotes by remember { mutableStateOf<List<MaoQuote>>(emptyList()) }
    var isFiltering by remember { mutableStateOf(false) }
    var pendingContinueQuoteId by remember { mutableStateOf<String?>(null) }
    var reloadRevision by rememberSaveable { mutableIntStateOf(0) }

    val currentDocument = (loadResult as? MaoQuoteLoadResult.Success)?.document

    /**
     * 把某一段保存为最后阅读位置。
     *
     * 参数由语录列表或随机阅读弹窗传入；写入SharedPreferences会切换到IO调度器，成功后立即更新页面
     * 标记，失败时保留旧位置并给出提示。
     *
     * @param quote 用户刚刚阅读或明确点击“记到这里”的语录段落。
     * @return 无返回值，保存结果通过[lastReadQuoteId]和[statusMessage]反馈。
     */
    fun rememberReadingPosition(quote: MaoQuote) {
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                repository.saveReadingPosition(MaoQuoteReadingPosition(quoteId = quote.id))
            }
            if (saved) {
                lastReadQuoteId = quote.id
                statusMessage = "已记录当前阅读位置"
            } else {
                statusMessage = "阅读位置保存失败，请稍后重试"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            statusMessage = "正在读取并校验本机TXT…"
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    repository.importFromUri(uri)
                }
                when (result) {
                    is MaoQuoteImportResult.Success -> {
                        val document = result.document
                        loadResult = MaoQuoteLoadResult.Success(
                            document = document,
                            source = MaoQuoteContentSource.IMPORTED
                        )
                        favoriteQuoteIds = withContext(Dispatchers.IO) {
                            repository.getFavoriteIds(document)
                        }
                        lastReadQuoteId = withContext(Dispatchers.IO) {
                            repository.getReadingPosition(document)?.quoteId
                        }
                        hasImportedContent = true
                        selectedChapterId = null
                        searchQuery = ""
                        favoritesOnly = false
                        randomQuote = null
                        statusMessage = "TXT已导入，仅保存在本机App私有目录"
                    }

                    is MaoQuoteImportResult.Failure -> {
                        statusMessage = result.message
                    }
                }
                isImporting = false
            }
        }
    }

    // 首次进入，以及清除本机导入副本后，在IO线程重新读取当前可用内容和轻量阅读状态。
    LaunchedEffect(repository, reloadRevision) {
        isLoading = true
        val result = withContext(Dispatchers.IO) {
            repository.load()
        }
        val document = (result as? MaoQuoteLoadResult.Success)?.document
        loadResult = result
        favoriteQuoteIds = withContext(Dispatchers.IO) {
            repository.getFavoriteIds(document)
        }
        lastReadQuoteId = withContext(Dispatchers.IO) {
            repository.getReadingPosition(document)?.quoteId
        }
        hasImportedContent = withContext(Dispatchers.IO) {
            repository.hasImportedContent()
        }
        isLoading = false
    }

    // 进程恢复后可能保留旧文档的章节id；只在确认新文档不包含该章节时退回“全部”。
    LaunchedEffect(currentDocument?.id, selectedChapterId) {
        val chapterId = selectedChapterId
        if (chapterId != null && currentDocument != null && currentDocument.chapters.none { chapter ->
                chapter.id == chapterId
            }
        ) {
            selectedChapterId = null
        }
    }

    // 关键词变化先做短防抖，再在Default线程完成全文匹配；切换章节和收藏同样复用这一条异步路径，
    // 避免接近2MB上限的自定义TXT在主线程输入期间造成掉帧。
    LaunchedEffect(
        currentDocument?.id,
        searchQuery,
        selectedChapterId,
        favoritesOnly,
        favoriteQuoteIds
    ) {
        val document = currentDocument
        if (document == null) {
            displayedQuotes = emptyList()
            isFiltering = false
            return@LaunchedEffect
        }

        isFiltering = true
        if (searchQuery.isNotBlank()) {
            delay(MAO_QUOTE_SEARCH_DEBOUNCE_MILLIS)
        }
        displayedQuotes = withContext(Dispatchers.Default) {
            filterMaoQuotes(
                document = document,
                filter = MaoQuoteFilter(
                    query = searchQuery,
                    chapterId = selectedChapterId,
                    favoritesOnly = favoritesOnly
                ),
                favoriteQuoteIds = favoriteQuoteIds
            )
        }
        isFiltering = false
    }

    // “继续阅读”会先清除可能隐藏目标的筛选条件；待异步列表更新后，再按当前头部项目数量定位到
    // 稳定语录id。这样不依赖旧索引，用户重新导入其他文档后也不会跳错内容。
    LaunchedEffect(
        pendingContinueQuoteId,
        currentDocument?.id,
        displayedQuotes,
        isFiltering,
        statusMessage
    ) {
        val targetQuoteId = pendingContinueQuoteId ?: return@LaunchedEffect
        val document = currentDocument ?: return@LaunchedEffect
        if (isFiltering || displayedQuotes.size != document.quotes.size) return@LaunchedEffect

        val quoteIndex = displayedQuotes.indexOfFirst { quote -> quote.id == targetQuoteId }
        if (quoteIndex >= 0) {
            val quoteStartIndex = 3 + if (statusMessage == null) 0 else 1
            quoteListState.animateScrollToItem(quoteStartIndex + quoteIndex)
        } else {
            statusMessage = "上次阅读位置已不在当前内容中"
        }
        pendingContinueQuoteId = null
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        MaoQuotesHeader(onBack = onBack)

        if (isLoading || isImporting || isFiltering) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            state = quoteListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                MaoQuotesDevelopmentCard(
                    isBusy = isLoading || isImporting,
                    hasImportedContent = hasImportedContent,
                    onImport = { importLauncher.launch(arrayOf("text/plain")) },
                    onOpenOfficialCatalog = {
                        statusMessage = if (openMaoQuotesOfficialCatalog(context)) {
                            null
                        } else {
                            "未找到可打开官方目录的浏览器"
                        }
                    },
                    onRequestClear = { showClearConfirmation = true }
                )
            }

            statusMessage?.let { message ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            text = message,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            when (val result = loadResult) {
                null -> item {
                    MaoQuotesMessageCard(
                        title = "正在准备",
                        message = "正在读取本机内容与阅读记录…",
                        isError = false
                    )
                }

                is MaoQuoteLoadResult.Empty -> item {
                    MaoQuotesMessageCard(
                        title = "尚未导入内容",
                        message = result.message + "。请选择你有权使用的UTF-8 TXT。",
                        isError = false
                    )
                }

                is MaoQuoteLoadResult.Failure -> item {
                    MaoQuotesMessageCard(
                        title = "内容暂时无法读取",
                        message = result.message,
                        isError = true
                    )
                }

                is MaoQuoteLoadResult.Success -> {
                    item {
                        MaoQuotesDocumentCard(
                            document = result.document,
                            source = result.source
                        )
                    }

                    item {
                        MaoQuotesFilters(
                            document = result.document,
                            searchQuery = searchQuery,
                            selectedChapterId = selectedChapterId,
                            favoritesOnly = favoritesOnly,
                            displayedCount = displayedQuotes.size,
                            isFiltering = isFiltering,
                            canContinueReading = lastReadQuoteId != null,
                            onSearchQueryChanged = { query -> searchQuery = query },
                            onChapterSelected = { chapterId -> selectedChapterId = chapterId },
                            onFavoritesOnlyChanged = { selected -> favoritesOnly = selected },
                            onContinueReading = {
                                val targetQuoteId = lastReadQuoteId
                                if (targetQuoteId != null) {
                                    searchQuery = ""
                                    selectedChapterId = null
                                    favoritesOnly = false
                                    pendingContinueQuoteId = targetQuoteId
                                }
                            },
                            onRandomQuote = {
                                val selectedQuote = displayedQuotes.randomOrNull()
                                if (selectedQuote == null) {
                                    statusMessage = "当前筛选条件下没有可随机阅读的内容"
                                } else {
                                    randomQuote = selectedQuote
                                    rememberReadingPosition(selectedQuote)
                                }
                            }
                        )
                    }

                    if (displayedQuotes.isEmpty()) {
                        item {
                            MaoQuotesMessageCard(
                                title = "没有匹配结果",
                                message = "请更换关键词、章节或关闭“只看收藏”后重试。",
                                isError = false
                            )
                        }
                    } else {
                        items(
                            items = displayedQuotes,
                            key = MaoQuote::id
                        ) { quote ->
                            MaoQuoteListItem(
                                quote = quote,
                                isFavorite = quote.id in favoriteQuoteIds,
                                isLastRead = quote.id == lastReadQuoteId,
                                onRememberReadingPosition = {
                                    rememberReadingPosition(quote)
                                },
                                onToggleFavorite = {
                                    val shouldFavorite = quote.id !in favoriteQuoteIds
                                    coroutineScope.launch {
                                        val saved = withContext(Dispatchers.IO) {
                                            repository.setFavorite(quote.id, shouldFavorite)
                                        }
                                        if (saved) {
                                            favoriteQuoteIds = if (shouldFavorite) {
                                                favoriteQuoteIds + quote.id
                                            } else {
                                                favoriteQuoteIds - quote.id
                                            }
                                            statusMessage = if (shouldFavorite) {
                                                "已加入收藏"
                                            } else {
                                                "已取消收藏"
                                            }
                                        } else {
                                            statusMessage = "收藏状态保存失败，请稍后重试"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    randomQuote?.let { quote ->
        MaoRandomQuoteDialog(
            quote = quote,
            isFavorite = quote.id in favoriteQuoteIds,
            onToggleFavorite = {
                val shouldFavorite = quote.id !in favoriteQuoteIds
                coroutineScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        repository.setFavorite(quote.id, shouldFavorite)
                    }
                    if (saved) {
                        favoriteQuoteIds = if (shouldFavorite) {
                            favoriteQuoteIds + quote.id
                        } else {
                            favoriteQuoteIds - quote.id
                        }
                    } else {
                        statusMessage = "收藏状态保存失败，请稍后重试"
                    }
                }
            },
            onDismiss = { randomQuote = null }
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清除已导入内容？") },
            text = {
                Text(
                    "只会删除App私有目录中的导入副本，不会删除你原来的TXT文件。" +
                        "清除后页面将不再显示这些正文。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        coroutineScope.launch {
                            isLoading = true
                            val cleared = withContext(Dispatchers.IO) {
                                repository.clearImportedContent()
                            }
                            if (cleared) {
                                selectedChapterId = null
                                searchQuery = ""
                                favoritesOnly = false
                                randomQuote = null
                                statusMessage = "已清除App内的导入副本"
                                reloadRevision += 1
                            } else {
                                statusMessage = "导入副本清除失败，请稍后重试"
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 显示语录页面统一标题与返回入口。
 *
 * 使用方法：
 * 放在[MaoQuotesScreen]最上方，保持与其他功能详情页一致的返回交互。
 *
 * @param onBack 返回功能中心的回调。
 * @return 无返回值，直接输出标题栏。
 */
@Composable
private fun MaoQuotesHeader(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ 返回")
            }

            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = "毛主席语录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "本机导入 · 离线阅读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 显示开发阶段、内容许可边界和用户主动操作入口。
 *
 * 使用方法：
 * 页面始终把本卡片放在内容列表之前，明确当前Debug内置内容来自用户提供PDF且尚未作为生产授权
 * 资产。导入与清除都只在用户点击后执行；官方目录也不会自动打开。
 *
 * @param isBusy 页面是否正在读取、导入或清除内容；忙碌期间禁用重复导入。
 * @param hasImportedContent App私有目录当前是否存在用户导入副本，用于决定是否显示清除入口。
 * @param onImport 打开系统TXT选择器的回调。
 * @param onOpenOfficialCatalog 用户主动要求把官方目录交给浏览器的回调。
 * @param onRequestClear 请求显示清除确认弹窗的回调，不能在此处直接删除。
 * @return 无返回值，直接输出状态说明卡片和操作按钮。
 */
@Composable
private fun MaoQuotesDevelopmentCard(
    isBusy: Boolean,
    hasImportedContent: Boolean,
    onImport: () -> Unit,
    onOpenOfficialCatalog: () -> Unit,
    onRequestClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "开发预览 · 已提供离线内容",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "当前Debug版本已加入由你提供PDF提取的中文内容，也可导入本机UTF-8 TXT替换。" +
                    "Release版本不会打包该开发资源，正式生产前仍需补齐内容许可和来源审核。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "TXT可用“# 章节名”分章，并用空行分隔不同段落；文件上限为2MB。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                    onClick = onImport
                ) {
                    Text(if (isBusy) "处理中…" else "导入本机TXT")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenOfficialCatalog
                ) {
                    Text("官方著作目录")
                }
            }

            if (hasImportedContent) {
                TextButton(
                    enabled = !isBusy,
                    onClick = onRequestClear
                ) {
                    Text("清除已导入内容")
                }
            }
        }
    }
}

/**
 * 显示当前导入文档的来源、许可说明和内容规模。
 *
 * 使用方法：
 * 只在仓库返回[MaoQuoteLoadResult.Success]时显示。本卡片仅复述TXT元数据，不会把用户填写的许可字段
 * 当作App已经完成权利审核的证明。
 *
 * @param document 已完成UTF-8、大小和结构校验的本地文档。
 * @param source 仓库确认的实际内容来源类型。
 * @return 无返回值，直接输出文档信息卡片。
 */
@Composable
private fun MaoQuotesDocumentCard(
    document: MaoQuoteDocument,
    source: MaoQuoteContentSource
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = document.metadata.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "内容来源：${source.displayName()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "来源说明：${document.metadata.source.ifBlank { "未填写" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "许可说明：${document.metadata.license.ifBlank { "未填写" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${document.chapters.size}个章节 · ${document.quotes.size}则内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示关键词、章节、收藏筛选和随机阅读控制区。
 *
 * 使用方法：
 * 父页面传入当前状态，控件只通过回调申请变更，不直接访问仓库。随机按钮由父页面在全部筛选条件
 * 生效后的结果集中选取内容，避免随机结果与用户当前筛选不一致。
 *
 * @param document 当前成功加载的文档，用于生成章节筛选项。
 * @param searchQuery 当前关键词。
 * @param selectedChapterId 当前章节id；null表示全部章节。
 * @param favoritesOnly 是否只显示收藏。
 * @param displayedCount 当前组合条件命中的段落数量。
 * @param isFiltering 是否正在后台计算新的筛选结果。
 * @param canContinueReading 是否存在仍可尝试定位的上次阅读位置。
 * @param onSearchQueryChanged 关键词变更回调。
 * @param onChapterSelected 章节选择回调，null表示选择全部。
 * @param onFavoritesOnlyChanged 收藏筛选变更回调。
 * @param onContinueReading 清除筛选后定位到上次阅读语录的回调。
 * @param onRandomQuote 从当前结果中随机选择一则的回调。
 * @return 无返回值，直接输出筛选控制区。
 */
@Composable
private fun MaoQuotesFilters(
    document: MaoQuoteDocument,
    searchQuery: String,
    selectedChapterId: String?,
    favoritesOnly: Boolean,
    displayedCount: Int,
    isFiltering: Boolean,
    canContinueReading: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onChapterSelected: (String?) -> Unit,
    onFavoritesOnlyChanged: (Boolean) -> Unit,
    onContinueReading: () -> Unit,
    onRandomQuote: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            singleLine = true,
            label = { Text("关键词搜索") },
            supportingText = { Text("匹配正文或章节名称") }
        )

        Text(
            text = "章节筛选",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedChapterId == null,
                    onClick = { onChapterSelected(null) },
                    label = { Text("全部") }
                )
            }
            items(
                items = document.chapters,
                key = { chapter -> chapter.id }
            ) { chapter ->
                FilterChip(
                    selected = selectedChapterId == chapter.id,
                    onClick = { onChapterSelected(chapter.id) },
                    label = { Text(chapter.title) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { onFavoritesOnlyChanged(!favoritesOnly) },
                label = { Text("只看收藏") }
            )
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = displayedCount > 0 && !isFiltering,
                onClick = onRandomQuote
            ) {
                Text("随机一则")
            }
            Text(
                text = if (isFiltering) "正在筛选…" else "共${displayedCount}则",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (canContinueReading) {
            TextButton(
                enabled = !isFiltering,
                onClick = onContinueReading
            ) {
                Text("继续上次阅读")
            }
        }
    }
}

/**
 * 显示阅读列表中的单段正文、章节位置、收藏按钮和阅读位置按钮。
 *
 * 使用方法：
 * 由LazyColumn按筛选后的原始顺序逐条调用。点击卡片或“记到这里”只保存稳定id，不会修改正文；收藏
 * 按钮请求父页面持久化收藏状态。
 *
 * @param quote 当前需要显示的语录段落。
 * @param isFavorite 当前段落是否已收藏。
 * @param isLastRead 当前段落是否为上次记录的阅读位置。
 * @param onRememberReadingPosition 保存当前阅读位置的回调。
 * @param onToggleFavorite 切换当前收藏状态的回调。
 * @return 无返回值，直接输出一张正文卡片。
 */
@Composable
private fun MaoQuoteListItem(
    quote: MaoQuote,
    isFavorite: Boolean,
    isLastRead: Boolean,
    onRememberReadingPosition: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRememberReadingPosition),
        colors = CardDefaults.cardColors(
            containerColor = if (isLastRead) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "${quote.chapterTitle} · 第${quote.indexInChapter + 1}则",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isLastRead) {
                    Text(
                        text = "上次读到这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SelectionContainer {
                Text(
                    text = quote.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRememberReadingPosition) {
                    Text("记到这里")
                }
                TextButton(onClick = onToggleFavorite) {
                    Text(if (isFavorite) "★ 已收藏" else "☆ 收藏")
                }
            }
        }
    }
}

/**
 * 在用户点击随机阅读后显示单段内容。
 *
 * 使用方法：
 * 父页面从当前筛选结果中选出一段后调用。弹窗只展示已导入的本机内容，并提供收藏切换；点击关闭或
 * 弹窗外部区域都会返回列表。
 *
 * @param quote 随机选中的语录段落。
 * @param isFavorite 当前段落是否已收藏。
 * @param onToggleFavorite 切换收藏状态的回调。
 * @param onDismiss 关闭随机阅读弹窗的回调。
 * @return 无返回值，直接输出Material对话框。
 */
@Composable
private fun MaoRandomQuoteDialog(
    quote: MaoQuote,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("随机一则 · ${quote.chapterTitle}") },
        text = {
            SelectionContainer {
                Text(
                    text = quote.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        dismissButton = {
            TextButton(onClick = onToggleFavorite) {
                Text(if (isFavorite) "★ 已收藏" else "☆ 收藏")
            }
        }
    )
}

/**
 * 显示空内容、加载中或失败状态的统一提示卡片。
 *
 * 使用方法：
 * 页面没有可渲染正文时传入简短标题和完整处理建议；失败状态会使用错误容器色，普通说明使用
 * surfaceVariant，确保提示层级明确但不会伪装成正文。
 *
 * @param title 提示标题。
 * @param message 面向用户的完整说明或恢复建议。
 * @param isError true表示读取失败，false表示普通空态或加载态。
 * @return 无返回值，直接输出状态卡片。
 */
@Composable
private fun MaoQuotesMessageCard(
    title: String,
    message: String,
    isError: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 把仓库内容来源转换为页面可读说明。
 *
 * 使用方法：
 * 文档信息卡直接对[MaoQuoteContentSource]调用；开发资产必须明确标注不可作为生产许可证明。
 *
 * @receiver 仓库返回的内容来源枚举。
 * @return 对应的简短中文来源说明。
 */
private fun MaoQuoteContentSource.displayName(): String {
    return when (this) {
        MaoQuoteContentSource.IMPORTED -> "用户本机导入"
        MaoQuoteContentSource.BUNDLED_AUTHORIZED -> "已声明再分发授权的内置内容"
        MaoQuoteContentSource.BUNDLED_DEVELOPMENT -> "仅限开发验证的内置内容"
    }
}

/**
 * 在用户明确点击后把官方著作目录交给系统浏览器。
 *
 * 使用方法：
 * 仅由“官方著作目录”按钮调用。函数不会在后台请求页面，也不会读取、抓取或缓存响应；系统没有可用
 * 浏览器或启动失败时只记录英文错误信息并返回false。
 *
 * @param context 当前页面Context，用于解析并启动ACTION_VIEW Intent。
 * @return 成功把Intent交给系统时返回true，否则返回false。
 */
private fun openMaoQuotesOfficialCatalog(context: Context): Boolean {
    val intent = Intent(
        Intent.ACTION_VIEW,
        MAO_QUOTES_OFFICIAL_CATALOG_URL.toUri()
    )
    if (intent.resolveActivity(context.packageManager) == null) {
        Log.w(MAO_QUOTES_SCREEN_TAG, "No browser can open the official catalog")
        return false
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse { error ->
        Log.e(MAO_QUOTES_SCREEN_TAG, "Failed to open the official catalog", error)
        false
    }
}
