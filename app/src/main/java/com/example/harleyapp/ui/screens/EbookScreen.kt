package com.example.harleyapp.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import com.example.harleyapp.data.EbookRepository
import com.example.harleyapp.data.EbookNoteRepository
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookChapter
import com.example.harleyapp.model.EbookFontFamily
import com.example.harleyapp.model.EbookFormat
import com.example.harleyapp.model.EbookNote
import com.example.harleyapp.model.EbookReadingBackground
import com.example.harleyapp.model.EbookReadingMode
import com.example.harleyapp.model.EbookTranslationDirection
import com.example.harleyapp.model.EbookTranslationDisplayMode
import com.example.harleyapp.system.EbookOfflineTranslator
import com.example.harleyapp.system.EbookReadAloudController
import com.example.harleyapp.system.EbookReadAloudState
import com.example.harleyapp.system.EbookTtsVoiceOption
import com.example.harleyapp.system.EbookTranslationStage
import com.example.harleyapp.system.detectEbookLanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.absoluteValue

/** 电子书根页面的分页书架和全部书籍两个分栏。 */
private enum class EbookLibraryTab(val displayName: String) {
    SHELF("我的书架"),
    ALL("全部书籍")
}

/**
 * 用户从阅读正文长按选择后、尚未保存的摘录草稿。
 *
 * @param excerpt 已选择的正文。
 * @param pageIndex 来源零基页码。
 * @param chapterTitle 当前章节标题，可为空。
 */
private data class PendingEbookNoteDraft(
    val excerpt: String,
    val pageIndex: Int,
    val chapterTitle: String
)

/**
 * 电子书书库、导入管理与内置阅读器页面。
 *
 * 使用方法：
 * 由功能中心在电子书详情被选中时调用。用户通过系统文件选择器导入书籍，页面负责搜索、打开、
 * 修改书名作者和删除；打开书籍后可切换仿真翻书、左右、上下、淡入四种翻页方式，并保存
 * 跳页、字号与阅读背景进度。
 *
 * @param repository 电子书本地仓库。
 * @param initialBookId 全局搜索要求直接打开的书籍id；为空表示显示书架。
 * @param onInitialBookConsumed 初始书籍成功定位或确认不存在后的消费回调。
 * @param onImmersiveChanged 阅读器是否处于无干扰模式的回调，用于联动隐藏App外层底部导航。
 * @param onBack 从书架返回功能中心的回调。
 * @param modifier 外部安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出书架或阅读器。
 */
@Composable
fun EbookScreen(
    repository: EbookRepository,
    initialBookId: String?,
    onInitialBookConsumed: () -> Unit,
    onImmersiveChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var books by remember(repository) { mutableStateOf(repository.getBooks()) }
    var selectedBookId by rememberSaveable { mutableStateOf("") }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var isPreparingLibrary by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            coroutineScope.launch {
                val result = repository.importFromUri(uri)
                books = repository.getBooks()
                statusMessage = result.message
                isImporting = false
            }
        }
    }

    // 公版书只在第一次进入新版电子书功能时复制一次，避免阻塞App启动或在用户删除后反复恢复。
    LaunchedEffect(repository) {
        val installed = repository.ensureStarterBooksInstalled()
        books = repository.getBooks()
        statusMessage = if (installed) "已准备14本免费公版书，可在“全部书籍”管理" else "内置书准备失败，仍可导入本机书籍"
        isPreparingLibrary = false
    }

    LaunchedEffect(initialBookId, books) {
        val targetId = initialBookId.orEmpty()
        if (targetId.isNotBlank()) {
            if (books.any { book -> book.id == targetId }) {
                selectedBookId = targetId
            }
            onInitialBookConsumed()
        }
    }

    val selectedBook = books.firstOrNull { book -> book.id == selectedBookId }
    LaunchedEffect(selectedBook?.id) {
        if (selectedBook == null) {
            onImmersiveChanged(false)
        }
    }
    BackHandler(enabled = selectedBook != null) {
        selectedBookId = ""
        books = repository.getBooks()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (selectedBook == null) {
            EbookLibrary(
                books = books,
                isImporting = isImporting || isPreparingLibrary,
                statusMessage = statusMessage,
                onBack = onBack,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onOpenBook = { book -> selectedBookId = book.id },
                onSetOnShelf = { book, isOnShelf ->
                    val saved = repository.setOnShelf(book.id, isOnShelf)
                    statusMessage = if (saved) {
                        if (isOnShelf) "《${book.title}》已加入书架" else "《${book.title}》已移出书架"
                    } else {
                        "书架设置保存失败"
                    }
                    if (saved) books = repository.getBooks()
                    saved
                },
                onReorderShelf = { orderedBookIds ->
                    val saved = repository.reorderShelfBooks(orderedBookIds)
                    statusMessage = if (saved) "书架顺序已保存" else "书架顺序保存失败"
                    if (saved) books = repository.getBooks()
                    saved
                },
                onUpdateMetadata = { bookId, title, author, spineColorArgb ->
                    val saved = repository.updateMetadata(
                        bookId = bookId,
                        title = title,
                        author = author,
                        spineColorArgb = spineColorArgb
                    )
                    statusMessage = if (saved) "书籍信息已更新" else "书籍信息保存失败"
                    if (saved) books = repository.getBooks()
                    saved
                },
                onDeleteBook = { book ->
                    val deleted = repository.deleteBook(book.id)
                    statusMessage = if (deleted) "《${book.title}》已删除" else "书籍删除失败"
                    if (deleted) books = repository.getBooks()
                    deleted
                }
            )
        } else {
            EbookReader(
                book = selectedBook,
                repository = repository,
                onImmersiveChanged = onImmersiveChanged,
                onBack = {
                    selectedBookId = ""
                    books = repository.getBooks()
                }
            )
        }
    }
}

/**
 * 显示可搜索并具备增删改查闭环的电子书架。
 *
 * @param books 当前全部书籍。
 * @param isImporting 是否正在复制和解析新书。
 * @param statusMessage 最近一次操作反馈。
 * @param onBack 返回功能中心回调。
 * @param onImport 打开系统文件选择器回调。
 * @param onOpenBook 打开阅读器回调。
 * @param onSetOnShelf 把书籍加入或移出分页书架的回调。
 * @param onReorderShelf 保存书架拖动顺序的回调。
 * @param onUpdateMetadata 保存书名、作者和书脊颜色回调。
 * @param onDeleteBook 删除整本书回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EbookLibrary(
    books: List<EbookBook>,
    isImporting: Boolean,
    statusMessage: String,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpenBook: (EbookBook) -> Unit,
    onSetOnShelf: (EbookBook, Boolean) -> Boolean,
    onReorderShelf: (List<String>) -> Boolean,
    onUpdateMetadata: (String, String, String, Int) -> Boolean,
    onDeleteBook: (EbookBook) -> Boolean
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTabName by rememberSaveable { mutableStateOf(EbookLibraryTab.SHELF.name) }
    var editingBook by remember { mutableStateOf<EbookBook?>(null) }
    var deletingBook by remember { mutableStateOf<EbookBook?>(null) }
    val selectedTab = EbookLibraryTab.entries.firstOrNull { tab ->
        tab.name == selectedTabName
    } ?: EbookLibraryTab.SHELF
    val normalizedQuery = query.trim().lowercase(Locale.CHINA)
    val visibleBooks = remember(books, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            books.sortedByDescending(EbookBook::updatedAtMillis)
        } else {
            books.filter { book ->
                normalizedQuery in book.title.lowercase(Locale.CHINA) ||
                    normalizedQuery in book.author.lowercase(Locale.CHINA) ||
                    normalizedQuery in book.originalFileName.lowercase(Locale.CHINA) ||
                    normalizedQuery in book.format.displayName.lowercase(Locale.CHINA) ||
                    normalizedQuery in book.category.lowercase(Locale.CHINA)
            }.sortedByDescending(EbookBook::updatedAtMillis)
        }
    }
    val shelfBooks = remember(books) {
        books.filter(EbookBook::isOnShelf).sortedBy(EbookBook::shelfOrder)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回功能中心") }
                Spacer(modifier = Modifier.weight(1f))
                Button(enabled = !isImporting, onClick = onImport) {
                    Text(if (isImporting) "正在导入…" else "＋ 导入书籍")
                }
            }
            Text(
                text = "电子书",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "14本公版书与本机导入统一管理；支持PDF、EPUB、TXT、Markdown、HTML、DOCX、FB2与RTF。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
            if (statusMessage.isNotBlank()) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EbookLibraryTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTabName = tab.name },
                        label = {
                            Text(
                                if (tab == EbookLibraryTab.SHELF) {
                                    "${tab.displayName} ${shelfBooks.size}"
                                } else {
                                    "${tab.displayName} ${books.size}"
                                }
                            )
                        }
                    )
                }
            }
        }

        if (selectedTab == EbookLibraryTab.SHELF) {
            item {
                EbookShelfPager(
                    books = shelfBooks,
                    onOpenBook = onOpenBook,
                    onReorderBooks = onReorderShelf,
                    onManageShelf = { selectedTabName = EbookLibraryTab.ALL.name }
                )
            }
        } else {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { value -> query = value.take(MAX_BOOK_QUERY_LENGTH) },
                    label = { Text("模糊搜索书名、作者、类别、格式或文件名") },
                    singleLine = true
                )
            }

            if (visibleBooks.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = if (books.isEmpty()) "书库还是空的" else "没有找到匹配书籍",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (books.isEmpty()) {
                                    "点击“导入书籍”，从手机文件中选择一本书。"
                                } else {
                                    "可以缩短关键词；搜索会匹配部分文字，不要求完整书名。"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(visibleBooks, key = EbookBook::id) { book ->
                    EbookBookCard(
                        book = book,
                        onOpen = { onOpenBook(book) },
                        onSetOnShelf = { isOnShelf -> onSetOnShelf(book, isOnShelf) },
                        onEdit = { editingBook = book },
                        onDelete = { deletingBook = book }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    editingBook?.let { book ->
        EbookMetadataDialog(
            book = book,
            onDismiss = { editingBook = null },
            onSave = { title, author, spineColorArgb ->
                if (onUpdateMetadata(book.id, title, author, spineColorArgb)) editingBook = null
            }
        )
    }

    deletingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { deletingBook = null },
            title = { Text("删除电子书") },
            text = { Text("将永久删除《${book.title}》的原文件、阅读索引和进度，无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onDeleteBook(book)) deletingBook = null
                    }
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingBook = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 把用户选择的书按每页三十本展示为可横向切换、可长按排序的多层书架。
 *
 * 使用方法：
 * 由[EbookLibrary]的“我的书架”分栏调用；用户右滑即可进入下一架，点击封面直接继续阅读。
 *
 * @param books 已按用户加入顺序排列的书籍。
 * @param onOpenBook 点击封面后的阅读回调。
 * @param onReorderBooks 一次长按拖动结束后保存全部书架顺序的回调。
 * @param onManageShelf 跳转“全部书籍”管理加入与移出状态的回调。
 * @return 无返回值。
 */
