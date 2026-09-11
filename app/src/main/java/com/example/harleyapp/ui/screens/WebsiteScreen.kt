package com.example.harleyapp.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import com.example.harleyapp.data.WebsiteToolRepository
import com.example.harleyapp.model.EbookWebDownloadRequest
import com.example.harleyapp.model.MAX_WEBSITE_BROWSER_TAB_COUNT
import com.example.harleyapp.model.WebsiteBrowserSession
import com.example.harleyapp.model.WebsiteBrowserTab
import com.example.harleyapp.model.WebsiteBookmarkSaveResult
import com.example.harleyapp.model.WEBSITE_MEDIA_SEEK_STEP_SECONDS
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.WebsiteToolSettings
import com.example.harleyapp.model.normalizeWebsiteBrowserUrl
import com.example.harleyapp.web.WebsiteScriptController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 在应用内部显示常驻的多标签网站浏览器。
 *
 * 使用方法：
 * 由HarleyApp根层始终保留本组件，只通过[isVisible]控制是否展示。每个本次会话中实际访问过的
 * 标签都会保留独立WebView；切换到底部“首页”或“我的”时仅暂停并卸载视图，不销毁网页对象。
 * 用户点击标签关闭按钮后，上层删除对应模型，相关WebView才会真正释放。
 *
 * @param session 当前完整标签会话，包含标签顺序和活动标签标识。
 * @param websites 新标签页中可供用户直接打开的全部网站收藏。
 * @param isVisible 当前是否正在显示底部“网站”页面；false时暂停全部网页媒体与交互。
 * @param isFullscreen 活动网页是否处于HTML5自定义全屏，用于暂时隐藏标签栏。
 * @param onSelectTab 用户点击已有标签后的切换回调。
 * @param onCloseTab 用户明确点击关闭按钮后的回调。
 * @param onCreateBlankTab 用户点击固定“+”按钮后的新增空白标签回调。
 * @param onOpenWebsiteInTab 用户在空白标签页选择网站后，用该网站填充当前标签的回调。
 * @param onOpenChildTab 网页通过target=_blank或window.open请求新标签时的回调。
 * @param onTabPageChanged 网页标题或实际地址变化后更新标签快照的回调。
 * @param onExitWebsite 网页没有可后退历史时，把系统返回交回App一级导航的回调。
 * @param onManageWebsites 打开网站收藏管理页面的回调。
 * @param onFullscreenChanged 自定义视频全屏状态回调，用于让宿主隐藏底部导航。
 * @param onEbookDownloadRequested 网页触发下载时，把公开直链交给电子书仓库的回调。
 * @param onBookmarkCurrentPage 把WebView当前实际标题和地址收藏进网站库的回调。
 * @param modifier 外部传入的页面尺寸与系统安全边距。
 *
 * @return 无返回值，直接输出标签栏、新标签页或当前网页。
 */
@Composable
fun WebsiteScreen(
    session: WebsiteBrowserSession,
    websites: List<WebsiteShortcut>,
    isVisible: Boolean,
    isFullscreen: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateBlankTab: () -> Unit,
    onOpenWebsiteInTab: (String, WebsiteShortcut) -> Unit,
    onOpenChildTab: (WebsiteBrowserTab, String) -> Unit,
    onTabPageChanged: (String, String, String) -> Unit,
    onExitWebsite: () -> Unit,
    onManageWebsites: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onEbookDownloadRequested: (EbookWebDownloadRequest) -> Unit,
    onBookmarkCurrentPage: (String, String) -> WebsiteBookmarkSaveResult,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isHostResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    val activeTabId = session.activeTabId
        ?.takeIf { selectedId -> session.tabs.any { tab -> tab.id == selectedId } }
        ?: session.tabs.firstOrNull()?.id
    val loadedTabIds = remember { mutableStateListOf<String>() }
    var showTabOverview by remember { mutableStateOf(false) }

    // 一级页面仍可能在Activity退到后台时留在组合中，因此额外跟踪宿主生命周期。只有“网站”栏目
    // 真正可见且Activity已经恢复交互时才唤醒活动WebView，避免按Home键后网页媒体继续播放。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isHostResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isHostResumed = lifecycleOwner.lifecycle.currentState
            .isAtLeast(Lifecycle.State.RESUMED)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isBrowserActive = isVisible && isHostResumed

    // 离开“网站”、进入网页全屏或App退到后台时主动收起总览，避免回到页面后恢复一个过期抽屉。
    LaunchedEffect(isVisible, isFullscreen, isHostResumed) {
        if (!isVisible || isFullscreen || !isHostResumed) {
            showTabOverview = false
        }
    }

    // 只为本次会话中真正显示过的标签创建WebView。冷启动恢复八个标签时不会一次创建八个渲染器，
    // 但用户切换离开的标签仍留在列表中，因此返回时可以继续原页面、历史、滚动和表单状态。
    SideEffect {
        if (isBrowserActive) {
            activeTabId?.let { selectedId ->
                if (selectedId !in loadedTabIds) {
                    loadedTabIds += selectedId
                }
            }
        }
        loadedTabIds.retainAll(session.tabs.map(WebsiteBrowserTab::id).toSet())
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showTabOverview) {
                        // 抽屉打开时从无障碍树隐藏被遮挡的网页，防止读屏焦点越过模态面板。
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                )
        ) {
            if (isVisible && !isFullscreen) {
                WebsiteBrowserTabStrip(
                    tabs = session.tabs,
                    activeTabId = activeTabId,
                    onSelectTab = onSelectTab,
                    onCloseTab = onCloseTab,
                    onCreateBlankTab = onCreateBlankTab,
                    onShowTabOverview = {
                        showTabOverview = true
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val activeTab = session.tabs.firstOrNull { tab -> tab.id == activeTabId }
                if (isBrowserActive && activeTab != null && activeTab.url == null) {
                    WebsiteNewTabPage(
                        modifier = Modifier.fillMaxSize(),
                        websites = websites,
                        onOpenWebsite = { website ->
                            onOpenWebsiteInTab(activeTab.id, website)
                        },
                        onManageWebsites = onManageWebsites
                    )
                }

                session.tabs
                    .filter { tab ->
                        tab.url != null && (
                            tab.id in loadedTabIds || (isBrowserActive && tab.id == activeTabId)
                            )
                    }
                    .forEach { tab ->
                        key(tab.id) {
                            WebsiteTabSession(
                                modifier = Modifier.fillMaxSize(),
                                tab = tab,
                                isActive = isBrowserActive && tab.id == activeTabId,
                                onOpenChildTab = { url -> onOpenChildTab(tab, url) },
                                onTabPageChanged = { title, url ->
                                    onTabPageChanged(tab.id, title, url)
                                },
                                onExitWebsite = onExitWebsite,
                                onFullscreenChanged = onFullscreenChanged,
                                onEbookDownloadRequested = onEbookDownloadRequested,
                                onBookmarkCurrentPage = onBookmarkCurrentPage
                            )
                        }
                    }
            }
        }

        // 进入HTML5全屏或离开网站时直接移除抽屉层；普通关闭仍由组件完整播放滑回动画。
        if (isVisible && !isFullscreen) {
            WebsiteTabOverviewDrawer(
                expanded = showTabOverview,
                tabs = session.tabs,
                activeTabId = activeTabId,
                onDismiss = {
                    showTabOverview = false
                },
                onSelectTab = { tabId ->
                    showTabOverview = false
                    onSelectTab(tabId)
                },
                onCloseTab = { tabId ->
                    if (session.tabs.size == 1) {
                        showTabOverview = false
                    }
                    onCloseTab(tabId)
                }
            )
        }
    }
}

/**
 * 持有一个标签独立的WebView、网页工具状态和全屏状态。
 *
 * 使用方法：
 * [WebsiteScreen]会为本次会话中访问过的每个网页标签保留该组件。只有[isActive]为true时才把
 * WebView重新挂到AndroidView并显示工具栏；false时组件仍在组合中，WebView只暂停、不销毁。
 *
 * @param tab 当前标签的稳定快照，id用于隔离同一网站打开的多个实例。
 * @param isActive 当前标签是否同时被选中且网站一级页面可见。
 * @param onOpenChildTab 网页请求打开新窗口时传回安全HTTP或HTTPS地址。
 * @param onTabPageChanged 页面标题或实际地址变化后的回调。
 * @param onExitWebsite 当前网页已经位于历史首项时返回App一级导航的回调。
 * @param onFullscreenChanged 自定义视频全屏状态变化回调。
 * @param onEbookDownloadRequested 网页下载电子书的请求回调。
 * @param onBookmarkCurrentPage 收藏当前实际页面的回调。
 * @param modifier 当前标签内容的可用尺寸。
 *
 * @return 无返回值；活动时输出网页浏览内容，后台时仅保留运行状态。
 */
