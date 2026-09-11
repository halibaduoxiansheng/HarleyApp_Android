package com.example.harleyapp.model

import java.net.URI
import java.util.Locale

/** 网站浏览器同时保留的最大标签数量，避免多个WebView长期占用过多内存。 */
const val MAX_WEBSITE_BROWSER_TAB_COUNT = 8

/** 空白标签在标签栏中显示的固定标题。 */
const val WEBSITE_BROWSER_NEW_TAB_TITLE = "新标签页"

/** 标签及收藏来源标识允许持久化的最大字符数。 */
internal const val WEBSITE_BROWSER_MAX_IDENTIFIER_LENGTH = 128

/** 网页标题写入标签快照前保留的最大字符数。 */
internal const val WEBSITE_BROWSER_MAX_TITLE_LENGTH = 512

/** 可恢复网页地址允许持久化的最大字符数。 */
internal const val WEBSITE_BROWSER_MAX_URL_LENGTH = 8 * 1024

/**
 * 网站浏览器中的一个独立标签。
 *
 * 使用方法：
 * 新建空白标签时只传[id]；从网站收藏打开时同时写入[websiteId]、[title]和[url]。
 * 网页继续跳转后保留原[websiteId]，仅通过[updateWebsiteBrowserTab]更新实际标题和网址。
 *
 * @param id 标签会话内稳定且唯一的标识，用于切换、关闭以及关联对应的WebView。
 * @param websiteId 打开该标签的网站收藏标识；空白标签或没有收藏来源时为null。
 * @param title 标签栏显示的页面标题；空白标签默认显示“新标签页”。
 * @param url 当前页面的HTTP或HTTPS网址；尚未加载网页的空白标签为null。
 */
data class WebsiteBrowserTab(
    val id: String,
    val websiteId: String? = null,
    val title: String = WEBSITE_BROWSER_NEW_TAB_TITLE,
    val url: String? = null
)

/**
 * 网站浏览器当前全部标签及选中状态。
 *
 * 使用方法：
 * 页面首次进入时调用[createWebsiteBrowserSession]获得带一个空白标签的会话；后续只使用本文件的
 * 纯函数生成新会话并替换旧状态。持久化恢复时允许先构造空会话，再逐项校验并恢复标签。
 *
 * @param tabs 按标签栏从左到右排列的标签，正常操作后数量不会超过[MAX_WEBSITE_BROWSER_TAB_COUNT]。
 * @param activeTabId 当前显示的标签标识；空会话或外部数据尚未完成修复时允许为null。
 */
data class WebsiteBrowserSession(
    val tabs: List<WebsiteBrowserTab> = emptyList(),
    val activeTabId: String? = null
)

/**
 * 创建网站浏览器的初始会话。
 *
 * 使用方法：
 * 页面或会话仓库没有可恢复数据时传入新生成的标签id，返回值可直接作为浏览器根状态。
 *
 * @param initialTabId 初始空白标签的唯一标识；空白标识无效。
 * @return 包含一个已选中空白标签的会话；标识为空时返回空会话。
 */
fun createWebsiteBrowserSession(initialTabId: String): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(initialTabId)
        ?: return WebsiteBrowserSession()

    val initialTab = WebsiteBrowserTab(id = normalizedTabId)
    return WebsiteBrowserSession(
        tabs = listOf(initialTab),
        activeTabId = initialTab.id
    )
}

/**
 * 在会话末尾创建网站标签并立即选中。
 *
 * 使用方法：
 * 用户从首页卡片、网站收藏或搜索结果明确打开网站时，传入调用方生成的唯一[tabId]和网站模型。
 * 如果当前标签已经达到上限，应由界面根据返回值未变化的结果提示用户先关闭标签。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 新标签的唯一标识，必须非空且不能与现有标签重复。
 * @param website 需要在新标签中打开的网站入口。
 * @return 新标签已追加并选中的会话；参数无效或达到八个标签上限时返回原会话。
 */
fun createWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String,
    website: WebsiteShortcut
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    if (!canAppendWebsiteBrowserTab(session, normalizedTabId)) return session

    val newTab = website.toWebsiteBrowserTab(normalizedTabId) ?: return session
    return session.copy(
        tabs = session.tabs + newTab,
        activeTabId = newTab.id
    )
}

