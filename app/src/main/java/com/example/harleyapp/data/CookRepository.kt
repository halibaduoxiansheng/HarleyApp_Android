package com.example.harleyapp.data

import android.content.Context
import android.content.res.AssetManager
import com.example.harleyapp.model.CookCatalog
import com.example.harleyapp.model.CookCategory
import com.example.harleyapp.model.CookContentBlock
import com.example.harleyapp.model.CookIngredient
import com.example.harleyapp.model.CookIngredientRequirement
import com.example.harleyapp.model.CookRecipe
import com.example.harleyapp.model.CookRecipeSection
import com.example.harleyapp.model.CookRequirementKind
import com.example.harleyapp.model.prepareCookRecipeSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Collections

/**
 * 菜谱目录加载或校验失败时抛出的异常。
 *
 * 使用方法：
 * 页面或上层状态容器可在调用[CookRepository.load]时捕获该异常，并向用户显示统一的“菜谱加载失败”状态。
 * 异常消息会尽量指出失败字段，便于开发阶段定位生成目录与数据模型之间的不一致。
 *
 * @param message 具体失败原因，通常包含JSON字段路径。
 * @param cause 触发失败的底层异常；纯字段校验失败时为null。
 */
class CookCatalogException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * 从APK内置assets读取并缓存完整Cook菜谱目录。
 *
 * 使用方法：
 * 使用Application或Activity Context创建实例，在协程中调用[load]。首次调用会切换到IO线程读取
 * `assets/cook/catalog.json`、解析完整分类/食材/菜谱正文，并验证所有非空本地图片引用；后续调用直接返回
 * 同一个只读目录实例。[getCached]可供只允许同步读取缓存的场景使用，但它不会主动触发加载。
 *
 * 同一仓库实例允许被多个协程并发调用。内部Mutex保证目录只解析一次，volatile引用保证已经完成的结果可被其他
 * 线程立即看见。若一次加载失败，失败结果不会进入缓存，下一次调用仍可重新尝试。
 *
 * @param context Android上下文；内部只保存Application Context，避免持有页面生命周期对象。
 */
class CookRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val loadMutex = Mutex()

    @Volatile
    private var cachedCatalog: CookCatalog? = null

    /**
     * 加载完整的内置菜谱目录。
     *
     * 使用方法：
     * 在ViewModel、LaunchedEffect或其他协程环境中调用。首次调用负责磁盘读取、JSON解析和资源校验，后续调用
     * 直接复用缓存，不会重复打开两百余万字节的目录文件。
     *
     * @return 已通过结构、引用和资源路径基本校验的[CookCatalog]。
     * @throws CookCatalogException 当目录缺失、JSON损坏、必需字段无效、引用失效或本地图片不存在时抛出。
     */
    suspend fun load(): CookCatalog {
        cachedCatalog?.let { catalog -> return catalog }

        return withContext(Dispatchers.IO) {
            cachedCatalog ?: loadMutex.withLock {
                cachedCatalog ?: loadFromAssets().also { catalog ->
                    cachedCatalog = catalog
                }
            }
        }
    }

    /**
     * 同步读取已经完成加载的菜谱缓存。
     *
     * 使用方法：
     * 仅适合全局搜索等不能主动等待IO、但可以接受“尚未加载”状态的调用方。需要确保拿到目录时应调用[load]。
     *
     * @return 已缓存的[CookCatalog]；当前实例从未成功加载时返回null。
     */
    fun getCached(): CookCatalog? = cachedCatalog

    /**
     * 打开内置JSON并完成一次完整解析。
     *
     * @return 已解析且所有非空图片路径都能在assets中打开的菜谱目录。
     * @throws CookCatalogException 当文件读取、解析或资源校验失败时抛出。
     */
    private fun loadFromAssets(): CookCatalog {
        val rawJson = try {
            applicationContext.assets.open(COOK_CATALOG_ASSET_PATH).bufferedReader().use { reader ->
                reader.readText()
            }
        } catch (error: Exception) {
            throw CookCatalogException("无法读取内置菜谱目录", error)
        }

        val verifiedAssetPaths = mutableSetOf<String>()
        val catalog = parseCookCatalogJson(rawJson) { assetPath ->
            if (assetPath in verifiedAssetPaths) {
                true
            } else {
                val exists = canOpenAsset(assetPath)
                if (exists) verifiedAssetPaths += assetPath
                exists
            }
        }
        // 全文索引同样在IO线程预热，避免全局搜索第一次输入时处理整份菜谱正文。
        prepareCookRecipeSearchIndex(catalog.recipes)
        return catalog
    }

    /**
     * 检查给定相对路径是否对应一个可打开的APK asset。
     *
     * @param assetPath 已通过路径语法校验、相对于assets根目录的资源路径。
     * @return 资源可成功打开时返回true；资源缺失或读取失败时返回false。
     */
    private fun canOpenAsset(assetPath: String): Boolean {
        return runCatching {
            applicationContext.assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                input.read()
            }
        }.isSuccess
    }
}

