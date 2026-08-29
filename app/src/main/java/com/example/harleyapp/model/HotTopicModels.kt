package com.example.harleyapp.model

import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 热点详情页支持的平台及其安全跳转信息。
 *
 * 使用方法：
 * 数据仓库使用[sourceId]匹配公开热点接口的平台编号；用户点击热点时，系统层优先把
 * HTTPS链接交给[preferredPackageName]对应的官方App处理，失败后再交给系统浏览器。
 *
 * @param sourceId 公开热点接口返回的平台稳定编号。
 * @param displayName 首页平台切换按钮显示的中文名称。
 * @param preferredPackageName Android端优先接收链接的官方App包名。
 * @param allowedDomainSuffixes 允许打开的官方域名后缀集合，用于拒绝第三方异常跳转。
 */
enum class HotTopicPlatform(
    val sourceId: String,
    val displayName: String,
    val preferredPackageName: String,
    val allowedDomainSuffixes: Set<String>
) {
    WEIBO(
        sourceId = "weibo",
        displayName = "微博",
        preferredPackageName = "com.sina.weibo",
        allowedDomainSuffixes = setOf("weibo.com", "weibo.cn")
    ),
    BAIDU(
        sourceId = "baidu",
        displayName = "百度",
        preferredPackageName = "com.baidu.searchbox",
        allowedDomainSuffixes = setOf("baidu.com")
    ),
    ZHIHU(
        sourceId = "zhihu",
        displayName = "知乎",
        preferredPackageName = "com.zhihu.android",
        allowedDomainSuffixes = setOf("zhihu.com")
    ),
    DOUYIN(
        sourceId = "douyin",
        displayName = "抖音",
        preferredPackageName = "com.ss.android.ugc.aweme",
        allowedDomainSuffixes = setOf("douyin.com")
    )
}

/**
 * 一条可以安全展示并跳转到原平台的热点索引。
 *
 * 使用方法：
 * 只能由热点数据仓库在完成标题清理、HTTPS校验和官方域名校验后创建，再交给Compose界面显示。
 *
 * @param platform 热点所属平台。
 * @param rank 原平台榜单排名，从1开始。
 * @param title 热点标题，不包含第三方文章正文。
 * @param url 已规范化且通过官方域名白名单校验的HTTPS链接。
 */
data class HotTopic(
    val platform: HotTopicPlatform,
    val rank: Int,
    val title: String,
    val url: String
)

/**
 * 一次成功拉取并解析后的热点快照。
 *
 * 使用方法：
 * 首页保留最后一次成功快照；网络刷新失败时继续显示该快照，避免页面突然变空。
 *
 * @param updatedAt 数据提供方返回的榜单更新时间，可能为空。
 * @param fetchedAtMillis 本App成功取得响应时的Unix毫秒时间戳，用于控制自动刷新频率。
 * @param topicsByPlatform 按平台保存的热点有序列表。
 */
data class HotTopicSnapshot(
    val updatedAt: String,
    val fetchedAtMillis: Long,
    val topicsByPlatform: Map<HotTopicPlatform, List<HotTopic>>
)

/**
 * 校验热点链接属于对应平台，并把查询类链接转换为可安全交给Intent的ASCII地址。
 *
 * 使用方法：
 * 解析接口响应时传入平台、原始链接和标题。微博、百度、抖音使用标题重新构造官方搜索链接，
 * 避免响应中未编码的中文或空格导致Android跳转失败；知乎保留接口返回的官方问题链接。
 *
 * @param platform 热点所属平台。
 * @param rawUrl 接口返回的原始跳转地址。
 * @param title 已去除首尾空白的热点标题。
 *
 * @return 通过HTTPS和官方域名校验后的ASCII链接；字段为空、协议或域名不合法时返回null。
 */
fun normalizeHotTopicUrl(
    platform: HotTopicPlatform,
    rawUrl: String,
    title: String
): String? {
    val trimmedUrl = rawUrl.trim()
    val trimmedTitle = title.trim()
    if (trimmedUrl.isBlank() || trimmedTitle.isBlank()) {
        return null
    }

    val parsedUrl = runCatching {
        URL(trimmedUrl)
    }.getOrNull() ?: return null
    if (!parsedUrl.protocol.equals("https", ignoreCase = true) ||
        !isAllowedPlatformHost(parsedUrl.host, platform.allowedDomainSuffixes)
    ) {
        return null
    }

    val encodedTitle = URLEncoder.encode(
        trimmedTitle,
        StandardCharsets.UTF_8.name()
    ).replace("+", "%20")

    return when (platform) {
        HotTopicPlatform.WEIBO -> "https://s.weibo.com/weibo?q=$encodedTitle"
        HotTopicPlatform.BAIDU -> "https://www.baidu.com/s?wd=$encodedTitle"
        HotTopicPlatform.DOUYIN -> "https://so.douyin.com/s?keyword=$encodedTitle"
        HotTopicPlatform.ZHIHU -> runCatching {
            URI(trimmedUrl).toASCIIString()
        }.getOrNull()
    }
}

/**
 * 判断主机名是否等于官方域名或属于其真实子域名。
 *
 * @param rawHost URL解析得到的主机名。
 * @param allowedSuffixes 平台允许的官方域名集合。
 *
 * @return 命中官方根域名或以点分隔的子域名时返回true，否则返回false。
 */
private fun isAllowedPlatformHost(
    rawHost: String,
    allowedSuffixes: Set<String>
): Boolean {
    val normalizedHost = rawHost.lowercase(Locale.ROOT).trimEnd('.')
    return allowedSuffixes.any { suffix ->
        normalizedHost == suffix || normalizedHost.endsWith(".$suffix")
    }
}