@Composable
private fun EbookShelfPager(
    books: List<EbookBook>,
    onOpenBook: (EbookBook) -> Unit,
    onReorderBooks: (List<String>) -> Boolean,
    onManageShelf: () -> Unit
) {
    var orderedBooks by remember(books) { mutableStateOf(books) }
    var draggingBookId by remember { mutableStateOf<String?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    val shelfPages = remember(orderedBooks) { buildEbookShelfPages(orderedBooks) }
    if (shelfPages.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("书架还没有放书", fontWeight = FontWeight.Bold)
                Text(
                    "书籍仍保存在“全部书籍”中，可自由选择哪些展示在书架。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onManageShelf) { Text("去选择书籍") }
            }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { shelfPages.size })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp),
            state = pagerState,
            userScrollEnabled = draggingBookId == null,
            beyondViewportPageCount = 1
        ) { pageIndex ->
            val pageOffset = (
                (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                ).absoluteValue
            EbookShelfPage(
                modifier = Modifier.graphicsLayer {
                    val scale = (1f - pageOffset * 0.04f).coerceIn(0.94f, 1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = (1f - pageOffset * 0.18f).coerceIn(0.72f, 1f)
                },
                books = shelfPages[pageIndex],
                shelfNumber = pageIndex + 1,
                draggingBookId = draggingBookId,
                onOpenBook = onOpenBook,
                onDragStarted = { bookId ->
                    draggingBookId = bookId
                    dragTargetIndex = orderedBooks.indexOfFirst { book -> book.id == bookId }
                        .takeIf { index -> index >= 0 }
                },
                onMoveBookBy = { bookId, delta ->
                    if (draggingBookId == bookId) {
                        val currentTarget = dragTargetIndex
                            ?: orderedBooks.indexOfFirst { book -> book.id == bookId }
                        dragTargetIndex = (currentTarget + delta).coerceIn(0, orderedBooks.lastIndex)
                    }
                },
                onDragFinished = {
                    val movedBookId = draggingBookId
                    val fromIndex = orderedBooks.indexOfFirst { book -> book.id == movedBookId }
                    val targetIndex = dragTargetIndex
                    if (fromIndex >= 0 && targetIndex != null && targetIndex != fromIndex) {
                        orderedBooks = moveEbookShelfBook(
                            books = orderedBooks,
                            bookId = movedBookId.orEmpty(),
                            targetIndex = targetIndex
                        )
                    }
                    draggingBookId = null
                    dragTargetIndex = null
                    if (!onReorderBooks(orderedBooks.map(EbookBook::id))) {
                        orderedBooks = books
                    }
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            shelfPages.indices.forEach { index ->
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == pagerState.currentPage) 18.dp else 7.dp),
                    shape = RoundedCornerShape(99.dp),
                    color = if (index == pagerState.currentPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ) {}
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = when {
                    draggingBookId != null -> {
                        "正在移动到第${(dragTargetIndex ?: 0) + 1}位 · 松手保存"
                    }
                    shelfPages.size > 1 -> "左右滑动切换书架 · 长按书脊拖动排序"
                    else -> "长按书脊拖动排序 · 当前书架还可继续放书"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onManageShelf) { Text("管理书架") }
        }
    }
}

/** @return 具有三层木质背板和密集竖排书脊的一页真实书架。 */
@Composable
private fun EbookShelfPage(
    books: List<EbookBook>,
    shelfNumber: Int,
    draggingBookId: String?,
    onOpenBook: (EbookBook) -> Unit,
    onDragStarted: (String) -> Unit,
    onMoveBookBy: (String, Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A291C)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF7A4B2E), Color(0xFF3C241A), Color(0xFF62402A))
                    )
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 轻微横向木纹让书架更接近实体木板，而不是普通主题色卡片。
                repeat(18) { index ->
                    val y = size.height * index / 18f
                    drawLine(
                        color = Color.White.copy(
                            alpha = if (index % 3 == 0) 0.055f else 0.025f
                        ),
                        start = Offset(0f, y),
                        end = Offset(size.width, y + (index % 2) * 3f),
                        strokeWidth = if (index % 4 == 0) 2f else 1f
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "HALIBADUO · 第${shelfNumber}书架",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFFE2B8),
                    fontWeight = FontWeight.Bold
                )
                repeat(SHELF_ROWS_PER_PAGE) { rowIndex ->
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        val spineWidth = (maxWidth - 18.dp) / BOOKS_PER_SHELF_ROW
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            repeat(BOOKS_PER_SHELF_ROW) { columnIndex ->
                                val book = books.getOrNull(
                                    rowIndex * BOOKS_PER_SHELF_ROW + columnIndex
                                )
                                if (book != null) {
                                    EbookShelfBook(
                                        modifier = Modifier.width(spineWidth),
                                        book = book,
                                        isDragging = draggingBookId == book.id,
                                        onOpen = { onOpenBook(book) },
                                        onDragStarted = { onDragStarted(book.id) },
                                        onMoveBy = { delta -> onMoveBookBy(book.id, delta) },
                                        onDragFinished = onDragFinished
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(spineWidth))
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(13.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFA87548),
                                        Color(0xFF5B331F),
                                        Color(0xFF2B160F)
                                    )
                                ),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}

/**
 * 显示一册可点击、可长按拖动且带圆形阅读进度的真实窄书脊。
 *
 * 使用方法：
 * 短按调用[onOpen]继续阅读；长按触发震动后，横向拖过一个书脊宽度会移动一个位置，纵向拖动
 * 会跨一层书架移动十个位置。拖动期间由父级禁用书架翻页，松手通过[onDragFinished]统一保存。
 *
 * @param book 当前书籍及其阅读进度。
 * @param isDragging 当前书脊是否正在被用户拖动。
 * @param onOpen 短按打开书籍的回调。
 * @param onDragStarted 长按开始拖动的回调。
 * @param onMoveBy 相对移动位置回调；正数向后，负数向前。
 * @param onDragFinished 松手或手势取消后的保存回调。
 * @param modifier 外部传入的书脊宽度修饰器。
 * @return 无返回值，直接绘制书脊。
 */
@Composable
private fun EbookShelfBook(
    book: EbookBook,
    isDragging: Boolean,
    onOpen: () -> Unit,
    onDragStarted: () -> Unit,
    onMoveBy: (Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val horizontalThreshold = with(density) { BOOK_DRAG_HORIZONTAL_THRESHOLD.toPx() }
    val verticalThreshold = with(density) { BOOK_DRAG_VERTICAL_THRESHOLD.toPx() }
    val currentOnMoveBy by rememberUpdatedState(onMoveBy)
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    var dragOffset by remember(book.id) { mutableStateOf(Offset.Zero) }
    var dragStepAccumulator by remember(book.id) { mutableStateOf(Offset.Zero) }
    val stableHash = book.id.hashCode().let { value ->
        if (value == Int.MIN_VALUE) 0 else kotlin.math.abs(value)
    }
    val spineColor = resolveEbookSpineColor(book)
    val heightFraction = 0.78f + (stableHash % 15) / 100f
    val verticalTitle = book.title.take(MAX_SPINE_TITLE_CHARACTERS)
        .toCharArray()
        .joinToString(separator = "\n")
    val readingProgress = if (book.lastReadAtMillis > 0L) {
        (book.currentPage + 1f) / book.pageCount.coerceAtLeast(1)
    } else {
        0f
    }.coerceIn(0f, 1f)
    Card(
        modifier = modifier
            .fillMaxHeight(heightFraction)
            .zIndex(if (isDragging) 2f else 0f)
            .graphicsLayer {
                rotationZ = if (isDragging) 0f else ((stableHash % 5) - 2) * 0.35f
                translationX = dragOffset.x
                translationY = dragOffset.y
                scaleX = if (isDragging) 1.08f else 1f
                scaleY = if (isDragging) 1.04f else 1f
                shadowElevation = if (isDragging) 18f else 0f
            }
            .clickable(onClick = onOpen)
            .pointerInput(book.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragOffset = Offset.Zero
                        dragStepAccumulator = Offset.Zero
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnDragStarted()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        var nextAccumulator = dragStepAccumulator + dragAmount
                        if (
                            nextAccumulator.y.absoluteValue >= verticalThreshold &&
                            nextAccumulator.y.absoluteValue > nextAccumulator.x.absoluteValue
                        ) {
                            val direction = if (nextAccumulator.y > 0f) 1 else -1
                            currentOnMoveBy(direction * BOOKS_PER_SHELF_ROW)
                            nextAccumulator = Offset(
                                nextAccumulator.x,
                                nextAccumulator.y - direction * verticalThreshold
                            )
                        } else if (nextAccumulator.x.absoluteValue >= horizontalThreshold) {
                            val direction = if (nextAccumulator.x > 0f) 1 else -1
                            currentOnMoveBy(direction)
                            nextAccumulator = Offset(
                                nextAccumulator.x - direction * horizontalThreshold,
                                nextAccumulator.y
                            )
                        }
                        dragStepAccumulator = nextAccumulator
                    },
                    onDragEnd = {
                        dragOffset = Offset.Zero
                        dragStepAccumulator = Offset.Zero
                        currentOnDragFinished()
                    },
                    onDragCancel = {
                        dragOffset = Offset.Zero
                        dragStepAccumulator = Offset.Zero
                        currentOnDragFinished()
                    }
                )
            },
        shape = RoundedCornerShape(topStart = 3.dp, topEnd = 5.dp),
        colors = CardDefaults.cardColors(containerColor = spineColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp, vertical = 5.dp)
            ) {
                Text(
                    modifier = Modifier.align(Alignment.TopCenter),
                    text = verticalTitle,
                    color = Color(0xFFFFF5DF),
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = MAX_SPINE_TITLE_CHARACTERS,
                    overflow = TextOverflow.Clip
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(18.dp),
                    progress = { readingProgress },
                    color = Color(0xFFFFD88A),
                    trackColor = Color.White.copy(alpha = 0.16f),
                    strokeWidth = 1.5.dp
                )
            }
        }
    }
}

