package com.example.harleyapp.model

import java.time.LocalDate
import java.util.Locale

/**
 * HowToCook菜谱所属的展示分类。
 *
 * 使用方法：
 * 数据生成器根据上游目录创建分类，页面使用[id]筛选菜谱并使用[displayName]显示中文名称。
 * 分类使用数据类而不是固定枚举，后续同步上游新增菜系时无需修改App源码。
 *
 * @param id 跨版本保持稳定的分类标识，建议使用小写ASCII字符和短横线。
 * @param displayName 面向用户显示的分类名称。
 * @param description 分类的简短说明；上游没有说明时为空字符串。
 * @param sortOrder 分类在筛选栏中的默认顺序，数值越小越靠前。
 */
data class CookCategory(
    val id: String,
    val displayName: String,
    val description: String = "",
    val sortOrder: Int = 0
)

/**
 * 食材目录中的一个可选择项目。
 *
 * 使用方法：
 * Cook页面把完整目录交给用户勾选，菜谱需求通过稳定[id]引用本项目。[aliases]只用于关键词搜索和
 * 展示提示，不参与“家里已有食材”的模糊判断；可用性判断只比较明确的稳定ID，避免“油”误匹配
 * “蚝油”等名称包含关系。
 *
 * @param id 跨版本稳定的规范食材ID，建议使用小写ASCII字符和短横线。
 * @param displayName 面向用户显示的食材名称。
 * @param aliases 常见别名，例如“番茄”可以包含“西红柿”；别名仅用于搜索。
 * @param kind 该项目属于主食材、调料还是工具。
 */
data class CookIngredient(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val kind: CookRequirementKind = CookRequirementKind.INGREDIENT
)

/**
 * 菜谱需求项目的业务类型。
 *
 * [INGREDIENT]表示会决定菜谱是否可直接制作的主食材；[SEASONING]表示同样需要满足、但允许由
 * 用户配置的常备调料自动覆盖；[TOOL]只用于做法详情提示，不参与食材齐全度和缺失数量计算。
 */
enum class CookRequirementKind {
    INGREDIENT,
    SEASONING,
    TOOL
}

/**
 * 一道菜对某项食材、调料或工具的结构化需求。
 *
 * 使用方法：
 * 数据生成器应把同一需求允许替换的项目全部写入[acceptedIngredientIds]。例如“番茄或圣女果”可以
 * 同时接受`tomato`和`cherry-tomato`，匹配函数只要命中任意一个稳定ID即视为满足。工具和可选项
 * 会正常显示在详情页，但不会让菜谱被判定为“缺少食材”。
 *
 * @param displayName 做法详情和缺失提示中显示的原始需求名称。
 * @param acceptedIngredientIds 能满足本需求的一个或多个规范食材ID。
 * @param amount 上游菜谱中的用量文本；无法结构化时保留原文，未知时为空字符串。
 * @param optional true表示可以省略且不影响“可直接做”；false表示需要参与齐全度判断。
 * @param kind 需求属于主食材、调料还是工具。
 */
data class CookIngredientRequirement(
    val displayName: String,
    val acceptedIngredientIds: Set<String>,
    val amount: String = "",
    val optional: Boolean = false,
    val kind: CookRequirementKind = CookRequirementKind.INGREDIENT
)

/**
 * 菜谱详情中的一个可按原始顺序渲染的内容块。
 *
 * 使用方法：
 * 生成器把上游Markdown转换成这些安全、结构化的数据类型，Compose页面逐块渲染，不需要使用
 * WebView执行HTML。不同类型均只保存展示数据，不包含脚本或任意可执行内容。
 */
sealed interface CookContentBlock {

    /**
     * 一个层级标题。
     *
     * @param text 标题文字。
     * @param level Markdown标题层级，建议限制在2到6之间。
     */
    data class Heading(
        val text: String,
        val level: Int
    ) : CookContentBlock

