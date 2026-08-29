package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** 随APK发布的英语词库文件名。 */
internal const val ENGLISH_WORD_ASSET_FILE = "english_words.json"

/** 当前离线词库约定的完整单词数量。 */
internal const val ENGLISH_WORD_ASSET_COUNT = 5_000

/**
 * 随App安装的单词静态内容，不包含任何用户进度。
 *
 * @param id 跨版本保持稳定的进度标识。
 * @param word 英文单词。
 * @param phonetic 英语音标，没有数据时为空字符串。
 * @param definitionEn 英文释义，没有数据时为空字符串。
 * @param meaningZh 中文释义。
 * @param exampleEn 可选的简短英文例句。
 * @param exampleZh 可选的例句中文翻译。
 * @param tags 中考、高考、四六级、牛津核心等分类标签。
 */
internal data class BundledEnglishWord(
    val id: String,
    val word: String,
    val phonetic: String,
    val definitionEn: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    val tags: List<String>
)

/**
 * 从assets目录读取完全离线的5000词JSON词库。
 *
 * 使用方法：
 * 仅由[EnglishWordRepository]首次读取单词时调用。函数会解析`assets/english_words.json`中的
 * `words`数组，跳过缺少稳定ID、英文拼写或中文释义的异常项目，并对重复ID只保留第一项。
 * 所有内容都随APK安装，不访问网络，也不会覆盖用户保存在Room中的学习进度。
 *
 * @param context Android上下文，用于打开APK的assets资源。
 * @return 按词库推荐顺序排列的离线单词；文件读取或JSON解析失败时返回空列表。
 */
internal fun loadBundledEnglishWords(context: Context): List<BundledEnglishWord> {
    return runCatching {
        val root = context.assets.open(ENGLISH_WORD_ASSET_FILE).bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }
        val wordArray = root.getJSONArray(JSON_WORDS_KEY)
        val seenIds = mutableSetOf<String>()
        val words = ArrayList<BundledEnglishWord>(wordArray.length())

        for (index in 0 until wordArray.length()) {
            val item = wordArray.optJSONObject(index) ?: continue
            val id = item.optString(JSON_ID_KEY).trim().lowercase()
            val spelling = item.optString(JSON_WORD_KEY).trim()
            val meaningZh = item.optString(JSON_MEANING_ZH_KEY).trim()
            if (id.isBlank() || spelling.isBlank() || meaningZh.isBlank()) {
                Log.w(TAG, "Skipping incomplete English word at index $index")
                continue
            }
            if (!seenIds.add(id)) {
                Log.w(TAG, "Skipping duplicate English word id: $id")
                continue
            }

            words += BundledEnglishWord(
                id = id,
                word = spelling,
                phonetic = item.optString(JSON_PHONETIC_KEY).trim(),
                definitionEn = item.optString(JSON_DEFINITION_EN_KEY).trim(),
                meaningZh = meaningZh,
                exampleEn = item.optString(JSON_EXAMPLE_EN_KEY).trim(),
                exampleZh = item.optString(JSON_EXAMPLE_ZH_KEY).trim(),
                tags = item.readStringList(JSON_TAGS_KEY)
            )
        }

        if (words.size != ENGLISH_WORD_ASSET_COUNT) {
            Log.w(TAG, "English word asset count is ${words.size}, expected $ENGLISH_WORD_ASSET_COUNT")
        }
        words
    }.getOrElse { error ->
        Log.e(TAG, "Failed to load English word asset", error)
        emptyList()
    }
}

/**
 * 读取JSON对象中的字符串数组，并自动忽略空白内容。
 *
 * @param key JSON数组字段名。
 * @return 保持原始顺序且不包含空白项的字符串列表；字段不存在时返回空列表。
 */
private fun JSONObject.readStringList(key: String): List<String> {
    val values = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until values.length()) {
            val value = values.optString(index).trim()
            if (value.isNotBlank()) add(value)
        }
    }
}

private const val TAG = "BundledEnglishWords"
private const val JSON_WORDS_KEY = "words"
private const val JSON_ID_KEY = "id"
private const val JSON_WORD_KEY = "word"
private const val JSON_PHONETIC_KEY = "phonetic"
private const val JSON_DEFINITION_EN_KEY = "definitionEn"
private const val JSON_MEANING_ZH_KEY = "meaningZh"
private const val JSON_EXAMPLE_EN_KEY = "exampleEn"
private const val JSON_EXAMPLE_ZH_KEY = "exampleZh"
private const val JSON_TAGS_KEY = "tags"