/**
 * 按书架容量生成稳定分页。
 *
 * @param books 已按书架顺序排序的书籍。
 * @return 每页最多三十本的列表；空书架返回空列表。
 */
internal fun buildEbookShelfPages(books: List<EbookBook>): List<List<EbookBook>> {
    return books.chunked(BOOKS_PER_SHELF_PAGE)
}

/**
 * 把指定书籍移动到目标书架位置，供拖动手势和单元测试共同使用。
 *
 * @param books 当前完整书架顺序。
 * @param bookId 需要移动的书籍id。
 * @param targetIndex 目标零基位置，超出范围时自动限制。
 * @return 新顺序；找不到书籍或无需移动时返回原列表。
 */
internal fun moveEbookShelfBook(
    books: List<EbookBook>,
    bookId: String,
    targetIndex: Int
): List<EbookBook> {
    val fromIndex = books.indexOfFirst { book -> book.id == bookId }
    if (fromIndex < 0 || books.isEmpty()) return books
    val safeTarget = targetIndex.coerceIn(0, books.lastIndex)
    if (safeTarget == fromIndex) return books
    return books.toMutableList().apply {
        add(safeTarget, removeAt(fromIndex))
    }
}

/** @return 一张书籍信息、阅读进度和管理操作卡片。 */
@Composable
private fun EbookBookCard(
    book: EbookBook,
    onOpen: () -> Unit,
    onSetOnShelf: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(width = 56.dp, height = 72.dp),
                    color = resolveEbookSpineColor(book),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = book.format.displayName.take(4),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFFFF5DF)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.author.ifBlank { "作者未填写" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${book.format.displayName} · ${formatEbookSize(book.fileSizeBytes)} · " +
                            "第${book.currentPage + 1}/${book.pageCount}页",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${book.category} · ${if (book.isBundled) "内置公版书" else "用户导入"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpen) { Text("继续阅读") }
                OutlinedButton(onClick = { onSetOnShelf(!book.isOnShelf) }) {
                    Text(if (book.isOnShelf) "移出书架" else "加入书架")
                }
                OutlinedButton(onClick = onEdit) { Text("信息与书脊颜色") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 修改书名、作者，并通过预设或RGB滑杆自定义书架书脊颜色。
 *
 * 使用方法：
 * 从“全部书籍”点击“信息与书脊颜色”打开。选择“自动”会恢复按书籍id配色；选择预设或拖动
 * 任一RGB滑杆会生成不透明自定义颜色。点击保存后由仓库与其他元数据一次原子写入。
 *
 * @param book 当前书籍及已保存颜色。
 * @param onDismiss 放弃修改的回调。
 * @param onSave 返回新书名、作者和ARGB颜色的保存回调；颜色0表示自动。
 * @return 无返回值，直接显示可滚动编辑对话框。
 */
@Composable
private fun EbookMetadataDialog(
    book: EbookBook,
    onDismiss: () -> Unit,
    onSave: (String, String, Int) -> Unit
) {
    var title by rememberSaveable(book.id) { mutableStateOf(book.title) }
    var author by rememberSaveable(book.id) { mutableStateOf(book.author) }
    val initialColor = resolveEbookSpineColor(book)
    var selectedSpineColorArgb by rememberSaveable(book.id) {
        mutableIntStateOf(book.spineColorArgb)
    }
    var customRed by rememberSaveable(book.id) {
        mutableIntStateOf((initialColor.red * 255f).toInt().coerceIn(0, 255))
    }
    var customGreen by rememberSaveable(book.id) {
        mutableIntStateOf((initialColor.green * 255f).toInt().coerceIn(0, 255))
    }
    var customBlue by rememberSaveable(book.id) {
        mutableIntStateOf((initialColor.blue * 255f).toInt().coerceIn(0, 255))
    }
    val previewColor = if (selectedSpineColorArgb == 0) {
        initialColor
    } else {
        Color(selectedSpineColorArgb)
    }

    /** 使用当前RGB滑杆值生成完整不透明ARGB并切换到自定义模式。 */
    fun applyCustomRgb() {
        selectedSpineColorArgb = createOpaqueArgb(customRed, customGreen, customBlue)
    }

    /** 选择预设颜色，并同步滑杆数值以便用户继续微调。 */
    fun selectPreset(argb: Int) {
        selectedSpineColorArgb = argb
        val color = if (argb == 0) initialColor else Color(argb)
        customRed = (color.red * 255f).toInt().coerceIn(0, 255)
        customGreen = (color.green * 255f).toInt().coerceIn(0, 255)
        customBlue = (color.blue * 255f).toInt().coerceIn(0, 255)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书籍信息与书脊") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { value -> title = value.take(120) },
                    label = { Text("书名") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { value -> author = value.take(80) },
                    label = { Text("作者（可不填）") },
                    singleLine = true
                )

                Text("书脊主题色", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EBOOK_SPINE_COLOR_PRESETS.forEach { preset ->
                        val presetColor = if (preset.argb == 0) initialColor else Color(preset.argb)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable { selectPreset(preset.argb) },
                                color = presetColor,
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (selectedSpineColorArgb == preset.argb) 3.dp else 1.dp,
                                    color = if (selectedSpineColorArgb == preset.argb) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                )
                            ) {
                                if (preset.argb == 0) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("A", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(preset.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(width = 48.dp, height = 72.dp),
                        color = previewColor,
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 7.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = book.title.take(2).toCharArray().joinToString("\n"),
                                color = Color(0xFFFFF5DF),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (selectedSpineColorArgb == 0) {
                                "自动配色"
                            } else {
                                "#%06X".format(selectedSpineColorArgb and 0xFFFFFF)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "拖动RGB滑杆可调出任意颜色",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                EbookRgbSlider("红", customRed, Color(0xFFD84A4A)) { value ->
                    customRed = value
                    applyCustomRgb()
                }
                EbookRgbSlider("绿", customGreen, Color(0xFF43A064)) { value ->
                    customGreen = value
                    applyCustomRgb()
                }
                EbookRgbSlider("蓝", customBlue, Color(0xFF477BD1)) { value ->
                    customBlue = value
                    applyCustomRgb()
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onSave(title, author, selectedSpineColorArgb) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 显示单个RGB颜色通道滑杆。
 *
 * 使用方法：
 * 书脊颜色编辑器分别创建红、绿、蓝三个滑杆；拖动时通过[onValueChanged]返回0至255整数。
 *
 * @param label 通道中文名称。
 * @param value 当前通道数值。
 * @param activeColor 滑杆激活颜色。
 * @param onValueChanged 数值变化回调。
 * @return 无返回值，直接显示带数值的滑杆行。
 */
@Composable
private fun EbookRgbSlider(
    label: String,
    value: Int,
    activeColor: Color,
    onValueChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label ${value.toString().padStart(3, '0')}", modifier = Modifier.width(66.dp))
        Slider(
            modifier = Modifier.weight(1f),
            value = value.toFloat(),
            onValueChange = { rawValue ->
                onValueChanged(rawValue.toInt().coerceIn(0, 255))
            },
            valueRange = 0f..255f,
            steps = 254,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = activeColor,
                activeTrackColor = activeColor
            )
        )
    }
}

/**
 * 根据书籍自定义值或稳定id计算实际书脊颜色。
 *
 * @param book 当前书籍。
 * @return 用户已选择颜色时返回该颜色，否则在默认主题色中稳定分配一种。
 */
private fun resolveEbookSpineColor(book: EbookBook): Color {
    if (book.spineColorArgb != 0) return Color(book.spineColorArgb)
    val stableHash = book.id.hashCode().let { value ->
        if (value == Int.MIN_VALUE) 0 else kotlin.math.abs(value)
    }
    return DEFAULT_EBOOK_SPINE_COLORS[stableHash % DEFAULT_EBOOK_SPINE_COLORS.size]
}

/**
 * 把RGB通道组合为不透明ARGB整数。
 *
 * @param red 红色通道，超出范围时自动限制。
 * @param green 绿色通道，超出范围时自动限制。
 * @param blue 蓝色通道，超出范围时自动限制。
 * @return Alpha固定为255的ARGB整数，可直接持久化到EbookBook。
 */
internal fun createOpaqueArgb(red: Int, green: Int, blue: Int): Int {
    return -0x1000000 or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
}

/**
 * 显示单本书阅读器并持久化分页、字号和阅读模式。
 *
 * @param book 当前书籍。
 * @param repository 读取正文、渲染PDF和保存进度的仓库。
 * @param onImmersiveChanged 沉浸状态变化回调；true时外层隐藏App底部四个导航按钮。
 * @param onBack 返回书架并刷新目录的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EbookReader(
    book: EbookBook,
    repository: EbookRepository,
    onImmersiveChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val latestImmersiveChanged by rememberUpdatedState(onImmersiveChanged)
    var extractedText by remember(book.id) { mutableStateOf("") }
    var textLoaded by remember(book.id) { mutableStateOf(book.format == EbookFormat.PDF) }
    var currentPage by rememberSaveable(book.id) {
        mutableStateOf(book.currentPage.coerceIn(0, book.pageCount - 1))
    }
    var readingModeName by rememberSaveable(book.id) { mutableStateOf(book.readingMode.name) }
    var readingBackgroundName by rememberSaveable(book.id) {
        mutableStateOf(book.readingBackground.name)
    }
    var fontScale by rememberSaveable(book.id) { mutableStateOf(book.fontScale) }
    var fontFamilyName by rememberSaveable(book.id) { mutableStateOf(book.fontFamily.name) }
    var controlsVisible by rememberSaveable(book.id) { mutableStateOf(true) }
    var showReaderSettings by rememberSaveable(book.id) { mutableStateOf(false) }
    var showTableOfContents by rememberSaveable(book.id) { mutableStateOf(false) }
    var showBookNotes by rememberSaveable(book.id) { mutableStateOf(false) }
    var pendingNoteDraft by remember(book.id) { mutableStateOf<PendingEbookNoteDraft?>(null) }
    var showJumpDialog by rememberSaveable(book.id) { mutableStateOf(false) }
    var showTranslationDialog by rememberSaveable(book.id) { mutableStateOf(false) }
    var translationDirectionName by rememberSaveable(book.id) {
        mutableStateOf(EbookTranslationDirection.ENGLISH_TO_CHINESE.name)
    }
    var translationDisplayModeName by rememberSaveable(book.id) {
        mutableStateOf(EbookTranslationDisplayMode.ORIGINAL.name)
    }
    var translatedText by remember(book.id) { mutableStateOf("") }
    var translationStageName by remember(book.id) {
        mutableStateOf(EbookTranslationStage.IDLE.name)
    }
    var translationMessage by remember(book.id) { mutableStateOf("") }
    var isAutoReading by rememberSaveable(book.id) { mutableStateOf(false) }
    var isSpeaking by remember(book.id) { mutableStateOf(false) }
    var readAloudStateName by remember(book.id) {
        mutableStateOf(EbookReadAloudState.INITIALIZING.name)
    }
    var showReadAloudDialog by rememberSaveable(book.id) { mutableStateOf(false) }
    var finishedUtteranceId by remember(book.id) { mutableStateOf("") }
    var activeUtteranceId by remember(book.id) { mutableStateOf("") }
    var speechRate by remember(book.id) {
        mutableStateOf(EbookReadAloudController.DEFAULT_SPEECH_RATE)
    }
    var selectedVoiceName by remember(book.id) { mutableStateOf("") }
    val noteRepository = remember(book.id) { EbookNoteRepository(context.applicationContext) }
    var ebookNotes by remember(book.id) { mutableStateOf(noteRepository.getBookNotes(book)) }
    val translator = remember(book.id) { EbookOfflineTranslator() }
    val readAloudController = remember(book.id) {
        EbookReadAloudController(
            context = context,
            onStateChanged = { state -> readAloudStateName = state.name },
            onSpeakingChanged = { speaking -> isSpeaking = speaking },
            onUtteranceFinished = { utteranceId -> finishedUtteranceId = utteranceId },
            onUtteranceFailed = { utteranceId ->
                if (utteranceId == activeUtteranceId) {
                    isAutoReading = false
                    activeUtteranceId = ""
                }
            }
        )
    }
    val readingMode = EbookReadingMode.entries.firstOrNull { mode ->
        mode.name == readingModeName
    } ?: EbookReadingMode.HORIZONTAL
    val readingBackground = EbookReadingBackground.entries.firstOrNull { background ->
        background.name == readingBackgroundName
    } ?: EbookReadingBackground.PAPER
    val fontFamily = EbookFontFamily.entries.firstOrNull { option ->
        option.name == fontFamilyName
    } ?: EbookFontFamily.SERIF
    val readerPalette = ebookReaderPalette(readingBackground)
    val translationDirection = EbookTranslationDirection.entries.firstOrNull { direction ->
        direction.name == translationDirectionName
    } ?: EbookTranslationDirection.ENGLISH_TO_CHINESE
    val translationDisplayMode = EbookTranslationDisplayMode.entries.firstOrNull { mode ->
        mode.name == translationDisplayModeName
    } ?: EbookTranslationDisplayMode.ORIGINAL
    val translationStage = EbookTranslationStage.entries.firstOrNull { stage ->
        stage.name == translationStageName
    } ?: EbookTranslationStage.IDLE
    val readAloudState = EbookReadAloudState.entries.firstOrNull { state ->
        state.name == readAloudStateName
    } ?: EbookReadAloudState.ERROR

    DisposableEffect(book.id) {
        onDispose {
            latestImmersiveChanged(false)
            translator.close()
            readAloudController.shutdown()
        }
    }

    // 把阅读器内部工具栏状态同步到App外壳；无工具栏即为沉浸阅读，外层底部导航必须同时隐藏。
    LaunchedEffect(book.id, controlsVisible) {
        latestImmersiveChanged(!controlsVisible)
    }

    LaunchedEffect(book.id) {
        if (book.format != EbookFormat.PDF) {
            extractedText = repository.readExtractedText(book)
            textLoaded = true
        }
    }

    val textPages = remember(extractedText) { paginateEbookText(extractedText) }
    val chapters = remember(textPages, book.format) {
        if (book.format == EbookFormat.PDF) emptyList() else buildEbookTableOfContents(textPages)
    }
    val pageCount = if (book.format == EbookFormat.PDF) {
        book.pageCount.coerceAtLeast(1)
    } else {
        textPages.size.coerceAtLeast(1)
    }
    currentPage = currentPage.coerceIn(0, pageCount - 1)
    val currentChapter = chapters.lastOrNull { chapter -> chapter.pageIndex <= currentPage }
    val originalPageText = if (book.format == EbookFormat.PDF) {
        ""
    } else {
        textPages.getOrElse(currentPage) { "没有可显示的正文" }
    }
    val displayedPageText = when (translationDisplayMode) {
        EbookTranslationDisplayMode.ORIGINAL -> originalPageText
        EbookTranslationDisplayMode.BILINGUAL -> {
            if (translatedText.isBlank()) {
                originalPageText
            } else {
                "$originalPageText\n\n—— 离线译文 ——\n\n$translatedText"
            }
        }
        EbookTranslationDisplayMode.TRANSLATED -> translatedText.ifBlank { originalPageText }
    }
    // 对照模式固定朗读原文，避免同一个TTS音色在中英文混合段落中频繁误读。
    val readAloudText = when (translationDisplayMode) {
        EbookTranslationDisplayMode.TRANSLATED -> translatedText
        else -> originalPageText
    }
    val readAloudLanguageCode = when {
        translationDisplayMode == EbookTranslationDisplayMode.TRANSLATED &&
            translationDirection == EbookTranslationDirection.ENGLISH_TO_CHINESE -> Locale.CHINESE.language
        translationDisplayMode == EbookTranslationDisplayMode.TRANSLATED -> Locale.ENGLISH.language
        else -> detectEbookLanguageCode(originalPageText)
    }

    // 每次翻页或切换语言方向时只翻译当前页；上一任务由LaunchedEffect自动取消，防止旧译文覆盖新页面。
    LaunchedEffect(
        book.id,
        currentPage,
        originalPageText,
        translationDirection,
        translationDisplayMode,
        textLoaded
    ) {
        translatedText = ""
        translationMessage = ""
        translationStageName = EbookTranslationStage.IDLE.name
        if (
            textLoaded &&
            book.format != EbookFormat.PDF &&
            translationDisplayMode != EbookTranslationDisplayMode.ORIGINAL
        ) {
            runCatching {
                translator.translate(originalPageText, translationDirection) { stage ->
                    translationStageName = stage.name
                }
            }.onSuccess { result ->
                translatedText = result
            }.onFailure {
                translationStageName = EbookTranslationStage.ERROR.name
                translationMessage = "离线翻译未完成，请确认首次模型下载时网络可用后重试"
            }
        }
    }

    LaunchedEffect(readAloudState, readAloudLanguageCode, showReadAloudDialog) {
        speechRate = readAloudController.speechRate()
        if (showReadAloudDialog && readAloudState == EbookReadAloudState.READY) {
            selectedVoiceName = readAloudController.selectedVoiceName(readAloudLanguageCode)
        }
    }

    // 连续朗读会在翻页或译文准备完成后提交当前页；QUEUE_FLUSH会停止仍在朗读的旧页面。
    LaunchedEffect(
        isAutoReading,
        currentPage,
        readAloudText,
        readAloudLanguageCode,
        readAloudState,
        translationStage
    ) {
        if (!isAutoReading) return@LaunchedEffect
        if (readAloudState != EbookReadAloudState.READY || readAloudText.isBlank()) {
            if (
                translationDisplayMode != EbookTranslationDisplayMode.TRANSLATED ||
                translationStage == EbookTranslationStage.ERROR
            ) {
                isAutoReading = false
            }
            return@LaunchedEffect
        }
        val utteranceId = "ebook_${book.id}_${currentPage}_${System.nanoTime()}"
        activeUtteranceId = utteranceId
        if (!readAloudController.speak(readAloudText, readAloudLanguageCode, utteranceId)) {
            isAutoReading = false
            activeUtteranceId = ""
        }
    }

    LaunchedEffect(finishedUtteranceId) {
        if (
            finishedUtteranceId.isBlank() ||
            finishedUtteranceId != activeUtteranceId ||
            !isAutoReading
        ) {
            return@LaunchedEffect
        }
        activeUtteranceId = ""
        if (currentPage < pageCount - 1) {
            currentPage += 1
        } else {
            isAutoReading = false
        }
    }

    // 短暂合并连续翻页和字号点击，避免每个触摸事件都同步写目录。
    LaunchedEffect(
        book.id,
        currentPage,
        pageCount,
        readingMode,
        readingBackground,
        fontScale,
        fontFamily
    ) {
        delay(PROGRESS_SAVE_DEBOUNCE_MILLIS)
        withContext(Dispatchers.IO) {
            repository.saveReadingProgress(
                bookId = book.id,
                currentPage = currentPage,
                pageCount = pageCount,
                readingMode = readingMode,
                readingBackground = readingBackground,
                fontScale = fontScale,
                fontFamily = fontFamily
            )
        }
    }

    // 沉浸模式下系统返回键先恢复工具栏，避免用户误触直接退出书籍。
    BackHandler(enabled = !controlsVisible) {
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerPalette.background)
    ) {
        if (!textLoaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.65f))
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = "正在准备离线正文…",
                        color = readerPalette.text.copy(alpha = 0.72f)
                    )
                }
            }
        } else {
            EbookPageContainer(
                modifier = Modifier.fillMaxSize(),
                book = book,
                repository = repository,
                textPages = textPages,
                pageCount = pageCount,
                currentPage = currentPage,
                currentTextOverride = displayedPageText,
                readingMode = readingMode,
                readerPalette = readerPalette,
                fontScale = fontScale,
                fontFamily = fontFamily,
                controlsVisible = controlsVisible,
                noteCountsByPage = ebookNotes.groupingBy(EbookNote::pageIndex).eachCount(),
                onToggleControls = { controlsVisible = !controlsVisible },
                onCreateNote = { excerpt, page ->
                    pendingNoteDraft = PendingEbookNoteDraft(
                        excerpt = excerpt,
                        pageIndex = page,
                        chapterTitle = chapters.lastOrNull { chapter ->
                            chapter.pageIndex <= page
                        }?.title.orEmpty()
                    )
                },
                onOpenPageNotes = { page ->
                    currentPage = page.coerceIn(0, pageCount - 1)
                    controlsVisible = true
                    showBookNotes = true
                },
                onPageChanged = { page -> currentPage = page.coerceIn(0, pageCount - 1) }
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.TopCenter),
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { height -> -height },
            exit = fadeOut() + slideOutVertically { height -> -height }
        ) {
            Surface(
                color = readerPalette.control.copy(alpha = 0.97f),
                tonalElevation = 5.dp,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            isAutoReading = false
                            readAloudController.stop()
                            onBack()
                        }
                    ) {
                        Text("‹ 书架", color = readerPalette.controlText)
                    }
                    Text(
                        modifier = Modifier.weight(1f),
                        text = book.title,
                        color = readerPalette.controlText,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { showReaderSettings = true }) {
                        Text("阅读设置", color = readerPalette.controlText)
                    }
                }
            }
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { height -> height },
            exit = fadeOut() + slideOutVertically { height -> height }
        ) {
            Surface(
                color = readerPalette.control.copy(alpha = 0.94f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = currentPage > 0,
                            onClick = { currentPage -= 1 }
                        ) {
                            Text("‹ 上一页", color = readerPalette.controlText)
                        }
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "第 ${currentPage + 1} / $pageCount 页",
                            color = readerPalette.controlText,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(
                            enabled = currentPage < pageCount - 1,
                            onClick = { currentPage += 1 }
                        ) {
                            Text("下一页 ›", color = readerPalette.controlText)
                        }
                    }
                    Text(
                        text = currentChapter?.let { chapter ->
                            "${chapter.title} · 单击正文进入沉浸阅读"
                        } ?: "单击正文进入沉浸阅读",
                        color = readerPalette.controlText.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (ebookNotes.isNotEmpty()) {
                        TextButton(onClick = { showBookNotes = true }) {
                            val currentPageNoteCount = ebookNotes.count { note ->
                                note.pageIndex == currentPage
                            }
                            Text(
                                text = if (currentPageNoteCount > 0) {
                                    "本页笔记 $currentPageNoteCount · 全书 ${ebookNotes.size}"
                                } else {
                                    "全书笔记 ${ebookNotes.size}"
                                },
                                color = readerPalette.controlText
                            )
                        }
                    }
                    if (translationDisplayMode != EbookTranslationDisplayMode.ORIGINAL) {
                        val stageText = when (translationStage) {
                            EbookTranslationStage.IDLE -> "等待离线翻译"
                            EbookTranslationStage.PREPARING_MODEL -> "正在准备离线语言模型…"
                            EbookTranslationStage.TRANSLATING -> "正在手机本地翻译本页…"
                            EbookTranslationStage.READY -> "离线译文已就绪"
                            EbookTranslationStage.ERROR -> translationMessage.ifBlank { "离线翻译失败" }
                        }
                        Text(
                            text = stageText,
                            color = if (translationStage == EbookTranslationStage.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                readerPalette.controlText.copy(alpha = 0.75f)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }

    if (showReaderSettings) {
        EbookReaderSettingsSheet(
            book = book,
            readingMode = readingMode,
            readingBackground = readingBackground,
            fontScale = fontScale,
            fontFamily = fontFamily,
            chapterCount = chapters.size,
            noteCount = ebookNotes.size,
            translationDisplayMode = translationDisplayMode,
            readAloudReady = readAloudState == EbookReadAloudState.READY,
            isAutoReading = isAutoReading || isSpeaking,
            onReadingModeChanged = { mode -> readingModeName = mode.name },
            onReadingBackgroundChanged = { background ->
                readingBackgroundName = background.name
            },
            onFontScaleChanged = { scale ->
                fontScale = scale.coerceIn(MIN_READER_FONT_SCALE, MAX_READER_FONT_SCALE)
            },
            onFontFamilyChanged = { option -> fontFamilyName = option.name },
            onOpenNotes = {
                showReaderSettings = false
                showBookNotes = true
            },
            onOpenTableOfContents = {
                showReaderSettings = false
                if (chapters.isEmpty()) {
                    showJumpDialog = true
                } else {
                    showTableOfContents = true
                }
            },
            onJump = {
                showReaderSettings = false
                showJumpDialog = true
            },
            onTranslate = {
                showReaderSettings = false
                showTranslationDialog = true
            },
            onToggleReadAloud = {
                if (isAutoReading || isSpeaking) {
                    isAutoReading = false
                    activeUtteranceId = ""
                    readAloudController.stop()
                } else {
                    isAutoReading = true
                }
            },
            onReadAloudSettings = {
                showReaderSettings = false
                showReadAloudDialog = true
            },
            onEnterImmersive = {
                showReaderSettings = false
                controlsVisible = false
            },
            onDismiss = { showReaderSettings = false }
        )
    }

    if (showJumpDialog) {
        EbookJumpDialog(
            currentPage = currentPage,
            pageCount = pageCount,
            onDismiss = { showJumpDialog = false },
            onJump = { page ->
                currentPage = page
                showJumpDialog = false
            }
        )
    }

    if (showTableOfContents) {
        EbookTableOfContentsDialog(
            chapters = chapters,
            currentPage = currentPage,
            onChapterSelected = { chapter ->
                currentPage = chapter.pageIndex.coerceIn(0, pageCount - 1)
                showTableOfContents = false
            },
            onDismiss = { showTableOfContents = false }
        )
    }

    pendingNoteDraft?.let { draft ->
        EbookCreateNoteDialog(
            draft = draft,
            onSave = { comment ->
                val saved = noteRepository.createNote(
                    book = book,
                    pageIndex = draft.pageIndex,
                    chapterTitle = draft.chapterTitle,
                    excerpt = draft.excerpt,
                    comment = comment
                )
                if (saved != null) {
                    ebookNotes = noteRepository.getBookNotes(book)
                    pendingNoteDraft = null
                    true
                } else {
                    false
                }
            },
            onDismiss = { pendingNoteDraft = null }
        )
    }

    if (showBookNotes) {
        EbookNotesDialog(
            notes = ebookNotes,
            currentPage = currentPage,
            onJump = { note ->
                currentPage = note.pageIndex.coerceIn(0, pageCount - 1)
                controlsVisible = true
                showBookNotes = false
            },
            onDelete = { note ->
                if (noteRepository.deleteNote(note.articleId)) {
                    ebookNotes = noteRepository.getBookNotes(book)
                }
            },
            onDismiss = { showBookNotes = false }
        )
    }

    if (showTranslationDialog) {
        EbookTranslationDialog(
            direction = translationDirection,
            displayMode = translationDisplayMode,
            onDirectionChanged = { direction -> translationDirectionName = direction.name },
            onDisplayModeChanged = { mode -> translationDisplayModeName = mode.name },
            onDismiss = { showTranslationDialog = false }
        )
    }

    if (showReadAloudDialog) {
        val voiceOptions = readAloudController.availableVoices(readAloudLanguageCode)
        EbookReadAloudDialog(
            state = readAloudState,
            languageCode = readAloudLanguageCode,
            voices = voiceOptions,
            selectedVoiceName = selectedVoiceName,
            speechRate = speechRate,
            onVoiceSelected = { voiceName ->
                if (readAloudController.selectVoice(readAloudLanguageCode, voiceName)) {
                    selectedVoiceName = voiceName
                }
            },
            onSpeechRateChanged = { rate -> speechRate = rate },
            onSpeechRateChangeFinished = {
                readAloudController.setSpeechRate(speechRate)
            },
            onOpenSystemSettings = {
                runCatching {
                    context.startActivity(Intent(EBOOK_TTS_SETTINGS_ACTION))
                }.recoverCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            },
            onDismiss = { showReadAloudDialog = false }
        )
    }
}

/**
 * 选择设备端翻译方向与正文展示方式。
 *
 * @param direction 当前中英文翻译方向。
 * @param displayMode 原文、对照或译文模式。
 * @param onDirectionChanged 方向切换回调。
 * @param onDisplayModeChanged 展示模式切换回调。
 * @param onDismiss 关闭设置回调。
 * @return 无返回值。
 */
@Composable
private fun EbookTranslationDialog(
    direction: EbookTranslationDirection,
    displayMode: EbookTranslationDisplayMode,
    onDirectionChanged: (EbookTranslationDirection) -> Unit,
    onDisplayModeChanged: (EbookTranslationDisplayMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离线翻译") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "正文只在手机本地翻译。第一次使用某个语言方向时需要联网下载约30MB模型，完成后可断网使用。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("翻译方向", fontWeight = FontWeight.Bold)
                EbookTranslationDirection.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDirectionChanged(option) }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = direction == option,
                            onClick = { onDirectionChanged(option) }
                        )
                        Text(option.displayName)
                    }
                }
                Text("显示方式", fontWeight = FontWeight.Bold)
                EbookTranslationDisplayMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDisplayModeChanged(option) }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = displayMode == option,
                            onClick = { onDisplayModeChanged(option) }
                        )
                        Text(option.displayName)
                    }
                }
                Text(
                    "对照模式显示原文与译文，但连续朗读只读原文；译文模式会朗读译文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "翻译由Google ML Kit设备端模型提供，适合辅助阅读，不替代专业出版译本。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/**
 * 设置当前正文语言对应的系统离线音色和朗读速度。
 *
 * @param state Android TTS初始化状态。
 * @param languageCode 当前将朗读的语言代码。
 * @param voices 当前语言的离线音色。
 * @param selectedVoiceName 已选系统Voice名称。
 * @param speechRate 当前倍速。
 * @param onVoiceSelected 音色选择回调。
 * @param onSpeechRateChanged 拖动速度时的即时页面状态回调。
 * @param onSpeechRateChangeFinished 保存最终速度回调。
 * @param onOpenSystemSettings 跳转系统TTS设置回调。
 * @param onDismiss 关闭设置回调。
 * @return 无返回值。
 */
@Composable
private fun EbookReadAloudDialog(
    state: EbookReadAloudState,
    languageCode: String,
    voices: List<EbookTtsVoiceOption>,
    selectedVoiceName: String,
    speechRate: Float,
    onVoiceSelected: (String) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
    onSpeechRateChangeFinished: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音色与阅读速度") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "当前朗读语言：${if (languageCode == Locale.CHINESE.language) "中文" else "英语"}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                when {
                    state == EbookReadAloudState.INITIALIZING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在连接Android文字转语音服务…")
                    }
                    voices.isEmpty() -> {
                        Text(
                            "手机没有安装该语言的离线音色。可进入系统设置下载语音数据，返回后重新打开阅读器。",
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = onOpenSystemSettings) { Text("打开系统TTS设置") }
                    }
                    else -> {
                        Text("离线音色（${voices.size}）", fontWeight = FontWeight.Bold)
                        voices.forEach { voice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onVoiceSelected(voice.name) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedVoiceName == voice.name,
                                    onClick = { onVoiceSelected(voice.name) }
                                )
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = voice.displayName,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "阅读速度：${String.format(Locale.CHINA, "%.1f×", speechRate)}",
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = speechRate,
                    onValueChange = onSpeechRateChanged,
                    valueRange = EbookReadAloudController.MIN_SPEECH_RATE..EbookReadAloudController.MAX_SPEECH_RATE,
                    steps = 14,
                    onValueChangeFinished = onSpeechRateChangeFinished
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5×", style = MaterialTheme.typography.labelSmall)
                    Text("1.0×", style = MaterialTheme.typography.labelSmall)
                    Text("2.0×", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "音色来自当前Android TTS引擎；不同手机和已下载语音包提供的选项数量会不同。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSpeechRateChangeFinished()
                    onDismiss()
                }
            ) { Text("完成") }
        }
    )
}

