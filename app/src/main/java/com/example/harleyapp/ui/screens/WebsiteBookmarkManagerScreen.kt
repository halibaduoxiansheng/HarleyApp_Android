package com.example.harleyapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.WebsiteCardBackgroundStore
import com.example.harleyapp.model.MAX_WEBSITE_FOLDER_DEPTH
import com.example.harleyapp.model.ROOT_WEBSITE_FOLDER_NAME
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsitePalette
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.canPlaceWebsiteFolder
import com.example.harleyapp.model.moveWebsiteNodesToFolder
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.websiteFolderDepth
import com.example.harleyapp.model.websiteFolderDescendantIds
import com.example.harleyapp.model.websiteFolderPath
import com.example.harleyapp.model.websiteMatchesQuery
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 显示类似浏览器收藏夹的分层网站管理页面。
 *
 * 使用方法：
 * HarleyApp从首页“详情”或网页页“管理网站”进入本页面，传入完整WebsiteLibrary和同步保存
 * 回调。用户可以建立最多五层文件夹、增删改网站、选择首页轮播项目、设置默认网站、模糊
 * 搜索，并长按同层条目上下拖动排序。所有编辑仅在保存回调返回true后反映到父级状态。
 *
 * @param modifier 外部传入的系统安全边距。
 * @param library 当前完整网站与收藏夹数据。
 * @param defaultWebsiteId 底部“网站”页签当前默认打开的网站id。
 * @param onBack 返回进入本页面前页面的回调。
 * @param onOpenWebsite 打开指定网站的回调。
 * @param onSetDefaultWebsite 设置默认网站的同步回调，成功返回true。
 * @param onSaveLibrary 覆盖保存完整网站库的同步回调，成功返回true。
 *
 * @return 无返回值，直接输出收藏管理界面和编辑确认框。
 */
