package com.example.harleyapp.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.harleyapp.backup.AppBackupManager
import com.example.harleyapp.data.ChineseGrowthRepository
import com.example.harleyapp.data.BreathHoldRepository
import com.example.harleyapp.data.CompanionRepository
import com.example.harleyapp.data.DeveloperModeRepository
import com.example.harleyapp.data.EnglishWordRepository
import com.example.harleyapp.data.EbookRepository
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.data.FeatureCenterOrderRepository
import com.example.harleyapp.data.GlobalSearchRepository
import com.example.harleyapp.data.HomeFeatureRepository
import com.example.harleyapp.data.HotTopicRepository
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.NotebookRepository
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.data.ShortcutRepository
import com.example.harleyapp.data.WebsiteRepository
import com.example.harleyapp.data.WebsiteBrowserSessionRepository
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.data.WebsiteLibraryChangeNotifier
import com.example.harleyapp.data.WechatBillImporter
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionInteraction
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EbookImportResult
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.LocalSearchType
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.WebsiteBookmarkSaveResult
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WechatCapture
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.model.homeCarouselWebsites
import com.example.harleyapp.model.closeWebsiteBrowserTab
import com.example.harleyapp.model.createBlankWebsiteBrowserTab
import com.example.harleyapp.model.createWebsiteBrowserChildTab
import com.example.harleyapp.model.createWebsiteBrowserTab
import com.example.harleyapp.model.fillWebsiteBrowserTab
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.primaryEnglishWordsForSelection
import com.example.harleyapp.model.selectWebsiteBrowserTab
import com.example.harleyapp.model.updateWebsiteBrowserTab
import com.example.harleyapp.notification.NotificationAlertChannels
import com.example.harleyapp.notification.WechatReminderScheduler
import com.example.harleyapp.notification.NotificationTestController
import com.example.harleyapp.notification.NotificationTestResult
import com.example.harleyapp.notification.ReminderSoundRepository
import com.example.harleyapp.notification.ReminderSoundTarget
import com.example.harleyapp.reminder.ReminderScheduler
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.AppUsageController
import com.example.harleyapp.system.ExactAlarmAccessController
import com.example.harleyapp.system.HotTopicLauncher
import com.example.harleyapp.system.InstalledAppsRepository
import com.example.harleyapp.system.LocalCleanupManager
import com.example.harleyapp.system.MobileDataUsageController
import com.example.harleyapp.system.NotificationAccessController
import com.example.harleyapp.system.NotificationSystemSettingsController
import com.example.harleyapp.system.OfflineEnglishTts
import com.example.harleyapp.system.OfflineEnglishTtsState
import com.example.harleyapp.system.SystemStorageController
import com.example.harleyapp.ui.screens.AppUsageScreen
import com.example.harleyapp.ui.screens.BreathHoldGameScreen
import com.example.harleyapp.ui.screens.FitnessScreen
import com.example.harleyapp.ui.screens.BackupScreen
import com.example.harleyapp.ui.screens.FeatureCenterPage
import com.example.harleyapp.ui.screens.FeatureCenterScreen
import com.example.harleyapp.ui.screens.FeatureDetailScaffold
import com.example.harleyapp.ui.screens.HomeScreen
import com.example.harleyapp.ui.screens.GlobalSearchScreen
import com.example.harleyapp.ui.screens.HotTopicsScreen
import com.example.harleyapp.ui.screens.LedgerScreen
import com.example.harleyapp.ui.screens.MobileDataUsageScreen
import com.example.harleyapp.ui.screens.ProfileScreen
import com.example.harleyapp.ui.screens.ReminderSoundPickerDialog
import com.example.harleyapp.ui.screens.TodayOverviewScreen
import com.example.harleyapp.ui.screens.WebsiteBookmarkManagerScreen
import com.example.harleyapp.ui.screens.WebsiteScreen
import com.example.harleyapp.ui.components.harleyCardBorder
import com.example.harleyapp.weather.DeviceLocationProvider
import com.example.harleyapp.weather.WeatherRepository
import com.example.harleyapp.widget.TodayWeatherWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

/**
 * 应用一级页面和卡片详情页的统一导航目标。
 *
 * 使用方法：
 * 后续新增卡片化功能时，把详情页登记为showInBottomNavigation=false，并在首页增加
 * FeatureEntryCard入口。这样详情内容不会直接堆放在首页，底部导航数量也不会不断增加。
 *
 * @param title 页面中文名称；一级页面会显示在底部导航中。
 * @param symbol 一级页面使用的简洁字符图标，避免引入体积较大的额外图标依赖。
 * @param showInBottomNavigation true表示固定一级页面，false表示只能由功能卡片进入的详情页。
 */
private enum class AppSection(
    val title: String,
    val symbol: String,
    val showInBottomNavigation: Boolean = true
) {
    HOME("首页", "首"),
    TODAY("今日总览", "今", showInBottomNavigation = false),
    HOT_TOPICS("每日热点", "热", showInBottomNavigation = false),
    MOBILE_DATA("手机流量", "流", showInBottomNavigation = false),
    APP_USAGE("应用使用", "用", showInBottomNavigation = false),
    BREATH_HOLD("深海憋气", "息", showInBottomNavigation = false),
    FEATURES("功能", "功"),
    SEARCH("全局搜索", "搜", showInBottomNavigation = false),
    BACKUP("本地备份", "备", showInBottomNavigation = false),
    LEDGER("记账", "¥", showInBottomNavigation = false),
    FITNESS("运动", "动", showInBottomNavigation = false),
    BOOKMARKS("网站收藏", "夹", showInBottomNavigation = false),
    WEBSITE("网站", "网"),
    PROFILE("我的", "我")
}

/** 首页两次返回确认的有效间隔；超过该时间后再次返回会重新提示。 */
internal const val HOME_EXIT_CONFIRM_WINDOW_MILLIS = 1_600L

/** 首页退出提示自动消失时间，略短于二次返回确认窗口。 */
private const val HOME_EXIT_HINT_DURATION_MILLIS = 1_500L

/** 首页第一次返回时显示的固定提示，用于安全识别并自动关闭当前Snackbar。 */
private const val HOME_EXIT_HINT_MESSAGE = "再按一次返回退出应用"

/**
 * 计算应用最外层返回操作应该进入的页面。
 *
 * 使用方法：
 * 仅在页面自身没有消费返回事件时调用。底部一级页面统一返回首页；从首页或功能中心进入的详情页
 * 先返回已记录的来源页，来源无效或指向自己时安全回退首页，从而保证连续返回最终一定到达首页。
 *
 * @param currentSectionName 当前页面的枚举名称。
 * @param detailReturnSectionName 进入详情页前记录的来源页面名称。
 *
 * @return 下一步目标页面的枚举名称；输入无效时返回首页。
 */
internal fun resolveAppBackDestinationName(
    currentSectionName: String,
    detailReturnSectionName: String
): String {
    val currentSection = AppSection.entries.firstOrNull { section ->
        section.name == currentSectionName
    } ?: return AppSection.HOME.name

    if (currentSection == AppSection.HOME || currentSection.showInBottomNavigation) {
        return AppSection.HOME.name
    }

    val detailReturnSection = AppSection.entries.firstOrNull { section ->
        section.name == detailReturnSectionName
    } ?: AppSection.HOME
    return if (detailReturnSection == currentSection) {
        AppSection.HOME.name
    } else {
        detailReturnSection.name
    }
}

/**
 * 判断首页本次返回是否处于第二次确认窗口内。
 *
 * 使用方法：
 * 第一次返回时记录[SystemClock.elapsedRealtime]；后续返回把上次与当前时间传入。本函数只做纯计算，
 * 页面负责显示提示或结束Activity，因此系统返回键和侧滑返回手势能够复用同一规则。
 *
 * @param previousBackAtMillis 上一次首页返回的单调时钟毫秒数，尚未返回过时传0。
 * @param currentBackAtMillis 本次返回的单调时钟毫秒数。
 * @param confirmWindowMillis 两次返回允许的最大间隔毫秒数。
 *
 * @return 本次应退出App返回true；应只提示“再按一次”返回false。
 */
internal fun isHomeExitConfirmed(
    previousBackAtMillis: Long,
    currentBackAtMillis: Long,
    confirmWindowMillis: Long = HOME_EXIT_CONFIRM_WINDOW_MILLIS
): Boolean {
    return previousBackAtMillis > 0L &&
        currentBackAtMillis >= previousBackAtMillis &&
        currentBackAtMillis - previousBackAtMillis <= confirmWindowMillis
}

/**
 * 显示支持左右滑动关闭的全局Snackbar。
 *
 * 使用方法：
 * 作为Scaffold的snackbarHost传入。用户把提示向任意水平方向滑离时调用SnackbarData.dismiss；
 * 原有操作按钮和由调用方控制的自动消失逻辑继续保留，因此不仅首页退出提示，其他短消息也能手动划掉。
 *
 * @param hostState 全局Snackbar队列状态。
 *
 * @return 无返回值，直接输出可滑动的SnackbarHost。
 */
@Composable
private fun SwipeDismissibleSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState = hostState) { snackbarData ->
        key(snackbarData) {
            val dismissState = rememberSwipeToDismissBoxState()
            LaunchedEffect(dismissState, snackbarData) {
                snapshotFlow { dismissState.currentValue }
                    .first { value -> value != SwipeToDismissBoxValue.Settled }
                snackbarData.dismiss()
            }
            SwipeToDismissBox(
                modifier = Modifier.fillMaxWidth(),
                state = dismissState,
                backgroundContent = {}
            ) {
                Snackbar(snackbarData = snackbarData)
            }
        }
    }
}

