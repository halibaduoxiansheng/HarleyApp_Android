package com.example.harleyapp.model

import java.net.URI
import java.util.Locale

/**
 * 首页网站卡片可选择的预设配色。
 *
 * 使用方法：
 * 网站编辑对话框把用户选择保存为枚举值，界面再根据枚举映射到固定渐变色。
 * 只保存稳定枚举名，不直接保存Compose颜色值，后续调整视觉颜色不会破坏旧数据。
 */
enum class WebsitePalette {
    OCEAN,
    VIOLET,
    SUNSET,
    FOREST,
    ROSE,
    AMBER
}

/**
 * 用户保存在首页轮播中的一个网站入口。
 *
 * 使用方法：
 * 新增网站时生成唯一id；编辑时保留原id，只替换名称、网址和配色。
 * 网址在进入本模型前应先通过normalizeWebsiteUrl完成HTTP或HTTPS规范化。
 *
 * @param id 本机唯一标识，用于稳定编辑、删除和Compose列表定位。
 * @param title 用户看到的网站名称。
 * @param url 完整的HTTP或HTTPS网址。
 * @param palette 首页卡片使用的预设配色。
 */
data class WebsiteShortcut(
    val id: String,
    val title: String,
    val url: String,
    val palette: WebsitePalette = WebsitePalette.OCEAN
)

/**
 * 从网站列表中解析当前可用的默认网站标识。
 *
 * 使用方法：
 * 从本地读取preferredWebsiteId后调用本函数。如果该网站仍存在则继续使用；如果网站已经被
 * 删除、标识为空或列表顺序发生变化，则自动回退到当前列表第一项，空列表返回null。
 *
 * @param websites 当前有效且有序的网站列表。
 * @param preferredWebsiteId 用户以前保存的默认网站标识，允许为null。
 *
 * @return 当前可用的默认网站标识；列表为空时返回null。
 */
fun resolveDefaultWebsiteId(
    websites: List<WebsiteShortcut>,
    preferredWebsiteId: String?
): String? {
    return preferredWebsiteId
        ?.takeIf { preferredId -> websites.any { website -> website.id == preferredId } }
        ?: websites.firstOrNull()?.id
}

/**
 * 计算网站卡片自动轮播时的下一页位置。
 *
 * 使用方法：
 * WebsiteCarousel在每次轮播计时结束后传入当前页和网站数量。到达最后一页时回到第一页；
 * 网站不足两项时返回null，调用方无需启动滚动动画。
 *
 * @param currentPage 当前显示页码，从0开始。
 * @param pageCount 当前网站卡片总数。
 *
 * @return 下一页页码；网站不足两项时返回null。
 */
fun nextWebsiteCarouselPage(
    currentPage: Int,
    pageCount: Int
): Int? {
    if (pageCount <= 1) {
        return null
    }

    val safeCurrentPage = currentPage.coerceIn(0, pageCount - 1)
    return (safeCurrentPage + 1) % pageCount
}

/**
 * 把用户输入的网址整理为可交给WebView加载的HTTP或HTTPS地址。
 *
 * 使用方法：
 * 用户可以输入example.com、http://example.com或https://example.com/path；未填写协议时
 * 自动补充https://，明确填写HTTP时保留原协议。缺少有效主机名、使用其他协议或包含空白
 * 字符时返回null，由界面提示用户修改。
 *
 * @param rawUrl 用户在网站编辑框中输入的原始文本。
 *
 * @return 规范化后的HTTP或HTTPS网址；输入无效或使用其他协议时返回null。
 */
fun normalizeWebsiteUrl(rawUrl: String): String? {
    val trimmedUrl = rawUrl.trim()
    if (trimmedUrl.isBlank() || trimmedUrl.any(Char::isWhitespace)) {
        return null
    }

    val urlWithScheme = if (trimmedUrl.contains("://")) {
        trimmedUrl
    } else {
        "https://$trimmedUrl"
    }
    val uri = runCatching {
        URI(urlWithScheme)
    }.getOrNull() ?: return null

    val normalizedScheme = uri.scheme?.lowercase(Locale.ROOT)
    if (normalizedScheme !in SUPPORTED_WEB_SCHEMES || uri.host.isNullOrBlank()) {
        return null
    }

    return uri.toASCIIString()
}

/** WebView入口允许用户显式选择的网页协议。 */
private val SUPPORTED_WEB_SCHEMES = setOf("http", "https")
