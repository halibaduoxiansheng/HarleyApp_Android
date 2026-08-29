package com.example.harleyapp.model

/**
 * 单个网站使用的网页工具设置。
 *
 * 使用方法：
 * [WebsiteToolRepository][com.example.harleyapp.data.WebsiteToolRepository]按网站id读取和保存本对象，
 * WebsiteScreen再把设置同步给WebView原生能力和网页脚本。所有字段只保存在本机。
 *
 * @param playbackRate HTML5音视频播放倍速，允许0.25到5.00。
 * @param blockAutoplay 是否移除新出现音视频的autoplay属性并暂停自动播放。
 * @param videoMuted 是否让页面内音视频保持静音。
 * @param videoLoopEnabled 是否让页面内音视频循环播放。
 * @param textSelectionEnabled 是否通过样式允许网页文字选择和复制。
 * @param nightModeEnabled 是否启用网页夜间遮罩。
 * @param nightOverlayAlpha 夜间遮罩不透明度，允许0.05到0.70。
 * @param keepScreenOn 是否在当前网站打开期间保持屏幕常亮。
 * @param desktopModeEnabled 是否使用桌面浏览器User-Agent重新加载网页。
 * @param textZoomPercent WebView文字缩放百分比，允许75到200。
 */
data class WebsiteToolSettings(
    val playbackRate: Float = DEFAULT_WEBSITE_PLAYBACK_RATE,
    val blockAutoplay: Boolean = false,
    val videoMuted: Boolean = false,
    val videoLoopEnabled: Boolean = false,
    val textSelectionEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val nightOverlayAlpha: Float = DEFAULT_NIGHT_OVERLAY_ALPHA,
    val keepScreenOn: Boolean = false,
    val desktopModeEnabled: Boolean = false,
    val textZoomPercent: Int = DEFAULT_TEXT_ZOOM_PERCENT
) {

    /**
     * 把外部或旧版本数据限制到当前安全范围。
     *
     * @return 可直接保存和应用到网页的规范化设置。
     */
    fun normalized(): WebsiteToolSettings {
        return copy(
            playbackRate = playbackRate.coerceIn(
                MIN_WEBSITE_PLAYBACK_RATE,
                MAX_WEBSITE_PLAYBACK_RATE
            ),
            nightOverlayAlpha = nightOverlayAlpha.coerceIn(
                MIN_NIGHT_OVERLAY_ALPHA,
                MAX_NIGHT_OVERLAY_ALPHA
            ),
            textZoomPercent = textZoomPercent.coerceIn(
                MIN_TEXT_ZOOM_PERCENT,
                MAX_TEXT_ZOOM_PERCENT
            )
        )
    }
}

const val MIN_WEBSITE_PLAYBACK_RATE = 0.25f
const val MAX_WEBSITE_PLAYBACK_RATE = 5.00f
const val DEFAULT_WEBSITE_PLAYBACK_RATE = 1.00f
const val WEBSITE_PLAYBACK_RATE_STEP = 0.05f

const val MIN_NIGHT_OVERLAY_ALPHA = 0.05f
const val MAX_NIGHT_OVERLAY_ALPHA = 0.70f
const val DEFAULT_NIGHT_OVERLAY_ALPHA = 0.28f

const val MIN_TEXT_ZOOM_PERCENT = 75
const val MAX_TEXT_ZOOM_PERCENT = 200
const val DEFAULT_TEXT_ZOOM_PERCENT = 100