@Composable
private fun WebsiteTabSession(
    tab: WebsiteBrowserTab,
    isActive: Boolean,
    onOpenChildTab: (String) -> Unit,
    onTabPageChanged: (String, String) -> Unit,
    onExitWebsite: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onEbookDownloadRequested: (EbookWebDownloadRequest) -> Unit,
    onBookmarkCurrentPage: (String, String) -> WebsiteBookmarkSaveResult,
    modifier: Modifier = Modifier
) {
    val initialUrl = tab.url ?: return
    val settingsKey = tab.websiteId ?: tab.id

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val websiteToolRepository = remember(context) {
        WebsiteToolRepository(context.applicationContext)
    }
    val scriptController = remember {
        WebsiteScriptController()
    }
    var toolSettings by remember(tab.id) {
        mutableStateOf(websiteToolRepository.getSettings(settingsKey))
    }
    var showToolbox by remember(tab.id) {
        mutableStateOf(false)
    }
    var scriptToolsManuallyOpen by remember(tab.id) {
        mutableStateOf(false)
    }
    var suppressAutomaticScriptTools by remember(tab.id) {
        mutableStateOf(false)
    }
    var playingVideoCount by remember(tab.id) {
        mutableIntStateOf(0)
    }
    var toolMessage by remember(tab.id) {
        mutableStateOf<String?>(null)
    }
    var pageGeneration by remember(tab.id) {
        mutableIntStateOf(0)
    }
    var findQuery by remember(tab.id) {
        mutableStateOf("")
    }
    var findResultText by remember(tab.id) {
        mutableStateOf("")
    }
    var fullscreenContent by remember(tab.id) {
        mutableStateOf<FullscreenWebContent?>(null)
    }
    var fullscreenControlsVisible by remember(tab.id) {
        mutableStateOf(false)
    }
    var fullscreenControlsLocked by remember(tab.id) {
        mutableStateOf(false)
    }
    var fullscreenControlRevision by remember(tab.id) {
        mutableIntStateOf(0)
    }
    var showFullscreenRatePicker by remember(tab.id) {
        mutableStateOf(false)
    }
    var fullscreenBallOffsetY by remember(tab.id) {
        mutableFloatStateOf(0f)
    }
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnEbookDownloadRequested by rememberUpdatedState(onEbookDownloadRequested)
    val currentOnBookmarkCurrentPage by rememberUpdatedState(onBookmarkCurrentPage)
    val currentOnOpenChildTab by rememberUpdatedState(onOpenChildTab)
    val currentOnTabPageChanged by rememberUpdatedState(onTabPageChanged)
    val currentOnExitWebsite by rememberUpdatedState(onExitWebsite)
    val currentIsActive by rememberUpdatedState(isActive)
    var loadingProgress by remember {
        mutableIntStateOf(0)
    }
    var errorText by remember {
        mutableStateOf<String?>(null)
    }
    var canGoBack by remember {
        mutableStateOf(false)
    }
    var currentPageTitle by remember(tab.id) {
        mutableStateOf(tab.title)
    }
    var currentPageUrl by remember(tab.id) {
        mutableStateOf(initialUrl)
    }
    val showInlineScriptTools = shouldShowInlineWebsiteScriptTools(
        manuallyOpen = scriptToolsManuallyOpen,
        playingVideoCount = playingVideoCount,
        suppressAutomaticOpen = suppressAutomaticScriptTools
    )

    /** 显示全屏控制层并重新开始自动隐藏倒计时。 */
    fun revealFullscreenControls() {
        fullscreenControlsVisible = true
        fullscreenControlRevision++
    }

    /** 关闭当前全屏View并只回调网站一次。 */
    fun exitFullscreen() {
        val content = fullscreenContent ?: return
        fullscreenContent = null
        fullscreenControlsLocked = false
        fullscreenControlsVisible = false
        showFullscreenRatePicker = false
        fullscreenBallOffsetY = 0f
        content.close()
    }

    val webView = remember(context, tab.id) {
        createWebsiteWebView(
            context = context,
            initialUrl = initialUrl,
            initialSettings = toolSettings,
            onProgressChanged = { progress ->
                loadingProgress = progress
            },
            onNavigationStateChanged = { canNavigateBack ->
                canGoBack = canNavigateBack
            },
            onError = { message ->
                errorText = message
            },
            onPageStarted = { pageUrl ->
                if (isSupportedWebsitePageUrl(pageUrl)) {
                    currentPageUrl = pageUrl
                    currentOnTabPageChanged(currentPageTitle, pageUrl)
                }
            },
            onPageFinished = { pageTitle, pageUrl ->
                currentPageTitle = pageTitle.ifBlank { currentPageTitle }
                if (isSupportedWebsitePageUrl(pageUrl)) {
                    currentPageUrl = pageUrl
                }
                playingVideoCount = 0
                suppressAutomaticScriptTools = false
                pageGeneration++
                currentOnTabPageChanged(currentPageTitle, currentPageUrl)
            },
            onShowFullscreen = { view, callback ->
                if (currentIsActive) {
                    fullscreenContent?.close()
                    fullscreenContent = FullscreenWebContent(view, callback)
                    fullscreenControlsLocked = false
                    fullscreenControlsVisible = false
                    showFullscreenRatePicker = false
                    fullscreenBallOffsetY = 0f
                    fullscreenControlRevision++
                } else {
                    callback.onCustomViewHidden()
                }
            },
            onHideFullscreen = {
                exitFullscreen()
            },
            onFindResult = { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                if (isDoneCounting) {
                    findResultText = if (numberOfMatches <= 0) {
                        "当前网页没有匹配内容"
                    } else {
                        "第${activeMatchOrdinal + 1}项，共${numberOfMatches}项"
                    }
                }
            },
            onDownloadRequested = { url, contentDisposition, mimeType ->
                if (currentIsActive) {
                    currentOnEbookDownloadRequested(
                        EbookWebDownloadRequest(
                            url = url,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType
                        )
                    )
                }
            },
            onOpenNewTabRequested = { url ->
                if (currentIsActive) {
                    currentOnOpenChildTab(url)
                }
            }
        )
    }

    /**
     * 保存并立即应用当前网站的工具设置。
     *
     * @param updatedSettings 用户刚修改的完整设置。
     * @return 写入Room及兼容副本成功返回true，失败时保留旧设置并显示提示。
     */
    fun updateToolSettings(updatedSettings: WebsiteToolSettings): Boolean {
        val normalizedSettings = updatedSettings.normalized()
        val desktopModeChanged =
            normalizedSettings.desktopModeEnabled != toolSettings.desktopModeEnabled
        if (!websiteToolRepository.saveSettings(settingsKey, normalizedSettings)) {
            toolMessage = "网页工具设置保存失败，请重试"
            return false
        }

        toolSettings = normalizedSettings
        if (desktopModeChanged) {
            applyDesktopUserAgent(
                context = context,
                webView = webView,
                enabled = normalizedSettings.desktopModeEnabled
            )
            webView.reload()
            toolMessage = "已切换网页模式并重新加载"
        }
        return true
    }

    /**
     * 一键收藏WebView当前实际页面并显示具体结果。
     *
     * 使用方法：
     * 普通网页脚本栏或全屏悬浮球的“收藏”按钮调用。函数读取页面跳转后的标题和地址，而不是最初
     * WebsiteShortcut中的入口地址，因此搜索结果页、文章页等二级页面也能准确保存。
     *
     * @return 无返回值；保存结果通过[toolMessage]在当前网页上方短暂显示。
     */
    fun bookmarkCurrentPage() {
        val actualTitle = webView.title.orEmpty().ifBlank { currentPageTitle }
        val actualUrl = webView.url.orEmpty().ifBlank { currentPageUrl }
        toolMessage = websiteBookmarkSaveMessage(
            currentOnBookmarkCurrentPage(actualTitle, actualUrl)
        )
    }

    // 全屏优先于网页历史；用户按一次返回即可恢复工具栏和系统栏。
    BackHandler(
        enabled = isActive && (
            fullscreenContent != null ||
                canGoBack ||
                webView.canGoBack()
            )
    ) {
        if (fullscreenContent != null && fullscreenControlsLocked) {
            fullscreenControlsLocked = false
            toolMessage = "屏幕锁已解除"
            revealFullscreenControls()
        } else if (fullscreenContent != null) {
            exitFullscreen()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // 历史状态回调与Chromium真实栈之间即使出现一个帧的时间差，也不能吞掉用户返回操作。
            canGoBack = false
            currentOnExitWebsite()
        }
    }

    // 通知外层Scaffold隐藏底部导航；页面销毁时无条件恢复，避免其他一级页面丢失导航栏。
    LaunchedEffect(fullscreenContent != null, isActive) {
        currentOnFullscreenChanged(isActive && fullscreenContent != null)
    }

    // 切走标签或离开“网站”时退出可能仍在处理中的全屏，并暂停标准HTML5媒体。WebView对象、
    // DOM、历史、滚动和表单仍保留在内存中，重新选中时由onResume继续使用同一实例。
    DisposableEffect(webView, isActive) {
        if (isActive) {
            webView.onResume()
        } else {
            exitFullscreen()
            pauseWebsiteMedia(webView)
            webView.keepScreenOn = false
            webView.onPause()
            playingVideoCount = 0
        }

        // 标签切换会立即建立一个isActive=false的新Effect并执行暂停；组件整体销毁时则由下方唯一的
        // WebView清理入口按固定顺序完成暂停和destroy，避免两个并列Effect争抢已销毁对象。
        onDispose { }
    }

    // 悬浮球展开的工具面板停止操作后自动收起；悬浮球本身始终保留且不覆盖底部播放器进度条。
    LaunchedEffect(
        fullscreenContent,
        fullscreenControlsVisible,
        fullscreenControlRevision,
        showFullscreenRatePicker
    ) {
        if (fullscreenContent != null &&
            fullscreenControlsVisible &&
            !showFullscreenRatePicker
        ) {
            delay(FULLSCREEN_CONTROLS_HIDE_DELAY_MILLIS)
            fullscreenControlsVisible = false
        }
    }
    // 页面加载完成或设置变化后重应用脚本；同时更新不依赖JavaScript的原生文字缩放和常亮状态。
    LaunchedEffect(webView, pageGeneration, toolSettings, isActive) {
        if (isActive) {
            webView.settings.textZoom = toolSettings.textZoomPercent
            webView.keepScreenOn = toolSettings.keepScreenOn
            scriptController.applySettings(webView, toolSettings)
        }
    }

    // 页面可见时低频检查视频是否真正播放；只在播放后自动展开脚本栏，不因网页仅包含video标签就占空间。
    LaunchedEffect(webView, pageGeneration, fullscreenContent, isActive) {
        if (!isActive) return@LaunchedEffect

        while (isActive) {
            if (fullscreenContent == null) {
                scriptController.queryPlayingVideoCount(webView) { count ->
                    playingVideoCount = count
                }
            }
            delay(PLAYING_VIDEO_CHECK_INTERVAL_MILLIS)
        }
    }

    // 一段视频播放结束后解除本轮手动隐藏，下一次开始播放仍可自动给出脚本入口。
    LaunchedEffect(playingVideoCount) {
        if (playingVideoCount <= 0) {
            suppressAutomaticScriptTools = false
        }
    }

    // 操作结果只短暂显示，避免占用网页可视区域。
    LaunchedEffect(toolMessage) {
        if (toolMessage != null) {
            delay(TOOL_MESSAGE_DURATION_MILLIS)
            toolMessage = null
        }
    }

    // 网站进入自定义全屏后隐藏系统栏并允许设备自由旋转；退出时恢复Activity原状态。
    DisposableEffect(fullscreenContent, activity, isActive) {
        val content = fullscreenContent
        if (!isActive || content == null || activity == null) {
            onDispose { }
        } else {
            val previousOrientation = activity.requestedOrientation
            val insetsController = WindowCompat.getInsetsController(
                activity.window,
                activity.window.decorView
            )
            content.view.keepScreenOn = true
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

            onDispose {
                content.view.keepScreenOn = false
                content.close()
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    // 只有用户关闭该标签或Activity真正销毁、使标签会话离开组合时才释放WebView。普通一级导航
    // 和标签切换只会触发上面的暂停Effect，因此不会丢失网页内存状态。
    DisposableEffect(webView) {
        onDispose {
            if (currentIsActive) {
                currentOnFullscreenChanged(false)
            }
            webView.keepScreenOn = false
            webView.onPause()
            webView.stopLoading()
            webView.clearMatches()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    if (!isActive) {
        return
    }

    fullscreenContent?.let { content ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    (content.view.parent as? ViewGroup)?.removeView(content.view)
                    content.view
                }
            )

            // 全屏夜间模式使用原生半透明层，保证Chromium自定义视频View也能降低亮度。
            if (toolSettings.nightModeEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = toolSettings.nightOverlayAlpha))
                )
            }

            toolMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    color = Color.Black.copy(alpha = 0.46f),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 只有锁屏和倍速选择器这种明确的模态状态才拦截整屏；普通收起状态完全让出播放器触摸。
            if (fullscreenControlsLocked || showFullscreenRatePicker) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            fullscreenControlsLocked,
                            showFullscreenRatePicker
                        ) {
                            detectTapGestures {
                                if (showFullscreenRatePicker) {
                                    showFullscreenRatePicker = false
                                }
                                revealFullscreenControls()
                            }
                        }
                )
            }

            val maximumBallOffsetPixels = with(LocalDensity.current) {
                FULLSCREEN_FLOATING_BALL_MAX_VERTICAL_OFFSET.toPx()
            }
            val ballDragState = rememberDraggableState { delta ->
                fullscreenBallOffsetY = calculateFullscreenBallOffset(
                    currentOffset = fullscreenBallOffsetY,
                    dragDelta = delta,
                    maximumOffset = maximumBallOffsetPixels
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .offset { IntOffset(x = 0, y = fullscreenBallOffsetY.roundToInt()) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (fullscreenControlsVisible) {
                    Surface(
                        modifier = Modifier
                            .weight(weight = 1f, fill = false)
                            .widthIn(max = FULLSCREEN_FLOATING_PANEL_MAX_WIDTH),
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.extraLarge,
                        shadowElevation = 8.dp
                    ) {
                        if (fullscreenControlsLocked) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "屏幕已锁定",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                FullscreenToolButton(
                                    text = "解除锁定",
                                    active = true,
                                    onClick = {
                                        fullscreenControlsLocked = false
                                        toolMessage = "屏幕锁已解除"
                                        revealFullscreenControls()
                                    }
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FullscreenToolButton(
                                    text = "${formatToolbarRate(toolSettings.playbackRate)} ▾",
                                    active = toolSettings.playbackRate != 1f,
                                    onClick = {
                                        showFullscreenRatePicker = true
                                        revealFullscreenControls()
                                    }
                                )
                                FullscreenToolButton(
                                    text = "播放/暂停",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.togglePlayback(webView) { count ->
                                            toolMessage = mediaActionMessage(count, "已切换播放状态")
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = "-${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.seekBy(
                                            webView,
                                            -WEBSITE_MEDIA_SEEK_STEP_SECONDS
                                        ) { count ->
                                            toolMessage = mediaActionMessage(
                                                count,
                                                "已快退${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒"
                                            )
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = "+${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒",
                                    onClick = {
                                        revealFullscreenControls()
                                        scriptController.seekBy(
                                            webView,
                                            WEBSITE_MEDIA_SEEK_STEP_SECONDS
                                        ) { count ->
                                            toolMessage = mediaActionMessage(
                                                count,
                                                "已快进${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒"
                                            )
                                        }
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.videoMuted) "静音开" else "静音",
                                    active = toolSettings.videoMuted,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(videoMuted = !toolSettings.videoMuted)
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.videoLoopEnabled) "循环开" else "循环",
                                    active = toolSettings.videoLoopEnabled,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(
                                                videoLoopEnabled = !toolSettings.videoLoopEnabled
                                            )
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = if (toolSettings.nightModeEnabled) "夜间开" else "夜间",
                                    active = toolSettings.nightModeEnabled,
                                    onClick = {
                                        revealFullscreenControls()
                                        updateToolSettings(
                                            toolSettings.copy(
                                                nightModeEnabled = !toolSettings.nightModeEnabled
                                            )
                                        )
                                    }
                                )
                                FullscreenToolButton(
                                    text = "收藏",
                                    onClick = {
                                        bookmarkCurrentPage()
                                        revealFullscreenControls()
                                    }
                                )
                                FullscreenToolButton(
                                    text = "锁屏",
                                    onClick = {
                                        fullscreenControlsLocked = true
                                        showFullscreenRatePicker = false
                                        toolMessage = "屏幕已锁定，点击悬浮球可管理"
                                        revealFullscreenControls()
                                    }
                                )
                                FullscreenToolButton(
                                    text = "退出全屏",
                                    onClick = ::exitFullscreen
                                )
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(FULLSCREEN_FLOATING_BALL_SIZE)
                        .draggable(
                            state = ballDragState,
                            orientation = Orientation.Vertical
                        )
                        .clickable {
                            fullscreenControlsVisible = !fullscreenControlsVisible
                            showFullscreenRatePicker = false
                            fullscreenControlRevision++
                        },
                    color = if (fullscreenControlsVisible) {
                        FULLSCREEN_ACTIVE_COLOR.copy(alpha = 0.92f)
                    } else {
                        Color.Black.copy(alpha = 0.68f)
                    },
                    shape = CircleShape,
                    shadowElevation = 10.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (fullscreenControlsVisible) "收起" else "脚本",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (showFullscreenRatePicker && !fullscreenControlsLocked) {
                FullscreenPlaybackRatePicker(
                    modifier = Modifier.align(Alignment.Center),
                    selectedRate = toolSettings.playbackRate,
                    onRateSelected = { selectedRate ->
                        updateToolSettings(toolSettings.copy(playbackRate = selectedRate))
                        showFullscreenRatePicker = false
                        toolMessage = "播放速度已切换为 ${formatToolbarRate(selectedRate)}"
                        revealFullscreenControls()
                    },
                    onDismiss = {
                        showFullscreenRatePicker = false
                        revealFullscreenControls()
                    }
                )
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(
                    enabled = canGoBack,
                    onClick = {
                        webView.goBack()
                    }
                ) {
                    Text(text = "← 返回")
                }

                Text(
                    modifier = Modifier.weight(1f),
                    text = currentPageTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                TextButton(
                    onClick = {
                        if (showInlineScriptTools) {
                            scriptToolsManuallyOpen = false
                            suppressAutomaticScriptTools = playingVideoCount > 0
                        } else {
                            scriptToolsManuallyOpen = true
                            suppressAutomaticScriptTools = false
                        }
                    }
                ) {
                    Text(
                        text = when {
                            showInlineScriptTools -> "收起"
                            playingVideoCount > 0 -> "脚本 •"
                            else -> "脚本"
                        }
                    )
                }

                TextButton(
                    onClick = {
                        errorText = null
                        webView.reload()
                    }
                ) {
                    Text(text = "刷新")
                }

                TextButton(
                    onClick = {
                        openExternalUrl(context, currentPageUrl)
                    }
                ) {
                    Text(text = "外部")
                }
            }
        }

        if (loadingProgress in 0..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(visible = showInlineScriptTools) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showToolbox = true }) {
                        Text(text = "网页工具")
                    }
                    TextButton(onClick = { showToolbox = true }) {
                        Text(text = formatToolbarRate(toolSettings.playbackRate))
                    }
                    TextButton(onClick = ::bookmarkCurrentPage) {
                        Text(text = "收藏当前页")
                    }
                    TextButton(
                        onClick = {
                            scriptController.togglePlayback(webView) { count ->
                                toolMessage = mediaActionMessage(count, "已切换播放状态")
                            }
                        }
                    ) {
                        Text(text = "播放/暂停")
                    }
                    TextButton(
                        onClick = {
                            scriptController.seekBy(
                                webView,
                                -WEBSITE_MEDIA_SEEK_STEP_SECONDS
                            ) { count ->
                                toolMessage = mediaActionMessage(
                                    count,
                                    "已快退${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒"
                                )
                            }
                        }
                    ) {
                        Text(text = "-${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒")
                    }
                    TextButton(
                        onClick = {
                            scriptController.seekBy(
                                webView,
                                WEBSITE_MEDIA_SEEK_STEP_SECONDS
                            ) { count ->
                                toolMessage = mediaActionMessage(
                                    count,
                                    "已快进${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒"
                                )
                            }
                        }
                    ) {
                        Text(text = "+${WEBSITE_MEDIA_SEEK_STEP_SECONDS}秒")
                    }
                    TextButton(
                        onClick = {
                            scriptController.requestFullscreen(webView) { count ->
                                toolMessage = mediaActionMessage(count, "已向播放器请求全屏")
                            }
                        }
                    ) {
                        Text(text = "全屏")
                    }
                }
            }
        }

        toolMessage?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                }
            )

            errorText?.let { message ->
                WebsiteErrorView(
                    message = message,
                    onRetry = {
                        errorText = null
                        webView.loadUrl(currentPageUrl)
                    },
                    onOpenBrowser = {
                        openExternalUrl(context, currentPageUrl)
                    }
                )
            }

            if (loadingProgress == 0 && errorText == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    if (showToolbox) {
        WebsiteToolboxDialog(
            settings = toolSettings,
            findQuery = findQuery,
            findResultText = findResultText,
            onSettingsChanged = { updatedSettings ->
                updateToolSettings(updatedSettings)
            },
            onFindQueryChanged = { query ->
                findQuery = query
                if (query.isBlank()) {
                    findResultText = ""
                    webView.clearMatches()
                } else {
                    findResultText = "正在查找…"
                    webView.findAllAsync(query)
                }
            },
            onFindPrevious = {
                webView.findNext(false)
            },
            onFindNext = {
                webView.findNext(true)
            },
            onTogglePlayback = {
                scriptController.togglePlayback(webView) { count ->
                    toolMessage = mediaActionMessage(count, "已切换播放状态")
                }
            },
            onSeekBy = { seconds ->
                scriptController.seekBy(webView, seconds) { count ->
                    toolMessage = mediaActionMessage(
                        count,
                        if (seconds < 0) "已快退${-seconds}秒" else "已快进${seconds}秒"
                    )
                }
            },
            onRequestFullscreen = {
                showToolbox = false
                scriptController.requestFullscreen(webView) { count ->
                    toolMessage = mediaActionMessage(count, "已向播放器请求全屏")
                }
            },
            onDismiss = {
                showToolbox = false
            }
        )
    }
}