@Composable
fun WebsiteBookmarkManagerScreen(
    modifier: Modifier = Modifier,
    library: WebsiteLibrary,
    defaultWebsiteId: String?,
    onBack: () -> Unit,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onSetDefaultWebsite: (String) -> Boolean,
    onSaveLibrary: (WebsiteLibrary) -> Boolean
) {
    val context = LocalContext.current
    val backgroundStore = remember {
        WebsiteCardBackgroundStore(context.applicationContext)
    }
    var currentFolderId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var query by rememberSaveable {
        mutableStateOf("")
    }
    var editingFolder by remember {
        mutableStateOf<WebsiteFolder?>(null)
    }
    var folderEditorVisible by remember {
        mutableStateOf(false)
    }
    var editingWebsite by remember {
        mutableStateOf<WebsiteShortcut?>(null)
    }
    var websiteEditorVisible by remember {
        mutableStateOf(false)
    }
    var existingContentDialogVisible by remember {
        mutableStateOf(false)
    }
    var deletingFolder by remember {
        mutableStateOf<WebsiteFolder?>(null)
    }
    var deletingWebsite by remember {
        mutableStateOf<WebsiteShortcut?>(null)
    }
    var saveError by remember {
        mutableStateOf("")
    }

    // 删除了当前正在浏览的目录后自动回到根目录，避免页面停留在无效路径。
    LaunchedEffect(currentFolderId, library.folders.map { folder -> folder.id }) {
        if (currentFolderId != null && library.folders.none { folder -> folder.id == currentFolderId }) {
            currentFolderId = null
        }
    }

    if (folderEditorVisible) {
        WebsiteFolderEditorDialog(
            library = library,
            folder = editingFolder,
            initialParentId = currentFolderId,
            onDismiss = {
                folderEditorVisible = false
                editingFolder = null
            },
            onSave = { updatedLibrary ->
                val saved = onSaveLibrary(updatedLibrary)
                if (saved) {
                    folderEditorVisible = false
                    editingFolder = null
                    saveError = ""
                } else {
                    saveError = "收藏夹保存失败，请重试"
                }
                saved
            }
        )
    }

    if (websiteEditorVisible) {
        WebsiteBookmarkEditorDialog(
            library = library,
            website = editingWebsite,
            initialFolderId = currentFolderId,
            backgroundStore = backgroundStore,
            onDismiss = {
                websiteEditorVisible = false
                editingWebsite = null
            },
            onSave = { updatedLibrary ->
                val saved = onSaveLibrary(updatedLibrary)
                if (saved) {
                    websiteEditorVisible = false
                    editingWebsite = null
                    saveError = ""
                } else {
                    saveError = "网站保存失败，请重试"
                }
                saved
            }
        )
    }

    if (existingContentDialogVisible) {
        WebsiteExistingContentDialog(
            library = library,
            targetFolderId = currentFolderId,
            onDismiss = {
                existingContentDialogVisible = false
            },
            onSave = { updatedLibrary ->
                val saved = onSaveLibrary(updatedLibrary)
                if (saved) {
                    existingContentDialogVisible = false
                    saveError = ""
                } else {
                    saveError = "已有内容移动失败，请重试"
                }
                saved
            }
        )
    }

    deletingWebsite?.let { website ->
        WebsiteBookmarkDeleteDialog(
            website = website,
            onDismiss = {
                deletingWebsite = null
            },
            onConfirm = {
                val updatedLibrary = library.copy(
                    websites = library.websites.filterNot { current -> current.id == website.id }
                )
                val deleted = onSaveLibrary(updatedLibrary)
                if (deleted) {
                    deletingWebsite = null
                    saveError = ""
                } else {
                    saveError = "网站删除失败，请重试"
                }
                deleted
            }
        )
    }

    deletingFolder?.let { folder ->
        val descendantIds = remember(library.folders, folder.id) {
            websiteFolderDescendantIds(library.folders, folder.id) + folder.id
        }
        val websiteCount = library.websites.count { website -> website.folderId in descendantIds }
        WebsiteFolderDeleteDialog(
            folder = folder,
            descendantFolderCount = descendantIds.size - 1,
            websiteCount = websiteCount,
            onDismiss = {
                deletingFolder = null
            },
            onConfirm = {
                val updatedLibrary = library.copy(
                    folders = library.folders.filterNot { current -> current.id in descendantIds },
                    websites = library.websites.filterNot { website -> website.folderId in descendantIds }
                )
                val deleted = onSaveLibrary(updatedLibrary)
                if (deleted) {
                    if (currentFolderId in descendantIds) {
                        currentFolderId = folder.parentId
                    }
                    deletingFolder = null
                    saveError = ""
                } else {
                    saveError = "文件夹删除失败，请重试"
                }
                deleted
            }
        )
    }

    val currentDepth = websiteFolderDepth(library.folders, currentFolderId)
    val currentNodes = remember(library, currentFolderId) {
        websiteNodesAt(library, currentFolderId)
    }
    val searchResults = remember(library, query) {
        websiteSearchResults(library, query)
    }
    val breadcrumbs = remember(library.folders, currentFolderId) {
        websiteBreadcrumbs(library.folders, currentFolderId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "‹ 返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "网站收藏",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${library.folders.size} 个文件夹 · ${library.websites.size} 个网站",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { newValue -> query = newValue },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            label = {
                Text(text = "搜索收藏")
            },
            placeholder = {
                Text(text = "模糊搜索名称、网址或文件夹")
            },
            leadingIcon = {
                Text(text = "⌕")
            },
            trailingIcon = if (query.isBlank()) null else {
                {
                    TextButton(onClick = { query = "" }) {
                        Text(text = "清除")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp)
        )

        if (query.isBlank()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(
                    items = breadcrumbs,
                    key = { breadcrumb -> breadcrumb.id ?: "root" }
                ) { breadcrumb ->
                    TextButton(onClick = { currentFolderId = breadcrumb.id }) {
                        Text(
                            text = breadcrumb.name,
                            fontWeight = if (breadcrumb.id == currentFolderId) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    }
                    if (breadcrumb != breadcrumbs.last()) {
                        Text(
                            text = "›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = { existingContentDialogVisible = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Text(text = "＋ 添加已有内容")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        editingFolder = null
                        folderEditorVisible = true
                    },
                    enabled = currentDepth < MAX_WEBSITE_FOLDER_DEPTH,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (currentDepth < MAX_WEBSITE_FOLDER_DEPTH) {
                            "＋ 新建文件夹"
                        } else {
                            "已达 5 层"
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        editingWebsite = null
                        websiteEditorVisible = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "＋ 新建网站")
                }
            }
        }

        if (saveError.isNotBlank()) {
            Text(
                text = saveError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (query.isBlank()) {
            WebsiteFolderContents(
                modifier = Modifier.weight(1f),
                library = library,
                nodes = currentNodes,
                defaultWebsiteId = defaultWebsiteId,
                currentFolderId = currentFolderId,
                onOpenFolder = { folderId -> currentFolderId = folderId },
                onOpenWebsite = onOpenWebsite,
                onEditFolder = { folder ->
                    editingFolder = folder
                    folderEditorVisible = true
                },
                onDeleteFolder = { folder -> deletingFolder = folder },
                onEditWebsite = { website ->
                    editingWebsite = website
                    websiteEditorVisible = true
                },
                onDeleteWebsite = { website -> deletingWebsite = website },
                onSetDefaultWebsite = onSetDefaultWebsite,
                onToggleHome = { website, showOnHome ->
                    val updatedLibrary = library.copy(
                        websites = library.websites.map { current ->
                            if (current.id == website.id) {
                                current.copy(showOnHome = showOnHome)
                            } else {
                                current
                            }
                        }
                    )
                    if (!onSaveLibrary(updatedLibrary)) {
                        saveError = "首页轮播设置保存失败，请重试"
                    } else {
                        saveError = ""
                    }
                },
                onReorder = { draggedKey, targetIndex ->
                    val updatedLibrary = reorderWebsiteNodes(
                        library = library,
                        parentId = currentFolderId,
                        draggedKey = draggedKey,
                        targetIndex = targetIndex
                    )
                    if (!onSaveLibrary(updatedLibrary)) {
                        saveError = "排序保存失败，请重试"
                    } else {
                        saveError = ""
                    }
                }
            )
        } else {
            WebsiteSearchResultList(
                modifier = Modifier.weight(1f),
                results = searchResults,
                defaultWebsiteId = defaultWebsiteId,
                onOpenFolder = { folderId ->
                    currentFolderId = folderId
                    query = ""
                },
                onOpenWebsite = onOpenWebsite,
                onEditWebsite = { website ->
                    editingWebsite = website
                    websiteEditorVisible = true
                },
                onToggleHome = { website, showOnHome ->
                    val updatedLibrary = library.copy(
                        websites = library.websites.map { current ->
                            if (current.id == website.id) {
                                current.copy(showOnHome = showOnHome)
                            } else {
                                current
                            }
                        }
                    )
                    if (!onSaveLibrary(updatedLibrary)) {
                        saveError = "首页轮播设置保存失败，请重试"
                    } else {
                        saveError = ""
                    }
                },
                onSetDefaultWebsite = onSetDefaultWebsite
            )
        }
    }
}

/**
 * 显示当前文件夹的同层文件夹和网站，并处理长按拖动排序。
 *
 * 使用方法：
 * 仅在非搜索状态下调用。用户长按任意卡片并上下拖动，松手后根据跨过的卡片数量计算目标
 * 位置；拖动只改变当前层，不会误把条目移动到另一个文件夹。
 *
 * @param modifier 列表布局修饰器。
 * @param library 当前完整网站库。
 * @param nodes 当前文件夹已经排序的混合节点。
 * @param defaultWebsiteId 当前默认网站id。
 * @param currentFolderId 当前文件夹id；null表示根目录。
 * @param onOpenFolder 进入子文件夹的回调。
 * @param onOpenWebsite 打开网站的回调。
 * @param onEditFolder 编辑文件夹的回调。
 * @param onDeleteFolder 删除文件夹的回调。
 * @param onEditWebsite 编辑网站的回调。
 * @param onDeleteWebsite 删除网站的回调。
 * @param onSetDefaultWebsite 设置默认网站的回调。
 * @param onToggleHome 切换首页轮播开关的回调。
 * @param onReorder 拖动结束后的排序回调，参数为节点key和目标位置。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteFolderContents(
    modifier: Modifier,
    library: WebsiteLibrary,
    nodes: List<WebsiteManagerNode>,
    defaultWebsiteId: String?,
    currentFolderId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onEditFolder: (WebsiteFolder) -> Unit,
    onDeleteFolder: (WebsiteFolder) -> Unit,
    onEditWebsite: (WebsiteShortcut) -> Unit,
    onDeleteWebsite: (WebsiteShortcut) -> Unit,
    onSetDefaultWebsite: (String) -> Boolean,
    onToggleHome: (WebsiteShortcut, Boolean) -> Unit,
    onReorder: (String, Int) -> Unit
) {
    var draggingKey by remember(currentFolderId) {
        mutableStateOf<String?>(null)
    }
    var dragOffset by remember(currentFolderId) {
        mutableFloatStateOf(0f)
    }
    var draggedRowHeight by remember(currentFolderId) {
        mutableIntStateOf(1)
    }

    if (nodes.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (library.websites.isEmpty() && library.folders.isEmpty()) "收藏还是空的" else "这个文件夹是空的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "可以在上方新建文件夹或添加网站",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 8.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "长按卡片上下拖动可调整同层顺序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = nodes,
            key = { node -> node.key }
        ) { node ->
            val isDragging = draggingKey == node.key
            var rowHeight by remember(node.key) {
                mutableIntStateOf(1)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else 0f
                        shadowElevation = if (isDragging) 18f else 0f
                    }
                    .onSizeChanged { size ->
                        rowHeight = size.height.coerceAtLeast(1)
                    }
                    .pointerInput(node.key, nodes.map { current -> current.key }) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = node.key
                                dragOffset = 0f
                                draggedRowHeight = rowHeight
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                val sourceIndex = nodes.indexOfFirst { current -> current.key == node.key }
                                if (sourceIndex >= 0) {
                                    val movedRows = (dragOffset / draggedRowHeight.toFloat()).roundToInt()
                                    val targetIndex = (sourceIndex + movedRows).coerceIn(nodes.indices)
                                    if (targetIndex != sourceIndex) {
                                        onReorder(node.key, targetIndex)
                                    }
                                }
                                draggingKey = null
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                            }
                        )
                    }
            ) {
                node.folder?.let { folder ->
                    WebsiteFolderRow(
                        folder = folder,
                        childCount = library.folders.count { child -> child.parentId == folder.id } +
                            library.websites.count { website -> website.folderId == folder.id },
                        onOpen = { onOpenFolder(folder.id) },
                        onEdit = { onEditFolder(folder) },
                        onDelete = { onDeleteFolder(folder) }
                    )
                }
                node.website?.let { website ->
                    WebsiteBookmarkRow(
                        website = website,
                        location = null,
                        isDefault = website.id == defaultWebsiteId,
                        onOpen = { onOpenWebsite(website) },
                        onEdit = { onEditWebsite(website) },
                        onDelete = { onDeleteWebsite(website) },
                        onSetDefault = { onSetDefaultWebsite(website.id) },
                        onToggleHome = { checked -> onToggleHome(website, checked) }
                    )
                }
            }
        }
    }
}

/**
 * 显示模糊搜索结果；搜索状态不启用拖动，避免跨文件夹顺序含义不清。
 *
 * @param modifier 列表布局修饰器。
 * @param results 已计算的文件夹和网站搜索结果。
 * @param defaultWebsiteId 当前默认网站id。
 * @param onOpenFolder 点击文件夹结果时进入该目录的回调。
 * @param onOpenWebsite 点击网站结果时打开网站的回调。
 * @param onEditWebsite 编辑网站的回调。
 * @param onToggleHome 切换首页显示的回调。
 * @param onSetDefaultWebsite 设置默认网站的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteSearchResultList(
    modifier: Modifier,
    results: List<WebsiteSearchResult>,
    defaultWebsiteId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenWebsite: (WebsiteShortcut) -> Unit,
    onEditWebsite: (WebsiteShortcut) -> Unit,
    onToggleHome: (WebsiteShortcut, Boolean) -> Unit,
    onSetDefaultWebsite: (String) -> Boolean
) {
    if (results.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "没有找到匹配收藏",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "可尝试名称、域名或文件夹关键词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 8.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "找到 ${results.size} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(
            items = results,
            key = { result -> result.key }
        ) { result ->
            result.folder?.let { folder ->
                WebsiteFolderSearchRow(
                    folder = folder,
                    path = result.path,
                    onOpen = { onOpenFolder(folder.id) }
                )
            }
            result.website?.let { website ->
                WebsiteBookmarkRow(
                    website = website,
                    location = result.path,
                    isDefault = website.id == defaultWebsiteId,
                    onOpen = { onOpenWebsite(website) },
                    onEdit = { onEditWebsite(website) },
                    onDelete = null,
                    onSetDefault = { onSetDefaultWebsite(website.id) },
                    onToggleHome = { checked -> onToggleHome(website, checked) }
                )
            }
        }
    }
}

/**
 * 显示一个文件夹卡片。
 *
 * @param folder 文件夹数据。
 * @param childCount 直属子文件夹与直属网站总数。
 * @param onOpen 进入文件夹回调。
 * @param onEdit 编辑文件夹回调。
 * @param onDelete 删除文件夹回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteFolderRow(
    folder: WebsiteFolder,
    childCount: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.74f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "夹",
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$childCount 项 · 长按可拖动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) {
                Text(text = "编辑")
            }
            TextButton(onClick = onDelete) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error)
            }
            Text(text = "›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * 显示一个网站收藏卡片和首页轮播开关。
 *
 * @param website 网站数据。
 * @param location 搜索结果中显示的完整位置；普通目录列表传null。
 * @param isDefault 是否为底部网站页签默认入口。
 * @param onOpen 打开网站回调。
 * @param onEdit 编辑网站回调。
 * @param onDelete 删除网站回调；搜索结果不显示删除时传null。
 * @param onSetDefault 设为默认网站回调。
 * @param onToggleHome 切换首页轮播开关回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteBookmarkRow(
    website: WebsiteShortcut,
    location: String?,
    isDefault: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onSetDefault: () -> Unit,
    onToggleHome: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = websitePaletteColor(
                        palette = website.palette,
                        customColorArgb = website.customColorArgb
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = website.title.firstOrNull()?.uppercaseChar()?.toString() ?: "网",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = website.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = website.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    location?.let { path ->
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "≡",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = website.showOnHome,
                    onCheckedChange = onToggleHome
                )
                Text(
                    text = "首页轮播",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isDefault) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "默认网站",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpen) {
                    Text(text = "打开")
                }
                TextButton(onClick = onSetDefault, enabled = !isDefault) {
                    Text(text = "设为默认")
                }
                TextButton(onClick = onEdit) {
                    Text(text = "编辑")
                }
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text(text = "删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/**
 * 显示文件夹搜索结果，并展示完整路径。
 *
 * @param folder 文件夹数据。
 * @param path 完整文件夹路径。
 * @param onOpen 进入文件夹的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteFolderSearchRow(
    folder: WebsiteFolder,
    path: String,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "夹", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(text = "进入 ›")
        }
    }
}

/**
 * 从其他目录多选已有网站或文件夹并移动到当前目录。
 *
 * 使用方法：
 * 用户在收藏管理页点击“添加已有内容”后调用。候选会排除已经位于目标目录的内容，以及会形成
 * 循环或超过五层限制的文件夹。选择父文件夹时，其后代和内部网站自动作为整体随之移动，避免
 * 用户同时勾选父子项后意外拆散原有结构。
 *
 * @param library 当前完整网站收藏数据。
 * @param targetFolderId 目标文件夹id；null表示根目录“未分类”。
 * @param onDismiss 取消移动的回调。
 * @param onSave 保存移动后完整网站库的回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteExistingContentDialog(
    library: WebsiteLibrary,
    targetFolderId: String?,
    onDismiss: () -> Unit,
    onSave: (WebsiteLibrary) -> Boolean
) {
    var query by rememberSaveable(targetFolderId) {
        mutableStateOf("")
    }
    var selectedWebsiteIds by remember(targetFolderId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var selectedFolderIds by remember(targetFolderId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var error by remember(targetFolderId) {
        mutableStateOf("")
    }
    val eligibleFolders = remember(library, targetFolderId) {
        library.folders.filter { folder ->
            folder.parentId != targetFolderId && canPlaceWebsiteFolder(
                library = library,
                folderId = folder.id,
                candidateParentId = targetFolderId
            )
        }
    }
    val eligibleWebsites = remember(library, targetFolderId) {
        library.websites.filter { website -> website.folderId != targetFolderId }
    }
    val selectedFolderSubtreeIds = remember(library.folders, selectedFolderIds) {
        selectedFolderIds.flatMapTo(mutableSetOf()) { folderId ->
            websiteFolderDescendantIds(library.folders, folderId) + folderId
        }
    }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val displayedFolders = eligibleFolders.filter { folder ->
        val coveredBySelectedParent = folder.id in selectedFolderSubtreeIds &&
            folder.id !in selectedFolderIds
        val searchableText = "${folder.name} ${websiteFolderPath(library.folders, folder.id)}"
            .lowercase(Locale.ROOT)
        !coveredBySelectedParent &&
            (normalizedQuery.isBlank() || searchableText.contains(normalizedQuery))
    }
    val displayedWebsites = eligibleWebsites.filter { website ->
        val coveredBySelectedFolder = website.folderId in selectedFolderSubtreeIds
        val searchableText = "${website.title} ${website.url} " +
            websiteFolderPath(library.folders, website.folderId)
        !coveredBySelectedFolder &&
            (normalizedQuery.isBlank() || searchableText.lowercase(Locale.ROOT).contains(normalizedQuery))
    }
    val selectedCount = selectedFolderIds.size + selectedWebsiteIds.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "添加已有内容")
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "移动到：${websiteFolderPath(library.folders, targetFolderId)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                item {
                    Text(
                        text = "可多选；移动文件夹时，其中的子文件夹和网站会保持原结构一起移动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { newValue ->
                            query = newValue
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = "搜索已有内容") },
                        singleLine = true
                    )
                }

                if (displayedFolders.isNotEmpty()) {
                    item {
                        Text(
                            text = "文件夹",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(
                        items = displayedFolders,
                        key = { folder -> "move_folder:${folder.id}" }
                    ) { folder ->
                        ExistingWebsiteNodeChoiceRow(
                            title = folder.name,
                            subtitle = websiteFolderPath(library.folders, folder.id),
                            symbol = "夹",
                            checked = folder.id in selectedFolderIds,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    val subtreeIds = websiteFolderDescendantIds(
                                        folders = library.folders,
                                        folderId = folder.id
                                    ) + folder.id
                                    selectedFolderIds = (selectedFolderIds - subtreeIds) + folder.id
                                    selectedWebsiteIds = selectedWebsiteIds.filterTo(linkedSetOf()) { websiteId ->
                                        library.websites.firstOrNull { website -> website.id == websiteId }
                                            ?.folderId !in subtreeIds
                                    }
                                } else {
                                    selectedFolderIds = selectedFolderIds - folder.id
                                }
                                error = ""
                            }
                        )
                    }
                }

                if (displayedWebsites.isNotEmpty()) {
                    item {
                        Text(
                            text = "网站",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(
                        items = displayedWebsites,
                        key = { website -> "move_website:${website.id}" }
                    ) { website ->
                        ExistingWebsiteNodeChoiceRow(
                            title = website.title,
                            subtitle = websiteFolderPath(library.folders, website.folderId),
                            symbol = "站",
                            checked = website.id in selectedWebsiteIds,
                            onCheckedChange = { checked ->
                                selectedWebsiteIds = if (checked) {
                                    selectedWebsiteIds + website.id
                                } else {
                                    selectedWebsiteIds - website.id
                                }
                                error = ""
                            }
                        )
                    }
                }

                if (displayedFolders.isEmpty() && displayedWebsites.isEmpty()) {
                    item {
                        Text(
                            text = if (normalizedQuery.isBlank()) {
                                "没有可以移动到这里的已有内容。"
                            } else {
                                "没有匹配的已有内容。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (error.isNotBlank()) {
                    item {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    val updatedLibrary = moveWebsiteNodesToFolder(
                        library = library,
                        targetFolderId = targetFolderId,
                        websiteIds = selectedWebsiteIds,
                        folderIds = selectedFolderIds
                    )
                    if (updatedLibrary == null) {
                        error = "所选文件夹会形成循环或超过五层，请重新选择"
                    } else if (!onSave(updatedLibrary)) {
                        error = "移动失败，请重试"
                    }
                }
            ) {
                Text(text = "移动 $selectedCount 项")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示“添加已有内容”对话框中的一个多选条目。
 *
 * @param title 网站或文件夹名称。
 * @param subtitle 当前所属文件夹路径。
 * @param symbol 区分网站和文件夹的单字图标。
 * @param checked 当前是否已经选中。
 * @param onCheckedChange 用户修改选中状态的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ExistingWebsiteNodeChoiceRow(
    title: String,
    subtitle: String,
    symbol: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    text = symbol,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * 新建或编辑文件夹名称和父目录。
 *
 * 使用方法：
 * 新增时folder传null并通过initialParentId指定当前位置；编辑时保留folder.id。父目录下拉框
 * 会排除自身、所有后代和超过五层的选项。移动到新父目录时自动排在该目录末尾。
 *
 * @param library 当前完整网站库。
 * @param folder 编辑目标；null表示新增。
 * @param initialParentId 新增文件夹默认所属目录。
 * @param onDismiss 取消回调。
 * @param onSave 保存完整新网站库的回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteFolderEditorDialog(
    library: WebsiteLibrary,
    folder: WebsiteFolder?,
    initialParentId: String?,
    onDismiss: () -> Unit,
    onSave: (WebsiteLibrary) -> Boolean
) {
    var name by remember(folder?.id) {
        mutableStateOf(folder?.name.orEmpty())
    }
    var parentId by remember(folder?.id, initialParentId) {
        mutableStateOf(folder?.parentId ?: initialParentId)
    }
    var parentMenuExpanded by remember {
        mutableStateOf(false)
    }
    var error by remember(folder?.id) {
        mutableStateOf("")
    }
    val choices = remember(library, folder?.id) {
        websiteFolderChoices(library).filter { choice ->
            canPlaceWebsiteFolder(
                library = library,
                folderId = folder?.id,
                candidateParentId = choice.id
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = if (folder == null) "新建文件夹" else "编辑文件夹")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { newValue ->
                        name = newValue
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(text = "文件夹名称")
                    },
                    singleLine = true
                )

                Text(
                    text = "所属位置",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Box {
                    OutlinedButton(
                        onClick = { parentMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = websiteFolderPath(library.folders, parentId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = parentMenuExpanded,
                        onDismissRequest = { parentMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        choices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(text = "  ".repeat(choice.depth) + choice.name)
                                },
                                onClick = {
                                    parentId = choice.id
                                    parentMenuExpanded = false
                                    error = ""
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "最多支持 $MAX_WEBSITE_FOLDER_DEPTH 层，可在这里调整所属目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error.isNotBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isBlank()) {
                        error = "请输入文件夹名称"
                        return@TextButton
                    }
                    if (!canPlaceWebsiteFolder(library, folder?.id, parentId)) {
                        error = "该位置会形成循环或超过五层"
                        return@TextButton
                    }

                    val folderId = folder?.id ?: UUID.randomUUID().toString()
                    val newOrder = if (folder == null || folder.parentId != parentId) {
                        nextWebsiteNodeOrder(library, parentId)
                    } else {
                        folder.sortOrder
                    }
                    val updatedFolder = WebsiteFolder(
                        id = folderId,
                        name = trimmedName,
                        parentId = parentId,
                        sortOrder = newOrder
                    )
                    val updatedFolders = if (folder == null) {
                        library.folders + updatedFolder
                    } else {
                        library.folders.map { current ->
                            if (current.id == folder.id) updatedFolder else current
                        }
                    }
                    if (!onSave(library.copy(folders = updatedFolders))) {
                        error = "保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 新建或编辑网站的名称、网址、颜色、所属文件夹和首页轮播开关。
 *
 * @param library 当前完整网站库。
 * @param website 编辑目标；null表示新增。
 * @param initialFolderId 新增网站默认所属目录。
 * @param backgroundStore 网站卡片私有背景图存储。
 * @param onDismiss 取消回调。
 * @param onSave 保存完整新网站库的回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteBookmarkEditorDialog(
    library: WebsiteLibrary,
    website: WebsiteShortcut?,
    initialFolderId: String?,
    backgroundStore: WebsiteCardBackgroundStore,
    onDismiss: () -> Unit,
    onSave: (WebsiteLibrary) -> Boolean
) {
    var title by remember(website?.id) {
        mutableStateOf(website?.title.orEmpty())
    }
    var url by remember(website?.id) {
        mutableStateOf(website?.url.orEmpty())
    }
    var palette by remember(website?.id) {
        mutableStateOf(website?.palette ?: WebsitePalette.OCEAN)
    }
    var customColorArgb by remember(website?.id) {
        mutableStateOf(website?.customColorArgb)
    }
    var backgroundImageFileName by remember(website?.id) {
        mutableStateOf(website?.backgroundImageFileName)
    }
    var folderId by remember(website?.id, initialFolderId) {
        mutableStateOf(website?.folderId ?: initialFolderId)
    }
    var showOnHome by remember(website?.id) {
        mutableStateOf(website?.showOnHome ?: true)
    }
    var folderMenuExpanded by remember {
        mutableStateOf(false)
    }
    var error by remember(website?.id) {
        mutableStateOf("")
    }
    val folderChoices = remember(library.folders) {
        websiteFolderChoices(library)
    }
    val dismissEditor = {
        discardUncommittedWebsiteBackground(
            store = backgroundStore,
            originalFileName = website?.backgroundImageFileName,
            currentFileName = backgroundImageFileName
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismissEditor,
        title = {
            Text(text = if (website == null) "添加网站" else "编辑网站")
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { newValue ->
                            title = newValue
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(text = "网站名称")
                        },
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { newValue ->
                            url = newValue
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(text = "HTTP或HTTPS网址")
                        },
                        supportingText = {
                            Text(text = "未填写协议时自动补充 https://")
                        },
                        singleLine = true
                    )
                }
                item {
                    WebsiteCardAppearanceEditor(
                        palette = palette,
                        customColorArgb = customColorArgb,
                        backgroundImageFileName = backgroundImageFileName,
                        originalBackgroundImageFileName = website?.backgroundImageFileName,
                        backgroundStore = backgroundStore,
                        onPaletteChanged = { selected -> palette = selected },
                        onCustomColorChanged = { color -> customColorArgb = color },
                        onBackgroundImageChanged = { fileName ->
                            backgroundImageFileName = fileName
                        },
                        onMessageChanged = { message -> error = message }
                    )
                }
                item {
                    Text(
                        text = "所属位置",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box {
                        OutlinedButton(
                            onClick = { folderMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = websiteFolderPath(library.folders, folderId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        DropdownMenu(
                            expanded = folderMenuExpanded,
                            onDismissRequest = { folderMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            folderChoices.forEach { choice ->
                                DropdownMenuItem(
                                    text = {
                                        Text(text = "  ".repeat(choice.depth) + choice.name)
                                    },
                                    onClick = {
                                        folderId = choice.id
                                        folderMenuExpanded = false
                                        error = ""
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showOnHome,
                            onCheckedChange = { checked -> showOnHome = checked }
                        )
                        Column {
                            Text(text = "在首页轮播")
                            Text(
                                text = "关闭后仍保留在收藏详情中",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (error.isNotBlank()) {
                    item {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedTitle = title.trim()
                    val normalizedUrl = normalizeWebsiteUrl(url)
                    if (trimmedTitle.isBlank()) {
                        error = "请输入网站名称"
                        return@TextButton
                    }
                    if (normalizedUrl == null) {
                        error = "请输入有效的HTTP或HTTPS网址"
                        return@TextButton
                    }

                    val websiteId = website?.id ?: UUID.randomUUID().toString()
                    val newOrder = if (website == null || website.folderId != folderId) {
                        nextWebsiteNodeOrder(library, folderId)
                    } else {
                        website.sortOrder
                    }
                    val updatedWebsite = WebsiteShortcut(
                        id = websiteId,
                        title = trimmedTitle,
                        url = normalizedUrl,
                        palette = palette,
                        customColorArgb = customColorArgb,
                        backgroundImageFileName = backgroundImageFileName,
                        folderId = folderId,
                        showOnHome = showOnHome,
                        sortOrder = newOrder
                    )
                    val updatedWebsites = if (website == null) {
                        library.websites + updatedWebsite
                    } else {
                        library.websites.map { current ->
                            if (current.id == website.id) updatedWebsite else current
                        }
                    }
                    if (!onSave(library.copy(websites = updatedWebsites))) {
                        error = "保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = dismissEditor) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示删除网站确认框。
 *
 * @param website 即将删除的网站。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认删除回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteBookmarkDeleteDialog(
    website: WebsiteShortcut,
    onDismiss: () -> Unit,
    onConfirm: () -> Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "删除网站")
        },
        text = {
            Text(text = "确定删除“${website.title}”吗？此操作只删除本App中的收藏。")
        },
        confirmButton = {
            TextButton(onClick = { onConfirm() }) {
                Text(text = "删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示文件夹递归删除确认框，并明确其中会被一并删除的数据数量。
 *
 * @param folder 即将删除的文件夹。
 * @param descendantFolderCount 子文件夹数量。
 * @param websiteCount 文件夹树中的网站数量。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认删除回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteFolderDeleteDialog(
    folder: WebsiteFolder,
    descendantFolderCount: Int,
    websiteCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "删除文件夹")
        },
        text = {
            Text(
                text = "确定删除“${folder.name}”吗？其中 $descendantFolderCount 个子文件夹和 " +
                    "$websiteCount 个网站也会一并删除。"
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm() }) {
                Text(text = "删除全部", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 取得指定父目录中的混合节点，并按手动顺序排列。
 *
 * @param library 当前完整网站库。
 * @param parentId 当前父目录id；null表示根目录。
 *
 * @return 同层文件夹和网站组成的有序列表。
 */
private fun websiteNodesAt(
    library: WebsiteLibrary,
    parentId: String?
): List<WebsiteManagerNode> {
    return buildList {
        library.folders.filter { folder -> folder.parentId == parentId }.forEach { folder ->
            add(
                WebsiteManagerNode(
                    key = "folder:${folder.id}",
                    order = folder.sortOrder,
                    folder = folder
                )
            )
        }
        library.websites.filter { website -> website.folderId == parentId }.forEach { website ->
            add(
                WebsiteManagerNode(
                    key = "website:${website.id}",
                    order = website.sortOrder,
                    website = website
                )
            )
        }
    }.sortedWith(
        compareBy<WebsiteManagerNode> { node -> node.order }
            .thenBy { node -> if (node.folder != null) 0 else 1 }
            .thenBy { node -> node.key }
    )
}

/**
 * 按目标位置重新编号一个层级中的文件夹和网站。
 *
 * @param library 当前完整网站库。
 * @param parentId 当前层级父目录id。
 * @param draggedKey 被拖动节点的带类型key。
 * @param targetIndex 松手时目标索引。
 *
 * @return 仅改变当前层sortOrder的新WebsiteLibrary；参数无效时原样返回。
 */
private fun reorderWebsiteNodes(
    library: WebsiteLibrary,
    parentId: String?,
    draggedKey: String,
    targetIndex: Int
): WebsiteLibrary {
    val nodes = websiteNodesAt(library, parentId).toMutableList()
    val sourceIndex = nodes.indexOfFirst { node -> node.key == draggedKey }
    if (sourceIndex < 0 || targetIndex !in nodes.indices || sourceIndex == targetIndex) {
        return library
    }

    val moved = nodes.removeAt(sourceIndex)
    nodes.add(targetIndex, moved)
    val orderByKey = nodes.mapIndexed { index, node -> node.key to index * WEBSITE_ORDER_STEP }.toMap()
    return library.copy(
        folders = library.folders.map { folder ->
            orderByKey["folder:${folder.id}"]?.let { order -> folder.copy(sortOrder = order) } ?: folder
        },
        websites = library.websites.map { website ->
            orderByKey["website:${website.id}"]?.let { order -> website.copy(sortOrder = order) }
                ?: website
        }
    )
}

/**
 * 计算在指定父目录末尾新增节点时使用的顺序值。
 *
 * @param library 当前完整网站库。
 * @param parentId 目标父目录id；null表示根目录。
 *
 * @return 大于当前同层最大顺序的数值；空目录返回0。
 */
private fun nextWebsiteNodeOrder(
    library: WebsiteLibrary,
    parentId: String?
): Int {
    return websiteNodesAt(library, parentId)
        .maxOfOrNull { node -> node.order + WEBSITE_ORDER_STEP }
        ?: 0
}

/**
 * 生成从根目录到当前目录的可点击面包屑。
 *
 * @param folders 当前全部文件夹。
 * @param folderId 当前目录id；null表示根目录。
 *
 * @return 第一项固定为“未分类”的有序路径。
 */
private fun websiteBreadcrumbs(
    folders: List<WebsiteFolder>,
    folderId: String?
): List<WebsiteBreadcrumb> {
    val foldersById = folders.associateBy { folder -> folder.id }
    val path = mutableListOf<WebsiteBreadcrumb>()
    val visited = mutableSetOf<String>()
    var currentId = folderId
    while (currentId != null && visited.add(currentId)) {
        val folder = foldersById[currentId] ?: break
        path.add(WebsiteBreadcrumb(folder.id, folder.name))
        currentId = folder.parentId
    }
    return listOf(WebsiteBreadcrumb(null, ROOT_WEBSITE_FOLDER_NAME)) + path.asReversed()
}

/**
 * 生成父目录下拉框选项，顺序与收藏树一致并带层级深度。
 *
 * @param library 当前完整网站库。
 *
 * @return 根目录加全部可到达文件夹的扁平选项。
 */
private fun websiteFolderChoices(library: WebsiteLibrary): List<WebsiteFolderChoice> {
    val childrenByParent = library.folders.groupBy { folder -> folder.parentId }
    val output = mutableListOf(
        WebsiteFolderChoice(
            id = null,
            name = ROOT_WEBSITE_FOLDER_NAME,
            depth = 0
        )
    )
    val visited = mutableSetOf<String>()

    fun append(parentId: String?, depth: Int) {
        childrenByParent[parentId].orEmpty()
            .sortedWith(compareBy<WebsiteFolder> { folder -> folder.sortOrder }.thenBy { folder -> folder.name })
            .forEach { folder ->
                if (visited.add(folder.id)) {
                    output.add(
                        WebsiteFolderChoice(
                            id = folder.id,
                            name = folder.name,
                            depth = depth
                        )
                    )
                    append(folder.id, depth + 1)
                }
            }
    }

    append(parentId = null, depth = 1)
    return output
}

/**
 * 对全部文件夹和网站执行模糊搜索。
 *
 * @param library 当前完整网站库。
 * @param query 用户输入的搜索词。
 *
 * @return 文件夹结果在前、网站结果在后的有序列表；空查询返回空列表。
 */
private fun websiteSearchResults(
    library: WebsiteLibrary,
    query: String
): List<WebsiteSearchResult> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    if (normalizedQuery.isBlank()) {
        return emptyList()
    }

    val folderResults = library.folders.mapNotNull { folder ->
        val path = websiteFolderPath(library.folders, folder.id)
        if (simpleFuzzyContains("${folder.name} $path".lowercase(Locale.ROOT), normalizedQuery)) {
            WebsiteSearchResult(
                key = "folder:${folder.id}",
                path = path,
                folder = folder
            )
        } else {
            null
        }
    }.sortedBy { result -> result.path }
    val websiteResults = library.websites.mapNotNull { website ->
        val path = websiteFolderPath(library.folders, website.folderId)
        if (websiteMatchesQuery(website, path, normalizedQuery)) {
            WebsiteSearchResult(
                key = "website:${website.id}",
                path = path,
                website = website
            )
        } else {
            null
        }
    }.sortedBy { result -> result.website?.title }
    return folderResults + websiteResults
}

/**
 * 为文件夹搜索提供连续包含和字符顺序匹配。
 *
 * @param source 已转换为小写的候选文本。
 * @param query 已转换为小写的搜索词。
 *
 * @return 连续或宽松顺序匹配成功返回true。
 */
private fun simpleFuzzyContains(source: String, query: String): Boolean {
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
 * 返回网站配色在管理页头像上的主色。
 *
 * @param palette 网站预设配色枚举。
 * @param customColorArgb 用户自定义主题色；null表示使用预设色。
 *
 * @return 对应的稳定Compose颜色。
 */
private fun websitePaletteColor(
    palette: WebsitePalette,
    customColorArgb: Long?
): androidx.compose.ui.graphics.Color {
    return websiteCardVisualStyle(palette, customColorArgb).actionColor
}

/** 当前文件夹混合列表中的内部节点。 */
private data class WebsiteManagerNode(
    val key: String,
    val order: Int,
    val folder: WebsiteFolder? = null,
    val website: WebsiteShortcut? = null
)

/** 搜索列表中的内部结果节点。 */
private data class WebsiteSearchResult(
    val key: String,
    val path: String,
    val folder: WebsiteFolder? = null,
    val website: WebsiteShortcut? = null
)

/** 面包屑中的一个可点击目录。 */
private data class WebsiteBreadcrumb(
    val id: String?,
    val name: String
)

/** 父目录下拉框中的目录标识、名称和缩进层级。 */
private data class WebsiteFolderChoice(
    val id: String?,
    val name: String,
    val depth: Int
)

/** 同层排序之间预留的间隔，便于后续扩展插入策略。 */
private const val WEBSITE_ORDER_STEP = 10
