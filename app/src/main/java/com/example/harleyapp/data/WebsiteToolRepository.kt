package com.example.harleyapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.WebsiteToolSettings
import org.json.JSONObject

/**
 * 按网站保存网页工具箱设置。
 *
 * 使用方法：
 * 使用Application Context创建实例，进入网站时调用[getSettings]，用户改变工具选项后调用
 * [saveSettings]。数据通过RoomBackedPreferences写入Room并保留SharedPreferences兼容副本，
 * 不包含浏览记录、网页内容或搜索词。
 *
 * @param context Android上下文，用于打开应用私有存储。
 */
class WebsiteToolRepository(context: Context) {

    private val preferences = RoomBackedPreferences.create(
        context = context,
        preferenceName = PREFERENCE_NAME
    )

    /**
     * 读取指定网站的工具设置。
     *
     * @param websiteId WebsiteShortcut的稳定网站编号。
     * @return 已规范化设置；无记录或旧数据损坏时返回默认设置。
     */
    fun getSettings(websiteId: String): WebsiteToolSettings {
        if (websiteId.isBlank()) return WebsiteToolSettings()

        return runCatching {
            val allSettings = JSONObject(
                preferences.getString(KEY_SETTINGS, EMPTY_JSON).orEmpty().ifBlank { EMPTY_JSON }
            )
            val item = allSettings.optJSONObject(websiteId) ?: return WebsiteToolSettings()
            decodeSettings(item).normalized()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read website tool settings", error)
            WebsiteToolSettings()
        }
    }

    /**
     * 保存指定网站的全部工具设置。
     *
     * @param websiteId WebsiteShortcut的稳定网站编号。
     * @param settings 页面提交的网页工具设置，保存前会限制数值范围。
     * @return 完整写入Room和兼容副本返回true，否则返回false。
     */
    @Synchronized
    @SuppressLint("UseKtx")
    fun saveSettings(websiteId: String, settings: WebsiteToolSettings): Boolean {
        if (websiteId.isBlank() || websiteId.length > MAX_WEBSITE_ID_LENGTH) return false

        return runCatching {
            val allSettings = JSONObject(
                preferences.getString(KEY_SETTINGS, EMPTY_JSON).orEmpty().ifBlank { EMPTY_JSON }
            )
            allSettings.put(websiteId, encodeSettings(settings.normalized()))
            preferences.edit()
                .putString(KEY_SETTINGS, allSettings.toString())
                .commit()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to persist website tool settings", error)
            false
        }
    }

    /**
     * 把JSON对象转换为当前版本设置。
     *
     * @param json 单个网站的设置对象。
     * @return 缺失字段使用默认值的网页工具设置。
     */
    private fun decodeSettings(json: JSONObject): WebsiteToolSettings {
        val defaults = WebsiteToolSettings()
        return WebsiteToolSettings(
            playbackRate = json.optDouble(JSON_PLAYBACK_RATE, defaults.playbackRate.toDouble())
                .toFloat(),
            blockAutoplay = json.optBoolean(JSON_BLOCK_AUTOPLAY, defaults.blockAutoplay),
            videoMuted = json.optBoolean(JSON_VIDEO_MUTED, defaults.videoMuted),
            videoLoopEnabled = json.optBoolean(JSON_VIDEO_LOOP, defaults.videoLoopEnabled),
            textSelectionEnabled = json.optBoolean(
                JSON_TEXT_SELECTION,
                defaults.textSelectionEnabled
            ),
            nightModeEnabled = json.optBoolean(JSON_NIGHT_MODE, defaults.nightModeEnabled),
            nightOverlayAlpha = json.optDouble(
                JSON_NIGHT_ALPHA,
                defaults.nightOverlayAlpha.toDouble()
            ).toFloat(),
            keepScreenOn = json.optBoolean(JSON_KEEP_SCREEN_ON, defaults.keepScreenOn),
            desktopModeEnabled = json.optBoolean(JSON_DESKTOP_MODE, defaults.desktopModeEnabled),
            textZoomPercent = json.optInt(JSON_TEXT_ZOOM, defaults.textZoomPercent)
        )
    }

    /**
     * 把规范化设置编码为稳定JSON字段。
     *
     * @param settings 待保存设置。
     * @return 可放入网站设置总表的JSON对象。
     */
    private fun encodeSettings(settings: WebsiteToolSettings): JSONObject {
        return JSONObject()
            .put(JSON_PLAYBACK_RATE, settings.playbackRate.toDouble())
            .put(JSON_BLOCK_AUTOPLAY, settings.blockAutoplay)
            .put(JSON_VIDEO_MUTED, settings.videoMuted)
            .put(JSON_VIDEO_LOOP, settings.videoLoopEnabled)
            .put(JSON_TEXT_SELECTION, settings.textSelectionEnabled)
            .put(JSON_NIGHT_MODE, settings.nightModeEnabled)
            .put(JSON_NIGHT_ALPHA, settings.nightOverlayAlpha.toDouble())
            .put(JSON_KEEP_SCREEN_ON, settings.keepScreenOn)
            .put(JSON_DESKTOP_MODE, settings.desktopModeEnabled)
            .put(JSON_TEXT_ZOOM, settings.textZoomPercent)
    }

    companion object {
        const val PREFERENCE_NAME = "harley_website_tools"

        private const val TAG = "WebsiteToolRepository"
        private const val KEY_SETTINGS = "settings_json"
        private const val EMPTY_JSON = "{}"
        private const val MAX_WEBSITE_ID_LENGTH = 128

        private const val JSON_PLAYBACK_RATE = "playback_rate"
        private const val JSON_BLOCK_AUTOPLAY = "block_autoplay"
        private const val JSON_VIDEO_MUTED = "video_muted"
        private const val JSON_VIDEO_LOOP = "video_loop"
        private const val JSON_TEXT_SELECTION = "text_selection"
        private const val JSON_NIGHT_MODE = "night_mode"
        private const val JSON_NIGHT_ALPHA = "night_alpha"
        private const val JSON_KEEP_SCREEN_ON = "keep_screen_on"
        private const val JSON_DESKTOP_MODE = "desktop_mode"
        private const val JSON_TEXT_ZOOM = "text_zoom"
    }
}