/** 阅读背景与工具栏使用的固定配色，避免主题色破坏长文本的可读性。 */
private data class EbookReaderPalette(
    val background: Color,
    val text: Color,
    val control: Color,
    val controlText: Color
)

/**
 * 把用户选择的阅读背景转换为正文和浮动工具栏颜色。
 *
 * 使用方法：
 * 阅读器根据已保存的[EbookReadingBackground]调用本函数，并把返回值传给正文页和工具栏。
 *
 * @param background 当前阅读背景选项。
 * @return 完整的阅读器背景、文字、控制栏和控制栏文字颜色。
 */
private fun ebookReaderPalette(background: EbookReadingBackground): EbookReaderPalette {
    return when (background) {
        EbookReadingBackground.PAPER -> EbookReaderPalette(
            background = Color(0xFFFFFAEF),
            text = Color(0xFF2D281F),
            control = Color(0xFFF2E9D5),
            controlText = Color(0xFF332B20)
        )
        EbookReadingBackground.WARM -> EbookReaderPalette(
            background = Color(0xFFF1DEB7),
            text = Color(0xFF3A2A1C),
            control = Color(0xFFDEBF8B),
            controlText = Color(0xFF372515)
        )
        EbookReadingBackground.GREEN -> EbookReaderPalette(
            background = Color(0xFFDDE8D6),
            text = Color(0xFF263328),
            control = Color(0xFFC5D6BD),
            controlText = Color(0xFF233026)
        )
        EbookReadingBackground.NIGHT -> EbookReaderPalette(
            background = Color(0xFF111318),
            text = Color(0xFFD8DAE0),
            control = Color(0xFF22262E),
            controlText = Color(0xFFF1F2F5)
        )
    }
}