/**
 * 把菜谱目录JSON转换为应用内模型，并执行跨字段基本校验。
 *
 * 使用方法：
 * 正常运行时由[CookRepository]调用；测试或目录生成工具的校验代码可传入自定义[assetExists]，验证路径引用而无需
 * Android AssetManager。函数不会跳过坏记录，任何一个必需字段或引用失效都会拒绝整份目录，避免用户看到
 * “部分菜谱悄悄消失”的不完整状态。
 *
 * @param rawJson UTF-8菜谱目录JSON文本。
 * @param assetExists 判断非空本地图片路径是否存在的回调；返回false会终止解析。
 * @return 与JSON顺序一致的完整[CookCatalog]。
 * @throws CookCatalogException 当JSON语法、字段类型、唯一性、引用关系或路径校验失败时抛出。
 */
internal fun parseCookCatalogJson(
    rawJson: String,
    assetExists: (String) -> Boolean = { true }
): CookCatalog {
    if (rawJson.isBlank()) {
        invalidCookCatalog("catalog", "目录内容为空")
    }

    val root = try {
        JSONObject(rawJson)
    } catch (error: Exception) {
        throw CookCatalogException("catalog：JSON格式无效", error)
    }

    return try {
        decodeCookCatalog(root, assetExists)
    } catch (error: CookCatalogException) {
        throw error
    } catch (error: Exception) {
        throw CookCatalogException("catalog：解析菜谱目录失败", error)
    }
}

/**
 * 解析目录根对象，并在模型创建前校验版本及分类、食材、菜谱之间的引用关系。
 *
 * @param root 菜谱目录JSON根对象。
 * @param assetExists 本地图片存在性检查回调。
 * @return 完整的[CookCatalog]。
 */
private fun decodeCookCatalog(
    root: JSONObject,
    assetExists: (String) -> Boolean
): CookCatalog {
    val schemaVersion = root.requireInt(JSON_SCHEMA_VERSION, "catalog.schemaVersion")
    if (schemaVersion != SUPPORTED_COOK_CATALOG_SCHEMA_VERSION) {
        invalidCookCatalog(
            "catalog.schemaVersion",
            "不支持版本$schemaVersion，当前仅支持$SUPPORTED_COOK_CATALOG_SCHEMA_VERSION"
        )
    }

    val sourceRepositoryUrl = root.requireNonBlankString(
        JSON_SOURCE_REPOSITORY_URL,
        "catalog.sourceRepositoryUrl"
    )
    validateHttpsUrl(sourceRepositoryUrl, "catalog.sourceRepositoryUrl")

    val sourceCommit = root.requireNonBlankString(JSON_SOURCE_COMMIT, "catalog.sourceCommit")
    if (!SOURCE_COMMIT_PATTERN.matches(sourceCommit)) {
        invalidCookCatalog("catalog.sourceCommit", "必须是40位Git提交哈希")
    }

    val sourceLicense = root.requireNonBlankString(JSON_SOURCE_LICENSE, "catalog.sourceLicense")
    val categories = decodeCategories(
        root.requireArray(JSON_CATEGORIES, "catalog.categories")
    )
    val ingredients = decodeIngredients(
        root.requireArray(JSON_INGREDIENTS, "catalog.ingredients")
    )
    val categoryById = categories.associateBy(CookCategory::id)
    val ingredientIds = ingredients.mapTo(mutableSetOf(), CookIngredient::id)
    val recipes = decodeRecipes(
        array = root.requireArray(JSON_RECIPES, "catalog.recipes"),
        categoryById = categoryById,
        ingredientIds = ingredientIds,
        assetExists = assetExists
    )

    return CookCatalog(
        schemaVersion = schemaVersion,
        sourceRepositoryUrl = sourceRepositoryUrl,
        sourceCommit = sourceCommit,
        sourceLicense = sourceLicense,
        categories = categories,
        ingredients = ingredients,
        recipes = recipes
    )
}

