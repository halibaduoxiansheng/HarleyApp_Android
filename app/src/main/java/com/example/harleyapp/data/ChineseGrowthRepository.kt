package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.LocalDocumentStore
import com.example.harleyapp.model.ChineseGrowthProgress
import com.example.harleyapp.model.ChineseReadingArticle
import com.example.harleyapp.model.ChineseReadingLoadResult
import com.example.harleyapp.model.ChineseReadingTopic
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.calculateChineseGrowthStreakDays
import com.example.harleyapp.model.countRecentChineseGrowthEvents
import org.json.JSONObject
import java.time.LocalDate

/**
 * 保存语文成长年级、写作草稿和本机学习进度。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面启动时调用[getSelectedGrade]恢复年级，通过[getProgress]
 * 读取本机进度；阅读正文调用[loadArticle]直接获得内置原创内容，不依赖境外接口或网络缓存。
 *
 * @param context Android上下文，内部只保留Application Context对应的Room文档存储。
 */
class ChineseGrowthRepository(context: Context) {

    private val documentStore = LocalDocumentStore.create(context.applicationContext)

    /**
     * 读取用户上次选择的语文学习年级。
     *
     * @return 已保存且仍受支持的年级；首次使用或数据损坏时返回三年级。
     */
    fun getSelectedGrade(): PrimarySchoolGrade {
        val storedName = documentStore.getString(NAMESPACE, SELECTED_GRADE_KEY)
            ?: return PrimarySchoolGrade.GRADE_THREE
        return PrimarySchoolGrade.entries.firstOrNull { grade ->
            grade.name == storedName
        } ?: PrimarySchoolGrade.GRADE_THREE
    }

    /**
     * 保存语文写作、阅读和诗词共同使用的年级。
     *
     * @param grade 用户刚选择的有效年级。
     * @return Room文档保存成功返回true，否则返回false。
     */
    fun saveSelectedGrade(grade: PrimarySchoolGrade): Boolean {
        return documentStore.putString(NAMESPACE, SELECTED_GRADE_KEY, grade.name)
    }

    /**
     * 取得一篇无需网络的原创精读内容。
     *
     * 使用方法：
     * 页面打开内置主题时直接调用。正文来自随APK发布的固定目录，不请求境外接口；需要更多资料时，
     * 页面另行展示[ChineseReadingTopic.extensionSourceUrl]对应的国内站点，并由用户主动确认打开。
     *
     * @param topic [ChineseGrowthCatalog]提供的固定阅读主题。
     * @return 始终包含可展示内容且没有联网失败状态的加载结果。
     */
    fun loadArticle(topic: ChineseReadingTopic): ChineseReadingLoadResult {
        return ChineseReadingLoadResult(article = createOfflineArticle(topic))
    }

    /** 创建不依赖网络、明确标记为原创导读的文章。 */
    private fun createOfflineArticle(topic: ChineseReadingTopic): ChineseReadingArticle {
        return ChineseReadingArticle(
            topicId = topic.id,
            title = topic.title,
            description = "${topic.category} · 离线导读",
            body = topic.offlineGuide,
            sourceName = "HarleyApp原创导读",
            sourceUrl = "",
            licenseLabel = "仅用于本机学习",
            fetchedAtMillis = 0L,
            isOnlineContent = false,
            isFromCache = false
        )
    }

    /**
     * 读取语文成长的累计、本周和连续学习进度。
     *
     * 使用方法：
     * 页面首次进入以及完成任一内容后调用。连续天数允许“今天尚未学习但昨天仍连续”的自然状态，
     * 一旦最近学习日早于昨天就归零。本函数只读本机Room数据。
     *
     * @param currentEpochDay 当前自然日，默认使用设备本地日期；测试时可传固定值。
     * @return 可直接展示的进度摘要，数据损坏时返回全零状态。
     */
    fun getProgress(
        currentEpochDay: Long = LocalDate.now().toEpochDay()
    ): ChineseGrowthProgress {
        val completionDates = readCompletionDates()
        val studyEventDays = readStudyEventDays().ifEmpty { completionDates.values.toList() }
        return ChineseGrowthProgress(
            completedContentIds = completionDates.keys,
            totalCompletedCount = completionDates.size,
            weeklyCompletedCount = countRecentChineseGrowthEvents(
                studyEpochDays = studyEventDays,
                currentEpochDay = currentEpochDay
            ),
            streakDays = calculateChineseGrowthStreakDays(
                studyEpochDays = studyEventDays,
                currentEpochDay = currentEpochDay
            )
        )
    }

    /**
     * 把一项已完成内容记录到本机学习进度。
     *
     * 使用方法：
     * 仅在用户明确点击“完成”后调用；重复完成同一内容保持首次完成日期，不重复增加累计数量。
     * 未出现在当前内置目录中的标识会被拒绝，避免任意字符串污染进度文档。
     *
     * @param contentId 写作任务、阅读主题或诗词课程的稳定标识。
     * @param currentEpochDay 完成时的设备本地自然日，默认读取今天。
     * @return 内容已记录或此前已经完成时返回true；标识无效或Room写入失败时返回false。
     */
    fun markContentCompleted(
        contentId: String,
        currentEpochDay: Long = LocalDate.now().toEpochDay()
    ): Boolean {
        if (contentId !in knownContentIds()) return false

        val completionDates = readCompletionDates().toMutableMap()
        if (contentId !in completionDates) {
            completionDates[contentId] = currentEpochDay
            if (!saveCompletionDates(completionDates)) return false
        }
        return saveStudyEvent(contentId, currentEpochDay)
    }

