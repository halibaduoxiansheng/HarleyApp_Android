package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.MAX_WEBSITE_FOLDER_DEPTH
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.canPlaceWebsiteFolder
import com.example.harleyapp.model.isValidWebsiteBackgroundFileName
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.normalizeWebsiteThemeColor
import com.example.harleyapp.model.resolveDefaultWebsiteId
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用Room文档保存首页网站轮播列表，同时保留SharedPreferences兼容副本。
 *
 * 使用方法：
 * 使用Application Context创建仓库，通过getWebsites读取有序列表，通过saveWebsites整组覆盖保存。
 * 首次安装且从未保存过配置时返回空列表，由用户自行添加HTTP或HTTPS网站；升级用户已经保存的
 * 网站保持不变，不会因删除代码内置网站而丢失自己的收藏。
 *
 * @param context Android上下文，内部会转换为Application Context避免持有Activity。
 */
class WebsiteRepository(context: Context) {

    private val preferences = RoomBackedPreferences.create(
        context = context,
        preferenceName = PREFERENCE_NAME
    )

    /**
     * 读取用户保存的网站，并保持轮播顺序。
     *
     * @return 已保存的网站列表；首次使用或存储内容整体损坏时返回空列表。
     */
    fun getWebsites(): List<WebsiteShortcut> {
        if (!preferences.contains(KEY_WEBSITES)) {
            return initialWebsiteShortcuts()
        }

        val storedJson = preferences.getString(KEY_WEBSITES, null)
            ?: return initialWebsiteShortcuts()

        val decodedWebsites = decodeWebsites(storedJson) ?: return initialWebsiteShortcuts()
        val migratedWebsites = removeObsoleteBuiltInWebsites(decodedWebsites)
        if (migratedWebsites != decodedWebsites && !saveWebsites(migratedWebsites)) {
            Log.e(TAG, "Failed to remove obsolete built-in websites")
        }
        return migratedWebsites
    }

    /**
     * 一次读取网站与收藏夹树，并修复旧版本没有文件夹字段的数据。
     *
     * 使用方法：
     * HarleyApp初始化网站状态以及备份恢复后调用本函数。旧网站会保留原顺序、默认开启
     * 首页展示，并归入根目录“未分类”；引用已经不存在文件夹的网站也会安全移回根目录。
     *
     * @return 可直接交给收藏管理页面的完整网站库。
     */
    fun getLibrary(): WebsiteLibrary {
        val folders = getFolders()
        val folderIds = folders.map { folder -> folder.id }.toSet()
        val websites = getWebsites()
        val repairedWebsites = websites.map { website ->
            if (website.folderId != null && website.folderId !in folderIds) {
                website.copy(folderId = null)
            } else {
                website
            }
        }
        val library = WebsiteLibrary(
            websites = repairedWebsites,
            folders = folders
        )

        if (repairedWebsites != websites && !saveLibrary(library)) {
            Log.e(TAG, "Failed to persist repaired website folder references")
        }

        return library
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

        val success = preferences.edit()
            .putString(KEY_WEBSITES, encodeWebsites(websites).toString())
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist website shortcuts")
        }