    /**
     * 一个普通正文段落。
     *
     * @param text 保留必要换行的正文文字。
     */
    data class Paragraph(
        val text: String
    ) : CookContentBlock

    /**
     * 一个无序列表。
     *
     * @param items 按上游原始顺序排列的列表文字。
     */
    data class BulletList(
        val items: List<String>
    ) : CookContentBlock

    /**
     * 一个有序步骤列表。
     *
     * @param items 按实际操作顺序排列的步骤文字。
     */
    data class NumberedList(
        val items: List<String>
    ) : CookContentBlock

    /**
     * 一张随App发布的本地菜谱图片。
     *
     * @param assetPath 相对于Android assets根目录的安全路径。
     * @param contentDescription 面向无障碍服务的图片说明；装饰图可为空字符串。
     */
    data class Image(
        val assetPath: String,
        val contentDescription: String
    ) : CookContentBlock

    /**
     * 一个Markdown表格转换后的结构化表格。
     *
     * @param headers 按列排列的表头文字。
     * @param rows 按行排列的单元格；生成阶段应确保每行列数与表头一致。
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : CookContentBlock

    /**
     * 一段需要与普通正文区分显示的引用或安全提示。
     *
     * @param text 引用原文。
     */
    data class Quote(
        val text: String
    ) : CookContentBlock

    /**
     * 一段需要保留换行和等宽排版的公式或代码文字。
     *
     * @param text 上游代码围栏内的原始文字。
     */
    data class Code(
        val text: String
    ) : CookContentBlock

    /** 用于保留上游 Markdown 中章节内的水平分隔线。 */
    data object Divider : CookContentBlock
}

/**
 * 菜谱详情中的一个命名章节。
 *
 * 使用方法：
 * “必备原料和工具”“计算”“操作”“附加内容”等上游章节分别保存为独立对象，页面按照列表顺序
 * 展示，避免简化Markdown时丢失说明、表格或步骤图片。
 *
 * @param id 菜谱内部稳定的章节标识。
 * @param title 章节标题。
 * @param blocks 按上游原始顺序排列的结构化内容块。
 */
data class CookRecipeSection(
    val id: String,
    val title: String,
    val blocks: List<CookContentBlock>
)

/**
 * 一道可以搜索、匹配食材并进入详情页查看的完整菜谱。
 *
 * 使用方法：
 * [id]用于Compose列表、收藏、最近浏览和全局搜索跳转，任何版本都不应复用给另一道菜。[categoryId]
 * 必须能在[CookCatalog.categories]中找到；[requirements]提供严谨的食材匹配，[sections]保留完整做法。
 *
 * @param id 跨版本稳定的菜谱标识。
 * @param name 菜名。
 * @param categoryId 所属分类的稳定ID。
 * @param categoryName 所属分类的中文名称，用于列表和无需额外查表的搜索摘要。
 * @param summary 菜谱的简短介绍或难度说明。
 * @param tags 可供搜索和筛选使用的补充关键词。
 * @param difficulty 上游给出的预估烹饪难度；未提供时为空字符串。
 * @param calories 上游给出的预估卡路里；未提供时为空字符串。
 * @param coverImageAssetPath 列表卡片和详情头图使用的本地assets路径。
 * @param requirements 结构化食材、调料和工具需求。
 * @param sections 按原文顺序排列的完整做法章节。
 * @param sourcePath 菜谱在固定上游提交中的相对Markdown路径。
 * @param sourceUrl 可供用户查看原始来源的HTTPS地址。
 */
data class CookRecipe(
    val id: String,
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val difficulty: String = "",
    val calories: String = "",
    val coverImageAssetPath: String,
    val requirements: List<CookIngredientRequirement>,
    val sections: List<CookRecipeSection>,
    val sourcePath: String,
    val sourceUrl: String
) {
    /** 缓存不含长正文的规范搜索语料，避免每次输入都重复整理相同字段。 */
    internal val normalizedSummarySearchCorpus: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildCookRecipeSummaryCorpus(this)
    }

    /** 缓存含全部正文块的规范搜索语料，首次由仓库在后台线程预热。 */
    internal val normalizedFullSearchCorpus: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildCookRecipeFullCorpus(this, normalizedSummarySearchCorpus)
    }
}