/**
 * 解析分类数组，并保证稳定ID唯一。
 *
 * @param array `catalog.categories`数组。
 * @return 保持目录原始顺序的分类列表。
 */
private fun decodeCategories(array: JSONArray): List<CookCategory> {
    if (array.length() == 0) invalidCookCatalog("catalog.categories", "分类列表不能为空")

    val seenIds = mutableSetOf<String>()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val path = "catalog.categories[$index]"
            val item = array.requireObject(index, path)
            val id = item.requireNonBlankString(JSON_ID, "$path.id")
            if (!seenIds.add(id)) invalidCookCatalog("$path.id", "分类ID重复：$id")

            val sortOrder = item.requireInt(JSON_SORT_ORDER, "$path.sortOrder")
            if (sortOrder < 0) invalidCookCatalog("$path.sortOrder", "排序值不能小于0")

            add(
                CookCategory(
                    id = id,
                    displayName = item.requireNonBlankString(JSON_DISPLAY_NAME, "$path.displayName"),
                    description = item.requireString(JSON_DESCRIPTION, "$path.description"),
                    sortOrder = sortOrder
                )
            )
        }
    }
}

/**
 * 解析可用于“按现有食材找菜”的标准食材词典，并保证稳定ID唯一。
 *
 * @param array `catalog.ingredients`数组。
 * @return 保持目录原始顺序的食材列表。
 */
private fun decodeIngredients(array: JSONArray): List<CookIngredient> {
    if (array.length() == 0) invalidCookCatalog("catalog.ingredients", "食材列表不能为空")

    val seenIds = mutableSetOf<String>()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val path = "catalog.ingredients[$index]"
            val item = array.requireObject(index, path)
            val id = item.requireNonBlankString(JSON_ID, "$path.id")
            if (!seenIds.add(id)) invalidCookCatalog("$path.id", "食材ID重复：$id")

            add(
                CookIngredient(
                    id = id,
                    displayName = item.requireNonBlankString(JSON_DISPLAY_NAME, "$path.displayName"),
                    aliases = item.requireStringList(
                        key = JSON_ALIASES,
                        path = "$path.aliases",
                        allowEmpty = true
                    ),
                    kind = decodeRequirementKind(
                        item.requireNonBlankString(JSON_KIND, "$path.kind"),
                        "$path.kind"
                    )
                )
            )
        }
    }
}

/**
 * 解析全部菜谱，并校验分类引用、食材引用、正文分节和本地图片。
 *
 * @param array `catalog.recipes`数组。
 * @param categoryById 已校验分类按ID建立的索引。
 * @param ingredientIds 已校验的全部标准食材ID。
 * @param assetExists 本地图片存在性检查回调。
 * @return 保持目录原始顺序的菜谱列表。
 */
