package com.example.harleyapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import com.example.harleyapp.data.EbookRepository
import com.example.harleyapp.data.EbookNoteRepository
import com.example.harleyapp.data.EbookPaginationCacheSpec
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookChapter
import com.example.harleyapp.model.EbookFontFamily
import com.example.harleyapp.model.EbookFormat
import com.example.harleyapp.model.EbookImportProgress
import com.example.harleyapp.model.EbookNote
import com.example.harleyapp.model.EbookReadingBackground
import com.example.harleyapp.model.EbookReadingMode
import com.example.harleyapp.model.EbookShelfSkin
import com.example.harleyapp.model.EbookTranslationDirection
import com.example.harleyapp.model.EbookTranslationDisplayMode
import com.example.harleyapp.system.EbookOfflineTranslator
import com.example.harleyapp.system.EbookReadAloudController
import com.example.harleyapp.system.EbookReadAloudConfig
import com.example.harleyapp.system.EbookReadAloudPage
import com.example.harleyapp.system.EbookReadAloudPlayback
import com.example.harleyapp.system.EbookReadAloudPlaybackStatus
import com.example.harleyapp.system.EbookReadAloudProgressCallback
import com.example.harleyapp.system.EbookReadAloudService
import com.example.harleyapp.system.EbookReadAloudSnapshot
import com.example.harleyapp.system.EbookReadAloudState
import com.example.harleyapp.system.EbookSpeechTextRange
import com.example.harleyapp.system.EbookTtsVoiceOption
import com.example.harleyapp.system.EbookTranslationStage
import com.example.harleyapp.system.DEFAULT_EBOOK_AUTO_PAGE_INTERVAL_SECONDS
import com.example.harleyapp.system.MAX_EBOOK_AUTO_PAGE_INTERVAL_SECONDS
import com.example.harleyapp.system.MIN_EBOOK_AUTO_PAGE_INTERVAL_SECONDS
import com.example.harleyapp.system.detectEbookLanguageCode
import com.example.harleyapp.system.resolveNextEbookAutomaticPage
import com.example.harleyapp.system.shouldScheduleEbookTimedPageTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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
 * 阅读器即将持久化的完整阅读状态快照。
 *
 * 使用方法：
 * 阅读器每次重组时根据当前页码和阅读设置创建最新快照；正常停留时由防抖任务保存，退出阅读器时
 * 由DisposableEffect读取最后一份快照并立即落盘，从而避免用户快速返回书架时丢失最后一次翻页。
 *
 * @param bookId 当前书籍稳定标识。
 * @param currentPage 当前零基页码。
 * @param currentTextOffset 文本书籍当前可恢复词句在完整正文中的字符位置；普通翻页为页首，
 * 后台原文朗读暂停时可精确到页内，PDF固定为0。
 * @param currentPagePreview 文本书籍当前页原文快照，用于下次进入阅读器时立即显示。
 * @param pageCount 当前正文实际页数。
 * @param readingMode 当前翻页方式。
 * @param readingBackground 当前阅读背景。
 * @param fontScale 当前正文字号倍率。
 * @param fontFamily 当前正文字体。
 * @param readyToSave PDF始终为true；文本书籍只有完成正文加载后才为true。
 */
private data class EbookReadingProgressSnapshot(
    val bookId: String,
    val currentPage: Int,
    val currentTextOffset: Int,
    val currentPagePreview: String,
    val pageCount: Int,
    val readingMode: EbookReadingMode,
    val readingBackground: EbookReadingBackground,
    val fontScale: Float,
    val fontFamily: EbookFontFamily,
    val readyToSave: Boolean
)

/**
 * 一页按真实阅读区域测量后的文本及其原文位置。
 *
 * @param text 当前页完整正文，不包含下一页内容。
 * @param startOffset 当前页首字符在完整提取正文中的零基位置。
 * @param endOffset 当前页末尾后一位在完整提取正文中的零基位置。
 */
internal data class EbookMeasuredTextPage(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * 把前台朗读服务的低频关键断点按顺序保存回电子书目录。
 *
 * 使用方法：
 * 阅读页启动一次新服务会话时创建一个实例，并连同分页快照传给
 * [EbookReadAloudPlayback.register]。服务切页、暂停或停止时会调用本实例；磁盘写入始终在单独
 * IO线程串行执行，因此不会阻塞TTS、MediaSession或Compose主线程。停止回调排入队列后线程池
 * 会自然关闭，已排队的最后一次保存仍会执行。
 *
 * @param repository 当前应用的电子书仓库。
 * @param bookId 当前朗读书籍的稳定ID。
 * @param pages 启动会话时与服务页码一一对应的原文分页快照。
 * @param spokenTextUsesOriginal true表示服务朗读原文，可把页内断点换算为全文位置；朗读译文时
 * 传false，只保存原文页首，避免把译文UTF-16索引错误套到原文。
 */
private class EbookReadAloudRepositoryProgressCallback(
    private val repository: EbookRepository,
    private val bookId: String,
    private val pages: List<EbookMeasuredTextPage>,
    private val spokenTextUsesOriginal: Boolean
) : EbookReadAloudProgressCallback {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ebook-read-aloud-progress").apply { isDaemon = true }
    }
    private var closed = false

    /**
     * 自动续读、通知栏翻页或阅读页跳转后保存新页位置。
     *
     * @param snapshot 服务完成页码切换后的权威状态。
     * @return 无返回值；写入任务只排入后台队列。
     */
    override fun onPageChanged(snapshot: EbookReadAloudSnapshot) {
        enqueueSave(snapshot, closeAfterSave = false)
    }

    /**
     * 暂停后保存服务计算出的安全词句断点，供当前进程内继续及阅读页重建同步使用。
     *
     * @param snapshot 已回退到安全词首或句首的暂停状态。
     * @return 无返回值；写入任务只排入后台队列。
     */
    override fun onPlaybackPaused(snapshot: EbookReadAloudSnapshot) {
        enqueueSave(snapshot, closeAfterSave = false)
    }

    /**
     * 显式停止或服务销毁时排入最后一个断点保存，并关闭本会话专用线程池。
     *
     * @param snapshot 服务释放资源前发布的最终状态。
     * @return 无返回值；队列关闭后仍会完成已经接收的保存任务。
     */
    override fun onPlaybackStopped(snapshot: EbookReadAloudSnapshot) {
        enqueueSave(snapshot, closeAfterSave = true)
    }

    /**
     * 校验快照、换算原文绝对位置并按服务事件顺序提交磁盘保存。
     *
     * @param snapshot 待持久化的服务状态。
     * @param closeAfterSave true表示这是会话最后一次回调，入队后不再接受新任务。
     * @return 无返回值；书籍不匹配、页码无效或实例已关闭时安全忽略。
     */
    private fun enqueueSave(
        snapshot: EbookReadAloudSnapshot,
        closeAfterSave: Boolean
    ) {
        if (closed || snapshot.bookId != bookId) return
        val page = pages.getOrNull(snapshot.currentPage) ?: return
        val pageOffset = if (spokenTextUsesOriginal) {
            snapshot.resumeOffset.coerceIn(0, page.text.length)
        } else {
            0
        }
        val absoluteOffset = (page.startOffset + pageOffset).coerceIn(
            page.startOffset,
            page.endOffset
        )
        val accepted = runCatching {
            ioExecutor.execute {
                if (
                    !repository.saveBackgroundReadingPosition(
                        bookId = bookId,
                        currentPage = snapshot.currentPage,
                        currentTextOffset = absoluteOffset,
                        currentPagePreview = page.text,
                        pageCount = pages.size
                    )
                ) {
                    Log.w(EBOOK_SCREEN_TAG, "Failed to save background ebook playback progress")
                }
            }
            true
        }.onFailure { error ->
            Log.e(EBOOK_SCREEN_TAG, "Failed to queue background ebook playback progress", error)
        }.getOrDefault(false)

        if (closeAfterSave) {
            closed = true
            ioExecutor.shutdown()
        } else if (!accepted) {
            Log.w(EBOOK_SCREEN_TAG, "Background ebook playback progress was not queued")
        }
    }
}

/**
 * 判断系统是否允许显示电子书前台朗读的媒体通知。
 *
 * 使用方法：
 * 启动朗读服务前调用。Android 13以下没有通知运行时权限，直接返回true；Android 13及以上
 * 检查POST_NOTIFICATIONS。虽然系统可能允许无抽屉通知的前台服务继续运行，但本功能明确依赖
 * 通知栏和锁屏按钮，因此未授权时先请求权限，不静默启动一个用户看不到控制入口的会话。
 *
 * @param context 用于查询权限的Android上下文。
 * @return 能显示媒体通知返回true，否则返回false。
 */
private fun hasEbookReadAloudNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * 电子书导入或封面操作需要显示的强提示。
 *
 * @param success 操作是否成功，用于决定弹窗标题。
 * @param message 仓库返回的完整中文结果说明。
 */