/**
 * 显示类似手机浏览器的横向标签栏，并把标签总览与新增按钮固定在右侧。
 *
 * 使用方法：
 * [WebsiteScreen]在普通网页模式下调用。标签过多时中间区域可横向滚动，活动标签变化后会自动
 * 滚动到可见位置；用户也可以点击“☰”打开从右侧滑入的纵向总览抽屉，快速切换或连续关闭标签。
 * “+”按钮始终留在屏幕右侧，达到上限后仍把点击交给上层显示明确提示。
 *
 * @param tabs 从左到右排列的完整标签列表。
 * @param activeTabId 当前活动标签标识。
 * @param onSelectTab 点击标签正文后的切换回调。
 * @param onCloseTab 点击标签关闭区域后的关闭回调。
 * @param onCreateBlankTab 点击固定新增按钮后的回调。
 * @param onShowTabOverview 点击固定“☰”按钮后打开右侧标签总览的回调。
 *
 * @return 无返回值，直接输出可滚动标签条。
 */
@Composable
private fun WebsiteBrowserTabStrip(
    tabs: List<WebsiteBrowserTab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCreateBlankTab: () -> Unit,
    onShowTabOverview: () -> Unit
) {
    val listState = rememberLazyListState()
    val tabIds = tabs.map(WebsiteBrowserTab::id)

    LaunchedEffect(activeTabId, tabIds) {
        val selectedIndex = tabs.indexOfFirst { tab -> tab.id == activeTabId }
        if (selectedIndex >= 0) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = tabs,
                    key = WebsiteBrowserTab::id
                ) { tab ->
                    val selected = tab.id == activeTabId
                    val displayTitle = tab.title.ifBlank { "新标签页" }
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 142.dp, max = 234.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shape = RoundedCornerShape(13.dp),
                        tonalElevation = if (selected) 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription = "切换到标签页：$displayTitle"
                                        role = Role.Button
                                    }
                                    .clickable { onSelectTab(tab.id) }
                                    .padding(start = 12.dp, top = 14.dp, bottom = 12.dp),
                                text = displayTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics {
                                        contentDescription = "关闭标签页：$displayTitle"
                                        role = Role.Button
                                    }
                                    .clickable { onCloseTab(tab.id) }
                                    .padding(horizontal = 15.dp, vertical = 10.dp),
                                text = "×",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            Text(
                text = "${tabs.size}/$MAX_WEBSITE_BROWSER_TAB_COUNT",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            TextButton(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "查看全部标签页，共${tabs.size}个"
                    },
                onClick = onShowTabOverview
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = "新建标签页"
                },
                onClick = onCreateBlankTab
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 从屏幕右侧滑入全部标签的纵向快捷抽屉。
 *
 * 使用方法：
 * [WebsiteScreen]把本函数放在网页内容之后，使遮罩与抽屉绘制在WebView上方。点击“☰”后将
 * [expanded]设为true，抽屉从右侧滑入；点击左侧保留的半透明空白区域、标题栏关闭按钮或按系统
 * 返回键时调用[onDismiss]，抽屉再滑回屏幕右侧。标签列表使用LazyColumn，标签数量增加后仍可
 * 纵向滚动，并会在每次打开时把当前标签准确滚动到可见位置。
 *
 * @param expanded 是否显示标签总览抽屉。
 * @param tabs 按标签栏顺序排列的全部标签快照。
 * @param activeTabId 当前活动标签标识；为空时不高亮任何标签。
 * @param onDismiss 点击左侧空白区域、关闭按钮或系统返回键时的收起回调。
 * @param onSelectTab 点击标签正文时的切换回调；调用方应同时收起抽屉。
 * @param onCloseTab 点击单项关闭按钮时的回调；除最后一个标签外，抽屉可保持打开以便连续整理。
 *
 * @return 无返回值，直接在网站页面上层输出带遮罩、进出场动画和纵向列表的右侧抽屉。
 */
@Composable
private fun WebsiteTabOverviewDrawer(
    expanded: Boolean,
    tabs: List<WebsiteBrowserTab>,
    activeTabId: String?,
    onDismiss: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val tabIds = tabs.map(WebsiteBrowserTab::id)
    val drawerBlockerInteractionSource = remember { MutableInteractionSource() }
    val drawerTransition = updateTransition(
        targetState = expanded,
        label = "WebsiteTabOverviewDrawer"
    )

    // 本抽屉在WebsiteTabSession之后进入组合，因此返回处理优先于网页历史和App一级导航。退出动画
    // 完成前currentState仍为true，可继续吞掉快速连按的第二次返回，避免网页或一级页面意外后退。
    BackHandler(enabled = drawerTransition.currentState || drawerTransition.targetState) {
        onDismiss()
    }

    // 使用LazyListState按真实列表项定位，不依赖固定行高，系统字体放大时当前标签仍能正确显示。
    LaunchedEffect(expanded, activeTabId, tabIds) {
        if (expanded) {
            val selectedIndex = tabs.indexOfFirst { tab -> tab.id == activeTabId }
            if (selectedIndex >= 0) {
                listState.scrollToItem(selectedIndex)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
    ) {
        // 手机上保留约18%的左侧区域用于点击关闭；大屏设备限制抽屉宽度，避免横向铺满。
        val drawerWidth = minOf(maxWidth * 0.82f, 360.dp)
        val dismissAreaWidth = maxWidth - drawerWidth

        drawerTransition.AnimatedVisibility(
            visible = { isOpen -> isOpen },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(dismissAreaWidth)
                .fillMaxHeight(),
            enter = fadeIn(
                animationSpec = tween(TAB_OVERVIEW_SCRIM_ANIMATION_MILLIS)
            ),
            exit = fadeOut(
                animationSpec = tween(TAB_OVERVIEW_SCRIM_ANIMATION_MILLIS)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .semantics {
                        contentDescription = "关闭标签页总览"
                        role = Role.Button
                    }
                    .clickable(onClick = onDismiss)
            )
        }

        // 面板滑动时，右侧目标区域先由无反馈的透明层接住触摸；稳定后面板绘制在它上方。这样既不会
        // 留出可穿透到WebView的动画空洞，也不会用父级手势识别器抢走标签项点击或纵向滚动。
        if (drawerTransition.currentState || drawerTransition.targetState) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = drawerBlockerInteractionSource,
                        indication = null,
                        onClick = { }
                    )
                    .clearAndSetSemantics { }
            )
        }

        drawerTransition.AnimatedVisibility(
            visible = { isOpen -> isOpen },
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(
                animationSpec = tween(TAB_OVERVIEW_DRAWER_ANIMATION_MILLIS),
                initialOffsetX = { fullWidth -> fullWidth }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(TAB_OVERVIEW_DRAWER_ANIMATION_MILLIS),
                targetOffsetX = { fullWidth -> fullWidth }
            )
        ) {
            Surface(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .semantics {
                        paneTitle = "标签页总览"
                    },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                            text = "全部标签页",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${tabs.size}/$MAX_WEBSITE_BROWSER_TAB_COUNT",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "关闭标签页总览"
                                    role = Role.Button
                                }
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 15.dp, vertical = 10.dp),
                            text = "×",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = tabs,
                            key = WebsiteBrowserTab::id
                        ) { tab ->
                            val isSelected = tab.id == activeTabId
                            val displayTitle = tab.title.ifBlank { "新标签页" }
                            val secondaryText = tab.url ?: "空白标签页"

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                },
                                contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.heightIn(min = 68.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 68.dp)
                                            .semantics {
                                                contentDescription = if (isSelected) {
                                                    "当前标签页：$displayTitle"
                                                } else {
                                                    "切换到标签页：$displayTitle"
                                                }
                                                role = Role.Button
                                                selected = isSelected
                                            }
                                            .clickable {
                                                onSelectTab(tab.id)
                                            }
                                            .padding(
                                                start = 14.dp,
                                                top = 10.dp,
                                                bottom = 10.dp
                                            ),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                modifier = Modifier.weight(1f),
                                                text = displayTitle,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) {
                                                    FontWeight.SemiBold
                                                } else {
                                                    FontWeight.Normal
                                                }
                                            )
                                            if (isSelected) {
                                                Text(
                                                    modifier = Modifier.padding(start = 8.dp),
                                                    text = "当前",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        Text(
                                            text = secondaryText,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer.copy(
                                                    alpha = 0.72f
                                                )
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .semantics {
                                                contentDescription = "关闭标签页：$displayTitle"
                                                role = Role.Button
                                            }
                                            .clickable {
                                                onCloseTab(tab.id)
                                            }
                                            .padding(horizontal = 15.dp, vertical = 10.dp),
                                        text = "×",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 判断普通网页模式下是否应该显示横向脚本工具栏。
 *
 * 使用方法：
 * WebsiteScreen每次视频播放状态或用户手动开关变化时调用。手动打开优先级最高；视频正在播放时
 * 自动打开，但用户本轮主动收起后保持隐藏，直到当前视频全部停止再允许下次自动展开。
 *
 * @param manuallyOpen 用户是否主动要求保持展开。
 * @param playingVideoCount 当前正在播放的视频数量。
 * @param suppressAutomaticOpen 是否抑制本轮播放的自动展开。
 *
 * @return 应显示脚本工具栏返回true，否则返回false。
 */
internal fun shouldShowInlineWebsiteScriptTools(
    manuallyOpen: Boolean,
    playingVideoCount: Int,
    suppressAutomaticOpen: Boolean
): Boolean {
    return manuallyOpen || (playingVideoCount > 0 && !suppressAutomaticOpen)
}

/**
 * 显示一个可直接选择已收藏网站的新标签页。
 *
 * 使用方法：
 * 用户点击“+”或关闭最后一个标签后，由[WebsiteScreen]为当前空白标签调用。选择列表中的网站会
 * 填充当前空白标签，而不是额外再创建一个标签；没有收藏时提供直接进入管理页面的入口。
 *
 * @param websites 当前网站收藏快照，保持用户在收藏管理中的顺序。
 * @param onOpenWebsite 用户选择一个网站后填充当前空白标签的回调。
 * @param onManageWebsites 打开网站收藏管理页面的回调。
 * @param modifier 外部传入的内容尺寸。
 *
 * @return 无返回值，直接输出新标签页内容。
 */
@Composable
private fun WebsiteNewTabPage(
    websites: List<WebsiteShortcut>,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onManageWebsites: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "新标签页",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (websites.isEmpty()) {
                    "还没有收藏网站，先添加一个常用入口吧。"
                } else {
                    "选择一个已收藏网站，在当前标签中打开"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            websites.forEach { website ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWebsite(website) },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = website.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = website.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (websites.isEmpty()) {
                Button(onClick = onManageWebsites) {
                    Text(text = "添加网站")
                }
            } else {
                TextButton(onClick = onManageWebsites) {
                    Text(text = "管理网站收藏")
                }
            }
        }
    }
}

/**
 * 暂停当前网页中可直接访问的标准HTML5音视频。
 *
 * 使用方法：
 * 标签切到后台或用户离开“网站”一级页面时，在[WebView.onPause]之前调用。函数只执行pause，
 * 不修改播放进度、页面地址、历史或DOM；跨域iframe和非标准播放器仍由WebView自身的onPause处理。
 *
 * @param webView 需要暂停媒体但继续保留页面状态的标签WebView。
 * @return 无返回值；网页没有标准媒体元素时安全地不执行任何操作。
 */
private fun pauseWebsiteMedia(webView: WebView) {
    webView.evaluateJavascript(
        """
        (function() {
          document.querySelectorAll('video, audio').forEach(function(media) {
            if (!media.paused) media.pause();
          });
        })();
        """.trimIndent(),
        null
    )
}

/**
 * 判断网页回调地址是否适合写入可跨启动恢复的标签快照。
 *
 * 使用方法：
 * WebView开始或结束主文档导航时调用。仅保存带主机名的HTTP或HTTPS地址；about:blank、
 * javascript、file等内部或高风险地址只在当前WebView中存在，不会覆盖最后一个可恢复网址。
 *
 * @param url WebView回调的候选页面地址。
 * @return 地址可安全持久化并用于下次冷启动恢复时返回true，否则返回false。
 */
private fun isSupportedWebsitePageUrl(url: String): Boolean {
    return normalizeWebsiteBrowserUrl(url) != null
}

/**
 * 创建并配置用于HTTP或HTTPS网页浏览的WebView。
 *
 * @param context 页面上下文，用于创建WebView和打开外部协议。
 * @param initialUrl 当前网站的完整HTTP或HTTPS网址。
 * @param initialSettings 当前网站首次创建WebView时应用的原生设置。
 * @param onProgressChanged 页面加载进度变化回调，范围为0到100。
 * @param onNavigationStateChanged 是否可以网页后退的状态回调。
 * @param onError 主页面加载失败时的中文错误提示回调；传null表示清除旧错误。
 * @param onPageStarted 主页面开始跳转后的实际地址回调，用于及时保存冷启动恢复位置。
 * @param onPageFinished 页面完成加载后的标题、网址和脚本重应用通知。
 * @param onShowFullscreen 网站请求显示自定义全屏View的回调。
 * @param onHideFullscreen 网站请求退出自定义全屏的回调。
 * @param onFindResult 页内查找位置和总数回调。
 * @param onDownloadRequested 网页下载公开直链、文件名响应头和MIME类型回调。
 * @param onOpenNewTabRequested 网页明确请求新窗口并解析出安全HTTP或HTTPS地址后的回调。
 *
 * @return 已完成安全设置，并会在首次获得有效布局尺寸后加载网站的WebView实例。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWebsiteWebView(
    context: Context,
    initialUrl: String,
    initialSettings: WebsiteToolSettings,
    onProgressChanged: (Int) -> Unit,
    onNavigationStateChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, String) -> Unit,
    onShowFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideFullscreen: () -> Unit,
    onFindResult: (Int, Int, Boolean) -> Unit,
    onDownloadRequested: (String, String, String) -> Unit,
    onOpenNewTabRequested: (String) -> Unit
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            // 只允许用户手势触发的新窗口进入onCreateWindow，阻止广告脚本在后台批量弹出标签。
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
        }
        settings.textZoom = initialSettings.textZoomPercent
        keepScreenOn = initialSettings.keepScreenOn
        applyDesktopUserAgent(
            context = context,
            webView = this,
            enabled = initialSettings.desktopModeEnabled
        )

        webViewClient = HarleyWebViewClient(
            context = context,
            onNavigationStateChangedCallback = onNavigationStateChanged,
            onPageStartedCallback = { startedUrl ->
                onError(null)
                onNavigationStateChanged(canGoBack())
                onPageStarted(startedUrl.orEmpty())
            },
            onPageFinishedCallback = { view, finishedUrl ->
                onNavigationStateChanged(canGoBack())
                onPageFinished(
                    view?.title.orEmpty(),
                    finishedUrl.orEmpty()
                )
            },
            onMainFrameErrorCallback = { message ->
                onError(message)
            }
        )
        webChromeClient = HarleyWebChromeClient(
            context = context,
            onProgressChangedCallback = onProgressChanged,
            onShowFullscreenCallback = onShowFullscreen,
            onHideFullscreenCallback = onHideFullscreen,
            onOpenNewTabRequested = onOpenNewTabRequested
        )
        setFindListener(onFindResult)
        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val safeUrl = url.orEmpty()
            if (safeUrl.isBlank()) {
                Log.e(TAG, "Website reported an empty download URL")
            } else {
                onDownloadRequested(
                    safeUrl,
                    contentDisposition.orEmpty(),
                    mimeType.orEmpty()
                )
            }
        }

        // 本WebView会先在remember中创建，再交给AndroidView挂载。必须等真实宽高就绪后再加载，
        // 否则页面中的100vh和百分比高度可能按0计算，脚本虽已生成内容但整个内容区仍会被压扁。
        loadUrlAfterFirstMeasuredLayout(initialUrl)
    }
}

/**
 * 等待WebView首次获得非零布局尺寸后加载入口网址。
 *
 * 使用方法：
 * WebView完成设置、WebViewClient和WebChromeClient配置后调用本函数。若View已经挂载并具有有效宽高，
 * 会立即加载；否则监听布局变化，在首次取得有效宽高时移除监听并加载一次。
 *
 * @param initialUrl 首次需要加载的完整HTTP或HTTPS网址。
 *
 * @return 无返回值；网址会在WebView具备有效视口后异步加载。
 */
private fun WebView.loadUrlAfterFirstMeasuredLayout(initialUrl: String) {
    if (isAttachedToWindow && width > 0 && height > 0) {
        loadUrl(initialUrl)
        return
    }

    addOnLayoutChangeListener(
        object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                val measuredWidth = right - left
                val measuredHeight = bottom - top
                if (!view.isAttachedToWindow || measuredWidth <= 0 || measuredHeight <= 0) {
                    return
                }

                view.removeOnLayoutChangeListener(this)
                (view as WebView).loadUrl(initialUrl)
            }
        }
    )
}