    /**
     * 读取一项写作训练此前保存的草稿。
     *
     * @param missionId [ChineseGrowthCatalog]中的写作任务稳定标识。
     * @return 草稿正文；任务无效、未保存或读取失败时返回空字符串。
     */
    fun getWritingDraft(missionId: String): String {
        if (missionId !in knownWritingMissionIds()) return ""
        return documentStore.getString(NAMESPACE, "$WRITING_DRAFT_PREFIX$missionId").orEmpty()
    }

    /**
     * 保存一项写作训练的本机草稿。
     *
     * 使用方法：
     * 页面让用户主动点击保存后调用。空白草稿会删除旧文档；正文超过[WRITING_DRAFT_MAX_CHARS]
     * 时返回false，避免异常输入持续增大本机数据库。
     *
     * @param missionId [ChineseGrowthCatalog]中的写作任务稳定标识。
     * @param draft 用户当前输入的完整草稿。
     * @return 保存或删除成功返回true；任务无效、正文过长或Room操作失败时返回false。
     */
    fun saveWritingDraft(missionId: String, draft: String): Boolean {
        if (missionId !in knownWritingMissionIds()) return false
        if (draft.length > WRITING_DRAFT_MAX_CHARS) return false
        val key = "$WRITING_DRAFT_PREFIX$missionId"
        return if (draft.isBlank()) {
            documentStore.remove(NAMESPACE, key)
        } else {
            documentStore.putString(NAMESPACE, key, draft)
        }
    }

    /** 读取并严格校验内容标识到完成自然日的JSON文档。 */
    private fun readCompletionDates(): Map<String, Long> {
        val storedValue = documentStore.getString(NAMESPACE, COMPLETION_DATES_KEY)
            ?: return emptyMap()
        return runCatching {
            val root = JSONObject(storedValue)
            val knownIds = knownContentIds()
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val contentId = keys.next()
                    val epochDay = root.optLong(contentId, INVALID_EPOCH_DAY)
                    if (contentId in knownIds && epochDay >= 0L) {
                        put(contentId, epochDay)
                    }
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse Chinese growth completion dates", error)
            emptyMap()
        }
    }

    /** 把完整完成日期映射一次性写回Room文档。 */
    private fun saveCompletionDates(completionDates: Map<String, Long>): Boolean {
        val root = JSONObject()
        completionDates.toSortedMap().forEach { (contentId, epochDay) ->
            root.put(contentId, epochDay)
        }
        return documentStore.putString(NAMESPACE, COMPLETION_DATES_KEY, root.toString())
    }

    /** 读取并校验每个“内容加自然日”唯一学习事件的日期。 */
    private fun readStudyEventDays(): List<Long> {
        val storedValue = documentStore.getString(NAMESPACE, STUDY_EVENTS_KEY)
            ?: return emptyList()
        return runCatching {
            val root = JSONObject(storedValue)
            buildList {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val eventId = keys.next()
                    val epochDay = root.optLong(eventId, INVALID_EPOCH_DAY)
                    if (eventId.contains(STUDY_EVENT_SEPARATOR) && epochDay >= 0L) {
                        add(epochDay)
                    }
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to parse Chinese growth study events", error)
            emptyList()
        }
    }

    /** 保存一次按“同一内容同一天”去重的学习事件，使课程可在以后重复学习并计入周目标。 */
    private fun saveStudyEvent(contentId: String, currentEpochDay: Long): Boolean {
        val storedValue = documentStore.getString(NAMESPACE, STUDY_EVENTS_KEY)
        val root = runCatching { JSONObject(storedValue ?: "{}") }.getOrElse { error ->
            Log.e(TAG, "Failed to parse Chinese growth events before saving", error)
            JSONObject()
        }
        val eventId = "$contentId$STUDY_EVENT_SEPARATOR$currentEpochDay"
        if (root.has(eventId)) return true
        root.put(eventId, currentEpochDay)
        return documentStore.putString(NAMESPACE, STUDY_EVENTS_KEY, root.toString())
    }

    /** @return 当前版本全部允许写入进度的稳定内容标识。 */
    private fun knownContentIds(): Set<String> {
        return buildSet {
            ChineseGrowthCatalog.allWritingMissions().mapTo(this) { mission -> mission.id }
            ChineseGrowthCatalog.allReadingTopics().mapTo(this) { topic -> topic.id }
            ChineseGrowthCatalog.allClassicLessons().mapTo(this) { lesson -> lesson.id }
        }
    }

    /** @return 当前版本允许保存草稿的写作任务稳定标识。 */
    private fun knownWritingMissionIds(): Set<String> {
        return ChineseGrowthCatalog.allWritingMissions()
            .mapTo(mutableSetOf()) { mission -> mission.id }
    }

    companion object {
        const val NAMESPACE = "harley_chinese_growth"
        const val WRITING_DRAFT_MAX_CHARS = 20_000

        private const val TAG = "ChineseGrowthRepository"
        private const val SELECTED_GRADE_KEY = "selected_grade"
        private const val COMPLETION_DATES_KEY = "completion_dates"
        private const val STUDY_EVENTS_KEY = "study_events"
        private const val WRITING_DRAFT_PREFIX = "writing_draft:"
        private const val STUDY_EVENT_SEPARATOR = "@"
        private const val INVALID_EPOCH_DAY = -1L
    }
}
