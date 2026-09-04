package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.ENGLISH_WORD_ALL_STAGES
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EnglishLearningStage
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.EnglishWordLibraryMode
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.SchoolTerm
import com.example.harleyapp.model.primaryEnglishWordsForSelection
import com.example.harleyapp.model.resolveEnglishLearningExample
import com.example.harleyapp.model.searchEnglishWords
import com.example.harleyapp.system.OfflineEnglishTtsState

/**
 * 显示小学年级分册推荐、5000+词完整离线列表、进度分栏、搜索和单词独立详情页。
 *
 * 使用方法：
 * 功能中心进入英语学习功能时调用。默认显示用户上次保存的年级和册次，一、二年级明确标为
 * 启蒙词，三至六年级显示人教PEP（三年级起点）整理词；用户仍可切换到完整词库。输入英文
 * 前缀、英文片段或中文释义后会立即筛选，点击结果进入独立学习页。
 *
 * @param words 已合并本机进度的完整离线单词列表。
 * @param selection 用户当前年级、册次和词库范围。
 * @param onSelectionChanged 保存新选择并刷新首页推荐的回调，成功返回true。
 * @param ttsState Android离线英语TTS当前状态。
 * @param onSpeakEnglish 提交英文朗读的回调，成功返回true。
 * @param onMarkLearned 把指定单词学习次数增加一次的回调，保存成功返回true。
 * @param onResetWord 把指定单词恢复到未学会分栏的回调，保存成功返回true。
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外部页面安全边距修饰器。
 * @param initialWordId 从全局搜索跳入时需要直接打开的单词id；普通进入时传null。
 * @param onInitialWordConsumed 初始单词已处理后的回调，避免下次进入时重复打开旧目标。
 * @return 无返回值，直接输出英语学习列表或当前单词详情页。
 */
