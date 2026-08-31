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
 * @param customColorArgb 用户自定义的不透明ARGB主题色；null表示使用预设配色。
 * @param backgroundImageFileName App私有目录中的卡片背景图文件名；null表示纯色渐变背景。
 * @param folderId 所属收藏夹标识；null表示放在根目录的“未分类”区域。
 * @param showOnHome true表示参与首页网站卡片轮播，false表示只在收藏详情中显示。
 * @param sortOrder 同一层级中的显示顺序，数值越小越靠前。
 */
data class WebsiteShortcut(
    val id: String,
    val title: String,
    val url: String,
    val palette: WebsitePalette = WebsitePalette.OCEAN,
    val customColorArgb: Long? = null,
    val backgroundImageFileName: String? = null,
    val folderId: String? = null,
    val showOnHome: Boolean = true,
    val sortOrder: Int = 0
)

/**
 * 把用户输入的十六进制主题色转换成不透明ARGB值。
 *
 * 使用方法：
 * 网站卡片编辑器允许输入“#RRGGBB”或“RRGGBB”。输入有效时保存为Long，始终补充FF透明度；
 * 其他长度、非十六进制字符或空白输入均返回null。
 *
 * @param value 用户输入的颜色文本。
 * @return 0xFF000000至0xFFFFFFFF范围内的不透明ARGB值；格式无效时返回null。
 */
fun parseWebsiteThemeColor(value: String): Long? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { character -> !character.isDigit() &&
            character.lowercaseChar() !in 'a'..'f'
        }
    ) {
        return null
    }
    return normalized.toLongOrNull(radix = 16)?.or(0xFF000000L)
}

/**
 * 把已保存的ARGB主题色转换为编辑框使用的“#RRGGBB”文本。
 *
 * @param argb 已保存的主题色；null表示没有自定义色。
 * @return 大写十六进制文本；未设置自定义色时返回空字符串。
 */
fun formatWebsiteThemeColor(argb: Long?): String {
    if (argb == null) return ""
    return "#%06X".format(Locale.ROOT, argb and 0x00FFFFFFL)
}

/**
 * 规范化外部读取的主题色为不透明32位ARGB。
 *
 * @param argb JSON或页面传入的颜色值。
 * @return 有效32位颜色补齐FF透明度后的值；越界时返回null。
 */
fun normalizeWebsiteThemeColor(argb: Long?): Long? {
    if (argb == null || argb !in 0L..0xFFFFFFFFL) return null
    return (argb and 0x00FFFFFFL) or 0xFF000000L
}

/**
 * 校验网站背景图只能引用App生成的UUID JPEG文件名。
 *
 * @param fileName 网站模型中的背景文件名；null表示没有背景图。
 * @return null或安全UUID JPEG返回true，包含目录和异常扩展名时返回false。
 */
fun isValidWebsiteBackgroundFileName(fileName: String?): Boolean {
    return fileName == null || WEBSITE_BACKGROUND_FILE_PATTERN.matches(fileName)
}

private val WEBSITE_BACKGROUND_FILE_PATTERN = Regex(
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg"
)

/**
 * 表示网站收藏中的一个可嵌套文件夹。
 *
 * 使用方法：
 * 在当前收藏夹内新增文件夹时，把当前收藏夹id写入[parentId]；在根目录新增时传null。
 * 编辑名称或调整同层顺序时保留[id]。界面最多允许五层，避免手机小屏出现过深路径。
 *
 * @param id 文件夹稳定且唯一的标识。
 * @param name 用户自定义的文件夹名称。
 * @param parentId 父文件夹标识；null表示根目录。
 * @param sortOrder 文件夹与同层网站共同使用的显示顺序，数值越小越靠前。
 */
data class WebsiteFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0
)

