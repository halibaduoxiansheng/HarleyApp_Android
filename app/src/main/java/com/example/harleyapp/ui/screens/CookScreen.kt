package com.example.harleyapp.ui.screens

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.CookCatalog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** 菜名关键词搜索模式在页面内部使用的稳定编号。 */
private const val COOK_SEARCH_MODE_RECIPE = 0

/** 已有食材匹配模式在页面内部使用的稳定编号。 */
private const val COOK_SEARCH_MODE_INGREDIENT = 1

/** 食材选择横向列表一次最多展示的候选数量，避免为超长目录同时创建全部组件。 */
private const val COOK_INGREDIENT_OPTION_LIMIT = 48

/** 列表封面解码时允许的最大边长，兼顾双列卡片清晰度和滚动内存占用。 */
private const val COOK_CARD_IMAGE_MAX_DIMENSION = 640

/** 详情头图和步骤图解码时允许的最大边长。 */
private const val COOK_DETAIL_IMAGE_MAX_DIMENSION = 1280

/** 保存会改变菜谱结果集合或排序的筛选条件，用于只在条件真正变化时回到列表顶部。 */
private data class CookGridFilterIdentity(
    val searchMode: Int,
    val recipeQuery: String,
    val categoryId: String?,
    val selectedIngredientIds: List<String>,
    val includeDefaultPantry: Boolean,
    val directlyCookableOnly: Boolean
)

/**
 * 显示完整离线Cook功能，包括菜谱搜索、分类、食材匹配、今日推荐和独立详情页。
 *
 * 使用方法：
 * 宿主先通过CookRepository加载一次[CookCatalog]，再把结果传给本页面。全局搜索跳转时传入
 * [initialRecipeId]；页面成功处理一次后调用[onInitialRecipeConsumed]，防止后续重新进入时重复打开。
 * 页面只消费不可变目录，不直接读取或写入仓库，因而可以独立于具体持久化实现进行预览和测试。
 *
 * @param catalog 已成功加载的完整离线菜谱目录。
 * @param initialRecipeId 需要首次直接打开的菜谱稳定ID；普通进入时传null。
 * @param onInitialRecipeConsumed 初始跳转ID处理完成后的回调，无论ID是否存在都只应消费一次。
 * @param onBack 从Cook列表返回功能中心的回调。
 * @param modifier 宿主提供的页面尺寸和安全边距修饰器。
 * @return 无返回值，直接输出Cook列表、详情或随机推荐弹窗。
 */
@Composable
fun CookScreen(
    catalog: CookCatalog,
    initialRecipeId: String?,
    onInitialRecipeConsumed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchMode by rememberSaveable {
        mutableIntStateOf(COOK_SEARCH_MODE_RECIPE)
    }
    var recipeQuery by rememberSaveable {
        mutableStateOf("")
    }
    var ingredientQuery by rememberSaveable {
        mutableStateOf("")
    }
    var selectedCategoryId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedRecipeId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedIngredientIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    var includeDefaultPantry by rememberSaveable {
        mutableStateOf(true)
    }
    var directlyCookableOnly by rememberSaveable {
        mutableStateOf(false)
    }
    var randomRecipeId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var randomExcludedRecipeIds by rememberSaveable {
        mutableStateOf<List<String>>(emptyList())
    }
    val recipeGridState = rememberLazyGridState()
    val detailListState = rememberLazyListState()
    val gridFilterIdentity = remember(
        searchMode,
        recipeQuery,
        selectedCategoryId,
        selectedIngredientIds,
        includeDefaultPantry,
        directlyCookableOnly
    ) {
        CookGridFilterIdentity(
            searchMode = searchMode,
            recipeQuery = recipeQuery,
            categoryId = selectedCategoryId,
            selectedIngredientIds = selectedIngredientIds.toList(),
            includeDefaultPantry = includeDefaultPantry,
            directlyCookableOnly = directlyCookableOnly
        )
    }
    var previousGridFilterIdentity by remember {
        mutableStateOf(gridFilterIdentity)
    }
    val selectedRecipe = remember(catalog.recipes, selectedRecipeId) {
        catalog.recipes.firstOrNull { recipe -> recipe.id == selectedRecipeId }
    }
    val randomRecipe = remember(catalog.recipes, randomRecipeId) {
        catalog.recipes.firstOrNull { recipe -> recipe.id == randomRecipeId }
    }

    // 一次性跳转参数只在目录到达后处理，ID失效时也通知宿主清理，避免每次重组反复尝试。
    LaunchedEffect(initialRecipeId, catalog.recipes) {
        if (!initialRecipeId.isNullOrBlank()) {
            selectedRecipeId = catalog.recipes
                .firstOrNull { recipe -> recipe.id == initialRecipeId }
                ?.id
            onInitialRecipeConsumed()
        }
    }

    // 数据快照更新后清理已经不存在的分类和食材ID，避免旧保存状态产生永久空列表。
    LaunchedEffect(catalog.categories, catalog.ingredients) {
        if (selectedCategoryId != null && catalog.categories.none { category ->
                category.id == selectedCategoryId
            }
        ) {
            selectedCategoryId = null
        }
        val availableIngredientIds = catalog.ingredients.mapTo(hashSetOf()) { ingredient ->
            ingredient.id
        }
        selectedIngredientIds = selectedIngredientIds.filter(availableIngredientIds::contains)
    }

    // 每次打开另一道菜都从详情顶部开始；列表滚动位置由独立的recipeGridState继续保留。
    LaunchedEffect(selectedRecipeId) {
        if (selectedRecipeId != null) {
            detailListState.scrollToItem(0)
        }
    }

    // 仅在用户改变结果条件时回到第一项；进入详情再返回时条件未变，继续保留原来的列表位置。
    LaunchedEffect(gridFilterIdentity) {
        if (gridFilterIdentity != previousGridFilterIdentity) {
            recipeGridState.scrollToItem(0)
            previousGridFilterIdentity = gridFilterIdentity
        }
    }

    if (selectedRecipe != null) {
        CookRecipeDetailScreen(
            modifier = modifier,
            recipe = selectedRecipe,
            sourceCommit = catalog.sourceCommit,
            sourceLicense = catalog.sourceLicense,
            listState = detailListState,
            onBack = { selectedRecipeId = null }
        )
        return
    }

    BackHandler(onBack = onBack)

    CookCatalogScreen(
        modifier = modifier,
        catalog = catalog,
        searchMode = searchMode,
        recipeQuery = recipeQuery,
        ingredientQuery = ingredientQuery,
        selectedCategoryId = selectedCategoryId,
        selectedIngredientIds = selectedIngredientIds,
        includeDefaultPantry = includeDefaultPantry,
        directlyCookableOnly = directlyCookableOnly,
        gridState = recipeGridState,
        onBack = onBack,
        onSearchModeChanged = { mode -> searchMode = mode },
        onRecipeQueryChanged = { query -> recipeQuery = query },
        onIngredientQueryChanged = { query -> ingredientQuery = query },
        onCategoryChanged = { categoryId -> selectedCategoryId = categoryId },
        onIngredientToggled = { ingredientId ->
            selectedIngredientIds = if (ingredientId in selectedIngredientIds) {
                selectedIngredientIds.filterNot { selectedId -> selectedId == ingredientId }
            } else {
                selectedIngredientIds + ingredientId
            }
        },
        onClearIngredients = { selectedIngredientIds = emptyList() },
        onIncludeDefaultPantryChanged = { enabled -> includeDefaultPantry = enabled },
        onDirectlyCookableOnlyChanged = { enabled -> directlyCookableOnly = enabled },
        onOpenRecipe = { recipeId -> selectedRecipeId = recipeId },
        onShowDailyRecipe = {
            val dailyRecipe = selectDailyCookRecipe(
                recipes = catalog.recipes,
                date = LocalDate.now()
            )
            randomExcludedRecipeIds = emptyList()
            randomRecipeId = dailyRecipe?.id
        }
    )

    if (randomRecipe != null) {
        CookDailyRecipeDialog(
            recipe = randomRecipe,
            canShuffle = catalog.recipes.size > 1,
            onDismiss = { randomRecipeId = null },
            onShuffle = {
                val nextExcludedIds = LinkedHashSet(randomExcludedRecipeIds).apply {
                    add(randomRecipe.id)
                }
                val nextRecipe = selectAlternateCookRecipe(
                    recipes = catalog.recipes,
                    date = LocalDate.now(),
                    excludedRecipeIds = nextExcludedIds
                )
                if (nextRecipe != null) {
                    randomExcludedRecipeIds = nextExcludedIds.toList()
                    randomRecipeId = nextRecipe.id
                } else {
                    // 全部看过后从新一轮开始，但仍优先排除当前菜，避免点击后画面没有变化。
                    val restartedRecipe = selectAlternateCookRecipe(
                        recipes = catalog.recipes,
                        date = LocalDate.now(),
                        excludedRecipeIds = setOf(randomRecipe.id)
                    )
                    randomExcludedRecipeIds = listOf(randomRecipe.id)
                    randomRecipeId = restartedRecipe?.id ?: randomRecipe.id
                }
            },
            onOpenRecipe = {
                selectedRecipeId = randomRecipe.id
                randomRecipeId = null
            }
        )
    }
}