private fun decodeRecipes(
    array: JSONArray,
    categoryById: Map<String, CookCategory>,
    ingredientIds: Set<String>,
    assetExists: (String) -> Boolean
): List<CookRecipe> {
    if (array.length() == 0) invalidCookCatalog("catalog.recipes", "菜谱列表不能为空")

    val seenIds = mutableSetOf<String>()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val path = "catalog.recipes[$index]"
            val item = array.requireObject(index, path)
            val id = item.requireNonBlankString(JSON_ID, "$path.id")
            if (!seenIds.add(id)) invalidCookCatalog("$path.id", "菜谱ID重复：$id")

            val categoryId = item.requireNonBlankString(JSON_CATEGORY_ID, "$path.categoryId")
            val category = categoryById[categoryId]
                ?: invalidCookCatalog("$path.categoryId", "引用了不存在的分类：$categoryId")
            val categoryName = item.requireNonBlankString(JSON_CATEGORY_NAME, "$path.categoryName")
            if (categoryName != category.displayName) {
                invalidCookCatalog(
                    "$path.categoryName",
                    "与分类${categoryId}的名称${category.displayName}不一致"
                )
            }

            val coverImageAssetPath = item.requireString(
                JSON_COVER_IMAGE_ASSET_PATH,
                "$path.coverImageAssetPath"
            )
            validateOptionalCookAsset(
                assetPath = coverImageAssetPath,
                path = "$path.coverImageAssetPath",
                assetExists = assetExists
            )

            val sourcePath = item.requireNonBlankString(JSON_SOURCE_PATH, "$path.sourcePath")
            validateSourcePath(sourcePath, "$path.sourcePath")
            val sourceUrl = item.requireNonBlankString(JSON_SOURCE_URL, "$path.sourceUrl")
            validateHttpsUrl(sourceUrl, "$path.sourceUrl")

            add(
                CookRecipe(
                    id = id,
                    name = item.requireNonBlankString(JSON_NAME, "$path.name"),
                    categoryId = categoryId,
                    categoryName = categoryName,
                    summary = item.requireString(JSON_SUMMARY, "$path.summary"),
                    tags = item.requireStringList(JSON_TAGS, "$path.tags", allowEmpty = true),
                    difficulty = item.requireString(JSON_DIFFICULTY, "$path.difficulty"),
                    calories = item.requireString(JSON_CALORIES, "$path.calories"),
                    coverImageAssetPath = coverImageAssetPath,
                    requirements = decodeRequirements(
                        array = item.requireArray(JSON_REQUIREMENTS, "$path.requirements"),
                        path = "$path.requirements",
                        ingredientIds = ingredientIds
                    ),
                    sections = decodeSections(
                        array = item.requireArray(JSON_SECTIONS, "$path.sections"),
                        path = "$path.sections",
                        assetExists = assetExists
                    ),
                    sourcePath = sourcePath,
                    sourceUrl = sourceUrl
                )
            )
        }
    }
}

/**
 * 解析一道菜的原料、调料和工具要求。
 *
 * @param array 当前菜谱的`requirements`数组。
 * @param path 当前数组的完整JSON路径，用于错误定位。
 * @param ingredientIds 目录中可被引用的标准食材ID集合。
 * @return 保持目录原始顺序的要求列表。
 */
private fun decodeRequirements(
    array: JSONArray,
    path: String,
    ingredientIds: Set<String>
): List<CookIngredientRequirement> {
    if (array.length() == 0) invalidCookCatalog(path, "原料要求不能为空")

    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val itemPath = "$path[$index]"
            val item = array.requireObject(index, itemPath)
            val acceptedIds = item.requireStringSet(
                key = JSON_ACCEPTED_INGREDIENT_IDS,
                path = "$itemPath.acceptedIngredientIds"
            )
            acceptedIds.forEach { ingredientId ->
                if (ingredientId !in ingredientIds) {
                    invalidCookCatalog(
                        "$itemPath.acceptedIngredientIds",
                        "引用了不存在的食材ID：$ingredientId"
                    )
                }
            }

            add(
                CookIngredientRequirement(
                    displayName = item.requireNonBlankString(
                        JSON_DISPLAY_NAME,
                        "$itemPath.displayName"
                    ),
                    acceptedIngredientIds = acceptedIds,
                    amount = item.requireString(JSON_AMOUNT, "$itemPath.amount"),
                    optional = item.requireBoolean(JSON_OPTIONAL, "$itemPath.optional"),
                    kind = decodeRequirementKind(
                        item.requireNonBlankString(JSON_KIND, "$itemPath.kind"),
                        "$itemPath.kind"
                    )
                )
            )
        }
    }
}

/**
 * 解析一道菜的正文分节，并保证分节ID在该菜谱内唯一。
 *
 * @param array 当前菜谱的`sections`数组。
 * @param path 当前数组的完整JSON路径，用于错误定位。
 * @param assetExists 本地图片存在性检查回调。
 * @return 保持目录原始顺序的正文分节列表。
 */
private fun decodeSections(
    array: JSONArray,
    path: String,
    assetExists: (String) -> Boolean
): List<CookRecipeSection> {
    if (array.length() == 0) invalidCookCatalog(path, "正文分节不能为空")

    val seenIds = mutableSetOf<String>()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val itemPath = "$path[$index]"
            val item = array.requireObject(index, itemPath)
            val id = item.requireNonBlankString(JSON_ID, "$itemPath.id")
            if (!seenIds.add(id)) invalidCookCatalog("$itemPath.id", "分节ID重复：$id")

            add(
                CookRecipeSection(
                    id = id,
                    title = item.requireNonBlankString(JSON_TITLE, "$itemPath.title"),
                    blocks = decodeContentBlocks(
                        array = item.requireArray(JSON_BLOCKS, "$itemPath.blocks"),
                        path = "$itemPath.blocks",
                        assetExists = assetExists
                    )
                )
            )
        }
    }
}