/**
 * 显示网页主文档加载失败后的可恢复提示。
 *
 * @param message 具体错误说明。
 * @param onRetry 重新加载网站回调。
 * @param onOpenBrowser 使用系统浏览器打开回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteErrorView(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "网页暂时无法打开",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                text = message.ifBlank { "请检查网络连接或稍后重试。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) {
                    Text(text = "重新加载")
                }
                TextButton(onClick = onOpenBrowser) {
                    Text(text = "使用浏览器")
                }
            }
        }
    }
}

/**
 * 使用系统默认浏览器或支持该链接的应用打开网址。
 *
 * @param context 用于启动Activity的上下文。
 * @param url 完整HTTP或HTTPS网址。
 *
 * @return 成功发送启动请求返回true；设备没有可用浏览器时返回false。
 */
private fun openExternalUrl(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.e(TAG, "No application can open the website", error)
        false
    }
}

/**
 * 处理WebView页面导航和主文档错误。
 *
 * @param context 用于打开非HTTP协议的上下文。
 * @param onNavigationStateChangedCallback Chromium历史项变化后的可后退状态回调。
 * @param onPageStartedCallback 主页面开始加载后的实际地址回调。
 * @param onPageFinishedCallback 主页面加载完成后的WebView与最终网址回调。
 * @param onMainFrameErrorCallback 主页面失败回调。
 */