private data class EbookFeedbackDialogState(
    val success: Boolean,
    val message: String
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
 * @param onReadingDuration 阅读器处于前台RESUMED状态的有效阅读毫秒数回调。
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
    onReadingDuration: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var books by remember(repository) { mutableStateOf(repository.getBooks()) }
    var selectedBookId by rememberSaveable { mutableStateOf("") }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<EbookImportProgress?>(null) }
    var isUpdatingCover by remember { mutableStateOf(false) }
    var isPreparingLibrary by remember { mutableStateOf(true) }
    var pendingCoverBookId by rememberSaveable { mutableStateOf("") }
    var feedbackDialogState by remember { mutableStateOf<EbookFeedbackDialogState?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            importProgress = EbookImportProgress(0f, "正在准备导入…")
            coroutineScope.launch {
                try {
                    val result = repository.importFromUri(uri) { progress ->
                        // 仓库在IO线程解析大文件；切回页面协程更新Compose状态，避免阻塞文件解析线程。
                        coroutineScope.launch {
                            if (isImporting) importProgress = progress
                        }
                    }
                    books = repository.getBooks()
                    statusMessage = result.message
                    feedbackDialogState = EbookFeedbackDialogState(
                        success = result.success,
                        message = result.message
                    )
                } catch (error: Throwable) {
                    Log.e(EBOOK_SCREEN_TAG, "Unexpected ebook import failure", error)
                    val message = "书籍导入意外中断，请重新选择文件后再试"
                    statusMessage = message
                    feedbackDialogState = EbookFeedbackDialogState(
                        success = false,
                        message = message
                    )
                } finally {
                    // 无论文件提供者、解析器还是存储操作发生何种异常，都必须结束页面转动状态。
                    isImporting = false
                    importProgress = null
                }
            }
        }
    }

    val folderImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            // 尽量保留目录读取权限，避免部分文档提供者在异步扫描期间过早回收授权；即使提供者不支持
            // 持久授权，当前Activity授予的临时读取权限仍可继续完成这一次导入。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure { error ->
                Log.w(EBOOK_SCREEN_TAG, "Unable to persist ebook folder permission", error)
            }
            isImporting = true
            importProgress = EbookImportProgress(0f, "正在扫描文件夹中的电子书…")
            coroutineScope.launch {
                try {
                    val result = repository.importFromTreeUri(treeUri) { progress ->
                        coroutineScope.launch {
                            if (isImporting) importProgress = progress
                        }
                    }
                    books = repository.getBooks()
                    statusMessage = result.message
                    feedbackDialogState = EbookFeedbackDialogState(
                        success = result.importedCount > 0,
                        message = result.message
                    )
                } catch (error: Throwable) {
                    Log.e(EBOOK_SCREEN_TAG, "Unexpected ebook folder import failure", error)
                    val message = "文件夹导入意外中断，请重新选择文件夹后再试"
                    statusMessage = message
                    feedbackDialogState = EbookFeedbackDialogState(
                        success = false,
                        message = message
                    )
                } finally {
                    isImporting = false
                    importProgress = null
                }
            }
        }
    }

    val coverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetBookId = pendingCoverBookId
        pendingCoverBookId = ""
        if (uri != null && targetBookId.isNotBlank()) {
            isUpdatingCover = true
            coroutineScope.launch {
                val result = repository.updateCoverFromUri(targetBookId, uri)
                books = repository.getBooks()
                statusMessage = result.message
                isUpdatingCover = false
                feedbackDialogState = EbookFeedbackDialogState(
                    success = result.success,
                    message = result.message
                )
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
                repository = repository,
                books = books,
                isImporting = isImporting || isUpdatingCover || isPreparingLibrary,
                importProgress = importProgress,
                statusMessage = statusMessage,
                onBack = onBack,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onImportFolder = { folderImportLauncher.launch(null) },
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
                onUpdateShelfSlots = { shelfSlots ->
                    val saved = repository.updateShelfSlots(shelfSlots)
                    statusMessage = if (saved) "书架位置已保存" else "书架位置保存失败"
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
                onChooseCover = { book ->
                    pendingCoverBookId = book.id
                    coverLauncher.launch(arrayOf("image/*"))
                },
                onRemoveCover = { book ->
                    val result = repository.removeCustomCover(book.id)
                    books = repository.getBooks()
                    statusMessage = result.message
                    feedbackDialogState = EbookFeedbackDialogState(
                        success = result.success,
                        message = result.message
                    )
                    result.success
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
                onReadingDuration = onReadingDuration,
                onBack = {
                    selectedBookId = ""
                    books = repository.getBooks()
                }
            )
        }
    }

    feedbackDialogState?.let { feedback ->
        AlertDialog(
            onDismissRequest = { feedbackDialogState = null },
            title = { Text(if (feedback.success) "操作成功" else "操作失败") },
            text = { Text(feedback.message) },
            confirmButton = {
                TextButton(onClick = { feedbackDialogState = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

/**
 * 显示可搜索并具备增删改查闭环的电子书架。
 *
 * @param repository 书籍封面读取仓库。
 * @param books 当前全部书籍。
 * @param isImporting 是否正在复制和解析新书。
 * @param importProgress 当前文件导入的定量进度；准备内置书或更新封面时为空。
 * @param statusMessage 最近一次操作反馈。
 * @param onBack 返回功能中心回调。
 * @param onImport 打开系统单文件选择器回调。
 * @param onImportFolder 打开系统文件夹选择器并批量导入回调。
 * @param onOpenBook 打开阅读器回调。
 * @param onSetOnShelf 把书籍加入或移出分页书架的回调。
 * @param onUpdateShelfSlots 保存书架绝对槽位的回调，槽位之间允许保留空白。
 * @param onUpdateMetadata 保存书名、作者和书脊颜色回调。
 * @param onChooseCover 为指定书籍打开系统图片选择器的回调。
 * @param onRemoveCover 删除指定书籍自定义封面的回调。
 * @param onDeleteBook 删除整本书回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EbookLibrary(
    repository: EbookRepository,
    books: List<EbookBook>,
    isImporting: Boolean,
    importProgress: EbookImportProgress?,
    statusMessage: String,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenBook: (EbookBook) -> Unit,
    onSetOnShelf: (EbookBook, Boolean) -> Boolean,
    onUpdateShelfSlots: (Map<String, Int>) -> Boolean,
    onUpdateMetadata: (String, String, String, Int) -> Boolean,
    onChooseCover: (EbookBook) -> Unit,
    onRemoveCover: (EbookBook) -> Boolean,
    onDeleteBook: (EbookBook) -> Boolean
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTabName by rememberSaveable { mutableStateOf(EbookLibraryTab.SHELF.name) }
    var shelfSkinName by rememberSaveable { mutableStateOf(repository.getShelfSkin().name) }
    var editingBook by remember { mutableStateOf<EbookBook?>(null) }
    var deletingBook by remember { mutableStateOf<EbookBook?>(null) }
    val selectedTab = EbookLibraryTab.entries.firstOrNull { tab ->
        tab.name == selectedTabName
    } ?: EbookLibraryTab.SHELF
    val shelfSkin = EbookShelfSkin.entries.firstOrNull { skin -> skin.name == shelfSkinName }
        ?: EbookShelfSkin.WALNUT
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
        books.filter(EbookBook::isOnShelf).sortedBy(EbookBook::shelfSlot)
    }

    // 封面或其他信息保存后，保持编辑弹窗指向目录中的最新模型，立即刷新预览和按钮状态。
    LaunchedEffect(books, editingBook?.id) {
        val editingBookId = editingBook?.id ?: return@LaunchedEffect
        editingBook = books.firstOrNull { book -> book.id == editingBookId }
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
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    onClick = onImport
                ) {
                    Text("＋ 导入单本")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    onClick = onImportFolder
                ) {
                    Text(if (isImporting) "正在导入…" else "选择文件夹")
                }
            }
            Text(
                text = "电子书",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "14本公版书与本机导入统一管理；支持PDF、EPUB、MOBI、AZW、AZW3、TXT、Markdown、HTML、DOCX、FB2与RTF。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isImporting) {
                val currentProgress = importProgress
                if (currentProgress != null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        progress = { currentProgress.overallFraction.coerceIn(0f, 1f) }
                    )
                    Text(
                        modifier = Modifier.padding(top = 7.dp),
                        text = "${currentProgress.message}  " +
                            "${(currentProgress.overallFraction * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    )
                }
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
                    shelfSkin = shelfSkin,
                    onOpenBook = onOpenBook,
                    onUpdateBookSlots = onUpdateShelfSlots,
                    onShelfSkinChanged = { skin ->
                        repository.saveShelfSkin(skin).also { saved ->
                            if (saved) shelfSkinName = skin.name
                        }
                    },
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
                        repository = repository,
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
            repository = repository,
            book = book,
            onDismiss = { editingBook = null },
            onChooseCover = { onChooseCover(book) },
            onRemoveCover = { onRemoveCover(book) },
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

/** 保存一套书架皮肤在Compose中实际使用的完整颜色。 */
private data class EbookShelfPalette(
    val frame: Color,
    val backTop: Color,
    val backMiddle: Color,
    val backBottom: Color,
    val shelfTop: Color,
    val shelfMiddle: Color,
    val shelfBottom: Color,
    val title: Color,
    val grainAlpha: Float
)

/**
 * 把书架皮肤枚举映射为完整木板调色板。
 *
 * @param skin 用户当前选择的书架皮肤。
 * @return 同时覆盖边框、背板、层板、标题和木纹强度的颜色集合。
 */
private fun ebookShelfPalette(skin: EbookShelfSkin): EbookShelfPalette {
    return when (skin) {
        EbookShelfSkin.WALNUT -> EbookShelfPalette(
            Color(0xFF4A291C), Color(0xFF7A4B2E), Color(0xFF3C241A), Color(0xFF62402A),
            Color(0xFFA87548), Color(0xFF5B331F), Color(0xFF2B160F), Color(0xFFFFE2B8), 0.055f
        )
        EbookShelfSkin.NATURAL_OAK -> EbookShelfPalette(
            Color(0xFF8A633C), Color(0xFFD7B780), Color(0xFFA57A48), Color(0xFFC79B62),
            Color(0xFFE4C394), Color(0xFF9A6F3F), Color(0xFF684527), Color(0xFF3F2B18), 0.09f
        )
        EbookShelfSkin.CHERRY -> EbookShelfPalette(
            Color(0xFF54251F), Color(0xFF9A4B3D), Color(0xFF56261F), Color(0xFF7C382E),
            Color(0xFFC16A57), Color(0xFF71352A), Color(0xFF361512), Color(0xFFFFD7C5), 0.06f
        )
        EbookShelfSkin.EBONY -> EbookShelfPalette(
            Color(0xFF171717), Color(0xFF3A3A3A), Color(0xFF111111), Color(0xFF292929),
            Color(0xFF5A5A5A), Color(0xFF252525), Color(0xFF080808), Color(0xFFF2D7A0), 0.07f
        )
        EbookShelfSkin.SPRUCE_WHITE -> EbookShelfPalette(
            Color(0xFFB8B1A5), Color(0xFFF2EEE5), Color(0xFFD5CEC1), Color(0xFFE7E0D4),
            Color(0xFFFFFFFF), Color(0xFFC7BFB2), Color(0xFF928A80), Color(0xFF4A4640), 0.12f
        )
        EbookShelfSkin.DEEP_OCEAN -> EbookShelfPalette(
            Color(0xFF102A43), Color(0xFF285B78), Color(0xFF102C40), Color(0xFF1D4963),
            Color(0xFF4F87A3), Color(0xFF173E55), Color(0xFF091E2B), Color(0xFFD6F2FF), 0.06f
        )
        EbookShelfSkin.STARRY_PURPLE -> EbookShelfPalette(
            Color(0xFF2C1D44), Color(0xFF665080), Color(0xFF281C3B), Color(0xFF49345F),
            Color(0xFF8D72A8), Color(0xFF442F5B), Color(0xFF1C122A), Color(0xFFF0DCFF), 0.08f
        )
        EbookShelfSkin.JADE_GREEN -> EbookShelfPalette(
            Color(0xFF173B32), Color(0xFF3E7462), Color(0xFF173C32), Color(0xFF285747),
            Color(0xFF70A18C), Color(0xFF285243), Color(0xFF0D2922), Color(0xFFD9F4E8), 0.06f
        )
    }
}

/**
 * 显示全部书架皮肤的可视化选择弹窗。
 *
 * 使用方法：
 * 用户点击书架底部“书架皮肤”时显示。每个选项使用自己的背板与层板色预览；点击后由[onSelect]
 * 持久化，保存成功时父级关闭弹窗。
 *
 * @param selectedSkin 当前已经生效的皮肤。
 * @param onSelect 用户选择一个皮肤后的保存回调。
 * @param onDismiss 关闭弹窗回调。
 * @return 无返回值，直接显示Material对话框。
 */
@Composable
private fun EbookShelfSkinDialog(
    selectedSkin: EbookShelfSkin,
    onSelect: (EbookShelfSkin) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择书架皮肤") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                EbookShelfSkin.entries.chunked(2).forEach { rowSkins ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowSkins.forEach { skin ->
                            val palette = ebookShelfPalette(skin)
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(82.dp)
                                    .clickable { onSelect(skin) }
                                    .border(
                                        width = if (skin == selectedSkin) 2.dp else 1.dp,
                                        color = if (skin == selectedSkin) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                color = palette.backMiddle,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column {
                                    Text(
                                        modifier = Modifier.padding(start = 10.dp, top = 10.dp),
                                        text = if (skin == selectedSkin) "✓ ${skin.displayName}" else skin.displayName,
                                        color = palette.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(15.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        palette.shelfTop,
                                                        palette.shelfMiddle,
                                                        palette.shelfBottom
                                                    )
                                                )
                                            )
                                    )
                                }
                            }
                        }
                        if (rowSkins.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/**
 * 把用户选择的书按每页三十个可空槽位展示为可横向切换、可自由放置的多层书架。
 *
 * 使用方法：
 * 由[EbookLibrary]的“我的书架”分栏调用；短按书脊打开书籍，长按后由书架根层统一接管拖动。
 * 根层使用实际槽位在根布局中的边界计算落点，并单独绘制跟手浮层，因此外层列表偏移、不同屏幕
 * 宽度和书架翻页动画都不会改变拖动基准。手指停留在左右边缘约450毫秒时切换相邻书架。
 *
 * @param books 已包含唯一绝对槽位的在架书籍。
 * @param shelfSkin 当前持久化书架皮肤。
 * @param onOpenBook 点击封面后的阅读回调。
 * @param onUpdateBookSlots 一次长按拖动结束后保存全部书架槽位的回调。
 * @param onShelfSkinChanged 保存用户新书架皮肤的回调。
 * @param onManageShelf 跳转“全部书籍”管理加入与移出状态的回调。
 * @return 无返回值。
 */
@Composable
private fun EbookShelfPager(
    books: List<EbookBook>,
    shelfSkin: EbookShelfSkin,
    onOpenBook: (EbookBook) -> Unit,
    onUpdateBookSlots: (Map<String, Int>) -> Boolean,
    onShelfSkinChanged: (EbookShelfSkin) -> Boolean,
    onManageShelf: () -> Unit
) {
    var positionedBooks by remember(books) { mutableStateOf(books.sortedBy(EbookBook::shelfSlot)) }
    var draggingBookId by remember { mutableStateOf<String?>(null) }
    var dragTargetSlot by remember { mutableStateOf<Int?>(null) }
    var dragPointerInRoot by remember { mutableStateOf<Offset?>(null) }
    var dragGrabOffsetInBook by remember { mutableStateOf(Offset.Zero) }
    var draggedBookBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    var shelfRootBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var edgePagingJob by remember { mutableStateOf<Job?>(null) }
    var edgePagingDirection by remember { mutableIntStateOf(0) }
    var edgePagingAnimationRunning by remember { mutableStateOf(false) }
    var showShelfSkinDialog by rememberSaveable { mutableStateOf(false) }
    val shelfSlotBoundsBySlot = remember { mutableMapOf<Int, Rect>() }
    val shelfBookBoundsById = remember { mutableMapOf<String, Rect>() }
    val shelfPages = remember(positionedBooks) { buildEbookShelfPages(positionedBooks) }
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
    val shelfCoroutineScope = rememberCoroutineScope()
    val shelfDensity = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val edgePagingWidthPx = with(shelfDensity) { EBOOK_SHELF_EDGE_PAGING_WIDTH.toPx() }
    val dropTolerancePx = with(shelfDensity) { EBOOK_SHELF_DROP_TOLERANCE.toPx() }
    val currentOnUpdateBookSlots by rememberUpdatedState(onUpdateBookSlots)

    /**
     * 停止尚未触发的边缘翻页等待。
     *
     * 使用方法：
     * 手指离开边缘、拖动结束或组件销毁时调用。已经开始的翻页动画默认自然完成，避免页面停在
     * 两页之间；组件销毁时传入[force]强制取消整个协程。
     *
     * @param force 是否连正在执行的翻页动画也立即取消。
     * @return 无返回值。
     */
    fun stopShelfEdgePaging(force: Boolean) {
        edgePagingDirection = 0
        if (force || !edgePagingAnimationRunning) {
            edgePagingJob?.cancel()
            edgePagingJob = null
        }
    }

    /**
     * 清除一次拖动的全部界面状态，但不修改任何书籍槽位。
     *
     * 使用方法：
     * 拖动正常结束并完成数据计算后调用，或在手势取消时直接调用。此函数只移除浮层、目标高亮
     * 和抓取坐标，不调用持久化接口，因此取消手势不会误保存位置。
     *
     * @return 无返回值。
     */
    fun clearShelfDragState() {
        draggingBookId = null
        dragTargetSlot = null
        dragPointerInRoot = null
        dragGrabOffsetInBook = Offset.Zero
        draggedBookBoundsInRoot = null
    }

    /**
     * 根据当前手指位置维护唯一的边缘翻页任务。
     *
     * 使用方法：
     * 每次有效拖动事件传入手指的根坐标。函数只有在手指进入可翻页方向的边缘区域后才创建任务；
     * 同一方向已有等待任务时不会重复创建。等待结束后只翻一页，并用新页面的真实槽位边界重新
     * 解析落点。继续跨页需要手指在边缘产生新的移动事件，避免一次停留连续失控翻过多页。
     *
     * @param pointerInRoot 当前手指相对于Compose根布局的坐标。
     * @return 无返回值。
     */
    fun updateShelfEdgePaging(pointerInRoot: Offset) {
        if (edgePagingAnimationRunning) return
        val direction = resolveEbookShelfEdgePagingDirection(
            pointerInRoot = pointerInRoot,
            shelfBoundsInRoot = shelfRootBoundsInRoot,
            currentPage = pagerState.currentPage,
            pageCount = shelfPages.size,
            edgeWidthPx = edgePagingWidthPx
        )
        if (direction == 0) {
            stopShelfEdgePaging(force = false)
            return
        }
        if (edgePagingDirection == direction && edgePagingJob?.isActive == true) return

        edgePagingJob?.cancel()
        edgePagingDirection = direction
        edgePagingJob = shelfCoroutineScope.launch {
            delay(EBOOK_SHELF_EDGE_PAGING_DWELL_MILLIS)
            if (draggingBookId == null || edgePagingDirection != direction) return@launch

            val sourcePage = pagerState.currentPage
            val targetPage = (sourcePage + direction).coerceIn(0, shelfPages.lastIndex)
            if (targetPage != sourcePage) {
                edgePagingAnimationRunning = true
                // 翻页期间旧页绝对槽号已经失效；新页边界未就绪时宁可不落槽，也不能跳回旧页。
                dragTargetSlot = null
                try {
                    pagerState.animateScrollToPage(targetPage)
                    val currentPointer = dragPointerInRoot
                    if (draggingBookId != null) {
                        dragTargetSlot = currentPointer?.let { pointer ->
                            resolveEbookShelfTargetSlot(
                                pointerInRoot = pointer,
                                currentPage = targetPage,
                                slotBoundsBySlot = shelfSlotBoundsBySlot,
                                nearbyTolerancePx = dropTolerancePx
                            )
                        }
                    }
                } finally {
                    edgePagingAnimationRunning = false
                }
            }
            if (edgePagingDirection == direction) edgePagingDirection = 0
            edgePagingJob = null
        }
    }

    /**
     * 结束当前拖动，并按手势结果决定是否更新和保存槽位。
     *
     * 使用方法：
     * `onDragEnd`传入true，仅当最终目标与源槽不同才调用[moveEbookShelfBookToSlot]并保存；
     * `onDragCancel`传入false，只清理状态。保存失败时恢复调用方最近一次传入的书架顺序。
     *
     * @param shouldSave 是否允许把本次目标槽位写入仓库。
     * @return 无返回值。
     */
    fun finishShelfDrag(shouldSave: Boolean) {
        if (shouldSave && edgePagingAnimationRunning) {
            val runningPagingJob = edgePagingJob
            shelfCoroutineScope.launch {
                // 松手发生在跨架动画中时先等页面停稳，让动画完成后的真实槽位命中成为最终落点。
                runningPagingJob?.join()
                if (draggingBookId != null) finishShelfDrag(shouldSave = true)
            }
            return
        }

        val movedBookId = draggingBookId
        val sourceSlot = positionedBooks.firstOrNull { book -> book.id == movedBookId }?.shelfSlot
        val targetSlot = dragTargetSlot
        stopShelfEdgePaging(force = false)
        clearShelfDragState()

        if (!shouldSave || movedBookId == null || sourceSlot == null || targetSlot == null) return
        if (targetSlot == sourceSlot) return

        val updatedBooks = moveEbookShelfBookToSlot(
            books = positionedBooks,
            bookId = movedBookId,
            targetSlot = targetSlot
        )
        positionedBooks = updatedBooks
        if (!currentOnUpdateBookSlots(updatedBooks.associate { book -> book.id to book.shelfSlot })) {
            positionedBooks = books.sortedBy(EbookBook::shelfSlot)
        }
    }

    LaunchedEffect(shelfPages.size) {
        if (pagerState.currentPage > shelfPages.lastIndex) {
            pagerState.scrollToPage(shelfPages.lastIndex.coerceAtLeast(0))
        }
    }
    DisposableEffect(Unit) {
        onDispose { stopShelfEdgePaging(force = true) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .onGloballyPositioned { coordinates ->
                    shelfRootBoundsInRoot = coordinates.boundsInRoot()
                }
                .pointerInput(books, shelfPages.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startPosition ->
                            val pointerInRoot = shelfRootBoundsInRoot.topLeft + startPosition
                            val selectedBook = findEbookShelfBookAtPointer(
                                pointerInRoot = pointerInRoot,
                                currentPage = pagerState.currentPage,
                                books = positionedBooks,
                                bookBoundsById = shelfBookBoundsById
                            )
                            val selectedBounds = selectedBook?.let { book ->
                                shelfBookBoundsById[book.id]
                            }
                            if (selectedBook != null && selectedBounds != null) {
                                edgePagingJob?.cancel()
                                edgePagingJob = null
                                edgePagingDirection = 0
                                draggingBookId = selectedBook.id
                                dragTargetSlot = selectedBook.shelfSlot
                                dragPointerInRoot = pointerInRoot
                                dragGrabOffsetInBook = pointerInRoot - selectedBounds.topLeft
                                draggedBookBoundsInRoot = selectedBounds
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, _ ->
                            if (draggingBookId != null) {
                                change.consume()
                                val pointerInRoot = shelfRootBoundsInRoot.topLeft + change.position
                                dragPointerInRoot = pointerInRoot
                                dragTargetSlot = resolveEbookShelfTargetSlot(
                                    pointerInRoot = pointerInRoot,
                                    currentPage = pagerState.currentPage,
                                    slotBoundsBySlot = shelfSlotBoundsBySlot,
                                    nearbyTolerancePx = dropTolerancePx
                                )
                                updateShelfEdgePaging(pointerInRoot)
                            }
                        },
                        onDragEnd = { finishShelfDrag(shouldSave = true) },
                        onDragCancel = { finishShelfDrag(shouldSave = false) }
                    )
                }
        ) {
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
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
                    shelfSkin = shelfSkin,
                    draggingBookId = draggingBookId,
                    dragTargetSlot = dragTargetSlot,
                    onOpenBook = onOpenBook,
                    onSlotBoundsChanged = { slot, bounds ->
                        shelfSlotBoundsBySlot[slot] = bounds
                    },
                    onBookBoundsChanged = { bookId, bounds ->
                        shelfBookBoundsById[bookId] = bounds
                    }
                )
            }

            val draggedBook = positionedBooks.firstOrNull { book -> book.id == draggingBookId }
            val currentPointer = dragPointerInRoot
            val originalBookBounds = draggedBookBoundsInRoot
            if (draggedBook != null && currentPointer != null && originalBookBounds != null) {
                val overlayTopLeftInRoot = currentPointer - dragGrabOffsetInBook
                val overlayTopLeftInShelf = overlayTopLeftInRoot - shelfRootBoundsInRoot.topLeft
                val overlayWidth = with(shelfDensity) { originalBookBounds.width.toDp() }
                val overlayHeight = with(shelfDensity) { originalBookBounds.height.toDp() }
                EbookShelfBookVisual(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = overlayTopLeftInShelf.x.roundToInt(),
                                y = overlayTopLeftInShelf.y.roundToInt()
                            )
                        }
                        .size(width = overlayWidth, height = overlayHeight)
                        .zIndex(EBOOK_SHELF_DRAG_OVERLAY_Z_INDEX),
                    book = draggedBook,
                    isLifted = true,
                    isDropTarget = false
                )
            }
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
            // 委托状态先稳定为局部值，既保证同一帧提示一致，也允许Kotlin安全收窄为非空槽位。
            val currentDragTargetSlot = dragTargetSlot
            Text(
                modifier = Modifier.weight(1f),
                text = when {
                    draggingBookId != null && currentDragTargetSlot != null -> {
                        val target = currentDragTargetSlot
                        "正在移动到第${target / BOOKS_PER_SHELF_PAGE + 1}架" +
                            "第${target % BOOKS_PER_SHELF_PAGE + 1}位 · 松手保存"
                    }
                    draggingBookId != null -> "当前位置不可放置 · 松手取消移动"
                    shelfPages.size > 1 -> "左右滑动切换书架 · 长按可放到任意空位"
                    else -> "长按书脊可放到当前书架任意空位"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showShelfSkinDialog = true }) { Text("书架皮肤") }
            TextButton(onClick = onManageShelf) { Text("管理书架") }
        }
    }

    if (showShelfSkinDialog) {
        EbookShelfSkinDialog(
            selectedSkin = shelfSkin,
            onSelect = { skin ->
                if (onShelfSkinChanged(skin)) showShelfSkinDialog = false
            },
            onDismiss = { showShelfSkinDialog = false }
        )
    }
}