/**
 * 显示Cook主列表的全部筛选控件和双列自适应结果卡片。
 *
 * 使用方法：
 * 由[CookScreen]在未打开详情时调用。所有可变状态由父组件传入并通过回调更新，使本组件只负责
 * 目录派生结果和界面渲染，不持有仓库或导航对象。
 *
 * @param catalog 完整离线菜谱目录。
 * @param searchMode 当前搜索模式编号。
 * @param recipeQuery 菜谱关键词。
 * @param ingredientQuery 食材候选关键词。
 * @param selectedCategoryId 当前分类ID，null表示全部。
 * @param selectedIngredientIds 用户明确选择的已有食材ID。
 * @param includeDefaultPantry true表示允许基础常备调料自动满足调料需求。
 * @param directlyCookableOnly true表示食材模式仅显示无需补充必需食材的菜谱。
 * @param gridState 需要跨详情往返保留的网格滚动状态。
 * @param onBack 返回功能中心的回调。
 * @param onSearchModeChanged 切换搜索模式的回调。
 * @param onRecipeQueryChanged 修改菜谱关键词的回调。
 * @param onIngredientQueryChanged 修改食材候选关键词的回调。
 * @param onCategoryChanged 修改分类的回调。
 * @param onIngredientToggled 增加或移除一个已有食材的回调。
 * @param onClearIngredients 清空全部已有食材的回调。
 * @param onIncludeDefaultPantryChanged 修改常备调料开关的回调。
 * @param onDirectlyCookableOnlyChanged 修改“只看能做”筛选的回调。
 * @param onOpenRecipe 打开指定菜谱详情的回调。
 * @param onShowDailyRecipe 打开今日推荐弹窗的回调。
 * @param modifier 外部页面修饰器。
 * @return 无返回值。
 */
