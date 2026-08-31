package com.example.harleyapp.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.harleyapp.backup.AppBackupManager
import com.example.harleyapp.data.CompanionRepository
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
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.data.WechatBillImporter
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.LocalSearchType
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WechatCapture
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.model.homeCarouselWebsites
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.notification.WechatReminderScheduler
import com.example.harleyapp.reminder.ReminderScheduler
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.ExactAlarmAccessController
import com.example.harleyapp.system.HotTopicLauncher
import com.example.harleyapp.system.InstalledAppsRepository
import com.example.harleyapp.system.LocalCleanupManager
import com.example.harleyapp.system.MobileDataUsageController
import com.example.harleyapp.system.NotificationAccessController
import com.example.harleyapp.system.OfflineEnglishTts
import com.example.harleyapp.system.OfflineEnglishTtsState
import com.example.harleyapp.system.SystemStorageController
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
import com.example.harleyapp.ui.screens.TodayOverviewScreen
import com.example.harleyapp.ui.screens.WebsiteBookmarkManagerScreen
import com.example.harleyapp.ui.screens.WebsiteScreen
import com.example.harleyapp.weather.DeviceLocationProvider
import com.example.harleyapp.weather.WeatherRepository
import com.example.harleyapp.widget.TodayWeatherWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

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
    HOME("首页", "⌂"),
    TODAY("今日总览", "今", showInBottomNavigation = false),
    HOT_TOPICS("每日热点", "热", showInBottomNavigation = false),
    MOBILE_DATA("手机流量", "流", showInBottomNavigation = false),
    FEATURES("功能", "功"),
    SEARCH("全局搜索", "搜", showInBottomNavigation = false),
    BACKUP("本地备份", "备", showInBottomNavigation = false),
    LEDGER("记账", "¥", showInBottomNavigation = false),
    FITNESS("运动", "动", showInBottomNavigation = false),
    BOOKMARKS("网站收藏", "夹", showInBottomNavigation = false),
    WEBSITE("网站", "◎"),
    PROFILE("我的", "我")
}

/**
 * 显示会随角色主题变化的底部导航图标。
 *
 * 使用方法：
 * 底部四个一级页面统一调用本组件。纯色主题继续显示简洁字符；人物主题会把同一张角色图按页面
 * 使用不同偏移和放大比例裁成头像、发饰或服装局部，并叠加小型页面符号，避免四个按钮简单重复整张人物。
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
    if (artResourceId == 0) {
        Text(
            text = section.symbol,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        )
        return
    }

    val horizontalShift = when (section) {
        AppSection.HOME -> -7.dp
        AppSection.FEATURES -> 5.dp
        AppSection.WEBSITE -> -2.dp
        AppSection.PROFILE -> 8.dp
        else -> 0.dp
    }
    val verticalShift = when (section) {
        AppSection.HOME -> 7.dp
        AppSection.FEATURES -> -5.dp
        AppSection.WEBSITE -> 0.dp
        AppSection.PROFILE -> -9.dp
        else -> 0.dp
    }
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Image(
                painter = painterResource(artResourceId),
                contentDescription = "${visualTheme.displayName}主题·${section.title}局部插画",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (selected) 1.72f else 1.58f
                        scaleY = if (selected) 1.72f else 1.58f
                        translationX = horizontalShift.toPx()
                        translationY = verticalShift.toPx()
                        alpha = if (selected) 1f else 0.72f
                    }
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(19.dp),
            shape = CircleShape,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = section.symbol,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
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
 * @param openTodayRequest 是否收到桌面小组件发出的“打开今日总览”请求。
 * @param onOpenTodayRequestConsumed 请求完成导航后的消费回调，防止重组时重复打开。
 * @param onSetDarkTheme 保存并立即应用主题模式的回调，成功返回true。
 * @param onSetVisualTheme 保存并立即应用角色风格主题的回调，成功返回true。
 *
 * @return 无返回值，直接输出完整应用界面。
 */