/**
 * 按类型解析正文块，完整覆盖目录约定的标题、段落、列表、图片、表格、引用、代码和分隔线。
 *
 * @param array 当前分节的`blocks`数组。
 * @param path 当前数组的完整JSON路径，用于错误定位。
 * @param assetExists 本地图片存在性检查回调。
 * @return 保持目录原始顺序的正文块列表。
 */
private fun decodeContentBlocks(
    array: JSONArray,
    path: String,
    assetExists: (String) -> Boolean
): List<CookContentBlock> {
    if (array.length() == 0) invalidCookCatalog(path, "正文块不能为空")

    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val itemPath = "$path[$index]"
            val item = array.requireObject(index, itemPath)
            val type = item.requireNonBlankString(JSON_TYPE, "$itemPath.type")
            add(
                when (type) {
                    BLOCK_HEADING -> decodeHeadingBlock(item, itemPath)
                    BLOCK_PARAGRAPH -> CookContentBlock.Paragraph(
                        text = item.requireNonBlankString(JSON_TEXT, "$itemPath.text")
                    )
                    BLOCK_BULLET_LIST -> CookContentBlock.BulletList(
                        items = item.requireStringList(JSON_ITEMS, "$itemPath.items")
                    )
                    BLOCK_NUMBERED_LIST -> CookContentBlock.NumberedList(
                        items = item.requireStringList(JSON_ITEMS, "$itemPath.items")
                    )
                    BLOCK_IMAGE -> decodeImageBlock(item, itemPath, assetExists)
                    BLOCK_TABLE -> decodeTableBlock(item, itemPath)
                    BLOCK_QUOTE -> CookContentBlock.Quote(
                        // 上游少量Markdown引用仅承担排版占位，因此这里允许空文本并原样保留。
                        text = item.requireString(JSON_TEXT, "$itemPath.text")
                    )
                    BLOCK_CODE -> CookContentBlock.Code(
                        text = item.requireNonBlankString(JSON_TEXT, "$itemPath.text")
                    )
                    BLOCK_DIVIDER -> CookContentBlock.Divider
                    else -> invalidCookCatalog("$itemPath.type", "未知正文块类型：$type")
                }
            )
        }
    }
}

/**
 * 解析标题正文块，并限制Markdown标题层级范围。
 *
 * @param item 标题块JSON对象。
 * @param path 标题块完整JSON路径。
 * @return 可直接渲染的标题块。
 */
private fun decodeHeadingBlock(item: JSONObject, path: String): CookContentBlock.Heading {
    val level = item.requireInt(JSON_LEVEL, "$path.level")
    if (level !in MIN_HEADING_LEVEL..MAX_HEADING_LEVEL) {
        invalidCookCatalog("$path.level", "标题层级必须位于1至6之间")
    }
    return CookContentBlock.Heading(
        text = item.requireNonBlankString(JSON_TEXT, "$path.text"),
        level = level
    )
}

/**
 * 解析图片正文块，并校验本地路径或远端来源至少存在一项。装饰图允许使用空的无障碍说明。
 *
 * @param item 图片块JSON对象。
 * @param path 图片块完整JSON路径。
 * @param assetExists 本地图片存在性检查回调。
 * @return 保留本地资源路径和无障碍描述的图片块。
 */
private fun decodeImageBlock(
    item: JSONObject,
    path: String,
    assetExists: (String) -> Boolean
): CookContentBlock.Image {
    val assetPath = item.requireString(JSON_ASSET_PATH, "$path.assetPath")
    val sourceUrl = item.requireString(JSON_SOURCE_URL, "$path.sourceUrl").trim()
    if (sourceUrl.isNotEmpty()) validateHttpsUrl(sourceUrl, "$path.sourceUrl")
    if (assetPath.isBlank() && sourceUrl.isBlank()) {
        invalidCookCatalog(path, "图片必须至少提供本地资源或远端来源")
    }
    validateOptionalCookAsset(assetPath, "$path.assetPath", assetExists)

    return CookContentBlock.Image(
        assetPath = assetPath,
        contentDescription = item.requireString(JSON_ALT, "$path.alt").trim()
    )
}

/**
 * 解析表格正文块，并保证每一行的列数与表头一致。
 *
 * @param item 表格块JSON对象。
 * @param path 表格块完整JSON路径。
 * @return 列宽关系有效的表格块。
 */