@Composable
private fun CookCatalogScreen(
    catalog: CookCatalog,
    searchMode: Int,
    recipeQuery: String,
    ingredientQuery: String,
    selectedCategoryId: String?,
    selectedIngredientIds: List<String>,
    includeDefaultPantry: Boolean,
    directlyCookableOnly: Boolean,
    gridState: LazyGridState,
    onBack: () -> Unit,
    onSearchModeChanged: (Int) -> Unit,
    onRecipeQueryChanged: (String) -> Unit,
    onIngredientQueryChanged: (String) -> Unit,
    onCategoryChanged: (String?) -> Unit,
    onIngredientToggled: (String) -> Unit,
    onClearIngredients: () -> Unit,
    onIncludeDefaultPantryChanged: (Boolean) -> Unit,
    onDirectlyCookableOnlyChanged: (Boolean) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onShowDailyRecipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 仅使用模型层固定ID白名单，绝不通过别名扩展，避免“糖”别名把冰糖、绵白糖误算为常备。
    val defaultPantryIds = remember(catalog.ingredients) {
        resolveDefaultCookPantrySeasoningIds(catalog.ingredients)
    }
    val recipeSearchState = rememberCookRecipeSearchResults(
        recipes = catalog.recipes,
        query = recipeQuery,
        categoryId = selectedCategoryId,
        enabled = searchMode == COOK_SEARCH_MODE_RECIPE
    )
    val ingredientMatchState = rememberCookIngredientMatchResults(
        recipes = catalog.recipes,
        selectedIngredientIds = selectedIngredientIds,
        pantrySeasoningIds = if (includeDefaultPantry) defaultPantryIds else emptySet(),
        categoryId = selectedCategoryId,
        enabled = searchMode == COOK_SEARCH_MODE_INGREDIENT
    )
    val visibleIngredientMatches = remember(
        ingredientMatchState.results,
        directlyCookableOnly
    ) {
        if (directlyCookableOnly) {
            ingredientMatchState.results.filter(CookIngredientMatchResult::isDirectlyCookable)
        } else {
            ingredientMatchState.results
        }
    }
    val cardItems = remember(searchMode, recipeSearchState.results, visibleIngredientMatches) {
        if (searchMode == COOK_SEARCH_MODE_INGREDIENT) {
            visibleIngredientMatches.map { result ->
                CookRecipeCardItem(recipe = result.recipe, ingredientMatch = result)
            }
        } else {
            recipeSearchState.results.map { recipe ->
                CookRecipeCardItem(recipe = recipe, ingredientMatch = null)
            }
        }
    }
    val resultIsLoading = if (searchMode == COOK_SEARCH_MODE_INGREDIENT) {
        ingredientMatchState.isLoading
    } else {
        recipeSearchState.isLoading
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CookPageHeader(
            title = "Cook",
            subtitle = "${catalog.recipes.size} 道离线菜谱 · 图片和做法无需联网",
            onBack = onBack,
            actionLabel = "今日随机",
            actionEnabled = catalog.recipes.isNotEmpty(),
            onAction = onShowDailyRecipe
        )

        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = gridState,
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 28.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(
                key = "cook_search_mode",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                CookSearchModeSelector(
                    selectedMode = searchMode,
                    onModeChanged = onSearchModeChanged
                )
            }

            item(
                key = "cook_search_controls_$searchMode",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                if (searchMode == COOK_SEARCH_MODE_INGREDIENT) {
                    CookIngredientSelector(
                        ingredients = catalog.ingredients,
                        query = ingredientQuery,
                        selectedIngredientIds = selectedIngredientIds,
                        includeDefaultPantry = includeDefaultPantry,
                        defaultPantryCount = defaultPantryIds.size,
                        directlyCookableOnly = directlyCookableOnly,
                        onQueryChanged = onIngredientQueryChanged,
                        onIngredientToggled = onIngredientToggled,
                        onClearIngredients = onClearIngredients,
                        onIncludeDefaultPantryChanged = onIncludeDefaultPantryChanged,
                        onDirectlyCookableOnlyChanged = onDirectlyCookableOnlyChanged
                    )
                } else {
                    CookRecipeSearchField(
                        query = recipeQuery,
                        onQueryChanged = onRecipeQueryChanged
                    )
                }
            }

            item(
                key = "cook_category_filters",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                CookCategoryFilters(
                    catalog = catalog,
                    selectedCategoryId = selectedCategoryId,
                    onCategoryChanged = onCategoryChanged
                )
            }

            item(
                key = "cook_result_summary",
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    text = when {
                        resultIsLoading && searchMode == COOK_SEARCH_MODE_INGREDIENT ->
                            "正在根据现有食材重新计算可做菜谱…"
                        resultIsLoading -> "正在搜索菜谱…"
                        cardItems.isEmpty() -> "没有符合当前条件的菜谱"
                        searchMode == COOK_SEARCH_MODE_INGREDIENT &&
                            selectedIngredientIds.isEmpty() ->
                            "尚未选择食材，先按缺料较少的顺序展示 ${cardItems.size} 道菜"
                        searchMode == COOK_SEARCH_MODE_INGREDIENT ->
                            "已按现有食材匹配 ${cardItems.size} 道菜，卡片会列出仍需补充的材料"
                        recipeQuery.isBlank() -> "当前显示 ${cardItems.size} 道菜"
                        else -> "“${recipeQuery.trim()}”找到 ${cardItems.size} 道菜"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (resultIsLoading) {
                item(
                    key = "loading_cook_results_$searchMode",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    CookLoadingResultsCard(
                        ingredientMode = searchMode == COOK_SEARCH_MODE_INGREDIENT
                    )
                }
            } else if (cardItems.isEmpty()) {
                item(
                    key = "empty_cook_results",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    CookEmptyResultsCard(
                        ingredientMode = searchMode == COOK_SEARCH_MODE_INGREDIENT
                    )
                }
            } else {
                lazyGridItems(
                    items = cardItems,
                    key = { item -> item.recipe.id },
                    contentType = { "cook_recipe_card" }
                ) { item ->
                    CookRecipeCard(
                        recipe = item.recipe,
                        ingredientMatch = item.ingredientMatch,
                        onOpen = { onOpenRecipe(item.recipe.id) }
                    )
                }
            }
        }
    }
}

/**
 * 显示页面顶部返回栏，并可选显示一个右侧操作按钮。
 *
 * @param title 页面主标题。
 * @param subtitle 页面副标题。
 * @param onBack 点击返回按钮后的回调。
 * @param actionLabel 右侧操作按钮文字；为null时不显示按钮。
 * @param actionEnabled true表示右侧操作可点击。
 * @param onAction 点击右侧操作后的回调。
 * @return 无返回值。
 */
@Composable
private fun CookPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(text = "返回")
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (actionLabel != null) {
            Button(
                enabled = actionEnabled,
                onClick = onAction
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

/**
 * 显示“搜菜谱”和“按食材找菜”两种入口。
 *
 * @param selectedMode 当前模式编号。
 * @param onModeChanged 用户选择另一模式后的回调。
 * @return 无返回值。
 */
@Composable
private fun CookSearchModeSelector(
    selectedMode: Int,
    onModeChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedMode == COOK_SEARCH_MODE_RECIPE,
            onClick = { onModeChanged(COOK_SEARCH_MODE_RECIPE) },
            label = { Text(text = "搜菜谱") }
        )
        FilterChip(
            selected = selectedMode == COOK_SEARCH_MODE_INGREDIENT,
            onClick = { onModeChanged(COOK_SEARCH_MODE_INGREDIENT) },
            label = { Text(text = "按食材找菜") }
        )
    }
}

/** 保存一次后台菜谱搜索所需的可比较参数。 */
private data class CookRecipeSearchRequest(
    val query: String,
    val categoryId: String?,
    val enabled: Boolean
)

/** 保存一次后台食材匹配所需的可比较参数。 */
private data class CookIngredientMatchRequest(
    val selectedIngredientIds: Set<String>,
    val pantrySeasoningIds: Set<String>,
    val categoryId: String?,
    val enabled: Boolean
)

/** 保存菜谱搜索结果及其所属请求，防止新条件错误展示旧请求的数据。 */
private data class CookRecipeSearchUiState(
    val request: CookRecipeSearchRequest,
    val results: List<CookRecipe>,
    val isLoading: Boolean
)

/** 保存食材匹配结果及其所属请求，供界面准确区分计算中和无结果。 */
private data class CookIngredientMatchUiState(
    val request: CookIngredientMatchRequest,
    val results: List<CookIngredientMatchResult>,
    val isLoading: Boolean
)

/** 保存后台食材候选搜索所需的稳定参数。 */
private data class CookIngredientOptionRequest(
    val query: String,
    val selectedIngredientIds: Set<String>
)

/** 保存食材候选结果及其请求身份，避免输入变化时把旧候选当作新结果。 */
private data class CookIngredientOptionUiState(
    val request: CookIngredientOptionRequest,
    val results: List<CookIngredient>,
    val isLoading: Boolean
)

/**
 * 在后台线程执行菜谱全文搜索，并在输入连续变化时等待短暂防抖窗口。
 *
 * 使用方法：
 * Cook列表把当前关键词、分类和模式传入。本函数只在菜谱搜索模式启用时计算；输入非空时等待
 * 180毫秒，避免用户连续输入过程中反复扫描全部正文。返回值同时携带请求身份和加载状态，输入
 * 变化后不会再把上一请求的结果标成当前结果。
 *
 * @param recipes 完整菜谱列表。
 * @param query 当前关键词。
 * @param categoryId 当前分类ID，null表示全部。
 * @param enabled true表示当前正在显示菜谱搜索模式。
 * @return 当前请求对应的搜索状态；未启用时返回非加载的空结果。
 */
@Composable
private fun rememberCookRecipeSearchResults(
    recipes: List<CookRecipe>,
    query: String,
    categoryId: String?,
    enabled: Boolean
): CookRecipeSearchUiState {
    val request = remember(query, categoryId, enabled) {
        CookRecipeSearchRequest(
            query = query,
            categoryId = categoryId,
            enabled = enabled
        )
    }
    val initialState = CookRecipeSearchUiState(
        request = request,
        results = emptyList(),
        isLoading = request.enabled
    )
    val resultState = produceState(
        initialValue = initialState,
        key1 = recipes,
        key2 = request
    ) {
        if (!request.enabled) {
            value = CookRecipeSearchUiState(
                request = request,
                results = emptyList(),
                isLoading = false
            )
            return@produceState
        }
        value = CookRecipeSearchUiState(
            request = request,
            results = emptyList(),
            isLoading = true
        )
        if (request.query.isNotBlank()) {
            delay(180L)
        }
        val results = withContext(Dispatchers.Default) {
            searchCookRecipes(
                recipes = recipes,
                filter = CookRecipeSearchFilter(
                    query = request.query,
                    categoryId = request.categoryId
                )
            )
        }
        value = CookRecipeSearchUiState(
            request = request,
            results = results,
            isLoading = false
        )
    }
    return resultState.value.takeIf { state -> state.request == request } ?: initialState
}

/**
 * 在后台线程执行全目录食材匹配，避免大量需求分组计算阻塞Compose主线程。
 *
 * @param recipes 完整菜谱列表。
 * @param selectedIngredientIds 用户明确选择的已有食材ID。
 * @param pantrySeasoningIds 当前允许自动覆盖的常备调料ID。
 * @param categoryId 当前分类ID，null表示全部。
 * @param enabled true表示当前正在显示食材匹配模式。
 * @return 当前请求对应的匹配状态；未启用时返回非加载的空结果。
 */
@Composable
private fun rememberCookIngredientMatchResults(
    recipes: List<CookRecipe>,
    selectedIngredientIds: Collection<String>,
    pantrySeasoningIds: Collection<String>,
    categoryId: String?,
    enabled: Boolean
): CookIngredientMatchUiState {
    val request = remember(
        selectedIngredientIds,
        pantrySeasoningIds,
        categoryId,
        enabled
    ) {
        CookIngredientMatchRequest(
            selectedIngredientIds = selectedIngredientIds.toSet(),
            pantrySeasoningIds = pantrySeasoningIds.toSet(),
            categoryId = categoryId,
            enabled = enabled
        )
    }
    val initialState = CookIngredientMatchUiState(
        request = request,
        results = emptyList(),
        isLoading = request.enabled
    )
    val resultState = produceState(
        initialValue = initialState,
        key1 = recipes,
        key2 = request
    ) {
        if (!request.enabled) {
            value = CookIngredientMatchUiState(
                request = request,
                results = emptyList(),
                isLoading = false
            )
            return@produceState
        }
        value = CookIngredientMatchUiState(
            request = request,
            results = emptyList(),
            isLoading = true
        )
        val results = withContext(Dispatchers.Default) {
            matchCookRecipesByIngredients(
                recipes = recipes,
                selectedIngredientIds = request.selectedIngredientIds,
                pantrySeasoningIds = request.pantrySeasoningIds,
                categoryId = request.categoryId
            )
        }
        value = CookIngredientMatchUiState(
            request = request,
            results = results,
            isLoading = false
        )
    }
    return resultState.value.takeIf { state -> state.request == request } ?: initialState
}

/**
 * 在后台筛选食材候选，避免输入时在Compose主线程反复规范化和排序完整食材目录。
 *
 * @param ingredients 完整食材目录。
 * @param query 当前食材搜索词。
 * @param selectedIngredientIds 已选食材ID集合，已选项会在候选中优先显示。
 * @return 当前请求对应的候选搜索状态。
 */
@Composable
private fun rememberCookIngredientOptions(
    ingredients: List<CookIngredient>,
    query: String,
    selectedIngredientIds: Set<String>
): CookIngredientOptionUiState {
    val request = remember(query, selectedIngredientIds) {
        CookIngredientOptionRequest(
            query = query,
            selectedIngredientIds = selectedIngredientIds.toSet()
        )
    }
    val initialState = CookIngredientOptionUiState(
        request = request,
        results = emptyList(),
        isLoading = true
    )
    val resultState = produceState(
        initialValue = initialState,
        key1 = ingredients,
        key2 = request
    ) {
        value = CookIngredientOptionUiState(
            request = request,
            results = emptyList(),
            isLoading = true
        )
        if (request.query.isNotBlank()) {
            delay(120L)
        }
        val results = withContext(Dispatchers.Default) {
            findCookIngredientOptions(
                ingredients = ingredients,
                query = request.query,
                selectedIngredientIds = request.selectedIngredientIds
            )
        }
        value = CookIngredientOptionUiState(
            request = request,
            results = results,
            isLoading = false
        )
    }
    return resultState.value.takeIf { state -> state.request == request } ?: initialState
}

/**
 * 显示菜名、食材、简介和步骤共用的关键词输入框。
 *
 * @param query 当前关键词。
 * @param onQueryChanged 用户修改关键词后的回调。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeSearchField(
    query: String,
    onQueryChanged: (String) -> Unit
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        label = { Text(text = "搜索菜名、食材或做法") },
        placeholder = { Text(text = "例如：鸡蛋、番茄、空气炸锅") },
        trailingIcon = if (query.isNotEmpty()) {
            {
                TextButton(onClick = { onQueryChanged("") }) {
                    Text(text = "清除")
                }
            }
        } else {
            null
        }
    )
}

/**
 * 显示可多选食材、候选搜索、常备调料和“只看能做”控制区。
 *
 * 使用方法：
 * 食材是否满足菜谱只通过稳定ID判断，本组件的文本搜索仅用于找到目录项，不会把自由文字直接当成
 * 已有食材。这样可以避免“油”误匹配“蚝油”以及别名歧义。
 *
 * @param ingredients 完整规范食材目录。
 * @param query 当前候选搜索词。
 * @param selectedIngredientIds 用户已选食材ID。
 * @param includeDefaultPantry true表示开启默认常备调料。
 * @param defaultPantryCount 当前目录中被精确识别为常备调料的数量。
 * @param directlyCookableOnly true表示只显示必需材料齐全的菜谱。
 * @param onQueryChanged 修改候选搜索词的回调。
 * @param onIngredientToggled 切换一个食材选择状态的回调。
 * @param onClearIngredients 清空全部已选食材的回调。
 * @param onIncludeDefaultPantryChanged 修改常备调料开关的回调。
 * @param onDirectlyCookableOnlyChanged 修改“只看能做”的回调。
 * @return 无返回值。
 */
@Composable
private fun CookIngredientSelector(
    ingredients: List<CookIngredient>,
    query: String,
    selectedIngredientIds: List<String>,
    includeDefaultPantry: Boolean,
    defaultPantryCount: Int,
    directlyCookableOnly: Boolean,
    onQueryChanged: (String) -> Unit,
    onIngredientToggled: (String) -> Unit,
    onClearIngredients: () -> Unit,
    onIncludeDefaultPantryChanged: (Boolean) -> Unit,
    onDirectlyCookableOnlyChanged: (Boolean) -> Unit
) {
    val selectedIds = remember(selectedIngredientIds) {
        selectedIngredientIds.toHashSet()
    }
    val selectedIngredients = remember(ingredients, selectedIds) {
        ingredients.filter { ingredient -> ingredient.id in selectedIds }
    }
    val ingredientOptionState = rememberCookIngredientOptions(
        ingredients = ingredients,
        query = query,
        selectedIngredientIds = selectedIds
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            value = query,
            onValueChange = onQueryChanged,
            singleLine = true,
            label = { Text(text = "添加家里已有的食材") },
            placeholder = { Text(text = "输入鸡蛋、土豆、番茄等") },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    TextButton(onClick = { onQueryChanged("") }) {
                        Text(text = "清除")
                    }
                }
            } else {
                null
            }
        )

        if (ingredientOptionState.isLoading) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = "正在搜索食材…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (ingredientOptionState.results.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                text = "没有找到对应的规范食材，请尝试更短的名称或常见别名",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lazyRowItems(
                    items = ingredientOptionState.results,
                    key = CookIngredient::id
                ) { ingredient ->
                    FilterChip(
                        selected = ingredient.id in selectedIds,
                        onClick = { onIngredientToggled(ingredient.id) },
                        label = { Text(text = ingredient.displayName) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "已选 ${selectedIngredients.size} 种食材",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            TextButton(
                enabled = selectedIngredients.isNotEmpty(),
                onClick = onClearIngredients
            ) {
                Text(text = "清空")
            }
        }

        if (selectedIngredients.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                lazyRowItems(
                    items = selectedIngredients,
                    key = { ingredient -> "selected_${ingredient.id}" }
                ) { ingredient ->
                    FilterChip(
                        selected = true,
                        onClick = { onIngredientToggled(ingredient.id) },
                        label = { Text(text = "${ingredient.displayName} ×") }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = includeDefaultPantry,
                    role = Role.Switch,
                    onValueChange = onIncludeDefaultPantryChanged
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "默认常备基础调料",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "仅自动计入水、盐、白糖、食用油，共 $defaultPantryCount 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = includeDefaultPantry,
                onCheckedChange = null
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = directlyCookableOnly,
                onClick = {
                    onDirectlyCookableOnlyChanged(!directlyCookableOnly)
                },
                label = { Text(text = "只看能直接做") }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "工具和可选材料不会计入缺料数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示“全部”和所有上游菜谱分类的横向筛选条。
 *
 * @param catalog 完整目录，用于读取分类和各分类菜谱数量。
 * @param selectedCategoryId 当前分类ID，null表示全部。
 * @param onCategoryChanged 用户选择分类后的回调。
 * @return 无返回值。
 */
@Composable
private fun CookCategoryFilters(
    catalog: CookCatalog,
    selectedCategoryId: String?,
    onCategoryChanged: (String?) -> Unit
) {
    val categoryCounts = remember(catalog.recipes) {
        catalog.recipes.groupingBy(CookRecipe::categoryId).eachCount()
    }
    val sortedCategories = remember(catalog.categories) {
        catalog.categories.sortedWith(
            compareBy<com.example.harleyapp.model.CookCategory> { category -> category.sortOrder }
                .thenBy { category -> category.displayName }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedCategoryId == null,
            onClick = { onCategoryChanged(null) },
            label = { Text(text = "全部 ${catalog.recipes.size}") }
        )
        sortedCategories.forEach { category ->
            FilterChip(
                selected = selectedCategoryId == category.id,
                onClick = { onCategoryChanged(category.id) },
                label = {
                    Text(text = "${category.displayName} ${categoryCounts[category.id] ?: 0}")
                }
            )
        }
    }
}

/** 保存双模式结果列表中一张卡片所需的统一数据。 */
private data class CookRecipeCardItem(
    val recipe: CookRecipe,
    val ingredientMatch: CookIngredientMatchResult?
)

/**
 * 显示一张含本地封面、菜名、摘要和可选缺料解释的菜谱卡片。
 *
 * @param recipe 当前菜谱。
 * @param ingredientMatch 食材模式下的解释结果；普通关键词模式传null。
 * @param onOpen 点击卡片进入详情的回调。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeCard(
    recipe: CookRecipe,
    ingredientMatch: CookIngredientMatchResult?,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        CookAssetImage(
            assetPath = recipe.coverImageAssetPath,
            contentDescription = "${recipe.name}成品图",
            categoryId = recipe.categoryId,
            categoryName = recipe.categoryName,
            maxDecodeDimension = COOK_CARD_IMAGE_MAX_DIMENSION,
            cropToFrame = true,
            modifier = Modifier.fillMaxWidth()
        )

        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = recipe.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildCookRecipeMetadata(recipe),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (ingredientMatch == null) {
                if (recipe.summary.isNotBlank()) {
                    Text(
                        text = recipe.summary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                CookIngredientMatchExplanation(result = ingredientMatch)
            }

            Text(
                modifier = Modifier.align(Alignment.End),
                text = "查看做法 ›",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 在菜谱卡片内显示匹配数量、缺少内容和工具提示。
 *
 * @param result 当前菜谱的结构化食材匹配结果。
 * @return 无返回值。
 */
@Composable
private fun CookIngredientMatchExplanation(result: CookIngredientMatchResult) {
    val missingNames = result.missingRequirements
        .map(CookIngredientRequirement::displayName)
        .filter(String::isNotBlank)
    val toolNames = result.ignoredRequirements
        .filter { requirement -> requirement.kind == CookRequirementKind.TOOL }
        .map(CookIngredientRequirement::displayName)
        .filter(String::isNotBlank)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (result.isDirectlyCookable) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = if (result.isDirectlyCookable) {
                    "可直接做 · 已有 ${result.matchedRequirementCount}/${result.requiredRequirementCount}"
                } else {
                    "已有 ${result.matchedRequirementCount}/${result.requiredRequirementCount} · 缺 ${missingNames.size} 项"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (result.isDirectlyCookable) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (missingNames.isNotEmpty()) {
                Text(
                    text = "还缺：${summarizeCookNames(missingNames)}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (toolNames.isNotEmpty()) {
                Text(
                    text = "工具提示：${summarizeCookNames(toolNames)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (result.automaticallyCoveredSeasonings.isNotEmpty()) {
                Text(
                    text = "常备调料已覆盖 ${result.automaticallyCoveredSeasonings.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 显示当前结果请求仍在后台计算的明确状态，避免把加载中的空列表误报为无结果。
 *
 * @param ingredientMode true表示正在执行食材匹配，false表示正在执行菜谱关键词搜索。
 * @return 无返回值。
 */
@Composable
private fun CookLoadingResultsCard(ingredientMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(
                modifier = Modifier.weight(1f),
                text = if (ingredientMode) {
                    "正在核对必需食材和缺料…"
                } else {
                    "正在搜索菜名、食材和做法…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示当前筛选无结果时的恢复建议。
 *
 * @param ingredientMode true表示当前处于按食材匹配模式。
 * @return 无返回值。
 */
@Composable
private fun CookEmptyResultsCard(ingredientMode: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "暂时没有匹配结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (ingredientMode) {
                    "可以继续添加已有食材、关闭“只看能直接做”或切换到全部分类。"
                } else {
                    "可以缩短关键词、清除分类，或改用“按食材找菜”。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示当天稳定推荐的一道菜，并提供换一道和进入详情操作。
 *
 * @param recipe 当前预览菜谱。
 * @param canShuffle true表示目录中至少还有另一道菜可选。
 * @param onDismiss 关闭弹窗的回调。
 * @param onShuffle 排除本轮已展示菜谱后选择下一道的回调。
 * @param onOpenRecipe 进入当前菜谱详情的回调。
 * @return 无返回值。
 */
@Composable
private fun CookDailyRecipeDialog(
    recipe: CookRecipe,
    canShuffle: Boolean,
    onDismiss: () -> Unit,
    onShuffle: () -> Unit,
    onOpenRecipe: () -> Unit
) {
    val dialogScrollState = rememberScrollState()

    // “换一道”后从新菜的图片和摘要开始展示，不沿用上一道菜的弹窗滚动位置。
    LaunchedEffect(recipe.id) {
        dialogScrollState.scrollTo(0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "今日推荐 · ${recipe.name}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(dialogScrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CookAssetImage(
                    assetPath = recipe.coverImageAssetPath,
                    contentDescription = "${recipe.name}今日推荐图",
                    categoryId = recipe.categoryId,
                    categoryName = recipe.categoryName,
                    maxDecodeDimension = COOK_CARD_IMAGE_MAX_DIMENSION,
                    cropToFrame = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                )
                Text(
                    text = buildCookRecipeMetadata(recipe),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (recipe.summary.isNotBlank()) {
                    Text(
                        text = recipe.summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "今日推荐在同一天保持不变；“换一道”会避开本轮已经看过的菜。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canShuffle,
                    onClick = onShuffle
                ) {
                    Text(text = if (canShuffle) "换一道" else "当前只有这一道菜")
                }
            }
        },
        confirmButton = {
            Button(onClick = onOpenRecipe) {
                Text(text = "查看做法")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

/**
 * 显示一道菜的完整结构化详情，包括所有原料、工具、内容块和固定源码链接。
 *
 * @param recipe 当前完整菜谱。
 * @param sourceCommit 本地快照对应的上游提交。
 * @param sourceLicense 上游内容许可证名称。
 * @param listState 详情列表滚动状态。
 * @param onBack 返回Cook搜索列表的回调。
 * @param modifier 外部页面修饰器。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeDetailScreen(
    recipe: CookRecipe,
    sourceCommit: String,
    sourceLicense: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    var sourceOpenMessage by remember(recipe.id) {
        mutableStateOf<String?>(null)
    }
    val safeSourceUrl = remember(recipe.sourceUrl) {
        recipe.sourceUrl.takeIf(::isSafeCookSourceUrl)
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CookPageHeader(
            title = recipe.name,
            subtitle = "${recipe.categoryName} · 完整离线做法",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 6.dp,
                end = 18.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "hero_${recipe.id}") {
                CookRecipeHero(recipe = recipe)
            }

            item(key = "requirements_${recipe.id}") {
                CookRequirementsCard(requirements = recipe.requirements)
            }

            recipe.sections.forEachIndexed { sectionIndex, section ->
                if (section.title.isNotBlank() || section.blocks.isEmpty()) {
                    item(
                        key = "section_header_${recipe.id}_${section.id}_$sectionIndex"
                    ) {
                        CookRecipeSectionHeaderCard(section = section)
                    }
                }
                section.blocks.forEachIndexed { blockIndex, block ->
                    item(
                        key = "section_block_${recipe.id}_${section.id}_${sectionIndex}_$blockIndex"
                    ) {
                        CookRecipeContentBlockCard(
                            block = block,
                            recipe = recipe,
                            blockIndex = blockIndex
                        )
                    }
                }
            }

            item(key = "source_${recipe.id}") {
                CookSourceCard(
                    sourcePath = recipe.sourcePath,
                    sourceCommit = sourceCommit,
                    sourceLicense = sourceLicense,
                    canOpenSource = safeSourceUrl != null,
                    operationMessage = sourceOpenMessage,
                    onOpenSource = {
                        val sourceUrl = safeSourceUrl
                        if (sourceUrl == null) {
                            sourceOpenMessage = "源码地址无效，已阻止打开"
                        } else {
                            sourceOpenMessage = try {
                                uriHandler.openUri(sourceUrl)
                                null
                            } catch (_: IllegalArgumentException) {
                                "系统无法识别这个源码地址"
                            } catch (_: Exception) {
                                "暂时无法打开浏览器，请稍后重试"
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * 显示详情页头图、菜名、元数据和简介。
 *
 * @param recipe 当前菜谱。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeHero(recipe: CookRecipe) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        CookAssetImage(
            assetPath = recipe.coverImageAssetPath,
            contentDescription = "${recipe.name}成品图",
            categoryId = recipe.categoryId,
            categoryName = recipe.categoryName,
            maxDecodeDimension = COOK_DETAIL_IMAGE_MAX_DIMENSION,
            cropToFrame = false,
            modifier = Modifier.fillMaxWidth()
        )
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = recipe.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildCookRecipeMetadata(recipe),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (recipe.summary.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = recipe.summary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

/**
 * 按主食材、调料和工具分组显示结构化需求，并保留用量与可选标记。
 *
 * @param requirements 当前菜谱的全部结构化需求。
 * @return 无返回值。
 */
@Composable
private fun CookRequirementsCard(requirements: List<CookIngredientRequirement>) {
    val ingredientRequirements = requirements.filter { requirement ->
        requirement.kind == CookRequirementKind.INGREDIENT
    }
    val seasoningRequirements = requirements.filter { requirement ->
        requirement.kind == CookRequirementKind.SEASONING
    }
    val toolRequirements = requirements.filter { requirement ->
        requirement.kind == CookRequirementKind.TOOL
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "原料与工具速览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (requirements.isEmpty()) {
                Text(
                    text = "本菜谱未提供可结构化的原料清单，请以下方原始章节为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                CookRequirementGroup(
                    title = "食材",
                    requirements = ingredientRequirements
                )
                CookRequirementGroup(
                    title = "调料",
                    requirements = seasoningRequirements
                )
                CookRequirementGroup(
                    title = "工具",
                    requirements = toolRequirements
                )
            }
        }
    }
}

/**
 * 显示一类结构化需求；列表为空时不创建无意义标题。
 *
 * @param title 分组标题。
 * @param requirements 本组需求列表。
 * @return 无返回值。
 */
@Composable
private fun CookRequirementGroup(
    title: String,
    requirements: List<CookIngredientRequirement>
) {
    if (requirements.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        requirements.forEach { requirement ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(requirement.displayName.ifBlank { "未命名项目" })
                            if (requirement.optional) append("（可选）")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (requirement.amount.isNotBlank()) {
                        Text(
                            text = requirement.amount,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示一个上游命名章节的标题；正文块由独立LazyColumn项目继续渲染。
 *
 * @param section 当前章节。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeSectionHeaderCard(section: CookRecipeSection) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = section.title.ifBlank { "菜谱内容" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (section.blocks.isEmpty()) {
                Text(
                    text = "本章节暂无正文。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 在独立LazyColumn项目中显示一个正文块，使离屏步骤图片能够及时释放组合和位图状态。
 *
 * @param block 当前结构化正文块。
 * @param recipe 所属菜谱，用于图片分类兜底和无障碍说明。
 * @param blockIndex 当前块在章节内的原始顺序。
 * @return 无返回值。
 */
@Composable
private fun CookRecipeContentBlockCard(
    block: CookContentBlock,
    recipe: CookRecipe,
    blockIndex: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            CookContentBlockView(
                block = block,
                recipe = recipe,
                blockIndex = blockIndex
            )
        }
    }
}

/**
 * 渲染一个安全的结构化菜谱内容块。
 *
 * 使用方法：
 * 仅接受[CookContentBlock]已有类型，不解析HTML或执行脚本。列表、表格、引用、代码、图片和分隔线
 * 均保留上游顺序；后续模型新增密封类型时，编译器会要求本函数补齐渲染分支。
 *
 * @param block 当前内容块。
 * @param recipe 所属菜谱。
 * @param blockIndex 当前块在章节内的顺序，仅用于补充图片说明。
 * @return 无返回值。
 */
@Composable
private fun CookContentBlockView(
    block: CookContentBlock,
    recipe: CookRecipe,
    blockIndex: Int
) {
    when (block) {
        is CookContentBlock.Heading -> {
            Text(
                text = block.text,
                style = when (block.level) {
                    1, 2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.Bold
            )
        }

        is CookContentBlock.Paragraph -> {
            SelectionContainer {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        is CookContentBlock.BulletList -> {
            CookTextList(
                items = block.items,
                numbered = false
            )
        }

        is CookContentBlock.NumberedList -> {
            CookTextList(
                items = block.items,
                numbered = true
            )
        }

        is CookContentBlock.Image -> {
            CookAssetImage(
                assetPath = block.assetPath,
                contentDescription = block.contentDescription.ifBlank {
                    "${recipe.name}步骤图 ${blockIndex + 1}"
                },
                categoryId = recipe.categoryId,
                categoryName = recipe.categoryName,
                maxDecodeDimension = COOK_DETAIL_IMAGE_MAX_DIMENSION,
                cropToFrame = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
            )
        }

        is CookContentBlock.Table -> {
            CookContentTable(
                headers = block.headers,
                rows = block.rows
            )
        }

        is CookContentBlock.Quote -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                SelectionContainer {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        is CookContentBlock.Code -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        CookContentBlock.Divider -> {
            HorizontalDivider()
        }
    }
}

/**
 * 显示无序原料列表或有序操作步骤列表。
 *
 * @param items 按原始顺序排列的文字项。
 * @param numbered true使用数字编号，false使用圆点。
 * @return 无返回值。
 */
@Composable
private fun CookTextList(
    items: List<String>,
    numbered: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, text ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    modifier = Modifier.widthIn(min = 22.dp),
                    text = if (numbered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (numbered) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                SelectionContainer {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * 显示可横向滚动的Markdown表格，避免窄屏强行压缩单元格文字。
 *
 * @param headers 表头列表。
 * @param rows 按原始顺序排列的数据行。
 * @return 无返回值。
 */
@Composable
private fun CookContentTable(
    headers: List<String>,
    rows: List<List<String>>
) {
    val columnCount = maxOf(
        headers.size,
        rows.maxOfOrNull(List<String>::size) ?: 0,
        1
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        if (headers.isNotEmpty()) {
            CookContentTableRow(
                cells = List(columnCount) { index -> headers.getOrElse(index) { "" } },
                header = true
            )
        }
        rows.forEach { row ->
            CookContentTableRow(
                cells = List(columnCount) { index -> row.getOrElse(index) { "" } },
                header = false
            )
        }
    }
}

/**
 * 显示Markdown表格中的一行固定宽度单元格。
 *
 * @param cells 当前行的全部文字单元格。
 * @param header true表示表头并使用强调样式。
 * @return 无返回值。
 */
@Composable
private fun CookContentTableRow(
    cells: List<String>,
    header: Boolean
) {
    Row {
        cells.forEach { cell ->
            Surface(
                modifier = Modifier
                    .width(150.dp)
                    .padding(end = 1.dp, bottom = 1.dp),
                color = if (header) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                SelectionContainer {
                    Text(
                        modifier = Modifier.padding(9.dp),
                        text = cell,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 显示菜谱来源、固定提交和许可证，并只允许打开可信HTTPS源码链接。
 *
 * @param sourcePath 上游提交中的相对Markdown路径。
 * @param sourceCommit 本地快照对应的完整提交哈希。
 * @param sourceLicense 上游许可证名称。
 * @param canOpenSource true表示源码地址已通过安全校验。
 * @param operationMessage 最近一次打开操作的提示。
 * @param onOpenSource 打开源码页面的回调。
 * @return 无返回值。
 */
@Composable
private fun CookSourceCard(
    sourcePath: String,
    sourceCommit: String,
    sourceLicense: String,
    canOpenSource: Boolean,
    operationMessage: String?,
    onOpenSource: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "来源与许可",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            SelectionContainer {
                Text(
                    text = buildString {
                        append("HowToCook · ")
                        append(sourceLicense.ifBlank { "许可证信息未提供" })
                        if (sourceCommit.isNotBlank()) {
                            append("\n提交：")
                            append(sourceCommit.take(12))
                        }
                        if (sourcePath.isNotBlank()) {
                            append("\n路径：")
                            append(sourcePath)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                enabled = canOpenSource,
                onClick = onOpenSource
            ) {
                Text(text = "在浏览器查看源码")
            }
            operationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 保存一次图片读取请求，确保异步结果只能用于完全相同的路径和目标尺寸。 */
private data class CookAssetImageRequest(
    val assetPath: String,
    val maxDecodeDimension: Int
)

/** 明确区分图片加载中、加载成功和需要分类兜底三种状态。 */
private sealed interface CookAssetImageUiState {
    val request: CookAssetImageRequest

    data class Loading(
        override val request: CookAssetImageRequest
    ) : CookAssetImageUiState

    data class Success(
        override val request: CookAssetImageRequest,
        val image: ImageBitmap
    ) : CookAssetImageUiState

    data class Fallback(
        override val request: CookAssetImageRequest
    ) : CookAssetImageUiState
}

/**
 * 异步显示assets中的WebP图片；路径缺失或解码失败时改用当前分类插画。
 *
 * @param assetPath 相对于Android assets根目录的图片路径。
 * @param contentDescription 图片无障碍说明。
 * @param categoryId 分类稳定ID，用于选择兜底插画。
 * @param categoryName 分类中文名。
 * @param maxDecodeDimension 解码后允许的目标最大边长。
 * @param cropToFrame true表示列表封面裁切为4:3；false表示按原图比例完整显示。
 * @param modifier 外部尺寸与裁剪修饰器。
 * @return 无返回值。
 */
@Composable
private fun CookAssetImage(
    assetPath: String,
    contentDescription: String,
    categoryId: String,
    categoryName: String,
    maxDecodeDimension: Int,
    cropToFrame: Boolean,
    modifier: Modifier = Modifier
) {
    val imageState = rememberCookAssetImage(
        assetPath = assetPath,
        maxDecodeDimension = maxDecodeDimension
    )
    val displayAspectRatio = if (cropToFrame) {
        4f / 3f
    } else {
        when (imageState) {
            is CookAssetImageUiState.Success -> {
                imageState.image.width.toFloat() / imageState.image.height.coerceAtLeast(1)
            }

            is CookAssetImageUiState.Loading,
            is CookAssetImageUiState.Fallback -> 4f / 3f
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(displayAspectRatio)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (imageState) {
            is CookAssetImageUiState.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        text = "图片加载中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is CookAssetImageUiState.Success -> {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    bitmap = imageState.image,
                    contentDescription = contentDescription,
                    contentScale = if (cropToFrame) ContentScale.Crop else ContentScale.Fit
                )
            }

            is CookAssetImageUiState.Fallback -> {
                CookCategoryIllustration(
                    categoryId = categoryId,
                    categoryName = categoryName,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 在IO线程解码并记住一张本地asset图片。
 *
 * 使用方法：
 * 调用方提供受信任的`cook/`路径和目标边长。本函数会先读取尺寸再按2的幂下采样，避免列表滚动时
 * 把原始大图完整解码到内存；状态中保留请求身份，切换菜谱时不会短暂复用上一张图片。
 *
 * @param assetPath 相对于assets根目录的路径。
 * @param maxDecodeDimension 目标最大边长，必须大于0。
 * @return 当前请求对应的加载、成功或分类兜底状态。
 */
@Composable
private fun rememberCookAssetImage(
    assetPath: String,
    maxDecodeDimension: Int
): CookAssetImageUiState {
    val applicationContext = LocalContext.current.applicationContext
    val request = remember(assetPath, maxDecodeDimension) {
        CookAssetImageRequest(
            assetPath = assetPath,
            maxDecodeDimension = maxDecodeDimension
        )
    }
    val requestIsValid = isSafeCookAssetPath(assetPath) && maxDecodeDimension > 0
    val initialState: CookAssetImageUiState = if (requestIsValid) {
        CookAssetImageUiState.Loading(request)
    } else {
        CookAssetImageUiState.Fallback(request)
    }
    val imageState = produceState(
        initialValue = initialState,
        key1 = request
    ) {
        if (!requestIsValid) {
            value = CookAssetImageUiState.Fallback(request)
            return@produceState
        }
        value = CookAssetImageUiState.Loading(request)
        val image = try {
            withContext(Dispatchers.IO) {
                decodeCookAssetImage(
                    assetManager = applicationContext.assets,
                    assetPath = request.assetPath,
                    maxDecodeDimension = request.maxDecodeDimension
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        value = if (image == null) {
            CookAssetImageUiState.Fallback(request)
        } else {
            CookAssetImageUiState.Success(request = request, image = image)
        }
    }
    return imageState.value.takeIf { state -> state.request == request } ?: initialState
}

/**
 * 通过两次读取安全解码一张asset图片，并按目标边长计算采样率。
 *
 * @param assetManager Android应用assets管理器。
 * @param assetPath 已通过校验的相对图片路径。
 * @param maxDecodeDimension 目标最大边长。
 * @return 解码成功的[ImageBitmap]，无法识别图片时返回null。
 */
private fun decodeCookAssetImage(
    assetManager: AssetManager,
    assetPath: String,
    maxDecodeDimension: Int
): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    assetManager.open(assetPath).use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateCookImageSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = maxDecodeDimension
        )
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return assetManager.open(assetPath).use { input ->
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
}

/**
 * 计算BitmapFactory可使用的2次幂下采样比例。
 *
 * @param width 原始图片宽度。
 * @param height 原始图片高度。
 * @param maxDimension 期望的最大边长。
 * @return 至少为1的2次幂采样值。
 */
private fun calculateCookImageSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    var sampleSize = 1
    val largestDimension = maxOf(width, height)
    while (
        largestDimension / sampleSize > maxDimension &&
        sampleSize <= Int.MAX_VALUE / 2
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * 显示无需外部文件的分类兜底插画，使任何无图或损坏图片卡片仍有明确视觉内容。
 *
 * @param categoryId 分类稳定ID。
 * @param categoryName 分类中文名称。
 * @param modifier 外部尺寸修饰器。
 * @return 无返回值。
 */
@Composable
private fun CookCategoryIllustration(
    categoryId: String,
    categoryName: String,
    modifier: Modifier = Modifier
) {
    val emoji = cookCategoryEmoji(categoryId)
    Box(
        modifier = modifier.background(
            brush = Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.displayMedium
            )
            Text(
                text = categoryName.ifBlank { "菜谱" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "本地分类插画",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * 根据稳定分类ID返回语义明确的本地插画符号。
 *
 * @param categoryId 上游分类ID。
 * @return 对应分类的Emoji；未知分类返回餐盘。
 */
private fun cookCategoryEmoji(categoryId: String): String {
    return when (categoryId.lowercase(Locale.ROOT)) {
        "vegetable_dish" -> "🥬"
        "meat_dish" -> "🍖"
        "aquatic" -> "🐟"
        "breakfast" -> "🍳"
        "staple" -> "🍚"
        "semi-finished" -> "🥟"
        "soup" -> "🥣"
        "drink" -> "🥤"
        "condiment" -> "🧂"
        "dessert" -> "🍰"
        else -> "🍽️"
    }
}

/**
 * 从完整目录中筛选当前食材输入框应展示的候选项。
 *
 * @param ingredients 完整食材目录。
 * @param query 用户输入的名称或别名关键词。
 * @param selectedIngredientIds 已选择的稳定ID集合。
 * @return 最多[COOK_INGREDIENT_OPTION_LIMIT]项，已选项优先且不包含工具。
 */
private fun findCookIngredientOptions(
    ingredients: List<CookIngredient>,
    query: String,
    selectedIngredientIds: Set<String>
): List<CookIngredient> {
    val tokens = normalizeCookUiSearch(query)
        .split(' ')
        .filter(String::isNotBlank)
    return ingredients.asSequence()
        .filter { ingredient -> ingredient.kind != CookRequirementKind.TOOL }
        .filter { ingredient ->
            if (tokens.isEmpty()) {
                true
            } else {
                val corpus = normalizeCookUiSearch(
                    buildString {
                        append(ingredient.displayName)
                        append(' ')
                        append(ingredient.aliases.joinToString(" "))
                    }
                )
                tokens.all { token -> token in corpus }
            }
        }
        .sortedWith(
            compareByDescending<CookIngredient> { ingredient ->
                ingredient.id in selectedIngredientIds
            }.thenBy { ingredient ->
                if (ingredient.kind == CookRequirementKind.INGREDIENT) 0 else 1
            }.thenBy(CookIngredient::displayName)
                .thenBy(CookIngredient::id)
        )
        .take(COOK_INGREDIENT_OPTION_LIMIT)
        .toList()
}

/**
 * 合并菜谱分类、难度和热量，跳过上游未提供的空字段。
 *
 * @param recipe 当前菜谱。
 * @return 适合卡片和详情头部显示的一行元数据。
 */
private fun buildCookRecipeMetadata(recipe: CookRecipe): String {
    return listOf(
        recipe.categoryName,
        recipe.difficulty.takeIf(String::isNotBlank),
        recipe.calories.takeIf(String::isNotBlank)
    ).filterNotNull()
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .ifBlank { "离线菜谱" }
}

/**
 * 把可能很长的缺料或工具名称列表压缩为适合卡片显示的摘要。
 *
 * @param names 原始名称列表。
 * @return 前三项名称；超过三项时追加剩余数量。
 */
private fun summarizeCookNames(names: List<String>): String {
    val visibleNames = names.take(3)
    return buildString {
        append(visibleNames.joinToString("、"))
        if (names.size > visibleNames.size) {
            append(" 等")
            append(names.size)
            append("项")
        }
    }
}

/**
 * 规范化界面搜索文字，采用NFKC并把连续空白和标点折叠为空格。
 *
 * @param value 原始用户输入或目录文本。
 * @return 可用于中文包含匹配和拉丁字符大小写无关匹配的文本。
 */
private fun normalizeCookUiSearch(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .map { character ->
            if (character.isLetterOrDigit()) character else ' '
        }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")
}

/**
 * 校验菜谱图片只能读取Cook自己的安全asset路径。
 *
 * @param assetPath 待校验相对路径。
 * @return 路径位于`cook/`、不含反斜线和目录穿越且以受支持图片扩展名结尾时返回true。
 */
private fun isSafeCookAssetPath(assetPath: String): Boolean {
    val normalizedPath = assetPath.trim()
    return normalizedPath.startsWith("cook/") &&
        !normalizedPath.contains("\\") &&
        normalizedPath.split('/').none { segment -> segment == ".." } &&
        normalizedPath.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in setOf("webp", "png", "jpg", "jpeg")
}

/**
 * 校验源码按钮只打开官方HowToCook仓库的HTTPS页面。
 *
 * @param sourceUrl 待校验地址。
 * @return 地址属于官方GitHub仓库时返回true。
 */
private fun isSafeCookSourceUrl(sourceUrl: String): Boolean {
    return sourceUrl.startsWith(
        prefix = "https://github.com/Anduin2017/HowToCook/",
        ignoreCase = true
    )
}