/**
 * 集中展示阅读模式、背景、字号、跳页、翻译和朗读设置。
 *
 * 使用方法：
 * 用户点击阅读器右上角“阅读设置”后显示。二级功能通过回调打开原有对话框，关闭本面板后
 * 不会在正文顶部长期占据空间；点击“进入沉浸阅读”则让正文保留为屏幕唯一主要内容。
 *
 * @param book 当前书籍，用于判断PDF不支持的文字能力。
 * @param readingMode 当前翻页模式。
 * @param readingBackground 当前阅读背景。
 * @param fontScale 当前字号缩放倍率。
 * @param fontFamily 当前正文字体。
 * @param chapterCount 当前书籍识别出的目录项数量；0表示只能按页码跳转。
 * @param noteCount 当前书籍已经保存的摘录笔记数量。
 * @param translationDisplayMode 当前翻译展示方式。
 * @param readAloudReady Android TTS是否已准备完成。
 * @param isAutoReading 当前是否正在连续朗读。
 * @param onReadingModeChanged 翻页模式变化回调。
 * @param onReadingBackgroundChanged 阅读背景变化回调。
 * @param onFontScaleChanged 字号变化回调。
 * @param onFontFamilyChanged 字体变化回调。
 * @param onOpenNotes 打开本书笔记列表的回调。
 * @param onOpenTableOfContents 打开目录或页码列表的回调。
 * @param onJump 打开跳页窗口的回调。
 * @param onTranslate 打开离线翻译设置的回调。
 * @param onToggleReadAloud 开始或停止连续朗读的回调。
 * @param onReadAloudSettings 打开音色与速度设置的回调。
 * @param onEnterImmersive 进入无干扰阅读的回调。
 * @param onDismiss 关闭面板的回调。
 * @return 无返回值，直接显示底部设置面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EbookReaderSettingsSheet(
    book: EbookBook,
    readingMode: EbookReadingMode,
    readingBackground: EbookReadingBackground,
    fontScale: Float,
    fontFamily: EbookFontFamily,
    chapterCount: Int,
    noteCount: Int,
    translationDisplayMode: EbookTranslationDisplayMode,
    readAloudReady: Boolean,
    isAutoReading: Boolean,
    onReadingModeChanged: (EbookReadingMode) -> Unit,
    onReadingBackgroundChanged: (EbookReadingBackground) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onFontFamilyChanged: (EbookFontFamily) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTableOfContents: () -> Unit,
    onJump: () -> Unit,
    onTranslate: () -> Unit,
    onToggleReadAloud: () -> Unit,
    onReadAloudSettings: () -> Unit,
    onEnterImmersive: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Text("翻页效果", fontWeight = FontWeight.SemiBold)
            EbookReadingMode.entries.chunked(2).forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowModes.forEach { mode ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = readingMode == mode,
                            onClick = { onReadingModeChanged(mode) },
                            label = { Text(mode.displayName) }
                        )
                    }
                    if (rowModes.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            Text("阅读背景", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EbookReadingBackground.entries.forEach { background ->
                    val palette = ebookReaderPalette(background)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onReadingBackgroundChanged(background) },
                        color = palette.background,
                        contentColor = palette.text,
                        shape = RoundedCornerShape(14.dp),
                        border = if (readingBackground == background) {
                            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            androidx.compose.foundation.BorderStroke(1.dp, palette.text.copy(alpha = 0.18f))
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Aa", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                            Text(background.displayName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Text(
                text = "正文字号：${String.format(Locale.CHINA, "%.0f%%", fontScale * 100f)}",
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                enabled = book.format != EbookFormat.PDF,
                value = fontScale,
                onValueChange = onFontScaleChanged,
                valueRange = MIN_READER_FONT_SCALE..MAX_READER_FONT_SCALE,
                steps = 5
            )

            Text("正文字体", fontWeight = FontWeight.SemiBold)
            EbookFontFamily.entries.chunked(2).forEach { rowFonts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowFonts.forEach { option ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            enabled = book.format != EbookFormat.PDF,
                            selected = fontFamily == option,
                            onClick = { onFontFamilyChanged(option) },
                            label = {
                                Text(
                                    text = option.displayName,
                                    fontFamily = composeFontFamily(option)
                                )
                            }
                        )
                    }
                    if (rowFonts.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenTableOfContents) {
                    Text(if (chapterCount > 0) "目录（$chapterCount）" else "页面目录")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onJump) {
                    Text("跳转页码")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = book.format != EbookFormat.PDF,
                onClick = onTranslate
            ) {
                Text(
                    if (translationDisplayMode == EbookTranslationDisplayMode.ORIGINAL) {
                        "离线翻译"
                    } else {
                        translationDisplayMode.displayName
                    }
                )
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = noteCount > 0,
                onClick = onOpenNotes
            ) {
                Text(if (noteCount > 0) "本书笔记（$noteCount）" else "本书还没有笔记")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = book.format != EbookFormat.PDF && readAloudReady,
                    onClick = onToggleReadAloud
                ) {
                    Text(if (isAutoReading) "停止朗读" else "连续朗读")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = book.format != EbookFormat.PDF,
                    onClick = onReadAloudSettings
                ) {
                    Text("音色与速度")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onEnterImmersive
            ) {
                Text("进入沉浸阅读")
            }
            Text(
                "沉浸模式只保留正文。单击正文可重新显示工具栏，系统返回键也会先恢复工具栏。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 根据阅读模式承载PDF页或文本页。
 *
 * @return 无返回值。
 */