/**
 * 显示具有三层背板、固定空槽和密集竖排书脊的一页真实书架。
 *
 * 使用方法：
 * [EbookShelfPager]为每一页传入固定三十个可空槽位。本函数根据[shelfSkin]绘制背板与层板，并把
 * 当前拖动目标传给书脊或空槽显示落点反馈。
 *
 * @param books 当前页固定三十个可空槽位。
 * @param shelfNumber 当前一基书架编号。
 * @param shelfSkin 当前书架皮肤。
 * @param draggingBookId 正在拖动的书籍id；没有拖动时为null。
 * @param dragTargetSlot 当前目标绝对槽位；没有拖动时为null。
 * @param onOpenBook 点击书脊打开阅读器的回调。
 * @param onSlotBoundsChanged 槽位完成布局后上报绝对槽位及其根坐标边界的回调。
 * @param onBookBoundsChanged 书脊完成布局后上报书籍id及其根坐标边界的回调。
 * @param modifier 外部页面变换修饰器。
 * @return 无返回值，直接绘制当前书架页。
 */
@Composable
private fun EbookShelfPage(
    books: List<EbookBook?>,
    shelfNumber: Int,
    shelfSkin: EbookShelfSkin,
    draggingBookId: String?,
    dragTargetSlot: Int?,
    onOpenBook: (EbookBook) -> Unit,
    onSlotBoundsChanged: (Int, Rect) -> Unit,
    onBookBoundsChanged: (String, Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val shelfPalette = ebookShelfPalette(shelfSkin)
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = shelfPalette.frame),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(shelfPalette.backTop, shelfPalette.backMiddle, shelfPalette.backBottom)
                    )
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 轻微横向木纹让书架更接近实体木板，而不是普通主题色卡片。
                repeat(18) { index ->
                    val y = size.height * index / 18f
                    drawLine(
                        color = Color.White.copy(
                            alpha = if (index % 3 == 0) {
                                shelfPalette.grainAlpha
                            } else {
                                shelfPalette.grainAlpha * 0.45f
                            }
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
                    color = shelfPalette.title,
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
                                val localSlot = rowIndex * BOOKS_PER_SHELF_ROW + columnIndex
                                val absoluteSlot = (shelfNumber - 1) * BOOKS_PER_SHELF_PAGE + localSlot
                                val book = books.getOrNull(localSlot)
                                Box(
                                    modifier = Modifier
                                        .width(spineWidth)
                                        .fillMaxHeight()
                                        .onGloballyPositioned { coordinates ->
                                            onSlotBoundsChanged(
                                                absoluteSlot,
                                                coordinates.boundsInRoot()
                                            )
                                        },
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (book != null) {
                                        val isDraggedSource = draggingBookId == book.id
                                        EbookShelfBook(
                                            modifier = Modifier.fillMaxWidth(),
                                            book = book,
                                            isSourceHidden = isDraggedSource,
                                            isDropTarget = dragTargetSlot == absoluteSlot,
                                            onOpen = { onOpenBook(book) },
                                            onBoundsChanged = { bounds ->
                                                onBookBoundsChanged(book.id, bounds)
                                            }
                                        )
                                        if (isDraggedSource) {
                                            // 源书脊只隐藏绘制而不移除布局，槽位尺寸和命中边界在拖动中保持稳定。
                                            EbookEmptyShelfSlot(
                                                modifier = Modifier.fillMaxSize(),
                                                isDropTarget = dragTargetSlot == absoluteSlot
                                            )
                                        }
                                    } else {
                                        EbookEmptyShelfSlot(
                                            modifier = Modifier.fillMaxSize(),
                                            isDropTarget = dragTargetSlot == absoluteSlot
                                        )
                                    }
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
                                        shelfPalette.shelfTop,
                                        shelfPalette.shelfMiddle,
                                        shelfPalette.shelfBottom
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
 * 显示一个可接收书籍的空书架槽位。
 *
 * 使用方法：
 * [EbookShelfPage]遍历固定三十个槽位时，对没有书的槽位调用。拖动目标经过此处时显示金色
 * “放这里”占位，普通状态保持透明，不破坏真实书架的简洁外观。
 *
 * @param isDropTarget 当前拖动目标是否指向本空槽。
 * @param modifier 父级传入的固定书脊宽度。
 * @return 无返回值，直接绘制空槽反馈。
 */
@Composable
private fun EbookEmptyShelfSlot(
    isDropTarget: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (isDropTarget) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .background(
                        color = Color(0x55FFD88A),
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                    )
                    .border(
                        width = 1.5.dp,
                        color = Color(0xFFFFD88A),
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "放\n这\n里",
                    color = Color(0xFFFFE9BF),
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 在固定书架槽中显示一册可点击的真实窄书脊，并把实际书脊边界上报给根层手势。
 *
 * 使用方法：
 * [EbookShelfPage]在每个有书的完整槽位中调用。普通状态允许短按打开；根层确认这本书进入拖动后，
 * [isSourceHidden]设为true，只隐藏书脊绘制但保留原尺寸和边界，避免源槽坍缩或重新排版。长按和
 * 拖动不在本函数内处理，统一由[EbookShelfPager]使用同一根坐标系完成。
 *
 * @param book 当前书籍及其阅读进度。
 * @param isSourceHidden 是否隐藏正在拖动的源书脊视觉并禁用短按。
 * @param isDropTarget 当前槽位是否为拖动落点；为true时显示金色边框。
 * @param onOpen 普通短按打开书籍的回调。
 * @param onBoundsChanged 书脊布局完成后上报根坐标边界的回调。
 * @param modifier 父级传入的书脊宽度修饰器。
 * @return 无返回值，直接绘制书脊或保留不可见的源书脊占位。
 */
@Composable
private fun EbookShelfBook(
    book: EbookBook,
    isSourceHidden: Boolean,
    isDropTarget: Boolean,
    onOpen: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val stableHash = book.id.hashCode().let { value ->
        if (value == Int.MIN_VALUE) 0 else kotlin.math.abs(value)
    }
    val heightFraction = 0.78f + (stableHash % 15) / 100f
    Box(
        modifier = modifier
            .fillMaxHeight(heightFraction)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.boundsInRoot())
            }
            .clickable(enabled = !isSourceHidden, onClick = onOpen)
    ) {
        EbookShelfBookVisual(
            modifier = Modifier.fillMaxSize(),
            book = book,
            isLifted = false,
            isDropTarget = isDropTarget,
            isVisible = !isSourceHidden
        )
    }
}

/**
 * 绘制可复用的书脊外观，供槽位中的原书和根层拖动浮层共同使用。
 *
 * 使用方法：
 * [EbookShelfBook]传入`isLifted=false`绘制静态书脊；[EbookShelfPager]创建跟手浮层时传入
 * `isLifted=true`，让书脊取消轻微倾斜并增加缩放和阴影。调用方必须提供明确尺寸，本函数不参与
 * 槽位测量，也不安装点击或拖动手势。
 *
 * @param book 需要绘制标题、颜色和阅读进度的书籍。
 * @param isLifted 是否使用拖动浮层的抬起视觉效果。
 * @param isDropTarget 是否绘制当前占用槽的落点边框。
 * @param isVisible 是否实际绘制书脊；false时保留布局尺寸但完全透明。
 * @param modifier 调用方提供的确定尺寸、位置和层级修饰器。
 * @return 无返回值，直接绘制书脊外观。
 */
@Composable
private fun EbookShelfBookVisual(
    book: EbookBook,
    isLifted: Boolean,
    isDropTarget: Boolean,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    val stableHash = book.id.hashCode().let { value ->
        if (value == Int.MIN_VALUE) 0 else kotlin.math.abs(value)
    }
    val spineColor = resolveEbookSpineColor(book)
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
            .zIndex(if (isLifted) 2f else 0f)
            .graphicsLayer {
                alpha = if (isVisible) 1f else 0f
                rotationZ = if (isLifted) 0f else ((stableHash % 5) - 2) * 0.35f
                scaleX = if (isLifted) 1.08f else 1f
                scaleY = if (isLifted) 1.04f else 1f
                shadowElevation = if (isLifted) 18f else 0f
            }
            .border(
                width = if (isDropTarget) 2.dp else 0.dp,
                color = if (isDropTarget) Color(0xFFFFD88A) else Color.Transparent,
                shape = RoundedCornerShape(topStart = 3.dp, topEnd = 5.dp)
            ),
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 5.dp)
            ) {
                // 书名与进度区使用独立高度约束。长书名只能在上方区域内排版，不能延伸到圆环内部。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = verticalTitle,
                        color = Color(0xFFFFF5DF),
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = MAX_SPINE_TITLE_CHARACTERS,
                        overflow = TextOverflow.Clip
                    )
                }

                // 进度圆环区域使用书脊实色遮住上方溢出的文字，并提高绘制层级，保证圆环始终清晰。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SHELF_PROGRESS_AREA_HEIGHT)
                        .background(spineColor)
                        .zIndex(3f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .zIndex(4f),
                        progress = { readingProgress },
                        color = Color(0xFFFFD88A),
                        trackColor = Color.White.copy(alpha = 0.16f),
                        strokeWidth = 1.5.dp
                    )
                }
            }
        }
    }
}

/**
 * 在当前书架页中查找被长按起点命中的书籍。
 *
 * 使用方法：
 * 根层收到长按开始坐标后调用。函数只检查[currentPage]对应的三十个绝对槽位，避免相邻预加载
 * 页面中不可见的书籍抢占手势；只有坐标位于真实书脊边界内时才返回书籍。
 *
 * @param pointerInRoot 长按起点在Compose根布局中的坐标。
 * @param currentPage 当前书架页的零基索引。
 * @param books 当前全部在架书籍。
 * @param bookBoundsById 书籍id到真实书脊根坐标边界的映射。
 * @return 命中的书籍；长按发生在空槽、层板或页外时返回null。
 */
private fun findEbookShelfBookAtPointer(
    pointerInRoot: Offset,
    currentPage: Int,
    books: List<EbookBook>,
    bookBoundsById: Map<String, Rect>
): EbookBook? {
    val firstSlot = currentPage.coerceAtLeast(0) * BOOKS_PER_SHELF_PAGE
    val pageSlots = firstSlot until firstSlot + BOOKS_PER_SHELF_PAGE
    return books.firstOrNull { book ->
        book.shelfSlot in pageSlots &&
            bookBoundsById[book.id]?.contains(pointerInRoot) == true
    }
}

/**
 * 使用真实槽位边界把手指根坐标解析为当前页唯一目标槽位。
 *
 * 使用方法：
 * 每个拖动事件和边缘翻页完成后调用。手指位于某个完整槽位矩形内时直接返回该槽；仅在距离
 * 槽位边缘不超过[nearbyTolerancePx]的小间隙内吸附到最近槽。拖到标题区、书架上下方或远离
 * 槽位后返回null，松手时保留原位置，避免书籍看似自行“飘”到远处。
 *
 * @param pointerInRoot 当前手指在Compose根布局中的坐标。
 * @param currentPage 当前书架页的零基索引。
 * @param slotBoundsBySlot 绝对槽位到完整槽位根坐标边界的映射。
 * @param nearbyTolerancePx 允许跨越书脊小间隙的最大像素距离；负数按0处理。
 * @return 当前页直接命中或容差内最近的绝对槽位；越界或边界未就绪时返回null。
 */
internal fun resolveEbookShelfTargetSlot(
    pointerInRoot: Offset,
    currentPage: Int,
    slotBoundsBySlot: Map<Int, Rect>,
    nearbyTolerancePx: Float
): Int? {
    val firstSlot = currentPage.coerceAtLeast(0) * BOOKS_PER_SHELF_PAGE
    val candidates = (firstSlot until firstSlot + BOOKS_PER_SHELF_PAGE).mapNotNull { slot ->
        slotBoundsBySlot[slot]
            ?.takeIf { bounds -> bounds.width > 0f && bounds.height > 0f }
            ?.let { bounds -> slot to bounds }
    }
    candidates.firstOrNull { (_, bounds) -> bounds.contains(pointerInRoot) }?.let { return it.first }
    val nearest = candidates.minWithOrNull(
        compareBy<Pair<Int, Rect>> { (_, bounds) ->
            squaredDistanceFromPointToRect(pointerInRoot, bounds)
        }.thenBy { (_, bounds) ->
            val centerX = (bounds.left + bounds.right) / 2f
            val centerY = (bounds.top + bounds.bottom) / 2f
            val deltaX = pointerInRoot.x - centerX
            val deltaY = pointerInRoot.y - centerY
            deltaX * deltaX + deltaY * deltaY
        }.thenBy { (slot, _) -> slot }
    ) ?: return null
    val safeTolerance = nearbyTolerancePx.coerceAtLeast(0f)
    return nearest.first.takeIf {
        squaredDistanceFromPointToRect(pointerInRoot, nearest.second) <=
            safeTolerance * safeTolerance
    }
}

/**
 * 计算一个点到轴对齐矩形边缘的平方距离。
 *
 * 使用方法：
 * 落槽解析用它判断手指是否只位于相邻槽的小间隙内。点在矩形内部或边界上返回0；使用平方值
 * 可以避免每帧拖动都执行开方，并保持与像素容差的严格比较。
 *
 * @param point 当前手指的根坐标。
 * @param bounds 一个已经验证宽高为正的槽位根坐标矩形。
 * @return 点到矩形最近位置的非负平方像素距离。
 */
private fun squaredDistanceFromPointToRect(point: Offset, bounds: Rect): Float {
    val deltaX = when {
        point.x < bounds.left -> bounds.left - point.x
        point.x > bounds.right -> point.x - bounds.right
        else -> 0f
    }
    val deltaY = when {
        point.y < bounds.top -> bounds.top - point.y
        point.y > bounds.bottom -> point.y - bounds.bottom
        else -> 0f
    }
    return deltaX * deltaX + deltaY * deltaY
}