/**
 * 根据来源标签创建网页请求的新标签并立即选中。
 *
 * 使用方法：
 * WebView收到target=_blank或window.open请求并取得目标标题、网址后，传入发起请求的[sourceTabId]
 * 和调用方生成的唯一[tabId]。新标签会继承来源标签的websiteId，使网页工具设置继续归属于原网站；
 * 临时标签id只用于会话定位，绝不会被误写为网站收藏标识。
 *
 * @param session 当前网站浏览会话。
 * @param sourceTabId 发起新窗口请求的来源标签标识。
 * @param tabId 新标签的唯一标识，必须非空且不能与现有标签重复。
 * @param title 目标页面标题；空白时显示“新标签页”，页面加载完成后可继续更新。
 * @param url 临时窗口捕获到的第一个完整HTTP或HTTPS目标网址；尚未得到真实网址时调用方继续等待。
 * @return 已继承来源网站归属、追加并选中的新会话；来源不存在、标识无效或达到上限时返回原会话。
 */
fun createWebsiteBrowserChildTab(
    session: WebsiteBrowserSession,
    sourceTabId: String,
    tabId: String,
    title: String,
    url: String
): WebsiteBrowserSession {
    val normalizedSourceTabId = normalizeWebsiteBrowserIdentifier(sourceTabId) ?: return session
    val sourceTab = session.tabs.firstOrNull { tab -> tab.id == normalizedSourceTabId }
        ?: return session
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    if (!canAppendWebsiteBrowserTab(session, normalizedTabId)) return session

    val normalizedUrl = normalizeWebsiteBrowserUrl(url) ?: return session
    val newTab = WebsiteBrowserTab(
        id = normalizedTabId,
        websiteId = sourceTab.websiteId,
        title = normalizeWebsiteBrowserTitle(title) ?: WEBSITE_BROWSER_NEW_TAB_TITLE,
        url = normalizedUrl
    )
    return session.copy(
        tabs = session.tabs + newTab,
        activeTabId = newTab.id
    )
}

/**
 * 在会话末尾创建空白标签并立即选中。
 *
 * 使用方法：
 * 用户点击标签栏的新增按钮时传入新生成的唯一[tabId]。后续选择网站可调用
 * [fillWebsiteBrowserTab]复用此空白标签，避免产生两个标签。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 新空白标签的唯一标识，必须非空且不能与现有标签重复。
 * @return 新空白标签已追加并选中的会话；参数无效或达到八个标签上限时返回原会话。
 */
fun createBlankWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    if (!canAppendWebsiteBrowserTab(session, normalizedTabId)) return session

    val newTab = WebsiteBrowserTab(id = normalizedTabId)
    return session.copy(
        tabs = session.tabs + newTab,
        activeTabId = newTab.id
    )
}

/**
 * 使用网站入口填充指定空白标签并立即选中。
 *
 * 使用方法：
 * 用户已经打开“新标签页”后再选择网站时传入该空白标签id；函数只会替换真正没有网址和网站来源
 * 的标签，不会覆盖已加载网页的标签。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 要填充的空白标签标识。
 * @param website 要写入空白标签的网站入口。
 * @return 标签已填充并选中的新会话；标签不存在或已经加载网页时返回原会话。
 */
fun fillWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String,
    website: WebsiteShortcut
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    val tabIndex = session.tabs.indexOfFirst { tab -> tab.id == normalizedTabId }
    if (tabIndex < 0 || !isBlankWebsiteBrowserTab(session.tabs[tabIndex])) return session

    val updatedTab = website.toWebsiteBrowserTab(normalizedTabId) ?: return session
    return session.copy(
        tabs = session.tabs.replaceAt(tabIndex, updatedTab),
        activeTabId = updatedTab.id
    )
}

/**
 * 更新标签在网页跳转后的实际标题和网址。
 *
 * 使用方法：
 * WebView页面加载完成时传入最新标题和网址；只更新其中一项时，另一项传null即可保留旧值。
 * 空标题和空网址不会覆盖最后一个有效值，避免加载中间状态把标签信息清空。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 需要更新的标签标识。
 * @param title 最新网页标题；null或空白表示保留原标题。
 * @param url 最新网页网址；null或空白表示保留原网址。
 * @return 指定标签已更新的新会话；标签不存在或没有有效变化时返回原会话。
 */
fun updateWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String,
    title: String? = null,
    url: String? = null
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    val tabIndex = session.tabs.indexOfFirst { tab -> tab.id == normalizedTabId }
    if (tabIndex < 0) return session

    val currentTab = session.tabs[tabIndex]
    val updatedTab = currentTab.copy(
        title = title
            ?.let(::normalizeWebsiteBrowserTitle)
            ?: currentTab.title,
        url = url
            ?.let(::normalizeWebsiteBrowserUrl)
            ?: currentTab.url
    )
    if (updatedTab == currentTab) return session

    return session.copy(tabs = session.tabs.replaceAt(tabIndex, updatedTab))
}