private fun decodeTableBlock(item: JSONObject, path: String): CookContentBlock.Table {
    val headers = item.requireStringList(JSON_HEADERS, "$path.headers")
    val rowArray = item.requireArray(JSON_ROWS, "$path.rows")
    if (rowArray.length() == 0) invalidCookCatalog("$path.rows", "表格数据行不能为空")

    val rows = buildList(rowArray.length()) {
        for (index in 0 until rowArray.length()) {
            val rowPath = "$path.rows[$index]"
            val row = rowArray.requireArray(index, rowPath).readStringList(rowPath)
            if (row.size != headers.size) {
                invalidCookCatalog(
                    rowPath,
                    "列数${row.size}与表头列数${headers.size}不一致"
                )
            }
            add(row)
        }
    }
    return CookContentBlock.Table(headers = headers, rows = rows)
}

/**
 * 把JSON中的英文要求类型转换为强类型枚举。
 *
 * @param rawKind JSON中的`ingredient`、`seasoning`或`tool`。
 * @param path 类型字段的完整JSON路径。
 * @return 与输入值对应的[CookRequirementKind]。
 */
private fun decodeRequirementKind(rawKind: String, path: String): CookRequirementKind {
    return when (rawKind) {
        KIND_INGREDIENT -> CookRequirementKind.INGREDIENT
        KIND_SEASONING -> CookRequirementKind.SEASONING
        KIND_TOOL -> CookRequirementKind.TOOL
        else -> invalidCookCatalog(path, "未知原料类型：$rawKind")
    }
}

/**
 * 校验可选本地图片路径；空字符串表示上游仅提供远端图片或该菜谱没有封面。
 *
 * @param assetPath 相对于assets根目录的图片路径，允许为空。
 * @param path 当前字段的完整JSON路径。
 * @param assetExists 本地图片存在性检查回调。
 * @return 无返回值；路径非空时仅在格式与资源存在性均有效后正常结束。
 */
private fun validateOptionalCookAsset(
    assetPath: String,
    path: String,
    assetExists: (String) -> Boolean
) {
    if (assetPath.isBlank()) return
    validateRelativePath(assetPath, path, requiredPrefix = COOK_IMAGE_ASSET_PREFIX)
    val extension = assetPath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension !in SUPPORTED_IMAGE_EXTENSIONS) {
        invalidCookCatalog(path, "不支持图片格式：$extension")
    }
    if (!assetExists(assetPath)) invalidCookCatalog(path, "本地图片资源不存在：$assetPath")
}

/**
 * 校验菜谱来源文件路径，禁止绝对路径、反斜杠和目录穿越。
 *
 * @param sourcePath 相对于HowToCook仓库根目录的Markdown路径。
 * @param path 当前字段的完整JSON路径。
 * @return 无返回值；来源路径安全且扩展名正确时正常结束。
 */
private fun validateSourcePath(sourcePath: String, path: String) {
    validateRelativePath(sourcePath, path, requiredPrefix = COOK_SOURCE_PATH_PREFIX)
    if (!sourcePath.endsWith(MARKDOWN_EXTENSION, ignoreCase = true)) {
        invalidCookCatalog(path, "菜谱来源必须是Markdown文件")
    }
}

/**
 * 校验受信任的相对路径格式，避免资源引用逃逸到约定目录之外。
 *
 * @param value 待校验路径。
 * @param path 当前字段的完整JSON路径。
 * @param requiredPrefix 路径必须使用的目录前缀。
 * @return 无返回值；路径位于指定目录且不包含危险片段时正常结束。
 */
private fun validateRelativePath(value: String, path: String, requiredPrefix: String) {
    if (value != value.trim()) invalidCookCatalog(path, "路径首尾不能包含空白")
    if (!value.startsWith(requiredPrefix)) {
        invalidCookCatalog(path, "路径必须位于${requiredPrefix}目录下")
    }
    if (value.startsWith('/') || value.contains('\\') || value.contains(':')) {
        invalidCookCatalog(path, "路径必须是使用正斜杠的相对路径")
    }
    val segments = value.split('/')
    if (segments.any { segment -> segment.isBlank() || segment == "." || segment == ".." }) {
        invalidCookCatalog(path, "路径包含空目录或目录穿越片段")
    }
}

