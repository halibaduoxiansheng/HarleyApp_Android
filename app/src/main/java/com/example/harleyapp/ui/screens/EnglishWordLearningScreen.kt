package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EnglishLearningStage
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.system.OfflineEnglishTtsState

/**
 * 显示完全离线的英语单词学习页，并按学习次数提供四个分栏。
 *
 * 使用方法：
 * 功能中心进入英语学习详情时调用。用户在0、1、2次分栏点击“学会 +1”，保存成功后该单词会
 * 自动进入下一分栏；在3次分栏可以选择“重新学习”，把单词恢复到未学会。单词和例句的发音
 * 均通过上层提供的Android离线TTS回调完成。
 *
 * @param words 已合并本机进度的完整离线单词列表。
 * @param ttsState Android离线英语TTS当前状态。
 * @param onSpeakEnglish 提交英文朗读的回调，成功返回true。
 * @param onMarkLearned 把指定单词学习次数增加一次的回调，保存成功返回true。
 * @param onResetWord 把指定单词恢复到未学会分栏的回调，保存成功返回true。
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外部页面安全边距修饰器。
 *
 * @return 无返回值，直接输出英语学习界面。
 */
@Composable
fun EnglishWordLearningScreen(
    words: List<EnglishWord>,
    ttsState: OfflineEnglishTtsState,
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: (String) -> Boolean,
    onResetWord: (String) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedStageCount by rememberSaveable {
        mutableStateOf(EnglishLearningStage.NOT_LEARNED.learnedCount)
    }
    var operationMessage by remember {
        mutableStateOf<String?>(null)
    }
    val selectedStage = EnglishLearningStage.fromLearnedCount(selectedStageCount)
    val displayedWords = remember(words, selectedStage) {
        words.filter { word -> word.learnedCount == selectedStage.learnedCount }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    text = "英语单词",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "完全离线 · 学会三次后完成本轮",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        TtsStatusBanner(
            state = ttsState,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnglishLearningStage.entries.forEach { stage ->
                val stageWordCount = words.count { word ->
                    word.learnedCount == stage.learnedCount
                }
                FilterChip(
                    selected = selectedStage == stage,
                    onClick = {
                        selectedStageCount = stage.learnedCount
                        operationMessage = null
                    },
                    label = {
                        Text(text = "${stage.title} $stageWordCount")
                    }
                )
            }
        }

        operationMessage?.let { message ->
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (displayedWords.isEmpty()) {
                item(key = "empty_${selectedStage.name}") {
                    EmptyEnglishStageCard(stage = selectedStage)
                }
            } else {
                items(
                    items = displayedWords,
                    key = EnglishWord::id
                ) { word ->
                    EnglishWordStudyCard(
                        word = word,
                        ttsReady = ttsState == OfflineEnglishTtsState.READY,
                        onSpeakEnglish = onSpeakEnglish,
                        onMarkLearned = {
                            operationMessage = if (onMarkLearned(word.id)) {
                                null
                            } else {
                                "学习进度保存失败，请重试"
                            }
                        },
                        onResetWord = {
                            operationMessage = if (onResetWord(word.id)) {
                                null
                            } else {
                                "学习进度保存失败，请重试"
                            }
                        }
                    )
                }
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
 * 显示单个单词、中文释义、中英例句、发音按钮和进度操作。
 *
 * @param word 当前单词和进度。
 * @param ttsReady true表示发音按钮可用。
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
    onSpeakEnglish: (String) -> Boolean,
    onMarkLearned: () -> Unit,
    onResetWord: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = word.word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                TextButton(
                    enabled = ttsReady,
                    onClick = { onSpeakEnglish(word.word) }
                ) {
                    Text(text = "发音")
                }
            }

            Text(
                text = word.meaningZh,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = word.exampleEn,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = word.exampleZh,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = ttsReady,
                    onClick = { onSpeakEnglish(word.exampleEn) }
                ) {
                    Text(text = "朗读例句")
                }

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
        }
    }
}

/**
 * 显示空分栏的友好说明。
 *
 * @param stage 当前没有单词的学习阶段。
 * @return 无返回值。
 */
@Composable
private fun EmptyEnglishStageCard(stage: EnglishLearningStage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            modifier = Modifier.padding(18.dp),
            text = if (stage == EnglishLearningStage.NOT_LEARNED) {
                "这里暂时没有单词，可以去其他分栏继续复习。"
            } else {
                "“${stage.title}”分栏暂时为空。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
