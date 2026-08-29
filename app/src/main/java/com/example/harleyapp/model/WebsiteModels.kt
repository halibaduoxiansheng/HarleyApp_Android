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
 * 网址在进入本模型前应先通过normalizeWebsiteUrl完成HTTPS规范化。
 *
 * @param id 本机唯一标识，用于稳定编辑、删除和Compose列表定位。
 * @param title 用户看到的网站名称。
 * @param url 完整的HTTPS网址。
 * @param palette 首页卡片使用的预设配色。
 */
data class WebsiteShortcut(
    val id: String,
    val title: String,
    val url: String,
    val palette: WebsitePalette = WebsitePalette.OCEAN
)

/**
 * 把用户输入的网址整理为可安全交给WebView加载的HTTPS地址。
 *
 * 使用方法：
 * 用户可以输入example.com或https://example.com/path；前者会自动补充https://。
 * 明确填写http、缺少有效主机名或包含空白字符时返回null，由界面提示用户修改。
 *
 * @param rawUrl 用户在网站编辑框中输入的原始文本。
 *
 * @return 规范化后的HTTPS网址；输入无效或不是HTTPS时返回null。
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

    if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank()) {
        return null
    }

    return uri.toASCIIString()
}