        return success
    }

    /**
     * 原子保存完整网站收藏，保证文件夹与网站引用不会只写入一半。
     *
     * 使用方法：
     * 收藏详情页完成增删改、首页开关或拖动排序后传入完整WebsiteLibrary。函数会拒绝
     * 重复id、空名称、孤立父目录、循环层级、超过五层以及无效网址。
     *
     * @param library 需要覆盖保存的完整网站与文件夹集合，允许两个列表都为空。
     *
     * @return 数据合法且同步写入成功返回true，否则返回false。
     */
    fun saveLibrary(library: WebsiteLibrary): Boolean {
        if (!isValidLibrary(library)) {
            Log.e(TAG, "Refusing to persist an invalid website library")
            return false
        }

        val normalizedLibrary = library.copy(
            websites = library.websites.map { website ->
                website.copy(
                    title = website.title.trim(),
                    url = normalizeWebsiteUrl(website.url) ?: website.url,
                    customColorArgb = normalizeWebsiteThemeColor(website.customColorArgb),
                    sortOrder = website.sortOrder.coerceAtLeast(0)
                )
            },
            folders = library.folders.map { folder ->
                folder.copy(
                    name = folder.name.trim(),
                    sortOrder = folder.sortOrder.coerceAtLeast(0)
                )
            }
        )
        val success = preferences.edit()
            .putString(KEY_WEBSITES, encodeWebsites(normalizedLibrary.websites).toString())
            .putString(KEY_FOLDERS, encodeFolders(normalizedLibrary.folders).toString())
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist website library")
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
                            palette = palette,
                            customColorArgb = jsonObject
                                .takeIf { value ->
                                    value.has(JSON_CUSTOM_COLOR_ARGB) &&
                                        !value.isNull(JSON_CUSTOM_COLOR_ARGB)
                                }
                                ?.optLong(JSON_CUSTOM_COLOR_ARGB)
                                ?.let(::normalizeWebsiteThemeColor),
                            backgroundImageFileName = jsonObject
                                .takeIf { value ->
                                    value.has(JSON_BACKGROUND_IMAGE_FILE) &&
                                        !value.isNull(JSON_BACKGROUND_IMAGE_FILE)
                                }
                                ?.optString(JSON_BACKGROUND_IMAGE_FILE)
                                ?.trim()
                                ?.takeIf { fileName ->
                                    isValidWebsiteBackgroundFileName(fileName)
                                },
                            folderId = jsonObject
                                .takeIf { value ->
                                    value.has(JSON_FOLDER_ID) && !value.isNull(JSON_FOLDER_ID)
                                }
                                ?.optString(JSON_FOLDER_ID)
                                ?.trim()
                                ?.takeIf(String::isNotBlank),
                            showOnHome = if (jsonObject.has(JSON_SHOW_ON_HOME)) {
                                jsonObject.optBoolean(JSON_SHOW_ON_HOME, true)
                            } else {
                                true
                            },
                            sortOrder = if (jsonObject.has(JSON_SORT_ORDER)) {
                                jsonObject.optInt(JSON_SORT_ORDER, index).coerceAtLeast(0)
                            } else {
                                index
                            }
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
     * 读取收藏夹JSON；旧版本没有该字段时返回空列表。
     *
     * @return 已保存且结构有效的文件夹列表；数据损坏时返回空列表并记录英文错误日志。
     */
    private fun getFolders(): List<WebsiteFolder> {
        val storedJson = preferences.getString(KEY_FOLDERS, null) ?: return emptyList()
        return decodeFolders(storedJson) ?: emptyList()
    }

    /**
     * 把文件夹JSON转换为数据模型，并过滤重复id或空名称条目。
     *
     * @param storedJson 本地保存的JSON数组文本。
     *
     * @return 解析成功返回文件夹列表；整体JSON损坏时返回null。
     */
    private fun decodeFolders(storedJson: String): List<WebsiteFolder>? {
        return runCatching {
            val jsonArray = JSONArray(storedJson)
            buildList {
                val usedIds = mutableSetOf<String>()
                for (index in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.optJSONObject(index) ?: continue
                    val id = jsonObject.optString(JSON_ID).trim()
                    val name = jsonObject.optString(JSON_NAME).trim()
                    if (id.isBlank() || name.isBlank() || !usedIds.add(id)) {
                        continue
                    }

                    add(
                        WebsiteFolder(
                            id = id,
                            name = name,
                            parentId = jsonObject
                                .takeIf { value ->
                                    value.has(JSON_PARENT_ID) && !value.isNull(JSON_PARENT_ID)
                                }
                                ?.optString(JSON_PARENT_ID)
                                ?.trim()
                                ?.takeIf(String::isNotBlank),
                            sortOrder = jsonObject
                                .optInt(JSON_SORT_ORDER, index)
                                .coerceAtLeast(0)
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to decode website folders", error)
            null
        }
    }

    /**
     * 把网站模型编码为持久化JSON，同时写入兼容升级后的文件夹、首页开关和顺序字段。
     *
     * @param websites 待编码的网站列表。
     *
     * @return 可直接写入本地存储的JSON数组。
     */
    private fun encodeWebsites(websites: List<WebsiteShortcut>): JSONArray {
        return JSONArray().apply {
            websites.forEach { website ->
                put(
                    JSONObject().apply {
                        put(JSON_ID, website.id)
                        put(JSON_TITLE, website.title.trim())
                        put(JSON_URL, normalizeWebsiteUrl(website.url))
                        put(JSON_PALETTE, website.palette.name)
                        put(
                            JSON_CUSTOM_COLOR_ARGB,
                            website.customColorArgb ?: JSONObject.NULL
                        )
                        put(
                            JSON_BACKGROUND_IMAGE_FILE,
                            website.backgroundImageFileName ?: JSONObject.NULL
                        )
                        put(JSON_FOLDER_ID, website.folderId ?: JSONObject.NULL)
                        put(JSON_SHOW_ON_HOME, website.showOnHome)
                        put(JSON_SORT_ORDER, website.sortOrder.coerceAtLeast(0))
                    }
                )
            }
        }
    }

    /**
     * 把文件夹树编码为持久化JSON。
     *
     * @param folders 待编码的文件夹列表。
     *
     * @return 可直接写入本地存储的JSON数组。
     */
    private fun encodeFolders(folders: List<WebsiteFolder>): JSONArray {
        return JSONArray().apply {
            folders.forEach { folder ->
                put(
                    JSONObject().apply {
                        put(JSON_ID, folder.id)
                        put(JSON_NAME, folder.name.trim())
                        put(JSON_PARENT_ID, folder.parentId ?: JSONObject.NULL)
                        put(JSON_SORT_ORDER, folder.sortOrder.coerceAtLeast(0))
                    }
                )
            }
        }
    }

    /**
     * 校验网站收藏的唯一标识、引用关系、目录深度和网址。
     *
     * @param library 待保存的网站收藏。
     *
     * @return 所有数据均可安全持久化时返回true，否则返回false。
     */
    private fun isValidLibrary(library: WebsiteLibrary): Boolean {
        val websiteIds = library.websites.map { website -> website.id }
        val folderIds = library.folders.map { folder -> folder.id }
        if (websiteIds.toSet().size != websiteIds.size ||
            folderIds.toSet().size != folderIds.size ||
            websiteIds.any { id -> id in folderIds }
        ) {
            return false
        }
        if (library.websites.any { website ->
                !isValidWebsite(website) ||
                    (website.folderId != null && website.folderId !in folderIds)
            }
        ) {
            return false
        }
        if (library.folders.any { folder ->
                folder.id.isBlank() ||
                    folder.name.trim().isBlank() ||
                    !canPlaceWebsiteFolder(
                        library = library,
                        folderId = folder.id,
                        candidateParentId = folder.parentId,
                        maxDepth = MAX_WEBSITE_FOLDER_DEPTH
                    )
            }
        ) {
            return false
        }

        return true
    }

    /**
     * 检查单条网站是否具备稳定id、可显示名称和有效HTTP或HTTPS网址。
     *
     * @param website 待检查的网站。
     *
     * @return 数据可安全保存时返回true，否则返回false。
     */
    private fun isValidWebsite(website: WebsiteShortcut): Boolean {
        return website.id.isNotBlank() &&
            website.title.trim().isNotBlank() &&
            normalizeWebsiteUrl(website.url) != null &&
            normalizeWebsiteThemeColor(website.customColorArgb) == website.customColorArgb &&
            isValidWebsiteBackgroundFileName(website.backgroundImageFileName)
    }

    private companion object {
        const val TAG = "WebsiteRepository"
        const val PREFERENCE_NAME = "harley_websites"
        const val KEY_WEBSITES = "websites"
        const val KEY_FOLDERS = "folders"
        const val KEY_DEFAULT_WEBSITE_ID = "default_website_id"
        const val JSON_ID = "id"
        const val JSON_TITLE = "title"
        const val JSON_URL = "url"
        const val JSON_PALETTE = "palette"
        const val JSON_CUSTOM_COLOR_ARGB = "custom_color_argb"
        const val JSON_BACKGROUND_IMAGE_FILE = "background_image_file"
        const val JSON_FOLDER_ID = "folder_id"
        const val JSON_SHOW_ON_HOME = "show_on_home"
        const val JSON_SORT_ORDER = "sort_order"
        const val JSON_NAME = "name"
        const val JSON_PARENT_ID = "parent_id"
    }
}

/**
 * 新安装且尚未创建网站收藏时使用的初始列表。
 *
 * 使用方法：
 * [WebsiteRepository.getWebsites]在本地没有网站配置或整体配置损坏时返回本列表。列表固定为空，
 * 防止未来维护时又把开发者网站、搜书网站或其他推广入口自动写入用户收藏。
 *
 * @return 空的网站列表；用户只能通过页面新增或脚本“一键收藏”创建内容。
 */
internal fun initialWebsiteShortcuts(): List<WebsiteShortcut> = emptyList()

/**
 * 移除旧版本曾自动写入的开发者网站和搜书网站，同时保留用户自己创建的同网址收藏。
 *
 * 使用方法：
 * [WebsiteRepository.getWebsites]解码旧列表后调用。函数只匹配旧内置条目的稳定id，不按标题或
 * 域名删除，所以用户后来手动添加的halibaduo.cn、鸠摩搜书或其他相同网址不会被误删。
 *
 * @param websites 从旧版本本地存储读取的网站列表。
 * @return 删除两个旧内置稳定id后的列表；不存在旧条目时内容和顺序保持不变。
 */
internal fun removeObsoleteBuiltInWebsites(
    websites: List<WebsiteShortcut>
): List<WebsiteShortcut> {
    return websites.filterNot { website -> website.id in OBSOLETE_BUILTIN_WEBSITE_IDS }
}

/** 旧版本自动插入的网站稳定id，仅用于一次升级清理，不再用于创建任何默认收藏。 */
private val OBSOLETE_BUILTIN_WEBSITE_IDS = setOf(
    "default_halibaduo",
    "builtin_jiumo_diary"
)
