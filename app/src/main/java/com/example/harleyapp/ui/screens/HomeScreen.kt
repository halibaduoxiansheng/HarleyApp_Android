package com.example.harleyapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionInteraction
import com.example.harleyapp.model.CompanionOperationResult
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionShopItem
import com.example.harleyapp.model.DeviceSnapshot
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.system.DeviceMonitor
import com.example.harleyapp.system.NetworkSample
import com.example.harleyapp.system.OfflineEnglishTtsState
import com.example.harleyapp.ui.components.bouncyClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 显示首页概览、实时网络速率和用户快捷应用。
 *
 * 使用方法：
 * 由HarleyApp在首页导航项选中时调用。页面可见期间每秒读取一次轻量网络状态，
 * 用于展示上传和下载速率；容量信息统一放在“我的”页面，离开首页后LaunchedEffect会自动取消。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param deviceMonitor 设备状态读取服务。
 * @param shortcuts 用户已选且仍然安装的快捷应用。
 * @param onLaunchApp 点击快捷应用后的启动回调，参数为应用包名。
 * @param onManageShortcuts 前往快捷应用管理页面的回调。
 * @param websites 用户保存的首页网站轮播列表。
 * @param defaultWebsiteId 点击底部“网站”时默认打开的网站标识。
 * @param companionProgress 玩偶当前经验、分类和今日任务状态。
 * @param onOpenWebsite 前往内置网站页面的回调，参数为用户点击的网站。
 * @param onOpenWebsiteDetails 打开网站收藏夹详情与管理页面的回调。
 * @param onSetDefaultWebsite 把指定网站设为底部网站页签默认入口的回调。
 * @param selectedHomeFeatures 用户选择在首页展示的功能标识集合。
 * @param onSaveHomeFeatures 覆盖保存首页功能选择的回调，成功返回true。
 * @param onOpenFeature 点击首页功能卡片后的统一导航回调，参数为功能标识。
 * @param englishWord 首页当前随机显示的尚未完成单词；全部达到三次时为null。
 * @param englishRemainingCount 当前尚未达到三次、仍参与首页随机复习的单词数量。
 * @param englishTtsState Android离线英语TTS当前状态。
 * @param onSpeakEnglish 朗读英文单词或例句的回调，成功提交返回true。
 * @param onMarkEnglishWordLearned 把指定首页单词学习次数增加一次的回调。
 * @param onSaveWebsite 新增或编辑网站的同步保存回调，成功返回true。
 * @param onDeleteWebsite 删除网站的同步回调，成功返回true。
 * @param onSelectCompanionCategory 更换玩偶分类的同步保存回调，成功返回true。
 * @param onPurchaseCompanionItem 使用金币购买伙伴物品的回调。
 * @param onUseCompanionItem 使用背包物品与伙伴互动的回调。
 * @param onCompleteCompanionInteraction 完成免费互动或小游戏后的回调。
 * @param onQuickSearch 提交首页顶部快捷搜索词的回调；宿主收到后打开完整全局搜索页。
 *
 * @return 无返回值，直接输出首页界面。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    deviceMonitor: DeviceMonitor,
    shortcuts: List<LaunchableApp>,
    onLaunchApp: (String) -> Unit,
    onManageShortcuts: () -> Unit,
    websites: List<WebsiteShortcut>,
    defaultWebsiteId: String?,
    companionProgress: CompanionProgress,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onOpenWebsiteDetails: () -> Unit,
    onSetDefaultWebsite: (String) -> Boolean,
    selectedHomeFeatures: Set<HomeFeatureId>,
    onSaveHomeFeatures: (Set<HomeFeatureId>) -> Boolean,
    onOpenFeature: (HomeFeatureId) -> Unit,
    englishWord: EnglishWord?,
    englishRemainingCount: Int,
    englishTtsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkEnglishWordLearned: (String) -> Boolean,
    onSaveWebsite: (WebsiteShortcut) -> Boolean,
    onDeleteWebsite: (String) -> Boolean,
    onSelectCompanionCategory: (CompanionCategory) -> Boolean,
    onPurchaseCompanionItem: (CompanionShopItem) -> CompanionOperationResult,
    onUseCompanionItem: (CompanionShopItem) -> CompanionOperationResult,
    onCompleteCompanionInteraction: (CompanionInteraction) -> CompanionOperationResult,
    onQuickSearch: (String) -> Unit
) {
    var snapshot by remember {
        mutableStateOf(DeviceSnapshot())
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var quickSearchVisible by rememberSaveable { mutableStateOf(true) }
    var quickSearchQuery by rememberSaveable { mutableStateOf("") }
    var quickSearchFocused by remember { mutableStateOf(false) }

    // 用累计滚动距离而不是单个像素方向切换搜索框，避免手指轻微抖动导致显隐状态反复翻转。
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        var accumulatedScroll = 0

        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (currentIndex, currentOffset) ->
            val isAtTop = currentIndex == 0 && currentOffset == 0
            val scrollDelta = when {
                currentIndex > previousIndex -> QUICK_SEARCH_HIDE_DISTANCE_PX
                currentIndex < previousIndex -> -QUICK_SEARCH_SHOW_DISTANCE_PX
                else -> currentOffset - previousOffset
            }

            if (isAtTop) {
                quickSearchVisible = true
                accumulatedScroll = 0
            } else if (scrollDelta != 0) {
                // 滚动方向改变时重新累计，只有稳定滑动超过阈值才触发一次显隐。
                if (
                    accumulatedScroll != 0 &&
                    (accumulatedScroll > 0) != (scrollDelta > 0)
                ) {
                    accumulatedScroll = scrollDelta
                } else {
                    accumulatedScroll += scrollDelta
                }
                when {
                    quickSearchVisible && accumulatedScroll >= QUICK_SEARCH_HIDE_DISTANCE_PX -> {
                        // 用户开始浏览首页内容时同步收起键盘和输入焦点，否则焦点状态会强制搜索条继续显示。
                        focusManager.clearFocus(force = true)
                        quickSearchFocused = false
                        quickSearchVisible = false
                        accumulatedScroll = 0
                    }
                    !quickSearchVisible && accumulatedScroll <= -QUICK_SEARCH_SHOW_DISTANCE_PX -> {
                        quickSearchVisible = true
                        accumulatedScroll = 0
                    }
                }
            }
            previousIndex = currentIndex
            previousOffset = currentOffset
        }
    }

    // 仅在首页可见时每秒采样，避免无意义地常驻消耗电量。
    LaunchedEffect(deviceMonitor) {
        var previousSample: NetworkSample? = null

        while (isActive) {
            val reading = withContext(Dispatchers.Default) {
                deviceMonitor.read(previousSample)
            }
            snapshot = reading.snapshot
            previousSample = reading.networkSample
            delay(1_000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 88.dp,
                end = 20.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WebsiteCarousel(
                    websites = websites,
                    defaultWebsiteId = defaultWebsiteId,
                    onOpenWebsite = onOpenWebsite,
                    onOpenDetails = onOpenWebsiteDetails,
                    onSetDefaultWebsite = onSetDefaultWebsite,
                    onSaveWebsite = onSaveWebsite,
                    onDeleteWebsite = onDeleteWebsite
                )
            }

        item {
            HomeFeatureCarousel(
                selectedFeatures = selectedHomeFeatures,
                onSaveSelection = onSaveHomeFeatures,
                onOpenFeature = onOpenFeature
            )
        }

        item {
            HomeEnglishWordCard(
                word = englishWord,
                remainingCount = englishRemainingCount,
                ttsState = englishTtsState,
                onSpeakEnglish = onSpeakEnglish,
                onMarkLearned = onMarkEnglishWordLearned,
                onOpenAll = { onOpenFeature(HomeFeatureId.ENGLISH_WORDS) }
            )
        }

        item {
            SectionTitle(
                title = "我的伙伴",
                subtitle = "完成每日任务，解锁新形态与技能"
            )
        }

        item {
            CompanionCard(
                progress = companionProgress,
                onSelectCategory = onSelectCompanionCategory,
                onPurchaseItem = onPurchaseCompanionItem,
                onUseItem = onUseCompanionItem,
                onCompleteInteraction = onCompleteCompanionInteraction
            )
        }

        item {
            SectionTitle(
                title = "实时状态",
                subtitle = "每秒刷新 · 不主动消耗流量"
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "↑",
                    title = "上传",
                    value = formatSpeed(snapshot.uploadBytesPerSecond),
                    highlighted = true
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    symbol = "↓",
                    title = "下载",
                    value = formatSpeed(snapshot.downloadBytesPerSecond),
                    highlighted = false
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    modifier = Modifier.weight(1f),
                    title = "快捷应用",
                    subtitle = if (shortcuts.isEmpty()) "还没有选择应用" else "点击即可打开"
                )

                TextButton(onClick = onManageShortcuts) {
                    Text(text = "管理")
                }
            }
        }

        if (shortcuts.isEmpty()) {
            item {
                EmptyShortcutCard(onManageShortcuts = onManageShortcuts)
            }
        } else {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    shortcuts.forEach { app ->
                        ShortcutItem(
                            app = app,
                            onClick = {
                                onLaunchApp(app.packageName)
                            }
                        )
                    }
                }
            }
            }
        }

        // 搜索条覆盖在列表上方，不参与LazyColumn高度计算；显隐时下面内容不会重新测量或突然跳位。
        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(2f),
            visible = quickSearchVisible || quickSearchFocused,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = QUICK_SEARCH_ENTER_MILLIS),
                initialOffsetY = { height -> -height / 2 }
            ) + fadeIn(animationSpec = tween(durationMillis = QUICK_SEARCH_ENTER_MILLIS)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = QUICK_SEARCH_EXIT_MILLIS),
                targetOffsetY = { height -> -height / 2 }
            ) + fadeOut(animationSpec = tween(durationMillis = QUICK_SEARCH_EXIT_MILLIS))
        ) {
            HomeQuickSearchBar(
                query = quickSearchQuery,
                onQueryChanged = { value -> quickSearchQuery = value.take(MAX_QUICK_SEARCH_LENGTH) },
                onFocusChanged = { focused -> quickSearchFocused = focused },
                onSearch = {
                    val normalizedQuery = quickSearchQuery.trim()
                    if (normalizedQuery.isNotEmpty()) {
                        onQuickSearch(normalizedQuery)
                    }
                }
            )
        }
    }
}