/**
 * 汇总完整的网站收藏数据，便于一次保存网站与文件夹之间的引用关系。
 *
 * 使用方法：
 * 页面完成新增、编辑、删除或拖动排序后构造新的WebsiteLibrary，并整组交给仓库保存。
 * 不要单独改变文件夹id，否则其中网站和子文件夹的归属关系会失效。
 *
 * @param websites 全部网站收藏，包含首页显示开关和所属文件夹。
 * @param folders 全部自定义文件夹。
 */
data class WebsiteLibrary(
    val websites: List<WebsiteShortcut> = emptyList(),
    val folders: List<WebsiteFolder> = emptyList()
)

/**
 * 网页脚本“一键收藏当前页”的保存结果。
 *
 * 使用方法：
 * HarleyApp完成网址校验、重复检查和本地持久化后返回对应状态；WebsiteScreen根据状态显示明确
 * 中文反馈，避免把重复收藏、无效内部页面和真正的存储失败都混成同一条提示。
 */
enum class WebsiteBookmarkSaveResult {
    SAVED,
    ALREADY_SAVED,
    INVALID_URL,
    SAVE_FAILED
}

/**
 * 取得参与首页自动轮播的网站，并按收藏夹树和同层手动顺序展开。
 *
 * 使用方法：
 * HarleyApp读取WebsiteLibrary后调用本函数，再把结果交给WebsiteCarousel。关闭“首页展示”
 * 的网站仍保留在收藏详情中，但不会挤占首页卡片。
 *
 * @param library 当前完整网站收藏。
 *
 * @return 按树形顺序展开且仅包含showOnHome=true的网站列表。
 */
fun homeCarouselWebsites(library: WebsiteLibrary): List<WebsiteShortcut> {
    val foldersByParent = library.folders.groupBy { folder -> folder.parentId }
    val websitesByParent = library.websites.groupBy { website -> website.folderId }
    val output = mutableListOf<WebsiteShortcut>()
    val visitedFolders = mutableSetOf<String>()

    fun appendFolder(parentId: String?) {
        val nodes = buildList<WebsiteTreeOrderNode> {
            foldersByParent[parentId].orEmpty().forEach { folder ->
                add(
                    WebsiteTreeOrderNode(
                        id = folder.id,
                        order = folder.sortOrder,
                        folder = folder
                    )
                )
            }
            websitesByParent[parentId].orEmpty().forEach { website ->
                add(
                    WebsiteTreeOrderNode(
                        id = website.id,
                        order = website.sortOrder,
                        website = website
                    )
                )
            }
        }.sortedWith(compareBy<WebsiteTreeOrderNode> { node -> node.order }.thenBy { node -> node.id })

        nodes.forEach { node ->
            node.website?.takeIf { website -> website.showOnHome }?.let(output::add)
            node.folder?.takeIf { folder -> visitedFolders.add(folder.id) }?.let { folder ->
                appendFolder(folder.id)
            }
        }
    }

    appendFolder(parentId = null)

    // 损坏数据中的孤立网站也应可恢复显示；正常保存的数据不会进入此分支。
    library.websites
        .filter { website -> website.showOnHome && output.none { current -> current.id == website.id } }
        .sortedWith(compareBy<WebsiteShortcut> { website -> website.sortOrder }.thenBy { website -> website.id })
        .forEach(output::add)

    return output
}

/**
 * 生成指定收藏夹的完整中文路径。
 *
 * 使用方法：
 * 搜索结果和网站编辑框需要说明条目位置时传入folderId。根目录统一显示“未分类”；
 * 遇到损坏的循环引用时会停止继续追溯，避免死循环。
 *
 * @param folders 当前全部文件夹。
 * @param folderId 需要生成路径的文件夹标识；null表示根目录。
 *
 * @return 以“ / ”分隔的文件夹路径。
 */
