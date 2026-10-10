package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.MAX_WEBSITE_FOLDER_DEPTH
import com.example.harleyapp.model.MAX_BROWSER_BOOKMARK_TITLE_LENGTH
import com.example.harleyapp.model.WebsiteBookmarkSaveResult
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.canPlaceWebsiteFolder
import com.example.harleyapp.model.isValidWebsiteBackgroundFileName
import com.example.harleyapp.model.normalizeWebsiteTags
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.normalizeWebsiteThemeColor
import com.example.harleyapp.model.resolveDefaultWebsiteId
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.util.UUID

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
     * 一次读取网站与收藏夹树，并修复旧版本没有文件夹字段或日期层级的数据。
     *
     * 使用方法：
     * HarleyApp初始化网站状态以及备份恢复后调用本函数。旧网站会保留原顺序、默认开启
     * 首页展示，并归入根目录“未分类”；引用已经不存在文件夹的网站也会安全移回根目录。
     * 旧版一键收藏在根目录创建的合法`yyyy-MM-dd`文件夹会自动整理为“yyyy年/M月/yyyy-MM-dd”，
     * 文件夹本身及其中全部内容保持原id和从属关系，普通文件夹不会参与迁移。
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
        val repairedLibrary = WebsiteLibrary(
            websites = repairedWebsites,
            folders = folders
        )

        val organizedLibrary = organizeRootDateFoldersByYearMonth(
            library = repairedLibrary,
            newFolderId = { UUID.randomUUID().toString() }
        )
        if (organizedLibrary == null) {
            Log.e(TAG, "Failed to organize legacy bookmark date folders")
            if (repairedWebsites != websites && !saveLibrary(repairedLibrary)) {
                Log.e(TAG, "Failed to persist repaired website folder references")
            }
            return repairedLibrary
        }
        if (organizedLibrary != repairedLibrary || repairedWebsites != websites) {
            if (!saveLibrary(organizedLibrary)) {
                Log.e(TAG, "Failed to persist organized website library")
            }
        }

        return organizedLibrary
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
                    sortOrder = website.sortOrder.coerceAtLeast(0),
                    tags = normalizeWebsiteTags(website.tags)
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
     * 把App内WebView或外部浏览器识别出的当前网页追加到现有网站收藏。
     *
     * 使用方法：
     * 调用方传入当前页面标题和地址即可。本函数统一完成HTTP/HTTPS校验、重复网址判断、标题
     * 截断、按设备本地日期创建或复用“年/月/日”文件夹、默认不加入首页以及首次收藏的默认网站设置。
     * 无障碍悬浮球与App内“收藏当前页”必须共用本函数，避免两条入口产生不同的数据规则。
     *
     * @param pageTitle 浏览器公开的页面标题；为空时自动使用网址域名。
     * @param pageUrl 浏览器公开的当前页地址，只接受HTTP或HTTPS。
     *
     * @return [WebsiteBookmarkSaveResult.SAVED]表示已写入；其余状态分别表示重复、网址无效或
     * 本地保存失败。
     */
    fun saveBookmarkedPage(
        pageTitle: String,
        pageUrl: String
    ): WebsiteBookmarkSaveResult = synchronized(WEBSITE_LIBRARY_WRITE_LOCK) {
        val normalizedUrl = normalizeWebsiteUrl(pageUrl)
            ?: return@synchronized WebsiteBookmarkSaveResult.INVALID_URL
        val library = getLibrary()
        if (library.websites.any { website ->
                normalizeWebsiteUrl(website.url) == normalizedUrl
            }
        ) {
            return@synchronized WebsiteBookmarkSaveResult.ALREADY_SAVED
        }

        val normalizedTitle = pageTitle.trim()
            .take(MAX_BROWSER_BOOKMARK_TITLE_LENGTH)
            .ifBlank {
                runCatching { URI(normalizedUrl).host }
                    .getOrNull()
                    .orEmpty()
                    .removePrefix("www.")
                    .ifBlank { "未命名网站" }
            }
        val bookmarkId = UUID.randomUUID().toString()
        val updatedLibrary = addBookmarkedPageToDateHierarchy(
            library = library,
            bookmarkDate = LocalDate.now(),
            newYearFolderId = UUID.randomUUID().toString(),
            newMonthFolderId = UUID.randomUUID().toString(),
            newDateFolderId = UUID.randomUUID().toString(),
            bookmarkId = bookmarkId,
            pageTitle = normalizedTitle,
            normalizedUrl = normalizedUrl
        )
            ?: return@synchronized WebsiteBookmarkSaveResult.SAVE_FAILED
        if (!saveLibrary(updatedLibrary)) {
            return@synchronized WebsiteBookmarkSaveResult.SAVE_FAILED
        }

        if (getDefaultWebsiteId(library.websites) == null) {
            setDefaultWebsiteId(
                websiteId = bookmarkId,
                websites = updatedLibrary.websites
            )
        }

        WebsiteLibraryChangeNotifier.notifyChanged()
        WebsiteBookmarkSaveResult.SAVED
    }

    /**
     * 记录用户从HarleyApp入口成功打开一次网站。
     *
     * 使用方法：
     * 首页卡片、收藏管理页和新标签页在成功创建或填充浏览器标签后传入网站id。函数重新读取最新
     * 网站库并原子增加计数，避免使用界面旧快照覆盖浏览器悬浮球刚写入的收藏；网页刷新、内部链接
     * 跳转、冷启动恢复标签以及创建失败的标签都不应调用本函数。
     *
     * @param websiteId 已成功打开的网站收藏稳定标识。
     * @return 保存成功后的完整网站库；网站不存在或本地保存失败时返回null。
     */
    fun recordWebsiteOpened(websiteId: String): WebsiteLibrary? =
        synchronized(WEBSITE_LIBRARY_WRITE_LOCK) {
            val library = getLibrary()
            val website = library.websites.firstOrNull { current -> current.id == websiteId }
                ?: return@synchronized null
            val nextOpenCount = if (website.openCount == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                website.openCount + 1L
            }
            val updatedLibrary = library.copy(
                websites = library.websites.map { current ->
                    if (current.id == websiteId) {
                        current.copy(openCount = nextOpenCount)
                    } else {
                        current
                    }
                }
            )
            if (!saveLibrary(updatedLibrary)) {
                return@synchronized null
            }
            updatedLibrary
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
                            },
                            isPinned = jsonObject.optBoolean(JSON_IS_PINNED, false),
                            openCount = jsonObject
                                .optLong(JSON_OPEN_COUNT, 0L)
                                .coerceAtLeast(0L),
                            tags = decodeWebsiteTags(jsonObject)
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
                                .coerceAtLeast(0),
                            isPinned = jsonObject.optBoolean(JSON_IS_PINNED, false)
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
                        put(JSON_IS_PINNED, website.isPinned)
                        put(JSON_OPEN_COUNT, website.openCount.coerceAtLeast(0L))
                        put(
                            JSON_TAGS,
                            JSONArray().apply {
                                normalizeWebsiteTags(website.tags).forEach { tag -> put(tag) }
                            }
                        )
                    }
                )
            }
        }
    }

    /**
     * 从单条网站JSON中读取并清理标签数组。
     *
     * 使用方法：
     * [decodeWebsites]完成网站基础字段校验后调用。旧版本没有标签字段、字段类型错误或数组中混有
     * 非字符串值时，只保留能够安全显示的字符串标签，不影响该网站其他收藏信息的恢复。
     *
     * @param jsonObject 当前网站对应的JSON对象。
     *
     * @return 已去除空白、空标签和重复项的标签列表；没有可用标签时返回空列表。
     */
    private fun decodeWebsiteTags(jsonObject: JSONObject): List<String> {
        val jsonTags = jsonObject.optJSONArray(JSON_TAGS) ?: return emptyList()
        val decodedTags = buildList {
            for (index in 0 until jsonTags.length()) {
                (jsonTags.opt(index) as? String)?.let(::add)
            }
        }
        return normalizeWebsiteTags(decodedTags)
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
                        put(JSON_IS_PINNED, folder.isPinned)
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
            isValidWebsiteBackgroundFileName(website.backgroundImageFileName) &&
            website.tags.none { tag -> tag.trim().isBlank() } &&
            website.openCount >= 0L
    }

    private companion object {
        /** 同一进程内的收藏新增和打开计数共用锁，避免并发读改写造成后写覆盖先写。 */
        val WEBSITE_LIBRARY_WRITE_LOCK = Any()

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
        const val JSON_IS_PINNED = "is_pinned"
        const val JSON_OPEN_COUNT = "open_count"
        const val JSON_TAGS = "tags"
        const val JSON_NAME = "name"
        const val JSON_PARENT_ID = "parent_id"
    }
}