/**
 * 显示会随角色主题变化的底部导航图标。
 *
 * 使用方法：
 * 底部四个一级页面统一调用本组件。所有状态都保留各自的单字徽记；人物主题只在当前选中页
 * 作为低透明背景出现，让用户仍能感知主题，也不会因切换成一张脸而失去页面辨识度。
 *
 * @param section 当前底部导航页面。
 * @param selected 是否为当前选中页面。
 * @param visualTheme 当前用户人物主题。
 * @param scale 选中切换动画提供的整体缩放值。
 * @return 无返回值，直接输出导航图标。
 */
@Composable
private fun ThemedNavigationIcon(
    section: AppSection,
    selected: Boolean,
    visualTheme: AppVisualTheme,
    scale: Float
) {
    val context = LocalContext.current
    val artResourceId = remember(visualTheme.artResourceName) {
        if (visualTheme.artResourceName.isBlank()) {
            0
        } else {
            context.resources.getIdentifier(
                visualTheme.artResourceName,
                "drawable",
                context.packageName
            )
        }
    }
    Surface(
        modifier = Modifier
            .size(width = 44.dp, height = 34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(13.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        border = if (selected) {
            harleyCardBorder(alpha = 0.86f)
        } else {
            null
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected && artResourceId != 0) {
                Image(
                    painter = painterResource(artResourceId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(13.dp))
                        .graphicsLayer { alpha = 0.36f }
                )
            }
            Text(
                text = section.symbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 组织整个Harley生活助手的页面状态、数据仓库和底部导航。
 *
 * 使用方法：
 * 在MainActivity的setContent中、HarleyAppTheme内部调用HarleyApp()。
 * 本函数负责把账目、快捷应用和设备服务传递给各个独立页面。
 *
 * @param isDarkTheme 当前是否使用黑夜模式，用于“更多”页显示正确切换方向。
 * @param visualTheme 当前整体角色风格主题。
 * @param startupAnimationEnabled 下次启动是否播放逐字启动动画。
 * @param openTodayRequest 是否收到桌面小组件发出的“打开今日总览”请求。
 * @param onOpenTodayRequestConsumed 请求完成导航后的消费回调，防止重组时重复打开。
 * @param openEbookRequestId 媒体通知要求打开的电子书id；为空表示没有待处理请求。
 * @param onOpenEbookRequestConsumed 进入目标电子书后的消费回调，防止重组时重复导航。
 * @param onSetDarkTheme 保存并立即应用主题模式的回调，成功返回true。
 * @param onSetVisualTheme 保存并立即应用角色风格主题的回调，成功返回true。
 * @param onSetStartupAnimationEnabled 保存下次启动动画开关的回调，成功返回true。
 * @param onExitApp 首页第二次返回确认后的Activity退出回调。
 *
 * @return 无返回值，直接输出完整应用界面。
 */
@Composable
fun HarleyApp(
    isDarkTheme: Boolean,
    visualTheme: AppVisualTheme,
    startupAnimationEnabled: Boolean,
    openTodayRequest: Boolean,
    onOpenTodayRequestConsumed: () -> Unit,
    openEbookRequestId: String,
    onOpenEbookRequestConsumed: () -> Unit,
    onSetDarkTheme: (Boolean) -> Boolean,
    onSetVisualTheme: (AppVisualTheme) -> Boolean,
    onSetStartupAnimationEnabled: (Boolean) -> Boolean,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val applicationContext = context.applicationContext
    val ledgerRepository = remember {
        LedgerRepository(applicationContext)
    }
    val shortcutRepository = remember {
        ShortcutRepository(applicationContext)
    }
    val homeFeatureRepository = remember {
        HomeFeatureRepository(applicationContext)
    }
    val featureCenterOrderRepository = remember {
        FeatureCenterOrderRepository(applicationContext)
    }
    val englishWordRepository = remember {
        EnglishWordRepository(applicationContext)
    }
    val websiteRepository = remember {
        WebsiteRepository(applicationContext)
    }
    val websiteBrowserSessionRepository = remember {
        WebsiteBrowserSessionRepository(applicationContext)
    }
    val websiteBackgroundStore = remember {
        WebsiteCardBackgroundStore(applicationContext)
    }
    val hotTopicRepository = remember {
        HotTopicRepository()
    }
    val companionRepository = remember {
        CompanionRepository(applicationContext)
    }
    val developerModeRepository = remember {
        DeveloperModeRepository(applicationContext)
    }
    val wechatReminderRepository = remember {
        WechatReminderRepository(applicationContext)
    }
    val reminderRepository = remember {
        ReminderRepository(applicationContext)
    }
    val fitnessRepository = remember {
        FitnessRepository(applicationContext)
    }
    val notebookRepository = remember {
        NotebookRepository(applicationContext)
    }
    val ebookRepository = remember {
        EbookRepository(applicationContext)
    }
    val chineseGrowthRepository = remember {
        ChineseGrowthRepository(applicationContext)
    }
    val breathHoldRepository = remember {
        BreathHoldRepository(applicationContext)
    }
    val backupManager = remember {
        AppBackupManager(applicationContext)
    }
    val weatherRepository = remember {
        WeatherRepository(applicationContext)
    }
    val deviceLocationProvider = remember {
        DeviceLocationProvider(applicationContext)
    }
    val globalSearchRepository = remember {
        GlobalSearchRepository(
            ledgerRepository = ledgerRepository,
            reminderRepository = reminderRepository,
            fitnessRepository = fitnessRepository,
            websiteRepository = websiteRepository,
            notebookRepository = notebookRepository,
            ebookRepository = ebookRepository
        )
    }
    val wechatBillImporter = remember {
        WechatBillImporter(applicationContext, ledgerRepository)
    }
    val installedAppsRepository = remember {
        InstalledAppsRepository(applicationContext)
    }
    val hotTopicLauncher = remember {
        HotTopicLauncher(applicationContext)
    }
    val deviceMonitor = remember {
        DeviceMonitor(applicationContext)
    }
    val localCleanupManager = remember {
        LocalCleanupManager(applicationContext, ledgerRepository)
    }
    val mobileDataUsageController = remember {
        MobileDataUsageController(applicationContext)
    }
    val appUsageController = remember {
        AppUsageController(applicationContext)
    }
    val notificationAccessController = remember {
        NotificationAccessController(applicationContext)
    }
    val notificationSystemSettingsController = remember {
        NotificationSystemSettingsController(applicationContext)
    }
    val notificationTestController = remember {
        NotificationTestController(applicationContext)
    }
    val reminderSoundRepository = remember {
        ReminderSoundRepository(applicationContext)
    }
    val wechatReminderScheduler = remember {
        WechatReminderScheduler(applicationContext)
    }
    val reminderScheduler = remember {
        ReminderScheduler(applicationContext)
    }
    val exactAlarmAccessController = remember {
        ExactAlarmAccessController(applicationContext)
    }
    val systemStorageController = remember {
        SystemStorageController(applicationContext)
    }
    val snackbarHostState = remember {
        SnackbarHostState()
    }
    val coroutineScope = rememberCoroutineScope()
    var websiteEbookImportResult by remember {
        mutableStateOf<EbookImportResult?>(null)
    }
    var reminderSoundPickerTargetName by rememberSaveable {
        mutableStateOf("")
    }
    var englishTtsState by remember {
        mutableStateOf(OfflineEnglishTtsState.INITIALIZING)
    }
    val offlineEnglishTts = remember {
        OfflineEnglishTts(applicationContext) { newState ->
            englishTtsState = newState
        }
    }

    DisposableEffect(offlineEnglishTts) {
        onDispose {
            offlineEnglishTts.shutdown()
        }
    }

    var currentSectionName by rememberSaveable {
        mutableStateOf(AppSection.HOME.name)
    }
    var featureCenterPageName by rememberSaveable {
        mutableStateOf(FeatureCenterPage.OVERVIEW.name)
    }
    var searchEnglishWordTargetId by rememberSaveable {
        mutableStateOf("")
    }
    var searchNotebookArticleTargetId by rememberSaveable {
        mutableStateOf("")
    }
    var searchEbookTargetId by rememberSaveable {
        mutableStateOf("")
    }
    var pendingGlobalSearchQuery by rememberSaveable {
        mutableStateOf("")
    }
    var detailReturnSectionName by rememberSaveable {
        mutableStateOf(AppSection.HOME.name)
    }
    var ledgerEntries by remember {
        mutableStateOf(ledgerRepository.getEntries())
    }
    var pendingWechatCaptures by remember {
        mutableStateOf(ledgerRepository.getPendingCaptures())
    }
    var selectedPackages by remember {
        mutableStateOf(shortcutRepository.getSelectedPackages())
    }
    var selectedHomeFeatures by remember {
        mutableStateOf(homeFeatureRepository.getSelectedFeatures())
    }
    var featureCenterOrder by remember {
        mutableStateOf(featureCenterOrderRepository.getOrder())
    }
    // 功能中心的网格状态由App根层长期持有。进入任意内部或全屏功能时即使概览离开组合，返回后仍能
    // 恢复离开前的卡片位置；rememberLazyGridState自身支持Activity重建时保存首项与像素偏移。
    val featureCenterGridState = rememberLazyGridState()
    // 独立全屏功能会暂时移除底部导航，返回时网格可能先按更高视口夹紧滚动值；额外保存离开前的
    // 精确锚点，在底栏恢复布局后用于无动画复位，避免出现整整一个底栏高度的偏移。
    var featureCenterRestoreIndex by rememberSaveable { mutableIntStateOf(0) }
    var featureCenterRestoreOffset by rememberSaveable { mutableIntStateOf(0) }
    val initialEnglishWords = remember {
        englishWordRepository.getWords()
    }
    val initialEnglishSelection = remember {
        englishWordRepository.getLearningSelection()
    }
    var englishWords by remember {
        mutableStateOf(initialEnglishWords)
    }
    var englishLearningSelection by remember {
        mutableStateOf(initialEnglishSelection)
    }
    var homeEnglishWordId by rememberSaveable {
        mutableStateOf(
            englishWordRepository.chooseNextWord(
                words = initialEnglishWords,
                previousWordId = null,
                selection = initialEnglishSelection
            )?.id
        )
    }
    var websiteLibrary by remember {
        mutableStateOf(websiteRepository.getLibrary())
    }
    val websites = websiteLibrary.websites
    var defaultWebsiteId by remember {
        mutableStateOf(websiteRepository.getDefaultWebsiteId(websites))
    }
    val websiteBrowserSessionState = remember {
        val fallbackWebsite = websites.firstOrNull { website ->
            website.id == defaultWebsiteId
        } ?: websites.firstOrNull()
        mutableStateOf(
            websiteBrowserSessionRepository.getSession(fallbackWebsite)
        )
    }
    var websiteBrowserSession by websiteBrowserSessionState

    // 浏览器悬浮球可能在Activity仍留在后台时写入收藏；收到事件后立即刷新内存状态，返回App无需重启。
    LaunchedEffect(websiteRepository) {
        WebsiteLibraryChangeNotifier.changes.collect {
            val refreshedLibrary = websiteRepository.getLibrary()
            websiteLibrary = refreshedLibrary
            defaultWebsiteId = websiteRepository.getDefaultWebsiteId(
                refreshedLibrary.websites
            )
        }
    }

    // 标签元数据采用短延迟合并写入，避免页面加载开始/结束连续回调时在主线程同步写磁盘。
    // WebView本身仍只存在于当前进程；冷启动恢复的是标签顺序、标题和最后一个有效网址。
    LaunchedEffect(websiteBrowserSession, websiteBrowserSessionRepository) {
        delay(WEBSITE_BROWSER_SESSION_SAVE_DELAY_MILLIS)
        websiteBrowserSessionRepository.enqueueSessionSave(websiteBrowserSession)
    }

    // 快速切换标签后立刻按Home键、旋转Activity或退出时，短延迟自动保存可能尚未触发。生命周期
    // 进入后台及组合最终释放前再提交最新快照；apply只排队写盘，不在主线程执行同步磁盘commit。
    DisposableEffect(lifecycleOwner, websiteBrowserSessionRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                websiteBrowserSessionRepository.enqueueSessionSave(
                    websiteBrowserSessionState.value
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            websiteBrowserSessionRepository.enqueueSessionSave(
                websiteBrowserSessionState.value
            )
        }
    }

    // 所有应用内网站入口统一通过这里追加标签。同一收藏重复点击也会得到独立标签；达到上限时
    // 保留当前会话并提示用户手动关闭，不会静默回收任何仍在使用的网页。
    val openWebsiteInNewTab: (WebsiteShortcut) -> Boolean = { website ->
        val updatedSession = createWebsiteBrowserTab(
            session = websiteBrowserSession,
            tabId = UUID.randomUUID().toString(),
            website = website
        )
        val opened = updatedSession != websiteBrowserSession
        if (opened) {
            websiteBrowserSession = updatedSession
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(WEBSITE_BROWSER_TAB_LIMIT_MESSAGE)
            }
        }
        opened
    }

    // 网页明确请求target=_blank或window.open时继承来源收藏归属，让不同标签继续共用该网站的
    // 脚本工具设置，同时仍以独立tabId隔离WebView、标题、历史和关闭行为。
    val openWebsiteChildTab: (String, String) -> Boolean = { sourceTabId, url ->
        val updatedSession = createWebsiteBrowserChildTab(
            session = websiteBrowserSession,
            sourceTabId = sourceTabId,
            tabId = UUID.randomUUID().toString(),
            title = "正在加载…",
            url = url
        )
        val opened = updatedSession != websiteBrowserSession
        if (opened) {
            websiteBrowserSession = updatedSession
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(WEBSITE_BROWSER_TAB_LIMIT_MESSAGE)
            }
        }
        opened
    }
    var companionProgress by remember {
        mutableStateOf(
            companionRepository.getProgress(LocalDate.now().toEpochDay())
        )
    }
    var developerModeEnabled by remember {
        mutableStateOf(developerModeRepository.isEnabled())
    }
    var wechatReminderSettings by remember {
        mutableStateOf(wechatReminderRepository.getSettings())
    }
    var wechatReminderStatus by remember {
        mutableStateOf(wechatReminderRepository.getStatus())
    }
    var notificationAccessGranted by remember {
        mutableStateOf(notificationAccessController.isGranted())
    }
    var notificationListenerConnected by remember {
        mutableStateOf(notificationAccessController.isConnected())
    }
    var reminders by remember {
        mutableStateOf(reminderRepository.getReminders())
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(applicationContext))
    }
    var exactAlarmPermissionGranted by remember {
        mutableStateOf(exactAlarmAccessController.isGranted())
    }
    var notificationBackgroundUnrestricted by remember {
        mutableStateOf(notificationSystemSettingsController.isBatteryOptimizationIgnored())
    }
    var cleanupStatus by remember {
        mutableStateOf<LocalCleanupStatus>(localCleanupManager.getStatus())
    }
    var launchableApps by remember {
        mutableStateOf<List<LaunchableApp>>(emptyList())
    }
    var isLoadingApps by remember {
        mutableStateOf(true)
    }
    var isWebsiteFullscreen by remember {
        mutableStateOf(false)
    }
    var isEbookImmersive by remember {
        mutableStateOf(false)
    }
    var lastHomeBackAtMillis by remember {
        mutableLongStateOf(0L)
    }
    val currentSection = AppSection.entries.firstOrNull {
        it.name == currentSectionName
    } ?: AppSection.HOME
    val featureCenterPage = FeatureCenterPage.entries.firstOrNull { page ->
        page.name == featureCenterPageName
    } ?: FeatureCenterPage.OVERVIEW
    // 离开电子书详情时强制恢复外层导航，防止异常返回路径把沉浸状态遗留到其他页面。
    LaunchedEffect(currentSection, featureCenterPage) {
        if (
            currentSection != AppSection.FEATURES ||
            featureCenterPage != FeatureCenterPage.EBOOKS
        ) {
            isEbookImmersive = false
        }
    }

    // 启动后清理上次取消编辑可能遗留的临时背景图，只保留当前网站模型仍在引用的文件。
    LaunchedEffect(websiteBackgroundStore) {
        withContext(Dispatchers.IO) {
            websiteBackgroundStore.cleanupUnused(
                websiteLibrary.websites.mapNotNull { website ->
                    website.backgroundImageFileName
                }.toSet()
            )
        }
    }
    val homeEnglishScope = primaryEnglishWordsForSelection(
        words = englishWords,
        selection = englishLearningSelection
    )
    val homeEnglishWord = homeEnglishScope.firstOrNull { word ->
        word.id == homeEnglishWordId && word.learnedCount < ENGLISH_WORD_MASTERY_COUNT
    } ?: englishWordRepository.chooseNextWord(
        words = englishWords,
        previousWordId = homeEnglishWordId,
        selection = englishLearningSelection
    )
    val saveEnglishLearningSelection: (PrimaryEnglishSelection) -> Boolean = { newSelection ->
        val saved = englishWordRepository.saveLearningSelection(newSelection)
        if (saved) {
            englishLearningSelection = newSelection
            homeEnglishWordId = englishWordRepository.chooseNextWord(
                words = englishWords,
                previousWordId = null,
                selection = newSelection
            )?.id
        }
        saved
    }
    val markEnglishWordLearned: (String) -> Boolean = { wordId ->
        val saved = englishWordRepository.markLearned(wordId)
        if (saved) {
            val refreshedWords = englishWordRepository.getWords()
            englishWords = refreshedWords
            homeEnglishWordId = englishWordRepository.chooseNextWord(
                words = refreshedWords,
                previousWordId = wordId,
                selection = englishLearningSelection
            )?.id
            companionProgress = companionRepository.claimTask(
                task = CompanionTask.ENGLISH_LEARN,
                currentEpochDay = LocalDate.now().toEpochDay()
            )
        }
        saved
    }
    val resetEnglishWord: (String) -> Boolean = { wordId ->
        val saved = englishWordRepository.resetWord(wordId)
        if (saved) {
            val refreshedWords = englishWordRepository.getWords()
            englishWords = refreshedWords
            homeEnglishWordId = englishWordRepository.chooseNextWord(
                words = refreshedWords,
                previousWordId = homeEnglishWordId,
                selection = englishLearningSelection
            )?.id
        }
        saved
    }

    // 桌面小组件发出的请求只消费一次；默认返回首页，避免重组后重复改变用户当前页面。
    LaunchedEffect(openTodayRequest) {
        if (openTodayRequest) {
            detailReturnSectionName = AppSection.HOME.name
            currentSectionName = AppSection.TODAY.name
            onOpenTodayRequestConsumed()
        }
    }

    // 通知内容点击必须直接回到正在朗读的书，而不是只打开App首页。这里复用全局搜索已经验证过的
    // 电子书深链状态，目标不存在时EbookScreen会安全留在书架并消费请求。
    LaunchedEffect(openEbookRequestId) {
        if (openEbookRequestId.isNotBlank()) {
            searchEnglishWordTargetId = ""
            searchNotebookArticleTargetId = ""
            searchEbookTargetId = openEbookRequestId
            featureCenterPageName = FeatureCenterPage.EBOOKS.name
            currentSectionName = AppSection.FEATURES.name
            onOpenEbookRequestConsumed()
        }
    }
    val notificationAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        notificationAccessGranted = notificationAccessController.isGranted()
        if (notificationAccessGranted) {
            notificationAccessController.requestRebindIfGranted()
        }
        notificationListenerConnected = notificationAccessController.isConnected()
        wechatReminderStatus = wechatReminderRepository.getStatus()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted ||
            isNotificationPermissionGranted(applicationContext)
    }
    val exactAlarmPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        exactAlarmPermissionGranted = exactAlarmAccessController.isGranted()
        if (exactAlarmPermissionGranted) {
            reminders = reminderRepository.getReminders()
            reminderScheduler.reschedule(reminders)
            val currentSettings = wechatReminderRepository.getSettings()
            val currentStatus = wechatReminderRepository.getStatus()
            if (currentSettings.enabled && currentStatus.pendingNotificationCount > 0) {
                wechatReminderScheduler.restore(
                    intervalMinutes = currentSettings.intervalMinutes,
                    savedTriggerAtMillis = currentStatus.nextReminderAtMillis
                )
            }
        }
    }
    val notificationSystemSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        notificationPermissionGranted = isNotificationPermissionGranted(applicationContext)
        notificationBackgroundUnrestricted =
            notificationSystemSettingsController.isBatteryOptimizationIgnored()
    }

    // 监听通知服务写入的待查看数量和提醒时间，让已打开的“更多”页面能够即时刷新。
    DisposableEffect(wechatReminderRepository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            wechatReminderSettings = wechatReminderRepository.getSettings()
            wechatReminderStatus = wechatReminderRepository.getStatus()
            notificationListenerConnected = notificationAccessController.isConnected()
        }
        wechatReminderRepository.registerChangeListener(listener)

        onDispose {
            wechatReminderRepository.unregisterChangeListener(listener)
        }
    }

    // 监听通知服务和账单导入写入的账目，保证App打开时汇总与待确认列表可以即时刷新。
    DisposableEffect(ledgerRepository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            ledgerEntries = ledgerRepository.getEntries()
            pendingWechatCaptures = ledgerRepository.getPendingCaptures()
        }
        ledgerRepository.registerChangeListener(listener)

        onDispose {
            ledgerRepository.unregisterChangeListener(listener)
        }
    }

    // 覆盖安装或系统回收服务后，授权记录可能仍在但监听服务尚未重连。部分小米系统第一次请求
    // 只恢复系统记录而不会立刻回调，因此在短时间内轮询连接状态并最多补发三次重绑请求。
    LaunchedEffect(notificationAccessController) {
        if (notificationAccessController.isGranted()) {
            repeat(NOTIFICATION_REBIND_RETRY_COUNT) { attempt ->
                notificationListenerConnected = notificationAccessController.isConnected()
                if (notificationListenerConnected) {
                    return@LaunchedEffect
                }

                notificationAccessController.requestRebindIfGranted()
                delay(NOTIFICATION_REBIND_RETRY_DELAY_MILLIS)
                notificationListenerConnected = notificationAccessController.isConnected()
                if (notificationListenerConnected ||
                    attempt == NOTIFICATION_REBIND_RETRY_COUNT - 1
                ) {
                    return@LaunchedEffect
                }
            }
        }
    }

    // 监听通用通知接收器推进重复时间后的本机数据变化，让计划查询列表即时显示下一次提醒。
    DisposableEffect(reminderRepository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            reminders = reminderRepository.getReminders()
        }
        reminderRepository.registerChangeListener(listener)

        onDispose {
            reminderRepository.unregisterChangeListener(listener)
        }
    }

    // 应用列表可能较大，首次进入时在后台加载，避免阻塞Compose主线程。
    LaunchedEffect(installedAppsRepository) {
        launchableApps = installedAppsRepository.loadLaunchableApps()
        isLoadingApps = false
    }

    // App保持前台跨过零点时同步领取新一天的打开经验与1金币，仓库保证同一天只发放一次。
    LaunchedEffect(companionRepository) {
        while (isActive) {
            val currentEpochDay = LocalDate.now().toEpochDay()
            companionProgress = companionRepository.claimDailyLogin(currentEpochDay)
            delay(COMPANION_DATE_REFRESH_INTERVAL_MILLIS)
        }
    }

    // 自动删除超过90天仍未处理的支付通知摘要，不影响已经确认的正式账目。
    LaunchedEffect(ledgerRepository) {
        ledgerRepository.cleanExpiredCaptures()
        pendingWechatCaptures = ledgerRepository.getPendingCaptures()
    }

    // 每天最多一次清理七天前的本App缓存，不操作微信或其他应用的数据与进程。
    LaunchedEffect(localCleanupManager) {
        localCleanupManager.runAutomaticCleanupIfDue()
        cleanupStatus = localCleanupManager.getStatus()
        pendingWechatCaptures = ledgerRepository.getPendingCaptures()
    }

    // App启动时再次提交仍有效的通用通知，兼容应用升级或系统曾临时清除Alarm的情况。
    LaunchedEffect(reminderRepository, reminderScheduler) {
        reminderScheduler.reschedule(reminderRepository.getReminders())
    }

    // App内今日相关数据变化或从运动页返回时同步桌面小组件；小组件本身不会后台定位或联网。
    LaunchedEffect(ledgerEntries, reminders, currentSection) {
        TodayWeatherWidgetProvider.updateAll(applicationContext)
    }

    // App进程被系统回收或升级后，根据仓库保存的绝对时间恢复微信等待提醒倒计时。
    LaunchedEffect(wechatReminderRepository, wechatReminderScheduler) {
        val currentSettings = wechatReminderRepository.getSettings()
        val currentStatus = wechatReminderRepository.getStatus()
        if (currentSettings.enabled && currentStatus.pendingNotificationCount > 0) {
            wechatReminderScheduler.restore(
                intervalMinutes = currentSettings.intervalMinutes,
                savedTriggerAtMillis = currentStatus.nextReminderAtMillis
            )
        }
    }

    // 每次进入功能中心时重新读取系统授权，兼容用户从系统设置或安全中心修改权限。
    LaunchedEffect(currentSection) {
        if (currentSection == AppSection.FEATURES) {
            notificationAccessGranted = notificationAccessController.isGranted()
            notificationListenerConnected = notificationAccessController.isConnected()
            wechatReminderStatus = wechatReminderRepository.getStatus()
            reminders = reminderRepository.getReminders()
            notificationPermissionGranted =
                isNotificationPermissionGranted(applicationContext)
            exactAlarmPermissionGranted = exactAlarmAccessController.isGranted()
            notificationBackgroundUnrestricted =
                notificationSystemSettingsController.isBatteryOptimizationIgnored()
        }
    }

    // 离开首页后清空上一次退出确认，避免稍后回到首页时误把一次返回识别为第二次返回。
    LaunchedEffect(currentSection) {
        if (currentSection != AppSection.HOME) {
            lastHomeBackAtMillis = 0L
        }
    }

    // 页面内部的返回处理会优先消费全屏、WebView历史和详情层级；剩余外层页面最终逐级回到首页。
    BackHandler(enabled = currentSection != AppSection.HOME) {
        currentSectionName = resolveAppBackDestinationName(
            currentSectionName = currentSection.name,
            detailReturnSectionName = detailReturnSectionName
        )
    }

    // 首页第一次返回仅提示，确认窗口内再次返回才真正结束Activity，兼容系统按键和侧滑返回手势。
    BackHandler(enabled = currentSection == AppSection.HOME) {
        val currentBackAtMillis = SystemClock.elapsedRealtime()
        if (isHomeExitConfirmed(lastHomeBackAtMillis, currentBackAtMillis)) {
            onExitApp()
        } else {
            lastHomeBackAtMillis = currentBackAtMillis
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val automaticDismissJob = launch {
                    delay(HOME_EXIT_HINT_DURATION_MILLIS)
                    snackbarHostState.currentSnackbarData
                        ?.takeIf { data -> data.visuals.message == HOME_EXIT_HINT_MESSAGE }
                        ?.dismiss()
                }
                snackbarHostState.showSnackbar(
                    message = HOME_EXIT_HINT_MESSAGE,
                    duration = SnackbarDuration.Indefinite
                )
                automaticDismissJob.cancel()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SwipeDismissibleSnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (
                currentSection.showInBottomNavigation &&
                !isWebsiteFullscreen &&
                !isEbookImmersive
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        AppSection.entries
                            .filter { section -> section.showInBottomNavigation }
                            .forEach { section ->
                                val selected = currentSection == section
                                val iconScale by animateFloatAsState(
                                    targetValue = if (selected) 1.03f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = 0.8f,
                                        stiffness = 560f
                                    ),
                                    label = "nav_scale_${section.name}"
                                )
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (section == AppSection.FEATURES) {
                                            featureCenterPageName = FeatureCenterPage.OVERVIEW.name
                                        }
                                        currentSectionName = section.name
                                    },
                                    icon = {
                                        ThemedNavigationIcon(
                                            section = section,
                                            selected = selected,
                                            visualTheme = visualTheme,
                                            scale = iconScale
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = section.title,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor =
                                            MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor =
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor =
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
            targetState = currentSection,
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(durationMillis = 240)) +
                        slideInVertically(
                            animationSpec = tween(durationMillis = 300),
                            initialOffsetY = { fullHeight -> fullHeight / 18 }
                        ) +
                        scaleIn(
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = 380f
                            ),
                            initialScale = 0.985f
                        )
                    ).togetherWith(
                    fadeOut(animationSpec = tween(durationMillis = 140)) +
                        scaleOut(
                            animationSpec = tween(durationMillis = 160),
                            targetScale = 0.99f
                        )
                )
            },
            label = "app_page_transition"
        ) { targetSection ->
            when (targetSection) {
            AppSection.HOME -> HomeScreen(
                modifier = Modifier.padding(innerPadding),
                deviceMonitor = deviceMonitor,
                shortcuts = launchableApps.filter {
                    it.packageName in selectedPackages
                },
                onLaunchApp = { packageName ->
                    if (installedAppsRepository.launchApp(packageName)) {
                        companionProgress = companionRepository.claimTask(
                            task = CompanionTask.SHORTCUT_LAUNCH,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("应用已卸载或暂时无法打开")
                        }
                    }
                },
                onManageShortcuts = {
                    currentSectionName = AppSection.PROFILE.name
                },
                websites = homeCarouselWebsites(websiteLibrary),
                defaultWebsiteId = defaultWebsiteId,
                companionProgress = companionProgress,
                onOpenWebsite = { website ->
                    if (openWebsiteInNewTab(website)) {
                        companionProgress = companionRepository.claimTask(
                            task = CompanionTask.WEBSITE_VISIT,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                    }
                    currentSectionName = AppSection.WEBSITE.name
                },
                onOpenWebsiteDetails = {
                    detailReturnSectionName = AppSection.HOME.name
                    currentSectionName = AppSection.BOOKMARKS.name
                },
                onSetDefaultWebsite = { websiteId ->
                    val saved = websiteRepository.setDefaultWebsiteId(
                        websiteId = websiteId,
                        websites = websites
                    )
                    if (saved) {
                        defaultWebsiteId = websiteId
                    }
                    saved
                },
                selectedHomeFeatures = selectedHomeFeatures,
                onSaveHomeFeatures = { newSelection ->
                    val saved = homeFeatureRepository.saveSelectedFeatures(newSelection)
                    if (saved) {
                        selectedHomeFeatures = newSelection
                    }
                    saved
                },
                onOpenFeature = { featureId ->
                    when (featureId) {
                        HomeFeatureId.TODAY -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.TODAY.name
                        }

                        HomeFeatureId.SEARCH -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.SEARCH.name
                        }

                        HomeFeatureId.LEDGER -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.LEDGER.name
                        }

                        HomeFeatureId.FITNESS -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.FITNESS.name
                        }

                        HomeFeatureId.HOT_TOPICS -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.HOT_TOPICS.name
                        }

                        HomeFeatureId.MOBILE_DATA -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.MOBILE_DATA.name
                        }

                        HomeFeatureId.APP_USAGE -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.APP_USAGE.name
                        }

                        HomeFeatureId.BREATH_HOLD -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.BREATH_HOLD.name
                        }

                        HomeFeatureId.WECHAT_REMINDER -> {
                            featureCenterPageName = FeatureCenterPage.WECHAT_REMINDER.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.GENERAL_REMINDER -> {
                            featureCenterPageName = FeatureCenterPage.GENERAL_REMINDER.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.BACKUP -> {
                            detailReturnSectionName = AppSection.HOME.name
                            currentSectionName = AppSection.BACKUP.name
                        }

                        HomeFeatureId.LOCAL_CLEANUP -> {
                            featureCenterPageName = FeatureCenterPage.LOCAL_CLEANUP.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.ENGLISH_WORDS -> {
                            featureCenterPageName = FeatureCenterPage.ENGLISH_WORDS.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.NOTEBOOK -> {
                            featureCenterPageName = FeatureCenterPage.NOTEBOOK.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.EBOOKS -> {
                            featureCenterPageName = FeatureCenterPage.EBOOKS.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.CHINESE_GROWTH -> {
                            featureCenterPageName = FeatureCenterPage.CHINESE_GROWTH.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.QR_SCANNER -> {
                            featureCenterPageName = FeatureCenterPage.QR_SCANNER.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.FLASHLIGHT -> {
                            featureCenterPageName = FeatureCenterPage.FLASHLIGHT.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.MAO_QUOTES -> {
                            featureCenterPageName = FeatureCenterPage.MAO_QUOTES.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        HomeFeatureId.DUAL_CAMERA -> {
                            featureCenterPageName = FeatureCenterPage.DUAL_CAMERA.name
                            currentSectionName = AppSection.FEATURES.name
                        }
                    }
                },
                englishWord = homeEnglishWord,
                englishRemainingCount = homeEnglishScope.count { word ->
                    word.learnedCount < ENGLISH_WORD_MASTERY_COUNT
                },
                englishTtsState = englishTtsState,
                onSpeakEnglish = offlineEnglishTts::speak,
                onMarkEnglishWordLearned = markEnglishWordLearned,
                onSaveWebsite = { website: WebsiteShortcut ->
                    val normalizedUrl = normalizeWebsiteUrl(website.url)
                    if (website.title.trim().isBlank() || normalizedUrl == null) {
                        false
                    } else {
                        val normalizedWebsite = website.copy(
                            title = website.title.trim(),
                            url = normalizedUrl
                        )
                        val existingIndex = websites.indexOfFirst { currentWebsite ->
                            currentWebsite.id == normalizedWebsite.id
                        }
                        val websiteToPersist = if (existingIndex >= 0) {
                            normalizedWebsite
                        } else {
                            // 首页快捷新增统一进入根目录末尾，并默认加入首页轮播。
                            val nextRootOrder = (
                                websiteLibrary.folders
                                    .filter { folder -> folder.parentId == null }
                                    .map { folder -> folder.sortOrder } +
                                    websites
                                        .filter { currentWebsite -> currentWebsite.folderId == null }
                                        .map { currentWebsite -> currentWebsite.sortOrder }
                                ).maxOrNull()?.plus(10) ?: 0
                            normalizedWebsite.copy(
                                folderId = null,
                                showOnHome = true,
                                sortOrder = nextRootOrder
                            )
                        }
                        val updatedWebsites = websites.toMutableList().apply {
                            if (existingIndex >= 0) {
                                set(existingIndex, websiteToPersist)
                            } else {
                                add(websiteToPersist)
                            }
                        }
                        val newWebsiteLibrary = websiteLibrary.copy(websites = updatedWebsites)
                        val saved = websiteRepository.saveLibrary(newWebsiteLibrary)
                        if (saved) {
                            websiteLibrary = newWebsiteLibrary
                            websiteBackgroundStore.cleanupUnused(
                                newWebsiteLibrary.websites.mapNotNull { currentWebsite ->
                                    currentWebsite.backgroundImageFileName
                                }.toSet()
                            )
                            if (defaultWebsiteId == null) {
                                val firstWebsiteId = updatedWebsites.firstOrNull()?.id
                                websiteRepository.setDefaultWebsiteId(
                                    websiteId = firstWebsiteId,
                                    websites = updatedWebsites
                                )
                                defaultWebsiteId = firstWebsiteId
                            }
                        }
                        saved
                    }
                },
                onDeleteWebsite = { websiteId ->
                    val updatedWebsites = websites.filterNot { website ->
                        website.id == websiteId
                    }
                    val newWebsiteLibrary = websiteLibrary.copy(websites = updatedWebsites)
                    val deleted = updatedWebsites.size != websites.size &&
                        websiteRepository.saveLibrary(newWebsiteLibrary)
                    if (deleted) {
                        websiteLibrary = newWebsiteLibrary
                        websiteBackgroundStore.cleanupUnused(
                            newWebsiteLibrary.websites.mapNotNull { currentWebsite ->
                                currentWebsite.backgroundImageFileName
                            }.toSet()
                        )
                        if (defaultWebsiteId == websiteId) {
                            val fallbackWebsiteId = updatedWebsites.firstOrNull()?.id
                            websiteRepository.setDefaultWebsiteId(
                                websiteId = fallbackWebsiteId,
                                websites = updatedWebsites
                            )
                            defaultWebsiteId = fallbackWebsiteId
                        }
                    }
                    deleted
                },
                onSelectCompanionCategory = { category: CompanionCategory ->
                    val updatedProgress = companionRepository.selectCategory(
                        category = category,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                    if (updatedProgress != null) {
                        companionProgress = updatedProgress
                        true
                    } else {
                        false
                    }
                },
                onPurchaseCompanionItem = { item ->
                    val result = companionRepository.purchaseItem(
                        item = item,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                    companionProgress = result.progress
                    result
                },
                onUseCompanionItem = { item ->
                    val result = companionRepository.useItem(
                        item = item,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                    companionProgress = result.progress
                    result
                },
                onCompleteCompanionInteraction = { interaction: CompanionInteraction ->
                    val result = companionRepository.completeInteraction(
                        interaction = interaction,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                    companionProgress = result.progress
                    result
                },
                onQuickSearch = { query ->
                    pendingGlobalSearchQuery = query
                    detailReturnSectionName = AppSection.HOME.name
                    currentSectionName = AppSection.SEARCH.name
                },
                onOpenQrScanner = {
                    detailReturnSectionName = AppSection.HOME.name
                    featureCenterPageName = FeatureCenterPage.QR_SCANNER.name
                    currentSectionName = AppSection.FEATURES.name
                }
            )

            AppSection.TODAY -> TodayOverviewScreen(
                modifier = Modifier.padding(innerPadding),
                ledgerRepository = ledgerRepository,
                reminderRepository = reminderRepository,
                fitnessRepository = fitnessRepository,
                wechatReminderRepository = wechatReminderRepository,
                weatherRepository = weatherRepository,
                locationProvider = deviceLocationProvider,
                coroutineScope = coroutineScope,
                onWeatherUpdated = {
                    TodayWeatherWidgetProvider.updateAll(applicationContext)
                },
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.SEARCH -> GlobalSearchScreen(
                modifier = Modifier.padding(innerPadding),
                repository = globalSearchRepository,
                englishWords = englishWords,
                launchableApps = launchableApps,
                initialQuery = pendingGlobalSearchQuery,
                onInitialQueryConsumed = {
                    pendingGlobalSearchQuery = ""
                },
                onOpenResult = { result ->
                    when (result.type) {
                        LocalSearchType.LEDGER,
                        LocalSearchType.WECHAT_CAPTURE -> {
                            detailReturnSectionName = AppSection.SEARCH.name
                            currentSectionName = AppSection.LEDGER.name
                        }

                        LocalSearchType.REMINDER -> {
                            featureCenterPageName = FeatureCenterPage.GENERAL_REMINDER.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        LocalSearchType.FITNESS -> {
                            detailReturnSectionName = AppSection.SEARCH.name
                            currentSectionName = AppSection.FITNESS.name
                        }

                        LocalSearchType.WEBSITE -> {
                            websites.firstOrNull { website ->
                                website.id == result.targetValue
                            }?.let { website ->
                                openWebsiteInNewTab(website)
                                currentSectionName = AppSection.WEBSITE.name
                            }
                        }

                        LocalSearchType.ENGLISH_WORD -> {
                            searchEnglishWordTargetId = result.targetValue
                            searchNotebookArticleTargetId = ""
                            searchEbookTargetId = ""
                            featureCenterPageName = FeatureCenterPage.ENGLISH_WORDS.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        LocalSearchType.NOTEBOOK -> {
                            searchNotebookArticleTargetId = result.targetValue
                            searchEnglishWordTargetId = ""
                            searchEbookTargetId = ""
                            featureCenterPageName = FeatureCenterPage.NOTEBOOK.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        LocalSearchType.EBOOK -> {
                            searchEbookTargetId = result.targetValue
                            searchNotebookArticleTargetId = ""
                            searchEnglishWordTargetId = ""
                            featureCenterPageName = FeatureCenterPage.EBOOKS.name
                            currentSectionName = AppSection.FEATURES.name
                        }

                        LocalSearchType.APP -> {
                            if (!installedAppsRepository.launchApp(result.targetValue)) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("无法打开该应用，请确认应用仍已安装")
                                }
                            }
                        }

                        LocalSearchType.FEATURE -> {
                            val featurePage = FeatureCenterPage.entries.firstOrNull { page ->
                                page.name == result.targetValue
                            }
                            val appSection = AppSection.entries.firstOrNull { section ->
                                section.name == result.targetValue
                            }
                            when {
                                featurePage != null -> {
                                    searchEnglishWordTargetId = ""
                                    searchNotebookArticleTargetId = ""
                                    searchEbookTargetId = ""
                                    featureCenterPageName = featurePage.name
                                    currentSectionName = AppSection.FEATURES.name
                                }

                                appSection != null -> {
                                    detailReturnSectionName = AppSection.SEARCH.name
                                    currentSectionName = appSection.name
                                }
                            }
                        }
                    }
                },
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.BACKUP -> BackupScreen(
                modifier = Modifier.padding(innerPadding),
                manager = backupManager,
                coroutineScope = coroutineScope,
                onRestoreCompleted = {
                    // 先清除导入前计划对应的Alarm，再以新手机恢复后的计划完整重建。
                    reminders.forEach { reminder ->
                        reminderScheduler.cancel(reminder.id)
                    }

                    ledgerEntries = ledgerRepository.getEntries()
                    pendingWechatCaptures = ledgerRepository.getPendingCaptures()
                    selectedPackages = shortcutRepository.getSelectedPackages()
                    selectedHomeFeatures = homeFeatureRepository.getSelectedFeatures()
                    val restoredEnglishWords = englishWordRepository.getWords()
                    val restoredEnglishSelection = englishWordRepository.getLearningSelection()
                    englishWords = restoredEnglishWords
                    englishLearningSelection = restoredEnglishSelection
                    homeEnglishWordId = englishWordRepository.chooseNextWord(
                        words = restoredEnglishWords,
                        previousWordId = null,
                        selection = restoredEnglishSelection
                    )?.id

                    val restoredWebsiteLibrary = websiteRepository.getLibrary()
                    val restoredWebsites = restoredWebsiteLibrary.websites
                    val restoredDefaultWebsiteId =
                        websiteRepository.getDefaultWebsiteId(restoredWebsites)
                    websiteLibrary = restoredWebsiteLibrary
                    websiteBackgroundStore.cleanupUnused(
                        restoredWebsiteLibrary.websites.mapNotNull { website ->
                            website.backgroundImageFileName
                        }.toSet()
                    )
                    defaultWebsiteId = restoredDefaultWebsiteId

                    companionProgress = companionRepository.getProgress(
                        LocalDate.now().toEpochDay()
                    )
                    wechatReminderSettings = wechatReminderRepository.getSettings()
                    wechatReminderScheduler.cancel()
                    wechatReminderStatus = wechatReminderRepository.getStatus()
                    reminders = reminderRepository.getReminders()
                    reminderScheduler.reschedule(reminders)
                    cleanupStatus = localCleanupManager.getStatus()
                    notificationAccessGranted = notificationAccessController.isGranted()
                    notificationListenerConnected = notificationAccessController.isConnected()
                    notificationPermissionGranted =
                        isNotificationPermissionGranted(applicationContext)
                    exactAlarmPermissionGranted = exactAlarmAccessController.isGranted()

                    TodayWeatherWidgetProvider.updateAll(applicationContext)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("本地数据恢复成功")
                    }
                },
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.HOT_TOPICS -> HotTopicsScreen(
                modifier = Modifier.padding(innerPadding),
                repository = hotTopicRepository,
                onOpenTopic = { topic: HotTopic ->
                    if (hotTopicLauncher.open(topic)) {
                        companionProgress = companionRepository.claimTask(
                            task = CompanionTask.HOT_TOPIC_VIEW,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "无法打开原平台，请确认已安装浏览器后重试"
                            )
                        }
                    }
                },
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.MOBILE_DATA -> MobileDataUsageScreen(
                modifier = Modifier.padding(innerPadding),
                controller = mobileDataUsageController,
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.APP_USAGE -> AppUsageScreen(
                modifier = Modifier.padding(innerPadding),
                controller = appUsageController,
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.BREATH_HOLD -> BreathHoldGameScreen(
                modifier = Modifier.padding(innerPadding),
                repository = breathHoldRepository,
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            )

            AppSection.FEATURES -> FeatureCenterScreen(
                modifier = Modifier.padding(innerPadding),
                page = featureCenterPage,
                overviewGridState = featureCenterGridState,
                overviewRestoreIndex = featureCenterRestoreIndex,
                overviewRestoreOffset = featureCenterRestoreOffset,
                onOverviewPositionCaptured = { index, offset ->
                    featureCenterRestoreIndex = index
                    featureCenterRestoreOffset = offset
                },
                featureOrder = featureCenterOrder,
                onFeatureOrderChanged = { newOrder ->
                    val success = featureCenterOrderRepository.saveOrder(newOrder)
                    if (success) {
                        featureCenterOrder = newOrder
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("功能顺序保存失败，请重试")
                        }
                    }
                    success
                },
                onPageChanged = { page ->
                    featureCenterPageName = page.name
                },
                onOpenLedger = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.LEDGER.name
                },
                onOpenFitness = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.FITNESS.name
                },
                onOpenHotTopics = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.HOT_TOPICS.name
                },
                onOpenMobileData = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.MOBILE_DATA.name
                },
                onOpenAppUsage = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.APP_USAGE.name
                },
                onOpenBreathHold = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.BREATH_HOLD.name
                },
                onOpenToday = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.TODAY.name
                },
                onOpenSearch = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.SEARCH.name
                },
                onOpenBackup = {
                    detailReturnSectionName = AppSection.FEATURES.name
                    currentSectionName = AppSection.BACKUP.name
                },
                englishWords = englishWords,
                englishSelection = englishLearningSelection,
                onEnglishSelectionChanged = saveEnglishLearningSelection,
                englishTtsState = englishTtsState,
                onSpeakEnglish = offlineEnglishTts::speak,
                onMarkEnglishWordLearned = markEnglishWordLearned,
                onResetEnglishWord = resetEnglishWord,
                initialEnglishWordId = searchEnglishWordTargetId.ifBlank { null },
                initialNotebookArticleId = searchNotebookArticleTargetId.ifBlank { null },
                initialEbookId = searchEbookTargetId.ifBlank { null },
                ebookRepository = ebookRepository,
                chineseGrowthRepository = chineseGrowthRepository,
                onEbookImmersiveChanged = { immersive ->
                    isEbookImmersive = immersive
                },
                onNotebookArticlePublished = {
                    companionProgress = companionRepository.claimTask(
                        task = CompanionTask.NOTEBOOK_PUBLISH,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                },
                onEbookReadingDuration = { elapsedMillis ->
                    companionProgress = companionRepository.recordReading(
                        elapsedMillis = elapsedMillis,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                },
                onInitialSearchTargetConsumed = {
                    searchEnglishWordTargetId = ""
                    searchNotebookArticleTargetId = ""
                    searchEbookTargetId = ""
                },
                wechatReminderSettings = wechatReminderSettings,
                wechatReminderStatus = wechatReminderStatus,
                notificationAccessGranted = notificationAccessGranted,
                notificationListenerConnected = notificationListenerConnected,
                notificationBackgroundUnrestricted = notificationBackgroundUnrestricted,
                onSaveWechatReminderSettings = { newSettings: WechatReminderSettings ->
                    val success = wechatReminderRepository.saveSettings(newSettings)
                    if (success) {
                        wechatReminderSettings = wechatReminderRepository.getSettings()
                        wechatReminderStatus = wechatReminderRepository.getStatus()
                        if (!wechatReminderSettings.enabled ||
                            wechatReminderStatus.pendingNotificationCount <= 0
                        ) {
                            wechatReminderScheduler.cancel()
                        } else {
                            wechatReminderScheduler.schedule(
                                wechatReminderSettings.intervalMinutes
                            )
                        }
                    }
                    success
                },
                onOpenNotificationAccess = {
                    notificationAccessLauncher.launch(
                        notificationAccessController.createSettingsIntent()
                    )
                },
                onChooseWechatReminderSound = {
                    reminderSoundPickerTargetName = ReminderSoundTarget.WECHAT.name
                },
                onChooseScheduledReminderSound = {
                    reminderSoundPickerTargetName = ReminderSoundTarget.SCHEDULED.name
                },
                onRequestNotificationBackgroundAccess = {
                    notificationSystemSettingsLauncher.launch(
                        notificationSystemSettingsController.createBatteryOptimizationIntent()
                    )
                },
                onTestScheduledNotificationNow = {
                    notificationTestResultMessage(
                        result = notificationTestController.publishNow(
                            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
                        ),
                        backgroundTest = false
                    )
                },
                onTestWechatNotificationNow = {
                    notificationTestResultMessage(
                        result = notificationTestController.publishNow(
                            NotificationAlertChannels.WECHAT_REMINDER_CHANNEL_ID
                        ),
                        backgroundTest = false
                    )
                },
                onScheduleBackgroundNotificationTest = {
                    notificationTestResultMessage(
                        result = notificationTestController.scheduleBackgroundTest(),
                        backgroundTest = true
                    )
                },
                reminders = reminders,
                notificationPermissionGranted = notificationPermissionGranted,
                exactAlarmPermissionGranted = exactAlarmPermissionGranted,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationPermissionGranted = true
                    }
                },
                onRequestExactAlarmPermission = {
                    exactAlarmPermissionLauncher.launch(
                        exactAlarmAccessController.createSettingsIntent()
                    )
                },
                onSaveReminder = { reminder: ScheduledReminder ->
                    val previousReminder = reminder.id
                        .takeIf { reminderId -> reminderId > 0L }
                        ?.let(reminderRepository::getReminder)
                    if (reminder.id > 0L) {
                        reminderScheduler.cancel(reminder.id)
                    }
                    val savedReminder = reminderRepository.upsertReminder(reminder)
                    val success = when {
                        savedReminder == null -> {
                            previousReminder?.let(reminderScheduler::schedule)
                            false
                        }
                        reminderScheduler.schedule(savedReminder) -> true
                        previousReminder != null -> {
                            reminderRepository.upsertReminder(previousReminder)
                            reminderScheduler.schedule(previousReminder)
                            false
                        }
                        else -> {
                            reminderRepository.deleteReminder(savedReminder.id)
                            false
                        }
                    }
                    reminders = reminderRepository.getReminders()
                    success
                },
                onDeleteReminder = { reminderId ->
                    val previousReminder = reminderRepository.getReminder(reminderId)
                    reminderScheduler.cancel(reminderId)
                    val success = reminderRepository.deleteReminder(reminderId)
                    if (!success) {
                        previousReminder?.let(reminderScheduler::schedule)
                    }
                    reminders = reminderRepository.getReminders()
                    success
                },
                cleanupStatus = cleanupStatus,
                onSetAutomaticCleanup = { enabled ->
                    val success = localCleanupManager.setAutomaticEnabled(enabled)
                    if (success) {
                        cleanupStatus = localCleanupManager.getStatus()
                    }
                    success
                },
                onMeasureAppCache = {
                    localCleanupManager.getReclaimableBytes()
                },
                onCleanAppNow = {
                    val result = localCleanupManager.cleanNow()
                    cleanupStatus = localCleanupManager.getStatus()
                    pendingWechatCaptures = ledgerRepository.getPendingCaptures()
                    result
                },
                onOpenSystemStorage = {
                    if (!systemStorageController.open()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("无法打开系统存储管理页面")
                        }
                    }
                }
            )

            AppSection.LEDGER -> FeatureDetailScaffold(
                modifier = Modifier.padding(innerPadding),
                title = "记账",
                subtitle = "账目记录、汇总分析与微信账单处理",
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            ) { contentModifier ->
                LedgerScreen(
                modifier = contentModifier,
                entries = ledgerEntries,
                onSaveEntry = { entry ->
                    val isNewEntry = entry.id == 0L
                    val success = ledgerRepository.upsertEntry(entry)
                    if (success) {
                        if (entry.externalKey.isNotBlank()) {
                            ledgerRepository.deletePendingCapture(entry.externalKey)
                        }
                        ledgerEntries = ledgerRepository.getEntries()
                        pendingWechatCaptures = ledgerRepository.getPendingCaptures()
                        if (isNewEntry) {
                            companionProgress = companionRepository.claimTask(
                                task = CompanionTask.LEDGER_ENTRY,
                                currentEpochDay = LocalDate.now().toEpochDay()
                            )
                        }
                    }
                    success
                },
                onDeleteEntry = { entryId ->
                    val success = ledgerRepository.deleteEntry(entryId)
                    if (success) {
                        ledgerEntries = ledgerRepository.getEntries()
                    }
                    success
                },
                pendingCaptures = pendingWechatCaptures,
                notificationAccessGranted = notificationAccessGranted,
                onOpenNotificationAccess = {
                    notificationAccessLauncher.launch(
                        notificationAccessController.createSettingsIntent()
                    )
                },
                onImportWechatBill = { uri ->
                    val result = wechatBillImporter.importFromUri(uri)
                    ledgerEntries = ledgerRepository.getEntries()
                    pendingWechatCaptures = ledgerRepository.getPendingCaptures()
                    result
                },
                onIgnoreWechatCapture = { capture: WechatCapture ->
                    val success = ledgerRepository.deletePendingCapture(capture.externalKey)
                    if (success) {
                        pendingWechatCaptures = ledgerRepository.getPendingCaptures()
                    }
                    success
                }
                )
            }

            AppSection.FITNESS -> FeatureDetailScaffold(
                modifier = Modifier.padding(innerPadding),
                title = "运动",
                subtitle = "运动目标、每日记录与日期区间分析",
                onBack = {
                    currentSectionName = detailReturnSectionName
                }
            ) { contentModifier ->
                FitnessScreen(modifier = contentModifier)
            }

            AppSection.BOOKMARKS -> WebsiteBookmarkManagerScreen(
                modifier = Modifier.padding(innerPadding),
                library = websiteLibrary,
                defaultWebsiteId = defaultWebsiteId,
                onBack = {
                    currentSectionName = detailReturnSectionName
                },
                onOpenWebsite = { website ->
                    if (openWebsiteInNewTab(website)) {
                        companionProgress = companionRepository.claimTask(
                            task = CompanionTask.WEBSITE_VISIT,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                    }
                    currentSectionName = AppSection.WEBSITE.name
                },
                onSetDefaultWebsite = { websiteId ->
                    val saved = websiteRepository.setDefaultWebsiteId(
                        websiteId = websiteId,
                        websites = websites
                    )
                    if (saved) {
                        defaultWebsiteId = websiteId
                    }
                    saved
                },
                onSaveLibrary = { updatedLibrary: WebsiteLibrary ->
                    val saved = websiteRepository.saveLibrary(updatedLibrary)
                    if (saved) {
                        websiteLibrary = updatedLibrary
                        websiteBackgroundStore.cleanupUnused(
                            updatedLibrary.websites.mapNotNull { website ->
                                website.backgroundImageFileName
                            }.toSet()
                        )

                        // 删除默认网站时同步选择新的首项，保证底部“网站”始终有稳定入口。
                        val resolvedDefaultWebsiteId = websiteRepository.getDefaultWebsiteId(
                            updatedLibrary.websites
                        )
                        if (resolvedDefaultWebsiteId != defaultWebsiteId) {
                            websiteRepository.setDefaultWebsiteId(
                                websiteId = resolvedDefaultWebsiteId,
                                websites = updatedLibrary.websites
                            )
                            defaultWebsiteId = resolvedDefaultWebsiteId
                        }
                    }
                    saved
                }
            )

            // 真正的网站浏览器在AnimatedContent外常驻；这里仅保留一级页面的动画占位。
            AppSection.WEBSITE -> Box(modifier = Modifier.fillMaxSize())

            AppSection.PROFILE -> ProfileScreen(
                modifier = Modifier.padding(innerPadding),
                isDarkTheme = isDarkTheme,
                visualTheme = visualTheme,
                startupAnimationEnabled = startupAnimationEnabled,
                onSetDarkTheme = onSetDarkTheme,
                onSetVisualTheme = onSetVisualTheme,
                onSetStartupAnimationEnabled = onSetStartupAnimationEnabled,
                deviceMonitor = deviceMonitor,
                apps = launchableApps,
                selectedPackages = selectedPackages,
                isLoading = isLoadingApps,
                onSelectionChanged = { newSelection ->
                    if (shortcutRepository.saveSelectedPackages(newSelection)) {
                        selectedPackages = newSelection
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("快捷应用保存失败，请重试")
                        }
                    }
                },
                onOpenProjectSource = {
                    openWebsiteInNewTab(PROJECT_SOURCE_WEBSITE)
                    currentSectionName = AppSection.WEBSITE.name
                },
                developerModeConfigured = developerModeRepository.isConfigured(),
                developerModeEnabled = developerModeEnabled,
                companionProgress = companionProgress,
                onVerifyDeveloperKey = { key ->
                    val verified = developerModeRepository.verifyAndEnable(key)
                    developerModeEnabled = verified
                    verified
                },
                onDisableDeveloperMode = {
                    val disabled = developerModeRepository.disable()
                    if (disabled) developerModeEnabled = false
                    disabled
                },
                onAddDeveloperLevels = { levels ->
                    if (!developerModeEnabled || !developerModeRepository.isEnabled()) {
                        false
                    } else {
                        val before = companionProgress
                        val updated = companionRepository.addDeveloperLevels(
                            levels = levels,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                        companionProgress = updated
                        updated != before
                    }
                },
                onAddDeveloperCoins = { coins ->
                    if (!developerModeEnabled || !developerModeRepository.isEnabled()) {
                        false
                    } else {
                        val before = companionProgress
                        val updated = companionRepository.addDeveloperCoins(
                            coinsToAdd = coins,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                        companionProgress = updated
                        updated != before
                    }
                }
            )
            }
        }

            // 浏览器必须长期留在Scaffold内容树中，才能在切换“首页/我的”后继续复用同一批WebView。
            // 不可见时WebsiteScreen只保留已经访问过的标签对象，不绘制AndroidView或拦截返回事件。
            WebsiteScreen(
                modifier = when {
                    currentSection != AppSection.WEBSITE -> Modifier.size(0.dp)
                    isWebsiteFullscreen -> Modifier.fillMaxSize()
                    else -> Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                },
                session = websiteBrowserSession,
                websites = websites,
                isVisible = currentSection == AppSection.WEBSITE,
                isFullscreen = isWebsiteFullscreen,
                onSelectTab = { tabId ->
                    val updatedSession = selectWebsiteBrowserTab(
                        session = websiteBrowserSession,
                        tabId = tabId
                    )
                    if (updatedSession != websiteBrowserSession) {
                        websiteBrowserSession = updatedSession
                    }
                },
                onCloseTab = { tabId ->
                    val updatedSession = closeWebsiteBrowserTab(
                        session = websiteBrowserSession,
                        tabId = tabId,
                        replacementBlankTabId = UUID.randomUUID().toString()
                    )
                    if (updatedSession != websiteBrowserSession) {
                        websiteBrowserSession = updatedSession
                    }
                },
                onCreateBlankTab = {
                    val updatedSession = createBlankWebsiteBrowserTab(
                        session = websiteBrowserSession,
                        tabId = UUID.randomUUID().toString()
                    )
                    if (updatedSession != websiteBrowserSession) {
                        websiteBrowserSession = updatedSession
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(WEBSITE_BROWSER_TAB_LIMIT_MESSAGE)
                        }
                    }
                },
                onOpenWebsiteInTab = { tabId, website ->
                    val updatedSession = fillWebsiteBrowserTab(
                        session = websiteBrowserSession,
                        tabId = tabId,
                        website = website
                    )
                    if (updatedSession != websiteBrowserSession) {
                        websiteBrowserSession = updatedSession
                        companionProgress = companionRepository.claimTask(
                            task = CompanionTask.WEBSITE_VISIT,
                            currentEpochDay = LocalDate.now().toEpochDay()
                        )
                    }
                },
                onOpenChildTab = { sourceTab, url ->
                    openWebsiteChildTab(sourceTab.id, url)
                },
                onTabPageChanged = { tabId, title, url ->
                    val updatedSession = updateWebsiteBrowserTab(
                        session = websiteBrowserSession,
                        tabId = tabId,
                        title = title,
                        url = url
                    )
                    if (updatedSession != websiteBrowserSession) {
                        websiteBrowserSession = updatedSession
                    }
                },
                onExitWebsite = {
                    currentSectionName = AppSection.HOME.name
                },
                onFullscreenChanged = { isFullscreen ->
                    if (isWebsiteFullscreen != isFullscreen) {
                        isWebsiteFullscreen = isFullscreen
                    }
                },
                onEbookDownloadRequested = { request ->
                    coroutineScope.launch {
                        val progressMessage = launch {
                            snackbarHostState.showSnackbar("正在下载并导入电子书…")
                        }
                        val result = ebookRepository.importFromWebDownload(request)
                        progressMessage.cancel()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        websiteEbookImportResult = result
                    }
                },
                onBookmarkCurrentPage = bookmarkCurrentPage@ { pageTitle, pageUrl ->
                    val result = websiteRepository.saveBookmarkedPage(pageTitle, pageUrl)
                    if (result == WebsiteBookmarkSaveResult.SAVED) {
                        val refreshedLibrary = websiteRepository.getLibrary()
                        websiteLibrary = refreshedLibrary
                        defaultWebsiteId = websiteRepository.getDefaultWebsiteId(
                            refreshedLibrary.websites
                        )
                    }
                    result
                },
                onManageWebsites = {
                    detailReturnSectionName = AppSection.WEBSITE.name
                    currentSectionName = AppSection.BOOKMARKS.name
                }
            )
        }
    }

    websiteEbookImportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { websiteEbookImportResult = null },
            title = { Text(if (result.success) "电子书导入成功" else "电子书导入失败") },
            text = { Text(result.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        websiteEbookImportResult = null
                        if (result.success) {
                            featureCenterPageName = FeatureCenterPage.EBOOKS.name
                            currentSectionName = AppSection.FEATURES.name
                        }
                    }
                ) {
                    Text(if (result.success) "打开书库" else "知道了")
                }
            },
            dismissButton = if (result.success) {
                {
                    TextButton(onClick = { websiteEbookImportResult = null }) {
                        Text("稍后查看")
                    }
                }
            } else {
                null
            }
        )
    }

    ReminderSoundTarget.entries.firstOrNull { target ->
        target.name == reminderSoundPickerTargetName
    }?.let { target ->
        ReminderSoundPickerDialog(
            target = target,
            initialSound = reminderSoundRepository.getSound(target),
            onDismiss = {
                reminderSoundPickerTargetName = ""
            },
            onConfirm = { selectedSound ->
                reminderSoundRepository.saveSound(target, selectedSound)
            }
        )
    }
}

