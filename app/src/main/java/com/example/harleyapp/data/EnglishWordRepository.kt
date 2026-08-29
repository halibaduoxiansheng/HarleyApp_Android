package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.LocalDocumentStore
import com.example.harleyapp.model.ENGLISH_WORD_MASTERY_COUNT
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.chooseNextEnglishWord
import org.json.JSONObject

/**
 * 管理随App内置的英语词库和仅保存在本机的学习进度。
 *
 * 使用方法：
 * 使用Application Context创建实例，通过[getWords]读取当前完整列表；用户确认学会后调用
 * [markLearned]，需要重新复习一个词时调用[resetWord]。静态词库直接编译进APK，不访问网络；
 * 进度写入Room文档存储，因此会进入App现有的本地导出和恢复流程。
 *
 * @param context Android上下文，内部只保留Application Context。
 */
class EnglishWordRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val documentStore = LocalDocumentStore.create(applicationContext)
    private val wordContents: List<BundledEnglishWord> by lazy(::loadBundledEnglishWords)

    /**
     * 读取内置内容并合并当前学习次数。
     *
     * @return 按词库原始顺序排列的完整单词列表；词库读取失败时返回空列表。
     */
    fun getWords(): List<EnglishWord> {
        val progress = loadProgress()
        return wordContents.map { content ->
            EnglishWord(
                id = content.id,
                word = content.word,
                meaningZh = content.meaningZh,
                exampleEn = content.exampleEn,
                exampleZh = content.exampleZh,
                learnedCount = progress.optInt(content.id, 0)
                    .coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
            )
        }
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
     * @return 尚未达到三次的随机单词；全部完成时返回null。
     */
    fun chooseNextWord(words: List<EnglishWord>, previousWordId: String?): EnglishWord? {
        return chooseNextEnglishWord(words, previousWordId)
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
    }
}