fun websiteFolderPath(
    folders: List<WebsiteFolder>,
    folderId: String?
): String {
    if (folderId == null) {
        return ROOT_WEBSITE_FOLDER_NAME
    }

    val foldersById = folders.associateBy { folder -> folder.id }
    val names = mutableListOf<String>()
    val visited = mutableSetOf<String>()
    var currentId: String? = folderId
    while (currentId != null && visited.add(currentId)) {
        val folder = foldersById[currentId] ?: break
        names.add(folder.name)
        currentId = folder.parentId
    }

    return if (names.isEmpty()) {
        ROOT_WEBSITE_FOLDER_NAME
    } else {
        names.asReversed().joinToString(separator = " / ")
    }
}

/**
 * 判断网站是否匹配用户输入的模糊搜索词。
 *
 * 使用方法：
 * 搜索框内容改变时，对全部网站调用本函数。函数同时检查名称、网址和文件夹路径，
 * 支持不区分英文大小写的包含匹配；若没有连续命中，也支持字符按顺序出现的宽松匹配。
 *
 * @param website 待检查的网站。
 * @param folderPath 网站所在文件夹的完整路径。
 * @param query 用户输入的搜索文本，允许包含多个空格分隔关键词。
 *
 * @return 每个关键词均能命中任一字段时返回true；空搜索词返回true。
 */
fun websiteMatchesQuery(
    website: WebsiteShortcut,
    folderPath: String,
    query: String
): Boolean {
    val keywords = query.trim()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (keywords.isEmpty()) {
        return true
    }

    val candidates = listOf(website.title, website.url, folderPath).map { value ->
        value.lowercase(Locale.ROOT)
    }
    return keywords.all { keyword ->
        candidates.any { candidate -> fuzzyContains(candidate, keyword) }
    }
}

/**
 * 判断文件夹是否能够移动到指定父文件夹，并同时满足无循环和最多五层限制。
 *
 * 使用方法：
 * 文件夹编辑框展示父目录选项前调用。新增文件夹时folderId传null；编辑时传现有id，
 * 这样可以排除自身、后代目录以及会让最深后代超过限制的位置。
 *
 * @param library 当前完整收藏数据。
 * @param folderId 正在编辑的文件夹id；新增时传null。
 * @param candidateParentId 候选父文件夹id；null表示移动到根目录。
 * @param maxDepth 允许的最大文件夹层数，默认五层。
 *
 * @return 该父目录选择安全可用时返回true，否则返回false。
 */
fun canPlaceWebsiteFolder(
    library: WebsiteLibrary,
    folderId: String?,
    candidateParentId: String?,
    maxDepth: Int = MAX_WEBSITE_FOLDER_DEPTH
): Boolean {
    if (folderId != null && folderId == candidateParentId) {
        return false
    }

    val foldersById = library.folders.associateBy { folder -> folder.id }
    if (candidateParentId != null && candidateParentId !in foldersById) {
        return false
    }

    val descendantIds = folderId?.let { currentId ->
        websiteFolderDescendantIds(library.folders, currentId)
    }.orEmpty()
    if (candidateParentId != null && candidateParentId in descendantIds) {
        return false
    }

    val parentDepth = websiteFolderDepth(library.folders, candidateParentId)
    val subtreeDepth = folderId?.let { currentId ->
        websiteFolderSubtreeDepth(library.folders, currentId)
    } ?: 1
    return parentDepth + subtreeDepth <= maxDepth
}

/**
 * 把已有网站和文件夹批量移动到指定文件夹末尾。
 *
 * 使用方法：
 * 收藏管理页的“添加已有内容”对话框在用户确认后调用。网站只改变所属文件夹；文件夹会连同
 * 整个子树一起移动。若同时选择父文件夹和它的后代，只有最外层父文件夹执行移动，后代继续
 * 保持原层级；同时选中该子树中的网站也不会被单独拆出。所有待移动内容会按稳定顺序追加到
 * 目标文件夹末尾，不覆盖目标目录原有排序。
 *
 * @param library 当前完整网站收藏数据。
 * @param targetFolderId 目标文件夹id；null表示移动到根目录“未分类”。
 * @param websiteIds 用户选中的已有网站id集合。
 * @param folderIds 用户选中的已有文件夹id集合。
 *
 * @return 移动完成的新网站库；目标无效、选中项不存在、形成循环或超过五层时返回null。
 */