/**
 * 判断本应用是否具备展示微信等待提醒和通用定时提醒的通知权限。
 *
 * 使用方法：
 * Android 13以下系统无需运行时权限，直接返回true；Android 13及以上使用ContextCompat检查
 * POST_NOTIFICATIONS。页面进入和系统权限回调后均应重新调用。
 *
 * @param context Android上下文。
 *
 * @return 当前可以发布提醒通知返回true，否则返回false。
 */
private fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * 把通知测试结果转换为用户可直接执行下一步操作的中文反馈。
 *
 * 使用方法：
 * 立即测试或10秒后台测试结束调度后调用。失败提示会区分运行时权限、App总开关、当前强提醒
 * 渠道和系统异常，避免所有问题都只显示“测试失败”。
 *
 * @param result 通知测试控制器返回的结构化结果。
 * @param backgroundTest true表示10秒后台测试，false表示立即发布测试。
 *
 * @return 可显示在提醒卡片中的中文结果文本。
 */
internal fun notificationTestResultMessage(
    result: NotificationTestResult,
    backgroundTest: Boolean
): String {
    return when (result) {
        NotificationTestResult.SHOWN ->
            "测试通知已发送，正在播放该提醒类型选择的HarleyApp提示音"
        NotificationTestResult.SCHEDULED -> if (backgroundTest) {
            "已安排10秒后台测试，请立即返回桌面或切换到其他App等待通知"
        } else {
            "测试提醒已安排"
        }
        NotificationTestResult.NOTIFICATION_PERMISSION_MISSING ->
            "尚未允许通知，请先授予通知权限"
        NotificationTestResult.APP_NOTIFICATIONS_DISABLED ->
            "系统已关闭HarleyApp通知，请在系统通知设置中开启"
        NotificationTestResult.CHANNEL_DISABLED ->
            "强提醒渠道已关闭，请在系统通知设置中允许该渠道显示通知"
        NotificationTestResult.FAILED ->
            "系统未接受本次测试，请检查后台与闹钟权限后重试"
    }
}

