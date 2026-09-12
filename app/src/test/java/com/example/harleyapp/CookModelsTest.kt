package com.example.harleyapp

import com.example.harleyapp.model.CookContentBlock
import com.example.harleyapp.model.CookIngredient
import com.example.harleyapp.model.CookIngredientMatchResult
import com.example.harleyapp.model.CookIngredientRequirement
import com.example.harleyapp.model.CookRecipe
import com.example.harleyapp.model.CookRecipeSearchFilter
import com.example.harleyapp.model.CookRecipeSection
import com.example.harleyapp.model.CookRequirementKind
import com.example.harleyapp.model.matchCookRecipesByIngredients
import com.example.harleyapp.model.resolveDefaultCookPantrySeasoningIds
import com.example.harleyapp.model.searchCookRecipes
import com.example.harleyapp.model.selectAlternateCookRecipe
import com.example.harleyapp.model.selectDailyCookRecipe
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证Cook菜谱搜索、食材齐全度和每日稳定推荐的纯Kotlin规则。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew :app:testDebugUnitTest --tests *CookModelsTest`。测试只使用内存中的虚构
 * 菜谱，不依赖Android Context、HowToCook资产、网络或用户本机数据。
 */
class CookModelsTest {

    /**
     * 验证关键词可以同时命中菜名、分类、食材和正文，并与分类条件采用交集。
     *
     * @return 无返回值；任一字段漏搜、多个关键词未采用全部命中或分类未生效时由JUnit报告失败。
     */
    @Test
    fun keywordSearchCoversStructuredFieldsAndCombinesCategoryFilter() {
        val tomatoEgg = recipe(
            id = "tomato-egg",
            name = "番茄炒鸡蛋",
            categoryId = "vegetable",
            categoryName = "素菜",
            requirements = listOf(
                requirement("番茄", "tomato"),
                requirement("鸡蛋", "egg")
            ),
            sections = listOf(
                section(
                    "操作",
                    CookContentBlock.NumberedList(listOf("大火快速翻炒", "装盘食用"))
                )
            )
        )
        val beefNoodles = recipe(
            id = "beef-noodles",
            name = "红烧牛肉面",
            categoryId = "staple",
            categoryName = "主食",
            tags = listOf("Noodle", "面食"),
            requirements = listOf(
                requirement("牛肉", "beef"),
                requirement("面条", "noodle")
            )
        )
        val recipes = listOf(tomatoEgg, beefNoodles)

        assertEquals(
            listOf(tomatoEgg),
            searchCookRecipes(
                recipes,
                CookRecipeSearchFilter(query = " 番茄   鸡蛋 ")
            )
        )
        assertEquals(
            listOf(tomatoEgg),
            searchCookRecipes(
                recipes,
                CookRecipeSearchFilter(query = "大火")
            )
        )
        assertEquals(
            listOf(beefNoodles),
            searchCookRecipes(
                recipes,
                CookRecipeSearchFilter(query = "noodle", categoryId = "STAPLE")
            )
        )
        assertTrue(
            searchCookRecipes(
                recipes,
                CookRecipeSearchFilter(query = "番茄", categoryId = "staple")
            ).isEmpty()
        )
    }

    /**
     * 验证空关键词仅执行分类筛选并保留数据生成器给出的原始顺序。
     *
     * @return 无返回值；结果被按名称重新排序或混入其他分类时由JUnit报告失败。
     */
    @Test
    fun blankQueryKeepsSourceOrderWithinSelectedCategory() {
        val first = recipe("second-name", "乙菜", categoryId = "soup", categoryName = "汤羹")
        val second = recipe("first-name", "甲菜", categoryId = "soup", categoryName = "汤羹")
        val other = recipe("drink", "饮品", categoryId = "drink", categoryName = "饮料")

        val result = searchCookRecipes(
            listOf(first, other, second),
            CookRecipeSearchFilter(query = "   ", categoryId = " soup ")
        )

        assertEquals(listOf(first, second), result)
    }

    /**
     * 验证菜名精确命中的结果优先于仅在菜名或正文中包含关键词的结果。
     *
     * @return 无返回值；相关度排序不符合精确、前缀和正文层级时由JUnit报告失败。
     */
    @Test
    fun exactRecipeNameRanksBeforeBroaderMatches() {
        val contentMatch = recipe(
            id = "breakfast",
            name = "家常早餐",
            sections = listOf(
                section("提示", CookContentBlock.Paragraph("可以搭配鸡蛋"))
            )
        )
        val prefixMatch = recipe(id = "egg-soup", name = "鸡蛋汤")
        val exactMatch = recipe(id = "egg", name = "鸡蛋")

        val result = searchCookRecipes(
            listOf(contentMatch, prefixMatch, exactMatch),
            CookRecipeSearchFilter(query = "鸡蛋")
        )

        assertEquals(listOf(exactMatch, prefixMatch, contentMatch), result)
    }

    /**
     * 验证替代食材ID、常备调料、工具和可选项目共同参与匹配时仍能得到准确的可制作结论。
     *
     * @return 无返回值；任一替代项未命中、调料未自动覆盖或工具和可选项被算作缺失时测试失败。
     */
    @Test
    fun ingredientMatchingUsesAlternativeIdsPantrySeasoningsAndIgnoredItems() {
        val target = recipe(
            id = "tomato-egg",
            name = "番茄炒鸡蛋",
            requirements = listOf(
                CookIngredientRequirement(
                    displayName = "番茄或圣女果",
                    acceptedIngredientIds = setOf("tomato", "cherry-tomato")
                ),
                requirement("鸡蛋", "egg"),
                CookIngredientRequirement(
                    displayName = "盐",
                    acceptedIngredientIds = setOf("salt"),
                    kind = CookRequirementKind.SEASONING
                ),
                CookIngredientRequirement(
                    displayName = "葱花",
                    acceptedIngredientIds = setOf("scallion"),
                    optional = true
                ),
                CookIngredientRequirement(
                    displayName = "炒锅",
                    acceptedIngredientIds = setOf("wok"),
                    kind = CookRequirementKind.TOOL
                )
            )
        )

        val result = matchCookRecipesByIngredients(
            recipes = listOf(target),
            selectedIngredientIds = setOf(" CHERRY-TOMATO ", "EGG"),
            pantrySeasoningIds = setOf("SALT")
        ).single()

        assertTrue(result.isDirectlyCookable)
        assertTrue(result.missingRequirements.isEmpty())
        assertEquals(3, result.requiredRequirementCount)
        assertEquals(3, result.matchedRequirementCount)
        assertEquals(1.0, result.coverageRatio, 0.0)
        assertEquals(listOf("盐"), result.automaticallyCoveredSeasonings.map { it.displayName })
        assertEquals(setOf("葱花", "炒锅"), result.ignoredRequirements.map { it.displayName }.toSet())
    }

    /**
     * 验证常备调料只覆盖SEASONING类型，且完整ID匹配不会把名称相似项目误判为已有。
     *
     * @return 无返回值；常备调料覆盖主食材或`oyster-oil`包含匹配`oil`时由JUnit报告失败。
     */
    @Test
    fun pantryAndSelectedIngredientsNeverUseBroadContainsMatching() {
        val recipe = recipe(
            id = "strict-ids",
            name = "严格ID测试菜",
            requirements = listOf(
                CookIngredientRequirement(
                    displayName = "作为主食材的盐块",
                    acceptedIngredientIds = setOf("salt"),
                    kind = CookRequirementKind.INGREDIENT
                ),
                CookIngredientRequirement(
                    displayName = "食用油",
                    acceptedIngredientIds = setOf("oil"),
                    kind = CookRequirementKind.SEASONING
                )
            )
        )

        val result = matchCookRecipesByIngredients(
            recipes = listOf(recipe),
            selectedIngredientIds = setOf("oyster-oil"),
            pantrySeasoningIds = setOf("salt")
        ).single()

        assertFalse(result.isDirectlyCookable)
        assertEquals(setOf("作为主食材的盐块", "食用油"), result.missingRequirements.map { it.displayName }.toSet())
        assertTrue(result.automaticallyCoveredSeasonings.isEmpty())
        assertEquals(0.0, result.coverageRatio, 0.0)
    }

    /**
     * 验证默认常备调料只按规范ID匹配，不会因为“糖”别名把冰糖等特殊材料一并算作已有。
     *
     * @return 无返回值；别名参与默认白名单或非调料类型进入结果时由JUnit报告失败。
     */
    @Test
    fun defaultPantryUsesCanonicalIdsInsteadOfBroadAliases() {
        val ingredients = listOf(
            // 目录级类型允许因某道菜把水、糖作为主料而提升，默认白名单仍应包含其稳定ID。
            CookIngredient("水", "水", kind = CookRequirementKind.INGREDIENT),
            CookIngredient("盐", "盐", aliases = listOf("食盐"), kind = CookRequirementKind.SEASONING),
            CookIngredient("白糖", "白糖", aliases = listOf("糖"), kind = CookRequirementKind.INGREDIENT),
            CookIngredient("食用油", "食用油", aliases = listOf("油"), kind = CookRequirementKind.SEASONING),
            CookIngredient("冰糖", "冰糖", aliases = listOf("糖"), kind = CookRequirementKind.SEASONING),
            CookIngredient("绵白糖", "绵白糖", aliases = listOf("糖"), kind = CookRequirementKind.SEASONING),
            CookIngredient("盐块", "盐块", aliases = listOf("盐"), kind = CookRequirementKind.INGREDIENT)
        )

        assertEquals(
            linkedSetOf("水", "盐", "白糖", "食用油"),
            resolveDefaultCookPantrySeasoningIds(ingredients)
        )
    }

    /**
     * 验证食材结果先列出可直接制作的菜，再按缺失数量和覆盖率展示最接近的候选。
     *
     * @return 无返回值；直接可做项目未置顶或缺一项排在缺两项之后时由JUnit报告失败。
     */
    @Test
    fun ingredientMatchesAreSortedByCookabilityAndMissingRequirements() {
        val directlyCookable = recipe(
            id = "egg",
            name = "煎鸡蛋",
            requirements = listOf(requirement("鸡蛋", "egg"))
        )
        val missingOneWithHighCoverage = recipe(
            id = "tomato-egg",
            name = "番茄炒鸡蛋",
            requirements = listOf(
                requirement("鸡蛋", "egg"),
                requirement("番茄", "tomato"),
                requirement("葱", "scallion")
            )
        )
        val missingOneWithLowCoverage = recipe(
            id = "beef",
            name = "炒牛肉",
            requirements = listOf(
                requirement("番茄", "tomato"),
                requirement("牛肉", "beef")
            )
        )
        val missingTwo = recipe(
            id = "noodles",
            name = "牛肉面",
            requirements = listOf(
                requirement("鸡蛋", "egg"),
                requirement("牛肉", "beef"),
                requirement("面条", "noodle")
            )
        )

        val results = matchCookRecipesByIngredients(
            recipes = listOf(missingTwo, missingOneWithLowCoverage, directlyCookable, missingOneWithHighCoverage),
            selectedIngredientIds = setOf("egg", "tomato")
        )

        assertEquals(
            listOf("egg", "tomato-egg", "beef", "noodles"),
            results.map { result -> result.recipe.id }
        )
        assertEquals(listOf("葱"), results[1].missingRequirements.map { it.displayName })
        assertEquals(listOf("牛肉", "面条"), results.last().missingRequirements.map { it.displayName })
    }

    /**
     * 验证分类条件同样适用于食材推荐，不会把其他菜系的高匹配项目混入结果。
     *
     * @return 无返回值；返回项目包含其他分类时由JUnit报告失败。
     */
    @Test
    fun ingredientMatchingCanBeRestrictedToOneCategory() {
        val soup = recipe(
            id = "egg-soup",
            name = "鸡蛋汤",
            categoryId = "soup",
            categoryName = "汤羹",
            requirements = listOf(requirement("鸡蛋", "egg"))
        )
        val breakfast = recipe(
            id = "fried-egg",
            name = "煎蛋",
            categoryId = "breakfast",
            categoryName = "早餐",
            requirements = listOf(requirement("鸡蛋", "egg"))
        )

        val results = matchCookRecipesByIngredients(
            recipes = listOf(breakfast, soup),
            selectedIngredientIds = setOf("egg"),
            categoryId = " SOUP "
        )

        assertEquals(listOf(soup), results.map(CookIngredientMatchResult::recipe))
    }

    /**
     * 验证同一日期和同一菜谱集合始终得到同一道推荐，调用方传入顺序不会改变结果。
     *
     * @return 无返回值；同日结果随列表顺序或重复调用变化时由JUnit报告失败。
     */
    @Test
    fun dailyRecommendationIsStableAndIndependentOfInputOrder() {
        val recipes = listOf(
            recipe("alpha", "甲菜"),
            recipe("bravo", "乙菜"),
            recipe("charlie", "丙菜")
        )
        val date = LocalDate.of(2026, 9, 12)

        val first = selectDailyCookRecipe(recipes, date)
        val repeated = selectDailyCookRecipe(recipes, date)
        val reversed = selectDailyCookRecipe(recipes.reversed(), date)

        assertEquals(first, repeated)
        assertEquals(first, reversed)
        assertTrue(first in recipes)
    }

    /**
     * 验证“换一道”严格排除已经展示的菜谱，并在排除集合相同时保持稳定。
     *
     * @return 无返回值；返回被排除菜谱、同条件结果漂移或全部排除后仍返回内容时测试失败。
     */
    @Test
    fun alternateRecommendationHonorsAccumulatedExclusions() {
        val recipes = listOf(
            recipe("alpha", "甲菜"),
            recipe("bravo", "乙菜"),
            recipe("charlie", "丙菜")
        )
        val date = LocalDate.of(2026, 9, 12)
        val daily = requireNotNull(selectDailyCookRecipe(recipes, date))

        val alternate = selectAlternateCookRecipe(
            recipes = recipes,
            date = date,
            excludedRecipeIds = setOf(daily.id)
        )
        val repeated = selectAlternateCookRecipe(
            recipes = recipes.reversed(),
            date = date,
            excludedRecipeIds = setOf(daily.id.uppercase())
        )

        assertNotEquals(daily, alternate)
        assertEquals(alternate, repeated)
        assertNull(
            selectAlternateCookRecipe(
                recipes = recipes,
                date = date,
                excludedRecipeIds = recipes.map(CookRecipe::id)
            )
        )
    }

    /**
     * 创建单元测试使用的必需主食材需求。
     *
     * @param displayName 页面显示名称。
     * @param ingredientId 能满足需求的规范食材ID。
     * @return optional=false且kind=INGREDIENT的需求对象。
     */
    private fun requirement(
        displayName: String,
        ingredientId: String
    ): CookIngredientRequirement {
        return CookIngredientRequirement(
            displayName = displayName,
            acceptedIngredientIds = setOf(ingredientId)
        )
    }

    /**
     * 创建只包含一个或多个内容块的测试章节。
     *
     * @param title 章节标题。
     * @param blocks 需要按顺序保存的内容块。
     * @return 使用标题生成稳定测试ID的章节。
     */
    private fun section(
        title: String,
        vararg blocks: CookContentBlock
    ): CookRecipeSection {
        return CookRecipeSection(
            id = "section-${title.hashCode()}",
            title = title,
            blocks = blocks.toList()
        )
    }

    /**
     * 创建不依赖真实HowToCook内容的测试菜谱。
     *
     * @param id 菜谱稳定ID。
     * @param name 菜名。
     * @param categoryId 分类稳定ID。
     * @param categoryName 分类显示名称。
     * @param tags 搜索标签。
     * @param requirements 食材、调料和工具需求。
     * @param sections 结构化做法章节。
     * @return 字段完整且使用虚构来源地址的测试菜谱。
     */
    private fun recipe(
        id: String,
        name: String,
        categoryId: String = "test-category",
        categoryName: String = "测试分类",
        tags: List<String> = emptyList(),
        requirements: List<CookIngredientRequirement> = emptyList(),
        sections: List<CookRecipeSection> = emptyList()
    ): CookRecipe {
        return CookRecipe(
            id = id,
            name = name,
            categoryId = categoryId,
            categoryName = categoryName,
            summary = "测试菜谱简介",
            tags = tags,
            coverImageAssetPath = "cook/images/$id.webp",
            requirements = requirements,
            sections = sections,
            sourcePath = "dishes/test/$id.md",
            sourceUrl = "https://example.com/$id"
        )
    }
}