/**
 * 把一个经过校验的网页收藏追加到设备当天的“年/月/日”文件夹。
 *
 * 使用方法：
 * [WebsiteRepository.saveBookmarkedPage]完成网址和标题规范化后，传入设备本地日期以及年、月、日
 * 三个候选文件夹标识。函数依次创建或复用根目录“yyyy年”、其下“M月”和其下`yyyy-MM-dd`，
 * 只复用父级位置正确的同名文件夹。新网站追加在日期文件夹现有内容末尾，默认不参与首页轮播。
 *
 * @param library 收藏前的完整网站库。
 * @param bookmarkDate 设备本地日期，用于生成“yyyy年/M月/yyyy-MM-dd”三级名称。
 * @param newYearFolderId 根目录尚无对应年份文件夹时使用的新标识。
 * @param newMonthFolderId 年份文件夹下尚无对应月份文件夹时使用的新标识。
 * @param newDateFolderId 月份文件夹下尚无当天文件夹时使用的新标识。
 * @param bookmarkId 新网站的唯一标识。
 * @param pageTitle 已清理并限制长度的网页标题。
 * @param normalizedUrl 已通过HTTP或HTTPS规范化校验的完整网址。
 *
 * @return 已加入三级日期文件夹和新网站的数据副本；关键标识或文本无效、产生标识冲突时返回null。
 */