/**
 * 校验来源链接是否为带主机名的HTTPS地址。
 *
 * @param value 待校验URL。
 * @param path 当前字段的完整JSON路径。
 * @return 无返回值；URL使用HTTPS并包含主机名时正常结束。
 */
private fun validateHttpsUrl(value: String, path: String) {
    val uri = runCatching { URI(value) }.getOrNull()
        ?: invalidCookCatalog(path, "URL格式无效")
    if (!uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true) || uri.host.isNullOrBlank()) {
        invalidCookCatalog(path, "URL必须使用HTTPS并包含有效主机名")
    }
}

/**
 * 读取必需字符串字段并拒绝空白内容。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前字段的完整JSON路径。
 * @return 去除首尾空白后的非空字符串。
 */
private fun JSONObject.requireNonBlankString(key: String, path: String): String {
    val value = requireString(key, path).trim()
    if (value.isBlank()) invalidCookCatalog(path, "字符串不能为空")
    return value
}

/**
 * 读取必需字符串字段，允许字段值为空字符串并保留原始内容。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前字段的完整JSON路径。
 * @return JSON中未经裁剪的字符串。
 */
private fun JSONObject.requireString(key: String, path: String): String {
    val value = opt(key)
    if (value !is String) invalidCookCatalog(path, "缺少字符串字段或字段类型错误")
    return value
}

/**
 * 读取必需整数，拒绝浮点数、布尔值和字符串形式的数字。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前字段的完整JSON路径。
 * @return JSON中的32位整数。
 */
private fun JSONObject.requireInt(key: String, path: String): Int {
    val value = opt(key)
    if (value !is Int) invalidCookCatalog(path, "缺少整数字段或字段类型错误")
    return value
}

/**
 * 读取必需布尔字段，避免把文本`true`或`false`误当作合法布尔值。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前字段的完整JSON路径。
 * @return JSON中的布尔值。
 */
private fun JSONObject.requireBoolean(key: String, path: String): Boolean {
    val value = opt(key)
    if (value !is Boolean) invalidCookCatalog(path, "缺少布尔字段或字段类型错误")
    return value
}

/**
 * 读取必需JSON数组字段。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前字段的完整JSON路径。
 * @return 对应的[JSONArray]。
 */
private fun JSONObject.requireArray(key: String, path: String): JSONArray {
    return opt(key) as? JSONArray
        ?: invalidCookCatalog(path, "缺少数组字段或字段类型错误")
}

/**
 * 读取字符串数组，校验元素类型和空白值；正文列表中的重复行会按原文保留。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前数组的完整JSON路径。
 * @param allowEmpty true表示允许空数组，false表示至少需要一个字符串。
 * @return 保持JSON顺序的非空字符串列表。
 */
private fun JSONObject.requireStringList(
    key: String,
    path: String,
    allowEmpty: Boolean = false
): List<String> {
    val array = requireArray(key, path)
    val values = array.readStringList(path)
    if (!allowEmpty && values.isEmpty()) invalidCookCatalog(path, "字符串数组不能为空")
    return values
}

/**
 * 读取非空字符串数组并转换为保持插入顺序的集合。
 *
 * @param key 当前JSON对象中的字段名。
 * @param path 当前数组的完整JSON路径。
 * @return 至少包含一个稳定ID的集合。
 */
private fun JSONObject.requireStringSet(key: String, path: String): Set<String> {
    val values = requireStringList(key, path)
    val uniqueValues = LinkedHashSet(values)
    if (uniqueValues.size != values.size) invalidCookCatalog(path, "稳定ID数组不能包含重复项")
    return Collections.unmodifiableSet(uniqueValues)
}

/**
 * 从JSON数组指定位置读取对象。
 *
 * @param index 数组下标。
 * @param path 当前元素的完整JSON路径。
 * @return 指定位置的[JSONObject]。
 */
private fun JSONArray.requireObject(index: Int, path: String): JSONObject {
    return opt(index) as? JSONObject
        ?: invalidCookCatalog(path, "数组元素必须是对象")
}

/**
 * 从JSON数组指定位置读取子数组。
 *
 * @param index 数组下标。
 * @param path 当前元素的完整JSON路径。
 * @return 指定位置的[JSONArray]。
 */
private fun JSONArray.requireArray(index: Int, path: String): JSONArray {
    return opt(index) as? JSONArray
        ?: invalidCookCatalog(path, "数组元素必须是数组")
}

