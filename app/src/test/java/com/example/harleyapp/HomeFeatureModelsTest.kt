package com.example.harleyapp

import com.example.harleyapp.model.DEFAULT_HOME_FEATURE_IDS
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.decodeHomeFeatureIds
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证首页功能选择的默认值和持久化名称兼容行为。 */
class HomeFeatureModelsTest {

    /**
     * 验证旧版本升级且尚未保存设置时仍保留原首页三个功能。
     *
     * @return 无返回值；断言失败时由JUnit报告测试失败。
     */
    @Test
    fun defaultSelectionKeepsExistingHomeFeatures() {
        assertEquals(
            linkedSetOf(
                HomeFeatureId.TODAY,
                HomeFeatureId.HOT_TOPICS,
                HomeFeatureId.MOBILE_DATA
            ),
            DEFAULT_HOME_FEATURE_IDS
        )
    }

    /**
     * 验证损坏或未来版本产生的未知名称不会影响当前版本读取有效功能。
     *
     * @return 无返回值；断言失败时由JUnit报告测试失败。
     */
    @Test
    fun decodeSelectionIgnoresUnknownNamesAndUsesCatalogOrder() {
        val decoded = decodeHomeFeatureIds(
            setOf(
                HomeFeatureId.MOBILE_DATA.name,
                "FUTURE_FEATURE",
                HomeFeatureId.TODAY.name
            )
        )

        assertEquals(
            linkedSetOf(HomeFeatureId.TODAY, HomeFeatureId.MOBILE_DATA),
            decoded
        )
    }

    /**
     * 验证用户明确取消全部功能后仍保持空集合，不会被解码函数恢复默认值。
     *
     * @return 无返回值；断言失败时由JUnit报告测试失败。
     */
    @Test
    fun decodeSelectionKeepsExplicitEmptySelection() {
        assertEquals(emptySet<HomeFeatureId>(), decodeHomeFeatureIds(emptySet()))
    }
}