/**
 * 随APK发布的一次完整HowToCook数据快照。
 *
 * 使用方法：
 * 仓库解析`assets/cook/catalog.json`后返回本对象。界面从[categories]建立筛选栏，从[ingredients]
 * 建立用户食材选择器，从[recipes]展示和搜索菜谱；来源字段用于许可审计与关于页面归属说明。
 *
 * @param schemaVersion 本App菜谱JSON结构版本。
 * @param sourceRepositoryUrl 固定上游仓库地址。
 * @param sourceCommit 生成本快照的完整上游提交哈希。
 * @param sourceLicense 上游内容随包保留的许可证名称。
 * @param categories 本快照包含的全部菜谱分类。
 * @param ingredients 可供用户选择并被菜谱需求引用的规范食材目录。
 * @param recipes 本快照包含的全部正式菜谱。
 */
data class CookCatalog(
    val schemaVersion: Int,
    val sourceRepositoryUrl: String,
    val sourceCommit: String,
    val sourceLicense: String,
    val categories: List<CookCategory>,
    val ingredients: List<CookIngredient>,
    val recipes: List<CookRecipe>
)

/**
 * 菜谱关键词搜索和分类筛选条件。
 *
 * @param query 用户输入的关键词；支持用空格分隔多个词，所有词都命中才保留结果。
 * @param categoryId 需要保留的分类ID；null或空白表示全部分类。
 */
data class CookRecipeSearchFilter(
    val query: String = "",
    val categoryId: String? = null
)

/**
 * 一道菜与用户现有食材的严谨匹配结果。
 *
 * 使用方法：
 * [matchCookRecipesByIngredients]为每道菜生成本对象。页面可使用[isDirectlyCookable]分成“可直接做”
 * 和“还缺食材”两组，并把[missingRequirements]中的显示名称直接展示给用户。
 *
 * @param recipe 被评估的菜谱。
 * @param isDirectlyCookable 所有必需主食材和调料都已满足时为true。
 * @param matchedRequirements 由用户所选食材或常备调料满足的必需需求。
 * @param missingRequirements 尚未满足的必需主食材或调料需求。
 * @param automaticallyCoveredSeasonings 只由常备调料集合自动满足、而非用户本次主动选择的需求。
 * @param ignoredRequirements 不参与缺失判断的工具和可选项目。
 * @param requiredRequirementCount 本菜谱参与齐全度判断的需求总数。
 * @param matchedRequirementCount 已满足的必需需求数量。
 * @param coverageRatio 已满足数量除以必需数量；没有必需需求时为1.0。
 */
data class CookIngredientMatchResult(
    val recipe: CookRecipe,
    val isDirectlyCookable: Boolean,
    val matchedRequirements: List<CookIngredientRequirement>,
    val missingRequirements: List<CookIngredientRequirement>,
    val automaticallyCoveredSeasonings: List<CookIngredientRequirement>,
    val ignoredRequirements: List<CookIngredientRequirement>,
    val requiredRequirementCount: Int,
    val matchedRequirementCount: Int,
    val coverageRatio: Double
)

/**
 * 从目录中取得“默认常备基础调料”能够自动覆盖的规范食材ID。
 *
 * 使用方法：
 * Cook页面开启默认常备开关时调用本函数，并把返回值传给[matchCookRecipesByIngredients]。
 * 本函数只按稳定ID白名单精确匹配水、盐、白糖和食用油，不读取显示名或别名；这样“糖”这个别名
 * 不会误把冰糖、绵白糖等特殊用料也当作用户家中默认存在。
 *
 * @param ingredients 当前菜谱目录提供的完整规范食材列表。
 * 目录级[kind][CookIngredient.kind]可能因同一种材料在少数菜谱中被当作主料而提升为INGREDIENT；
 * 是否允许自动覆盖最终仍由每条[CookIngredientRequirement.kind]严格限制为SEASONING，因此这里不按
 * 目录级类型过滤。
 *
 * @return 目录中真实存在且属于基础白名单的稳定ID集合。
 */
