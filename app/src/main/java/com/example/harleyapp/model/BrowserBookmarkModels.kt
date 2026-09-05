package com.example.harleyapp.model

import java.net.URI

/** Microsoft Edge Android 正式版的稳定包名。 */
const val EDGE_BROWSER_PACKAGE_NAME = "com.microsoft.emmx"

/** 彩色 G 图标 Google 应用的稳定包名；其内置网页不一定注册为系统默认浏览器。 */
const val GOOGLE_APP_PACKAGE_NAME = "com.google.android.googlequicksearchbox"

/** 浏览器悬浮球能够保留的候选节点上限，避免异常页面生成过大的节点树。 */
const val MAX_BROWSER_BOOKMARK_NODE_COUNT = 512

/** 浏览器悬浮球能够遍历的最大节点深度，防止异常无障碍树造成递归过深。 */
const val MAX_BROWSER_BOOKMARK_NODE_DEPTH = 40

/** 自动生成网站收藏名称时允许保留的最大字符数。 */
const val MAX_BROWSER_BOOKMARK_TITLE_LENGTH = 80

/** 外部浏览器单次分享文本允许解析的最大字符数，避免异常Intent携带过大文本。 */
const val MAX_BROWSER_BOOKMARK_SHARE_TEXT_LENGTH = 8_192

/**
 * 保存浏览器无障碍节点中与网页识别有关的最小只读快照。
 *
 * 使用方法：
 * 无障碍服务只在用户点击悬浮球后，把当前浏览器窗口节点转换为本模型，再调用
 * [resolveBrowserBookmarkPage]。模型不持有系统AccessibilityNodeInfo，因此解析结束后不会继续
 * 访问浏览器窗口，也不会保存网页正文。
 *
 * @param resourceId 浏览器公开的完整View资源标识，未公开时为空字符串。
 * @param className 节点控件类型，用于区分地址输入框与普通网页文字。
 * @param text 节点当前可见文字；密码节点不会写入本字段。
 * @param contentDescription 节点辅助说明，部分浏览器会把网址放在这里。
 * @param insideWebView true表示节点属于网页正文，仅可用于推测标题，不能作为网址来源。
 */
data class BrowserBookmarkNodeSnapshot(
    val resourceId: String = "",
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val insideWebView: Boolean = false
)

/**
 * 表示从浏览器当前窗口成功识别出的可收藏网页。
 *
 * @param title 写入HarleyApp网站收藏的名称；页面标题不可用时使用域名。
 * @param url 经过HTTP/HTTPS校验与ASCII规范化的完整网址。
 */
data class ResolvedBrowserBookmarkPage(
    val title: String,
    val url: String
)

/**
 * 从浏览器通过Android系统分享的文字中提取可收藏网页。
 *
 * 使用方法：
 * 分享接收Activity把[Intent.EXTRA_TEXT]作为[sharedText]传入，并优先把
 * [Intent.EXTRA_TITLE]或[Intent.EXTRA_SUBJECT]作为[sharedTitle]传入。本函数只接受HTTP或
 * HTTPS网址；普通聊天文字、内部页面协议和没有有效主机名的内容会被拒绝。
 *
 * @param sharedText 浏览器分享的文字，可为纯网址，也可为“标题+换行+网址”。
 * @param sharedTitle 浏览器单独提供的页面标题；为空或本身是网址时自动回退到域名。
 *
 * @return 成功时返回规范化标题和完整网址；分享内容不含合法网页地址时返回null。
 */
fun resolveSharedBrowserPage(
    sharedText: String,
    sharedTitle: String = ""
): ResolvedBrowserBookmarkPage? {
    val boundedText = sharedText.take(MAX_BROWSER_BOOKMARK_SHARE_TEXT_LENGTH).trim()
    val normalizedUrl = normalizeExplicitBrowserUrl(boundedText)
        ?: SHARED_BROWSER_URL_PATTERN.findAll(boundedText)
            .map { match -> trimSharedUrlPunctuation(match.value) }
            .firstNotNullOfOrNull(::normalizeExplicitBrowserUrl)
        ?: return null
    val normalizedTitle = sharedTitle
        .trim()
        .take(MAX_BROWSER_BOOKMARK_TITLE_LENGTH)
        .takeIf { title ->
            title.isNotBlank() && normalizeExplicitBrowserUrl(title) == null
        }

    return ResolvedBrowserBookmarkPage(
        title = normalizedTitle ?: resolveBrowserBookmarkTitle(normalizedUrl, emptyList()),
        url = normalizedUrl
    )
}

/**
 * 从一次浏览器窗口快照中识别当前网页标题和网址。
 *
 * 使用方法：
 * 先按Edge与Google App已知地址栏标识匹配，再识别常见的url、address、location与omnibox
 * 资源名；最后只在网页正文外接受完整HTTP/HTTPS文字。普通正文中的超链接不会被误判为当前页。
 * 地址栏省略协议时仅接受形如真实域名或IPv4地址的文本，并默认补充HTTPS。
 *
 * @param packageName 当前活动窗口所属包名，用于优先选择对应浏览器的地址栏规则。
 * @param nodes 从当前窗口按界面顺序取得的只读节点快照。
 *
 * @return 成功时返回规范化标题与网址；地址栏未公开、处于新标签页或内容无效时返回null。
 */