@Composable
private fun EbookPageContainer(
    modifier: Modifier = Modifier,
    book: EbookBook,
    repository: EbookRepository,
    textPages: List<String>,
    pageCount: Int,
    currentPage: Int,
    currentTextOverride: String,
    readingMode: EbookReadingMode,
    readerPalette: EbookReaderPalette,
    fontScale: Float,
    fontFamily: EbookFontFamily,
    controlsVisible: Boolean,
    noteCountsByPage: Map<Int, Int>,
    onToggleControls: () -> Unit,
    onCreateNote: (String, Int) -> Unit,
    onOpenPageNotes: (Int) -> Unit,
    onPageChanged: (Int) -> Unit
) {
    val pageContent: @Composable (Int, Boolean) -> Unit = { page, allowInnerScroll ->
        if (book.format == EbookFormat.PDF) {
            PdfEbookPage(
                book = book,
                pageIndex = page,
                repository = repository,
                allowVerticalScroll = allowInnerScroll
            )
        } else {
            TextEbookPage(
                text = if (page == currentPage) {
                    currentTextOverride
                } else {
                    textPages.getOrElse(page) { "没有可显示的正文" }
                },
                fontScale = fontScale,
                fontFamily = fontFamily,
                allowVerticalScroll = allowInnerScroll,
                controlsVisible = controlsVisible,
                noteCount = noteCountsByPage[page] ?: 0,
                backgroundColor = readerPalette.background,
                textColor = readerPalette.text,
                onCreateNote = { excerpt -> onCreateNote(excerpt, page) },
                onOpenNotes = { onOpenPageNotes(page) }
            )
        }
    }

    // 正文区域统一接收单击手势；分页器仍负责拖动手势，两者不会要求用户切换操作模式。
    Box(
        modifier = modifier
            .background(readerPalette.background)
            .pointerInput(book.id, readingMode, controlsVisible) {
                detectTapGestures(onTap = { onToggleControls() })
            }
    ) {
        when (readingMode) {
        EbookReadingMode.PAGE_CURL -> {
            val density = LocalDensity.current
            val pagerState = rememberPagerState(
                initialPage = currentPage.coerceIn(0, pageCount - 1),
                pageCount = { pageCount }
            )
            LaunchedEffect(currentPage) {
                if (!pagerState.isScrollInProgress && pagerState.currentPage != currentPage) {
                    pagerState.scrollToPage(currentPage)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
            }
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                beyondViewportPageCount = 1
            ) { page ->
                val signedOffset = (
                    pagerState.currentPage - page + pagerState.currentPageOffsetFraction
                ).coerceIn(-1f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 以书页内侧为轴做透视旋转，并随拖动增加阴影，形成纸页翻动效果。
                            rotationY = signedOffset * -34f
                            cameraDistance = 22f * density.density
                            transformOrigin = if (signedOffset < 0f) {
                                TransformOrigin(0f, 0.5f)
                            } else {
                                TransformOrigin(1f, 0.5f)
                            }
                            shadowElevation = with(density) {
                                (signedOffset.absoluteValue * 18f).dp.toPx()
                            }
                            alpha = 1f - signedOffset.absoluteValue * 0.08f
                        }
                        .background(readerPalette.background)
                ) {
                    pageContent(page, true)
                    if (signedOffset.absoluteValue > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(34.dp)
                                .align(
                                    if (signedOffset < 0f) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    }
                                )
                                .background(
                                    Brush.horizontalGradient(
                                        colors = if (signedOffset < 0f) {
                                            listOf(Color.Black.copy(alpha = 0.20f), Color.Transparent)
                                        } else {
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f))
                                        }
                                    )
                                )
                        )
                    }
                }
            }
        }

        EbookReadingMode.HORIZONTAL -> {
            val pagerState = rememberPagerState(
                initialPage = currentPage.coerceIn(0, pageCount - 1),
                pageCount = { pageCount }
            )
            LaunchedEffect(currentPage) {
                if (!pagerState.isScrollInProgress && pagerState.currentPage != currentPage) {
                    pagerState.scrollToPage(currentPage)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
            }
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                beyondViewportPageCount = 1
            ) { page -> pageContent(page, true) }
        }

        EbookReadingMode.VERTICAL -> {
            val pagerState = rememberPagerState(
                initialPage = currentPage.coerceIn(0, pageCount - 1),
                pageCount = { pageCount }
            )
            LaunchedEffect(currentPage) {
                if (!pagerState.isScrollInProgress && pagerState.currentPage != currentPage) {
                    pagerState.scrollToPage(currentPage)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
            }
            VerticalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                beyondViewportPageCount = 1
            ) { page -> pageContent(page, false) }
        }

            EbookReadingMode.FADE -> {
                AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    targetState = currentPage,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ebook_fade_page"
                ) { page -> pageContent(page, true) }
            }
    }
    }
}

