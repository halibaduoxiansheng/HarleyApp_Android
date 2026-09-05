package com.example.harleyapp.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.DuplicateFileGroup
import com.example.harleyapp.model.StorageCategorySummary
import com.example.harleyapp.model.StorageFileCategory
import com.example.harleyapp.model.StorageFileEntry
import com.example.harleyapp.model.StorageScanError
import com.example.harleyapp.model.StorageScanSnapshot
import com.example.harleyapp.system.StorageManagementController
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示共享存储容量分类、大文件、重复文件、下载目录整理和长期未修改文件。
 *
 * 使用方法：
 * HarleyApp把页面登记为功能中心详情页，并传入[StorageManagementController]。首次进入需要用户
 * 主动授予文件管理权限，授权返回后自动扫描。扫描只读；永久删除单个文件和移动Download顶层文件
 * 都会先弹出确认框，页面不会后台自动清理。
 *
 * @param controller 文件权限、扫描和操作控制器。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部传入的安全区域与布局修饰器。
 * @return 无返回值，直接输出文件空间管理页面。
 */
@Composable
fun StorageManagerScreen(
    controller: StorageManagementController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fullAccessGranted by remember {
        mutableStateOf(controller.hasFullAccess())
    }
    var snapshot by remember {
        mutableStateOf<StorageScanSnapshot?>(null)
    }
    var scanError by remember {
        mutableStateOf<StorageScanError?>(null)
    }
    var isScanning by remember {
        mutableStateOf(false)
    }
    var isOperating by remember {
        mutableStateOf(false)
    }
    var refreshToken by remember {
        mutableIntStateOf(0)
    }
    var selectedSectionName by rememberSaveable {
        mutableStateOf(StorageManagerSection.OVERVIEW.name)
    }
    var pendingDelete by remember {
        mutableStateOf<StorageFileEntry?>(null)
    }
    var confirmOrganize by remember {
        mutableStateOf(false)
    }
    var operationMessage by remember {
        mutableStateOf("")
    }
    var settingsError by remember {
        mutableStateOf("")
    }
    val selectedSection = StorageManagerSection.entries.firstOrNull { section ->
        section.name == selectedSectionName
    } ?: StorageManagerSection.OVERVIEW
    val coroutineScope = rememberCoroutineScope()
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        fullAccessGranted = controller.hasFullAccess()
        refreshToken += 1
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fullAccessGranted = controller.hasFullAccess()
        refreshToken += 1
    }

    LaunchedEffect(refreshToken) {
        fullAccessGranted = controller.hasFullAccess()
        settingsError = ""
        if (!fullAccessGranted) {
            snapshot = null
            scanError = StorageScanError.FULL_ACCESS_REQUIRED
            isScanning = false
            return@LaunchedEffect
        }

        isScanning = true
        scanError = null
        val result = controller.scan()
        snapshot = result.snapshot
        scanError = result.error
        isScanning = false
    }

    pendingDelete?.let { file ->
        StorageDeleteConfirmationDialog(
            file = file,
            isOperating = isOperating,
            onDismiss = {
                if (!isOperating) {
                    pendingDelete = null
                }
            },
            onConfirm = {
                coroutineScope.launch {
                    isOperating = true
                    val result = controller.deleteFile(file)
                    operationMessage = result.message
                    pendingDelete = null
                    isOperating = false
                    if (result.success) {
                        refreshToken += 1
                    }
                }
            }
        )
    }

    if (confirmOrganize) {
        val directDownloadFiles = snapshot?.downloads
            ?.filter(StorageFileEntry::directlyInDownloads)
            .orEmpty()
        StorageOrganizeConfirmationDialog(
            fileCount = directDownloadFiles.size,
            isOperating = isOperating,
            onDismiss = {
                if (!isOperating) {
                    confirmOrganize = false
                }
            },
            onConfirm = {
                coroutineScope.launch {
                    isOperating = true
                    val result = controller.organizeDownloads(directDownloadFiles)
                    operationMessage = result.message
                    confirmOrganize = false
                    isOperating = false
                    if (result.affectedCount > 0) {
                        refreshToken += 1
                    }
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFE7F8EF), MaterialTheme.colorScheme.background)
                )
            ),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 36.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            StorageManagerHeader(
                isBusy = isScanning || isOperating,
                onBack = onBack,
                onRefresh = {
                    operationMessage = ""
                    refreshToken += 1
                }
            )
        }

        if (!fullAccessGranted) {
            item {
                StoragePermissionCard(
                    settingsError = settingsError,
                    onRequestPermission = {
                        val opened = runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                settingsLauncher.launch(controller.createFullAccessIntent())
                            } else {
                                legacyPermissionLauncher.launch(
                                    controller.requiredLegacyPermissions()
                                )
                            }
                        }.isSuccess
                        if (!opened) {
                            settingsError = "无法打开授权页，请在系统设置中允许文件管理权限"
                        }
                    }
                )
            }
        } else {
            if (operationMessage.isNotBlank()) {
                item {
                    StorageOperationMessageCard(operationMessage)
                }
            }

            if (isScanning && snapshot == null) {
                item {
                    StorageScanningCard()
                }
            } else if (snapshot == null) {
                item {
                    StorageScanErrorCard(scanError)
                }
            } else {
                item {
                    StorageCapacityCard(snapshot!!)
                }
                item {
                    StorageSectionSelector(
                        selectedSection = selectedSection,
                        onSelect = { section ->
                            selectedSectionName = section.name
                        }
                    )
                }

                when (selectedSection) {
                    StorageManagerSection.OVERVIEW -> {
                        item {
                            StorageCategoryCard(snapshot!!.categories)
                        }
                        item {
                            StorageFindingSummary(snapshot!!)
                        }
                    }

                    StorageManagerSection.LARGE_FILES -> {
                        item {
                            StorageListIntroduction(
                                title = "大文件",
                                detail = "大于等于100MB，共${snapshot!!.largeFiles.size}个",
                                warning = "删除是永久操作，请先确认文件仍不需要。"
                            )
                        }
                        if (snapshot!!.largeFiles.isEmpty()) {
                            item {
                                StorageEmptyCard("没有发现大于等于100MB的文件")
                            }
                        } else {
                            items(
                                items = snapshot!!.largeFiles,
                                key = { file -> "large:${file.absolutePath}" }
                            ) { file ->
                                StorageFileRow(file = file, onDelete = { pendingDelete = file })
                            }
                        }
                    }

                    StorageManagerSection.DUPLICATES -> {
                        item {
                            StorageListIntroduction(
                                title = "重复文件",
                                detail = "SHA-256完整内容确认，可释放${formatStorageBytes(snapshot!!.duplicateReclaimableBytes)}",
                                warning = "请逐组确认要保留哪一份；App不会自动选择或删除。"
                            )
                        }
                        if (snapshot!!.duplicateGroups.isEmpty()) {
                            item {
                                StorageEmptyCard("没有发现大于等于1MB的重复文件")
                            }
                        } else {
                            items(
                                items = snapshot!!.duplicateGroups,
                                key = DuplicateFileGroup::sha256
                            ) { group ->
                                StorageDuplicateGroupCard(
                                    group = group,
                                    onDelete = { file -> pendingDelete = file }
                                )
                            }
                        }
                    }

                    StorageManagerSection.DOWNLOADS -> {
                        val directDownloadCount = snapshot!!.downloads.count(
                            StorageFileEntry::directlyInDownloads
                        )
                        item {
                            StorageDownloadsActions(
                                totalCount = snapshot!!.downloads.size,
                                directCount = directDownloadCount,
                                isBusy = isOperating,
                                onOrganize = {
                                    confirmOrganize = true
                                }
                            )
                        }
                        if (snapshot!!.downloads.isEmpty()) {
                            item {
                                StorageEmptyCard("Download目录目前没有文件")
                            }
                        } else {
                            items(
                                items = snapshot!!.downloads,
                                key = { file -> "download:${file.absolutePath}" }
                            ) { file ->
                                StorageFileRow(file = file, onDelete = { pendingDelete = file })
                            }
                        }
                    }

                    StorageManagerSection.STALE_FILES -> {
                        item {
                            StorageListIntroduction(
                                title = "长期未修改",
                                detail = "超过90天未修改，共${snapshot!!.staleFiles.size}个",
                                warning = "Android无法可靠获知最后打开时间，这里只依据文件最后修改日期提示。"
                            )
                        }
                        if (snapshot!!.staleFiles.isEmpty()) {
                            item {
                                StorageEmptyCard("没有发现超过90天未修改的文件")
                            }
                        } else {
                            items(
                                items = snapshot!!.staleFiles,
                                key = { file -> "stale:${file.absolutePath}" }
                            ) { file ->
                                StorageFileRow(file = file, onDelete = { pendingDelete = file })
                            }
                        }
                    }
                }

                item {
                    val limitText = if (snapshot!!.reachedFileLimit) {
                        "已达到10万个文件保护上限，本次结果不是完整扫描。"
                    } else {
                        "已扫描${snapshot!!.scannedFileCount}个文件。"
                    }
                    Text(
                        text = "$limitText 跳过${snapshot!!.skippedPathCount}个受限或异常路径；Android/data与Android/obb不会扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 文件空间页面的五个稳定内容分区。 */
private enum class StorageManagerSection(val title: String) {
    OVERVIEW("总览"),
    LARGE_FILES("大文件"),
    DUPLICATES("重复"),
    DOWNLOADS("下载"),
    STALE_FILES("长期")
}

/**
 * 显示文件空间页面标题、返回和刷新操作。
 *
 * @param isBusy 扫描或文件操作是否正在执行。
 * @param onBack 返回回调。
 * @param onRefresh 重新扫描回调。
 * @return 无返回值。
 */
@Composable
private fun StorageManagerHeader(
    isBusy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(text = "‹ 返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "文件空间",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "看清占用，再决定整理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRefresh, enabled = !isBusy) {
            Text(text = "刷新")
        }
    }
}

/**
 * 显示完整文件访问权限的用途、隐私边界和授权入口。
 *
 * @param settingsError 无法启动系统授权页时的提示。
 * @param onRequestPermission 根据系统版本申请对应权限的回调。
 * @return 无返回值。
 */
@Composable
private fun StoragePermissionCard(
    settingsError: String,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "需要文件管理权限",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "为了查找全机共享存储中的大文件和重复文件，需要你在系统页允许“所有文件访问”。扫描只在本机进行，不上传内容；任何删除和移动都要你再次确认。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    modifier = Modifier.padding(12.dp),
                    text = "不会扫描Android/data和Android/obb，也不会触碰其他App的私有数据库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRequestPermission
            ) {
                Text(text = "去系统设置授权")
            }
            if (settingsError.isNotBlank()) {
                Text(
                    text = settingsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 显示共享存储正在后台扫描和校验重复内容的状态。 */
@Composable
private fun StorageScanningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text(text = "正在扫描文件并校验重复内容…")
            Text(
                text = "文件较多时可能需要一些时间，请保持页面打开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示分区总容量、已用和剩余容量。
 *
 * @param snapshot 当前扫描快照。
 * @return 无返回值。
 */
@Composable
private fun StorageCapacityCard(snapshot: StorageScanSnapshot) {
    val usedFraction = if (snapshot.totalSpaceBytes > 0L) {
        snapshot.usedSpaceBytes.toFloat() / snapshot.totalSpaceBytes.toFloat()
    } else {
        0f
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF087F5B), Color(0xFF22A6B3))
                    )
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "共享存储已使用",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.82f)
            )
            Text(
                text = formatStorageBytes(snapshot.usedSpaceBytes),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            LinearProgressIndicator(
                progress = { usedFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "总容量 ${formatStorageBytes(snapshot.totalSpaceBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f)
                )
                Text(
                    text = "剩余 ${formatStorageBytes(snapshot.freeSpaceBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
        }
    }
}

/**
 * 显示五个文件管理分区的横向选择按钮。
 *
 * @param selectedSection 当前分区。
 * @param onSelect 用户选择分区时的回调。
 * @return 无返回值。
 */
@Composable
private fun StorageSectionSelector(
    selectedSection: StorageManagerSection,
    onSelect: (StorageManagerSection) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = StorageManagerSection.entries,
            key = StorageManagerSection::name
        ) { section ->
            FilterChip(
                selected = selectedSection == section,
                onClick = { onSelect(section) },
                label = {
                    Text(
                        text = section.title,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            )
        }
    }
}

/**
 * 显示图片、视频、音频、文档、压缩包、安装包和其他文件的容量分类。
 *
 * @param categories 包含全部分类的容量汇总。
 * @return 无返回值。
 */
@Composable
private fun StorageCategoryCard(categories: List<StorageCategorySummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "文件容量分类",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            categories.forEach { summary ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = storageCategoryColor(summary.category).copy(alpha = 0.16f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = storageCategorySymbol(summary.category),
                                fontWeight = FontWeight.Bold,
                                color = storageCategoryColor(summary.category)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = storageCategoryTitle(summary.category),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${summary.fileCount}个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatStorageBytes(summary.totalBytes),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 显示总览中的大文件、重复空间、下载和长期文件数量。
 *
 * @param snapshot 当前扫描快照。
 * @return 无返回值。
 */
@Composable
private fun StorageFindingSummary(snapshot: StorageScanSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "值得看看",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            StorageFindingRow("大", "大文件", "${snapshot.largeFiles.size}个")
            StorageFindingRow(
                "重",
                "重复文件",
                "可释放${formatStorageBytes(snapshot.duplicateReclaimableBytes)}"
            )
            StorageFindingRow("下", "下载目录", "${snapshot.downloads.size}个文件")
            StorageFindingRow("久", "90天未修改", "${snapshot.staleFiles.size}个")
        }
    }
}

/**
 * 显示总览中的单条发现结果。
 *
 * @param symbol 结果符号。
 * @param title 结果标题。
 * @param value 数量或容量。
 * @return 无返回值。
 */
@Composable
private fun StorageFindingRow(symbol: String, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = symbol, fontWeight = FontWeight.Bold)
            }
        }
        Text(modifier = Modifier.weight(1f), text = title)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 显示文件列表标题、数量详情和风险提示。
 *
 * @param title 列表标题。
 * @param detail 当前数量或容量详情。
 * @param warning 需要用户了解的边界说明。
 * @return 无返回值。
 */
@Composable
private fun StorageListIntroduction(title: String, detail: String, warning: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = detail, fontWeight = FontWeight.SemiBold)
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
            )
        }
    }
}