fun resolveBrowserBookmarkPage(
    packageName: String,
    nodes: List<BrowserBookmarkNodeSnapshot>
): ResolvedBrowserBookmarkPage? {
    val addressNodes = nodes
        .asSequence()
        .filter { node -> isBrowserAddressNode(packageName, node) }
        .sortedBy { node -> browserAddressNodePriority(packageName, node.resourceId) }
        .toList()

    val resolvedUrl = addressNodes.firstNotNullOfOrNull { node ->
        resolveAddressNodeUrl(node)
    } ?: nodes.asSequence()
        .filterNot(BrowserBookmarkNodeSnapshot::insideWebView)
        .flatMap { node -> sequenceOf(node.text, node.contentDescription) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .firstNotNullOfOrNull(::normalizeExplicitBrowserUrl)
        ?: return null

    return ResolvedBrowserBookmarkPage(
        title = resolveBrowserBookmarkTitle(resolvedUrl, nodes),
        url = resolvedUrl
    )
}

/**
 * 判断一个节点是否可能是当前浏览器地址栏，而不是网页正文中的普通文本框。
 *
 * @param packageName 当前浏览器包名。
 * @param node 待判断的节点快照。
 * @return 已知专用标识或通用地址栏资源名匹配时返回true。
 */
private fun isBrowserAddressNode(
    packageName: String,
    node: BrowserBookmarkNodeSnapshot
): Boolean {
    if (node.insideWebView) {
        return false
    }

    val normalizedId = node.resourceId.lowercase()
    if (normalizedId in packageSpecificAddressIds(packageName)) {
        return true
    }

    val shortId = normalizedId.substringAfterLast('/')
    return GENERIC_ADDRESS_ID_MARKERS.any(shortId::contains) &&
        (node.text.isNotBlank() || node.contentDescription.isNotBlank())
}

/**
 * 为专用地址栏、通用地址栏排序，确保浏览器更新后出现多个相似节点时优先读取最可信项。
 *
 * @param packageName 当前浏览器包名。
 * @param resourceId 候选节点完整资源标识。
 * @return 数值越小优先级越高；专用精确标识固定优先于通用规则。
 */
private fun browserAddressNodePriority(packageName: String, resourceId: String): Int {
    val normalizedId = resourceId.lowercase()
    val exactIndex = packageSpecificAddressIds(packageName).indexOf(normalizedId)
    if (exactIndex >= 0) {
        return exactIndex
    }

    val shortId = normalizedId.substringAfterLast('/')
    val genericIndex = GENERIC_ADDRESS_ID_MARKERS.indexOfFirst(shortId::contains)
    return PACKAGE_ADDRESS_PRIORITY_LIMIT + genericIndex.coerceAtLeast(0)
}

/**
 * 返回特定浏览器版本优先尝试的地址栏资源标识。
 *
 * @param packageName 当前窗口包名。
 * @return 按可信度排序的小写完整资源标识列表；未知浏览器返回空列表。
 */
private fun packageSpecificAddressIds(packageName: String): List<String> {
    return when (packageName) {
        EDGE_BROWSER_PACKAGE_NAME -> EDGE_ADDRESS_IDS
        GOOGLE_APP_PACKAGE_NAME -> GOOGLE_APP_ADDRESS_IDS
        else -> emptyList()
    }
}

/**
 * 从地址栏节点的可见文字或辅助说明中解析网址。
 *
 * @param node 已确认属于地址栏的节点快照。
 * @return 有效HTTP/HTTPS网址；搜索词、新标签页提示或空文本返回null。
 */
private fun resolveAddressNodeUrl(node: BrowserBookmarkNodeSnapshot): String? {
    return sequenceOf(node.text, node.contentDescription)
        .map(::sanitizeBrowserAddressText)
        .filter(String::isNotEmpty)
        .firstNotNullOfOrNull { candidate ->
            normalizeExplicitBrowserUrl(candidate)
                ?: normalizeSchemeOmittedBrowserUrl(candidate)
        }
}

/**
 * 清除浏览器为了双向文字排版添加的不可见控制字符。
 *
 * @param value 地址栏原始文字。
 * @return 可用于网址判断的紧凑文本，不改变普通路径、查询参数和片段。
 */
private fun sanitizeBrowserAddressText(value: String): String {
    return value.trim().filterNot { character -> character in DIRECTIONAL_FORMATTING_CHARACTERS }
}

/**
 * 移除浏览器把网址嵌入说明文字时可能附带的句末标点。
 *
 * @param value 分享文字中正则匹配出的原始网址片段。
 * @return 保留网址路径、查询参数和片段，但去除不属于网址的常见中英文句末符号。
 */
private fun trimSharedUrlPunctuation(value: String): String {
    return value.trimEnd { character -> character in SHARED_URL_TRAILING_PUNCTUATION }
}

/**
 * 只接受明确带HTTP或HTTPS协议的完整浏览器网址。
 *
 * @param value 候选文本。
 * @return 规范化网址；省略协议、混入说明文字或使用内部协议时返回null。
 */
private fun normalizeExplicitBrowserUrl(value: String): String? {
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    return normalizeWebsiteUrl(value)
}

/**
 * 识别浏览器为了节省空间而省略协议的域名、路径、查询参数和片段。
 *
 * @param value 地址栏候选文本，例如example.com/news?id=1。
 * @return 默认补充HTTPS后的规范化网址；普通搜索词、含空格内容和异常域名返回null。
 */
private fun normalizeSchemeOmittedBrowserUrl(value: String): String? {
    if (value.any(Char::isWhitespace) ||
        value.contains("…") ||
        value.contains("://") ||
        (!DOMAIN_ADDRESS_PATTERN.matches(value) && !IPV4_ADDRESS_PATTERN.matches(value))
    ) {
        return null
    }
    return normalizeWebsiteUrl(value)
}

/**
 * 从网页正文第一条适合阅读的短文本推测标题，失败时稳定回退到域名。
 *
 * @param normalizedUrl 已经通过校验的完整网址。
 * @param nodes 当前浏览器窗口节点快照。
 * @return 不超过[MAX_BROWSER_BOOKMARK_TITLE_LENGTH]个字符的收藏名称。
 */
private fun resolveBrowserBookmarkTitle(
    normalizedUrl: String,
    nodes: List<BrowserBookmarkNodeSnapshot>
): String {
    val pageHeading = nodes.asSequence()
        .filter(BrowserBookmarkNodeSnapshot::insideWebView)
        .map(BrowserBookmarkNodeSnapshot::text)
        .map(String::trim)
        .firstOrNull { text ->
            text.length in MIN_BROWSER_PAGE_TITLE_LENGTH..MAX_BROWSER_BOOKMARK_TITLE_LENGTH &&
                normalizeExplicitBrowserUrl(text) == null &&
                !DOMAIN_ADDRESS_PATTERN.matches(text)
        }
    if (pageHeading != null) {
        return pageHeading.take(MAX_BROWSER_BOOKMARK_TITLE_LENGTH)
    }

    return runCatching { URI(normalizedUrl).host }
        .getOrNull()
        .orEmpty()
        .removePrefix("www.")
        .take(MAX_BROWSER_BOOKMARK_TITLE_LENGTH)
        .ifBlank { "未命名网站" }
}

/** Edge 151实机已确认的地址栏资源标识，后续版本仍保留通用规则兜底。 */
private val EDGE_ADDRESS_IDS = listOf(
    "com.microsoft.emmx:id/url_bar"
)

/** Google App历代内置网页常见地址栏标识；实机节点确认后可继续追加而不影响通用识别。 */
private val GOOGLE_APP_ADDRESS_IDS = listOf(
    "com.google.android.googlequicksearchbox:id/url_bar",
    "com.google.android.googlequicksearchbox:id/omnibox_text",
    "com.google.android.googlequicksearchbox:id/googleapp_browser_urlbar_text"
)

/** 常见Chromium、Firefox和厂商浏览器地址栏资源名片段。 */
private val GENERIC_ADDRESS_ID_MARKERS = listOf(
    "url_bar",
    "urlbar",
    "omnibox",
    "address_bar",
    "addressbar",
    "location_bar"
)

/** 域名或域名加端口、路径的保守格式，避免把搜索关键词自动补成无效网站。 */
private val DOMAIN_ADDRESS_PATTERN = Regex(
    pattern = "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+" +
        "[A-Za-z]{2,63}(?::[0-9]{1,5})?(?:[/?#].*)?$"
)

/** IPv4地址或IPv4加端口、路径的保守格式；每段范围最终仍由URI规范化检查。 */
private val IPV4_ADDRESS_PATTERN = Regex(
    pattern = "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?:[/?#].*)?$"
)

/** 从浏览器分享说明中保守提取第一个HTTP/HTTPS网址，不接受其他协议。 */
private val SHARED_BROWSER_URL_PATTERN = Regex(
    pattern = "https?://[^\\s<>\\\"]+",
    option = RegexOption.IGNORE_CASE
)

/** 分享文字中经常紧跟在网址后的句末标点。 */
private val SHARED_URL_TRAILING_PUNCTUATION = setOf(
    '.',
    ',',
    ';',
    '!',
    '?',
    '。',
    '，',
    '；',
    '！',
    '？',
    '、'
)

/** 浏览器可能插入但不属于网址的Unicode双向排版控制字符。 */
private val DIRECTIONAL_FORMATTING_CHARACTERS = setOf(
    '\u200E',
    '\u200F',
    '\u202A',
    '\u202B',
    '\u202C',
    '\u202D',
    '\u202E',
    '\u2066',
    '\u2067',
    '\u2068',
    '\u2069'
)

/** 专用浏览器候选在排序时预留的优先级范围。 */
private const val PACKAGE_ADDRESS_PRIORITY_LIMIT = 100

/** 自动采用网页正文文字作为标题所需的最小字符数。 */
private const val MIN_BROWSER_PAGE_TITLE_LENGTH = 2