private class HarleyWebViewClient(
    private val context: Context,
    private val onNavigationStateChangedCallback: (Boolean) -> Unit,
    private val onPageStartedCallback: (String?) -> Unit,
    private val onPageFinishedCallback: (WebView?, String?) -> Unit,
    private val onMainFrameErrorCallback: (String) -> Unit
) : WebViewClient() {

    /**
     * Chromium写入或切换历史项时立即刷新返回键状态。
     *
     * @param view 当前WebView。
     * @param url 当前历史项网址。
     * @param isReload 本次变化是否来自刷新。
     * @return 无返回值。
     */
    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        onNavigationStateChangedCallback(view?.canGoBack() == true)
    }

    /**
     * 主页面开始加载时清除旧错误。
     *
     * @param view 当前WebView。
     * @param url 正在加载的网址。
     * @param favicon 网站图标，可能为null。
     *
     * @return 无返回值。
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStartedCallback(url)
    }

    /**
     * 页面加载结束后更新网页是否可以后退。
     *
     * @param view 当前WebView。
     * @param url 已完成加载的网址。
     *
     * @return 无返回值。
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback(view, url)
    }

    /**
     * 仅处理主文档加载错误，忽略图片等子资源错误，避免整页被错误遮挡。
     *
     * @param view 当前WebView。
     * @param request 失败的资源请求。
     * @param error 系统返回的网页错误。
     *
     * @return 无返回值。
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)

        if (request?.isForMainFrame == true) {
            val description = error?.description?.toString().orEmpty()
            Log.e(TAG, "Main frame failed to load: $description")
            onMainFrameErrorCallback(
                if (description.isBlank()) "请检查网络连接或域名是否可以访问。" else description
            )
        }
    }

    /**
     * HTTP和HTTPS链接继续在内置WebView打开，电话等其他协议交给系统应用处理。
     *
     * @param view 当前WebView。
     * @param request 即将跳转的网页请求。
     *
     * @return 返回false表示WebView自行加载；返回true表示已交给外部应用。
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme == "http" || scheme == "https") {
            return false
        }

        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to open external URL scheme", error)
            true
        }
    }
}

/**
 * 把WebView加载进度、全屏和网页新窗口请求转发给Compose状态。
 *
 * @param context 用于处理新窗口中的非HTTP外部协议。
 * @param onProgressChangedCallback 进度变化回调，范围为0到100。
 * @param onShowFullscreenCallback 网站请求显示自定义视频View的回调。
 * @param onHideFullscreenCallback 网站或系统请求退出视频全屏的回调。
 * @param onOpenNewTabRequested 用户手势触发网页新窗口时，把目标HTTP或HTTPS地址交给标签会话。
 */
