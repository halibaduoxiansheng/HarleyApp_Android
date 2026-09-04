package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.PrimaryEnglishPlacement
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.SchoolTerm
import org.json.JSONObject

/** 随APK发布的小学英语年级分册资源文件名。 */
internal const val PRIMARY_ENGLISH_WORD_ASSET_FILE = "primary_english_words.json"

/**
 * 一个小学英语分册词条的精简静态内容。
 *
 * @param word 英文单词或教材常用短语。
 * @param meaningZh 面向小学生的简明中文义项。
 * @param phonetic 可选音标；空白时可继续使用5000词主词库音标或Android TTS。
 * @param placement 年级、册次和分册内顺序。
 */
internal data class BundledPrimaryEnglishWord(
    val word: String,
    val meaningZh: String,
    val phonetic: String,
    val placement: PrimaryEnglishPlacement
)

/**
 * 读取随APK发布的一至六年级、上下册英语推荐词。
 *
 * 使用方法：
 * 仅由[EnglishWordRepository]首次组装完整词库时调用。函数校验年级、册次、单词和释义，
 * 同一个分册内重复拼写只保留第一次；异常项目记录英文日志并跳过，不影响5000词主词库使用。
 *
 * @param context Android上下文，用于打开assets资源。
 * @return 按资源中的年级、册次和推荐顺序排列的词条；文件损坏或无法读取时返回空列表。
 */
internal fun loadBundledPrimaryEnglishWords(context: Context): List<BundledPrimaryEnglishWord> {
    return runCatching {
        val root = context.assets.open(PRIMARY_ENGLISH_WORD_ASSET_FILE).bufferedReader().use { reader ->
            JSONObject(reader.readText())
        }
        val books = root.getJSONArray(JSON_BOOKS)
        val result = mutableListOf<BundledPrimaryEnglishWord>()

        for (bookIndex in 0 until books.length()) {
            val book = books.optJSONObject(bookIndex) ?: continue
            val gradeNumber = book.optInt(JSON_GRADE, 0)
            val termNumber = book.optInt(JSON_TERM, 0)
            val grade = PrimarySchoolGrade.entries.firstOrNull { item ->
                item.gradeNumber == gradeNumber
            }
            val term = SchoolTerm.entries.firstOrNull { item ->
                item.termNumber == termNumber
            }
            if (grade == null || term == null) {
                Log.w(TAG, "Skipping invalid primary English book at index $bookIndex")
                continue
            }

            val isOfficialPepSeries = book.optBoolean(JSON_IS_OFFICIAL_PEP_SERIES, false)
            val words = book.optJSONArray(JSON_WORDS) ?: continue
            val seenSpellings = mutableSetOf<String>()
            for (wordIndex in 0 until words.length()) {
                val item = words.optJSONObject(wordIndex) ?: continue
                val spelling = item.optString(JSON_WORD).trim()
                val meaningZh = item.optString(JSON_MEANING_ZH).trim()
                val normalizedSpelling = spelling.lowercase()
                if (spelling.isBlank() || meaningZh.isBlank() || !seenSpellings.add(normalizedSpelling)) {
                    Log.w(TAG, "Skipping invalid primary English word at $gradeNumber-$termNumber-$wordIndex")
                    continue
                }
                result += BundledPrimaryEnglishWord(
                    word = spelling,
                    meaningZh = meaningZh,
                    phonetic = item.optString(JSON_PHONETIC).trim(),
                    placement = PrimaryEnglishPlacement(
                        grade = grade,
                        term = term,
                        orderInBook = wordIndex,
                        isOfficialPepSeries = isOfficialPepSeries
                    )
                )
            }
        }
        result
    }.getOrElse { error ->
        Log.e(TAG, "Failed to load primary English word asset", error)
        emptyList()
    }
}

private const val TAG = "BundledPrimaryEnglishWords"
private const val JSON_BOOKS = "books"
private const val JSON_GRADE = "grade"
private const val JSON_TERM = "term"
private const val JSON_IS_OFFICIAL_PEP_SERIES = "isOfficialPepSeries"
private const val JSON_WORDS = "words"
private const val JSON_WORD = "word"
private const val JSON_MEANING_ZH = "meaningZh"
private const val JSON_PHONETIC = "phonetic"
