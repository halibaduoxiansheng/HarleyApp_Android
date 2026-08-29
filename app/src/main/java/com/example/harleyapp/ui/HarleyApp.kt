package com.example.harleyapp.ui

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.harleyapp.data.AutoReplySettingsRepository
import com.example.harleyapp.data.CompanionRepository
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.ScheduledMessageRepository
import com.example.harleyapp.data.ShortcutRepository
import com.example.harleyapp.data.WebsiteRepository
import com.example.harleyapp.data.WechatBillImporter
import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.model.AutoReplyStatus
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.ScheduledWechatMessage
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WechatCapture
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.scheduled.ScheduledMessageScheduler
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.InstalledAppsRepository
import com.example.harleyapp.system.LocalCleanupManager
import com.example.harleyapp.system.NotificationAccessController
import com.example.harleyapp.system.SystemStorageController
import com.example.harleyapp.ui.screens.FitnessScreen
import com.example.harleyapp.ui.screens.HomeScreen
import com.example.harleyapp.ui.screens.LedgerScreen
import com.example.harleyapp.ui.screens.MoreScreen
import com.example.harleyapp.ui.screens.WebsiteScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 应用底部导航对应的五个一级页面。
 *
 * @param title 导航栏中文标题。
 * @param symbol 导航栏使用的简洁字符图标，避免引入体积较大的额外图标依赖。
 */
private enum class AppSection(
    val title: String,
    val symbol: String
) {
    HOME("首页", "⌂"),
    LEDGER("记账", "¥"),
    FITNESS("运动", "动"),
    WEBSITE("网站", "◎"),
    MORE("更多", "＋")
}

/**
 * 组织整个Harley生活助手的页面状态、数据仓库和底部导航。
 *
 * 使用方法：
 * 在MainActivity的setContent中、HarleyAppTheme内部调用HarleyApp()。
 * 本函数负责把账目、快捷应用和设备服务传递给各个独立页面。
 *
 * @return 无返回值，直接输出完整应用界面。
 */
@Composable
fun HarleyApp() {
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
    val companionRepository = remember {
        CompanionRepository(applicationContext)
    }
    val autoReplySettingsRepository = remember {
        AutoReplySettingsRepository(applicationContext)
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
    val deviceMonitor = remember {
        DeviceMonitor(applicationContext)
    }
    val localCleanupManager = remember {
        LocalCleanupManager(applicationContext, ledgerRepository)
    }
    val notificationAccessController = remember {
        NotificationAccessController(applicationContext)
    }
    val scheduledMessageScheduler = remember {
        ScheduledMessageScheduler(applicationContext)
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
    var activeWebsiteId by rememberSaveable {
        mutableStateOf(websites.firstOrNull()?.id)
    }
    var companionProgress by remember {
        mutableStateOf(
            companionRepository.getProgress(LocalDate.now().toEpochDay())
        )
    }
    var autoReplySettings by remember {
        mutableStateOf(autoReplySettingsRepository.getSettings())
    }
    var autoReplyStatus by remember {
        mutableStateOf(autoReplySettingsRepository.getStatus())
    }
    var notificationAccessGranted by remember {
        mutableStateOf(notificationAccessController.isGranted())
    }
    var scheduledMessages by remember {
        mutableStateOf(scheduledMessageRepository.getMessages())
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
    val activeWebsite = websites.firstOrNull { website ->
        website.id == activeWebsiteId
    } ?: websites.firstOrNull()
    val notificationAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        notificationAccessGranted = notificationAccessController.isGranted()
        autoReplyStatus = autoReplySettingsRepository.getStatus()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted ||
            isNotificationPermissionGranted(applicationContext)
    }

    // 监听通知服务写入的兼容性和发送统计，让已打开的“更多”页面能够即时刷新。
    DisposableEffect(autoReplySettingsRepository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            autoReplySettings = autoReplySettingsRepository.getSettings()
            autoReplyStatus = autoReplySettingsRepository.getStatus()
        }
        autoReplySettingsRepository.registerChangeListener(listener)

        onDispose {
            autoReplySettingsRepository.unregisterChangeListener(listener)
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

    // 每次进入“更多”页面时重新读取系统授权，兼容用户从系统设置或安全中心修改权限。
    LaunchedEffect(currentSection) {
        if (currentSection == AppSection.MORE) {
            notificationAccessGranted = notificationAccessController.isGranted()
            autoReplyStatus = autoReplySettingsRepository.getStatus()
            scheduledMessages = scheduledMessageRepository.getMessages()
            notificationPermissionGranted =
                isNotificationPermissionGranted(applicationContext)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            NavigationBar {
                AppSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = currentSection == section,
                        onClick = {
                            currentSectionName = section.name
                        },
                        icon = {
                            Text(
                                text = section.symbol,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        label = {
                            Text(text = section.title)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (currentSection) {
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
                    currentSectionName = AppSection.MORE.name
                },
                websites = websites,
                companionProgress = companionProgress,
                onOpenWebsite = { website ->
                    activeWebsiteId = website.id
                    companionProgress = companionRepository.claimTask(
                        task = CompanionTask.WEBSITE_VISIT,
                        currentEpochDay = LocalDate.now().toEpochDay()
                    )
                    currentSectionName = AppSection.WEBSITE.name
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

            AppSection.LEDGER -> LedgerScreen(
                modifier = Modifier.padding(innerPadding),
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

            AppSection.FITNESS -> FitnessScreen(
                modifier = Modifier.padding(innerPadding)
            )

            AppSection.WEBSITE -> WebsiteScreen(
                modifier = Modifier.padding(innerPadding),
                website = activeWebsite,
                onManageWebsites = {
                    currentSectionName = AppSection.HOME.name
                }
            )

            AppSection.MORE -> MoreScreen(
                modifier = Modifier.padding(innerPadding),
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
                autoReplySettings = autoReplySettings,
                autoReplyStatus = autoReplyStatus,
                notificationAccessGranted = notificationAccessGranted,
                onSaveAutoReplySettings = { newSettings: AutoReplySettings ->
                    val success = autoReplySettingsRepository.saveSettings(newSettings)
                    if (success) {
                        autoReplySettings = autoReplySettingsRepository.getSettings()
                        autoReplyStatus = autoReplySettingsRepository.getStatus()
                    }
                    success
                },
                onOpenNotificationAccess = {
                    notificationAccessLauncher.launch(
                        notificationAccessController.createSettingsIntent()
                    )
                },
                scheduledMessages = scheduledMessages,
                notificationPermissionGranted = notificationPermissionGranted,
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationPermissionGranted = true
                    }
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