private class HarleyWebChromeClient(
    private val context: Context,
    private val onProgressChangedCallback: (Int) -> Unit,
    private val onShowFullscreenCallback: (View, CustomViewCallback) -> Unit,
    private val onHideFullscreenCallback: () -> Unit,
    private val onOpenNewTabRequested: (String) -> Unit
) : WebChromeClient() {

    /**
     * 接收Chromium页面加载进度。
     *
     * @param view 当前WebView。
     * @param newProgress 新进度，范围为0到100。
     *
     * @return 无返回值。
     */
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress.coerceIn(0, 100))
    }

    /**
     * 把target=_blank或window.open创建的临时窗口转换成应用内新标签。
     *
     * 使用方法：
     * 主WebView启用多窗口后由Chromium调用。只接受明确用户手势，先建立一个不展示的临时WebView
     * 获取最终目标地址；HTTP或HTTPS交给[onOpenNewTabRequested]，其他协议仍交给系统应用。
     *
     * @param view 发起新窗口请求的主WebView。
     * @param isDialog 网页是否把窗口声明为对话框，本实现不区分显示形态。
     * @param isUserGesture 是否由用户点击等明确手势触发。
     * @param resultMsg Chromium用于接收临时WebView的传输消息。
     * @return 已接管有效用户手势请求返回true；参数无效或自动弹窗返回false。
     */
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (!isUserGesture) {
            Log.w(TAG, "Blocked a web popup without a user gesture")
            return false
        }

        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popupClient = PopupCaptureWebViewClient(
            context = context,
            onOpenNewTabRequested = onOpenNewTabRequested
        )
        val popupWebView = WebView(view?.context ?: context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                safeBrowsingEnabled = true
            }
            webViewClient = popupClient
            webChromeClient = PopupCleanupWebChromeClient(
                popupClient = popupClient,
                popupWebView = this
            )
        }
        popupClient.scheduleTimeout(popupWebView)
        transport.webView = popupWebView
        return runCatching {
            resultMsg.sendToTarget()
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to hand a popup WebView to Chromium", error)
            popupClient.release(popupWebView)
            false
        }
    }

    /**
     * 接收网页播放器通过HTML5 Fullscreen API创建的自定义全屏View。
     *
     * @param view Chromium提供的视频View；为空时立即结束本次请求。
     * @param callback 宿主退出全屏后必须调用的完成回调。
     * @return 无返回值。
     */
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) {
            callback?.onCustomViewHidden()
            return
        }
        onShowFullscreenCallback(view, callback)
    }

    /**
     * 接收网站播放器主动退出全屏的请求。
     *
     * @return 无返回值。
     */
    override fun onHideCustomView() {
        onHideFullscreenCallback()
    }
}