/**
 * 判断拖动手指是否进入可以切换相邻书架的左右边缘。
 *
 * 使用方法：
 * 根层每次收到有效拖动坐标时调用，并把返回值交给单一延时任务。第一页左缘和末页右缘不会继续
 * 翻页；不在有效边缘时返回0，用于取消尚未触发的等待任务。
 *
 * @param pointerInRoot 当前手指在Compose根布局中的坐标。
 * @param shelfBoundsInRoot 书架手势根层在根布局中的边界。
 * @param currentPage 当前书架页的零基索引。
 * @param pageCount 当前书架总页数。
 * @param edgeWidthPx 左右边缘触发区域的像素宽度。
 * @return 左翻返回-1，右翻返回1，无需翻页返回0。
 */
internal fun resolveEbookShelfEdgePagingDirection(
    pointerInRoot: Offset,
    shelfBoundsInRoot: Rect,
    currentPage: Int,
    pageCount: Int,
    edgeWidthPx: Float
): Int {
    if (pageCount <= 1 || shelfBoundsInRoot.width <= 0f || edgeWidthPx <= 0f) return 0
    if (pointerInRoot.y !in shelfBoundsInRoot.top..shelfBoundsInRoot.bottom) return 0
    return when {
        pointerInRoot.x <= shelfBoundsInRoot.left + edgeWidthPx && currentPage > 0 -> -1
        pointerInRoot.x >= shelfBoundsInRoot.right - edgeWidthPx && currentPage < pageCount - 1 -> 1
        else -> 0
    }
}

/**
 * 按书架容量生成稳定分页。
 *
 * @param books 已具备唯一绝对槽位的在架书籍。
 * @return 每页固定三十个可空槽位；空书架返回空列表，末尾完全空白的书架不会保留。
 */
internal fun buildEbookShelfPages(books: List<EbookBook>): List<List<EbookBook?>> {
    if (books.isEmpty()) return emptyList()
    val positionedBooks = books.filter { book -> book.shelfSlot >= 0 }
    if (positionedBooks.isEmpty()) return emptyList()
    val pageCount = positionedBooks.maxOf(EbookBook::shelfSlot) / BOOKS_PER_SHELF_PAGE + 1
    val slots = MutableList<EbookBook?>(pageCount * BOOKS_PER_SHELF_PAGE) { null }
    positionedBooks.sortedBy(EbookBook::shelfSlot).forEach { book ->
        if (book.shelfSlot in slots.indices && slots[book.shelfSlot] == null) {
            slots[book.shelfSlot] = book
        }
    }
    return slots.chunked(BOOKS_PER_SHELF_PAGE)
}

/**
 * 把指定书籍放到目标绝对槽位，供拖动手势和单元测试共同使用。
 *
 * 使用方法：
 * 长按拖动结束时传入当前全部在架书籍。目标为空时只移动当前书，原槽位保留为空；目标已有书时
 * 两本书交换槽位，其他空位和书籍完全不动。
 *
 * @param books 当前全部在架书籍及其绝对槽位。
 * @param bookId 需要移动的书籍id。
 * @param targetSlot 目标绝对零基槽位，负数时限制到0。
 * @return 按槽位排序的新列表；找不到书籍或无需移动时返回原列表。
 */
internal fun moveEbookShelfBookToSlot(
    books: List<EbookBook>,
    bookId: String,
    targetSlot: Int
): List<EbookBook> {
    val movingBook = books.firstOrNull { book -> book.id == bookId } ?: return books
    val sourceSlot = movingBook.shelfSlot
    val safeTarget = targetSlot.coerceAtLeast(0)
    if (sourceSlot < 0 || safeTarget == sourceSlot) return books.sortedBy(EbookBook::shelfSlot)
    val occupiedBookId = books.firstOrNull { book ->
        book.shelfSlot == safeTarget && book.id != bookId
    }?.id
    return books.map { book ->
        when (book.id) {
            bookId -> book.copy(shelfSlot = safeTarget, shelfOrder = safeTarget.toLong())
            occupiedBookId -> book.copy(shelfSlot = sourceSlot, shelfOrder = sourceSlot.toLong())
            else -> book
        }
    }.sortedBy(EbookBook::shelfSlot)
}

/**
 * 显示一张带自定义封面、书籍信息、阅读进度和管理操作的卡片。
 *
 * @param repository 用于安全加载已持久化封面的仓库。
 * @param book 当前书籍。
 * @param onOpen 打开阅读器回调。
 * @param onSetOnShelf 加入或移出书架回调。
 * @param onEdit 打开书籍信息、封面和书脊编辑器回调。
 * @param onDelete 打开删除确认回调。
 * @return 无返回值，直接显示书籍管理卡片。
 */