@Composable
fun EnglishWordLearningScreen(
    words: List<EnglishWord>,
    selection: PrimaryEnglishSelection,
    onSelectionChanged: (PrimaryEnglishSelection) -> Boolean,
    ttsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: (String) -> Boolean,
    onResetWord: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialWordId: String? = null,
    onInitialWordConsumed: () -> Unit = {}
) {
    var selectedStageCount by rememberSaveable {
        mutableIntStateOf(ENGLISH_WORD_ALL_STAGES)
    }
    var searchQuery by rememberSaveable {
        mutableStateOf("")
    }
    var selectedWordId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectionMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    val selectedWord = words.firstOrNull { word -> word.id == selectedWordId }

    /**
     * 尝试保存一个新的筛选选择，失败时保留旧状态并给出提示。
     *
     * @param newSelection 用户刚点击形成的新选择。
     * @return 无返回值，结果通过上层状态和[selectionMessage]展示。
     */
    fun requestSelection(newSelection: PrimaryEnglishSelection) {
        selectionMessage = if (onSelectionChanged(newSelection)) {
            null
        } else {
            "选择保存失败，请稍后重试"
        }
    }

    // 全局搜索结果只消费一次；先保存有效词条id，再通知宿主清除一次性跳转参数。
    LaunchedEffect(initialWordId, words) {
        if (!initialWordId.isNullOrBlank()) {
            selectedWordId = words.firstOrNull { word -> word.id == initialWordId }?.id
            onInitialWordConsumed()
        }
    }

    // 仅在词库变化导致已选词条确实不存在时清理旧状态，不能把刚由全局搜索选中的有效词条清掉。
    LaunchedEffect(selectedWordId, selectedWord, initialWordId) {
        if (
            selectedWordId != null &&
            selectedWord == null &&
            initialWordId.isNullOrBlank()
        ) {
            selectedWordId = null
        }
    }

    if (selectedWord != null) {
        EnglishWordDetailScreen(
            modifier = modifier,
            word = selectedWord,
            ttsState = ttsState,
            onSpeakEnglish = onSpeakEnglish,
            onMarkLearned = { onMarkLearned(selectedWord.id) },
            onResetWord = { onResetWord(selectedWord.id) },
            onBack = { selectedWordId = null }
        )
        return
    }

    val scopedWords = remember(words, selection) {
        if (selection.mode == EnglishWordLibraryMode.FULL_LIBRARY) {
            words
        } else {
            primaryEnglishWordsForSelection(words, selection)
        }
    }
    val displayedWords = remember(scopedWords, selectedStageCount, searchQuery) {
        searchEnglishWords(
            words = scopedWords,
            query = searchQuery,
            learnedCount = selectedStageCount
        )
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        EnglishWordPageHeader(
            title = "英语单词",
            subtitle = "按年级推荐 · 5000+词离线词库",
            onBack = onBack
        )

        Text(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            text = "词库范围",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnglishWordLibraryMode.entries.forEach { mode ->
                FilterChip(
                    selected = selection.mode == mode,
                    onClick = {
                        requestSelection(selection.copy(mode = mode))
                    },
                    label = { Text(text = mode.displayName) }
                )
            }
        }

        if (selection.mode == EnglishWordLibraryMode.GRADE_RECOMMENDED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimarySchoolGrade.entries.forEach { grade ->
                    FilterChip(
                        selected = selection.grade == grade,
                        onClick = {
                            requestSelection(selection.copy(grade = grade))
                        },
                        label = { Text(text = grade.displayName) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SchoolTerm.entries.forEach { term ->
                    FilterChip(
                        selected = selection.term == term,
                        onClick = {
                            requestSelection(selection.copy(term = term))
                        },
                        label = { Text(text = term.displayName) }
                    )
                }
            }
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                text = if (selection.grade.gradeNumber <= 2) {
                    "${selection.grade.displayName}${selection.term.displayName}为启蒙推荐，共${scopedWords.size}词"
                } else {
                    "${selection.grade.displayName}${selection.term.displayName} · 人教PEP（三年级起点）整理，共${scopedWords.size}词"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        selectionMessage?.let { message ->
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            value = searchQuery,
            onValueChange = { value -> searchQuery = value },
            singleLine = true,
            label = { Text(text = "搜索单词或中文释义") },
            placeholder = { Text(text = "例如输入 goo 查找 good") },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text(text = "清除")
                    }
                }
            } else {
                null
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedStageCount == ENGLISH_WORD_ALL_STAGES,
                onClick = { selectedStageCount = ENGLISH_WORD_ALL_STAGES },
                label = { Text(text = "全部 ${scopedWords.size}") }
            )

            EnglishLearningStage.entries.forEach { stage ->
                val stageWordCount = scopedWords.count { word ->
                    word.learnedCount == stage.learnedCount
                }
                FilterChip(
                    selected = selectedStageCount == stage.learnedCount,
                    onClick = { selectedStageCount = stage.learnedCount },
                    label = { Text(text = "${stage.title} $stageWordCount") }
                )
            }
        }

        Text(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            text = if (searchQuery.isBlank()) {
                "当前显示 ${displayedWords.size} 个单词"
            } else {
                "“${searchQuery.trim()}”找到 ${displayedWords.size} 个结果"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 8.dp,
                end = 20.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (displayedWords.isEmpty()) {
                item(key = "empty_english_words") {
                    EmptyEnglishSearchCard(query = searchQuery)
                }
            } else {
                items(
                    items = displayedWords,
                    key = EnglishWord::id
                ) { word ->
                    EnglishWordSummaryCard(
                        word = word,
                        onOpen = { selectedWordId = word.id }
                    )
                }
            }
        }
    }
}

/**
 * 显示英语学习列表或详情页共用的返回栏。
 *
 * @param title 页面主标题。
 * @param subtitle 页面补充说明。
 * @param onBack 点击返回按钮时执行的回调。
 * @return 无返回值。
 */
@Composable
private fun EnglishWordPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, end = 20.dp, bottom = 6.dp),
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示一个适合5000词长列表的紧凑单词摘要。
 *
 * 使用方法：
 * LazyColumn仅为屏幕内可见项目创建本卡片。用户点击卡片任意位置后，通过[onOpen]进入该
 * 单词独立详情页，列表本身不再同时绘制完整例句和操作按钮。
 *
 * @param word 当前单词及学习进度。
 * @param onOpen 打开当前单词详情页的回调。
 * @return 无返回值。
 */
@Composable
private fun EnglishWordSummaryCard(
    word: EnglishWord,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = word.word,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${word.learnedCount}/$ENGLISH_WORD_MASTERY_COUNT  查看 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (word.phonetic.isNotBlank()) {
                Text(
                    text = "/${word.phonetic.trim('/')}/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = word.meaningZh,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 显示单个单词的独立学习页面。
 *
 * @param word 当前查看的完整单词内容和进度。
 * @param ttsState Android离线英语TTS当前状态。
 * @param onSpeakEnglish 朗读指定英文文本的回调。
 * @param onMarkLearned 当前单词学习次数加一的回调，成功返回true。
 * @param onResetWord 把当前单词恢复为未学会的回调，成功返回true。
 * @param onBack 返回搜索列表的回调。
 * @param modifier 外部页面修饰器。
 * @return 无返回值。
 */
@Composable
private fun EnglishWordDetailScreen(
    word: EnglishWord,
    ttsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: () -> Boolean,
    onResetWord: () -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var operationMessage by remember {
        mutableStateOf<String?>(null)
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        EnglishWordPageHeader(
            title = "单词详情",
            subtitle = "独立学习页 · 进度会自动保存到本机",
            onBack = onBack
        )

        TtsStatusBanner(
            state = ttsState,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 8.dp,
                end = 20.dp,
                bottom = 28.dp
            )
        ) {
            item(key = word.id) {
                EnglishWordStudyCard(
                    word = word,
                    ttsReady = ttsState == OfflineEnglishTtsState.READY,
                    operationMessage = operationMessage,
                    onSpeakEnglish = onSpeakEnglish,
                    onMarkLearned = {
                        operationMessage = if (onMarkLearned()) {
                            "学习进度已更新"
                        } else {
                            "学习进度保存失败，请重试"
                        }
                    },
                    onResetWord = {
                        operationMessage = if (onResetWord()) {
                            "已恢复为未学会"
                        } else {
                            "学习进度保存失败，请重试"
                        }
                    }
                )
            }
        }
    }
}

/**
 * 显示离线TTS的可用状态和缺失语音包时的处理提示。
 *
 * @param state 当前TTS状态。
 * @param modifier 外部布局修饰器。
 * @return 无返回值。
 */
@Composable
private fun TtsStatusBanner(
    state: OfflineEnglishTtsState,
    modifier: Modifier = Modifier
) {
    val message = offlineTtsStateMessage(state)
    val containerColor = when (state) {
        OfflineEnglishTtsState.READY -> MaterialTheme.colorScheme.secondaryContainer
        OfflineEnglishTtsState.INITIALIZING -> MaterialTheme.colorScheme.surfaceVariant
        OfflineEnglishTtsState.MISSING_OFFLINE_VOICE,
        OfflineEnglishTtsState.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (state) {
        OfflineEnglishTtsState.READY -> MaterialTheme.colorScheme.onSecondaryContainer
        OfflineEnglishTtsState.INITIALIZING -> MaterialTheme.colorScheme.onSurfaceVariant
        OfflineEnglishTtsState.MISSING_OFFLINE_VOICE,
        OfflineEnglishTtsState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor
        )
    }
}

/**
 * 显示单词的完整释义、发音、可选例句、来源标签和学习操作。
 *
 * @param word 当前单词和进度。
 * @param ttsReady true表示发音按钮可用。
 * @param operationMessage 最近一次学习操作结果，为null时不显示。
 * @param onSpeakEnglish 朗读英文文本的回调。
 * @param onMarkLearned 学习次数加一的回调。
 * @param onResetWord 恢复到未学会状态的回调。
 * @param modifier 外部布局修饰器。
 * @return 无返回值。
 */
@Composable
private fun EnglishWordStudyCard(
    word: EnglishWord,
    ttsReady: Boolean,
    operationMessage: String?,
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: () -> Unit,
    onResetWord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val learningExample = remember(word) {
        resolveEnglishLearningExample(word)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = word.word,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (word.phonetic.isNotBlank()) {
                        Text(
                            text = "/${word.phonetic.trim('/')}/",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(
                    enabled = ttsReady,
                    onClick = { onSpeakEnglish(word.word) }
                ) {
                    Text(text = "发音")
                }
            }

            WordDetailSection(title = "中文释义", content = word.meaningZh)

            if (word.definitionEn.isNotBlank()) {
                WordDetailSection(title = "英文释义", content = word.definitionEn)
            }

            WordDetailSection(
                title = if (learningExample.isGenerated) "通用英文例句" else "英文例句",
                content = learningExample.english
            )
            if (learningExample.chinese.isNotBlank()) {
                Text(
                    text = learningExample.chinese,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (learningExample.isGenerated) {
                Text(
                    text = "词库暂无专属例句，已在本机补充通用学习句。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = ttsReady,
                onClick = { onSpeakEnglish(learningExample.english) }
            ) {
                Text(text = if (ttsReady) "朗读例句" else "离线语音不可用")
            }

            if (word.tags.isNotEmpty()) {
                Text(
                    text = "词库标签：${word.tags.joinToString(" · ") { tag -> englishWordTagLabel(tag) }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (word.learnedCount < ENGLISH_WORD_MASTERY_COUNT) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onMarkLearned
                    ) {
                        Text(text = "学会 +1")
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onResetWord
                    ) {
                        Text(text = "重新学习")
                    }
                }
            }

            Text(
                text = "当前：已学会 ${word.learnedCount} / $ENGLISH_WORD_MASTERY_COUNT 次",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            operationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

/**
 * 显示单词详情中的一个带标题文本区块。
 *
 * @param title 区块标题。
 * @param content 需要完整显示的正文。
 * @return 无返回值。
 */
@Composable
private fun WordDetailSection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * 显示当前搜索或分栏没有结果时的说明。
 *
 * @param query 当前搜索文字，空白表示只是当前学习分栏为空。
 * @return 无返回值。
 */
@Composable
private fun EmptyEnglishSearchCard(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            modifier = Modifier.padding(18.dp),
            text = if (query.isBlank()) {
                "当前分栏暂时没有单词，可以切换到“全部”继续查看。"
            } else {
                "没有找到“${query.trim()}”，可以减少输入字母或尝试中文释义。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 把词库内部标签转换为适合用户阅读的名称。
 *
 * @param tag ECDICT中的考试或词库标签。
 * @return 可直接显示在单词详情页的中文名称；未知标签保留原值。
 */
private fun englishWordTagLabel(tag: String): String {
    return when (tag.lowercase()) {
        "zk" -> "中考"
        "gk" -> "高考"
        "cet4" -> "四级"
        "cet6" -> "六级"
        "ky" -> "考研"
        "ielts" -> "雅思"
        "toefl" -> "托福"
        "gre" -> "GRE"
        "oxford" -> "牛津核心"
        else -> tag
    }
}

/**
 * 把TTS内部状态转换成页面可读提示。
 *
 * @param state Android离线英语TTS状态。
 * @return 可直接显示给用户的中文说明。
 */
private fun offlineTtsStateMessage(state: OfflineEnglishTtsState): String {
    return when (state) {
        OfflineEnglishTtsState.INITIALIZING -> "正在检查本机英语离线语音…"
        OfflineEnglishTtsState.READY -> "本机英语离线发音已就绪，不会上传文字或音频。"
        OfflineEnglishTtsState.MISSING_OFFLINE_VOICE ->
            "尚未安装英语离线语音包，请到系统“文字转语音”设置中下载后重启App。"
        OfflineEnglishTtsState.ERROR ->
            "系统文字转语音暂不可用，请检查默认TTS引擎后重启App。"
    }
}