/**
 * 切换到已经存在的标签。
 *
 * 使用方法：
 * 用户点击标签栏项目时传入对应[tabId]；调用方只需替换返回的会话，不必重新排列标签。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 用户准备显示的标签标识。
 * @return 已切换活动标签的新会话；目标不存在或已经选中时返回原会话。
 */
fun selectWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    if (
        session.activeTabId == normalizedTabId ||
        session.tabs.none { tab -> tab.id == normalizedTabId }
    ) {
        return session
    }
    return session.copy(activeTabId = normalizedTabId)
}

/**
 * 关闭指定标签，并按稳定的相邻顺序选择后续标签。
 *
 * 使用方法：
 * 用户明确点击关闭按钮时传入目标[tabId]。关闭当前标签后优先选择其右侧标签；当前标签位于最右侧
 * 时选择左侧标签。关闭唯一标签时使用[replacementBlankTabId]创建新的“新标签页”，保证浏览器仍有
 * 可操作页面。关闭后台标签不会改变当前选中项。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 用户明确要求关闭的标签标识。
 * @param replacementBlankTabId 关闭唯一标签时用于创建替代空白标签的新标识。
 * @return 标签已关闭并完成活动项选择的新会话；目标不存在，或替代标识无效时返回原会话。
 */
fun closeWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String,
    replacementBlankTabId: String
): WebsiteBrowserSession {
    val normalizedTabId = normalizeWebsiteBrowserIdentifier(tabId) ?: return session
    val closingIndex = session.tabs.indexOfFirst { tab -> tab.id == normalizedTabId }
    if (closingIndex < 0) return session

    if (session.tabs.size == 1) {
        val normalizedReplacementId = normalizeWebsiteBrowserIdentifier(replacementBlankTabId)
            ?: return session
        if (normalizedReplacementId == normalizedTabId) return session
        return createWebsiteBrowserSession(normalizedReplacementId)
    }

    val remainingTabs = session.tabs.filterIndexed { index, _ -> index != closingIndex }
    val nextActiveTabId = when {
        session.activeTabId != normalizedTabId &&
            remainingTabs.any { tab -> tab.id == session.activeTabId } -> {
            session.activeTabId
        }

        closingIndex < remainingTabs.size -> remainingTabs[closingIndex].id
        else -> remainingTabs.last().id
    }
    return WebsiteBrowserSession(
        tabs = remainingTabs,
        activeTabId = nextActiveTabId
    )
}

/**
 * 判断是否允许向当前会话追加指定标签。
 *
 * 使用方法：
 * 两种新建标签函数在修改列表前统一调用，确保数量上限、非空标识和标识唯一性规则完全一致。
 *
 * @param session 当前网站浏览会话。
 * @param tabId 准备追加的新标签标识。
 * @return 标签数量未满、标识非空且未重复时返回true，否则返回false。
 */
private fun canAppendWebsiteBrowserTab(
    session: WebsiteBrowserSession,
    tabId: String
): Boolean {
    return session.tabs.size < MAX_WEBSITE_BROWSER_TAB_COUNT &&
        session.tabs.none { tab ->
            normalizeWebsiteBrowserIdentifier(tab.id) == tabId
        }
}

/**
 * 判断标签是否仍是没有加载内容的新标签页。
 *
 * 使用方法：
 * 填充标签前调用；网站来源和网址都为空才允许被网站入口复用。
 *
 * @param tab 待检查的浏览器标签。
 * @return 标签没有网站来源且网址为空时返回true，否则返回false。
 */
private fun isBlankWebsiteBrowserTab(tab: WebsiteBrowserTab): Boolean {
    return tab.websiteId == null && tab.url.isNullOrBlank()
}

/**
 * 把网站收藏入口转换成指定id的浏览器标签。
 *
 * 使用方法：
 * 新建网站标签或填充空白标签时调用，统一复制网站来源、标题和入口网址。
 *
 * @param tabId 新浏览器标签的唯一标识。
 * @return 包含当前网站入口信息的浏览器标签；标题为空或网址不安全时返回null。
 */
private fun WebsiteShortcut.toWebsiteBrowserTab(tabId: String): WebsiteBrowserTab? {
    val normalizedWebsiteId = normalizeWebsiteBrowserIdentifier(id) ?: return null
    val normalizedTitle = normalizeWebsiteBrowserTitle(title) ?: return null
    val normalizedUrl = normalizeWebsiteBrowserUrl(url) ?: return null
    return WebsiteBrowserTab(
        id = tabId,
        websiteId = normalizedWebsiteId,
        title = normalizedTitle,
        url = normalizedUrl
    )
}

