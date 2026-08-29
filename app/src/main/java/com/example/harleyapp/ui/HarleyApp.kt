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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.harleyapp.data.CompanionRepository
import com.example.harleyapp.data.HotTopicRepository
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.data.ScheduledMessageRepository
import com.example.harleyapp.data.ShortcutRepository
import com.example.harleyapp.data.WebsiteRepository
import com.example.harleyapp.data.WechatBillImporter
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.ScheduledWechatMessage
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WechatCapture
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.notification.WechatReminderScheduler
import com.example.harleyapp.reminder.ReminderScheduler
import com.example.harleyapp.scheduled.ScheduledMessageScheduler
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.HotTopicLauncher
import com.example.harleyapp.system.InstalledAppsRepository
import com.example.harleyapp.system.LocalCleanupManager
import com.example.harleyapp.system.MobileDataUsageController
import com.example.harleyapp.system.NotificationAccessController
import com.example.harleyapp.system.SystemStorageController
import com.example.harleyapp.ui.screens.FitnessScreen
import com.example.harleyapp.ui.screens.FeatureCenterPage
import com.example.harleyapp.ui.screens.FeatureCenterScreen
import com.example.harleyapp.ui.screens.FeatureDetailScaffold
import com.example.harleyapp.ui.screens.HomeScreen
import com.example.harleyapp.ui.screens.HotTopicsScreen
import com.example.harleyapp.ui.screens.LedgerScreen
import com.example.harleyapp.ui.screens.MobileDataUsageScreen
import com.example.harleyapp.ui.screens.ProfileScreen
import com.example.harleyapp.ui.screens.WebsiteScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    HOT_TOPICS("每日热点", "热", showInBottomNavigation = false),
    MOBILE_DATA("手机流量", "流", showInBottomNavigation = false),
    FEATURES("功能", "功"),
    LEDGER("记账", "¥", showInBottomNavigation = false),
    FITNESS("运动", "动", showInBottomNavigation = false),
    WEBSITE("网站", "◎"),
    PROFILE("我的", "我")
}

/**
 * 组织整个Harley生活助手的页面状态、数据仓库和底部导航。
 *
 * 使用方法：
 * 在MainActivity的setContent中、HarleyAppTheme内部调用HarleyApp()。
 * 本函数负责把账目、快捷应用和设备服务传递给各个独立页面。
 *
 * @param isDarkTheme 当前是否使用黑夜模式，用于“更多”页显示正确切换方向。
 * @param onSetDarkTheme 保存并立即应用主题模式的回调，成功返回true。
 *
 * @return 无返回值，直接输出完整应用界面。
 */
