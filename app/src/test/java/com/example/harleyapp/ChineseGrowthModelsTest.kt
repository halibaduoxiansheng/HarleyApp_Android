package com.example.harleyapp

import com.example.harleyapp.data.ChineseGrowthCatalog
import com.example.harleyapp.model.PrimarySchoolGrade
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

    /** 阅读主题必须使用唯一标识、合法年级范围和固定百科标题。 */
    @Test
    fun readingTopicsHaveSafeGradeRanges() {
        val topics = ChineseGrowthCatalog.allReadingTopics()
        assertEquals(topics.size, topics.map { topic -> topic.id }.toSet().size)
        topics.forEach { topic ->
            assertTrue(topic.minGrade in 1..6)
            assertTrue(topic.maxGrade in topic.minGrade..6)
            assertTrue(topic.wikipediaTitle.isNotBlank())
            assertTrue(topic.offlineGuide.length >= 40)
            assertTrue(topic.observationQuestion.isNotBlank())
            assertTrue(topic.writingChallenge.isNotBlank())
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
}