/**
 * 校验标签快照只保存可再次加载的HTTP或HTTPS网址。
 *
 * 使用方法：
 * 新建网页标签或接收WebView页面更新时调用。内部about、javascript、file等地址不会进入会话模型，
 * 从而避免冷启动把不可恢复或高风险协议重新交给WebView。
 *
 * @param value 待检查的完整网页地址。
 * @return 去除首尾空白且包含主机名的HTTP或HTTPS地址；其他输入返回null。
 */
internal fun normalizeWebsiteBrowserUrl(value: String): String? {
    val normalized = value.trim()
    if (
        normalized.isEmpty() ||
        normalized.length > WEBSITE_BROWSER_MAX_URL_LENGTH ||
        normalized.any(Char::isWhitespace)
    ) {
        return null
    }

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    return normalized.takeIf {
        scheme == "http" || scheme == "https"
    }?.takeIf {
        !uri.host.isNullOrBlank() && uri.userInfo == null
    }
}

/**
 * 规范化标签或收藏来源标识，使内存模型与冷启动持久化遵循同一规则。
 *
 * 使用方法：
 * 创建、切换、关闭标签以及会话仓库编解码前调用。函数会去除首尾空白，并拒绝可能造成JSON
 * 体积异常或同一标识在保存后发生变化的内容。
 *
 * @param value 待校验的标签标识或网站收藏标识。
 * @return 去除首尾空白后的有效标识；为空、超过128字符或包含控制字符时返回null。
 */
internal fun normalizeWebsiteBrowserIdentifier(value: String): String? {
    val normalized = value.trim()
    return normalized.takeIf { identifier ->
        identifier.isNotEmpty() &&
            identifier.length <= WEBSITE_BROWSER_MAX_IDENTIFIER_LENGTH &&
            identifier.none(Char::isISOControl)
    }
}

/**
 * 把网页标题整理为适合标签栏和轻量会话保存的单行文本。
 *
 * 使用方法：
 * WebView回传document.title或网站收藏标题时调用。控制字符会替换为空格，连续空白会折叠，
 * 最终内容最多保留512个字符；这样异常网页标题只会被安全截断，不会导致整个标签在重启后丢失。
 *
 * @param value 网页或网站收藏提供的原始标题。
 * @return 非空的单行标题；清理后没有可显示内容时返回null。
 */
internal fun normalizeWebsiteBrowserTitle(value: String): String? {
    val normalized = buildString(value.length.coerceAtMost(WEBSITE_BROWSER_MAX_TITLE_LENGTH)) {
        var previousWasWhitespace = false
        var index = 0
        while (index < value.length && length < WEBSITE_BROWSER_MAX_TITLE_LENGTH) {
            val character = value[index]
            if (
                Character.isHighSurrogate(character) &&
                index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1])
            ) {
                if (length + 2 > WEBSITE_BROWSER_MAX_TITLE_LENGTH) break
                append(character)
                append(value[index + 1])
                previousWasWhitespace = false
                index += 2
                continue
            }
            if (Character.isSurrogate(character)) {
                index++
                continue
            }

            val isWhitespace = character.isWhitespace() || character.isISOControl()
            if (isWhitespace) {
                if (
                    isNotEmpty() &&
                    !previousWasWhitespace &&
                    length < WEBSITE_BROWSER_MAX_TITLE_LENGTH
                ) {
                    append(' ')
                }
            } else if (length < WEBSITE_BROWSER_MAX_TITLE_LENGTH) {
                append(character)
            }
            previousWasWhitespace = isWhitespace
            index++
        }
    }.trim()

    return normalized.takeIf(String::isNotEmpty)
}

/**
 * 在不可变列表的指定位置替换一个标签。
 *
 * 使用方法：
 * 标签填充或页面信息更新时传入已确认有效的[index]，返回的新列表保留原有标签顺序。
 *
 * @param index 要替换的标签下标。
 * @param tab 新的标签内容。
 * @return 仅指定位置被替换的新标签列表。
 */
private fun List<WebsiteBrowserTab>.replaceAt(
    index: Int,
    tab: WebsiteBrowserTab
): List<WebsiteBrowserTab> {
    return mapIndexed { currentIndex, currentTab ->
        if (currentIndex == index) tab else currentTab
    }
}