internal fun addBookmarkedPageToDateHierarchy(
    library: WebsiteLibrary,
    bookmarkDate: LocalDate,
    newYearFolderId: String,
    newMonthFolderId: String,
    newDateFolderId: String,
    bookmarkId: String,
    pageTitle: String,
    normalizedUrl: String
): WebsiteLibrary? {
    val normalizedTitle = pageTitle.trim()
    if (
        normalizedTitle.isBlank() ||
        bookmarkId.isBlank() ||
        newYearFolderId == bookmarkId ||
        newMonthFolderId == bookmarkId ||
        newDateFolderId == bookmarkId ||
        normalizeWebsiteUrl(normalizedUrl) != normalizedUrl ||
        library.websites.any { website -> website.id == bookmarkId } ||
        library.folders.any { folder -> folder.id == bookmarkId }
    ) {
        return null
    }

    val parentFolders = ensureBookmarkYearMonthFolders(
        library = library,
        bookmarkDate = bookmarkDate,
        newYearFolderId = newYearFolderId,
        newMonthFolderId = newMonthFolderId
    ) ?: return null
    val dateFolderName = bookmarkDate.toString()
    val existingDateFolder = firstWebsiteFolderNamed(
        folders = parentFolders.library.folders,
        parentId = parentFolders.monthFolderId,
        name = dateFolderName
    )
    val dateFolder = existingDateFolder ?: run {
        if (
            !isAvailableWebsiteNodeId(parentFolders.library, newDateFolderId)
        ) {
            return null
        }
        WebsiteFolder(
            id = newDateFolderId,
            name = dateFolderName,
            parentId = parentFolders.monthFolderId,
            sortOrder = nextWebsiteBookmarkOrder(
                parentFolders.library,
                parentId = parentFolders.monthFolderId
            )
        )
    }
    val libraryWithDateFolder = if (existingDateFolder == null) {
        parentFolders.library.copy(folders = parentFolders.library.folders + dateFolder)
    } else {
        parentFolders.library
    }
    val bookmark = WebsiteShortcut(
        id = bookmarkId,
        title = normalizedTitle,
        url = normalizedUrl,
        folderId = dateFolder.id,
        showOnHome = false,
        sortOrder = nextWebsiteBookmarkOrder(libraryWithDateFolder, dateFolder.id)
    )
    return libraryWithDateFolder.copy(
        websites = libraryWithDateFolder.websites + bookmark
    )
}