/**
 * 显示可选择、复制的单页文本阅读区。
 *
 * 使用方法：
 * 由[EbookPageContainer]传入当前页文字与阅读背景，不直接在业务页面单独调用。
 *
 * @param text 当前页正文。
 * @param fontScale 用户设置的字号倍率。
 * @param fontFamily 用户设置的字体族。
 * @param allowVerticalScroll 当前分页模式是否允许页内上下滚动。
 * @param controlsVisible 工具栏是否显示，用于为顶部和底部控制栏预留阅读边距。
 * @param noteCount 当前页已经保存的笔记数量。
 * @param backgroundColor 阅读背景颜色。
 * @param textColor 正文文字颜色。
 * @param onCreateNote 用户确认把当前选择保存为笔记的回调。
 * @param onOpenNotes 打开当前页笔记列表的回调。
 * @return 无返回值，直接绘制文本页。
 */
@Composable
private fun TextEbookPage(
    text: String,
    fontScale: Float,
    fontFamily: EbookFontFamily,
    allowVerticalScroll: Boolean,
    controlsVisible: Boolean,
    noteCount: Int,
    backgroundColor: Color,
    textColor: Color,
    onCreateNote: (String) -> Unit,
    onOpenNotes: () -> Unit
) {
    var textFieldValue by remember(text) { mutableStateOf(TextFieldValue(text)) }
    val selectionStart = minOf(textFieldValue.selection.start, textFieldValue.selection.end)
        .coerceIn(0, text.length)
    val selectionEnd = maxOf(textFieldValue.selection.start, textFieldValue.selection.end)
        .coerceIn(selectionStart, text.length)
    val selectedText = text.substring(selectionStart, selectionEnd).trim()
    val scrollModifier = if (allowVerticalScroll) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(scrollModifier)
            .padding(
                horizontal = 24.dp,
                vertical = if (controlsVisible) 72.dp else 24.dp
            )
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = textFieldValue,
            onValueChange = { updatedValue ->
                // 阅读区只允许改变选区，禁止输入法或粘贴操作改写原书正文。
                textFieldValue = updatedValue.copy(text = text)
            },
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (18f * fontScale).sp,
                lineHeight = (30f * fontScale).sp,
                fontFamily = composeFontFamily(fontFamily),
                color = textColor
            )
        )

        if (noteCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(onClick = onOpenNotes),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    text = "◆ $noteCount",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (selectedText.isNotBlank()) {
            Button(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = {
                    onCreateNote(selectedText.take(MAX_NOTE_SELECTION_LENGTH))
                    textFieldValue = TextFieldValue(text)
                }
            ) {
                Text("为选中文字写笔记")
            }
        }
    }
}