@Composable
private fun EbookBookCard(
    repository: EbookRepository,
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
                EbookCoverThumbnail(
                    repository = repository,
                    book = book,
                    modifier = Modifier.size(width = 56.dp, height = 78.dp)
                )
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
                OutlinedButton(onClick = onEdit) { Text("信息、封面与书脊") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 显示书籍自定义图片封面；未设置图片时使用书名生成简洁默认封面。
 *
 * 使用方法：
 * 全部书籍卡片和编辑弹窗共同调用。图片只在[book.coverFileName]变化时重新采样解码，避免普通
 * 重组反复读取磁盘；解码失败时自动回退默认封面，不影响书籍打开和管理。
 *
 * @param repository 负责校验文件名并缩小图片的书籍仓库。
 * @param book 当前书籍。
 * @param modifier 封面尺寸和外部布局修饰器。
 * @return 无返回值，直接绘制图片或默认封面。
 */
@Composable
private fun EbookCoverThumbnail(
    repository: EbookRepository,
    book: EbookBook,
    modifier: Modifier = Modifier
) {
    val coverBitmap: Bitmap? = remember(book.id, book.coverFileName, repository) {
        repository.loadCoverBitmap(book)
    }
    Surface(
        modifier = modifier,
        color = resolveEbookSpineColor(book),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 2.dp
    ) {
        if (coverBitmap != null) {
            Image(
                modifier = Modifier.fillMaxSize(),
                bitmap = coverBitmap.asImageBitmap(),
                contentDescription = "${book.title}自定义封面",
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = book.title.trim().take(2).ifBlank { "书籍" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFF5DF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 修改书名、作者、自定义图片封面，并通过预设或RGB滑杆自定义书架书脊颜色。
 *
 * 使用方法：
 * 从“全部书籍”点击“信息、封面与书脊”打开。用户可选择、更换或删除图片封面；选择“自动”会
 * 恢复按书籍id配色，选择预设或拖动任一RGB滑杆会生成不透明自定义颜色。
 *
 * @param repository 用于加载当前封面预览的书籍仓库。
 * @param book 当前书籍、封面及已保存颜色。
 * @param onDismiss 放弃修改的回调。
 * @param onChooseCover 打开系统图片选择器以设置或更换封面的回调。
 * @param onRemoveCover 删除自定义封面并恢复默认封面的回调。
 * @param onSave 返回新书名、作者和ARGB颜色的保存回调；颜色0表示自动。
 * @return 无返回值，直接显示可滚动编辑对话框。
 */
@Composable
private fun EbookMetadataDialog(
    repository: EbookRepository,
    book: EbookBook,
    onDismiss: () -> Unit,
    onChooseCover: () -> Unit,
    onRemoveCover: () -> Boolean,
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
        title = { Text("书籍信息、封面与书脊") },
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

                Text("图片封面", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    EbookCoverThumbnail(
                        repository = repository,
                        book = book,
                        modifier = Modifier.size(width = 82.dp, height = 112.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = onChooseCover) {
                            Text(if (book.coverFileName.isBlank()) "选择封面图片" else "更换封面图片")
                        }
                        OutlinedButton(
                            enabled = book.coverFileName.isNotBlank(),
                            onClick = { onRemoveCover() }
                        ) {
                            Text("恢复默认封面")
                        }
                    }
                }
                Text(
                    "支持系统能够识别的常见图片，图片会复制到App本地，原相册图片移动或删除后仍可显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
 * @param onReadingDuration 阅读器位于前台期间按段上报的有效阅读毫秒数。
 * @param onBack 返回书架并刷新目录的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EbookReader(
    book: EbookBook,
    repository: EbookRepository,
    onImmersiveChanged: (Boolean) -> Unit,
    onReadingDuration: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val density = LocalDensity.current
    val latestImmersiveChanged by rememberUpdatedState(onImmersiveChanged)
    val latestReadingDuration by rememberUpdatedState(onReadingDuration)
    val readingStartedAtMillis = remember(book.id) { AtomicLong(0L) }
    var paginationViewportSize by remember(book.id) { mutableStateOf(IntSize.Zero) }
    var extractedText by remember(book.id) { mutableStateOf("") }
    var textLoaded by remember(book.id) { mutableStateOf(book.format == EbookFormat.PDF) }
    var measuredTextPages by remember(book.id) {
        mutableStateOf<List<EbookMeasuredTextPage>>(emptyList())
    }
    var paginationReady by remember(book.id) {
        mutableStateOf(book.format == EbookFormat.PDF)
    }
    var paginationProgress by remember(book.id) {
        mutableStateOf(if (book.format == EbookFormat.PDF) 1f else 0f)
    }
    var currentPage by remember(book.id) {
        mutableIntStateOf(book.currentPage.coerceIn(0, book.pageCount.coerceAtLeast(1) - 1))
    }
    var currentTextOffset by remember(book.id) {
        mutableIntStateOf(book.currentTextOffset.coerceAtLeast(0))
    }
    var readingModeName by remember(book.id) { mutableStateOf(book.readingMode.name) }
    var readingBackgroundName by remember(book.id) {
        mutableStateOf(book.readingBackground.name)
    }
    var fontScale by remember(book.id) { mutableStateOf(book.fontScale) }
    var fontFamilyName by remember(book.id) { mutableStateOf(book.fontFamily.name) }
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
    var isTimedAutoPageTurning by rememberSaveable(book.id) { mutableStateOf(false) }
    var autoPageIntervalSeconds by rememberSaveable(book.id) {
        mutableIntStateOf(DEFAULT_EBOOK_AUTO_PAGE_INTERVAL_SECONDS)
    }
    var naturalReadingEnabled by rememberSaveable(book.id) { mutableStateOf(true) }
    var isReaderForeground by remember(book.id) { mutableStateOf(false) }
    var isPageScrollInProgress by remember(book.id) { mutableStateOf(false) }
    var settledPage by remember(book.id) { mutableIntStateOf(currentPage) }
    var readAloudStateName by remember(book.id) {
        mutableStateOf(EbookReadAloudState.INITIALIZING.name)
    }
    var showReadAloudDialog by rememberSaveable(book.id) { mutableStateOf(false) }
    var readAloudLaunchMessage by remember(book.id) { mutableStateOf("") }
    var startReadAloudAfterNotificationPermission by remember(book.id) {
        mutableStateOf(false)
    }
    var notificationPermissionGranted by remember(book.id) {
        mutableStateOf(hasEbookReadAloudNotificationPermission(context))
    }
    var speechRate by remember(book.id) {
        mutableStateOf(EbookReadAloudController.DEFAULT_SPEECH_RATE)
    }
    var selectedVoiceName by remember(book.id) { mutableStateOf("") }
    val noteRepository = remember(book.id) { EbookNoteRepository(context.applicationContext) }
    var ebookNotes by remember(book.id) { mutableStateOf(noteRepository.getBookNotes(book)) }
    val translator = remember(book.id) { EbookOfflineTranslator() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        if (!granted) {
            startReadAloudAfterNotificationPermission = false
            readAloudLaunchMessage = "需要通知权限才能显示后台朗读控制栏"
        }
    }
    val readAloudSettingsController = remember(book.id) {
        EbookReadAloudController(
            context = context,
            onStateChanged = { state -> readAloudStateName = state.name },
            onSpeakingChanged = {},
            onUtteranceRangeChanged = { _, _, _ -> },
            onUtteranceFinished = {},
            onUtteranceFailed = {}
        )
    }
    val playbackSnapshot by EbookReadAloudPlayback.snapshot.collectAsState()
    val currentBookPlayback = playbackSnapshot.takeIf { snapshot ->
        snapshot.bookId == book.id &&
            snapshot.status != EbookReadAloudPlaybackStatus.IDLE &&
            snapshot.status != EbookReadAloudPlaybackStatus.STOPPED
    }
    val isReadAloudSessionActive = currentBookPlayback != null
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

    // 只统计阅读器所在Activity处于RESUMED状态的单调时钟时间。锁屏、切后台和离开阅读器都会
    // 立即结算当前片段；无法取得Activity时宁可不计时，也不能把后台停留误算成阅读。
    DisposableEffect(book.id, activity) {
        fun beginReadingIfNeeded() {
            readingStartedAtMillis.compareAndSet(0L, SystemClock.elapsedRealtime())
        }

        fun flushReadingDuration() {
            val startedAtMillis = readingStartedAtMillis.getAndSet(0L)
            if (startedAtMillis <= 0L) return
            val elapsedMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            if (elapsedMillis > 0L) latestReadingDuration(elapsedMillis)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isReaderForeground = true
                    beginReadingIfNeeded()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    isReaderForeground = false
                    flushReadingDuration()
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        if (activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            isReaderForeground = true
            beginReadingIfNeeded()
        }

        onDispose {
            isReaderForeground = false
            activity?.lifecycle?.removeObserver(observer)
            flushReadingDuration()
        }
    }

    // 阅读时间每分钟落盘一次，兼顾首页成长进度及时刷新和SharedPreferences写入频率。
    LaunchedEffect(book.id, activity) {
        while (true) {
            delay(EBOOK_READING_REPORT_INTERVAL_MILLIS)
            val startedAtMillis = readingStartedAtMillis.get()
            if (startedAtMillis <= 0L) continue

            val nowMillis = SystemClock.elapsedRealtime()
            if (readingStartedAtMillis.compareAndSet(startedAtMillis, nowMillis)) {
                val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
                if (elapsedMillis > 0L) latestReadingDuration(elapsedMillis)
            }
        }
    }

    DisposableEffect(book.id) {
        onDispose {
            latestImmersiveChanged(false)
            translator.close()
            // 页面只释放用于音色设置的空闲控制器；真正发声的前台服务继续存活并保持播放。
            readAloudSettingsController.shutdown()
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

    val pageHorizontalPaddingPx = with(density) {
        (READER_PAGE_HORIZONTAL_PADDING * 2).roundToPx()
    }
    val pageVerticalInsetsPx = with(density) {
        (READER_TOP_TEXT_INSET + READER_BOTTOM_TEXT_INSET).roundToPx()
    }
    val pageContentWidthPx = (paginationViewportSize.width - pageHorizontalPaddingPx)
        .coerceAtLeast(0)
    val pageContentHeightPx = (paginationViewportSize.height - pageVerticalInsetsPx)
        .coerceAtLeast(0)

    // 正文、字号、字体或可用区域变化后在后台重新排版。大体积MOBI不会阻塞Compose主线程。
    LaunchedEffect(
        book.id,
        book.format,
        extractedText,
        textLoaded,
        pageContentWidthPx,
        pageContentHeightPx,
        fontScale,
        fontFamily,
        density.density,
        density.fontScale
    ) {
        if (book.format == EbookFormat.PDF) {
            paginationProgress = 1f
            paginationReady = true
            return@LaunchedEffect
        }
        if (
            !textLoaded ||
            pageContentWidthPx <= 0 ||
            pageContentHeightPx <= 0
        ) {
            paginationProgress = 0f
            paginationReady = false
            return@LaunchedEffect
        }

        paginationProgress = 0f
        // 已经有可读页面时继续展示旧分页，新的屏幕或字体排版只在后台替换，不能把正文重新切回等待页。
        paginationReady = measuredTextPages.isNotEmpty()
        val fontSizePx = with(density) { (READER_BASE_FONT_SIZE_SP * fontScale).sp.toPx() }
        val lineHeightPx = with(density) { (READER_BASE_LINE_HEIGHT_SP * fontScale).sp.toPx() }
        val cacheSpec = EbookPaginationCacheSpec(
            bookId = book.id,
            textLength = extractedText.length,
            contentWidthPx = pageContentWidthPx,
            contentHeightPx = pageContentHeightPx,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            fontFamily = fontFamily
        )
        val cachedBoundaries = repository.loadPaginationBoundaries(cacheSpec)
        if (cachedBoundaries != null) {
            val cachedPages = withContext(Dispatchers.Default) {
                restoreMeasuredEbookPagesFromBoundaries(extractedText, cachedBoundaries)
            }
            if (cachedPages.isNotEmpty()) {
                measuredTextPages = cachedPages
                paginationProgress = 1f
                paginationReady = true
                return@LaunchedEffect
            }
        }

        val calculatedPages = withContext(Dispatchers.Default) {
            paginateEbookTextToViewport(
                text = extractedText,
                contentWidthPx = pageContentWidthPx,
                contentHeightPx = pageContentHeightPx,
                fontSizePx = fontSizePx,
                lineHeightPx = lineHeightPx,
                fontFamily = fontFamily,
                onProgress = { progress -> paginationProgress = progress }
            )
        }
        measuredTextPages = calculatedPages
        paginationProgress = 1f
        paginationReady = true
        repository.savePaginationBoundaries(
            spec = cacheSpec,
            boundaries = measuredEbookPagesToBoundaries(calculatedPages)
        )
    }

    val textPages = remember(measuredTextPages) {
        measuredTextPages.map(EbookMeasuredTextPage::text)
    }
    val readerTextReady = textLoaded && (book.format == EbookFormat.PDF || paginationReady)
    val readerDisplayReady = readerTextReady ||
        (book.format != EbookFormat.PDF && book.currentPagePreview.isNotBlank())
    var chapters by remember(book.id) { mutableStateOf<List<EbookChapter>>(emptyList()) }
    LaunchedEffect(book.id, book.format, measuredTextPages) {
        chapters = if (book.format == EbookFormat.PDF || measuredTextPages.isEmpty()) {
            emptyList()
        } else {
            // 大型小说可能包含数十万行，目录识别必须离开Compose主线程，避免分页完成瞬间冻结翻页按钮。
            withContext(Dispatchers.Default) {
                buildEbookTableOfContents(textPages)
            }
        }
    }
    val pageCount = resolveEbookReaderPageCount(
        format = book.format,
        savedPageCount = book.pageCount,
        textLoaded = readerTextReady,
        loadedTextPageCount = textPages.size
    )

    // 旧版本笔记只保存页码。真实屏幕分页后根据摘录在完整正文中的位置重新定位，避免字体或屏幕
    // 尺寸变化导致页边标记、笔记列表跳转仍停留在旧页码。
    var displayedEbookNotes by remember(book.id) { mutableStateOf(ebookNotes) }
    LaunchedEffect(
        book.id,
        book.format,
        ebookNotes,
        measuredTextPages,
        extractedText
    ) {
        displayedEbookNotes = if (book.format == EbookFormat.PDF || measuredTextPages.isEmpty()) {
            ebookNotes
        } else {
            // 每条旧笔记都可能检索完整正文，放到后台集中重定位，避免笔记较多时再次占用触摸主线程。
            withContext(Dispatchers.Default) {
                ebookNotes.map { note ->
                    note.copy(
                        pageIndex = findEbookPageIndexForExcerpt(
                            pages = measuredTextPages,
                            fullText = extractedText,
                            excerpt = note.excerpt,
                            fallbackPageIndex = note.pageIndex
                        )
                    )
                }
            }
        }
    }

    // 字号、字体或屏幕尺寸改变后，按正文字符位置找回原来的段落，而不是继续使用已经失效的页码。
    LaunchedEffect(book.id, book.format, readerTextReady, measuredTextPages) {
        if (book.format != EbookFormat.PDF && readerTextReady) {
            currentPage = findEbookPageIndexForOffset(
                pages = measuredTextPages,
                textOffset = currentTextOffset
            )
        }
    }

    // 用户真正翻到新页后更新字符锚点；若后台服务正在朗读本页，则优先采用服务的安全词句
    // 断点，避免页面重建或退出保存时又把精确位置覆盖为页首。
    LaunchedEffect(
        book.id,
        book.format,
        currentPage,
        currentBookPlayback?.currentPage,
        currentBookPlayback?.resumeOffset,
        currentBookPlayback?.spokenText
    ) {
        if (book.format != EbookFormat.PDF && paginationReady) {
            measuredTextPages.getOrNull(currentPage)?.let { page ->
                val playbackPageOffset = currentBookPlayback
                    ?.takeIf { snapshot ->
                        snapshot.currentPage == currentPage && snapshot.spokenText == page.text
                    }
                    ?.resumeOffset
                    ?.coerceIn(0, page.text.length)
                    ?: 0
                currentTextOffset = (page.startOffset + playbackPageOffset).coerceIn(
                    page.startOffset,
                    page.endOffset
                )
            }
        }
    }

    // 等真实正文页数准备完成后再修正边界，不能用加载中的临时页数覆盖上次阅读位置。
    LaunchedEffect(book.id, book.format, readerTextReady, pageCount) {
        if (book.format == EbookFormat.PDF || readerTextReady) {
            currentPage = currentPage.coerceIn(0, pageCount - 1)
        }
    }

    val latestProgressSnapshot by rememberUpdatedState(
        EbookReadingProgressSnapshot(
            bookId = book.id,
            currentPage = currentPage,
            currentTextOffset = if (book.format == EbookFormat.PDF) 0 else currentTextOffset,
            currentPagePreview = if (book.format == EbookFormat.PDF) {
                ""
            } else {
                measuredTextPages.getOrNull(currentPage)?.text ?: book.currentPagePreview
            },
            pageCount = pageCount,
            readingMode = readingMode,
            readingBackground = readingBackground,
            fontScale = fontScale,
            fontFamily = fontFamily,
            readyToSave = book.format == EbookFormat.PDF || readerTextReady
        )
    )
    val latestReadAloudSessionActive by rememberUpdatedState(isReadAloudSessionActive)

    // 退出阅读器时补足350毫秒防抖任务可能被页面销毁取消的时间窗口。后台朗读仍存活时，服务是
    // 唯一页码写入者，页面只合并阅读设置，避免刚推进的新断点被退出瞬间捕获的旧Compose页码覆盖。
    DisposableEffect(book.id, repository) {
        onDispose {
            val snapshot = latestProgressSnapshot
            if (snapshot.readyToSave) {
                val saved = if (latestReadAloudSessionActive) {
                    repository.saveReadingSettings(
                        bookId = snapshot.bookId,
                        readingMode = snapshot.readingMode,
                        readingBackground = snapshot.readingBackground,
                        fontScale = snapshot.fontScale,
                        fontFamily = snapshot.fontFamily
                    )
                } else {
                    repository.saveReadingProgress(
                        bookId = snapshot.bookId,
                        currentPage = snapshot.currentPage,
                        currentTextOffset = snapshot.currentTextOffset,
                        currentPagePreview = snapshot.currentPagePreview,
                        pageCount = snapshot.pageCount,
                        readingMode = snapshot.readingMode,
                        readingBackground = snapshot.readingBackground,
                        fontScale = snapshot.fontScale,
                        fontFamily = snapshot.fontFamily
                    )
                }
                if (!saved) {
                    Log.w(EBOOK_SCREEN_TAG, "Failed to flush ebook reading progress on reader exit")
                }
            }
        }
    }

    val currentChapter = chapters.lastOrNull { chapter -> chapter.pageIndex <= currentPage }
    val originalPageText = if (book.format == EbookFormat.PDF) {
        ""
    } else {
        textPages.getOrElse(currentPage) {
            book.currentPagePreview.ifBlank { "没有可显示的正文" }
        }
    }
    val serviceSpokenTextForCurrentPage = currentBookPlayback
        ?.takeIf { snapshot -> snapshot.currentPage == currentPage }
        ?.spokenText
        .orEmpty()
    val displayedPageText = when (translationDisplayMode) {
        EbookTranslationDisplayMode.ORIGINAL -> originalPageText
        EbookTranslationDisplayMode.BILINGUAL -> {
            if (translatedText.isBlank()) {
                originalPageText
            } else {
                "$originalPageText\n\n—— 离线译文 ——\n\n$translatedText"
            }
        }
        EbookTranslationDisplayMode.TRANSLATED -> {
            serviceSpokenTextForCurrentPage.ifBlank {
                translatedText.ifBlank { originalPageText }
            }
        }
    }
    val readAloudRange = currentBookPlayback
        ?.takeIf { snapshot ->
            snapshot.currentPage == currentPage &&
                snapshot.highlightStart >= 0 &&
                snapshot.highlightEnd > snapshot.highlightStart &&
                snapshot.highlightEnd <= displayedPageText.length
        }
        ?.let { snapshot ->
            EbookSpeechTextRange(
                startOffset = snapshot.highlightStart,
                endOffsetExclusive = snapshot.highlightEnd
            )
        }
    val readAloudLanguageCode = when {
        translationDisplayMode == EbookTranslationDisplayMode.TRANSLATED &&
            translationDirection == EbookTranslationDirection.ENGLISH_TO_CHINESE -> Locale.CHINESE.language
        translationDisplayMode == EbookTranslationDisplayMode.TRANSLATED -> Locale.ENGLISH.language
        else -> detectEbookLanguageCode(originalPageText)
    }
    val readerOverlayVisible = showReaderSettings ||
        showTableOfContents ||
        showBookNotes ||
        pendingNoteDraft != null ||
        showJumpDialog ||
        showTranslationDialog ||
        showReadAloudDialog

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
        speechRate = readAloudSettingsController.speechRate()
        if (showReadAloudDialog && readAloudState == EbookReadAloudState.READY) {
            selectedVoiceName = readAloudSettingsController.selectedVoiceName(
                readAloudLanguageCode
            )
        }
    }

    // 服务是后台朗读页码和词句断点的唯一事实源。通知栏、锁屏或耳机切页后，即使阅读页面之前
    // 已离开，重新进入也会先同步到服务当前页，再由Pager完成可见页面动画。
    LaunchedEffect(
        currentBookPlayback?.currentPage,
        currentBookPlayback?.resumeOffset,
        readerTextReady,
        measuredTextPages
    ) {
        val snapshot = currentBookPlayback ?: return@LaunchedEffect
        if (!readerTextReady || book.format == EbookFormat.PDF) return@LaunchedEffect
        val page = measuredTextPages.getOrNull(snapshot.currentPage) ?: return@LaunchedEffect
        currentPage = snapshot.currentPage
        val originalPageResumeOffset = if (snapshot.spokenText == page.text) {
            snapshot.resumeOffset.coerceIn(0, page.text.length)
        } else {
            0
        }
        currentTextOffset = (page.startOffset + originalPageResumeOffset).coerceIn(
            page.startOffset,
            page.endOffset
        )
    }

    // 页面上的按钮、手势、目录和笔记仍先改变Compose页码；页面动画停稳后把同一目标页交给
    // 服务。服务会原子作废旧TTS代际，并保持原来的播放或暂停意图，避免两页声音交叉。
    LaunchedEffect(
        currentPage,
        settledPage,
        isPageScrollInProgress,
        currentBookPlayback?.currentPage
    ) {
        val snapshot = currentBookPlayback ?: return@LaunchedEffect
        if (
            !isPageScrollInProgress &&
            settledPage == currentPage &&
            snapshot.currentPage != currentPage
        ) {
            if (!EbookReadAloudService.seekTo(context, currentPage)) {
                readAloudLaunchMessage = "无法把后台朗读切换到当前页，请停止后重新开始"
            }
        }
    }

    val startReadAloud: () -> Unit = {
        if (book.format == EbookFormat.PDF || measuredTextPages.isEmpty()) {
            readAloudLaunchMessage = "当前书籍还没有可供连续朗读的分页正文"
        } else {
            readAloudLaunchMessage = ""
            val spokenTextUsesOriginal =
                translationDisplayMode != EbookTranslationDisplayMode.TRANSLATED
            val config = EbookReadAloudConfig(
                bookId = book.id,
                title = book.title,
                author = book.author,
                pages = measuredTextPages.map { page -> EbookReadAloudPage(page.text) },
                initialPage = currentPage,
                naturalReadingEnabled = naturalReadingEnabled,
                translationDirection = if (spokenTextUsesOriginal) null else translationDirection
            )
            val progressCallback = EbookReadAloudRepositoryProgressCallback(
                repository = repository,
                bookId = book.id,
                pages = measuredTextPages.toList(),
                spokenTextUsesOriginal = spokenTextUsesOriginal
            )
            val token = runCatching {
                EbookReadAloudPlayback.register(config, progressCallback)
            }.onFailure { error ->
                Log.e(EBOOK_SCREEN_TAG, "Failed to register ebook read-aloud session", error)
            }.getOrNull()
            if (token == null || !EbookReadAloudService.start(context, token)) {
                token?.let(EbookReadAloudPlayback::unregister)
                readAloudLaunchMessage = "无法启动后台朗读，请检查通知权限后重试"
            }
        }
    }

    val requestPermissionOrStartReadAloud: () -> Unit = {
        if (hasEbookReadAloudNotificationPermission(context)) {
            notificationPermissionGranted = true
            startReadAloud()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startReadAloudAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 权限弹窗返回后再启动前台服务，确保第一张媒体通知从建立时就对用户可见并可控制。
    LaunchedEffect(
        startReadAloudAfterNotificationPermission,
        notificationPermissionGranted
    ) {
        if (startReadAloudAfterNotificationPermission && notificationPermissionGranted) {
            startReadAloudAfterNotificationPermission = false
            startReadAloud()
        }
    }

    val toggleReadAloud: () -> Unit = {
        readAloudLaunchMessage = ""
        val commandAccepted = when (currentBookPlayback?.status) {
            EbookReadAloudPlaybackStatus.PREPARING,
            EbookReadAloudPlaybackStatus.PLAYING -> EbookReadAloudService.pause(context)

            EbookReadAloudPlaybackStatus.PAUSED,
            EbookReadAloudPlaybackStatus.COMPLETED,
            EbookReadAloudPlaybackStatus.ERROR -> EbookReadAloudService.play(context)

            EbookReadAloudPlaybackStatus.IDLE,
            EbookReadAloudPlaybackStatus.STOPPED,
            null -> {
                requestPermissionOrStartReadAloud()
                true
            }
        }
        if (!commandAccepted) {
            readAloudLaunchMessage = "后台朗读控制失败，请停止后重新开始"
        }
    }

    val stopReadAloud: () -> Unit = {
        readAloudLaunchMessage = ""
        if (!EbookReadAloudService.stop(context)) {
            readAloudLaunchMessage = "后台朗读停止失败，请稍后重试"
        }
    }

    // 普通自动翻页与连续朗读互斥：TTS开启时由整页完成回调翻页，定时器完全不运行。任何弹层、
    // 后台状态或尚未完成的页面动画都会取消当前倒计时，恢复可读状态后重新按完整间隔计时。
    LaunchedEffect(
        isTimedAutoPageTurning,
        autoPageIntervalSeconds,
        isReadAloudSessionActive,
        isReaderForeground,
        readerOverlayVisible,
        isPageScrollInProgress,
        readerDisplayReady,
        currentPage,
        settledPage,
        pageCount
    ) {
        val canTurnPage = shouldScheduleEbookTimedPageTurn(
            enabled = isTimedAutoPageTurning,
            continuousReading = isReadAloudSessionActive,
            readerForeground = isReaderForeground,
            overlayVisible = readerOverlayVisible,
            pageScrollInProgress = isPageScrollInProgress,
            contentReady = readerDisplayReady && settledPage == currentPage,
            currentPage = currentPage,
            pageCount = pageCount
        )
        if (!canTurnPage) return@LaunchedEffect

        delay(autoPageIntervalSeconds * 1_000L)
        resolveNextEbookAutomaticPage(currentPage, pageCount)?.let { nextPage ->
            currentPage = nextPage
        }
    }

    // 到达书末后关闭运行开关，避免设置面板仍显示“正在自动翻页”却没有后续页面。
    LaunchedEffect(isTimedAutoPageTurning, readerDisplayReady, currentPage, pageCount) {
        if (
            isTimedAutoPageTurning &&
            readerDisplayReady &&
            resolveNextEbookAutomaticPage(currentPage, pageCount) == null
        ) {
            isTimedAutoPageTurning = false
        }
    }

    // 短暂合并连续翻页和字号点击，避免每个触摸事件都同步写目录。
    LaunchedEffect(
        book.id,
        currentPage,
        currentTextOffset,
        pageCount,
        readingMode,
        readingBackground,
        fontScale,
        fontFamily,
        isReadAloudSessionActive
    ) {
        if (book.format != EbookFormat.PDF && !readerTextReady) return@LaunchedEffect
        delay(PROGRESS_SAVE_DEBOUNCE_MILLIS)
        withContext(Dispatchers.IO) {
            if (isReadAloudSessionActive) {
                // 活跃朗读会话的字符断点由服务保存；此处只合并界面设置。
                repository.saveReadingSettings(
                    bookId = book.id,
                    readingMode = readingMode,
                    readingBackground = readingBackground,
                    fontScale = fontScale,
                    fontFamily = fontFamily
                )
            } else {
                repository.saveReadingProgress(
                    bookId = book.id,
                    currentPage = currentPage,
                    currentTextOffset = if (book.format == EbookFormat.PDF) 0 else currentTextOffset,
                    currentPagePreview = if (book.format == EbookFormat.PDF) "" else originalPageText,
                    pageCount = pageCount,
                    readingMode = readingMode,
                    readingBackground = readingBackground,
                    fontScale = fontScale,
                    fontFamily = fontFamily
                )
            }
        }
    }

    // 沉浸模式下系统返回键先恢复工具栏，避免用户误触直接退出书籍。
    BackHandler(enabled = !controlsVisible) {
        controlsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                paginationViewportSize = resolveEbookPaginationViewportSize(
                    previousSize = paginationViewportSize,
                    measuredSize = size,
                    retainHeightOnlyChange = !controlsVisible
                )
            }
            .background(readerPalette.background)
    ) {
        if (!readerDisplayReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (textLoaded) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(0.65f),
                            progress = { paginationProgress.coerceIn(0f, 1f) }
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.65f))
                    }
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = if (textLoaded) {
                            "正在按当前屏幕和字号重新分页… " +
                                "${(paginationProgress * 100f).roundToInt()}%"
                        } else {
                            "正在准备离线正文…"
                        },
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
                noteCountsByPage = displayedEbookNotes.groupingBy(EbookNote::pageIndex).eachCount(),
                readAloudRange = readAloudRange,
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
                onPageChanged = { page -> currentPage = page.coerceIn(0, pageCount - 1) },
                onPageScrollStateChanged = { scrolling ->
                    isPageScrollInProgress = scrolling
                },
                onPageSettled = { page ->
                    settledPage = page.coerceIn(0, pageCount - 1)
                }
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.TopCenter),
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { height -> -height },
            exit = fadeOut() + slideOutVertically { height -> -height }
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(READER_TOP_BAR_HEIGHT),
                color = readerPalette.control.copy(alpha = 0.97f),
                tonalElevation = 5.dp,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            isTimedAutoPageTurning = false
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(READER_BOTTOM_BAR_HEIGHT),
                color = readerPalette.control.copy(alpha = 0.94f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = currentPage > 0,
                            onClick = { currentPage -= 1 }
                        ) {
                            Text("‹ 上页", color = readerPalette.controlText)
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
                            Text("下页 ›", color = readerPalette.controlText)
                        }
                    }

                    val translationStatus = if (
                        translationDisplayMode != EbookTranslationDisplayMode.ORIGINAL
                    ) {
                        when (translationStage) {
                            EbookTranslationStage.IDLE -> "等待离线翻译"
                            EbookTranslationStage.PREPARING_MODEL -> "正在准备离线语言模型…"
                            EbookTranslationStage.TRANSLATING -> "正在手机本地翻译本页…"
                            EbookTranslationStage.READY -> "离线译文已就绪"
                            EbookTranslationStage.ERROR -> translationMessage.ifBlank { "离线翻译失败" }
                        }
                    } else {
                        null
                    }
                    val readingAutomationStatus = when {
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.PAUSED ->
                            "后台朗读已暂停 · 可从通知栏继续"
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.PREPARING ->
                            "正在准备后台朗读…"
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.PLAYING ->
                            "后台连续朗读 · 当前词句背景高亮"
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.COMPLETED ->
                            "本书已朗读完成"
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.ERROR ->
                            currentBookPlayback.error ?: "后台朗读发生错误"
                        readAloudLaunchMessage.isNotBlank() -> readAloudLaunchMessage
                        isTimedAutoPageTurning -> "自动翻页 · ${autoPageIntervalSeconds}秒/页"
                        else -> null
                    }
                    val footerColor = if (
                        translationStage == EbookTranslationStage.ERROR ||
                        currentBookPlayback?.status == EbookReadAloudPlaybackStatus.ERROR ||
                        readAloudLaunchMessage.isNotBlank()
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        readerPalette.controlText.copy(alpha = 0.74f)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = readingAutomationStatus
                                ?: translationStatus
                                ?: currentChapter?.title
                                ?: "轻触正文进入沉浸阅读",
                            color = footerColor,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displayedEbookNotes.isNotEmpty()) {
                            val currentPageNoteCount = displayedEbookNotes.count { note ->
                                note.pageIndex == currentPage
                            }
                            Text(
                                modifier = Modifier
                                    .clickable { showBookNotes = true }
                                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                text = if (currentPageNoteCount > 0) {
                                    "笔记 $currentPageNoteCount/${displayedEbookNotes.size}"
                                } else {
                                    "笔记 ${displayedEbookNotes.size}"
                                },
                                color = readerPalette.controlText,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
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
            noteCount = displayedEbookNotes.size,
            translationDisplayMode = translationDisplayMode,
            readAloudReady = readAloudState == EbookReadAloudState.READY,
            readAloudPlaybackStatus = currentBookPlayback?.status,
            isTimedAutoPageTurning = isTimedAutoPageTurning,
            autoPageIntervalSeconds = autoPageIntervalSeconds,
            naturalReadingEnabled = naturalReadingEnabled,
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
            onTimedAutoPageTurningChanged = { enabled ->
                isTimedAutoPageTurning = enabled
            },
            onAutoPageIntervalChanged = { seconds ->
                autoPageIntervalSeconds = seconds.coerceIn(
                    MIN_EBOOK_AUTO_PAGE_INTERVAL_SECONDS,
                    MAX_EBOOK_AUTO_PAGE_INTERVAL_SECONDS
                )
            },
            onToggleReadAloud = toggleReadAloud,
            onStopReadAloud = stopReadAloud,
            onNaturalReadingChanged = { enabled ->
                naturalReadingEnabled = enabled
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
            notes = displayedEbookNotes,
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
            onDirectionChanged = { direction ->
                if (isReadAloudSessionActive) stopReadAloud()
                translationDirectionName = direction.name
            },
            onDisplayModeChanged = { mode ->
                if (isReadAloudSessionActive) stopReadAloud()
                translationDisplayModeName = mode.name
            },
            onDismiss = { showTranslationDialog = false }
        )
    }

    if (showReadAloudDialog) {
        val voiceOptions = readAloudSettingsController.availableVoices(readAloudLanguageCode)
        EbookReadAloudDialog(
            state = readAloudState,
            languageCode = readAloudLanguageCode,
            voices = voiceOptions,
            selectedVoiceName = selectedVoiceName,
            speechRate = speechRate,
            onVoiceSelected = { voiceName ->
                if (readAloudSettingsController.selectVoice(readAloudLanguageCode, voiceName)) {
                    selectedVoiceName = voiceName
                }
            },
            onSpeechRateChanged = { rate -> speechRate = rate },
            onSpeechRateChangeFinished = {
                readAloudSettingsController.setSpeechRate(speechRate)
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
    val controlText: Color,
    val readAloudHighlight: Color
)

/**
 * 把用户选择的阅读背景转换为正文和浮动工具栏颜色。
 *
 * 使用方法：
 * 阅读器根据已保存的[EbookReadingBackground]调用本函数，并把返回值传给正文页和工具栏。
 *
 * @param background 当前阅读背景选项。
 * @return 完整的阅读器背景、文字、控制栏、控制栏文字和朗读范围背景颜色。
 */
private fun ebookReaderPalette(background: EbookReadingBackground): EbookReaderPalette {
    return when (background) {
        EbookReadingBackground.PAPER -> EbookReaderPalette(
            background = Color(0xFFFFFAEF),
            text = Color(0xFF2D281F),
            control = Color(0xFFF2E9D5),
            controlText = Color(0xFF332B20),
            readAloudHighlight = Color(0xB3FFD45A)
        )
        EbookReadingBackground.WARM -> EbookReaderPalette(
            background = Color(0xFFF1DEB7),
            text = Color(0xFF3A2A1C),
            control = Color(0xFFDEBF8B),
            controlText = Color(0xFF372515),
            readAloudHighlight = Color(0xB3F6B94B)
        )
        EbookReadingBackground.GREEN -> EbookReaderPalette(
            background = Color(0xFFDDE8D6),
            text = Color(0xFF263328),
            control = Color(0xFFC5D6BD),
            controlText = Color(0xFF233026),
            readAloudHighlight = Color(0xB39ECE79)
        )
        EbookReadingBackground.NIGHT -> EbookReaderPalette(
            background = Color(0xFF111318),
            text = Color(0xFFD8DAE0),
            control = Color(0xFF22262E),
            controlText = Color(0xFFF1F2F5),
            readAloudHighlight = Color(0xCC695426)
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
 * @param readAloudPlaybackStatus 当前书籍的后台朗读状态；null表示没有活动会话。
 * @param isTimedAutoPageTurning 当前是否开启按固定间隔自动翻页。
 * @param autoPageIntervalSeconds 定时自动翻页的单页停留秒数。
 * @param naturalReadingEnabled 是否按标点分句并应用轻微停顿、语速和音高变化。
 * @param onReadingModeChanged 翻页模式变化回调。
 * @param onReadingBackgroundChanged 阅读背景变化回调。
 * @param onFontScaleChanged 字号变化回调。
 * @param onFontFamilyChanged 字体变化回调。
 * @param onOpenNotes 打开本书笔记列表的回调。
 * @param onOpenTableOfContents 打开目录或页码列表的回调。
 * @param onJump 打开跳页窗口的回调。
 * @param onTranslate 打开离线翻译设置的回调。
 * @param onTimedAutoPageTurningChanged 开启或关闭定时自动翻页的回调。
 * @param onAutoPageIntervalChanged 修改单页停留秒数的回调。
 * @param onToggleReadAloud 按当前状态开始、暂停、继续或重试朗读的回调。
 * @param onStopReadAloud 显式结束前台服务并移除媒体通知的回调。
 * @param onNaturalReadingChanged 开启或关闭自然朗读的回调。
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
    readAloudPlaybackStatus: EbookReadAloudPlaybackStatus?,
    isTimedAutoPageTurning: Boolean,
    autoPageIntervalSeconds: Int,
    naturalReadingEnabled: Boolean,
    onReadingModeChanged: (EbookReadingMode) -> Unit,
    onReadingBackgroundChanged: (EbookReadingBackground) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onFontFamilyChanged: (EbookFontFamily) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTableOfContents: () -> Unit,
    onJump: () -> Unit,
    onTranslate: () -> Unit,
    onTimedAutoPageTurningChanged: (Boolean) -> Unit,
    onAutoPageIntervalChanged: (Int) -> Unit,
    onToggleReadAloud: () -> Unit,
    onStopReadAloud: () -> Unit,
    onNaturalReadingChanged: (Boolean) -> Unit,
    onReadAloudSettings: () -> Unit,
    onEnterImmersive: () -> Unit,
    onDismiss: () -> Unit
) {
    val isReadAloudSessionActive = readAloudPlaybackStatus != null
    val readAloudActionLabel = when (readAloudPlaybackStatus) {
        EbookReadAloudPlaybackStatus.PREPARING,
        EbookReadAloudPlaybackStatus.PLAYING -> "暂停朗读"
        EbookReadAloudPlaybackStatus.PAUSED -> "继续朗读"
        EbookReadAloudPlaybackStatus.COMPLETED -> "重读末页"
        EbookReadAloudPlaybackStatus.ERROR -> "重试朗读"
        EbookReadAloudPlaybackStatus.IDLE,
        EbookReadAloudPlaybackStatus.STOPPED,
        null -> "连续朗读"
    }
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

            Text("自动翻页", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isTimedAutoPageTurning) "正在自动翻页" else "定时自动翻页")
                    Text(
                        "连续朗读时自动暂停计时，改由本页朗读完成后翻页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isTimedAutoPageTurning,
                    onCheckedChange = onTimedAutoPageTurningChanged
                )
            }
            Text(
                text = "每页停留：${autoPageIntervalSeconds}秒",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = autoPageIntervalSeconds.toFloat(),
                onValueChange = { seconds -> onAutoPageIntervalChanged(seconds.roundToInt()) },
                valueRange = MIN_EBOOK_AUTO_PAGE_INTERVAL_SECONDS.toFloat()..
                    MAX_EBOOK_AUTO_PAGE_INTERVAL_SECONDS.toFloat(),
                steps = EBOOK_AUTO_PAGE_INTERVAL_SLIDER_STEPS
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = book.format != EbookFormat.PDF &&
                        (readAloudReady || isReadAloudSessionActive),
                    onClick = onToggleReadAloud
                ) {
                    Text(readAloudActionLabel)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = book.format != EbookFormat.PDF,
                    onClick = onReadAloudSettings
                ) {
                    Text("音色与速度")
                }
            }
            if (isReadAloudSessionActive) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStopReadAloud
                ) {
                    Text("停止朗读并移除通知")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自然朗读", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isReadAloudSessionActive) {
                            "请先停止当前朗读再切换；开启后会按标点分句并加入自然停顿与轻微语调变化。"
                        } else {
                            "按标点和段落分句，加入自然停顿与轻微语调变化；实际效果取决于系统离线音色。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = naturalReadingEnabled,
                    enabled = book.format != EbookFormat.PDF && !isReadAloudSessionActive,
                    onCheckedChange = onNaturalReadingChanged
                )
            }
            Text(
                "离开阅读页或切到后台不会暂停；通知栏和锁屏可上一页、暂停/继续、下一页和停止。" +
                    "正文会用背景色标出当前词句，系统音色不提供逐词位置时则高亮当前句。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
 * 使用方法：
 * [EbookReader]把唯一页码状态和本页朗读范围传入。三种Pager会把用户滑动页码回传，并在滚动开始、
 * 停止时分别通知外层暂停自动化；程序请求只跨一页时播放当前翻页动画，远距离目录跳转则直接定位。
 * 淡入模式没有可拖动Pager，会在淡入动画时长后报告页面已经稳定。
 *
 * @param modifier 正文容器外部修饰器。
 * @param book 当前书籍，决定绘制PDF位图或文本正文。
 * @param repository PDF页面渲染所需仓库。
 * @param textPages 文本书籍已经完成屏幕分页的页面。
 * @param pageCount 当前总页数。
 * @param currentPage 外层持有的当前零基页码。
 * @param currentTextOverride 当前页经过原文、对照或译文模式处理后的显示文字。
 * @param readingMode 当前翻页动画模式。
 * @param readerPalette 当前阅读背景配色。
 * @param fontScale 正文字号倍率。
 * @param fontFamily 正文字体。
 * @param controlsVisible 顶部和底部工具栏是否显示。
 * @param noteCountsByPage 每页笔记数量。
 * @param readAloudRange 当前页需要显示背景色的整页UTF-16范围；没有朗读时为null。
 * @param onToggleControls 单击正文切换工具栏的回调。
 * @param onCreateNote 创建当前页摘录笔记的回调。
 * @param onOpenPageNotes 打开指定页笔记的回调。
 * @param onPageChanged 用户或Pager动画改变当前页时的回调。
 * @param onPageScrollStateChanged Pager开始或停止移动时的回调。
 * @param onPageSettled 页面动画完整停止后的最终页码回调。
 * @return 无返回值，直接绘制当前阅读页。
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
    readAloudRange: EbookSpeechTextRange?,
    onToggleControls: () -> Unit,
    onCreateNote: (String, Int) -> Unit,
    onOpenPageNotes: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPageScrollStateChanged: (Boolean) -> Unit,
    onPageSettled: (Int) -> Unit
) {
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    val latestOnPageScrollStateChanged by rememberUpdatedState(onPageScrollStateChanged)
    val latestOnPageSettled by rememberUpdatedState(onPageSettled)
    val pageContent: @Composable (Int) -> Unit = { page ->
        if (book.format == EbookFormat.PDF) {
            PdfEbookPage(
                book = book,
                pageIndex = page,
                repository = repository,
                controlsVisible = controlsVisible
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
                controlsVisible = controlsVisible,
                noteCount = noteCountsByPage[page] ?: 0,
                backgroundColor = readerPalette.background,
                textColor = readerPalette.text,
                readAloudHighlightColor = readerPalette.readAloudHighlight,
                readAloudRange = readAloudRange.takeIf { page == currentPage },
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
                    if ((pagerState.currentPage - currentPage).absoluteValue == 1) {
                        pagerState.animateScrollToPage(currentPage)
                    } else {
                        pagerState.scrollToPage(currentPage)
                    }
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    latestOnPageChanged(page)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
                    latestOnPageScrollStateChanged(scrolling)
                    if (!scrolling) latestOnPageSettled(pagerState.currentPage)
                }
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
                    pageContent(page)
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
                    if ((pagerState.currentPage - currentPage).absoluteValue == 1) {
                        pagerState.animateScrollToPage(currentPage)
                    } else {
                        pagerState.scrollToPage(currentPage)
                    }
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    latestOnPageChanged(page)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
                    latestOnPageScrollStateChanged(scrolling)
                    if (!scrolling) latestOnPageSettled(pagerState.currentPage)
                }
            }
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                beyondViewportPageCount = 1
            ) { page -> pageContent(page) }
        }

        EbookReadingMode.VERTICAL -> {
            val pagerState = rememberPagerState(
                initialPage = currentPage.coerceIn(0, pageCount - 1),
                pageCount = { pageCount }
            )
            LaunchedEffect(currentPage) {
                if (!pagerState.isScrollInProgress && pagerState.currentPage != currentPage) {
                    if ((pagerState.currentPage - currentPage).absoluteValue == 1) {
                        pagerState.animateScrollToPage(currentPage)
                    } else {
                        pagerState.scrollToPage(currentPage)
                    }
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    latestOnPageChanged(page)
                }
            }
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
                    latestOnPageScrollStateChanged(scrolling)
                    if (!scrolling) latestOnPageSettled(pagerState.currentPage)
                }
            }
            VerticalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
                beyondViewportPageCount = 1
            ) { page -> pageContent(page) }
        }

            EbookReadingMode.FADE -> {
                LaunchedEffect(currentPage) {
                    latestOnPageScrollStateChanged(true)
                    delay(EBOOK_READER_FADE_SETTLE_MILLIS)
                    latestOnPageChanged(currentPage)
                    latestOnPageScrollStateChanged(false)
                    latestOnPageSettled(currentPage)
                }
                AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    targetState = currentPage,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ebook_fade_page"
                ) { page -> pageContent(page) }
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
 * @param controlsVisible 工具栏是否显示，用于为顶部和底部控制栏预留阅读边距。
 * @param noteCount 当前页已经保存的笔记数量。
 * @param backgroundColor 阅读背景颜色。
 * @param textColor 正文文字颜色。
 * @param readAloudHighlightColor 当前朗读范围使用的背景颜色。
 * @param readAloudRange 当前页正在朗读的UTF-16范围，终点不包含；没有朗读时为null。
 * @param onCreateNote 用户确认把当前选择保存为笔记的回调。
 * @param onOpenNotes 打开当前页笔记列表的回调。
 * @return 无返回值，直接绘制文本页。
 */
@Composable
private fun TextEbookPage(
    text: String,
    fontScale: Float,
    fontFamily: EbookFontFamily,
    controlsVisible: Boolean,
    noteCount: Int,
    backgroundColor: Color,
    textColor: Color,
    readAloudHighlightColor: Color,
    readAloudRange: EbookSpeechTextRange?,
    onCreateNote: (String) -> Unit,
    onOpenNotes: () -> Unit
) {
    var textFieldValue by remember(text) { mutableStateOf(TextFieldValue(text)) }
    val safeReadAloudRange = remember(text, readAloudRange) {
        readAloudRange?.let { range ->
            val safeStart = range.startOffset.coerceIn(0, text.length)
            val safeEnd = range.endOffsetExclusive.coerceIn(safeStart, text.length)
            if (safeEnd > safeStart) {
                EbookSpeechTextRange(safeStart, safeEnd)
            } else {
                null
            }
        }
    }
    val readAloudVisualTransformation: VisualTransformation = remember(
        safeReadAloudRange,
        readAloudHighlightColor
    ) {
        val highlightRange = safeReadAloudRange
        if (highlightRange == null) {
            VisualTransformation.None
        } else {
            VisualTransformation { source ->
                val highlightedText = buildAnnotatedString {
                    append(source)
                    addStyle(
                        style = SpanStyle(background = readAloudHighlightColor),
                        start = highlightRange.startOffset,
                        end = highlightRange.endOffsetExclusive
                    )
                }
                TransformedText(highlightedText, OffsetMapping.Identity)
            }
        }
    }
    val selectedText = normalizeEbookNoteSelection(
        text = text,
        selectionStart = textFieldValue.selection.start,
        selectionEnd = textFieldValue.selection.end
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 一页正文严格使用当前屏幕可读区域，不再提供页内滚动；翻页手势是阅读后续内容的唯一入口。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = READER_PAGE_HORIZONTAL_PADDING,
                    top = if (controlsVisible) READER_TOP_TEXT_INSET else READER_IMMERSIVE_TEXT_PADDING,
                    end = READER_PAGE_HORIZONTAL_PADDING,
                    bottom = if (controlsVisible) {
                        READER_BOTTOM_TEXT_INSET
                    } else {
                        READER_IMMERSIVE_TEXT_PADDING
                    }
                )
        ) {
            BasicTextField(
                modifier = Modifier.fillMaxSize(),
                value = textFieldValue,
                onValueChange = { updatedValue ->
                    // 阅读区只允许改变选区，禁止输入法或粘贴操作改写原书正文。
                    textFieldValue = updatedValue.copy(text = text)
                },
                readOnly = true,
                visualTransformation = readAloudVisualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = (READER_BASE_FONT_SIZE_SP * fontScale).sp,
                    lineHeight = (READER_BASE_LINE_HEIGHT_SP * fontScale).sp,
                    fontFamily = composeFontFamily(fontFamily),
                    color = textColor
                )
            )
        }

        if (noteCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (controlsVisible) READER_TOP_TEXT_INSET + 8.dp else 16.dp,
                        end = 16.dp
                    )
                    .zIndex(5f)
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

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (controlsVisible) {
                        READER_BOTTOM_TEXT_INSET + 8.dp
                    } else 20.dp
                )
                .zIndex(6f),
            visible = selectedText.isNotBlank(),
            enter = fadeIn() + slideInVertically { height -> height / 2 },
            exit = fadeOut() + slideOutVertically { height -> height / 2 }
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已选择 ${selectedText.length} 个字",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            onCreateNote(selectedText)
                            textFieldValue = TextFieldValue(text)
                        }
                    ) {
                        Text("写笔记")
                    }
                }
            }
        }
    }
}