/**
 * 从Chromium临时新窗口中截获第一个真实目标地址，然后立即释放临时WebView。
 *
 * 使用方法：
 * 只由[HarleyWebChromeClient.onCreateWindow]创建。about:blank允许继续加载，以兼容脚本先创建空窗口
 * 再设置location；第一个HTTP或HTTPS地址创建应用标签，其他协议交给系统后结束本次窗口。
 *
 * @param context 用于启动电话、邮件或其他非HTTP协议的系统处理程序。
 * @param onOpenNewTabRequested 安全HTTP或HTTPS目标地址回调。
 */
private class PopupCaptureWebViewClient(
    private val context: Context,
    private val onOpenNewTabRequested: (String) -> Unit
) : WebViewClient() {
    // 仅在真实WebView弹窗出现时创建，避免纯JVM单元测试加载本文件时访问Android Looper桩实现。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handled = false
    private var released = false
    private var timeoutTask: Runnable? = null

    /**
     * 安排临时窗口超时清理。
     *
     * 使用方法：
     * [HarleyWebChromeClient.onCreateWindow]完成临时WebView配置后立即调用。若网页一直停留在
     * about:blank、加载失败或没有发出导航，超时后也会释放Chromium资源。
     *
     * @param view 等待捕获真实地址的临时WebView。
     * @return 无返回值；清理任务会在主线程延时执行，提前处理完成时自动取消。
     */
    fun scheduleTimeout(view: WebView) {
        val task = Runnable {
            Log.w(TAG, "Timed out while waiting for a popup URL")
            release(view)
        }
        timeoutTask = task
        mainHandler.postDelayed(task, POPUP_CAPTURE_TIMEOUT_MILLIS)
    }

    /**
     * 在临时窗口真正加载前优先截获目标地址。
     *
     * @param view 临时WebView。
     * @param request 即将加载的主文档请求。
     * @return 已创建新标签或交给系统时返回true；about:blank返回false等待后续location。
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        if (request?.isForMainFrame == false) return false
        return handleUrl(view, request?.url?.toString().orEmpty())
    }

    /**
     * 兼容没有经过shouldOverrideUrlLoading的首次主文档加载。
     *
     * @param view 临时WebView。
     * @param url 正在开始加载的目标地址。
     * @param favicon 网站图标，可能为null。
     * @return 无返回值；真实地址被处理后会停止临时加载。
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (handleUrl(view, url.orEmpty())) {
            view?.stopLoading()
        }
    }

    /**
     * 临时窗口主文档加载失败时立即释放，不等待超时任务。
     *
     * @param view 临时WebView。
     * @param request 失败的资源请求。
     * @param error Chromium提供的错误信息。
     * @return 无返回值。
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            Log.e(
                TAG,
                "Popup main frame failed to load: ${error?.description?.toString().orEmpty()}"
            )
            handled = true
            release(view)
        }
    }

    /**
     * 临时窗口渲染进程退出时确认由应用处理，并释放已经失效的WebView。
     *
     * @param view 渲染进程已经退出的临时WebView。
     * @param detail 系统提供的退出原因。
     * @return 始终返回true，表示应用已处理并且不会继续复用该WebView。
     */
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        Log.e(TAG, "Popup renderer exited; didCrash=${detail?.didCrash() == true}")
        handled = true
        release(view)
        return true
    }

    /**
     * 校验并分流临时窗口地址。
     *
     * @param view 临时WebView，用于处理结束后释放资源。
     * @param url Chromium提供的候选地址。
     * @return 本次地址已经处理返回true；空地址或about:blank返回false继续等待。
     */
    private fun handleUrl(view: WebView?, url: String): Boolean {
        if (handled) return true
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme.isNullOrBlank() || scheme == "about") return false

        handled = true
        if (scheme == "http" || scheme == "https") {
            val safeUrl = normalizeWebsiteBrowserUrl(uri.toString())
            if (safeUrl != null) {
                onOpenNewTabRequested(safeUrl)
            } else {
                Log.w(TAG, "Rejected an unsafe or unrecoverable popup URL")
            }
        } else {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure { error ->
                Log.e(TAG, "Failed to open an external popup URL", error)
            }
        }
        release(view)
        return true
    }

    /**
     * 在当前Chromium回调结束后从主线程释放不再需要的临时WebView。
     *
     * @param view 已完成目标地址捕获的临时WebView；null时无需处理。
     * @return 无返回值。
     */
    fun release(view: WebView?) {
        val popupWebView = view ?: return
        if (released) return
        released = true
        timeoutTask?.let(mainHandler::removeCallbacks)
        timeoutTask = null

        mainHandler.post {
            popupWebView.stopLoading()
            (popupWebView.parent as? ViewGroup)?.removeView(popupWebView)
            popupWebView.webViewClient = WebViewClient()
            popupWebView.webChromeClient = null
            popupWebView.removeAllViews()
            popupWebView.destroy()
        }
    }
}