fun resolveDefaultCookPantrySeasoningIds(
    ingredients: List<CookIngredient>
): Set<String> {
    return ingredients.asSequence()
        .map { ingredient -> normalizeStableId(ingredient.id) }
        .filter { ingredientId -> ingredientId in DEFAULT_COOK_PANTRY_SEASONING_IDS }
        .toCollection(linkedSetOf())
}

/**
 * 预先构建菜谱全文搜索索引，避免全局搜索首次输入时在主线程整理整份正文。
 *
 * 使用方法：
 * [com.example.harleyapp.data.CookRepository]完成目录解析后在IO线程调用一次。索引保存在每个
 * [CookRecipe]对象的线程安全惰性属性中，重复调用不会重复生成，也不会改变菜谱正文或排序。
 *
 * @param recipes 已完成解析并准备交给页面使用的菜谱列表。
 * @return 无返回值；调用结束后每道菜的摘要和全文规范语料均已缓存。
 */
fun prepareCookRecipeSearchIndex(recipes: List<CookRecipe>) {
    recipes.forEach { recipe ->
        recipe.normalizedFullSearchCorpus
    }
}

/**
 * 根据关键词和分类筛选菜谱，并把更直接命中菜名的结果排在前面。
 *
 * 使用方法：
 * Cook页面每次查询条件变化后在后台线程调用本函数。关键词会匹配菜名、分类、简介、标签、需求名称、
 * 需求可接受ID以及完整内容块；多个空格分隔词采用“全部命中”语义。空关键词只执行分类筛选并保持
 * 上游菜谱顺序。
 *
 * @param recipes 当前已成功加载的完整菜谱列表。
 * @param filter 用户当前的关键词和分类条件。
 * @return 满足全部条件的菜谱；有关键词时按菜名精确、前缀、菜名包含、摘要字段、正文依次排序，
 * 同级结果保持输入顺序。
 */
fun searchCookRecipes(
    recipes: List<CookRecipe>,
    filter: CookRecipeSearchFilter
): List<CookRecipe> {
    val normalizedCategoryId = normalizeStableId(filter.categoryId.orEmpty())
    val normalizedQuery = normalizeCookSearchText(filter.query)
    val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank)
    val categoryMatches = recipes.withIndex().filter { indexedRecipe ->
        normalizedCategoryId.isBlank() ||
            normalizeStableId(indexedRecipe.value.categoryId) == normalizedCategoryId
    }
    if (queryTokens.isEmpty()) {
        return categoryMatches.map { indexedRecipe -> indexedRecipe.value }
    }

    return categoryMatches.mapNotNull { indexedRecipe ->
        val recipe = indexedRecipe.value
        val normalizedName = normalizeCookSearchText(recipe.name)
        val summaryCorpus = recipe.normalizedSummarySearchCorpus
        val fullCorpus = recipe.normalizedFullSearchCorpus
        if (queryTokens.any { token -> token !in fullCorpus }) {
            return@mapNotNull null
        }

        val rank = when {
            normalizedName == normalizedQuery -> 0
            normalizedName.startsWith(normalizedQuery) -> 1
            queryTokens.all { token -> token in normalizedName } -> 2
            queryTokens.all { token -> token in summaryCorpus } -> 3
            else -> 4
        }
        CookSearchCandidate(
            recipe = recipe,
            relevanceRank = rank,
            originalIndex = indexedRecipe.index
        )
    }.sortedWith(
        compareBy<CookSearchCandidate> { candidate -> candidate.relevanceRank }
            .thenBy { candidate -> candidate.originalIndex }
            .thenBy { candidate -> candidate.recipe.id }
    ).map { candidate -> candidate.recipe }
}