@Composable
fun HarleyApp(
    isDarkTheme: Boolean,
    onSetDarkTheme: (Boolean) -> Boolean
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val ledgerRepository = remember {
        LedgerRepository(applicationContext)
    }
    val shortcutRepository = remember {
        ShortcutRepository(applicationContext)
    }
    val websiteRepository = remember {
        WebsiteRepository(applicationContext)
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
    val scheduledMessageRepository = remember {
        ScheduledMessageRepository(applicationContext)
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
    val scheduledMessageScheduler = remember {
        ScheduledMessageScheduler(applicationContext)
    }
    val wechatReminderScheduler = remember {
        WechatReminderScheduler(applicationContext)
    }
    val reminderScheduler = remember {
        ReminderScheduler(applicationContext)
    }
    val systemStorageController = remember {
        SystemStorageController(applicationContext)
    }
    val snackbarHostState = remember {
        SnackbarHostState()
    }
    val coroutineScope = rememberCoroutineScope()

    var currentSectionName by rememberSaveable {
        mutableStateOf(AppSection.HOME.name)
    }
    var featureCenterPageName by rememberSaveable {
        mutableStateOf(FeatureCenterPage.OVERVIEW.name)
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
    var websites by remember {
        mutableStateOf(websiteRepository.getWebsites())
    }
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
    var scheduledMessages by remember {
        mutableStateOf(scheduledMessageRepository.getMessages())
    }
    var reminders by remember {
        mutableStateOf(reminderRepository.getReminders())
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(isNotificationPermissionGranted(applicationContext))
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

    // 覆盖安装或系统回收服务后，授权记录可能仍在但监听服务尚未重连，启动时主动请求一次恢复。
    LaunchedEffect(notificationAccessController) {
        if (notificationAccessController.isGranted()) {
            notificationAccessController.requestRebindIfGranted()
        }
    }

    // 监听定时提醒接收器和分享Activity写入的状态，让计划列表同步显示“已提醒”或“已打开微信”。
    DisposableEffect(scheduledMessageRepository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scheduledMessages = scheduledMessageRepository.getMessages()
        }
        scheduledMessageRepository.registerChangeListener(listener)

        onDispose {
            scheduledMessageRepository.unregisterChangeListener(listener)
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

    // 每次进入功能中心时重新读取系统授权，兼容用户从系统设置或安全中心修改权限。
    LaunchedEffect(currentSection) {
        if (currentSection == AppSection.FEATURES) {
            notificationAccessGranted = notificationAccessController.isGranted()
            notificationListenerConnected = notificationAccessController.isConnected()
            wechatReminderStatus = wechatReminderRepository.getStatus()
            reminders = reminderRepository.getReminders()
            scheduledMessages = scheduledMessageRepository.getMessages()
            notificationPermissionGranted =
                isNotificationPermissionGranted(applicationContext)
        }
    }

    // 独立详情页不占用底部导航；账本和运动返回功能中心，其余首页卡片详情返回首页。
    BackHandler(enabled = !currentSection.showInBottomNavigation) {
        currentSectionName = when (currentSection) {
            AppSection.LEDGER,
            AppSection.FITNESS -> AppSection.FEATURES.name
            AppSection.HOT_TOPICS,
            AppSection.MOBILE_DATA -> detailReturnSectionName
            else -> AppSection.HOME.name
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (currentSection.showInBottomNavigation) {
                NavigationBar {
                    AppSection.entries
                        .filter { section -> section.showInBottomNavigation }
                        .forEach { section ->
                            val selected = currentSection == section
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.2f else 1f,
                                animationSpec = spring(
                                    dampingRatio = 0.5f,
                                    stiffness = 460f
                                ),
                                label = "nav_scale_${section.name}"
                            )
                            val iconRotation by animateFloatAsState(
                                targetValue = if (selected) -5f else 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.45f,
                                    stiffness = 400f
                                ),
                                label = "nav_rotation_${section.name}"
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
                                    Text(
                                        text = section.symbol,
                                        style = MaterialTheme.typography.titleLarge,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                            rotationZ = iconRotation
                                        }
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
                websites = websites,
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
                onOpenHotTopics = {
                    detailReturnSectionName = AppSection.HOME.name
                    currentSectionName = AppSection.HOT_TOPICS.name
                },
                onOpenMobileData = {
                    detailReturnSectionName = AppSection.HOME.name
                    currentSectionName = AppSection.MOBILE_DATA.name
                },
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
                        val updatedWebsites = websites.toMutableList().apply {
                            if (existingIndex >= 0) {
                                set(existingIndex, normalizedWebsite)
                            } else {
                                add(normalizedWebsite)
                            }
                        }
                        val saved = websiteRepository.saveWebsites(updatedWebsites)
                        if (saved) {
                            websites = updatedWebsites
                            if (activeWebsiteId == null) {
                                activeWebsiteId = normalizedWebsite.id
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
                    val deleted = updatedWebsites.size != websites.size &&
                        websiteRepository.saveWebsites(updatedWebsites)
                    if (deleted) {
                        websites = updatedWebsites
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
                onPageChanged = { page ->
                    featureCenterPageName = page.name
                },
                onOpenLedger = {
                    currentSectionName = AppSection.LEDGER.name
                },
                onOpenFitness = {
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
                scheduledMessages = scheduledMessages,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationPermissionGranted = true
                    }
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
                onSaveScheduledMessage = { message: ScheduledWechatMessage ->
                    val previousMessage = message.id
                        .takeIf { it > 0L }
                        ?.let(scheduledMessageRepository::getMessage)
                    if (message.id > 0L) {
                        scheduledMessageScheduler.cancel(message.id)
                    }
                    val savedMessage = scheduledMessageRepository.upsertMessage(message)
                    val success = when {
                        savedMessage == null -> {
                            previousMessage?.let(scheduledMessageScheduler::schedule)
                            false
                        }
                        scheduledMessageScheduler.schedule(savedMessage) -> true
                        previousMessage != null -> {
                            scheduledMessageRepository.upsertMessage(previousMessage)
                            scheduledMessageScheduler.schedule(previousMessage)
                            false
                        }
                        else -> {
                            scheduledMessageRepository.deleteMessage(savedMessage.id)
                            false
                        }
                    }
                    scheduledMessages = scheduledMessageRepository.getMessages()
                    success
                },
                onDeleteScheduledMessage = { messageId ->
                    scheduledMessageScheduler.cancel(messageId)
                    val success = scheduledMessageRepository.deleteMessage(messageId)
                    if (success) {
                        scheduledMessages = scheduledMessageRepository.getMessages()
                    }
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
                    currentSectionName = AppSection.FEATURES.name
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
                    currentSectionName = AppSection.FEATURES.name
                }
            ) { contentModifier ->
                FitnessScreen(modifier = contentModifier)
            }

            AppSection.WEBSITE -> WebsiteScreen(
                modifier = Modifier.padding(innerPadding),
                website = activeWebsite,
                onManageWebsites = {
                    currentSectionName = AppSection.HOME.name
                }
            )

            AppSection.PROFILE -> ProfileScreen(
                modifier = Modifier.padding(innerPadding),
                isDarkTheme = isDarkTheme,
                onSetDarkTheme = onSetDarkTheme,
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
 * 判断本应用是否具备展示图文到点提醒的通知权限。
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