@Composable
fun HarleyApp(
    isDarkTheme: Boolean,
    visualTheme: AppVisualTheme,
    openTodayRequest: Boolean,
    onOpenTodayRequestConsumed: () -> Unit,
    onSetDarkTheme: (Boolean) -> Boolean,
    onSetVisualTheme: (AppVisualTheme) -> Boolean
) {
    val context = LocalContext.current
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
    val websiteBackgroundStore = remember {
        WebsiteCardBackgroundStore(applicationContext)
    }
    val hotTopicRepository = remember {
        HotTopicRepository()
    }
    val companionRepository = remember {
        CompanionRepository(applicationContext)
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
    val notificationAccessController = remember {
        NotificationAccessController(applicationContext)
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
    val initialEnglishWords = remember {
        englishWordRepository.getWords()
    }
    var englishWords by remember {
        mutableStateOf(initialEnglishWords)
    }
    var homeEnglishWordId by rememberSaveable {
        mutableStateOf(
            englishWordRepository.chooseNextWord(
                words = initialEnglishWords,
                previousWordId = null
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
    var activeWebsiteId by rememberSaveable {
        mutableStateOf(defaultWebsiteId ?: websites.firstOrNull()?.id)
    }
    var companionProgress by remember {
        mutableStateOf(
            companionRepository.getProgress(LocalDate.now().toEpochDay())
        )
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
    val currentSection = AppSection.entries.firstOrNull {
        it.name == currentSectionName
    } ?: AppSection.HOME
    val featureCenterPage = FeatureCenterPage.entries.firstOrNull { page ->
        page.name == featureCenterPageName
    } ?: FeatureCenterPage.OVERVIEW
    val activeWebsite = websites.firstOrNull { website ->
        website.id == activeWebsiteId
    } ?: websites.firstOrNull()
    val defaultWebsite = websites.firstOrNull { website ->
        website.id == defaultWebsiteId
    } ?: websites.firstOrNull()

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
    val homeEnglishWord = englishWords.firstOrNull { word ->
        word.id == homeEnglishWordId && word.learnedCount < ENGLISH_WORD_MASTERY_COUNT
    } ?: englishWordRepository.chooseNextWord(
        words = englishWords,
        previousWordId = homeEnglishWordId
    )
    val markEnglishWordLearned: (String) -> Boolean = { wordId ->
        val saved = englishWordRepository.markLearned(wordId)
        if (saved) {
            val refreshedWords = englishWordRepository.getWords()
            englishWords = refreshedWords
            homeEnglishWordId = englishWordRepository.chooseNextWord(
                words = refreshedWords,
                previousWordId = wordId
            )?.id
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
                previousWordId = homeEnglishWordId
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

    // App保持前台跨过零点时也会领取新一天的首次打开经验；仓库保证同一天只发放一次。
    LaunchedEffect(companionRepository) {
        while (isActive) {
            companionProgress = companionRepository.claimTask(
                task = CompanionTask.DAILY_OPEN,
                currentEpochDay = LocalDate.now().toEpochDay()
            )
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
        }
    }

    // 独立详情页不占用底部导航，并按进入详情前记录的来源页返回。
    BackHandler(enabled = !currentSection.showInBottomNavigation) {
        currentSectionName = when (currentSection) {
            AppSection.LEDGER,
            AppSection.FITNESS,
            AppSection.TODAY,
            AppSection.SEARCH,
            AppSection.BACKUP,
            AppSection.HOT_TOPICS,
            AppSection.MOBILE_DATA,
            AppSection.BOOKMARKS -> detailReturnSectionName
            else -> AppSection.HOME.name
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (
                currentSection.showInBottomNavigation &&
                !isWebsiteFullscreen &&
                !isEbookImmersive
            ) {
                NavigationBar {
                    AppSection.entries
                        .filter { section -> section.showInBottomNavigation }
                        .forEach { section ->
                            val selected = currentSection == section
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.06f else 1f,
                                animationSpec = spring(
                                    dampingRatio = 0.72f,
                                    stiffness = 500f
                                ),
                                label = "nav_scale_${section.name}"
                            )
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // 底部“网站”始终从用户设置的默认网站进入，不沿用上一次临时浏览项。
                                    if (section == AppSection.WEBSITE) {
                                        activeWebsiteId = defaultWebsite?.id
                                    }
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
                                    Text(text = section.title)
                                }
                            )
                        }
                }
            }
        }
    ) { innerPadding ->
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
                    activeWebsiteId = website.id
                    companionProgress = companionRepository.claimTask(
                        task = CompanionTask.WEBSITE_VISIT,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
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
                    }
                },
                englishWord = homeEnglishWord,
                englishRemainingCount = englishWords.count { word ->
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
                            if (activeWebsiteId == null) {
                                activeWebsiteId = websiteToPersist.id
                            }
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
                        if (activeWebsiteId == websiteId) {
                            activeWebsiteId = updatedWebsites.firstOrNull()?.id
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
                onQuickSearch = { query ->
                    pendingGlobalSearchQuery = query
                    detailReturnSectionName = AppSection.HOME.name
                    currentSectionName = AppSection.SEARCH.name
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
                            activeWebsiteId = result.targetValue
                            currentSectionName = AppSection.WEBSITE.name
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
                    englishWords = restoredEnglishWords
                    homeEnglishWordId = englishWordRepository.chooseNextWord(
                        words = restoredEnglishWords,
                        previousWordId = null
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
                    activeWebsiteId = restoredDefaultWebsiteId ?: restoredWebsites.firstOrNull()?.id

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
                    if (!hotTopicLauncher.open(topic)) {
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

            AppSection.FEATURES -> FeatureCenterScreen(
                modifier = Modifier.padding(innerPadding),
                page = featureCenterPage,
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
                englishTtsState = englishTtsState,
                onSpeakEnglish = offlineEnglishTts::speak,
                onMarkEnglishWordLearned = markEnglishWordLearned,
                onResetEnglishWord = resetEnglishWord,
                initialEnglishWordId = searchEnglishWordTargetId.ifBlank { null },
                initialNotebookArticleId = searchNotebookArticleTargetId.ifBlank { null },
                initialEbookId = searchEbookTargetId.ifBlank { null },
                ebookRepository = ebookRepository,
                onEbookImmersiveChanged = { immersive ->
                    isEbookImmersive = immersive
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
                    activeWebsiteId = website.id
                    companionProgress = companionRepository.claimTask(
                        task = CompanionTask.WEBSITE_VISIT,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
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
                        if (updatedLibrary.websites.none { website -> website.id == activeWebsiteId }) {
                            activeWebsiteId = resolvedDefaultWebsiteId
                                ?: updatedLibrary.websites.firstOrNull()?.id
                        }
                    }
                    saved
                }
            )

            AppSection.WEBSITE -> WebsiteScreen(
                modifier = if (isWebsiteFullscreen) {
                    Modifier
                } else {
                    Modifier.padding(innerPadding)
                },
                website = activeWebsite,
                onFullscreenChanged = { isFullscreen ->
                    isWebsiteFullscreen = isFullscreen
                },
                onEbookDownloadRequested = { request ->
                    coroutineScope.launch {
                        val progressMessage = launch {
                            snackbarHostState.showSnackbar("正在下载并导入电子书…")
                        }
                        val result = ebookRepository.importFromWebDownload(request)
                        progressMessage.cancel()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val snackbarResult = snackbarHostState.showSnackbar(
                            message = result.message,
                            actionLabel = if (result.success) "打开书库" else null,
                            withDismissAction = true
                        )
                        if (
                            result.success &&
                            snackbarResult == androidx.compose.material3.SnackbarResult.ActionPerformed
                        ) {
                            featureCenterPageName = FeatureCenterPage.EBOOKS.name
                            currentSectionName = AppSection.FEATURES.name
                        }
                    }
                },
                onManageWebsites = {
                    detailReturnSectionName = AppSection.WEBSITE.name
                    currentSectionName = AppSection.BOOKMARKS.name
                }
            )

            AppSection.PROFILE -> ProfileScreen(
                modifier = Modifier.padding(innerPadding),
                isDarkTheme = isDarkTheme,
                visualTheme = visualTheme,
                onSetDarkTheme = onSetDarkTheme,
                onSetVisualTheme = onSetVisualTheme,
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
                }
            )
            }
        }
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

/** App保持前台时检查本地日期的间隔，兼顾跨天刷新及时性与低功耗。 */
private const val COMPANION_DATE_REFRESH_INTERVAL_MILLIS = 60_000L

/** 覆盖安装后通知监听服务自动重绑的最大尝试次数。 */
private const val NOTIFICATION_REBIND_RETRY_COUNT = 3

/** 两次通知监听服务重绑检查之间的等待时间，避免短时间高频调用系统服务。 */
private const val NOTIFICATION_REBIND_RETRY_DELAY_MILLIS = 1_000L
