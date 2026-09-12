package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.data.ChineseGrowthRepository
import com.example.harleyapp.data.EbookRepository
import com.example.harleyapp.model.CookCatalog
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.model.moveFeatureCenterItem
import com.example.harleyapp.system.OfflineEnglishTtsState
import com.example.harleyapp.ui.components.HarleyPageBackground
import com.example.harleyapp.ui.components.HarleyPageHeader
import com.example.harleyapp.ui.components.HarleySymbolBadge
import com.example.harleyapp.ui.components.harleyCardBorder
import com.example.harleyapp.ui.theme.LocalAppVisualTheme

/**
 * 功能中心内部可以切换的概览页和工具详情页。
 *
 * 使用方法：
 * HarleyApp通过rememberSaveable保存枚举名称。用户点击功能卡片后传入对应详情值，点击返回或
 * 系统返回键时恢复OVERVIEW。记账和运动属于独立全屏详情，因此由HarleyApp单独导航。
 */
enum class FeatureCenterPage {
    OVERVIEW,
    WECHAT_REMINDER,
    GENERAL_REMINDER,
    LOCAL_CLEANUP,
    ENGLISH_WORDS,
    NOTEBOOK,
    EBOOKS,
    CHINESE_GROWTH,
    QR_SCANNER,
    FLASHLIGHT,
    MAO_QUOTES,
    DUAL_CAMERA,
    LIVE_TRANSLATION,
    COOK
}

/** 功能入口按下时的缩放比例，既要让反馈明显，也要避免文字产生过大的视觉抖动。 */
private const val FEATURE_ENTRY_PRESSED_SCALE = 0.985f

/** 功能入口缩放动画的阻尼比，用于形成短促且不反复回弹的按压手感。 */
private const val FEATURE_ENTRY_PRESS_DAMPING_RATIO = 0.78f

/** 功能入口缩放动画的刚度，保证抬手后能快速恢复，不拖慢页面跳转。 */
private const val FEATURE_ENTRY_PRESS_STIFFNESS = 650f

/** 功能入口按压色彩的过渡时长，单位为毫秒。 */
private const val FEATURE_ENTRY_COLOR_ANIMATION_MILLIS = 90