/**
 * 显示首页顶部的轻量搜索入口。
 *
 * 使用方法：
 * 由[HomeScreen]根据滚动方向控制可见性。用户输入关键词后点击“搜索”或键盘搜索键，
 * 页面只提交文本，不在首页重复执行完整索引查询。
 *
 * @param query 当前输入文本。
 * @param onQueryChanged 输入变化回调。
 * @param onFocusChanged 输入框焦点变化回调，用于输入期间保持搜索条可见。
 * @param onSearch 提交非空关键词的回调。
 *
 * @return 无返回值，直接输出一行搜索框和按钮。
 */
@Composable
private fun HomeQuickSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onSearch: () -> Unit
) {
    val searchIconColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 5.dp,
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(22.dp)
                    .semantics { contentDescription = "全局搜索" }
            ) {
                val strokeWidth = 1.8.dp.toPx()
                drawCircle(
                    color = searchIconColor,
                    radius = size.minDimension * 0.29f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = Stroke(width = strokeWidth)
                )
                drawLine(
                    color = searchIconColor,
                    start = Offset(size.width * 0.63f, size.height * 0.63f),
                    end = Offset(size.width * 0.86f, size.height * 0.86f),
                    strokeWidth = strokeWidth
                )
            }

            BasicTextField(
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state -> onFocusChanged(state.isFocused) },
                value = query,
                onValueChange = onQueryChanged,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = "搜索账目、提醒、笔记、单词…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Surface(
                enabled = query.isNotBlank(),
                onClick = onSearch,
                shape = RoundedCornerShape(20.dp),
                color = if (query.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .width(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "搜索",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (query.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/** 首页功能轮播中单个功能所需的固定展示信息和点击行为。 */
private data class HomeFeatureEntry(
    val id: HomeFeatureId,
    val title: String,
    val description: String,
    val symbol: String,
    val statusLabel: String,
    val onClick: () -> Unit
)

/**
 * 创建首页功能的完整有序目录。
 *
 * 使用方法：
 * 首页轮播和管理弹窗都调用本函数，确保可选择的功能、显示顺序和导航标识完全一致。新增功能时
 * 在此补充展示信息，并在HarleyApp的onOpenFeature分支中补充实际导航。
 *
 * @param onOpenFeature 打开指定功能的统一导航回调。
 *
 * @return 与HomeFeatureId声明顺序一致的完整首页功能目录。
 */
private fun homeFeatureEntries(
    onOpenFeature: (HomeFeatureId) -> Unit
): List<HomeFeatureEntry> {
    return listOf(
        HomeFeatureEntry(
            id = HomeFeatureId.TODAY,
            title = "今日总览",
            description = "天气、收支、提醒与运动",
            symbol = "今",
            statusLabel = "本地汇总",
            onClick = { onOpenFeature(HomeFeatureId.TODAY) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.SEARCH,
            title = "全局搜索",
            description = "搜索全部本机数据",
            symbol = "搜",
            statusLabel = "本地",
            onClick = { onOpenFeature(HomeFeatureId.SEARCH) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.LEDGER,
            title = "记账",
            description = "账目、汇总与微信账单",
            symbol = "¥",
            statusLabel = "本地",
            onClick = { onOpenFeature(HomeFeatureId.LEDGER) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.FITNESS,
            title = "运动",
            description = "目标、记录与区间分析",
            symbol = "动",
            statusLabel = "本地",
            onClick = { onOpenFeature(HomeFeatureId.FITNESS) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.HOT_TOPICS,
            title = "每日热点",
            description = "查看今日网络热点摘要",
            symbol = "热",
            statusLabel = "实时",
            onClick = { onOpenFeature(HomeFeatureId.HOT_TOPICS) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.MOBILE_DATA,
            title = "手机流量",
            description = "蜂窝流量统计与排行",
            symbol = "流",
            statusLabel = "仅蜂窝",
            onClick = { onOpenFeature(HomeFeatureId.MOBILE_DATA) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.WECHAT_REMINDER,
            title = "微信消息提醒",
            description = "未查看消息重复提醒",
            symbol = "微",
            statusLabel = "通知",
            onClick = { onOpenFeature(HomeFeatureId.WECHAT_REMINDER) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.GENERAL_REMINDER,
            title = "通知提醒",
            description = "一次或重复本机通知",
            symbol = "铃",
            statusLabel = "通知",
            onClick = { onOpenFeature(HomeFeatureId.GENERAL_REMINDER) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.BACKUP,
            title = "本地备份",
            description = "换手机导出与恢复",
            symbol = "备",
            statusLabel = "本地",
            onClick = { onOpenFeature(HomeFeatureId.BACKUP) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.LOCAL_CLEANUP,
            title = "手机清理",
            description = "缓存统计与存储管理",
            symbol = "清",
            statusLabel = "设备",
            onClick = { onOpenFeature(HomeFeatureId.LOCAL_CLEANUP) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.ENGLISH_WORDS,
            title = "英语单词",
            description = "离线词库、例句与发音",
            symbol = "英",
            statusLabel = "离线",
            onClick = { onOpenFeature(HomeFeatureId.ENGLISH_WORDS) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.NOTEBOOK,
            title = "记事本",
            description = "富内容文章、查询与往期回顾",
            symbol = "记",
            statusLabel = "本地",
            onClick = { onOpenFeature(HomeFeatureId.NOTEBOOK) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.EBOOKS,
            title = "电子书",
            description = "导入书籍、多种翻页与阅读进度",
            symbol = "书",
            statusLabel = "离线",
            onClick = { onOpenFeature(HomeFeatureId.EBOOKS) }
        ),
        HomeFeatureEntry(
            id = HomeFeatureId.CHINESE_GROWTH,
            title = "语文成长",
            description = "写作训练与精选阅读",
            symbol = "文",
            statusLabel = "分级",
            onClick = { onOpenFeature(HomeFeatureId.CHINESE_GROWTH) }
        )
    )
}

/**
 * 显示首页随机单词卡，并允许用户不进入详情页就完成一次学习。
 *
 * 使用方法：
 * HomeScreen传入上层已选择好的单词。点击“学会 +1”只在保存成功后由上层更换下一随机词；
 * 候选全部完成三次后显示本轮完成状态，仍可进入详情页搜索全部词或查看四个进度分栏。
 *
 * @param word 当前随机单词；全部完成时为null。
 * @param remainingCount 尚未达到三次的单词数量。
 * @param ttsState Android离线英语TTS状态。
 * @param onSpeakEnglish 朗读英文文本的回调。
 * @param onMarkLearned 学习次数加一的回调，保存成功返回true。
 * @param onOpenAll 打开英语学习详情页的回调。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun HomeEnglishWordCard(
    word: EnglishWord?,
    remainingCount: Int,
    ttsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: (String) -> Boolean,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var saveFailed by remember(word?.id) {
        mutableStateOf(false)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日英语",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (word == null) {
                            "本轮单词已全部完成"
                        } else {
                            "仍有 $remainingCount 个单词未完成三次"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(onClick = onOpenAll) {
                    Text(text = "查看全部")
                }
            }

            if (word == null) {
                Text(
                    text = "做得很好！可以进入四个分栏，把想继续巩固的单词设为“重新学习”。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = word.word,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "${word.learnedCount} / $ENGLISH_WORD_MASTERY_COUNT 次",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = word.meaningZh,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (word.phonetic.isNotBlank()) {
                    Text(
                        text = "/${word.phonetic.trim('/')}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (word.exampleEn.isNotBlank()) {
                    Text(
                        text = word.exampleEn,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (word.exampleZh.isNotBlank()) {
                        Text(
                            text = word.exampleZh,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (word.definitionEn.isNotBlank()) {
                    Text(
                        text = word.definitionEn,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (ttsState != OfflineEnglishTtsState.READY) {
                    Text(
                        text = when (ttsState) {
                            OfflineEnglishTtsState.INITIALIZING -> "正在检查本机离线发音…"
                            OfflineEnglishTtsState.MISSING_OFFLINE_VOICE ->
                                "需在系统文字转语音设置中安装英语离线语音包"
                            OfflineEnglishTtsState.ERROR -> "系统文字转语音暂不可用"
                            OfflineEnglishTtsState.READY -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = ttsState == OfflineEnglishTtsState.READY,
                        onClick = { onSpeakEnglish(word.word) }
                    ) {
                        Text(text = "单词发音")
                    }

                    if (word.exampleEn.isNotBlank()) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = ttsState == OfflineEnglishTtsState.READY,
                            onClick = { onSpeakEnglish(word.exampleEn) }
                        ) {
                            Text(text = "朗读例句")
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saveFailed = !onMarkLearned(word.id)
                    }
                ) {
                    Text(text = "学会 +1，并换下一个")
                }

                if (saveFailed) {
                    Text(
                        text = "学习进度保存失败，请重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 显示用户自定义的首页功能轮播。
 *
 * 使用方法：
 * HomeScreen传入已保存的功能集合和导航回调。普通手机每页显示两个功能，宽度达到600dp的
 * 平板或横屏设备每页显示三个；仅响应用户手动左右滑动，不创建自动轮播协程。点击“管理”
 * 打开选择弹窗，未选择任何功能时仍保留恢复入口。
 *
 * @param selectedFeatures 当前需要展示在首页的功能集合。
 * @param onSaveSelection 用户确认选择后的持久化回调，成功返回true。
 * @param onOpenFeature 打开指定功能的统一导航回调。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出标题、手动轮播、页码指示器和管理弹窗。
 */
@Composable
private fun HomeFeatureCarousel(
    selectedFeatures: Set<HomeFeatureId>,
    onSaveSelection: (Set<HomeFeatureId>) -> Boolean,
    onOpenFeature: (HomeFeatureId) -> Unit,
    modifier: Modifier = Modifier
) {
    var managerVisible by rememberSaveable {
        mutableStateOf(false)
    }
    val allFeatures = homeFeatureEntries(onOpenFeature)
    val visibleFeatures = allFeatures.filter { feature ->
        feature.id in selectedFeatures
    }

    if (managerVisible) {
        HomeFeatureManagerDialog(
            features = allFeatures,
            selectedFeatures = selectedFeatures,
            onDismiss = {
                managerVisible = false
            },
            onSave = { newSelection ->
                val saved = onSaveSelection(newSelection)
                if (saved) {
                    managerVisible = false
                }
                saved
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle(
                modifier = Modifier.weight(1f),
                title = "功能中心",
                subtitle = if (visibleFeatures.isEmpty()) {
                    "选择需要放到首页的功能"
                } else {
                    "手动左右滑动 · ${visibleFeatures.size} 个功能"
                }
            )

            TextButton(onClick = { managerVisible = true }) {
                Text(text = "管理")
            }
        }

        if (visibleFeatures.isEmpty()) {
            EmptyHomeFeatureCard(onManage = { managerVisible = true })
        } else {
            HomeFeaturePager(features = visibleFeatures)
        }
    }
}

/**
 * 根据可用宽度把首页功能按每页两个或三个进行手动分页。
 *
 * @param features 已经过滤且保持固定顺序的首页功能列表。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出横向分页和当前位置指示器。
 */
@Composable
private fun HomeFeaturePager(
    features: List<HomeFeatureEntry>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val itemsPerPage = if (maxWidth >= THREE_ITEM_PAGE_MIN_WIDTH) 3 else 2
        val pages = features.chunked(itemsPerPage)
        val pagerState = rememberPagerState(pageCount = { pages.size })

        // 管理弹窗减少功能数量后及时修正页码，避免停留在已经不存在的空白页。
        LaunchedEffect(pages.size) {
            if (pages.isNotEmpty() && pagerState.currentPage > pages.lastIndex) {
                pagerState.scrollToPage(pages.lastIndex)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HOME_FEATURE_PAGE_HEIGHT),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
                userScrollEnabled = pages.size > 1
            ) { pageIndex ->
                val pageFeatures = pages[pageIndex]
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pageFeatures.forEach { feature ->
                        HomeFeatureCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            feature = feature
                        )
                    }

                    // 最后一页不足两项或三项时保留等宽占位，避免单张卡片被拉伸到整行。
                    repeat(itemsPerPage - pageFeatures.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (pages.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pages.indices.forEach { index ->
                        val selected = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 9.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示首页轮播中的一张紧凑功能卡片。
 *
 * @param feature 功能名称、说明、状态标签和点击行为。
 * @param modifier 外部等宽、等高布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun HomeFeatureCard(
    feature: HomeFeatureEntry,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.bouncyClickable(
            role = Role.Button,
            onClick = feature.onClick
        ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    text = feature.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = feature.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = feature.statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 在首页没有选择任何功能时提供可恢复的管理入口。
 *
 * @param onManage 打开功能选择弹窗的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyHomeFeatureCard(onManage: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(role = Role.Button, onClick = onManage),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "首页暂未展示功能，可随时从完整功能中心重新选择。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "去选择 ›",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 让用户勾选需要放到首页的功能。
 *
 * 使用方法：
 * 打开弹窗时传入当前已保存集合。用户的勾选先保存在弹窗临时状态，只有点击“保存”并且
 * onSave返回true后才关闭；点击取消不会改变首页。允许全部取消，完整功能中心不会受影响。
 *
 * @param features 当前版本支持的完整功能目录。
 * @param selectedFeatures 打开弹窗时已经保存的功能集合。
 * @param onDismiss 放弃本次修改并关闭弹窗的回调。
 * @param onSave 保存完整选择集合的回调，成功返回true。
 *
 * @return 无返回值，直接输出功能选择对话框。
 */
@Composable
private fun HomeFeatureManagerDialog(
    features: List<HomeFeatureEntry>,
    selectedFeatures: Set<HomeFeatureId>,
    onDismiss: () -> Unit,
    onSave: (Set<HomeFeatureId>) -> Boolean
) {
    var pendingSelection by remember(selectedFeatures) {
        mutableStateOf(selectedFeatures)
    }
    var saveFailed by remember {
        mutableStateOf(false)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "管理首页功能")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "勾选需要放到首页的功能。普通手机每页显示2项，宽屏显示3项；完整功能中心始终保留全部入口。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    features.forEach { feature ->
                        val checked = feature.id in pendingSelection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .bouncyClickable(
                                    role = Role.Checkbox,
                                    onClick = {
                                        pendingSelection = if (checked) {
                                            pendingSelection - feature.id
                                        } else {
                                            pendingSelection + feature.id
                                        }
                                        saveFailed = false
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feature.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = feature.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (saveFailed) {
                    Text(
                        text = "保存失败，请重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saveFailed = !onSave(pendingSelection)
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示一个章节标题及可选说明。
 *
 * @param modifier 外部布局修饰器。
 * @param title 章节标题。
 * @param subtitle 辅助说明。
 *
 * @return 无返回值。
 */
@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示单项实时速率指标。
 *
 * @param modifier 外部布局修饰器。
 * @param symbol 上行或下行方向符号。
 * @param title 指标名称。
 * @param value 已格式化的速率文本。
 * @param highlighted 是否使用主色强调。
 *
 * @return 无返回值。
 */
@Composable
private fun MetricCard(
    symbol: String,
    title: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/**
 * 显示尚未选择快捷应用时的引导卡片。
 *
 * @param onManageShortcuts 点击选择按钮后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyShortcutCard(onManageShortcuts: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onManageShortcuts),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "选择微信、相机、音乐等常用应用，之后可以从首页一键打开。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "去选择 →",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 显示单个快捷应用图标和名称。
 *
 * @param app 待显示的可启动应用。
 * @param onClick 点击后的启动回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ShortcutItem(
    app: LaunchableApp,
    onClick: () -> Unit
) {
    val imageBitmap = remember(app.icon) {
        app.icon.asImageBitmap()
    }

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(18.dp))
            .bouncyClickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "打开${app.label}",
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 把字节数转换为便于阅读的容量文本。
 *
 * 使用方法：
 * 传入系统返回的字节数，例如formatBytes(1073741824)返回“1.0 GB”。
 *
 * @param bytes 原始字节数，负值会按0处理。
 *
 * @return 带B、KB、MB、GB或TB单位的文本。
 */
private fun formatBytes(bytes: Long): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (unitIndex == 0) {
        String.format(Locale.CHINA, "%.0f %s", value, units[unitIndex])
    } else {
        String.format(Locale.CHINA, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * 把字节每秒转换为速率文本。
 *
 * @param bytesPerSecond 每秒字节数。
 *
 * @return 带“/s”后缀的易读速率。
 */
private fun formatSpeed(bytesPerSecond: Long): String {
    return "${formatBytes(bytesPerSecond)}/s"
}

/** 宽度达到该值时每页显示三个首页功能，普通手机保持每页两个。 */
private val THREE_ITEM_PAGE_MIN_WIDTH = 600.dp

/** 首页功能卡片区域固定高度，保证每一页切换时纵向布局不跳动。 */
private val HOME_FEATURE_PAGE_HEIGHT = 166.dp

/** 首页快捷搜索允许的最大字符数，防止误粘贴超长内容导致页面状态异常。 */
private const val MAX_QUICK_SEARCH_LENGTH = 80
private const val QUICK_SEARCH_HIDE_DISTANCE_PX = 52
private const val QUICK_SEARCH_SHOW_DISTANCE_PX = 72
private const val QUICK_SEARCH_ENTER_MILLIS = 180
private const val QUICK_SEARCH_EXIT_MILLIS = 140
