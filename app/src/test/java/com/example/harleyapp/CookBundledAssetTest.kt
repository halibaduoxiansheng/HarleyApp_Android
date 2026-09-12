package com.example.harleyapp

import java.io.DataInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Cook 功能随 App 打包的 HowToCook 固定快照、图片和许可声明保持完整。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew :app:testDebugUnitTest --tests *CookBundledAssetTest`。测试直接读取
 * `app/src/main/assets`，不会访问网络，也不会打印菜谱正文。
 */
class CookBundledAssetTest {

    /**
     * 验证清单版本、固定来源、十个分类、369 道正式菜谱及其稳定标识和来源路径。
     *
     * @return 无返回值；清单缺失、JSON 结构损坏、固定快照漂移或菜谱漏项时由 JUnit 报告失败。
     */
    @Test
    fun bundledCatalogMatchesPinnedHowToCookSnapshot() {
        val assetsDirectory = resolveMainAssetsDirectory()
        val catalogFile = File(assetsDirectory, CATALOG_ASSET_PATH)
        assertTrue("Cook catalog asset is missing", catalogFile.isFile)

        val catalogJson = catalogFile.readText(Charsets.UTF_8)
        assertEquals(EXPECTED_SCHEMA_VERSION, readRequiredIntField(catalogJson, "schemaVersion"))
        assertEquals(EXPECTED_REPOSITORY_URL, readRequiredStringField(catalogJson, "sourceRepositoryUrl"))
        assertEquals(EXPECTED_SOURCE_COMMIT, readRequiredStringField(catalogJson, "sourceCommit"))
        assertEquals(EXPECTED_SOURCE_LICENSE, readRequiredStringField(catalogJson, "sourceLicense"))

        // 原料索引是 schema 的稳定组成部分，固定快照应保持数量、ID唯一性和四种基础常备项。
        val ingredientObjects = splitTopLevelJsonObjects(
            readRequiredArrayBody(catalogJson, "ingredients")
        )
        assertEquals(EXPECTED_INGREDIENT_COUNT, ingredientObjects.size)
        val ingredientIds = ingredientObjects.map { ingredientJson ->
            readRequiredStringField(ingredientJson, "id")
        }
        assertEquals(EXPECTED_INGREDIENT_COUNT, ingredientIds.toSet().size)
        assertTrue(ingredientIds.containsAll(EXPECTED_DEFAULT_PANTRY_IDS))

        val categoryObjects = splitTopLevelJsonObjects(
            readRequiredArrayBody(catalogJson, "categories")
        )
        assertEquals(EXPECTED_CATEGORY_COUNTS.size, categoryObjects.size)

        val categories = categoryObjects.map { categoryJson ->
            BundledCookCategoryRecord(
                id = readRequiredStringField(categoryJson, "id"),
                displayName = readRequiredStringField(categoryJson, "displayName"),
                description = readRequiredStringField(categoryJson, "description"),
                sortOrder = readRequiredIntField(categoryJson, "sortOrder")
            )
        }
        assertEquals(
            EXPECTED_CATEGORY_COUNTS.keys.toList(),
            categories.sortedBy(BundledCookCategoryRecord::sortOrder).map(BundledCookCategoryRecord::id)
        )
        categories.forEachIndexed { index, category ->
            assertEquals("Unexpected category sort order for ${category.id}", index, category.sortOrder)
            assertTrue("Category display name is blank for ${category.id}", category.displayName.isNotBlank())
            assertTrue("Category description is blank for ${category.id}", category.description.isNotBlank())
        }

        val recipeObjects = splitTopLevelJsonObjects(
            readRequiredArrayBody(catalogJson, "recipes")
        )
        assertEquals(EXPECTED_RECIPE_COUNT, recipeObjects.size)

        val recipes = recipeObjects.map { recipeJson ->
            REQUIRED_RECIPE_STRING_FIELDS.forEach { field ->
                readRequiredStringField(recipeJson, field)
            }
            REQUIRED_RECIPE_ARRAY_FIELDS.forEach { field ->
                readRequiredArrayBody(recipeJson, field)
            }
            val requirementCount = splitTopLevelJsonObjects(
                readRequiredArrayBody(recipeJson, "requirements")
            ).size
            val sectionTitles = splitTopLevelJsonObjects(
                readRequiredArrayBody(recipeJson, "sections")
            ).map { sectionJson -> readRequiredStringField(sectionJson, "title") }

            BundledCookRecipeRecord(
                id = readRequiredStringField(recipeJson, "id"),
                name = readRequiredStringField(recipeJson, "name"),
                categoryId = readRequiredStringField(recipeJson, "categoryId"),
                categoryName = readRequiredStringField(recipeJson, "categoryName"),
                difficulty = readRequiredStringField(recipeJson, "difficulty"),
                calories = readRequiredStringField(recipeJson, "calories"),
                sourcePath = readRequiredStringField(recipeJson, "sourcePath"),
                requirementCount = requirementCount,
                sectionTitles = sectionTitles,
                // 正文图片块也带有 sourceUrl；菜谱自身的固定来源字段位于对象末尾，因此读取最后一项。
                sourceUrl = readRequiredLastStringField(recipeJson, "sourceUrl")
            )
        }

        assertEquals(EXPECTED_RECIPE_COUNT, recipes.map(BundledCookRecipeRecord::id).toSet().size)
        assertTrue(
            "Cook recipe ids must use the stable generated format",
            recipes.all { recipe -> recipe.id.matches(RECIPE_ID_PATTERN) }
        )
        assertEquals(EXPECTED_RECIPE_COUNT, recipes.map(BundledCookRecipeRecord::sourcePath).toSet().size)
        assertEquals(
            EXPECTED_CATEGORY_COUNTS,
            recipes.groupingBy(BundledCookRecipeRecord::categoryId).eachCount()
        )

        val categoryNames = categories.associate { category -> category.id to category.displayName }
        recipes.forEach { recipe ->
            assertTrue("Cook recipe name is blank for ${recipe.id}", recipe.name.isNotBlank())
            assertEquals(
                "Category name does not match category id for ${recipe.id}",
                categoryNames[recipe.categoryId],
                recipe.categoryName
            )
            assertTrue(
                "Invalid difficulty for ${recipe.id}",
                recipe.difficulty.matches(DIFFICULTY_PATTERN)
            )
            assertTrue(
                "Invalid calorie text for ${recipe.id}",
                recipe.calories.matches(CALORIE_PATTERN)
            )
            assertTrue("Recipe requirements are empty for ${recipe.id}", recipe.requirementCount > 0)
            assertEquals(
                "Recipe sections are incomplete for ${recipe.id}",
                EXPECTED_SECTION_TITLES,
                recipe.sectionTitles
            )
            assertTrue(
                "Recipe source path is outside its category for ${recipe.id}",
                recipe.sourcePath.startsWith("dishes/${recipe.categoryId}/")
            )
            assertTrue("Recipe source path must end in .md for ${recipe.id}", recipe.sourcePath.endsWith(".md"))
            assertFalse("Template recipe must not be bundled", recipe.sourcePath.contains("/template/"))
            assertFalse("Recipe source path must not traverse directories", recipe.sourcePath.contains(".."))
            assertTrue(
                "Recipe source URL is not pinned for ${recipe.id}",
                recipe.sourceUrl.startsWith("$EXPECTED_REPOSITORY_URL/blob/$EXPECTED_SOURCE_COMMIT/")
            )
        }
    }

    /**
     * 验证上游写在计算段或小节标题里的可选语义、简单替代关系和工具分类不会在生成时丢失。
     *
     * @return 无返回值；关键回归样例重新变成必需项、替代项丢失或工具被算作食材时测试失败。
     */
    @Test
    fun bundledIngredientSemanticsPreserveOptionalAlternativesAndTools() {
        val assetsDirectory = resolveMainAssetsDirectory()
        val catalogJson = File(assetsDirectory, CATALOG_ASSET_PATH).readText(Charsets.UTF_8)
        val recipeObjects = splitTopLevelJsonObjects(
            readRequiredArrayBody(catalogJson, "recipes")
        )

        val babyCabbage = findRecipeObject(recipeObjects, "上汤娃娃菜")
        val centuryEgg = findRequirementObject(babyCabbage, "皮蛋")
        assertTrue(
            "Calculation text '可以不放' must mark century egg optional",
            readRequiredBooleanField(centuryEgg, "optional")
        )
        val luncheonMeat = findRequirementObject(babyCabbage, "午餐肉")
        assertEquals(
            listOf("午餐肉", "火腿肠"),
            readRequiredStringArray(luncheonMeat, "acceptedIngredientIds")
        )

        val kungPaoChicken = findRecipeObject(recipeObjects, "宫保鸡丁")
        val optionalChiliOil = findRequirementObject(kungPaoChicken, "油泼辣子")
        assertTrue(
            "Items under an optional subsection must remain optional",
            readRequiredBooleanField(optionalChiliOil, "optional")
        )

        val sugaredTomato = findRecipeObject(recipeObjects, "糖拌西红柿")
        val refrigerator = findRequirementObject(sugaredTomato, "冰箱")
        assertEquals("tool", readRequiredStringField(refrigerator, "kind"))

        val naan = findRecipeObject(recipeObjects, "印度烤饼")
        val flour = findRequirementObject(naan, "中筋面粉")
        assertEquals(
            listOf("中筋面粉", "高筋面粉"),
            readRequiredStringArray(flour, "acceptedIngredientIds")
        )
    }

    /**
     * 验证清单中每个非空封面和正文图片路径均指向真实的本地 RIFF/WEBP 文件。
     *
     * @return 无返回值；图片越界、缺失、扩展名错误、文件过短或容器签名不正确时由 JUnit 报告失败。
     */
    @Test
    fun bundledImageReferencesResolveToWebpAssets() {
        val assetsDirectory = resolveMainAssetsDirectory()
        val catalogJson = File(assetsDirectory, CATALOG_ASSET_PATH).readText(Charsets.UTF_8)
        val coverPaths = readStringFields(catalogJson, "coverImageAssetPath")
        val contentImagePaths = readStringFields(catalogJson, "assetPath")

        assertEquals(EXPECTED_RECIPE_COUNT, coverPaths.size)
        assertEquals(EXPECTED_COVER_IMAGE_RECIPE_COUNT, coverPaths.count(String::isNotBlank))
        val referencedImagePaths = (coverPaths + contentImagePaths)
            .filter(String::isNotBlank)
            .toSet()
        assertEquals(EXPECTED_LOCAL_IMAGE_COUNT, referencedImagePaths.size)

        // 输出目录不得残留已从清单删除的旧图，否则APK会无意义增大且难以证明图片与固定快照一一对应。
        val bundledImagePaths = File(assetsDirectory, COOK_IMAGE_DIRECTORY_PATH)
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .map { imageFile -> "$COOK_IMAGE_DIRECTORY_PATH/${imageFile.name}" }
            .toSet()
        assertEquals(referencedImagePaths, bundledImagePaths)

        val canonicalAssetsPath = assetsDirectory.canonicalFile.toPath()
        referencedImagePaths.forEach { assetPath ->
            assertTrue("Cook image path must use forward slashes: $assetPath", '\\' !in assetPath)
            assertTrue("Cook image path is outside cook/images: $assetPath", assetPath.startsWith("cook/images/"))
            assertTrue("Cook image asset must use .webp: $assetPath", assetPath.endsWith(".webp"))
            assertFalse("Cook image path must not traverse directories: $assetPath", assetPath.contains(".."))

            val imageFile = File(assetsDirectory, assetPath).canonicalFile
            assertTrue(
                "Cook image path escapes the main assets directory: $assetPath",
                imageFile.toPath().startsWith(canonicalAssetsPath)
            )
            assertTrue("Cook image asset is missing: $assetPath", imageFile.isFile)
            assertWebpContainer(imageFile, assetPath)
        }
    }

    /**
     * 验证完整 Unlicense 文本和第三方声明均记录 HowToCook 来源及固定提交。
     *
     * @return 无返回值；许可文件或第三方声明缺失、来源未固定时由 JUnit 报告失败。
     */
    @Test
    fun bundledLicenseAndThirdPartyNoticeDescribeHowToCook() {
        val assetsDirectory = resolveMainAssetsDirectory()
        val licenseFile = File(assetsDirectory, HOW_TO_COOK_LICENSE_ASSET_PATH)
        assertTrue("HowToCook license asset is missing", licenseFile.isFile)

        val licenseText = licenseFile.readText(Charsets.UTF_8)
        assertTrue("HowToCook license does not contain the public-domain grant", "public domain" in licenseText)
        assertTrue("HowToCook license does not identify the Unlicense", "https://unlicense.org" in licenseText)
        assertTrue("HowToCook license does not contain the warranty disclaimer", "AS IS" in licenseText)

        val noticesFile = File(assetsDirectory, THIRD_PARTY_NOTICES_ASSET_PATH)
        assertTrue("Third-party notices asset is missing", noticesFile.isFile)
        val noticesText = noticesFile.readText(Charsets.UTF_8)
        assertTrue("HowToCook repository is missing from third-party notices", EXPECTED_REPOSITORY_URL in noticesText)
        assertTrue("HowToCook commit is missing from third-party notices", EXPECTED_SOURCE_COMMIT in noticesText)
        assertTrue("HowToCook license is missing from third-party notices", EXPECTED_SOURCE_LICENSE in noticesText)
        assertTrue(
            "HowToCook full-license asset is missing from third-party notices",
            "cook/HOWTOCOOK_LICENSE.txt" in noticesText
        )
    }

    /**
     * 从项目根目录或 app 模块目录定位 main assets，供本类所有离线资产测试复用。
     *
     * @return 已存在的 main assets 目录；两种受支持工作目录均找不到时抛出异常。
     */
    private fun resolveMainAssetsDirectory(): File {
        return listOf(
            File("app/src/main/assets"),
            File("src/main/assets")
        ).firstOrNull(File::isDirectory)
            ?: error("Main assets directory is missing")
    }

    /**
     * 从 JSON 对象读取必需的字符串字段。
     *
     * @param json 包含目标字段的完整 JSON 对象文本。
     * @param field 要读取的字段名。
     * @return 已完成 JSON 转义还原的字符串；字段缺失或类型错误时抛出异常。
     */
    private fun readRequiredStringField(json: String, field: String): String {
        return readStringFields(json, field).firstOrNull()
            ?: error("Required JSON string field is missing: $field")
    }

    /**
     * 从 JSON 对象读取同名字符串字段的最后一项，处理图片块和菜谱本身都含 sourceUrl 的结构。
     *
     * @param json 包含目标字段的完整 JSON 对象文本。
     * @param field 要读取的字段名。
     * @return 最后一个已完成 JSON 转义还原的字符串；字段缺失时抛出异常。
     */
    private fun readRequiredLastStringField(json: String, field: String): String {
        return readStringFields(json, field).lastOrNull()
            ?: error("Required JSON string field is missing: $field")
    }

    /**
     * 读取 JSON 文本中某一字段名对应的全部字符串值，用于覆盖封面和正文图片路径。
     *
     * @param json 要扫描的 JSON 文本。
     * @param field 要匹配的字符串字段名。
     * @return 按原文顺序排列并完成转义还原的字段值列表。
     */
    private fun readStringFields(json: String, field: String): List<String> {
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.findAll(json).map { match ->
            decodeJsonString(match.groupValues[1])
        }.toList()
    }

    /**
     * 从 JSON 对象读取必需的整数字段。
     *
     * @param json 包含目标字段的完整 JSON 对象文本。
     * @param field 要读取的字段名。
     * @return 字段的整数值；字段缺失、类型错误或超出 Int 范围时抛出异常。
     */
    private fun readRequiredIntField(json: String, field: String): Int {
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+)")
        val encoded = pattern.find(json)?.groupValues?.get(1)
            ?: error("Required JSON integer field is missing: $field")
        return encoded.toInt()
    }

    /**
     * 从JSON对象读取必需的布尔字段。
     *
     * @param json 包含目标字段的完整JSON对象文本。
     * @param field 要读取的字段名。
     * @return 字段的true或false值；字段缺失或类型错误时抛出异常。
     */
    private fun readRequiredBooleanField(json: String, field: String): Boolean {
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*(true|false)")
        return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrict()
            ?: error("Required JSON boolean field is missing: $field")
    }

    /**
     * 读取只允许包含字符串元素的必需JSON数组。
     *
     * @param json 包含目标数组字段的完整JSON对象文本。
     * @param field 要读取的字段名。
     * @return 按原顺序完成JSON反转义的字符串列表。
     */
    private fun readRequiredStringArray(json: String, field: String): List<String> {
        val body = readRequiredArrayBody(json, field)
        val values = Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(body).map { match ->
            decodeJsonString(match.groupValues[1])
        }.toList()
        val remainder = body.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "")
        require(remainder.all { character -> character.isWhitespace() || character == ',' }) {
            "JSON array contains a non-string element: $field"
        }
        return values
    }

    /**
     * 按菜名从已切分的菜谱对象中查找唯一记录。
     *
     * @param recipeObjects 完整菜谱JSON对象列表。
     * @param recipeName 需要查找的菜名。
     * @return 菜名匹配的唯一菜谱对象文本。
     */
    private fun findRecipeObject(recipeObjects: List<String>, recipeName: String): String {
        return recipeObjects.single { recipeJson ->
            readRequiredStringField(recipeJson, "name") == recipeName
        }
    }

    /**
     * 按显示名从一道菜的结构化需求中查找唯一项目。
     *
     * @param recipeJson 完整菜谱JSON对象文本。
     * @param displayName 需要查找的需求显示名。
     * @return 显示名匹配的唯一需求对象文本。
     */
    private fun findRequirementObject(recipeJson: String, displayName: String): String {
        return splitTopLevelJsonObjects(
            readRequiredArrayBody(recipeJson, "requirements")
        ).single { requirementJson ->
            readRequiredStringField(requirementJson, "displayName") == displayName
        }
    }

    /**
     * 提取指定 JSON 数组内部的原始文本，并校验字符串转义与方括号层级完整。
     *
     * @param json 包含目标数组字段的 JSON 对象文本。
     * @param field 要读取的数组字段名。
     * @return 不包含最外层方括号的数组正文；字段缺失或括号未闭合时抛出异常。
     */
    private fun readRequiredArrayBody(json: String, field: String): String {
        val fieldPattern = Regex("\"${Regex.escape(field)}\"\\s*:")
        val fieldMatch = fieldPattern.find(json)
            ?: error("Required JSON array field is missing: $field")
        var index = fieldMatch.range.last + 1
        while (index < json.length && json[index].isWhitespace()) {
            index++
        }
        require(index < json.length && json[index] == '[') {
            "Required JSON field is not an array: $field"
        }

        val contentStart = index + 1
        var depth = 1
        var inString = false
        var escaped = false
        index++
        while (index < json.length) {
            val character = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return json.substring(contentStart, index)
                        }
                    }
                }
            }
            index++
        }
        error("JSON array is not closed: $field")
    }

    /**
     * 将数组正文切分成最外层对象，嵌套 sections、blocks 等对象不会被误拆。
     *
     * @param arrayBody 不含最外层方括号的 JSON 数组正文。
     * @return 按数组顺序排列的完整对象文本；遇到非对象元素或花括号损坏时抛出异常。
     */
    private fun splitTopLevelJsonObjects(arrayBody: String): List<String> {
        val objects = mutableListOf<String>()
        var objectStart = -1
        var depth = 0
        var inString = false
        var escaped = false

        arrayBody.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (character) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) {
                        objectStart = index
                    }
                    depth++
                }
                '}' -> {
                    require(depth > 0) { "Unexpected closing brace in JSON array" }
                    depth--
                    if (depth == 0) {
                        require(objectStart >= 0) { "JSON object start is missing" }
                        objects += arrayBody.substring(objectStart, index + 1)
                        objectStart = -1
                    }
                }
                else -> if (depth == 0) {
                    require(character.isWhitespace() || character == ',') {
                        "JSON array contains a non-object element"
                    }
                }
            }
        }

        require(!inString) { "JSON array contains an unterminated string" }
        require(depth == 0 && objectStart == -1) { "JSON array contains an unterminated object" }
        return objects
    }

    /**
     * 将正则捕获到的 JSON 字符串内容恢复为实际字符，覆盖标准单字符和 Unicode 转义。
     *
     * @param encoded 不含两侧引号的 JSON 字符串内容。
     * @return 完成反转义后的字符串；转义序列无效时抛出异常。
     */
    private fun decodeJsonString(encoded: String): String {
        val decoded = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index]
            if (character != '\\') {
                decoded.append(character)
                index++
                continue
            }

            require(index + 1 < encoded.length) { "JSON string ends with an escape marker" }
            when (val escaped = encoded[index + 1]) {
                '"', '\\', '/' -> decoded.append(escaped)
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'u' -> {
                    require(index + 6 <= encoded.length) { "JSON Unicode escape is incomplete" }
                    decoded.append(encoded.substring(index + 2, index + 6).toInt(16).toChar())
                    index += 4
                }
                else -> error("Unsupported JSON escape: \\$escaped")
            }
            index += 2
        }
        return decoded.toString()
    }

    /**
     * 读取并验证 WebP 文件最前面的 RIFF 容器签名和 WEBP 类型标识。
     *
     * @param imageFile 已确认存在的本地图片文件。
     * @param assetPath 用于测试失败信息的 assets 相对路径。
     * @return 无返回值；文件不足 12 字节或签名错误时由 JUnit 报告失败。
     */
    private fun assertWebpContainer(imageFile: File, assetPath: String) {
        assertTrue("Cook image asset is too short: $assetPath", imageFile.length() >= WEBP_HEADER_SIZE)
        val header = ByteArray(WEBP_HEADER_SIZE)
        DataInputStream(imageFile.inputStream()).use { input ->
            input.readFully(header)
        }
        assertEquals(
            "Invalid RIFF signature for $assetPath",
            "RIFF",
            String(header, 0, 4, StandardCharsets.US_ASCII)
        )
        assertEquals(
            "Invalid WEBP signature for $assetPath",
            "WEBP",
            String(header, 8, 4, StandardCharsets.US_ASCII)
        )
    }

    private data class BundledCookCategoryRecord(
        val id: String,
        val displayName: String,
        val description: String,
        val sortOrder: Int
    )

    private data class BundledCookRecipeRecord(
        val id: String,
        val name: String,
        val categoryId: String,
        val categoryName: String,
        val difficulty: String,
        val calories: String,
        val sourcePath: String,
        val requirementCount: Int,
        val sectionTitles: List<String>,
        val sourceUrl: String
    )

    private companion object {
        const val EXPECTED_SCHEMA_VERSION = 1
        const val EXPECTED_RECIPE_COUNT = 369
        const val EXPECTED_INGREDIENT_COUNT = 1001
        const val EXPECTED_COVER_IMAGE_RECIPE_COUNT = 177
        const val EXPECTED_LOCAL_IMAGE_COUNT = 346
        const val EXPECTED_REPOSITORY_URL = "https://github.com/Anduin2017/HowToCook"
        const val EXPECTED_SOURCE_COMMIT = "2b19c9e9ee926fd925a68207a57582a338813f9c"
        const val EXPECTED_SOURCE_LICENSE = "The Unlicense"
        const val CATALOG_ASSET_PATH = "cook/catalog.json"
        const val COOK_IMAGE_DIRECTORY_PATH = "cook/images"
        const val HOW_TO_COOK_LICENSE_ASSET_PATH = "cook/HOWTOCOOK_LICENSE.txt"
        const val THIRD_PARTY_NOTICES_ASSET_PATH = "third_party_licenses/THIRD_PARTY_NOTICES.txt"
        const val WEBP_HEADER_SIZE = 12

        val EXPECTED_CATEGORY_COUNTS = linkedMapOf(
            "vegetable_dish" to 63,
            "meat_dish" to 110,
            "aquatic" to 28,
            "breakfast" to 25,
            "staple" to 59,
            "semi-finished" to 10,
            "soup" to 23,
            "drink" to 23,
            "condiment" to 9,
            "dessert" to 19
        )
        val EXPECTED_DEFAULT_PANTRY_IDS = setOf("水", "盐", "白糖", "食用油")
        val EXPECTED_SECTION_TITLES = listOf(
            "简介",
            "必备原料和工具",
            "计算",
            "操作",
            "附加内容"
        )
        val REQUIRED_RECIPE_STRING_FIELDS = listOf(
            "id",
            "name",
            "categoryId",
            "categoryName",
            "summary",
            "difficulty",
            "calories",
            "coverImageAssetPath",
            "sourcePath",
            "sourceUrl"
        )
        val REQUIRED_RECIPE_ARRAY_FIELDS = listOf("tags", "requirements", "sections")
        val RECIPE_ID_PATTERN = Regex("recipe_[0-9a-f]{20}")
        val DIFFICULTY_PATTERN = Regex("★{1,5}")
        val CALORIE_PATTERN = Regex("\\d+ 大卡")
    }
}
