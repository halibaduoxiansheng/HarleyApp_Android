package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.resolveDefaultWebsiteId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用SharedPreferences保存首页网站轮播列表。
 *
 * 使用方法：
 * 使用Application Context创建仓库，通过getWebsites读取有序列表，通过saveWebsites整组覆盖保存。
 * 首次安装且从未保存过配置时返回默认的halibaduo.cn；用户主动删除全部网站后会保留空列表，
 * 不会在下次启动时擅自把默认网站加回来。
 *
 * @param context Android上下文，内部会转换为Application Context避免持有Activity。
 */
class WebsiteRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取用户保存的网站，并保持轮播顺序。
     *
     * @return 已保存的网站列表；首次使用或存储内容整体损坏时返回默认网站。
     */
    fun getWebsites(): List<WebsiteShortcut> {
        if (!preferences.contains(KEY_WEBSITES)) {
            return listOf(DEFAULT_WEBSITE)
        }

        val storedJson = preferences.getString(KEY_WEBSITES, null)
            ?: return listOf(DEFAULT_WEBSITE)

        return decodeWebsites(storedJson) ?: listOf(DEFAULT_WEBSITE)
    }

    /**
     * 读取点击底部“网站”时优先打开的网站标识。
     *
     * 使用方法：
     * HarleyApp读取网站列表后调用本函数。已保存的默认网站如果被删除，会自动回退到列表
     * 第一项；用户删除全部网站时返回null。
     *
     * @param websites 当前有效网站列表，默认直接读取本仓库保存的网站。
     *
     * @return 当前可用的默认网站标识；没有网站时返回null。
     */
    fun getDefaultWebsiteId(
        websites: List<WebsiteShortcut> = getWebsites()
    ): String? {
        val storedId = preferences.getString(KEY_DEFAULT_WEBSITE_ID, null)
        return resolveDefaultWebsiteId(websites, storedId)
    }

    /**
     * 保存点击底部“网站”时需要直接打开的默认网站。
     *
     * 使用方法：
     * 用户在首页网站卡片点击“设为默认”时传入该网站id和当前列表；删除默认网站后传入新的
     * 回退网站id；删除全部网站时传null清除设置。
     *
     * @param websiteId 需要设为默认的网站标识；null表示清除默认设置。
     * @param websites 当前有效网站列表，用于拒绝保存已经不存在的网站标识。
     *
     * @return 参数合法且同步写入成功返回true，否则返回false。
     */
    fun setDefaultWebsiteId(
        websiteId: String?,
        websites: List<WebsiteShortcut>
    ): Boolean {
        if (websiteId != null && websites.none { website -> website.id == websiteId }) {
            Log.e(TAG, "Refusing to persist a missing default website")
            return false
        }

        val editor = preferences.edit()
        if (websiteId == null) {
            editor.remove(KEY_DEFAULT_WEBSITE_ID)
        } else {
            editor.putString(KEY_DEFAULT_WEBSITE_ID, websiteId)
        }
        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to persist default website")
        }

        return success
    }

    /**
     * 覆盖保存完整网站列表，列表顺序就是首页轮播顺序。
     *
     * @param websites 需要保存的网站；允许空列表，表示用户已删除全部网站。
     *
     * @return 全部数据合法且同步写入成功时返回true，否则返回false。
     */
    fun saveWebsites(websites: List<WebsiteShortcut>): Boolean {
        if (websites.any { website -> !isValidWebsite(website) }) {
            Log.e(TAG, "Refusing to persist an invalid website shortcut")
            return false
        }
        if (websites.map { website -> website.id }.toSet().size != websites.size) {
            Log.e(TAG, "Refusing to persist duplicate website shortcut IDs")
            return false
        }

        val jsonArray = JSONArray()
        websites.forEach { website ->
            jsonArray.put(
                JSONObject().apply {
                    put(JSON_ID, website.id)
                    put(JSON_TITLE, website.title.trim())
                    put(JSON_URL, normalizeWebsiteUrl(website.url))
                    put(JSON_PALETTE, website.palette.name)
                }
            )
        }

        val success = preferences.edit()
            .putString(KEY_WEBSITES, jsonArray.toString())
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist website shortcuts")
        }

        return success
    }

    /**
     * 把持久化JSON转换为网站列表，并过滤单条异常数据。
     *
     * @param storedJson SharedPreferences中保存的JSON数组文本。
     *
     * @return 解析成功时返回列表；JSON整体损坏或非空数组中没有任何有效网站时返回null。
     */
    private fun decodeWebsites(storedJson: String): List<WebsiteShortcut>? {
        return runCatching {
            val jsonArray = JSONArray(storedJson)
            val websites = buildList {
                val usedIds = mutableSetOf<String>()

                for (index in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.optJSONObject(index) ?: continue
                    val id = jsonObject.optString(JSON_ID).trim()
                    val title = jsonObject.optString(JSON_TITLE).trim()
                    val normalizedUrl = normalizeWebsiteUrl(jsonObject.optString(JSON_URL))
                        ?: continue
                    if (id.isBlank() || title.isBlank() || !usedIds.add(id)) {
                        continue
                    }

                    val palette = WebsitePalette.entries.firstOrNull { candidate ->
                        candidate.name == jsonObject.optString(JSON_PALETTE)
                    } ?: WebsitePalette.OCEAN
                    add(
                        WebsiteShortcut(
                            id = id,
                            title = title,
                            url = normalizedUrl,
                            palette = palette
                        )
                    )
                }
            }

            if (jsonArray.length() > 0 && websites.isEmpty()) null else websites
        }.getOrElse { error ->
            Log.e(TAG, "Failed to decode website shortcuts", error)
            null
        }
    }

    /**
     * 检查单条网站是否具备稳定id、可显示名称和有效HTTPS网址。
     *
     * @param website 待检查的网站。
     *
     * @return 数据可安全保存时返回true，否则返回false。
     */
    private fun isValidWebsite(website: WebsiteShortcut): Boolean {
        return website.id.isNotBlank() &&
            website.title.trim().isNotBlank() &&
            normalizeWebsiteUrl(website.url) != null
    }

    private companion object {
        const val TAG = "WebsiteRepository"
        const val PREFERENCE_NAME = "harley_websites"
        const val KEY_WEBSITES = "websites"
        const val KEY_DEFAULT_WEBSITE_ID = "default_website_id"
        const val JSON_ID = "id"
        const val JSON_TITLE = "title"
        const val JSON_URL = "url"
        const val JSON_PALETTE = "palette"

        val DEFAULT_WEBSITE = WebsiteShortcut(
            id = "default_halibaduo",
            title = "Harley网站",
            url = "https://www.halibaduo.cn",
            palette = WebsitePalette.OCEAN
        )
    }
}
