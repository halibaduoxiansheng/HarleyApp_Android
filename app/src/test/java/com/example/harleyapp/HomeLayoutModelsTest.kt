package com.example.harleyapp

import com.example.harleyapp.model.DEFAULT_HOME_SECTION_ORDER
import com.example.harleyapp.model.HomeLayoutPreferences
import com.example.harleyapp.model.HomeSectionId
import com.example.harleyapp.model.decodeHiddenHomeSections
import com.example.harleyapp.model.moveHomeSection
import com.example.harleyapp.model.normalizeHomeLayoutPreferences
import com.example.harleyapp.model.normalizeHomeSectionOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证首页模块整理功能的顺序兼容、显隐恢复和移动边界。 */
class HomeLayoutModelsTest {

    /**
     * 验证读取旧配置时保留已知顺序，同时忽略未知项和重复项，并把新增模块补到末尾。
     *
     * @return 无返回值；规范化结果不完整或顺序错误时由JUnit报告测试失败。
     */
    @Test
    fun normalizeOrderKeepsKnownOrderAndAppendsMissingSections() {
        val normalized = normalizeHomeSectionOrder(
            listOf(
                HomeSectionId.COMPANION.name,
                "REMOVED_SECTION",
                HomeSectionId.WEBSITES.name,
                HomeSectionId.COMPANION.name
            )
        )

        assertEquals(HomeSectionId.COMPANION, normalized[0])
        assertEquals(HomeSectionId.WEBSITES, normalized[1])
        assertEquals(HomeSectionId.entries.size, normalized.size)
        assertEquals(HomeSectionId.entries.toSet(), normalized.toSet())
    }

    /**
     * 验证隐藏集合只恢复当前版本存在的模块，已下线名称不会污染布局。
     *
     * @return 无返回值；未知项未被忽略或有效项丢失时由JUnit报告测试失败。
     */
    @Test
    fun decodeHiddenSectionsIgnoresUnknownNames() {
        val decoded = decodeHiddenHomeSections(
            setOf(HomeSectionId.ENGLISH_WORD.name, "REMOVED_SECTION")
        )

        assertEquals(setOf(HomeSectionId.ENGLISH_WORD), decoded)
    }

    /**
     * 验证保存前会补齐调用方遗漏的模块，并保持用户明确隐藏的有效模块。
     *
     * @return 无返回值；布局未恢复为当前完整目录时由JUnit报告测试失败。
     */
    @Test
    fun normalizeLayoutRepairsIncompleteOrderAndKeepsVisibility() {
        val normalized = normalizeHomeLayoutPreferences(
            HomeLayoutPreferences(
                sectionOrder = listOf(
                    HomeSectionId.SHORTCUT_APPS,
                    HomeSectionId.WEBSITES,
                    HomeSectionId.SHORTCUT_APPS
                ),
                hiddenSections = setOf(HomeSectionId.REALTIME_STATUS)
            )
        )

        assertEquals(HomeSectionId.SHORTCUT_APPS, normalized.sectionOrder.first())
        assertEquals(HomeSectionId.entries.size, normalized.sectionOrder.size)
        assertFalse(normalized.isVisible(HomeSectionId.REALTIME_STATUS))
        assertTrue(normalized.isVisible(HomeSectionId.WEBSITES))
    }

    /**
     * 验证上移、下移和越界操作均保持可预测结果，避免弹窗快速点击破坏模块目录。
     *
     * @return 无返回值；任一移动结果与预期不符时由JUnit报告测试失败。
     */
    @Test
    fun moveSectionHandlesValidAndInvalidIndexes() {
        val movedUp = moveHomeSection(
            order = DEFAULT_HOME_SECTION_ORDER,
            fromIndex = 1,
            toIndex = 0
        )
        val movedDown = moveHomeSection(
            order = movedUp,
            fromIndex = 0,
            toIndex = 1
        )

        assertEquals(DEFAULT_HOME_SECTION_ORDER[1], movedUp[0])
        assertEquals(DEFAULT_HOME_SECTION_ORDER, movedDown)
        assertEquals(
            DEFAULT_HOME_SECTION_ORDER,
            moveHomeSection(DEFAULT_HOME_SECTION_ORDER, fromIndex = -1, toIndex = 0)
        )
    }
}
