package com.example.harleyapp.model

import java.net.URI

/** 二维码内容经过本机解析后的安全展示类型。 */
enum class QrScanContentType(val displayName: String) {
    WEB_LINK("网页链接"),
    WIFI("Wi-Fi网络"),
    TEXT("文本内容")
}

/**
 * 表示一次二维码识别后可直接展示的结构化结果。
 *
 * 使用方法：
 * 把扫描器返回的原始字符串交给[parseQrScanContent]，页面只展示本对象，不直接执行二维码里的
 * 命令。只有[safeWebUrl]非空时才允许在用户再次点击确认后交给浏览器。
 *
 * @param rawValue 二维码未经改写的原始文本，用于复制和追溯。
 * @param type 经过白名单判断的内容类型。
 * @param title 结果卡片的简短标题。
 * @param detail 适合直接阅读的说明；Wi-Fi内容会拆分网络名和安全类型。
 * @param safeWebUrl 仅允许HTTP或HTTPS且包含有效主机名的网址，其他类型固定为空。
 */
data class QrScanContent(
    val rawValue: String,
    val type: QrScanContentType,
    val title: String,
    val detail: String,
    val safeWebUrl: String? = null
)

/**
 * 在不执行任何外部动作的前提下解析二维码文本。
 *
 * 使用方法：
 * 相机或相册识别成功后调用本函数。函数优先识别安全网页链接和标准Wi-Fi二维码，其余内容全部
 * 降级为普通文本，避免`intent:`、`file:`或其他自定义协议被自动执行。
 *
 * @param rawValue 扫描库返回的原始二维码文本。
 * @return 去除首尾空白后的结构化结果；内容为空时返回null。
 */
fun parseQrScanContent(rawValue: String): QrScanContent? {
    val normalizedValue = rawValue.trim()
    if (normalizedValue.isEmpty()) return null

    normalizeSafeWebUrl(normalizedValue)?.let { safeUrl ->
        return QrScanContent(
            rawValue = normalizedValue,
            type = QrScanContentType.WEB_LINK,
            title = "识别到网页链接",
            detail = safeUrl,
            safeWebUrl = safeUrl
        )
    }

    parseWifiQrContent(normalizedValue)?.let { wifiContent ->
        return wifiContent
    }

    return QrScanContent(
        rawValue = normalizedValue,
        type = QrScanContentType.TEXT,
        title = "识别到文本",
        detail = normalizedValue
    )
}

/**
 * 校验网页二维码是否只使用HTTP或HTTPS协议。
 *
 * @param value 待校验的完整字符串。
 * @return 包含有效主机名的HTTP或HTTPS网址；协议不安全、格式错误或含用户凭据时返回null。
 */
fun normalizeSafeWebUrl(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return uri.toASCIIString()
}

/** 把标准Wi-Fi二维码拆成网络名、安全类型和可选密码，未知字段不会触发系统连接。 */
private fun parseWifiQrContent(value: String): QrScanContent? {
    if (!value.startsWith(WIFI_PREFIX, ignoreCase = true)) return null

    val fields = splitEscaped(value.substring(WIFI_PREFIX.length), ';')
        .mapNotNull { token ->
            val separatorIndex = findUnescapedCharacter(token, ':')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = token.substring(0, separatorIndex).uppercase()
            val fieldValue = unescapeWifiValue(token.substring(separatorIndex + 1))
            key to fieldValue
        }
        .toMap()
    val ssid = fields[WIFI_SSID_KEY]?.takeIf(String::isNotBlank) ?: return null
    val security = fields[WIFI_TYPE_KEY].orEmpty().ifBlank { "未标注" }
    val hiddenLabel = if (fields[WIFI_HIDDEN_KEY].equals("true", ignoreCase = true)) {
        "\n隐藏网络：是"
    } else {
        ""
    }
    val passwordLabel = fields[WIFI_PASSWORD_KEY]
        ?.takeIf(String::isNotBlank)
        ?.let { password -> "\n密码：$password" }
        .orEmpty()

    return QrScanContent(
        rawValue = value,
        type = QrScanContentType.WIFI,
        title = "识别到Wi-Fi网络",
        detail = "网络：$ssid\n安全类型：$security$passwordLabel$hiddenLabel"
    )
}

/** 按未转义分隔符切分Wi-Fi二维码字段，保留反斜杠供后续统一反转义。 */
private fun splitEscaped(value: String, delimiter: Char): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    value.forEach { character ->
        if (character == delimiter && !escaped) {
            result += current.toString()
            current.clear()
        } else {
            current.append(character)
        }
        escaped = character == '\\' && !escaped
        if (character != '\\') escaped = false
    }
    result += current.toString()
    return result
}

/** 查找Wi-Fi字段中第一个未转义分隔符的位置。 */
private fun findUnescapedCharacter(value: String, target: Char): Int {
    var escaped = false
    value.forEachIndexed { index, character ->
        if (character == target && !escaped) return index
        escaped = character == '\\' && !escaped
        if (character != '\\') escaped = false
    }
    return -1
}

/** 恢复Wi-Fi二维码规范允许转义的标点字符。 */
private fun unescapeWifiValue(value: String): String {
    val result = StringBuilder(value.length)
    var escaped = false
    value.forEach { character ->
        if (escaped) {
            result.append(character)
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else {
            result.append(character)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}

private const val WIFI_PREFIX = "WIFI:"
private const val WIFI_TYPE_KEY = "T"
private const val WIFI_SSID_KEY = "S"
private const val WIFI_PASSWORD_KEY = "P"
private const val WIFI_HIDDEN_KEY = "H"