/**
 * 把旧版本留在根目录的日期文件夹迁移到对应年份和月份文件夹。
 *
 * 使用方法：
 * [WebsiteRepository.getLibrary]读取并修复网站引用后调用。函数只处理根目录中名称可严格解析为
 * `yyyy-MM-dd`的文件夹；日期无效、名称格式不同或已经位于其他层级的文件夹全部保持原位。
 * 每个日期文件夹保留原id，因此其中网站、子文件夹和用户排序关系不会被拆散。
 *
 * @param library 迁移前的完整网站库。
 * @param newFolderId 创建缺失的年份或月份文件夹时调用的标识生成器；每次必须返回全库唯一的非空值。
 *
 * @return 完成迁移的新网站库；不需要迁移时原样返回，标识冲突或目录深度不允许移动时返回null。
 */
internal fun organizeRootDateFoldersByYearMonth(
    library: WebsiteLibrary,
    newFolderId: () -> String
): WebsiteLibrary? {
    val rootDateFolders = library.folders.mapNotNull { folder ->
        if (folder.parentId != null) {
            null
        } else {
            parseWebsiteBookmarkDate(folder.name)?.let { date -> folder to date }
        }
    }.sortedWith(
        compareBy<Pair<WebsiteFolder, LocalDate>> { (folder, _) -> folder.sortOrder }
            .thenBy { (folder, _) -> folder.id }
    )
    if (rootDateFolders.isEmpty()) {
        return library
    }

    var updatedLibrary = library
    rootDateFolders.forEach { (originalDateFolder, bookmarkDate) ->
        val dateFolder = updatedLibrary.folders.firstOrNull { folder ->
            folder.id == originalDateFolder.id
        } ?: return null
        val parentFolders = ensureBookmarkYearMonthFolders(
            library = updatedLibrary,
            bookmarkDate = bookmarkDate,
            newYearFolderId = newFolderId(),
            newMonthFolderId = newFolderId()
        ) ?: return null
        updatedLibrary = parentFolders.library
        if (!canPlaceWebsiteFolder(
                library = updatedLibrary,
                folderId = dateFolder.id,
                candidateParentId = parentFolders.monthFolderId
            )
        ) {
            return null
        }

        val nextOrder = nextWebsiteBookmarkOrder(
            library = updatedLibrary,
            parentId = parentFolders.monthFolderId
        )
        updatedLibrary = updatedLibrary.copy(
            folders = updatedLibrary.folders.map { folder ->
                if (folder.id == dateFolder.id) {
                    folder.copy(
                        parentId = parentFolders.monthFolderId,
                        sortOrder = nextOrder
                    )
                } else {
                    folder
                }
            }
        )
    }

    return updatedLibrary
}

/**
 * 确保指定日期对应的根年份文件夹和直属月份文件夹已经存在。
 *
 * 使用方法：
 * 新增当天收藏和迁移旧日期文件夹时调用。年份名称使用“yyyy年”，月份名称使用不补零的“M月”；
 * 同层有重名文件夹时稳定复用排序最小、id最小的一项，不合并或删除其余用户数据。
 *
 * @param library 当前完整网站库。
 * @param bookmarkDate 需要归档的本地日期。
 * @param newYearFolderId 缺少年份文件夹时使用的候选标识。
 * @param newMonthFolderId 缺少月份文件夹时使用的候选标识。
 *
 * @return 包含所需父目录的数据和月份文件夹id；候选标识无效或冲突时返回null。
 */
private fun ensureBookmarkYearMonthFolders(
    library: WebsiteLibrary,
    bookmarkDate: LocalDate,
    newYearFolderId: String,
    newMonthFolderId: String
): WebsiteBookmarkParentFolders? {
    val yearFolderName = "${bookmarkDate.year}年"
    val existingYearFolder = firstWebsiteFolderNamed(
        folders = library.folders,
        parentId = null,
        name = yearFolderName
    )
    val yearFolder = existingYearFolder ?: run {
        if (!isAvailableWebsiteNodeId(library, newYearFolderId)) {
            return null
        }
        WebsiteFolder(
            id = newYearFolderId,
            name = yearFolderName,
            parentId = null,
            sortOrder = nextWebsiteBookmarkOrder(library, parentId = null)
        )
    }
    val libraryWithYear = if (existingYearFolder == null) {
        library.copy(folders = library.folders + yearFolder)
    } else {
        library
    }

    val monthFolderName = "${bookmarkDate.monthValue}月"
    val existingMonthFolder = firstWebsiteFolderNamed(
        folders = libraryWithYear.folders,
        parentId = yearFolder.id,
        name = monthFolderName
    )
    val monthFolder = existingMonthFolder ?: run {
        if (!isAvailableWebsiteNodeId(libraryWithYear, newMonthFolderId)) {
            return null
        }
        WebsiteFolder(
            id = newMonthFolderId,
            name = monthFolderName,
            parentId = yearFolder.id,
            sortOrder = nextWebsiteBookmarkOrder(libraryWithYear, parentId = yearFolder.id)
        )
    }
    val libraryWithMonth = if (existingMonthFolder == null) {
        libraryWithYear.copy(folders = libraryWithYear.folders + monthFolder)
    } else {
        libraryWithYear
    }

    return WebsiteBookmarkParentFolders(
        library = libraryWithMonth,
        monthFolderId = monthFolder.id
    )
}