/**
 * 集中展示记账、运动、提醒与清理功能，并承载轻量工具的详情页面。
 *
 * 使用方法：
 * 由HarleyApp在底部“功能”被选中时调用。记账和运动通过独立回调打开原有完整页面；其他工具
 * 在功能中心内部切换详情，所有仓库、权限和保存回调仍由HarleyApp统一提供。
 *
 * @param page 当前功能中心页面。
 * @param onPageChanged 切换功能中心概览或内部详情的回调。
 * @param overviewGridState 由App根层长期保存的功能入口网格状态，确保进入详情再返回时恢复原位置。
 * @param overviewRestoreIndex 最近一次打开功能前记录的首个可见卡片索引。
 * @param overviewRestoreOffset 最近一次打开功能前记录的首个可见卡片像素偏移。
 * @param onOverviewPositionCaptured 打开功能前保存首项索引与像素偏移的回调。
 * @param featureOrder 功能卡片当前从左到右、从上到下的持久化顺序。
 * @param onFeatureOrderChanged 长按拖动结束后保存完整新顺序的回调，成功返回true。
 * @param onOpenLedger 打开原有完整记账页面的回调。
 * @param onOpenFitness 打开原有完整运动页面的回调。
 * @param onOpenHotTopics 打开每日热点完整页面的回调。
 * @param onOpenMobileData 打开手机流量统计页面的回调。
 * @param onOpenAppUsage 打开应用使用统计页面的回调。
 * @param onOpenBreathHold 打开深海憋气计时页面的回调。
 * @param onOpenToday 打开今日总览页面的回调。
 * @param onOpenSearch 打开全局本地搜索页面的回调。
 * @param onOpenBackup 打开跨手机本地备份页面的回调。
 * @param englishWords 已合并本机学习进度的完整离线单词列表。
 * @param englishSelection 当前小学英语年级、册次和词库范围。
 * @param onEnglishSelectionChanged 保存英语学习选择并刷新首页推荐的回调。
 * @param englishTtsState Android离线英语TTS当前状态。
 * @param onSpeakEnglish 朗读英文单词或例句的回调。
 * @param onMarkEnglishWordLearned 把指定单词学习次数增加一次的回调。
 * @param onResetEnglishWord 把指定单词恢复到未学会状态的回调。
 * @param initialEnglishWordId 全局搜索要求直接打开的英语单词id。
 * @param initialNotebookArticleId 全局搜索要求直接打开的记事本文章id。
 * @param initialEbookId 全局搜索要求直接打开的电子书id。
 * @param initialCookRecipeId 全局搜索要求直接打开的菜谱id。
 * @param cookCatalog 已在后台加载的完整离线菜谱目录；加载中或失败时为null。
 * @param cookLoadError 菜谱目录加载失败的用户提示；加载中时为空字符串。
 * @param onReloadCookCatalog 用户在失败提示中点击重试后的回调。
 * @param ebookRepository 电子书原文件、离线索引和阅读进度仓库。
 * @param chineseGrowthRepository 语文年级选择、在线阅读和缓存仓库。
 * @param onEbookImmersiveChanged 电子书沉浸阅读状态变化回调，用于隐藏或恢复App底部导航栏。
 * @param onNotebookArticlePublished 新草稿首次正式发布成功后的伙伴成长回调。
 * @param onEbookReadingDuration 电子书阅读器前台有效阅读毫秒数回调。
 * @param onInitialSearchTargetConsumed 初始搜索目标完成跳转后的清理回调。
 * @param wechatReminderSettings 微信未查看消息提醒设置。
 * @param wechatReminderStatus 微信提醒监听状态。
 * @param notificationAccessGranted 是否已授予通知使用权。
 * @param notificationListenerConnected 通知监听服务是否已连接。
 * @param notificationBackgroundUnrestricted 是否已允许App忽略系统电池优化。
 * @param onSaveWechatReminderSettings 保存微信提醒设置的回调。
 * @param onOpenNotificationAccess 打开通知使用权设置的回调。
 * @param onChooseWechatReminderSound 打开微信提醒App内独立提示音选择器的回调。
 * @param onChooseScheduledReminderSound 打开普通提醒App内独立提示音选择器的回调。
 * @param onRequestNotificationBackgroundAccess 请求解除电池后台限制的回调。
 * @param onTestScheduledNotificationNow 立即测试普通提醒已选择提示音的回调。
 * @param onTestWechatNotificationNow 立即测试微信提醒已选择提示音的回调。
 * @param onScheduleBackgroundNotificationTest 安排10秒后台测试并返回中文结果的回调。
 * @param reminders 普通通知提醒计划。
 * @param notificationPermissionGranted 是否具备发送通知权限。
 * @param exactAlarmPermissionGranted 是否具备准时触发提醒所需的精确Alarm权限。
 * @param onRequestNotificationPermission 请求发送通知权限的回调。
 * @param onRequestExactAlarmPermission 打开系统“闹钟和提醒”授权页的回调。
 * @param onSaveReminder 新增或更新普通提醒的回调。
 * @param onDeleteReminder 删除普通提醒的回调。
 * @param cleanupStatus 本App缓存清理状态。
 * @param onSetAutomaticCleanup 修改自动清理开关的回调。
 * @param onMeasureAppCache 统计可清理缓存的回调。
 * @param onCleanAppNow 立即执行本App缓存清理的回调。
 * @param onOpenSystemStorage 打开系统存储管理页面的回调。
 * @param modifier 外部传入的安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出功能中心概览或选中的详情页面。
 */
