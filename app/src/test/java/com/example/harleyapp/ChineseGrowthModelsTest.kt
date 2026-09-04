package com.example.harleyapp

import com.example.harleyapp.data.ChineseGrowthCatalog
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.calculateChineseGrowthStreakDays
import com.example.harleyapp.model.countRecentChineseGrowthEvents
import com.example.harleyapp.model.isChineseReadingTopicSuitable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证语文成长写作训练和精选阅读白名单的年级边界。
 *
 * 使用方法：
 * 在项目根目录运行`gradlew :app:testDebugUnitTest`。测试不联网，能够在生成APK前发现漏年级、
 * 重复标识、空白练习或适龄范围错误。
 */
class ChineseGrowthModelsTest {

    /** 每个年级必须恰好提供三项结构完整的写作训练。 */
    @Test
    fun writingCatalogCoversEveryGrade() {
        val allMissions = ChineseGrowthCatalog.allWritingMissions()
        assertEquals(allMissions.size, allMissions.map { mission -> mission.id }.toSet().size)

        PrimarySchoolGrade.entries.forEach { grade ->
            val missions = ChineseGrowthCatalog.writingMissionsFor(grade)
            assertEquals(3, missions.size)
            missions.forEach { mission ->
                assertTrue(mission.title.isNotBlank())
                assertTrue(mission.focus.isNotBlank())
                assertTrue(mission.prompt.isNotBlank())
                assertTrue(mission.methodSteps.size >= 3)
                assertTrue(mission.outline.size >= 3)
                assertTrue(mission.checklist.size >= 3)
            }
        }
    }

    /** 阅读主题必须使用唯一标识、合法年级范围和固定国内HTTPS延伸地址。 */
    @Test
    fun readingTopicsHaveSafeGradeRanges() {
        val topics = ChineseGrowthCatalog.allReadingTopics()
        assertEquals(topics.size, topics.map { topic -> topic.id }.toSet().size)
        topics.forEach { topic ->
            assertTrue(topic.minGrade in 1..6)
            assertTrue(topic.maxGrade in topic.minGrade..6)
            assertTrue(topic.offlineGuide.length >= 40)
            assertTrue(topic.observationQuestion.isNotBlank())
            assertTrue(topic.writingChallenge.isNotBlank())
            assertEquals("百度百科", topic.extensionSourceName)
            assertTrue(topic.extensionSourceUrl.startsWith("https://baike.baidu.com/item/"))
        }
    }

    /** 每个年级必须提供两篇内容完整、稳定标识唯一的离线诗词课程。 */
    @Test
    fun classicCatalogCoversEveryGrade() {
        val allLessons = ChineseGrowthCatalog.allClassicLessons()
        assertEquals(allLessons.size, allLessons.map { lesson -> lesson.id }.toSet().size)

        PrimarySchoolGrade.entries.forEach { grade ->
            val lessons = ChineseGrowthCatalog.classicLessonsFor(grade)
            assertEquals(2, lessons.size)
            lessons.forEach { lesson ->
                assertEquals(grade, lesson.grade)
                assertTrue(lesson.title.isNotBlank())
                assertTrue(lesson.author.isNotBlank())
                assertTrue(lesson.text.lines().size >= 4)
                assertTrue(lesson.appreciation.length >= 30)
                assertTrue(lesson.recitationTip.isNotBlank())
                assertTrue(lesson.practiceQuestion.endsWith("？"))
            }
        }
    }

    /** 适龄筛选只能返回年级处于主题闭区间内的内容。 */
    @Test
    fun readingSuitabilityUsesInclusiveGradeRange() {
        val topic = ChineseGrowthCatalog.allReadingTopics().first { item ->
            item.id == "chinese_reading_solar_system"
        }

        assertFalse(isChineseReadingTopicSuitable(topic, PrimarySchoolGrade.GRADE_TWO))
        assertTrue(isChineseReadingTopicSuitable(topic, PrimarySchoolGrade.GRADE_THREE))
        assertTrue(isChineseReadingTopicSuitable(topic, PrimarySchoolGrade.GRADE_FIVE))
        assertFalse(isChineseReadingTopicSuitable(topic, PrimarySchoolGrade.GRADE_SIX))
    }

    /** 最近七天按真实学习事件计数，使同一课程隔天复习仍能推进周目标。 */
    @Test
    fun recentProgressCountsRepeatedLearningOnDifferentDays() {
        val today = 1_000L

        assertEquals(
            4,
            countRecentChineseGrowthEvents(
                studyEpochDays = listOf(today, today, today - 1L, today - 6L, today - 7L, today + 1L),
                currentEpochDay = today
            )
        )
    }

    /** 连续学习按自然日去重，并允许今天尚未学习时延续到昨天。 */
    @Test
    fun streakUsesDistinctCalendarDaysAndAllowsYesterday() {
        val today = 2_000L

        assertEquals(
            3,
            calculateChineseGrowthStreakDays(
                studyEpochDays = listOf(today - 1L, today - 1L, today - 2L, today - 3L, today - 5L),
                currentEpochDay = today
            )
        )
        assertEquals(
            0,
            calculateChineseGrowthStreakDays(
                studyEpochDays = listOf(today - 2L, today - 3L),
                currentEpochDay = today
            )
        )
    }
}