/**
 * 按用户已有食材评估全部菜谱，并把最接近可制作状态的菜谱排在前面。
 *
 * 使用方法：
 * [selectedIngredientIds]应来自[CookCatalog.ingredients]的稳定ID，而不是任意显示文字。每项必需需求
 * 只要其[CookIngredientRequirement.acceptedIngredientIds]命中一个已选ID即满足；调料还可以由
 * [pantrySeasoningIds]自动满足。工具和optional=true的项目会被放入忽略列表，完全不参与缺失数量、
 * 覆盖率和“可直接做”判定，因此不会因缺锅具或装饰配料错误拦截菜谱。
 *
 * @param recipes 当前完整菜谱列表。
 * @param selectedIngredientIds 用户明确勾选为家中已有的规范食材ID。
 * @param pantrySeasoningIds 用户配置为默认常备的规范调料ID；仅能自动覆盖SEASONING需求。
 * @param categoryId 可选分类ID；null或空白表示评估全部分类。
 * @return 每道菜的匹配结果，依次按可直接做、缺失数量少、覆盖率高、已匹配数量多、菜名和稳定ID排序。
 */
fun matchCookRecipesByIngredients(
    recipes: List<CookRecipe>,
    selectedIngredientIds: Collection<String>,
    pantrySeasoningIds: Collection<String> = emptySet(),
    categoryId: String? = null
): List<CookIngredientMatchResult> {
    val selectedIds = selectedIngredientIds
        .map(::normalizeStableId)
        .filterTo(linkedSetOf(), String::isNotBlank)
    val pantryIds = pantrySeasoningIds
        .map(::normalizeStableId)
        .filterTo(linkedSetOf(), String::isNotBlank)
    val normalizedCategoryId = normalizeStableId(categoryId.orEmpty())

    return recipes.asSequence()
        .filter { recipe ->
            normalizedCategoryId.isBlank() ||
                normalizeStableId(recipe.categoryId) == normalizedCategoryId
        }
        .map { recipe ->
            buildCookIngredientMatchResult(
                recipe = recipe,
                selectedIngredientIds = selectedIds,
                pantrySeasoningIds = pantryIds
            )
        }
        .sortedWith(
            compareByDescending<CookIngredientMatchResult> { result ->
                result.isDirectlyCookable
            }.thenBy { result ->
                result.missingRequirements.size
            }.thenByDescending { result ->
                result.coverageRatio
            }.thenByDescending { result ->
                result.matchedRequirementCount
            }.thenBy { result ->
                result.recipe.name
            }.thenBy { result ->
                result.recipe.id
            }
        )
        .toList()
}

/**
 * 根据日期从完整菜谱中选择当天稳定不变的一道推荐。
 *
 * 使用方法：
 * 页面点击“今日随机”时传入当前本地日期。函数先按稳定ID排序并去重，再使用日期哈希选取，因此同一
 * 数据版本、同一天和同一组菜谱无论输入顺序如何都返回同一道菜；第二天会重新计算推荐。
 *
 * @param recipes 可参与推荐的菜谱列表。
 * @param date 用户设备当前本地日期，由调用方显式传入以便测试和时区行为清晰。
 * @return 当天稳定推荐的菜谱；列表为空时返回null。
 */
fun selectDailyCookRecipe(
    recipes: List<CookRecipe>,
    date: LocalDate
): CookRecipe? {
    return selectAlternateCookRecipe(
        recipes = recipes,
        date = date,
        excludedRecipeIds = emptySet()
    )
}

/**
 * 在排除已经展示过的菜谱后，稳定选择同一天的下一道推荐。
 *
 * 使用方法：
 * “换一道”按钮把当前菜谱ID追加到累计排除集合后调用本函数。相同日期、菜谱集合和排除集合会得到
 * 相同结果，避免Compose重组时随机菜谱自行跳变；当全部菜谱都已排除时返回null，由页面决定是否
 * 清空排除集合后重新开始。
 *
 * @param recipes 完整候选菜谱列表。
 * @param date 用户设备当前本地日期。
 * @param excludedRecipeIds 本轮已经展示或明确不希望再次出现的菜谱稳定ID集合。
 * @return 排除后选中的稳定菜谱；没有剩余有效候选时返回null。
 */