/**
 * 显示一个可检查并可请求删除的文件。
 *
 * @param file 文件扫描条目。
 * @param onDelete 请求打开永久删除确认框的回调。
 * @return 无返回值。
 */
@Composable
private fun StorageFileRow(file: StorageFileEntry, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = storageCategoryColor(file.category).copy(alpha = 0.16f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = storageCategorySymbol(file.category),
                            fontWeight = FontWeight.Bold,
                            color = storageCategoryColor(file.category)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.displayName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = file.relativePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatStorageBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "修改于 ${formatStorageDate(file.lastModifiedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 显示一组经完整内容摘要确认的重复文件。
 *
 * @param group 重复文件组。
 * @param onDelete 用户选择某个副本时的删除请求回调。
 * @return 无返回值。
 */
@Composable
private fun StorageDuplicateGroupCard(
    group: DuplicateFileGroup,
    onDelete: (StorageFileEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${group.files.size}份相同内容",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "可释放${formatStorageBytes(group.reclaimableBytes)}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "每份 ${formatStorageBytes(group.sizeBytes)} · 摘要 ${group.sha256.take(12)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            group.files.forEach { file ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = file.relativePath,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onDelete(file) }) {
                            Text(text = "删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 显示Download目录统计和用户确认式一键分类入口。
 *
 * @param totalCount Download及子目录中的全部文件数。
 * @param directCount 可安全自动移动的Download直接子文件数。
 * @param isBusy 当前是否正在移动文件。
 * @param onOrganize 请求打开整理确认框的回调。
 * @return 无返回值。
 */
@Composable
private fun StorageDownloadsActions(
    totalCount: Int,
    directCount: Int,
    isBusy: Boolean,
    onOrganize: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "下载目录整理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(text = "共${totalCount}个文件，其中${directCount}个顶层文件可整理")
            Text(
                text = "确认后移动到 Download/Harley整理/图片、视频、音频等目录；不移动已有子文件夹，也不覆盖同名文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOrganize,
                enabled = directCount > 0 && !isBusy
            ) {
                Text(text = if (isBusy) "正在整理…" else "预览并确认整理")
            }
        }
    }
}

/**
 * 显示永久删除二次确认，明确文件名、大小和不可恢复性质。
 *
 * @param file 待删除文件。
 * @param isOperating 删除是否正在执行。
 * @param onDismiss 取消删除回调。
 * @param onConfirm 最终确认删除回调。
 * @return 无返回值。
 */
@Composable
private fun StorageDeleteConfirmationDialog(
    file: StorageFileEntry,
    isOperating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "永久删除这个文件？") },
        text = {
            Text(
                text = "${file.displayName}\n${file.relativePath}\n${formatStorageBytes(file.sizeBytes)}\n\n此操作不经过回收站，无法在App内恢复。"
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isOperating
            ) {
                Text(text = if (isOperating) "删除中…" else "确认永久删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isOperating) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示下载文件分类移动前的二次确认。
 *
 * @param fileCount 将被移动的Download顶层文件数量。
 * @param isOperating 移动是否正在执行。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认移动回调。
 * @return 无返回值。
 */
@Composable
private fun StorageOrganizeConfirmationDialog(
    fileCount: Int,
    isOperating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "整理${fileCount}个下载文件？") },
        text = {
            Text(
                text = "文件会按类型移动到Download/Harley整理下。已有目录不变、同名文件不会被覆盖；整理后部分App里的旧下载路径可能失效。"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isOperating && fileCount > 0) {
                Text(text = if (isOperating) "整理中…" else "确认移动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isOperating) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示最近一次用户文件操作的结果。
 *
 * @param message 删除或整理结果文本。
 * @return 无返回值。
 */
@Composable
private fun StorageOperationMessageCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            modifier = Modifier.padding(13.dp),
            text = message,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * 显示文件扫描失败原因。
 *
 * @param error 控制器返回的稳定错误类型。
 * @return 无返回值。
 */
@Composable
private fun StorageScanErrorCard(error: StorageScanError?) {
    val message = when (error) {
        StorageScanError.FULL_ACCESS_REQUIRED -> "请先授予文件管理权限"
        StorageScanError.STORAGE_UNAVAILABLE -> "共享存储当前不可用，请检查存储状态"
        StorageScanError.SCAN_FAILED -> "文件扫描失败，请重新授权后再试"
        null -> "暂时没有扫描结果"
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示某个文件列表没有结果时的空状态。
 *
 * @param message 当前分区对应的空状态说明。
 * @return 无返回值。
 */
@Composable
private fun StorageEmptyCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 返回文件分类的中文标题。
 *
 * @param category 文件分类。
 * @return 页面展示标题。
 */
private fun storageCategoryTitle(category: StorageFileCategory): String = when (category) {
    StorageFileCategory.IMAGE -> "图片"
    StorageFileCategory.VIDEO -> "视频"
    StorageFileCategory.AUDIO -> "音频"
    StorageFileCategory.DOCUMENT -> "文档"
    StorageFileCategory.ARCHIVE -> "压缩包"
    StorageFileCategory.INSTALLER -> "安装包"
    StorageFileCategory.OTHER -> "其他"
}

/**
 * 返回文件分类的单字占位符号。
 *
 * @param category 文件分类。
 * @return 适合小尺寸图标容器的单字。
 */
private fun storageCategorySymbol(category: StorageFileCategory): String = when (category) {
    StorageFileCategory.IMAGE -> "图"
    StorageFileCategory.VIDEO -> "影"
    StorageFileCategory.AUDIO -> "音"
    StorageFileCategory.DOCUMENT -> "文"
    StorageFileCategory.ARCHIVE -> "压"
    StorageFileCategory.INSTALLER -> "装"
    StorageFileCategory.OTHER -> "其"
}

/**
 * 返回文件分类的识别颜色。
 *
 * @param category 文件分类。
 * @return 对应分类的固定高对比颜色。
 */
private fun storageCategoryColor(category: StorageFileCategory): Color = when (category) {
    StorageFileCategory.IMAGE -> Color(0xFFE0568C)
    StorageFileCategory.VIDEO -> Color(0xFF6857E5)
    StorageFileCategory.AUDIO -> Color(0xFF008F7A)
    StorageFileCategory.DOCUMENT -> Color(0xFF1677FF)
    StorageFileCategory.ARCHIVE -> Color(0xFFF08C00)
    StorageFileCategory.INSTALLER -> Color(0xFF2F9E44)
    StorageFileCategory.OTHER -> Color(0xFF667085)
}

/**
 * 把字节数转换为B、KB、MB、GB或TB容量文本。
 *
 * @param bytes 原始字节数，负数按0处理。
 * @return 保留一位小数的易读容量文本。
 */
private fun formatStorageBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.CHINA, "%.1f %s", value, units[unitIndex])
    }
}

/**
 * 把文件最后修改毫秒时间转换为本地日期。
 *
 * @param timestampMillis 文件系统时间；零值表示未知。
 * @return “yyyy年M月d日”文本，未知时返回“时间未知”。
 */
private fun formatStorageDate(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return "时间未知"
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(STORAGE_DATE_FORMATTER)
}

private val STORAGE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