@Composable
fun FeatureCenterScreen(
    page: FeatureCenterPage,
    onPageChanged: (FeatureCenterPage) -> Unit,
    overviewGridState: LazyGridState,
    overviewRestoreIndex: Int,
    overviewRestoreOffset: Int,
    onOverviewPositionCaptured: (index: Int, offset: Int) -> Unit,
    featureOrder: List<HomeFeatureId>,
    onFeatureOrderChanged: (List<HomeFeatureId>) -> Boolean,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenAppUsage: () -> Unit,
    onOpenBreathHold: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    englishWords: List<EnglishWord>,
    englishSelection: PrimaryEnglishSelection,
    onEnglishSelectionChanged: (PrimaryEnglishSelection) -> Boolean,
    englishTtsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkEnglishWordLearned: (String) -> Boolean,
    onResetEnglishWord: (String) -> Boolean,
    initialEnglishWordId: String?,
    initialNotebookArticleId: String?,
    initialEbookId: String?,
    initialCookRecipeId: String?,
    cookCatalog: CookCatalog?,
    cookLoadError: String,
    onReloadCookCatalog: () -> Unit,
    ebookRepository: EbookRepository,
    chineseGrowthRepository: ChineseGrowthRepository,
    onEbookImmersiveChanged: (Boolean) -> Unit,
    onNotebookArticlePublished: () -> Unit,
    onEbookReadingDuration: (Long) -> Unit,
    onInitialSearchTargetConsumed: () -> Unit,
    wechatReminderSettings: WechatReminderSettings,
    wechatReminderStatus: WechatReminderStatus,
    notificationAccessGranted: Boolean,
    notificationListenerConnected: Boolean,
    notificationBackgroundUnrestricted: Boolean,
    onSaveWechatReminderSettings: (WechatReminderSettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    onChooseWechatReminderSound: () -> Unit,
    onChooseScheduledReminderSound: () -> Unit,
    onRequestNotificationBackgroundAccess: () -> Unit,
    onTestScheduledNotificationNow: () -> String,
    onTestWechatNotificationNow: () -> String,
    onScheduleBackgroundNotificationTest: () -> String,
    reminders: List<ScheduledReminder>,
    notificationPermissionGranted: Boolean,
    exactAlarmPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onSaveReminder: (ScheduledReminder) -> Boolean,
    onDeleteReminder: (Long) -> Boolean,
    cleanupStatus: LocalCleanupStatus,
    onSetAutomaticCleanup: (Boolean) -> Boolean,
    onMeasureAppCache: suspend () -> Long,
    onCleanAppNow: suspend () -> LocalCleanupResult,
    onOpenSystemStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 部分工具（例如电子书书架）只在更深层内容中注册返回处理；这里统一兜底详情首页的系统
    // 返回事件，避免事件落到App外层后直接回首页。工具自身更晚注册的返回处理仍会优先执行。
    BackHandler(enabled = page != FeatureCenterPage.OVERVIEW) {
        onPageChanged(FeatureCenterPage.OVERVIEW)
    }

    when (page) {
        FeatureCenterPage.OVERVIEW -> FeatureCenterOverview(
            modifier = modifier,
            gridState = overviewGridState,
            restoreIndex = overviewRestoreIndex,
            restoreOffset = overviewRestoreOffset,
            onPositionCaptured = onOverviewPositionCaptured,
            featureOrder = featureOrder,
            onFeatureOrderChanged = onFeatureOrderChanged,
            onOpenLedger = onOpenLedger,
            onOpenFitness = onOpenFitness,
            onOpenHotTopics = onOpenHotTopics,
            onOpenMobileData = onOpenMobileData,
            onOpenAppUsage = onOpenAppUsage,
            onOpenBreathHold = onOpenBreathHold,
            onOpenToday = onOpenToday,
            onOpenSearch = onOpenSearch,
            onOpenBackup = onOpenBackup,
            onOpenNotebook = {
                onPageChanged(FeatureCenterPage.NOTEBOOK)
            },
            onOpenEbooks = {
                onPageChanged(FeatureCenterPage.EBOOKS)
            },
            onOpenEnglishWords = {
                onPageChanged(FeatureCenterPage.ENGLISH_WORDS)
            },
            onOpenChineseGrowth = {
                onPageChanged(FeatureCenterPage.CHINESE_GROWTH)
            },
            onOpenQrScanner = {
                onPageChanged(FeatureCenterPage.QR_SCANNER)
            },
            onOpenFlashlight = {
                onPageChanged(FeatureCenterPage.FLASHLIGHT)
            },
            onOpenMaoQuotes = {
                onPageChanged(FeatureCenterPage.MAO_QUOTES)
            },
            onOpenDualCamera = {
                onPageChanged(FeatureCenterPage.DUAL_CAMERA)
            },
            onOpenLiveTranslation = {
                onPageChanged(FeatureCenterPage.LIVE_TRANSLATION)
            },
            onOpenCook = {
                onPageChanged(FeatureCenterPage.COOK)
            },
            onOpenWechatReminder = {
                onPageChanged(FeatureCenterPage.WECHAT_REMINDER)
            },
            onOpenGeneralReminder = {
                onPageChanged(FeatureCenterPage.GENERAL_REMINDER)
            },
            onOpenLocalCleanup = {
                onPageChanged(FeatureCenterPage.LOCAL_CLEANUP)
            }
        )

        FeatureCenterPage.WECHAT_REMINDER -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "微信消息提醒",
            subtitle = "按时间段提醒仍未查看的微信消息",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            WechatReminderSettingsCard(
                settings = wechatReminderSettings,
                status = wechatReminderStatus,
                notificationAccessGranted = notificationAccessGranted,
                notificationListenerConnected = notificationListenerConnected,
                notificationPermissionGranted = notificationPermissionGranted,
                exactAlarmPermissionGranted = exactAlarmPermissionGranted,
                notificationBackgroundUnrestricted = notificationBackgroundUnrestricted,
                onSaveSettings = onSaveWechatReminderSettings,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestExactAlarmPermission = onRequestExactAlarmPermission,
                onChooseReminderSound = onChooseWechatReminderSound,
                onRequestNotificationBackgroundAccess =
                    onRequestNotificationBackgroundAccess,
                onTestNotificationNow = onTestWechatNotificationNow,
                onScheduleBackgroundNotificationTest = onScheduleBackgroundNotificationTest
            )
        }

        FeatureCenterPage.GENERAL_REMINDER -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "通知提醒",
            subtitle = "管理一次性或多时间单位重复的本机提醒",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            ReminderCard(
                reminders = reminders,
                notificationPermissionGranted = notificationPermissionGranted,
                exactAlarmPermissionGranted = exactAlarmPermissionGranted,
                notificationBackgroundUnrestricted = notificationBackgroundUnrestricted,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onRequestExactAlarmPermission = onRequestExactAlarmPermission,
                onChooseReminderSound = onChooseScheduledReminderSound,
                onRequestNotificationBackgroundAccess =
                    onRequestNotificationBackgroundAccess,
                onTestNotificationNow = onTestScheduledNotificationNow,
                onScheduleBackgroundNotificationTest = onScheduleBackgroundNotificationTest,
                onSaveReminder = onSaveReminder,
                onDeleteReminder = onDeleteReminder
            )
        }

        FeatureCenterPage.LOCAL_CLEANUP -> FeatureCardDetailScreen(
            modifier = modifier,
            title = "手机清理",
            subtitle = "清理本App缓存或打开系统存储管理",
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        ) {
            LocalCleanupCard(
                status = cleanupStatus,
                onSetAutomaticEnabled = onSetAutomaticCleanup,
                onMeasureCache = onMeasureAppCache,
                onCleanNow = onCleanAppNow,
                onOpenSystemStorage = onOpenSystemStorage
            )
        }

        FeatureCenterPage.ENGLISH_WORDS -> EnglishWordLearningScreen(
            modifier = modifier,
            words = englishWords,
            selection = englishSelection,
            onSelectionChanged = onEnglishSelectionChanged,
            ttsState = englishTtsState,
            onSpeakEnglish = onSpeakEnglish,
            onMarkLearned = onMarkEnglishWordLearned,
            onResetWord = onResetEnglishWord,
            initialWordId = initialEnglishWordId,
            onInitialWordConsumed = onInitialSearchTargetConsumed,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.NOTEBOOK -> NotebookScreen(
            modifier = modifier,
            initialArticleId = initialNotebookArticleId,
            onInitialArticleConsumed = onInitialSearchTargetConsumed,
            onArticlePublished = onNotebookArticlePublished,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.EBOOKS -> EbookScreen(
            modifier = modifier,
            repository = ebookRepository,
            initialBookId = initialEbookId,
            onInitialBookConsumed = onInitialSearchTargetConsumed,
            onImmersiveChanged = onEbookImmersiveChanged,
            onReadingDuration = onEbookReadingDuration,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.CHINESE_GROWTH -> ChineseGrowthScreen(
            modifier = modifier,
            repository = chineseGrowthRepository,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.QR_SCANNER -> QrScannerScreen(
            modifier = modifier,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.FLASHLIGHT -> FlashlightScreen(
            modifier = modifier,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.MAO_QUOTES -> MaoQuotesScreen(
            modifier = modifier,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.DUAL_CAMERA -> DualCameraScreen(
            modifier = modifier,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.LIVE_TRANSLATION -> LiveTranslationScreen(
            modifier = modifier,
            onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
        )

        FeatureCenterPage.COOK -> {
            val loadedCatalog = cookCatalog
            if (loadedCatalog != null) {
                CookScreen(
                    modifier = modifier,
                    catalog = loadedCatalog,
                    initialRecipeId = initialCookRecipeId,
                    onInitialRecipeConsumed = onInitialSearchTargetConsumed,
                    onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
                )
            } else {
                FeatureCardDetailScreen(
                    modifier = modifier,
                    title = "Cook 菜谱",
                    subtitle = "正在准备离线菜谱库",
                    onBack = { onPageChanged(FeatureCenterPage.OVERVIEW) }
                ) {
                    if (cookLoadError.isBlank()) {
                        CircularProgressIndicator()
                        Text("正在读取离线菜谱和本地图片…")
                    } else {
                        Text(
                            text = cookLoadError,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = onReloadCookCatalog) {
                            Text("重新读取")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 显示功能中心全部卡片化入口。
 *
 * @param modifier 外部布局修饰器。
 * @param gridState 由App根层持有的网格滚动状态，概览暂时离开组合后仍保留首项和像素偏移。
 * @param restoreIndex 最近一次打开功能前记录的首个可见卡片索引。
 * @param restoreOffset 最近一次打开功能前记录的首个可见卡片像素偏移。
 * @param onPositionCaptured 打开任一功能前保存当前首项索引与像素偏移的回调。
 * @param featureOrder 功能卡片的持久化顺序；当前概览保留该接口供拖动排序组件接入。
 * @param onFeatureOrderChanged 保存新功能顺序的回调；当前概览保留该接口避免上层状态丢失。
 * @param onOpenLedger 打开记账功能的回调。
 * @param onOpenFitness 打开运动功能的回调。
 * @param onOpenHotTopics 打开每日热点的回调。
 * @param onOpenMobileData 打开手机流量统计的回调。
 * @param onOpenAppUsage 打开应用使用统计的回调。
 * @param onOpenBreathHold 打开深海憋气计时的回调。
 * @param onOpenWechatReminder 打开微信消息提醒的回调。
 * @param onOpenGeneralReminder 打开普通通知提醒的回调。
 * @param onOpenLocalCleanup 打开手机清理的回调。
 * @param onOpenToday 打开今日总览的回调。
 * @param onOpenSearch 打开全局本地搜索的回调。
 * @param onOpenBackup 打开本地备份与恢复的回调。
 * @param onOpenNotebook 打开富内容记事本的回调。
 * @param onOpenEbooks 打开本地电子书书架的回调。
 * @param onOpenEnglishWords 打开离线英语单词学习页的回调。
 * @param onOpenChineseGrowth 打开语文写作与阅读成长页的回调。
 * @param onOpenQrScanner 打开完全本地识别的二维码扫描页回调。
 * @param onOpenFlashlight 打开可调频率、时长和亮度的手电筒页回调。
 * @param onOpenMaoQuotes 打开毛主席语录章节阅读、搜索收藏与本地导入页的回调。
 * @param onOpenDualCamera 打开前后摄像头等分同屏预览页的回调。
 * @param onOpenLiveTranslation 打开内录声音并显示悬浮字幕的实时翻译页回调。
 * @param onOpenCook 打开离线菜谱搜索与食材匹配页的回调。
 *
 * @return 无返回值，直接输出功能入口网格。
 */
@Composable
private fun FeatureCenterOverview(
    modifier: Modifier,
    gridState: LazyGridState,
    restoreIndex: Int,
    restoreOffset: Int,
    onPositionCaptured: (index: Int, offset: Int) -> Unit,
    featureOrder: List<HomeFeatureId>,
    onFeatureOrderChanged: (List<HomeFeatureId>) -> Boolean,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenAppUsage: () -> Unit,
    onOpenBreathHold: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenEbooks: () -> Unit,
    onOpenEnglishWords: () -> Unit,
    onOpenChineseGrowth: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenFlashlight: () -> Unit,
    onOpenMaoQuotes: () -> Unit,
    onOpenDualCamera: () -> Unit,
    onOpenLiveTranslation: () -> Unit,
    onOpenCook: () -> Unit,
    onOpenWechatReminder: () -> Unit,
    onOpenGeneralReminder: () -> Unit,
    onOpenLocalCleanup: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    var displayedOrder by remember(featureOrder) {
        mutableStateOf(featureOrder)
    }
    var draggingFeatureId by remember {
        mutableStateOf<HomeFeatureId?>(null)
    }
    var dragOffset by remember {
        mutableStateOf(Offset.Zero)
    }
    val entriesById = featureCenterEntries(
        onOpenLedger = onOpenLedger,
        onOpenFitness = onOpenFitness,
        onOpenHotTopics = onOpenHotTopics,
        onOpenMobileData = onOpenMobileData,
        onOpenAppUsage = onOpenAppUsage,
        onOpenBreathHold = onOpenBreathHold,
        onOpenToday = onOpenToday,
        onOpenSearch = onOpenSearch,
        onOpenBackup = onOpenBackup,
        onOpenNotebook = onOpenNotebook,
        onOpenEbooks = onOpenEbooks,
        onOpenEnglishWords = onOpenEnglishWords,
        onOpenChineseGrowth = onOpenChineseGrowth,
        onOpenQrScanner = onOpenQrScanner,
        onOpenFlashlight = onOpenFlashlight,
        onOpenMaoQuotes = onOpenMaoQuotes,
        onOpenDualCamera = onOpenDualCamera,
        onOpenLiveTranslation = onOpenLiveTranslation,
        onOpenCook = onOpenCook,
        onOpenWechatReminder = onOpenWechatReminder,
        onOpenGeneralReminder = onOpenGeneralReminder,
        onOpenLocalCleanup = onOpenLocalCleanup
    ).associateBy(FeatureEntry::id)
    val orderedEntries = displayedOrder.mapNotNull(entriesById::get)
    val context = LocalContext.current
    val visualTheme = LocalAppVisualTheme.current
    val themeArtResourceId = remember(visualTheme.artResourceName) {
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

    // 外层全屏页面返回时，Scaffold会先恢复底部导航再收窄内容区。等待两个布局帧后按离开前的
    // 首项和像素偏移复位，可避开中间视口对LazyGridState产生的一次性滚动夹紧。
    LaunchedEffect(gridState, restoreIndex, restoreOffset, orderedEntries.size) {
        if (orderedEntries.isNotEmpty()) {
            withFrameNanos { }
            withFrameNanos { }
            gridState.scrollToItem(
                index = restoreIndex.coerceIn(0, orderedEntries.lastIndex),
                scrollOffset = restoreOffset.coerceAtLeast(0)
            )
        }
    }

    HarleyPageBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, top = 22.dp, end = 20.dp)
        ) {
            HarleyPageHeader(
                title = "功能中心",
                subtitle = "${orderedEntries.size} 个本地工具 · 长按卡片可拖动排序",
                eyebrow = "TOOLS",
                trailing = {
                    if (themeArtResourceId != 0) {
                        Surface(
                            modifier = Modifier.size(58.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = harleyCardBorder(alpha = 0.8f)
                        ) {
                            Image(
                                painter = painterResource(themeArtResourceId),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        HarleySymbolBadge(symbol = "功")
                    }
                }
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 144.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 20.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = orderedEntries,
                    key = { entry -> entry.id.name }
                ) { entry ->
                val isDragging = draggingFeatureId == entry.id
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 1.055f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = 520f
                    ),
                    label = "feature_drag_scale_${entry.id.name}"
                )

                    Box(
                        modifier = Modifier
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragOffset.x else 0f
                                translationY = if (isDragging) dragOffset.y else 0f
                                scaleX = dragScale
                                scaleY = dragScale
                                shadowElevation = if (isDragging) 24f else 0f
                            }
                            .pointerInput(entry.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingFeatureId = entry.id
                                    dragOffset = Offset.Zero
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.LongPress
                                    )
                                },
                                onDragCancel = {
                                    displayedOrder = featureOrder
                                    draggingFeatureId = null
                                    dragOffset = Offset.Zero
                                },
                                onDragEnd = {
                                    val saved = onFeatureOrderChanged(displayedOrder)
                                    if (!saved) {
                                        displayedOrder = featureOrder
                                    }
                                    draggingFeatureId = null
                                    dragOffset = Offset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount

                                    val draggedItem = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { itemInfo ->
                                            itemInfo.key == entry.id.name
                                        } ?: return@detectDragGesturesAfterLongPress
                                    val draggedCenterX = draggedItem.offset.x +
                                        dragOffset.x + draggedItem.size.width / 2f
                                    val draggedCenterY = draggedItem.offset.y +
                                        dragOffset.y + draggedItem.size.height / 2f
                                    val targetItem = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { itemInfo ->
                                            draggedCenterX >= itemInfo.offset.x &&
                                                draggedCenterX <= itemInfo.offset.x + itemInfo.size.width &&
                                                draggedCenterY >= itemInfo.offset.y &&
                                                draggedCenterY <= itemInfo.offset.y + itemInfo.size.height
                                        } ?: return@detectDragGesturesAfterLongPress
                                    val sourceIndex = displayedOrder.indexOf(entry.id)
                                    val targetIndex = targetItem.index
                                    if (sourceIndex >= 0 &&
                                        targetIndex in displayedOrder.indices &&
                                        sourceIndex != targetIndex
                                    ) {
                                        displayedOrder = moveFeatureCenterItem(
                                            order = displayedOrder,
                                            fromIndex = sourceIndex,
                                            toIndex = targetIndex
                                        )
                                        dragOffset += Offset(
                                            x = (draggedItem.offset.x - targetItem.offset.x).toFloat(),
                                            y = (draggedItem.offset.y - targetItem.offset.y).toFloat()
                                        )
                                    }
                                }
                            )
                            }
                    ) {
                        FeatureEntryCard(
                            modifier = Modifier.fillMaxWidth(),
                            entry = entry.copy(
                                onClick = {
                                    onPositionCaptured(
                                        gridState.firstVisibleItemIndex,
                                        gridState.firstVisibleItemScrollOffset
                                    )
                                    entry.onClick()
                                }
                            )
                        )
                    }
                }
            }
        }
    }
}

/** 单个功能入口所需的稳定标识、展示内容和点击回调。 */
private data class FeatureEntry(
    val id: HomeFeatureId,
    val symbol: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

/**
 * 创建功能中心全部入口的固定内容目录。
 *
 * 使用方法：
 * 拖动网格只改变HomeFeatureId顺序，本函数始终把稳定标识映射回对应标题、图标和点击回调，
 * 因此重新排序不会造成显示内容与实际功能串位。
 *
 * @param onOpenLedger 打开记账功能的回调。
 * @param onOpenFitness 打开运动功能的回调。
 * @param onOpenHotTopics 打开每日热点的回调。
 * @param onOpenMobileData 打开手机流量的回调。
 * @param onOpenAppUsage 打开应用使用统计的回调。
 * @param onOpenBreathHold 打开深海憋气计时的回调。
 * @param onOpenToday 打开今日总览的回调。
 * @param onOpenSearch 打开全局搜索的回调。
 * @param onOpenBackup 打开本地备份的回调。
 * @param onOpenNotebook 打开富内容记事本的回调。
 * @param onOpenEbooks 打开本地电子书书架的回调。
 * @param onOpenEnglishWords 打开离线英语单词学习页的回调。
 * @param onOpenChineseGrowth 打开语文写作与阅读成长页的回调。
 * @param onOpenQrScanner 打开完全本地识别的二维码扫描页回调。
 * @param onOpenFlashlight 打开手电筒和爆闪控制页回调。
 * @param onOpenMaoQuotes 打开毛主席语录章节阅读、搜索收藏与本地导入页的回调。
 * @param onOpenDualCamera 打开前后摄像头等分同屏预览页的回调。
 * @param onOpenLiveTranslation 打开内录声音并显示悬浮字幕的实时翻译页回调。
 * @param onOpenCook 打开离线菜谱搜索与食材匹配页的回调。
 * @param onOpenWechatReminder 打开微信消息提醒的回调。
 * @param onOpenGeneralReminder 打开通知提醒的回调。
 * @param onOpenLocalCleanup 打开手机清理的回调。
 *
 * @return 包含当前全部功能且使用稳定标识的入口列表。
 */
private fun featureCenterEntries(
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenAppUsage: () -> Unit,
    onOpenBreathHold: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenEbooks: () -> Unit,
    onOpenEnglishWords: () -> Unit,
    onOpenChineseGrowth: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenFlashlight: () -> Unit,
    onOpenMaoQuotes: () -> Unit,
    onOpenDualCamera: () -> Unit,
    onOpenLiveTranslation: () -> Unit,
    onOpenCook: () -> Unit,
    onOpenWechatReminder: () -> Unit,
    onOpenGeneralReminder: () -> Unit,
    onOpenLocalCleanup: () -> Unit
): List<FeatureEntry> {
    return listOf(
        FeatureEntry(HomeFeatureId.TODAY, "今", "今日总览", "天气、收支、提醒与运动", onOpenToday),
        FeatureEntry(HomeFeatureId.SEARCH, "搜", "全局搜索", "搜索全部本机数据", onOpenSearch),
        FeatureEntry(HomeFeatureId.LEDGER, "¥", "记账", "账目、汇总与微信账单", onOpenLedger),
        FeatureEntry(HomeFeatureId.FITNESS, "动", "运动", "目标、记录与区间分析", onOpenFitness),
        FeatureEntry(HomeFeatureId.HOT_TOPICS, "热", "每日热点", "查看今日网络热点摘要", onOpenHotTopics),
        FeatureEntry(HomeFeatureId.MOBILE_DATA, "流", "手机流量", "蜂窝流量统计与排行", onOpenMobileData),
        FeatureEntry(HomeFeatureId.WECHAT_REMINDER, "微", "微信消息提醒", "未查看消息重复提醒", onOpenWechatReminder),
        FeatureEntry(HomeFeatureId.GENERAL_REMINDER, "铃", "通知提醒", "一次或重复本机通知", onOpenGeneralReminder),
        FeatureEntry(HomeFeatureId.BACKUP, "备", "本地备份", "换手机导出与恢复", onOpenBackup),
        FeatureEntry(HomeFeatureId.LOCAL_CLEANUP, "清", "手机清理", "缓存统计与存储管理", onOpenLocalCleanup),
        FeatureEntry(HomeFeatureId.ENGLISH_WORDS, "英", "英语单词", "离线词库、例句与发音", onOpenEnglishWords),
        FeatureEntry(HomeFeatureId.NOTEBOOK, "记", "记事本", "富内容文章、查询与往期回顾", onOpenNotebook),
        FeatureEntry(HomeFeatureId.EBOOKS, "书", "电子书", "导入书籍、多种翻页与阅读进度", onOpenEbooks),
        FeatureEntry(HomeFeatureId.CHINESE_GROWTH, "文", "语文成长", "分级写作训练与精选阅读", onOpenChineseGrowth),
        FeatureEntry(HomeFeatureId.QR_SCANNER, "码", "二维码扫描", "本地识别相机与相册二维码", onOpenQrScanner),
        FeatureEntry(HomeFeatureId.APP_USAGE, "用", "应用使用", "时长、次数与七天趋势", onOpenAppUsage),
        FeatureEntry(HomeFeatureId.BREATH_HOLD, "息", "深海憋气", "沉浸计时与本机记录", onOpenBreathHold),
        FeatureEntry(HomeFeatureId.FLASHLIGHT, "光", "手电筒", "亮度、频率与明灭时长控制", onOpenFlashlight),
        FeatureEntry(HomeFeatureId.MAO_QUOTES, "录", "毛主席语录", "章节阅读、搜索收藏与本地导入", onOpenMaoQuotes),
        FeatureEntry(HomeFeatureId.DUAL_CAMERA, "双", "前后双摄", "等分同屏、点击互换与手势变焦", onOpenDualCamera),
        FeatureEntry(HomeFeatureId.LIVE_TRANSLATION, "译", "实时翻译", "内录英/日语声音并悬浮显示中文字幕", onOpenLiveTranslation),
        FeatureEntry(HomeFeatureId.COOK, "厨", "Cook 菜谱", "搜索做法，按现有食材推理可做菜品", onOpenCook)
    )
}

/**
 * 显示一个带即时按压反馈的功能入口卡片。
 *
 * 使用方法：
 * 传入功能入口数据即可。按下卡片时会同步触发缩放、容器变色、阴影降低和Material涟漪，
 * 抬手后立即执行entry.onClick，不额外延迟导航。
 *
 * @param entry 功能名称、说明、图标字符和点击回调。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun FeatureEntryCard(
    entry: FeatureEntry,
    modifier: Modifier = Modifier
) {
    val (badgeContainerColor, badgeContentColor) = when (entry.id) {
        HomeFeatureId.EBOOKS,
        HomeFeatureId.CHINESE_GROWTH,
        HomeFeatureId.ENGLISH_WORDS,
        HomeFeatureId.MAO_QUOTES,
        HomeFeatureId.COOK -> {
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        }

        HomeFeatureId.QR_SCANNER,
        HomeFeatureId.MOBILE_DATA,
        HomeFeatureId.APP_USAGE,
        HomeFeatureId.FLASHLIGHT,
        HomeFeatureId.DUAL_CAMERA,
        HomeFeatureId.LIVE_TRANSLATION,
        HomeFeatureId.LOCAL_CLEANUP,
        HomeFeatureId.BACKUP -> {
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        }

        HomeFeatureId.TODAY,
        HomeFeatureId.LEDGER,
        HomeFeatureId.FITNESS,
        HomeFeatureId.BREATH_HOLD -> {
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
        }

        else -> {
            MaterialTheme.colorScheme.surfaceContainerHighest to
                MaterialTheme.colorScheme.onSurface
        }
    }

    val interactionSource = remember(entry.id) { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) FEATURE_ENTRY_PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = FEATURE_ENTRY_PRESS_DAMPING_RATIO,
            stiffness = FEATURE_ENTRY_PRESS_STIFFNESS
        ),
        label = "feature_entry_press_scale_${entry.id.name}"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isPressed) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = FEATURE_ENTRY_COLOR_ANIMATION_MILLIS),
        label = "feature_entry_press_color_${entry.id.name}"
    )

    Card(
        onClick = entry.onClick,
        modifier = modifier
            .heightIn(min = 148.dp)
            .graphicsLayer {
                scaleX = pressedScale
                scaleY = pressedScale
            },
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = harleyCardBorder(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            HarleySymbolBadge(
                symbol = entry.symbol,
                containerColor = badgeContainerColor,
                contentColor = badgeContentColor
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 为记账、运动和功能中心内部工具提供统一的返回标题栏与内容容器。
 *
 * 使用方法：
 * 独立详情页把原页面作为content传入；用户点击“返回功能中心”或系统返回键都会执行onBack。
 *
 * @param title 当前详情标题。
 * @param subtitle 当前详情的简短说明。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部传入的安全边距。
 * @param content 详情主体，参数是已经填满剩余区域的Modifier。
 *
 * @return 无返回值，直接输出统一详情框架。
 */
@Composable
fun FeatureDetailScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    BackHandler(onBack = onBack)

    HarleyPageBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = harleyCardBorder(alpha = 0.72f),
                tonalElevation = 0.dp,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text(
                            text = "← 功能中心",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * 把原有功能卡片放入统一详情框架，并提供独立滚动空间。
 *
 * @param title 详情标题。
 * @param subtitle 详情说明。
 * @param onBack 返回概览的回调。
 * @param modifier 外部安全边距。
 * @param cardContent 原有功能卡片内容。
 *
 * @return 无返回值。
 */
@Composable
private fun FeatureCardDetailScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable () -> Unit
) {
    FeatureDetailScaffold(
        modifier = modifier,
        title = title,
        subtitle = subtitle,
        onBack = onBack
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                cardContent()
            }
        }
    }
}