/**
 * 在指定父目录中稳定选择一个名称完全相同的文件夹。
 *
 * @param folders 当前全部文件夹。
 * @param parentId 指定父目录id；null表示根目录。
 * @param name 需要完整匹配的文件夹名称。
 *
 * @return 排序值最小、id最小的同名文件夹；不存在时返回null。
 */
private fun firstWebsiteFolderNamed(
    folders: List<WebsiteFolder>,
    parentId: String?,
    name: String
): WebsiteFolder? {
    return folders
        .filter { folder -> folder.parentId == parentId && folder.name == name }
        .minWithOrNull(
            compareBy<WebsiteFolder> { folder -> folder.sortOrder }
                .thenBy { folder -> folder.id }
        )
}

/**
 * 判断候选标识是否可以用于新的网站收藏文件夹。
 *
 * @param library 当前完整网站库。
 * @param candidateId 待检查的文件夹标识。
 *
 * @return 标识非空且没有与现有网站或文件夹冲突时返回true，否则返回false。
 */
private fun isAvailableWebsiteNodeId(
    library: WebsiteLibrary,
    candidateId: String
): Boolean {
    return candidateId.isNotBlank() &&
        library.folders.none { folder -> folder.id == candidateId } &&
        library.websites.none { website -> website.id == candidateId }
}

/**
 * 严格解析系统一键收藏使用的日期文件夹名称。
 *
 * @param folderName 待检查的文件夹名称。
 *
 * @return 名称严格符合`yyyy-MM-dd`且日期真实存在时返回对应日期，否则返回null。
 */
private fun parseWebsiteBookmarkDate(folderName: String): LocalDate? {
    if (!WEBSITE_BOOKMARK_DATE_FOLDER_REGEX.matches(folderName)) {
        return null
    }
    return runCatching { LocalDate.parse(folderName) }.getOrNull()
}

/** 年月父目录准备结果，供新增收藏和旧数据迁移继续使用同一份网站库。 */
private data class WebsiteBookmarkParentFolders(
    /** 已创建或复用年份、月份文件夹的完整网站库。 */
    val library: WebsiteLibrary,
    /** 当前日期对应的月份文件夹稳定标识。 */
    val monthFolderId: String
)

/**
 * 计算指定文件夹末尾可用的网站收藏顺序值。
 *
 * @param library 当前完整网站库。
 * @param parentId 目标文件夹标识；null表示根目录。
 * @return 正常范围内比同层最大顺序大10；接近整数上限时饱和为Int.MAX_VALUE，空层级返回0。
 */
private fun nextWebsiteBookmarkOrder(
    library: WebsiteLibrary,
    parentId: String?
): Int {
    val maximumOrder = buildList {
        library.folders
            .filter { folder -> folder.parentId == parentId }
            .mapTo(this) { folder -> folder.sortOrder }
        library.websites
            .filter { website -> website.folderId == parentId }
            .mapTo(this) { website -> website.sortOrder }
    }.maxOrNull() ?: return 0
    return maximumOrder
        .coerceAtMost(Int.MAX_VALUE - WEBSITE_BOOKMARK_ORDER_STEP)
        .plus(WEBSITE_BOOKMARK_ORDER_STEP)
}

/** 同层收藏顺序之间的固定间隔，便于后续继续追加内容。 */
private const val WEBSITE_BOOKMARK_ORDER_STEP = 10

/** 只识别四位年份、两位月份和两位日期的系统一键收藏旧文件夹。 */
private val WEBSITE_BOOKMARK_DATE_FOLDER_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

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
