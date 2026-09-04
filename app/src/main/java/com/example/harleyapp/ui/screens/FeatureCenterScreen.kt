package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.data.ChineseGrowthRepository
import com.example.harleyapp.data.EbookRepository
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.LocalCleanupResult
import com.example.harleyapp.model.LocalCleanupStatus
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import com.example.harleyapp.model.moveFeatureCenterItem
import com.example.harleyapp.system.OfflineEnglishTtsState
import com.example.harleyapp.ui.components.bouncyClickable
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
    CHINESE_GROWTH
}

/**
 * 集中展示记账、运动、提醒与清理功能，并承载轻量工具的详情页面。
 *
 * 使用方法：
 * 由HarleyApp在底部“功能”被选中时调用。记账和运动通过独立回调打开原有完整页面；其他工具
 * 在功能中心内部切换详情，所有仓库、权限和保存回调仍由HarleyApp统一提供。
 *
 * @param page 当前功能中心页面。
 * @param onPageChanged 切换功能中心概览或内部详情的回调。
 * @param featureOrder 功能卡片当前从左到右、从上到下的持久化顺序。
 * @param onFeatureOrderChanged 长按拖动结束后保存完整新顺序的回调，成功返回true。
 * @param onOpenLedger 打开原有完整记账页面的回调。
 * @param onOpenFitness 打开原有完整运动页面的回调。
 * @param onOpenHotTopics 打开每日热点完整页面的回调。
 * @param onOpenMobileData 打开手机流量统计页面的回调。
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
    featureOrder: List<HomeFeatureId>,
    onFeatureOrderChanged: (List<HomeFeatureId>) -> Boolean,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
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
    when (page) {
        FeatureCenterPage.OVERVIEW -> FeatureCenterOverview(
            modifier = modifier,
            featureOrder = featureOrder,
            onFeatureOrderChanged = onFeatureOrderChanged,
            onOpenLedger = onOpenLedger,
            onOpenFitness = onOpenFitness,
            onOpenHotTopics = onOpenHotTopics,
            onOpenMobileData = onOpenMobileData,
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
    }
}

/**
 * 显示功能中心全部卡片化入口。
 *
 * @param modifier 外部布局修饰器。
 * @param featureOrder 功能卡片的持久化顺序；当前概览保留该接口供拖动排序组件接入。
 * @param onFeatureOrderChanged 保存新功能顺序的回调；当前概览保留该接口避免上层状态丢失。
 * @param onOpenLedger 打开记账功能的回调。
 * @param onOpenFitness 打开运动功能的回调。
 * @param onOpenHotTopics 打开每日热点的回调。
 * @param onOpenMobileData 打开手机流量统计的回调。
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
 *
 * @return 无返回值，直接输出功能入口网格。
 */
@Composable
private fun FeatureCenterOverview(
    modifier: Modifier,
    featureOrder: List<HomeFeatureId>,
    onFeatureOrderChanged: (List<HomeFeatureId>) -> Boolean,
    onOpenLedger: () -> Unit,
    onOpenFitness: () -> Unit,
    onOpenHotTopics: () -> Unit,
    onOpenMobileData: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenEbooks: () -> Unit,
    onOpenEnglishWords: () -> Unit,
    onOpenChineseGrowth: () -> Unit,
    onOpenWechatReminder: () -> Unit,
    onOpenGeneralReminder: () -> Unit,
    onOpenLocalCleanup: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
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
        onOpenToday = onOpenToday,
        onOpenSearch = onOpenSearch,
        onOpenBackup = onOpenBackup,
        onOpenNotebook = onOpenNotebook,
        onOpenEbooks = onOpenEbooks,
        onOpenEnglishWords = onOpenEnglishWords,
        onOpenChineseGrowth = onOpenChineseGrowth,
        onOpenWechatReminder = onOpenWechatReminder,
        onOpenGeneralReminder = onOpenGeneralReminder,
        onOpenLocalCleanup = onOpenLocalCleanup
    ).associateBy(FeatureEntry::id)
    val orderedEntries = displayedOrder.mapNotNull(entriesById::get)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 20.dp, top = 22.dp, end = 20.dp)
    ) {
        Text(
            text = "功能中心",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "轻点打开 · 长按卡片后拖动可调整位置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 14.dp),
            state = gridState,
            contentPadding = PaddingValues(bottom = 28.dp),
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
                        entry = entry
                    )
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
 * @param onOpenToday 打开今日总览的回调。
 * @param onOpenSearch 打开全局搜索的回调。
 * @param onOpenBackup 打开本地备份的回调。
 * @param onOpenNotebook 打开富内容记事本的回调。
 * @param onOpenEbooks 打开本地电子书书架的回调。
 * @param onOpenEnglishWords 打开离线英语单词学习页的回调。
 * @param onOpenChineseGrowth 打开语文写作与阅读成长页的回调。
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
    onOpenToday: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenEbooks: () -> Unit,
    onOpenEnglishWords: () -> Unit,
    onOpenChineseGrowth: () -> Unit,
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
        FeatureEntry(HomeFeatureId.CHINESE_GROWTH, "文", "语文成长", "分级写作训练与精选阅读", onOpenChineseGrowth)
    )
}

/**
 * 显示一个可点击的功能入口卡片。
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
    val context = LocalContext.current
    val visualTheme = LocalAppVisualTheme.current
    val themeArtResourceId = remember(visualTheme.artResourceName) {
        context.resources.getIdentifier(
            visualTheme.artResourceName,
            "drawable",
            context.packageName
        )
    }
    Card(
        modifier = modifier
            .heightIn(min = 128.dp)
            .bouncyClickable(onClick = entry.onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (themeArtResourceId != 0) {
                    Image(
                        painter = painterResource(themeArtResourceId),
                        contentDescription = "${visualTheme.displayName}人物",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = entry.symbol,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "← 功能中心")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
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