/** App保持前台时检查本地日期的间隔，兼顾跨天刷新及时性与低功耗。 */
private const val COMPANION_DATE_REFRESH_INTERVAL_MILLIS = 60_000L

/** 标签标题或地址连续变化后合并保存的等待时间，减少主线程附近的小型磁盘写入。 */
private const val WEBSITE_BROWSER_SESSION_SAVE_DELAY_MILLIS = 250L

/** 达到WebView内存保护上限时显示的统一提示，不会自动关闭或替换现有标签。 */
private const val WEBSITE_BROWSER_TAB_LIMIT_MESSAGE = "最多保留8个标签页，请先手动关闭一个"

/** 覆盖安装后通知监听服务自动重绑的最大尝试次数。 */
private const val NOTIFICATION_REBIND_RETRY_COUNT = 3

/** 两次通知监听服务重绑检查之间的等待时间，避免短时间高频调用系统服务。 */
private const val NOTIFICATION_REBIND_RETRY_DELAY_MILLIS = 1_000L

/**
 * “我的→项目源码”专用的运行时网站入口。
 *
 * 该入口只用于把GitHub仓库交给App自带WebsiteScreen，不写入用户收藏、不参与首页轮播，
 * 因此用户的网站增删改查和默认网站设置不会被源码入口污染。
 */
private val PROJECT_SOURCE_WEBSITE = WebsiteShortcut(
    id = "runtime_project_source",
    title = "HarleyApp 项目源码",
    url = "https://github.com/halibaduoxiansheng/HarleyApp_Android",
    palette = WebsitePalette.VIOLET,
    showOnHome = false,
    sortOrder = Int.MAX_VALUE
)
