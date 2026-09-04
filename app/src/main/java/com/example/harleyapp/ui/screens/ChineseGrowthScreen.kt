package com.example.harleyapp.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.harleyapp.data.ChineseGrowthCatalog
import com.example.harleyapp.data.ChineseGrowthRepository
import com.example.harleyapp.model.ChineseClassicLesson
import com.example.harleyapp.model.ChineseGrowthProgress
import com.example.harleyapp.model.ChineseGrowthSection
import com.example.harleyapp.model.ChineseReadingLoadResult
import com.example.harleyapp.model.ChineseReadingTopic
import com.example.harleyapp.model.ChineseWritingMission
import com.example.harleyapp.model.PrimarySchoolGrade

/**
 * 显示按年级组织的今日计划、写作训练、离线精读和诗词积累。
 *
 * 使用方法：
 * 功能中心传入长期复用的[ChineseGrowthRepository]。首页给出短时学习路线和最近七天进度；写作
 * 支持草稿保存与完成记录；阅读核心正文和诗词均可离线使用，延伸阅读只打开固定国内HTTPS地址。
 *
 * @param repository 年级选择、草稿和学习进度仓库。
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外层传入的安全边距修饰器。
 * @return 无返回值，直接输出语文成长完整页面。
 */
@Composable
fun ChineseGrowthScreen(
    repository: ChineseGrowthRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedGrade by remember(repository) {
        mutableStateOf(repository.getSelectedGrade())
    }
    var selectedSectionName by rememberSaveable {
        mutableStateOf(ChineseGrowthSection.OVERVIEW.name)
    }
    var selectedMissionId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedTopicId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedClassicId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var progressRevision by rememberSaveable {
        mutableIntStateOf(0)
    }
    var operationMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val selectedSection = ChineseGrowthSection.entries.firstOrNull { section ->
        section.name == selectedSectionName
    } ?: ChineseGrowthSection.OVERVIEW
    val writingMissions = ChineseGrowthCatalog.writingMissionsFor(selectedGrade)
    val readingTopics = ChineseGrowthCatalog.readingTopicsFor(selectedGrade)
    val classicLessons = ChineseGrowthCatalog.classicLessonsFor(selectedGrade)
    val progress = remember(repository, progressRevision) {
        repository.getProgress()
    }
    val selectedMission = writingMissions.firstOrNull { mission ->
        mission.id == selectedMissionId
    }
    val selectedTopic = ChineseGrowthCatalog.allReadingTopics().firstOrNull { topic ->
        topic.id == selectedTopicId
    }
    val selectedClassic = ChineseGrowthCatalog.allClassicLessons().firstOrNull { lesson ->
        lesson.id == selectedClassicId
    }

    val handleBack: () -> Unit = {
        when {
            selectedMissionId != null -> selectedMissionId = null
            selectedTopicId != null -> {
                selectedTopicId = null
            }
            selectedClassicId != null -> selectedClassicId = null
            else -> onBack()
        }
    }
    BackHandler(onBack = handleBack)
    val completeContent: (String) -> Unit = { contentId ->
        if (repository.markContentCompleted(contentId)) {
            progressRevision += 1
            operationMessage = "已记录本次学习，继续保持"
        } else {
            operationMessage = "学习进度保存失败，请稍后重试"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChineseGrowthHeader(
            title = when {
                selectedMission != null -> selectedMission.title
                selectedTopic != null -> selectedTopic.title
                selectedClassic != null -> selectedClassic.title
                else -> "语文成长"
            },
            subtitle = when {
                selectedMission != null -> "${selectedGrade.displayName} · 写作训练"
                selectedTopic != null -> "${selectedTopic.category} · 离线精读"
                selectedClassic != null -> "${selectedGrade.displayName} · 诗词积累"
                else -> "每天一点积累、阅读和表达"
            },
            onBack = handleBack
        )

        if (selectedMission != null) {
            WritingMissionDetail(
                mission = selectedMission,
                repository = repository,
                isCompleted = selectedMission.id in progress.completedContentIds,
                onCompleted = { completeContent(selectedMission.id) },
                onMessage = { message -> operationMessage = message },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        if (selectedTopic != null) {
            ReadingArticleDetail(
                topic = selectedTopic,
                result = repository.loadArticle(selectedTopic),
                isCompleted = selectedTopic.id in progress.completedContentIds,
                onCompleted = { completeContent(selectedTopic.id) },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        if (selectedClassic != null) {
            ClassicLessonDetail(
                lesson = selectedClassic,
                isCompleted = selectedClassic.id in progress.completedContentIds,
                onCompleted = { completeContent(selectedClassic.id) },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        Text(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            text = "选择年级",
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
            PrimarySchoolGrade.entries.forEach { grade ->
                FilterChip(
                    selected = selectedGrade == grade,
                    onClick = {
                        if (repository.saveSelectedGrade(grade)) {
                            selectedGrade = grade
                            operationMessage = null
                        } else {
                            operationMessage = "年级保存失败，请稍后重试"
                        }
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
            ChineseGrowthSection.entries.forEach { section ->
                FilterChip(
                    selected = selectedSection == section,
                    onClick = { selectedSectionName = section.name },
                    label = { Text(text = section.displayName) }
                )
            }
        }
        operationMessage?.let { message ->
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        when (selectedSection) {
            ChineseGrowthSection.OVERVIEW -> ChineseGrowthOverview(
                grade = selectedGrade,
                progress = progress,
                writingMission = writingMissions.first(),
                readingTopic = readingTopics.first(),
                classicLesson = classicLessons.first(),
                onOpenWriting = { selectedMissionId = writingMissions.first().id },
                onOpenReading = { selectedTopicId = readingTopics.first().id },
                onOpenClassic = { selectedClassicId = classicLessons.first().id },
                modifier = Modifier.weight(1f)
            )

            ChineseGrowthSection.WRITING -> WritingMissionList(
                missions = writingMissions,
                completedIds = progress.completedContentIds,
                onOpen = { mission -> selectedMissionId = mission.id },
                modifier = Modifier.weight(1f)
            )

            ChineseGrowthSection.READING -> ReadingTopicList(
                topics = readingTopics,
                completedIds = progress.completedContentIds,
                onOpen = { topic -> selectedTopicId = topic.id },
                modifier = Modifier.weight(1f)
            )

            ChineseGrowthSection.CLASSICS -> ClassicLessonList(
                lessons = classicLessons,
                completedIds = progress.completedContentIds,
                onOpen = { lesson -> selectedClassicId = lesson.id },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 显示语文页面统一返回按钮、标题和说明。 */
@Composable
private fun ChineseGrowthHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "← 返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 显示当前年级的成长概览、最近七天目标和三步学习路线。
 *
 * @param grade 当前选择年级。
 * @param progress 本机累计、近七天和连续学习数据。
 * @param writingMission 今日推荐写作任务。
 * @param readingTopic 今日推荐离线精读主题。
 * @param classicLesson 今日推荐诗词内容。
 * @param onOpenWriting 打开推荐写作详情的回调。
 * @param onOpenReading 打开推荐精读详情的回调。
 * @param onOpenClassic 打开推荐诗词详情的回调。
 * @param modifier 外部布局修饰器。
 * @return 无返回值，直接输出可滚动成长首页。
 */
@Composable
private fun ChineseGrowthOverview(
    grade: PrimarySchoolGrade,
    progress: ChineseGrowthProgress,
    writingMission: ChineseWritingMission,
    readingTopic: ChineseReadingTopic,
    classicLesson: ChineseClassicLesson,
    onOpenWriting: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenClassic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val weeklyTarget = 5
    val weeklyProgress = progress.weeklyCompletedCount.coerceAtMost(weeklyTarget)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${grade.displayName} · 本周成长",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (weeklyProgress >= weeklyTarget) {
                            "本周目标已完成"
                        } else {
                            "再完成${weeklyTarget - weeklyProgress}项，点亮本周目标"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    LinearProgressIndicator(
                        progress = { weeklyProgress / weeklyTarget.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GrowthMetric(
                            modifier = Modifier.weight(1f),
                            value = "${progress.streakDays}天",
                            label = "连续学习"
                        )
                        GrowthMetric(
                            modifier = Modifier.weight(1f),
                            value = "${progress.weeklyCompletedCount}项",
                            label = "最近七天"
                        )
                        GrowthMetric(
                            modifier = Modifier.weight(1f),
                            value = "${progress.totalCompletedCount}项",
                            label = "累计完成"
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "今日三步路线", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "约30分钟，先积累，再精读，最后表达",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            GrowthPlanCard(
                step = "第1步 · 5分钟",
                title = classicLesson.title,
                description = "朗读诗词，抓住一处画面和一个关键词",
                completed = classicLesson.id in progress.completedContentIds,
                onOpen = onOpenClassic
            )
        }
        item {
            GrowthPlanCard(
                step = "第2步 · 10分钟",
                title = readingTopic.title,
                description = "离线精读，并回答一个信息提取问题",
                completed = readingTopic.id in progress.completedContentIds,
                onOpen = onOpenReading
            )
        }
        item {
            GrowthPlanCard(
                step = "第3步 · 15分钟",
                title = writingMission.title,
                description = "按方法列提纲，把今天的观察写下来",
                completed = writingMission.id in progress.completedContentIds,
                onOpen = onOpenWriting
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(text = "国内网络友好", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "训练、正文、诗词和进度都保存在本机。只有你主动点开延伸资料时才访问固定的国内HTTPS站点。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** 显示成长首页的单项数字指标。 */
@Composable
private fun GrowthMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 显示今日路线中的一步，并明确完成状态。 */
@Composable
private fun GrowthPlanCard(
    step: String,
    title: String,
    description: String,
    completed: Boolean,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = if (completed) "$step · 已完成" else step,
                style = MaterialTheme.typography.labelMedium,
                color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 显示当前年级三个写作训练入口。 */
@Composable
private fun WritingMissionList(
    missions: List<ChineseWritingMission>,
    completedIds: Set<String>,
    onOpen: (ChineseWritingMission) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "writing_intro") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "每次只练一个能力：先看方法和提纲，再自己动笔，最后按清单修改。不提供整篇套用范文。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        items(missions, key = ChineseWritingMission::id) { mission ->
            Card(
                onClick = { onOpen(mission) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (mission.id in completedIds) "已完成" else "待练习",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mission.id in completedIds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = mission.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = "训练重点：${mission.focus}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = mission.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 显示写作方法、可编辑草稿、自查清单和完成入口。
 *
 * @param mission 当前写作训练。
 * @param repository 本机草稿仓库。
 * @param isCompleted 当前任务是否已经计入成长进度。
 * @param onCompleted 用户确认完成本次训练的回调。
 * @param onMessage 草稿保存结果需要显示在上层页面的回调。
 * @param modifier 外部布局修饰器。
 * @return 无返回值，直接输出写作训练详情。
 */
@Composable
private fun WritingMissionDetail(
    mission: ChineseWritingMission,
    repository: ChineseGrowthRepository,
    isCompleted: Boolean,
    onCompleted: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(mission.id) {
        mutableStateOf(repository.getWritingDraft(mission.id))
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            DetailTextCard("本次重点", mission.focus)
        }
        item {
            DetailTextCard("立即练笔", mission.prompt)
        }
        item {
            DetailListCard("写作方法", mission.methodSteps)
        }
        item {
            DetailListCard("我的提纲", mission.outline.map { item -> "□ $item" })
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = "我的草稿", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        value = draft,
                        onValueChange = { newValue ->
                            if (newValue.length <= ChineseGrowthRepository.WRITING_DRAFT_MAX_CHARS) {
                                draft = newValue
                            }
                        },
                        label = { Text(text = "从提纲开始写") },
                        supportingText = {
                            Text(text = "${draft.length}/${ChineseGrowthRepository.WRITING_DRAFT_MAX_CHARS}字 · 点击下方按钮保存到本机")
                        }
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val saved = repository.saveWritingDraft(mission.id, draft)
                            onMessage(if (saved) "草稿已保存在本机" else "草稿保存失败，请稍后重试")
                        }
                    ) {
                        Text(text = "保存草稿")
                    }
                }
            }
        }
        item {
            DetailListCard("写完自查", mission.checklist.map { item -> "□ $item" })
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCompleted,
                onClick = onCompleted
            ) {
                Text(text = if (isCompleted) "本次训练已完成" else "完成本次训练")
            }
        }
    }
}

/** 显示当前年级经过白名单筛选的阅读主题。 */
@Composable
private fun ReadingTopicList(
    topics: List<ChineseReadingTopic>,
    completedIds: Set<String>,
    onOpen: (ChineseReadingTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "reading_intro") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "核心正文、阅读问题和小练笔全部离线可用。需要了解更多时，可主动打开固定的国内延伸资料。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        items(topics, key = ChineseReadingTopic::id) { topic ->
            Card(
                onClick = { onOpen(topic) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${topic.category} · ${if (topic.id in completedIds) "已完成" else "离线精读"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = topic.offlineGuide, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 显示离线原创精读、理解问题、小练笔和国内延伸资料。
 *
 * @param topic 当前固定阅读主题。
 * @param result 本机仓库生成的离线文章结果。
 * @param isCompleted 当前主题是否已经计入成长进度。
 * @param onCompleted 用户确认完成精读的回调。
 * @param modifier 外部布局修饰器。
 * @return 无返回值，直接输出阅读详情。
 */
@Composable
private fun ReadingArticleDetail(
    topic: ChineseReadingTopic,
    result: ChineseReadingLoadResult,
    isCompleted: Boolean,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val article = result.article

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(text = "离线精读", style = MaterialTheme.typography.labelLarge)
                    Text(text = article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = "正文和练习已随App保存在手机中，断网也能完整学习。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item {
            if (article.description.isNotBlank()) {
                Text(text = article.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = article.body,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            DetailTextCard("阅读思考", topic.observationQuestion)
        }
        item {
            DetailTextCard("读后小练笔", topic.writingChallenge)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "国内延伸资料", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = "来源：${topic.extensionSourceName}", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(text = topic.extensionSourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        text = "只有点击下方按钮才会联网，链接固定且不包含你的学习内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openDomesticReadingSource(context, topic.extensionSourceUrl) }
                    ) {
                        Text(text = "打开国内延伸资料")
                    }
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCompleted,
                onClick = onCompleted
            ) {
                Text(text = if (isCompleted) "本篇精读已完成" else "完成本篇精读")
            }
        }
    }
}

/** 显示当前年级的两篇离线诗词课程，并标记完成状态。 */
@Composable
private fun ClassicLessonList(
    lessons: List<ChineseClassicLesson>,
    completedIds: Set<String>,
    onOpen: (ChineseClassicLesson) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "classic_intro") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "不只背原文：先读画面，再找关键词，最后用一个问题检查是否真正理解。全部内容可离线使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        items(lessons, key = ChineseClassicLesson::id) { lesson ->
            Card(
                onClick = { onOpen(lesson) },
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (lesson.id in completedIds) "已完成" else "诗词积累",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(text = lesson.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(text = lesson.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = lesson.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 显示诗词原文、原创赏析、背诵方法和理解问题。 */
@Composable
private fun ClassicLessonDetail(
    lesson: ChineseClassicLesson,
    isCompleted: Boolean,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(text = lesson.author, style = MaterialTheme.typography.bodyMedium)
                    Text(text = lesson.text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            DetailTextCard("读懂画面", lesson.appreciation)
        }
        item {
            DetailTextCard("朗读与背诵", lesson.recitationTip)
        }
        item {
            DetailTextCard("理解挑战", lesson.practiceQuestion)
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCompleted,
                onClick = onCompleted
            ) {
                Text(text = if (isCompleted) "本篇积累已完成" else "完成本篇积累")
            }
        }
    }
}

/** 只打开目录内置的百度百科HTTPS链接，异常仅记录英文日志。 */
private fun openDomesticReadingSource(context: Context, sourceUrl: String) {
    val uri = runCatching { sourceUrl.toUri() }.getOrNull() ?: return
    if (uri.scheme != "https" || uri.host != "baike.baidu.com") return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.onFailure { error ->
        Log.e(CHINESE_GROWTH_TAG, "Failed to open domestic reading source", error)
    }
}

/** 显示一个标题加正文的详情信息卡。 */
@Composable
private fun DetailTextCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 显示一个标题和多行步骤列表的详情信息卡。 */
@Composable
private fun DetailListCard(title: String, lines: List<String>) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val CHINESE_GROWTH_TAG = "ChineseGrowthScreen"