/**
 * 把当前JSON数组解析为非空字符串列表。
 *
 * @param path 当前数组的完整JSON路径。
 * @return 保持JSON顺序的字符串列表。
 */
private fun JSONArray.readStringList(path: String): List<String> {
    return buildList(length()) {
        for (index in 0 until length()) {
            val itemPath = "$path[$index]"
            val value = opt(index)
            if (value !is String) invalidCookCatalog(itemPath, "数组元素必须是字符串")
            val normalized = value.trim()
            if (normalized.isBlank()) invalidCookCatalog(itemPath, "字符串不能为空")
            add(normalized)
        }
    }
}

/**
 * 统一抛出带JSON字段路径的菜谱目录异常。
 *
 * @param path 失败字段或元素的完整JSON路径。
 * @param reason 面向开发定位的中文失败原因。
 * @return 此函数不会正常返回，只用于让校验分支保持清晰。
 */
private fun invalidCookCatalog(path: String, reason: String): Nothing {
    throw CookCatalogException("$path：$reason")
}

private const val COOK_CATALOG_ASSET_PATH = "cook/catalog.json"
private const val COOK_IMAGE_ASSET_PREFIX = "cook/images/"
private const val COOK_SOURCE_PATH_PREFIX = "dishes/"
private const val MARKDOWN_EXTENSION = ".md"
private const val HTTPS_SCHEME = "https"
private const val SUPPORTED_COOK_CATALOG_SCHEMA_VERSION = 1
private const val MIN_HEADING_LEVEL = 1
private const val MAX_HEADING_LEVEL = 6

private const val JSON_SCHEMA_VERSION = "schemaVersion"
private const val JSON_SOURCE_REPOSITORY_URL = "sourceRepositoryUrl"
private const val JSON_SOURCE_COMMIT = "sourceCommit"
private const val JSON_SOURCE_LICENSE = "sourceLicense"
private const val JSON_CATEGORIES = "categories"
private const val JSON_INGREDIENTS = "ingredients"
private const val JSON_RECIPES = "recipes"
private const val JSON_ID = "id"
private const val JSON_DISPLAY_NAME = "displayName"
private const val JSON_DESCRIPTION = "description"
private const val JSON_SORT_ORDER = "sortOrder"
private const val JSON_ALIASES = "aliases"
private const val JSON_KIND = "kind"
private const val JSON_CATEGORY_ID = "categoryId"
private const val JSON_CATEGORY_NAME = "categoryName"
private const val JSON_NAME = "name"
private const val JSON_SUMMARY = "summary"
private const val JSON_TAGS = "tags"
private const val JSON_DIFFICULTY = "difficulty"
private const val JSON_CALORIES = "calories"
private const val JSON_COVER_IMAGE_ASSET_PATH = "coverImageAssetPath"
private const val JSON_REQUIREMENTS = "requirements"
private const val JSON_SECTIONS = "sections"
private const val JSON_SOURCE_PATH = "sourcePath"
private const val JSON_SOURCE_URL = "sourceUrl"
private const val JSON_ACCEPTED_INGREDIENT_IDS = "acceptedIngredientIds"
private const val JSON_AMOUNT = "amount"
private const val JSON_OPTIONAL = "optional"
private const val JSON_TITLE = "title"
private const val JSON_BLOCKS = "blocks"
private const val JSON_TYPE = "type"
private const val JSON_TEXT = "text"
private const val JSON_ITEMS = "items"
private const val JSON_LEVEL = "level"
private const val JSON_ASSET_PATH = "assetPath"
private const val JSON_ALT = "alt"
private const val JSON_HEADERS = "headers"
private const val JSON_ROWS = "rows"

private const val BLOCK_HEADING = "heading"
private const val BLOCK_PARAGRAPH = "paragraph"
private const val BLOCK_BULLET_LIST = "bulletList"
private const val BLOCK_NUMBERED_LIST = "numberedList"
private const val BLOCK_IMAGE = "image"
private const val BLOCK_TABLE = "table"
private const val BLOCK_QUOTE = "quote"
private const val BLOCK_CODE = "code"
private const val BLOCK_DIVIDER = "divider"

private const val KIND_INGREDIENT = "ingredient"
private const val KIND_SEASONING = "seasoning"
private const val KIND_TOOL = "tool"

private val SOURCE_COMMIT_PATTERN = Regex("[0-9a-fA-F]{40}")
private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