fun moveWebsiteNodesToFolder(
    library: WebsiteLibrary,
    targetFolderId: String?,
    websiteIds: Set<String>,
    folderIds: Set<String>
): WebsiteLibrary? {
    val foldersById = library.folders.associateBy { folder -> folder.id }
    val websitesById = library.websites.associateBy { website -> website.id }
    if (targetFolderId != null && targetFolderId !in foldersById) {
        return null
    }
    if (folderIds.any { folderId -> folderId !in foldersById } ||
        websiteIds.any { websiteId -> websiteId !in websitesById }
    ) {
        return null
    }

    val topLevelFolderIds = folderIds.filterTo(linkedSetOf()) { folderId ->
        !websiteFolderHasSelectedAncestor(
            foldersById = foldersById,
            folderId = folderId,
            selectedFolderIds = folderIds
        )
    }
    if (topLevelFolderIds.any { folderId ->
            !canPlaceWebsiteFolder(
                library = library,
                folderId = folderId,
                candidateParentId = targetFolderId
            )
        }
    ) {
        return null
    }

    val selectedSubtreeIds = topLevelFolderIds.flatMapTo(mutableSetOf()) { folderId ->
        websiteFolderDescendantIds(library.folders, folderId) + folderId
    }
    val movableFolderIds = topLevelFolderIds.filterTo(linkedSetOf()) { folderId ->
        foldersById.getValue(folderId).parentId != targetFolderId
    }
    val movableWebsiteIds = websiteIds.filterTo(linkedSetOf()) { websiteId ->
        val website = websitesById.getValue(websiteId)
        website.folderId != targetFolderId && website.folderId !in selectedSubtreeIds
    }
    if (movableFolderIds.isEmpty() && movableWebsiteIds.isEmpty()) {
        return library
    }

    val lastTargetOrder = buildList {
        library.folders
            .filter { folder -> folder.parentId == targetFolderId && folder.id !in movableFolderIds }
            .mapTo(this) { folder -> folder.sortOrder }
        library.websites
            .filter { website ->
                website.folderId == targetFolderId && website.id !in movableWebsiteIds
            }
            .mapTo(this) { website -> website.sortOrder }
    }.maxOrNull() ?: -10
    var nextOrder = lastTargetOrder + 10
    val folderOrders = mutableMapOf<String, Int>()
    library.folders.forEach { folder ->
        if (folder.id in movableFolderIds) {
            folderOrders[folder.id] = nextOrder
            nextOrder += 10
        }
    }
    val websiteOrders = mutableMapOf<String, Int>()
    library.websites.forEach { website ->
        if (website.id in movableWebsiteIds) {
            websiteOrders[website.id] = nextOrder
            nextOrder += 10
        }
    }

    return library.copy(
        folders = library.folders.map { folder ->
            folderOrders[folder.id]?.let { order ->
                folder.copy(parentId = targetFolderId, sortOrder = order)
            } ?: folder
        },
        websites = library.websites.map { website ->
            websiteOrders[website.id]?.let { order ->
                website.copy(folderId = targetFolderId, sortOrder = order)
            } ?: website
        }
    )
}

/**
 * 判断待移动文件夹是否已有另一个选中的祖先文件夹。
 *
 * @param foldersById 当前全部文件夹的id索引。
 * @param folderId 待判断的文件夹id。
 * @param selectedFolderIds 用户本次选中的全部文件夹id。
 *
 * @return 任一祖先也被选中时返回true；无选中祖先或父引用异常时返回false。
 */