/**
 * 接收脚本对临时窗口的window.close请求并转交统一清理入口。
 *
 * @param popupClient 持有超时任务和幂等释放状态的临时窗口客户端。
 * @param popupWebView Chromium未随关闭回调返回窗口对象时使用的兜底实例。
 */
private class PopupCleanupWebChromeClient(
    private val popupClient: PopupCaptureWebViewClient,
    private val popupWebView: WebView
) : WebChromeClient() {

    /**
     * 网页主动关闭临时窗口时立即释放对应WebView。
     *
     * @param window Chromium请求关闭的临时窗口。
     * @return 无返回值。
     */
    override fun onCloseWindow(window: WebView?) {
        popupClient.release(window ?: popupWebView)
    }
}

/**
 * 保存一次WebView自定义全屏会话并保证完成回调最多执行一次。
 *
 * @param view Chromium交给宿主展示的全屏View。
 * @param callback 全屏View被隐藏后通知Chromium的回调。
 */
private class FullscreenWebContent(
    val view: View,
    private val callback: WebChromeClient.CustomViewCallback
) {
    private var closed = false

    /**
     * 结束全屏会话。
     *
     * @return 无返回值；重复调用会安全忽略。
     */
    fun close() {
        if (closed) return
        closed = true
        callback.onCustomViewHidden()
    }
}

/**
 * 显示全屏视频底部半透明工具条中的一个紧凑操作按钮。
 *
 * @param text 按钮文字或当前状态。
 * @param active 是否使用强调色表示功能已经开启。
 * @param onClick 点击回调。
 * @return 无返回值。
 */
@Composable
private fun FullscreenToolButton(
    text: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = if (active) FULLSCREEN_ACTIVE_COLOR else Color.White,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * 在全屏视频中央显示可直接选择的常用播放速度。
 *
 * 使用方法：
 * 用户点击全屏工具条当前倍速后显示本组件，不需要再以0.05为步长反复点击。点击任一速度
 * 立即保存并应用，点击取消或弹层外部由宿主关闭。
 *
 * @param selectedRate 当前已保存的播放速度。
 * @param onRateSelected 用户选择预设速度后的回调。
 * @param onDismiss 关闭选择器但不改变速度的回调。
 * @param modifier 外部定位修饰器。
 *
 * @return 无返回值，直接输出全屏倍速选择面板。
 */
@Composable
private fun FullscreenPlaybackRatePicker(
    selectedRate: Float,
    onRateSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "直接选择播放速度",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            FULLSCREEN_PLAYBACK_RATE_PRESETS.chunked(4).forEach { rateRow ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rateRow.forEach { rate ->
                        val selected = kotlin.math.abs(rate - selectedRate) < 0.001f
                        if (selected) {
                            Button(onClick = { onRateSelected(rate) }) {
                                Text(text = formatToolbarRate(rate))
                            }
                        } else {
                            TextButton(onClick = { onRateSelected(rate) }) {
                                Text(text = formatToolbarRate(rate), color = Color.White)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Color.White)
            }
        }
    }
}

/**
 * 从可能经过主题包装的Compose Context中查找Activity。
 *
 * @return 当前Activity；无法解析时返回null，此时仍可展示网页但不控制系统栏和方向。
 */
private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * 切换WebView桌面版或系统默认手机版User-Agent。
 *
 * @param context 用于读取系统WebView默认User-Agent。
 * @param webView 目标网页视图。
 * @param enabled true使用桌面标识，false恢复当前系统默认标识。
 * @return 无返回值；调用方需要自行决定是否重新加载当前页面。
 */
private fun applyDesktopUserAgent(context: Context, webView: WebView, enabled: Boolean) {
    val defaultUserAgent = WebSettings.getDefaultUserAgent(context)
    webView.settings.userAgentString = if (enabled) {
        defaultUserAgent
            .replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("Version/4.0 ", "")
            .replace(" Mobile ", " ")
    } else {
        defaultUserAgent
    }
}

/** @return 工具栏使用的两位小数倍速文本。 */
private fun formatToolbarRate(rate: Float): String {
    return String.format(Locale.CHINA, "%.2f×", rate)
}

/**
 * 根据脚本找到的媒体数量生成操作反馈。
 *
 * @param mediaCount 脚本找到并尝试控制的媒体数量。
 * @param successMessage 找到媒体时显示的成功说明。
 * @return 可直接显示在网页工具栏下方的中文反馈。
 */
private fun mediaActionMessage(mediaCount: Int, successMessage: String): String {
    return if (mediaCount > 0) {
        successMessage
    } else {
        "当前页面未检测到可控制的HTML5视频"
    }
}

/**
 * 把网页脚本收藏结果转换为用户可直接理解的反馈。
 *
 * 使用方法：
 * [WebsiteScreen]调用宿主的onBookmarkCurrentPage后传入返回状态。成功时说明收藏位于网站详情且
 * 默认不加入首页轮播；重复、内部页面地址无效或本地保存失败时分别给出对应原因。
 *
 * @param result HarleyApp完成网址校验、重复检查及持久化后返回的状态。
 * @return 可显示在普通网页工具栏或全屏控制层顶部的中文提示。
 */
internal fun websiteBookmarkSaveMessage(result: WebsiteBookmarkSaveResult): String {
    return when (result) {
        WebsiteBookmarkSaveResult.SAVED -> "已收藏当前页，可在网站详情中管理"
        WebsiteBookmarkSaveResult.ALREADY_SAVED -> "当前页面已经收藏"
        WebsiteBookmarkSaveResult.INVALID_URL -> "当前页面不是可收藏的HTTP或HTTPS网址"
        WebsiteBookmarkSaveResult.SAVE_FAILED -> "收藏保存失败，请重试"
    }
}

/**
 * 计算全屏脚本悬浮球拖动后的安全纵向偏移。
 *
 * 使用方法：
 * [rememberDraggableState]每次收到拖动增量时传入当前偏移和增量。函数会把结果限制在屏幕中部
 * 的安全范围，避免悬浮球被拖出屏幕，或移动到底部后再次挡住视频原生进度条。
 *
 * @param currentOffset 当前相对屏幕垂直中心的像素偏移。
 * @param dragDelta 本次纵向拖动增加的像素。
 * @param maximumOffset 允许向上或向下移动的最大绝对像素。
 * @return 已限制在[-maximumOffset, maximumOffset]范围内的新偏移。
 */
internal fun calculateFullscreenBallOffset(
    currentOffset: Float,
    dragDelta: Float,
    maximumOffset: Float
): Float {
    val safeMaximum = maximumOffset.coerceAtLeast(0f)
    return (currentOffset + dragDelta).coerceIn(-safeMaximum, safeMaximum)
}

private const val TAG = "WebsiteScreen"
private const val TOOL_MESSAGE_DURATION_MILLIS = 2_600L
private const val FULLSCREEN_CONTROLS_HIDE_DELAY_MILLIS = 3_000L
/** 标签总览抽屉从右侧滑入或滑回时的动画时长。 */
private const val TAB_OVERVIEW_DRAWER_ANIMATION_MILLIS = 260
/** 标签总览左侧空白遮罩淡入或淡出时的动画时长。 */
private const val TAB_OVERVIEW_SCRIM_ANIMATION_MILLIS = 180
/** 临时弹窗等待真实HTTP或HTTPS地址的最长时间，超时后必须释放隐藏WebView。 */
private const val POPUP_CAPTURE_TIMEOUT_MILLIS = 15_000L
/** 普通网页模式检查视频播放状态的间隔，兼顾自动弹出及时性和WebView脚本开销。 */
private const val PLAYING_VIDEO_CHECK_INTERVAL_MILLIS = 1_200L
private val FULLSCREEN_FLOATING_BALL_SIZE = 52.dp
private val FULLSCREEN_FLOATING_BALL_MAX_VERTICAL_OFFSET = 120.dp
private val FULLSCREEN_FLOATING_PANEL_MAX_WIDTH = 520.dp
private val FULLSCREEN_ACTIVE_COLOR = Color(0xFFFFD54F)
private val FULLSCREEN_PLAYBACK_RATE_PRESETS = listOf(
    0.25f,
    0.50f,
    0.75f,
    1.00f,
    1.25f,
    1.50f,
    2.00f,
    2.50f,
    3.00f,
    4.00f,
    5.00f
)