fun selectAlternateCookRecipe(
    recipes: List<CookRecipe>,
    date: LocalDate,
    excludedRecipeIds: Collection<String>
): CookRecipe? {
    val excludedIds = excludedRecipeIds
        .map(::normalizeStableId)
        .filterTo(hashSetOf(), String::isNotBlank)
    val candidates = recipes
        .asSequence()
        .filter { recipe ->
            val normalizedId = normalizeStableId(recipe.id)
            normalizedId.isNotBlank() && normalizedId !in excludedIds
        }
        .sortedWith(
            compareBy<CookRecipe> { recipe -> normalizeStableId(recipe.id) }
                .thenBy { recipe -> recipe.name }
        )
        .distinctBy { recipe -> normalizeStableId(recipe.id) }
        .toList()
    if (candidates.isEmpty()) return null

    val selectedIndex = Math.floorMod(
        stableCookDateHash(date),
        candidates.size.toLong()
    ).toInt()
    return candidates[selectedIndex]
}

/** 保存一条关键词搜索的内部排序信息。 */
private data class CookSearchCandidate(
    val recipe: CookRecipe,
    val relevanceRank: Int,
    val originalIndex: Int
)

/**
 * 为单道菜构造食材匹配结果。
 *
 * @param recipe 需要评估的菜谱。
 * @param selectedIngredientIds 已规范化的用户所选食材ID。
 * @param pantrySeasoningIds 已规范化的常备调料ID。
 * @return 包含命中、缺失、自动调料和忽略项目的完整结果。
 */
private fun buildCookIngredientMatchResult(
    recipe: CookRecipe,
    selectedIngredientIds: Set<String>,
    pantrySeasoningIds: Set<String>
): CookIngredientMatchResult {
    val matchedRequirements = mutableListOf<CookIngredientRequirement>()
    val missingRequirements = mutableListOf<CookIngredientRequirement>()
    val automaticallyCoveredSeasonings = mutableListOf<CookIngredientRequirement>()
    val ignoredRequirements = mutableListOf<CookIngredientRequirement>()

    recipe.requirements.forEach { requirement ->
        if (requirement.optional || requirement.kind == CookRequirementKind.TOOL) {
            ignoredRequirements += requirement
            return@forEach
        }

        val acceptedIds = requirement.acceptedIngredientIds
            .asSequence()
            .map(::normalizeStableId)
            .filter(String::isNotBlank)
            .toSet()
        val selectedMatch = acceptedIds.any(selectedIngredientIds::contains)
        val pantryMatch = requirement.kind == CookRequirementKind.SEASONING &&
            acceptedIds.any(pantrySeasoningIds::contains)
        if (selectedMatch || pantryMatch) {
            matchedRequirements += requirement
            if (!selectedMatch && pantryMatch) {
                automaticallyCoveredSeasonings += requirement
            }
        } else {
            missingRequirements += requirement
        }
    }

    val requiredCount = matchedRequirements.size + missingRequirements.size
    val matchedCount = matchedRequirements.size
    return CookIngredientMatchResult(
        recipe = recipe,
        isDirectlyCookable = missingRequirements.isEmpty(),
        matchedRequirements = matchedRequirements,
        missingRequirements = missingRequirements,
        automaticallyCoveredSeasonings = automaticallyCoveredSeasonings,
        ignoredRequirements = ignoredRequirements,
        requiredRequirementCount = requiredCount,
        matchedRequirementCount = matchedCount,
        coverageRatio = if (requiredCount == 0) {
            1.0
        } else {
            matchedCount.toDouble() / requiredCount.toDouble()
        }
    )
}

/**
 * 构造不含长正文的菜谱搜索摘要。
 *
 * @param recipe 当前菜谱。
 * @return 已规范化的菜名、分类、简介、标签和需求信息文本。
 */