/** @return 后台渲染并显示的一页PDF。 */
@Composable
private fun PdfEbookPage(
    book: EbookBook,
    pageIndex: Int,
    repository: EbookRepository,
    allowVerticalScroll: Boolean
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }
        var bitmap by remember(book.id, pageIndex, targetWidth) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(book.id, pageIndex, targetWidth) {
            bitmap = repository.renderPdfPage(book, pageIndex, targetWidth)
        }
        val scrollModifier = if (allowVerticalScroll) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(scrollModifier)
                .padding(8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            val pageBitmap = bitmap
            if (pageBitmap == null) {
                Text("正在渲染第${pageIndex + 1}页…")
            } else {
                Image(
                    bitmap = pageBitmap.asImageBitmap(),
                    contentDescription = "${book.title}第${pageIndex + 1}页",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

/**
 * 让用户确认长按选择的原文并补充个人感想。
 *
 * 使用方法：
 * 阅读页产生[PendingEbookNoteDraft]后显示。点击保存时把感想交给[onSave]；只有仓库确认写入成功
 * 才关闭窗口，失败时保留内容并显示错误，避免用户重复选择原文。
 *
 * @param draft 待保存的摘录草稿。
 * @param onSave 保存回调；写入成功返回true。
 * @param onDismiss 放弃本次摘录的回调。
 * @return 无返回值，直接显示笔记编辑对话框。
 */
@Composable
private fun EbookCreateNoteDialog(
    draft: PendingEbookNoteDraft,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var comment by rememberSaveable(draft.excerpt, draft.pageIndex) { mutableStateOf("") }
    var saveFailed by remember(draft.excerpt, draft.pageIndex) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加阅读笔记") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = draft.chapterTitle.ifBlank { "第${draft.pageIndex + 1}页" },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        modifier = Modifier.padding(14.dp),
                        text = draft.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = comment,
                    onValueChange = { value ->
                        comment = value.take(MAX_NOTE_COMMENT_LENGTH)
                        saveFailed = false
                    },
                    label = { Text("我的想法（可不填）") },
                    minLines = 3,
                    maxLines = 7
                )
                Text(
                    "保存后会立即同步到“功能中心→笔记”，并参与全局搜索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (saveFailed) {
                    Text(
                        "保存失败，原文和输入内容已保留，请重试。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saveFailed = !onSave(comment)
                }
            ) { Text("保存笔记") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 显示本书全部摘录笔记，并提供回到原页和删除操作。
 *
 * 使用方法：
 * 阅读器从EbookNoteRepository读取同步列表后调用。点击“回到原文”通过[onJump]返回目标页；
 * 删除会直接删除笔记中心中的同一篇文章，因此列表和笔记功能始终一致。
 *
 * @param notes 本书全部笔记。
 * @param currentPage 当前零基页码，用于高亮同页笔记。
 * @param onJump 跳回笔记来源页的回调。
 * @param onDelete 删除笔记中心同一文章的回调。
 * @param onDismiss 关闭窗口的回调。
 * @return 无返回值，直接显示可滚动笔记列表。
 */
@Composable
private fun EbookNotesDialog(
    notes: List<EbookNote>,
    currentPage: Int,
    onJump: (EbookNote) -> Unit,
    onDelete: (EbookNote) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingDeleteNote by remember(notes) { mutableStateOf<EbookNote?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本书笔记 · ${notes.size}") },
        text = {
            if (notes.isEmpty()) {
                Text("还没有笔记。长按正文选择文字后，即可创建摘录笔记。")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = notes, key = EbookNote::articleId) { note ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (note.pageIndex == currentPage) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Text(
                                    text = note.chapterTitle.ifBlank {
                                        "第${note.pageIndex + 1}页"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "“${note.excerpt}”",
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (note.comment.isNotBlank()) {
                                    Text(
                                        text = note.comment,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { pendingDeleteNote = note }) {
                                        Text("删除")
                                    }
                                    TextButton(onClick = { onJump(note) }) {
                                        Text("回到第${note.pageIndex + 1}页")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    pendingDeleteNote?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDeleteNote = null },
            title = { Text("删除这条阅读笔记？") },
            text = { Text("删除后，笔记中心里的同一篇摘录也会一并删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(note)
                        pendingDeleteNote = null
                    }
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteNote = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 显示从正文中离线识别出的章节目录，并支持点击直接跳转。
 *
 * 使用方法：
 * 阅读器完成正文分页并调用[buildEbookTableOfContents]后，把结果传入本对话框；点击任一目录项
 * 会通过[onChapterSelected]返回目标章节，页面负责关闭窗口和更新当前页。
 *
 * @param chapters 按正文顺序排列的章节目录。
 * @param currentPage 当前零基页码，用于高亮用户所在章节。
 * @param onChapterSelected 用户点击目录项后的回调。
 * @param onDismiss 关闭目录窗口的回调。
 * @return 无返回值，直接显示可滚动目录对话框。
 */
@Composable
private fun EbookTableOfContentsDialog(
    chapters: List<EbookChapter>,
    currentPage: Int,
    onChapterSelected: (EbookChapter) -> Unit,
    onDismiss: () -> Unit
) {
    val currentChapterIndex = chapters.indexOfLast { chapter -> chapter.pageIndex <= currentPage }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录 · ${chapters.size}章") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = chapters,
                    key = { chapter -> "${chapter.pageIndex}_${chapter.title}" }
                ) { chapter ->
                    val chapterIndex = chapters.indexOf(chapter)
                    val selected = chapterIndex == currentChapterIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterSelected(chapter) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = (12 + (chapter.level - 1).coerceIn(0, 3) * 12).dp,
                                end = 12.dp,
                                top = 10.dp,
                                bottom = 10.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = chapter.title,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${chapter.pageIndex + 1}页",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** @return 输入一基页码并校验范围的跳页对话框。 */
@Composable
private fun EbookJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var pageText by rememberSaveable { mutableStateOf((currentPage + 1).toString()) }
    val parsedPage = pageText.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转页码") },
        text = {
            OutlinedTextField(
                value = pageText,
                onValueChange = { value -> pageText = value.filter(Char::isDigit).take(7) },
                label = { Text("页码（1-$pageCount）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = parsedPage == null || parsedPage !in 1..pageCount
            )
        },
        confirmButton = {
            TextButton(
                enabled = parsedPage != null && parsedPage in 1..pageCount,
                onClick = { parsedPage?.let { page -> onJump(page - 1) } }
            ) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 把离线正文分割为适合手机翻页的段落页。
 *
 * @param text 完整纯文本。
 * @return 至少包含一项的分页列表；优先在段落边界换页，超长段落按字符安全切分。
 */
internal fun paginateEbookText(text: String): List<String> {
    if (text.isBlank()) return listOf("没有解析到可显示的正文")
    val pages = mutableListOf<String>()
    val current = StringBuilder()
    text.split(Regex("\\n{2,}"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { paragraph ->
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                val available = TEXT_PAGE_CHARACTER_LIMIT - current.length
                if (available <= MIN_TEXT_PAGE_REMAINDER && current.isNotEmpty()) {
                    pages += current.toString().trim()
                    current.clear()
                    continue
                }
                val chunk = remaining.take(available.coerceAtLeast(1))
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(chunk)
                remaining = remaining.drop(chunk.length)
                if (current.length >= TEXT_PAGE_CHARACTER_LIMIT || remaining.isNotEmpty()) {
                    pages += current.toString().trim()
                    current.clear()
                }
            }
        }
    if (current.isNotEmpty()) pages += current.toString().trim()
    return pages.ifEmpty { listOf("没有解析到可显示的正文") }
}

/**
 * 从分页正文中识别可点击的章节目录。
 *
 * 使用方法：
 * 阅读器完成正文分页后调用。支持“第X章/回/卷/节”、Markdown井号标题、Chapter/Book/Part
 * 以及前言、序言、楔子、后记等常见标题；同页重复标题会自动去重。
 *
 * @param pages 已按阅读器规则分页的正文。
 * @return 按页码和出现顺序排列的目录；未识别到可靠标题时返回空列表。
 */
internal fun buildEbookTableOfContents(pages: List<String>): List<EbookChapter> {
    val chapters = mutableListOf<EbookChapter>()
    val seen = mutableSetOf<String>()
    pages.forEachIndexed { pageIndex, pageText ->
        pageText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.length !in 1..MAX_CHAPTER_TITLE_LENGTH) return@forEach

            val markdownMatch = MARKDOWN_CHAPTER_PATTERN.matchEntire(line)
            val normalizedTitle: String
            val level: Int
            when {
                markdownMatch != null -> {
                    normalizedTitle = markdownMatch.groupValues[2].trim()
                    level = markdownMatch.groupValues[1].length.coerceIn(1, 4)
                }
                CHINESE_CHAPTER_PATTERN.matches(line) ||
                    ENGLISH_CHAPTER_PATTERN.matches(line) ||
                    SPECIAL_CHAPTER_PATTERN.matches(line) -> {
                    normalizedTitle = line
                    level = 1
                }
                else -> return@forEach
            }
            if (normalizedTitle.length !in 1..MAX_CHAPTER_TITLE_LENGTH) return@forEach
            val key = "$pageIndex|${normalizedTitle.lowercase(Locale.ROOT)}"
            if (seen.add(key)) {
                chapters += EbookChapter(
                    title = normalizedTitle,
                    pageIndex = pageIndex,
                    level = level
                )
            }
        }
    }
    return chapters.take(MAX_TABLE_OF_CONTENTS_ITEMS)
}

/**
 * 把可持久化字体选项映射为Compose正文使用的字体族。
 *
 * @param option 用户保存的电子书字体选项。
 * @return 可直接赋给TextStyle.fontFamily的系统字体族，不需要联网下载字体文件。
 */
private fun composeFontFamily(option: EbookFontFamily): FontFamily {
    return when (option) {
        EbookFontFamily.SERIF -> FontFamily.Serif
        EbookFontFamily.SANS_SERIF -> FontFamily.SansSerif
        EbookFontFamily.MONOSPACE -> FontFamily.Monospace
        EbookFontFamily.CURSIVE -> FontFamily.Cursive
    }
}

/** @return 字节大小转换后的易读文本。 */
private fun formatEbookSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    return when {
        safeBytes >= 1024L * 1024L -> String.format(
            Locale.CHINA,
            "%.1f MB",
            safeBytes / (1024.0 * 1024.0)
        )
        safeBytes >= 1024L -> String.format(Locale.CHINA, "%.1f KB", safeBytes / 1024.0)
        else -> "$safeBytes B"
    }
}

private const val MAX_BOOK_QUERY_LENGTH = 80
private const val BOOKS_PER_SHELF_ROW = 10
private const val SHELF_ROWS_PER_PAGE = 3
private const val BOOKS_PER_SHELF_PAGE = BOOKS_PER_SHELF_ROW * SHELF_ROWS_PER_PAGE
private const val MAX_SPINE_TITLE_CHARACTERS = 8
private const val TEXT_PAGE_CHARACTER_LIMIT = 1_050
private const val MIN_TEXT_PAGE_REMAINDER = 80
private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 350L
private const val MIN_READER_FONT_SCALE = 0.75f
private const val MAX_READER_FONT_SCALE = 1.8f
private const val READER_FONT_STEP = 0.1f
private const val EBOOK_TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
private const val MAX_CHAPTER_TITLE_LENGTH = 72
private const val MAX_TABLE_OF_CONTENTS_ITEMS = 2_000
private const val MAX_NOTE_SELECTION_LENGTH = 8_000
private const val MAX_NOTE_COMMENT_LENGTH = 8_000
private val BOOK_DRAG_HORIZONTAL_THRESHOLD = 20.dp
private val BOOK_DRAG_VERTICAL_THRESHOLD = 44.dp
private val DEFAULT_EBOOK_SPINE_COLORS = listOf(
    Color(0xFF8C2F39),
    Color(0xFF315B63),
    Color(0xFF735D3D),
    Color(0xFF4C3B67),
    Color(0xFF2F5A45),
    Color(0xFF8A5529),
    Color(0xFF435E8D),
    Color(0xFF6F394F)
)
private val EBOOK_SPINE_COLOR_PRESETS = listOf(
    EbookSpineColorPreset("自动", 0),
    EbookSpineColorPreset("酒红", createOpaqueArgb(140, 47, 57)),
    EbookSpineColorPreset("墨绿", createOpaqueArgb(47, 90, 69)),
    EbookSpineColorPreset("藏蓝", createOpaqueArgb(49, 91, 99)),
    EbookSpineColorPreset("栗棕", createOpaqueArgb(115, 79, 52)),
    EbookSpineColorPreset("靛紫", createOpaqueArgb(76, 59, 103)),
    EbookSpineColorPreset("琥珀", createOpaqueArgb(138, 85, 41)),
    EbookSpineColorPreset("雾蓝", createOpaqueArgb(67, 94, 141)),
    EbookSpineColorPreset("莓粉", createOpaqueArgb(126, 63, 88))
)
private val MARKDOWN_CHAPTER_PATTERN = Regex("^(#{1,4})\\s+(.+)$")
private val CHINESE_CHAPTER_PATTERN = Regex(
    "^第[〇零一二三四五六七八九十百千万两0-9]+[章节回卷部篇集](?!正文(?:[。.]|$)).{0,60}$"
)
private val ENGLISH_CHAPTER_PATTERN = Regex(
    "^(chapter|book|part)\\s+([0-9ivxlcdm]+|[a-z]+)([ .:：-].{0,52})?$",
    RegexOption.IGNORE_CASE
)
private val SPECIAL_CHAPTER_PATTERN = Regex(
    "^(序|序言|前言|楔子|引子|引言|后记|尾声|终章|附录|contents|preface|prologue|epilogue)$",
    RegexOption.IGNORE_CASE
)

/**
 * 书脊预设色选项。
 *
 * @param name 编辑窗口显示名称。
 * @param argb 不透明ARGB颜色；0表示自动配色。
 */
private data class EbookSpineColorPreset(
    val name: String,
    val argb: Int
)