/**
 * 后台渲染并在当前屏幕可读区域内完整显示一页PDF。
 *
 * 使用方法：
 * 由[EbookPageContainer]传入书籍、页码和工具栏状态；本函数不提供页内滚动，整张PDF页会等比缩放。
 *
 * @param book 当前PDF书籍。
 * @param pageIndex 当前零基页码。
 * @param repository 负责渲染PDF页面的仓库。
 * @param controlsVisible 工具栏是否显示，用于预留不会遮挡页面的上下区域。
 * @return 无返回值，直接绘制一页PDF。
 */
@Composable
private fun PdfEbookPage(
    book: EbookBook,
    pageIndex: Int,
    repository: EbookRepository,
    controlsVisible: Boolean
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }
        var bitmap by remember(book.id, pageIndex, targetWidth) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(book.id, pageIndex, targetWidth) {
            bitmap = repository.renderPdfPage(book, pageIndex, targetWidth)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 8.dp,
                    top = if (controlsVisible) READER_TOP_TEXT_INSET else 8.dp,
                    end = 8.dp,
                    bottom = if (controlsVisible) READER_BOTTOM_TEXT_INSET else 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            val pageBitmap = bitmap
            if (pageBitmap == null) {
                Text("正在渲染第${pageIndex + 1}页…")
            } else {
                Image(
                    bitmap = pageBitmap.asImageBitmap(),
                    contentDescription = "${book.title}第${pageIndex + 1}页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
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
    var query by remember(chapters) { mutableStateOf("") }
    val currentChapterIndex = chapters.indexOfLast { chapter -> chapter.pageIndex <= currentPage }
    val currentChapter = chapters.getOrNull(currentChapterIndex)
    val filteredChapters = remember(chapters, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            chapters
        } else {
            chapters.filter { chapter ->
                chapter.title.contains(keyword, ignoreCase = true) ||
                    (chapter.pageIndex + 1).toString().contains(keyword)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录 · ${chapters.size}项") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { value -> query = value.take(MAX_TABLE_OF_CONTENTS_QUERY_LENGTH) },
                    label = { Text("搜索章节标题或页码") },
                    placeholder = { Text("例如：山边小村、第一章、120") },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredChapters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有找到匹配的目录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredChapters,
                            key = { chapter -> "${chapter.pageIndex}_${chapter.title}" }
                        ) { chapter ->
                            val selected = chapter == currentChapter
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = chapter.title,
                                            fontWeight = if (selected) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (selected) {
                                            Text(
                                                text = "当前阅读章节",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${chapter.pageIndex + 1}页",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
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
 * 计算阅读器当前应使用的可靠页数，避免正文加载中的占位页覆盖历史阅读位置。
 *
 * 使用方法：
 * PDF直接传入仓库保存页数；TEXT、EPUB、MOBI等文本型书籍在正文加载前使用仓库页数，加载完成后
 * 切换为本次实际分页数量。所有输入都会收敛到至少1页，调用者可安全执行页码边界限制。
 *
 * @param format 当前书籍格式。
 * @param savedPageCount 仓库中上一次保存或导入时得到的页数。
 * @param textLoaded 文本型书籍是否已经读取并完成本次分页准备。
 * @param loadedTextPageCount 当前内存中的实际文本分页数量。
 * @return 当前阶段可用于恢复、展示和保存进度的可靠页数，最小为1。
 */
internal fun resolveEbookReaderPageCount(
    format: EbookFormat,
    savedPageCount: Int,
    textLoaded: Boolean,
    loadedTextPageCount: Int
): Int {
    return when {
        format == EbookFormat.PDF -> savedPageCount
        !textLoaded -> savedPageCount
        else -> loadedTextPageCount
    }.coerceAtLeast(1)
}

/**
 * 选择本次文本分页应使用的稳定阅读区域尺寸。
 *
 * 使用方法：
 * 阅读器的onSizeChanged每次收到新尺寸后调用。普通模式或屏幕宽度改变时使用真实新尺寸；进入沉浸
 * 模式仅隐藏外层底部导航，通常只会增加高度，此时继续沿用进入前的分页高度，让当前页立即保持可读，
 * 不为一次工具栏显隐重新计算整本书。旋转屏幕会改变宽度，仍会触发必要的后台重新分页。
 *
 * @param previousSize 当前已经用于分页的稳定尺寸；首次测量时传[IntSize.Zero]。
 * @param measuredSize Compose最新测得的阅读器完整像素尺寸。
 * @param retainHeightOnlyChange true表示当前正在进入或处于沉浸模式，可忽略宽度不变的纯高度变化。
 * @return 应保存并用于本次分页缓存身份的尺寸；非法新尺寸不会覆盖已有有效值。
 */
internal fun resolveEbookPaginationViewportSize(
    previousSize: IntSize,
    measuredSize: IntSize,
    retainHeightOnlyChange: Boolean
): IntSize {
    if (measuredSize.width <= 0 || measuredSize.height <= 0) return previousSize
    if (previousSize.width <= 0 || previousSize.height <= 0) return measuredSize
    return if (retainHeightOnlyChange && measuredSize.width == previousSize.width) {
        previousSize
    } else {
        measuredSize
    }
}

/**
 * 把Compose正文选区转换为可保存的电子书摘录。
 *
 * 使用方法：
 * 将BasicTextField回传的原始selection起止位置连同正文传入。函数兼容反向拖动和越界位置，去除
 * 首尾空白并限制最大保存长度，返回结果可直接交给阅读笔记创建弹窗。
 *
 * @param text 当前页完整正文。
 * @param selectionStart 原始选区起点，可能大于终点或暂时越界。
 * @param selectionEnd 原始选区终点，可能小于起点或暂时越界。
 * @return 规范化后的摘录；没有有效选择时返回空字符串。
 */
internal fun normalizeEbookNoteSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int
): String {
    val safeStart = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val safeEnd = maxOf(selectionStart, selectionEnd).coerceIn(safeStart, text.length)
    return text.substring(safeStart, safeEnd).trim().take(MAX_NOTE_SELECTION_LENGTH)
}

/**
 * 把动态分页结果压缩成交替保存每页起点和终点的整数数组。
 *
 * 使用方法：
 * 完成真实屏幕分页后调用，返回值可直接交给[EbookRepository.savePaginationBoundaries]持久化。
 *
 * @param pages 当前已经按顺序生成的全部文本页。
 * @return 长度为页数两倍的start、end数组；空分页返回空数组。
 */
internal fun measuredEbookPagesToBoundaries(
    pages: List<EbookMeasuredTextPage>
): IntArray {
    return IntArray(pages.size * 2).also { boundaries ->
        pages.forEachIndexed { index, page ->
            boundaries[index * 2] = page.startOffset
            boundaries[index * 2 + 1] = page.endOffset
        }
    }
}

/**
 * 使用已经校验的分页边界快速恢复可显示文本页，不再调用Android文字排版器。
 *
 * 使用方法：
 * [EbookRepository.loadPaginationBoundaries]命中后，在后台线程传入完整正文和缓存数组。缓存边界若因
 * 意外情况越界会返回空列表，调用者随后可以退回完整重新分页。
 *
 * @param text 当前完整离线正文。
 * @param boundaries 交替保存每页start、end的缓存数组。
 * @return 恢复出的全部文本页；边界非法时返回空列表。
 */
internal fun restoreMeasuredEbookPagesFromBoundaries(
    text: String,
    boundaries: IntArray
): List<EbookMeasuredTextPage> {
    if (!validateMeasuredEbookPageBoundaries(boundaries, text.length)) return emptyList()
    return buildList(boundaries.size / 2) {
        boundaries.indices.step(2).forEach { index ->
            val start = boundaries[index]
            val end = boundaries[index + 1]
            add(
                EbookMeasuredTextPage(
                    text = text.substring(start, end).trim(),
                    startOffset = start,
                    endOffset = end
                )
            )
        }
    }
}

/** @return 分页缓存边界连续覆盖全文时返回true。 */
private fun validateMeasuredEbookPageBoundaries(
    boundaries: IntArray,
    textLength: Int
): Boolean {
    if (textLength <= 0 || boundaries.isEmpty() || boundaries.size % 2 != 0) return false
    var expectedStart = 0
    boundaries.indices.step(2).forEach { index ->
        val start = boundaries[index]
        val end = boundaries[index + 1]
        if (start != expectedStart || end <= start || end > textLength) return false
        expectedStart = end
    }
    return expectedStart == textLength
}

/**
 * 按当前手机真实正文宽高、字号和字体把完整离线正文切成不可滚动的屏幕页。
 *
 * 使用方法：
 * 阅读器取得自身像素尺寸后，在Dispatchers.Default后台调用本函数。传入的宽高必须已经扣除正文
 * 水平留白以及普通模式的顶部、底部工具栏安全区。返回结果可直接用于HorizontalPager或VerticalPager。
 *
 * 实现使用与正文相同的Android系统字体、字号和行高逐行排版；每页最多包含可视区域能容纳的完整行，
 * 因此用户不需要在单页内继续下拉。大文件按固定字符块排版，每块一次生成多页，并保留块尾尚未
 * 凑满一页的文字交给下一块继续计算。这样既不会把整本MOBI一次装入StaticLayout，也不会为了每一页
 * 重复排版后续正文。
 *
 * @param text 完整离线正文。
 * @param contentWidthPx 正文扣除左右留白后的可用像素宽度。
 * @param contentHeightPx 正文扣除上下控制栏安全区后的可用像素高度。
 * @param fontSizePx 当前正文字号像素值。
 * @param lineHeightPx 当前正文行高像素值。
 * @param fontFamily 用户选择的系统字体族。
 * @param onProgress 每处理完一个字符块回传0到1之间的进度；不需要显示进度时可省略。
 *
 * @return 至少一页的测量分页结果；正文为空或尺寸无效时返回说明页。
 */
internal fun paginateEbookTextToViewport(
    text: String,
    contentWidthPx: Int,
    contentHeightPx: Int,
    fontSizePx: Float,
    lineHeightPx: Float,
    fontFamily: EbookFontFamily,
    onProgress: (Float) -> Unit = {}
): List<EbookMeasuredTextPage> {
    if (text.isBlank() || contentWidthPx <= 0 || contentHeightPx <= 0) {
        return listOf(
            EbookMeasuredTextPage(
                text = "没有解析到可显示的正文",
                startOffset = 0,
                endOffset = 0
            )
        )
    }

    val safeLineHeightPx = lineHeightPx.coerceAtLeast(1f)
    val maxLines = (contentHeightPx / safeLineHeightPx).toInt().coerceAtLeast(1)
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx.coerceAtLeast(1f)
        typeface = androidTypeface(fontFamily)
    }
    val lineSpacingExtra = (safeLineHeightPx - textPaint.fontSpacing).coerceAtLeast(0f)
    val pages = mutableListOf<EbookMeasuredTextPage>()
    var chunkStart = 0

    while (chunkStart < text.length) {
        val chunkEnd = (chunkStart + PAGE_LAYOUT_CHARACTER_WINDOW).coerceAtMost(text.length)
        val chunkText = text.substring(chunkStart, chunkEnd)
        val layout = StaticLayout.Builder.obtain(
            chunkText,
            0,
            chunkText.length,
            textPaint,
            contentWidthPx
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .setLineSpacing(lineSpacingExtra, 1f)
            .build()

        // 非最后一块至少保留末尾一个未满页。即使字符块刚好在一行中间结束，该行也会进入下一块
        // 重新排版，从而保证页边界不受固定分块位置影响。
        val emittedLineCount = if (chunkEnd >= text.length) {
            layout.lineCount
        } else {
            ((layout.lineCount - 1).coerceAtLeast(0) / maxLines) * maxLines
        }

        if (emittedLineCount <= 0) {
            // 正常手机尺寸下固定字符块一定能形成多页。这里仅处理极端超宽屏或异常字体指标，
            // 直接扩大到剩余正文完成一次排版，确保循环始终向前推进而不会停在加载页面。
            val fallbackLayout = StaticLayout.Builder.obtain(
                text,
                chunkStart,
                text.length,
                textPaint,
                contentWidthPx
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .setLineSpacing(lineSpacingExtra, 1f)
                .build()
            appendMeasuredEbookPages(
                sourceText = text,
                sourceStartOffset = 0,
                layout = fallbackLayout,
                emittedLineCount = fallbackLayout.lineCount,
                maxLinesPerPage = maxLines,
                destination = pages
            )
            chunkStart = text.length
        } else {
            appendMeasuredEbookPages(
                sourceText = text,
                sourceStartOffset = chunkStart,
                layout = layout,
                emittedLineCount = emittedLineCount,
                maxLinesPerPage = maxLines,
                destination = pages
            )
            val emittedCharacterCount = layout.getLineEnd(emittedLineCount - 1)
                .coerceIn(1, chunkText.length)
            chunkStart += emittedCharacterCount
        }
        onProgress(chunkStart.toFloat() / text.length.toFloat())
    }

    return pages.ifEmpty {
        listOf(EbookMeasuredTextPage("没有解析到可显示的正文", 0, 0))
    }
}

/**
 * 把一次StaticLayout排版结果按每屏可容纳行数追加为多个阅读页。
 *
 * 使用方法：
 * 仅由[paginateEbookTextToViewport]在后台分块分页时调用。调用者必须保证[emittedLineCount]
 * 不超过layout实际行数，并且非最后一个字符块不传入未排满一页的尾部行。
 *
 * @param sourceText 完整离线正文，用于按绝对字符位置取得每页文本。
 * @param sourceStartOffset 当前layout第0个字符在完整正文中的绝对位置。
 * @param layout 当前字符块已经完成的Android行排版结果。
 * @param emittedLineCount 本次允许输出的行数。
 * @param maxLinesPerPage 每一屏最多容纳的完整行数。
 * @param destination 接收分页结果的可变列表。
 * @return 无返回值，分页结果直接追加到[destination]。
 */
private fun appendMeasuredEbookPages(
    sourceText: String,
    sourceStartOffset: Int,
    layout: StaticLayout,
    emittedLineCount: Int,
    maxLinesPerPage: Int,
    destination: MutableList<EbookMeasuredTextPage>
) {
    var pageFirstLine = 0
    while (pageFirstLine < emittedLineCount) {
        val pageLastLine = (pageFirstLine + maxLinesPerPage)
            .coerceAtMost(emittedLineCount) - 1
        val relativeStart = layout.getLineStart(pageFirstLine)
        val relativeEnd = layout.getLineEnd(pageLastLine)
        val absoluteStart = (sourceStartOffset + relativeStart)
            .coerceIn(0, sourceText.length)
        val absoluteEnd = (sourceStartOffset + relativeEnd)
            .coerceIn(absoluteStart, sourceText.length)

        if (absoluteEnd > absoluteStart) {
            destination += EbookMeasuredTextPage(
                text = sourceText.substring(absoluteStart, absoluteEnd).trim(),
                startOffset = absoluteStart,
                endOffset = absoluteEnd
            )
        }
        pageFirstLine = pageLastLine + 1
    }
}

/**
 * 根据正文字符锚点定位新分页中的页码。
 *
 * 使用方法：
 * 字号、字体、横竖屏或阅读区域变化后，把保存的currentTextOffset传入，恢复到包含该字符的页面。
 *
 * @param pages 当前真实屏幕分页结果。
 * @param textOffset 需要恢复的完整正文字符位置。
 * @return 包含该位置的零基页码；空列表返回0，越界位置会限制到首尾页。
 */
internal fun findEbookPageIndexForOffset(
    pages: List<EbookMeasuredTextPage>,
    textOffset: Int
): Int {
    if (pages.isEmpty()) return 0
    val safeOffset = textOffset.coerceAtLeast(0)
    var low = 0
    var high = pages.lastIndex
    var result = 0
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (pages[middle].startOffset <= safeOffset) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result.coerceIn(0, pages.lastIndex)
}

/**
 * 根据笔记摘录重新定位真实屏幕分页后的页码。
 *
 * 使用方法：
 * 电子书完成动态分页后，为旧笔记逐条调用本函数。函数优先在完整正文中查找摘录，并在摘录重复
 * 出现时选择距离原页位置最近的一处；找不到时保留经过边界修正的原页码。
 *
 * @param pages 当前真实屏幕分页结果。
 * @param fullText 完整离线正文。
 * @param excerpt 笔记保存的原文摘录。
 * @param fallbackPageIndex 无法匹配摘录时使用的原零基页码。
 * @return 摘录起点所在的当前零基页码。
 */
internal fun findEbookPageIndexForExcerpt(
    pages: List<EbookMeasuredTextPage>,
    fullText: String,
    excerpt: String,
    fallbackPageIndex: Int
): Int {
    if (pages.isEmpty()) return 0
    val safeFallbackPage = fallbackPageIndex.coerceIn(0, pages.lastIndex)
    val normalizedExcerpt = excerpt.trim()
    if (normalizedExcerpt.isBlank() || fullText.isBlank()) return safeFallbackPage

    val fallbackOffset = pages[safeFallbackPage].startOffset
    var searchStart = 0
    var closestOffset = -1
    var closestDistance = Int.MAX_VALUE
    while (searchStart < fullText.length) {
        val matchOffset = fullText.indexOf(normalizedExcerpt, startIndex = searchStart)
        if (matchOffset < 0) break
        val distance = kotlin.math.abs(matchOffset.toLong() - fallbackOffset.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        if (distance < closestDistance) {
            closestDistance = distance
            closestOffset = matchOffset
            if (distance == 0) break
        }
        searchStart = matchOffset + 1
    }

    return if (closestOffset >= 0) {
        findEbookPageIndexForOffset(pages, closestOffset)
    } else {
        safeFallbackPage
    }
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
 * 以及前言、序言、楔子、后记等常见标题；同页重复标题会自动去重。实现只保留当前行和下一条
 * 非空行，不会为大型小说一次性创建全书行列表；达到目录数量上限后立即结束扫描。
 *
 * @param pages 已按阅读器规则分页的正文。
 * @return 按页码和出现顺序排列的目录；未识别到可靠标题时返回空列表。
 */
internal fun buildEbookTableOfContents(pages: List<String>): List<EbookChapter> {
    val chapters = mutableListOf<EbookChapter>()
    val seen = mutableSetOf<String>()
    val sourceLines = sequence {
        pages.forEachIndexed { pageIndex, pageText ->
            pageText.lineSequence().forEach { rawLine ->
                val normalized = rawLine.trim().replace(EBOOK_TOC_WHITESPACE_PATTERN, " ")
                if (normalized.isNotBlank()) yield(pageIndex to normalized)
            }
        }
    }.iterator()
    if (!sourceLines.hasNext()) return emptyList()

    var currentLine = sourceLines.next()
    while (true) {
        val (pageIndex, line) = currentLine
        var prefetchedNextLine: Pair<Int, String>? = null
        val chapterCandidate = if (line.length !in 1..MAX_CHAPTER_TITLE_LENGTH) {
            null
        } else {
            val markdownMatch = MARKDOWN_CHAPTER_PATTERN.matchEntire(line)
            when {
                markdownMatch != null -> {
                    markdownMatch.groupValues[2].trim() to
                        markdownMatch.groupValues[1].length.coerceIn(1, 4)
                }
                CHINESE_CHAPTER_PATTERN.matches(line) -> {
                    // 只有纯“第X章”需要提前取得下一条非空行作为副标题；该行仍会在下一轮正常识别。
                    if (CHINESE_BARE_CHAPTER_PATTERN.matches(line) && sourceLines.hasNext()) {
                        prefetchedNextLine = sourceLines.next()
                    }
                    enrichChineseEbookChapterTitle(
                        chapterMarker = line,
                        nextLine = prefetchedNextLine?.second.orEmpty()
                    ) to 1
                }
                ENGLISH_CHAPTER_PATTERN.matches(line) || SPECIAL_CHAPTER_PATTERN.matches(line) -> {
                    line to 1
                }
                else -> null
            }
        }
        chapterCandidate?.let { (normalizedTitle, level) ->
            if (normalizedTitle.length in 1..MAX_CHAPTER_TITLE_LENGTH) {
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
        if (chapters.size >= MAX_TABLE_OF_CONTENTS_ITEMS) return chapters

        currentLine = prefetchedNextLine
            ?: if (sourceLines.hasNext()) sourceLines.next() else return chapters
    }
}

/**
 * 为只有“第X章/回/卷”等编号的中文章节补充下一行短标题。
 *
 * 使用方法：
 * [buildEbookTableOfContents]识别到纯章节编号后调用。若下一行是无句末标点的短文本，则将两行合并
 * 为“第一章 山边小村”；若下一行更像正文、另一个章节或Markdown标题，则保留原编号，避免误收正文。
 *
 * @param chapterMarker 已确认符合中文章节格式的当前行。
 * @param nextLine 正文中的下一条非空行；不存在时传入空字符串。
 * @return 包含可靠副标题的完整目录名，或未经修改的章节编号。
 */
private fun enrichChineseEbookChapterTitle(chapterMarker: String, nextLine: String): String {
    if (!CHINESE_BARE_CHAPTER_PATTERN.matches(chapterMarker)) return chapterMarker
    val subtitle = nextLine.trim().replace(Regex("\\s+"), " ")
    if (!isLikelyEbookChapterSubtitle(subtitle)) return chapterMarker
    return "$chapterMarker $subtitle".take(MAX_CHAPTER_TITLE_LENGTH)
}

/**
 * 判断章节编号后的短行是否更像章节副标题而不是普通正文。
 *
 * 使用方法：
 * 仅由[enrichChineseEbookChapterTitle]调用。判断会拒绝空行、超长句、带常见句末标点的句子、另一个
 * 章节编号以及Markdown标题，以较保守的方式补充目录详情。
 *
 * @param line 待判断的下一条非空正文行。
 * @return 可以作为章节副标题时返回true，否则返回false。
 */
private fun isLikelyEbookChapterSubtitle(line: String): Boolean {
    if (line.length !in 1..MAX_CHAPTER_SUBTITLE_LENGTH) return false
    if (CHINESE_CHAPTER_PATTERN.matches(line) || ENGLISH_CHAPTER_PATTERN.matches(line)) return false
    if (MARKDOWN_CHAPTER_PATTERN.matches(line) || SPECIAL_CHAPTER_PATTERN.matches(line)) return false
    return CHAPTER_SUBTITLE_SENTENCE_PUNCTUATION.none(line::contains)
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

/** @return 与Compose正文选项对应的Android系统字体，用于后台真实行宽测量。 */
private fun androidTypeface(option: EbookFontFamily): Typeface {
    return when (option) {
        EbookFontFamily.SERIF -> Typeface.SERIF
        EbookFontFamily.SANS_SERIF -> Typeface.SANS_SERIF
        EbookFontFamily.MONOSPACE -> Typeface.MONOSPACE
        EbookFontFamily.CURSIVE -> Typeface.create("cursive", Typeface.NORMAL)
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

/**
 * 从当前界面上下文向外查找承载 Compose 的 ComponentActivity。
 *
 * 电子书阅读时长只应在 Activity 处于前台 RESUMED 状态时累计，因此这里需要拿到宿主 Activity
 * 的生命周期。主题包装器通常会形成多层 ContextWrapper，本函数会逐层解包，直到找到
 * ComponentActivity；若当前上下文并非界面上下文，则安全返回 null，不会影响阅读器本身使用。
 *
 * @return 当前上下文对应的 ComponentActivity；无法找到时返回 null。
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}

private const val MAX_BOOK_QUERY_LENGTH = 80
private const val EBOOK_SCREEN_TAG = "EbookScreen"
private const val EBOOK_READING_REPORT_INTERVAL_MILLIS = 60_000L
private const val BOOKS_PER_SHELF_ROW = 10
private const val SHELF_ROWS_PER_PAGE = 3
private const val BOOKS_PER_SHELF_PAGE = BOOKS_PER_SHELF_ROW * SHELF_ROWS_PER_PAGE
private const val MAX_SPINE_TITLE_CHARACTERS = 8
private val SHELF_PROGRESS_AREA_HEIGHT = 24.dp
private const val TEXT_PAGE_CHARACTER_LIMIT = 1_050
private const val MIN_TEXT_PAGE_REMAINDER = 80
private const val PAGE_LAYOUT_CHARACTER_WINDOW = 64_000
private const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 350L
private const val EBOOK_AUTO_PAGE_INTERVAL_SLIDER_STEPS = 10
private const val EBOOK_READER_FADE_SETTLE_MILLIS = 300L
private const val MIN_READER_FONT_SCALE = 0.75f
private const val MAX_READER_FONT_SCALE = 1.8f
private const val READER_FONT_STEP = 0.1f
private const val READER_BASE_FONT_SIZE_SP = 18f
private const val READER_BASE_LINE_HEIGHT_SP = 30f
private val READER_PAGE_HORIZONTAL_PADDING = 24.dp
private val READER_TOP_BAR_HEIGHT = 56.dp
private val READER_BOTTOM_BAR_HEIGHT = 96.dp
private val READER_TOP_TEXT_INSET = READER_TOP_BAR_HEIGHT + 8.dp
private val READER_BOTTOM_TEXT_INSET = READER_BOTTOM_BAR_HEIGHT + 8.dp
private val READER_IMMERSIVE_TEXT_PADDING = 24.dp
private const val EBOOK_TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
private const val MAX_CHAPTER_TITLE_LENGTH = 72
private const val MAX_CHAPTER_SUBTITLE_LENGTH = 36
private const val MAX_TABLE_OF_CONTENTS_QUERY_LENGTH = 48
private const val MAX_TABLE_OF_CONTENTS_ITEMS = 2_000
private const val MAX_NOTE_SELECTION_LENGTH = 8_000
private const val MAX_NOTE_COMMENT_LENGTH = 8_000
private val EBOOK_SHELF_EDGE_PAGING_WIDTH = 36.dp
private val EBOOK_SHELF_DROP_TOLERANCE = 8.dp
private const val EBOOK_SHELF_EDGE_PAGING_DWELL_MILLIS = 450L
private const val EBOOK_SHELF_DRAG_OVERLAY_Z_INDEX = 10f
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
private val EBOOK_TOC_WHITESPACE_PATTERN = Regex("\\s+")
private val CHINESE_CHAPTER_PATTERN = Regex(
    "^第[〇零一二三四五六七八九十百千万两0-9]+[章节回卷部篇集](?!正文(?:[。.]|$)).{0,60}$"
)
private val CHINESE_BARE_CHAPTER_PATTERN = Regex(
    "^第[〇零一二三四五六七八九十百千万两0-9]+[章节回卷部篇集]$"
)
private val ENGLISH_CHAPTER_PATTERN = Regex(
    "^(chapter|book|part)\\s+([0-9ivxlcdm]+|[a-z]+)([ .:：-].{0,52})?$",
    RegexOption.IGNORE_CASE
)
private val SPECIAL_CHAPTER_PATTERN = Regex(
    "^(序|序言|前言|楔子|引子|引言|后记|尾声|终章|附录|contents|preface|prologue|epilogue)$",
    RegexOption.IGNORE_CASE
)
private val CHAPTER_SUBTITLE_SENTENCE_PUNCTUATION = charArrayOf('。', '！', '？', '!', '?', '；', ';')

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