private fun buildCookRecipeSummaryCorpus(recipe: CookRecipe): String {
    val requirementText = recipe.requirements.joinToString(" ") { requirement ->
        listOf(
            requirement.displayName,
            requirement.amount,
            requirement.acceptedIngredientIds.joinToString(" ")
        ).joinToString(" ")
    }
    return normalizeCookSearchText(
        listOf(
            recipe.name,
            recipe.categoryId,
            recipe.categoryName,
            recipe.summary,
            recipe.tags.joinToString(" "),
            requirementText
        ).joinToString(" ")
    )
}

/**
 * 在摘要基础上加入菜谱所有章节和内容块，供关键词最终匹配。
 *
 * @param recipe 当前菜谱。
 * @param summaryCorpus 已规范化的摘要文本，避免重复计算。
 * @return 同时包含摘要和完整做法的规范化搜索文本。
 */
private fun buildCookRecipeFullCorpus(
    recipe: CookRecipe,
    summaryCorpus: String
): String {
    val sectionText = recipe.sections.joinToString(" ") { section ->
        buildString {
            append(section.title)
            section.blocks.forEach { block ->
                append(' ')
                append(cookContentBlockText(block))
            }
        }
    }
    return normalizeCookSearchText("$summaryCorpus $sectionText")
}

/**
 * 把一个结构化内容块转换成只供本机搜索的普通文字。
 *
 * @param block 当前内容块。
 * @return 不含格式标记的文字；图片只返回无障碍说明，不把资产路径当作用户关键词。
 */
private fun cookContentBlockText(block: CookContentBlock): String {
    return when (block) {
        is CookContentBlock.Heading -> block.text
        is CookContentBlock.Paragraph -> block.text
        is CookContentBlock.BulletList -> block.items.joinToString(" ")
        is CookContentBlock.NumberedList -> block.items.joinToString(" ")
        is CookContentBlock.Image -> block.contentDescription
        is CookContentBlock.Table -> listOf(
            block.headers.joinToString(" "),
            block.rows.flatten().joinToString(" ")
        ).joinToString(" ")
        is CookContentBlock.Quote -> block.text
        is CookContentBlock.Code -> block.text
        CookContentBlock.Divider -> ""
    }
}

/**
 * 规范用于关键词比较的自然语言文本。
 *
 * @param value 菜名、正文或用户输入。
 * @return 转成小写并把标点和连续空白折叠为单个空格的文本。
 */
private fun normalizeCookSearchText(value: String): String {
    val normalized = buildString(value.length) {
        var previousWasSeparator = true
        value.trim().lowercase(Locale.ROOT).forEach { character ->
            if (character.isLetterOrDigit()) {
                append(character)
                previousWasSeparator = false
            } else if (!previousWasSeparator) {
                append(' ')
                previousWasSeparator = true
            }
        }
    }
    return normalized.trim()
}

/**
 * 规范数据模型中的稳定ID。
 *
 * @param value 分类、食材或菜谱ID。
 * @return 去除首尾空白并转为小写的ID；内部字符保持不变，确保匹配只采用完整ID相等语义。
 */
private fun normalizeStableId(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

/**
 * 计算只依赖本地日期的稳定哈希。
 *
 * @param date 用户设备当前本地日期。
 * @return 可通过floorMod映射到任意正候选数量的稳定Long值。
 */
private fun stableCookDateHash(date: LocalDate): Long {
    var hash = 1_125_899_906_842_597L
    date.toString().forEach { character ->
        // Long自然溢出在JVM上具有确定结果，可让相邻日期分布不再表现为简单顺序轮换。
        hash = hash * 31L + character.code.toLong()
    }
    return hash
}

/** 只允许默认常备开关自动覆盖的四种基础调料规范ID。 */
private val DEFAULT_COOK_PANTRY_SEASONING_IDS = setOf("水", "盐", "白糖", "食用油")