private fun websiteFolderHasSelectedAncestor(
    foldersById: Map<String, WebsiteFolder>,
    folderId: String,
    selectedFolderIds: Set<String>
): Boolean {
    val visited = mutableSetOf<String>()
    var parentId = foldersById[folderId]?.parentId
    while (parentId != null && visited.add(parentId)) {
        if (parentId in selectedFolderIds) return true
        parentId = foldersById[parentId]?.parentId
    }
    return false
}

/**
 * 收集某文件夹下的全部后代文件夹标识，不包含传入文件夹本身。
 *
 * @param folders 当前全部文件夹。
 * @param folderId 起始文件夹标识。
 *
 * @return 后代文件夹id集合；没有子文件夹时返回空集合。
 */
fun websiteFolderDescendantIds(
    folders: List<WebsiteFolder>,
    folderId: String
): Set<String> {
    val childrenByParent = folders.groupBy { folder -> folder.parentId }
    val output = mutableSetOf<String>()
    val pending = ArrayDeque<String>()
    pending.add(folderId)
    while (pending.isNotEmpty()) {
        val currentId = pending.removeFirst()
        childrenByParent[currentId].orEmpty().forEach { child ->
            if (output.add(child.id)) {
                pending.add(child.id)
            }
        }
    }
    output.remove(folderId)
    return output
}

/**
 * 计算某父文件夹所在层级深度，根目录深度为0。
 *
 * @param folders 当前全部文件夹。
 * @param folderId 待计算文件夹id；null表示根目录。
 *
 * @return 从根目录到该文件夹的层数；异常循环引用会在首次重复时停止。
 */
fun websiteFolderDepth(
    folders: List<WebsiteFolder>,
    folderId: String?
): Int {
    val foldersById = folders.associateBy { folder -> folder.id }
    val visited = mutableSetOf<String>()
    var depth = 0
    var currentId = folderId
    while (currentId != null && visited.add(currentId)) {
        val folder = foldersById[currentId] ?: break
        depth += 1
        currentId = folder.parentId
    }
    return depth
}

/** 网站收藏根目录在界面中的固定名称。 */
const val ROOT_WEBSITE_FOLDER_NAME = "未分类"

/** 手机端允许创建的最大收藏夹层数。 */
const val MAX_WEBSITE_FOLDER_DEPTH = 5

/** 用于首页树形展开的内部节点，避免把界面类型泄漏到数据模型。 */
private data class WebsiteTreeOrderNode(
    val id: String,
    val order: Int,
    val folder: WebsiteFolder? = null,
    val website: WebsiteShortcut? = null
)

/**
 * 执行包含匹配或字符顺序匹配。
 *
 * @param source 已转换为小写的候选文本。
 * @param query 已转换为小写的关键词。
 *
 * @return query连续出现或其中字符依次出现在source中时返回true。
 */
private fun fuzzyContains(source: String, query: String): Boolean {
    if (source.contains(query)) {
        return true
    }

    var queryIndex = 0
    source.forEach { character ->
        if (queryIndex < query.length && character == query[queryIndex]) {
            queryIndex += 1
        }
    }
    return queryIndex == query.length
}

/**
 * 计算一个文件夹子树占用的最大层数，文件夹自身计为一层。
 *
 * @param folders 当前全部文件夹。
 * @param folderId 子树根文件夹标识。
 *
 * @return 子树最大层数；没有子文件夹时返回1。
 */
private fun websiteFolderSubtreeDepth(
    folders: List<WebsiteFolder>,
    folderId: String
): Int {
    val childrenByParent = folders.groupBy { folder -> folder.parentId }
    val visited = mutableSetOf<String>()

    fun depth(currentId: String): Int {
        if (!visited.add(currentId)) {
            return 0
        }
        val childDepth = childrenByParent[currentId].orEmpty()
            .maxOfOrNull { child -> depth(child.id) }
            ?: 0
        visited.remove(currentId)
        return childDepth + 1
    }

    return depth(folderId).coerceAtLeast(1)
}

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
