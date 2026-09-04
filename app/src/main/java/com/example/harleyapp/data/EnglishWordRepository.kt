package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.LocalDocumentStore
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.EnglishWordLibraryMode
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.SchoolTerm
import com.example.harleyapp.model.chooseNextEnglishWord
import com.example.harleyapp.model.primaryEnglishWordsForSelection
import org.json.JSONObject

/**
 * 管理随App内置的英语词库和仅保存在本机的学习进度。
 *
 * 使用方法：
 * 使用Application Context创建实例，通过[getWords]读取当前完整列表；用户确认学会后调用
 * [markLearned]，需要重新复习一个词时调用[resetWord]。5000词主词库和小学分册映射均从assets
 * 读取，不访问网络；学习进度与年级选择写入Room文档存储，因此会进入App现有的本地导出和恢复流程。
 *
 * @param context Android上下文，内部只保留Application Context。
 */
class EnglishWordRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val documentStore = LocalDocumentStore.create(applicationContext)
    private val wordContents: List<BundledEnglishWord> by lazy {
        loadBundledEnglishWords(applicationContext)
    }
    private val primaryWordContents: List<BundledPrimaryEnglishWord> by lazy {
        loadBundledPrimaryEnglishWords(applicationContext)
    }

    /**
     * 读取内置内容并合并当前学习次数。
     *
     * @return 先保留5000词原始顺序，再追加主词库没有的教材短语；词库读取失败时返回空列表。
     */
    fun getWords(): List<EnglishWord> {
        val progress = loadProgress()
        val primaryContentsBySpelling = primaryWordContents.groupBy { content ->
            content.word.trim().lowercase()
        }
        val existingSpellings = wordContents.mapTo(mutableSetOf()) { content ->
            content.word.trim().lowercase()
        }
        val words = wordContents.map { content ->
            val normalizedSpelling = content.word.trim().lowercase()
            val primaryContents = primaryContentsBySpelling[normalizedSpelling].orEmpty()
            val primaryDisplayContent = primaryContents.firstOrNull()
            EnglishWord(
                id = content.id,
                word = content.word,
                phonetic = content.phonetic.ifBlank {
                    primaryDisplayContent?.phonetic.orEmpty()
                },
                definitionEn = content.definitionEn,
                meaningZh = primaryDisplayContent?.meaningZh ?: content.meaningZh,
                exampleEn = content.exampleEn,
                exampleZh = content.exampleZh,
                tags = if (primaryContents.isEmpty()) {
                    content.tags
                } else {
                    (content.tags + PRIMARY_TAG).distinct()
                },
                primaryPlacements = primaryContents.map { item -> item.placement },
                learnedCount = progress.optInt(content.id, 0)
                    .coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
            )
        }.toMutableList()

        // 教材中的短语、缩写和词形变化不一定进入5000词主词库，按拼写去重后补到列表末尾。
        primaryContentsBySpelling.forEach { (normalizedSpelling, primaryContents) ->
            if (normalizedSpelling in existingSpellings) return@forEach

            val displayContent = primaryContents.first()
            val wordId = "$PRIMARY_WORD_ID_PREFIX$normalizedSpelling"
            words += EnglishWord(
                id = wordId,
                word = displayContent.word,
                phonetic = displayContent.phonetic,
                meaningZh = displayContent.meaningZh,
                tags = listOf(PRIMARY_TAG),
                primaryPlacements = primaryContents.map { item -> item.placement },
                learnedCount = progress.optInt(wordId, 0)
                    .coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
            )
        }
        return words
    }

    /**
     * 读取用户上次选择的小学英语年级、册次和词库范围。
     *
     * 使用方法：
     * App启动时读取一次并保存为Compose状态。旧版本没有选择数据、JSON损坏或枚举名称无法识别时，
     * 安全回退到三年级上册的年级推荐，不影响既有单词学习次数。
     *
     * @return 当前有效选择，默认三年级上册年级推荐。
     */
    fun getLearningSelection(): PrimaryEnglishSelection {
        val storedValue = documentStore.getString(PROGRESS_NAMESPACE, SELECTION_KEY)
            ?: return PrimaryEnglishSelection()
        return runCatching {
            val root = JSONObject(storedValue)
            PrimaryEnglishSelection(
                grade = enumValueOrDefault(
                    storedName = root.optString(JSON_GRADE),
                    fallback = PrimarySchoolGrade.GRADE_THREE
                ),
                term = enumValueOrDefault(
                    storedName = root.optString(JSON_TERM),
                    fallback = SchoolTerm.FIRST
                ),
                mode = enumValueOrDefault(
                    storedName = root.optString(JSON_MODE),
                    fallback = EnglishWordLibraryMode.GRADE_RECOMMENDED
                )
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse English learning selection", error)
            PrimaryEnglishSelection()
        }
    }

    /**
     * 保存用户选择的小学英语年级、册次和词库范围。
     *
     * @param selection 页面已经确认的新选择。
     * @return Room文档保存成功返回true，否则返回false且页面应继续使用旧选择。
     */
    fun saveLearningSelection(selection: PrimaryEnglishSelection): Boolean {
        val root = JSONObject()
            .put(JSON_GRADE, selection.grade.name)
            .put(JSON_TERM, selection.term.name)
            .put(JSON_MODE, selection.mode.name)
        return documentStore.putString(PROGRESS_NAMESPACE, SELECTION_KEY, root.toString())
    }

    /**
     * 把指定单词的学习次数增加一次，最多增加到三次。
     *
     * 使用方法：
     * 首页卡片或学习列表中的“学会”按钮调用本函数。只有数据库成功保存后才返回true，上层应在
     * true时重新读取列表并切换下一词，避免界面进度领先于实际持久化数据。
     *
     * @param wordId 内置词库中的稳定单词标识。
     * @return 单词存在且进度保持或保存成功时返回true；标识无效或写入失败时返回false。
     */
    fun markLearned(wordId: String): Boolean {
        if (wordContents.none { content -> content.id == wordId }) return false

        val progress = loadProgress()
        val currentCount = progress.optInt(wordId, 0)
            .coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
        if (currentCount >= ENGLISH_WORD_MASTERY_COUNT) return true

        progress.put(wordId, currentCount + 1)
        return saveProgress(progress)
    }

    /**
     * 把一个已经学过的单词重新放回“未学会”分栏。
     *
     * @param wordId 内置词库中的稳定单词标识。
     * @return 单词存在且数据库成功保存时返回true，否则返回false。
     */
    fun resetWord(wordId: String): Boolean {
        if (wordContents.none { content -> content.id == wordId }) return false

        val progress = loadProgress()
        progress.remove(wordId)
        return saveProgress(progress)
    }

    /**
     * 从当前列表中选择首页要展示的下一单词。
     *
     * @param words 已通过[getWords]合并进度的列表。
     * @param previousWordId 上一张首页卡片的单词标识，用于尽量避免连续重复。
     * @param selection 用户当前年级和册次；首页始终从该分册推荐，不受“全部词库”列表模式影响。
     * @return 当前分册中尚未达到三次的随机单词；本册全部完成或资源为空时返回null。
     */
    fun chooseNextWord(
        words: List<EnglishWord>,
        previousWordId: String?,
        selection: PrimaryEnglishSelection
    ): EnglishWord? {
        return chooseNextEnglishWord(
            words = primaryEnglishWordsForSelection(words, selection),
            previousWordId = previousWordId
        )
    }

    /**
     * 把持久化的枚举名称恢复为当前代码支持的枚举值。
     *
     * @param storedName JSON中保存的枚举名称。
     * @param fallback 名称为空或旧版本值已经失效时使用的安全默认值。
     * @return 匹配的枚举值，无法匹配时返回[fallback]。
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        storedName: String,
        fallback: T
    ): T {
        return enumValues<T>().firstOrNull { item -> item.name == storedName } ?: fallback
    }

    /**
     * 读取学习进度JSON；空数据或损坏数据按尚未学习处理。
     *
     * @return 可继续修改和保存的JSON对象。
     */
    private fun loadProgress(): JSONObject {
        val storedValue = documentStore.getString(PROGRESS_NAMESPACE, PROGRESS_KEY)
            ?: return JSONObject()
        return runCatching {
            JSONObject(storedValue)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse English word progress", error)
            JSONObject()
        }
    }

    /**
     * 保存完整学习进度JSON。
     *
     * @param progress 已完成更新的进度对象。
     * @return Room文档写入成功返回true，否则返回false。
     */
    private fun saveProgress(progress: JSONObject): Boolean {
        return documentStore.putString(
            namespace = PROGRESS_NAMESPACE,
            key = PROGRESS_KEY,
            value = progress.toString()
        )
    }

    companion object {
        const val PROGRESS_NAMESPACE = "harley_english_words"

        private const val TAG = "EnglishWordRepository"
        private const val PROGRESS_KEY = "progress_json"
        private const val SELECTION_KEY = "primary_selection_json"
        private const val JSON_GRADE = "grade"
        private const val JSON_TERM = "term"
        private const val JSON_MODE = "mode"
        private const val PRIMARY_TAG = "pep-primary"
        private const val PRIMARY_WORD_ID_PREFIX = "pep-primary:"
    }
}
