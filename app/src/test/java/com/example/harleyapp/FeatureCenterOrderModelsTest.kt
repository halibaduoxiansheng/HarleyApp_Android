package com.example.harleyapp

import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.moveFeatureCenterItem
import com.example.harleyapp.model.normalizeFeatureCenterOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证功能中心拖动排序使用的纯列表逻辑，不依赖Android设备或Compose触摸事件。
 *
 * 使用方法：
 * 在项目根目录执行 `gradlew testDebugUnitTest`，JUnit会自动运行本类全部测试。
 */
class FeatureCenterOrderModelsTest {

    /**
     * 验证旧版本保存顺序中的重复项和已下线文件空间会被清理，新功能会自动补在末尾。
     *
     * @return 无返回值；兼容顺序不正确时由JUnit报告失败。
     */
    @Test
    fun normalizeOrderKeepsKnownUniqueItemsAndAppendsMissingItems() {
        val result = normalizeFeatureCenterOrder(
            listOf("FITNESS", "STORAGE_MANAGER", "TODAY", "FITNESS")
        )

        assertEquals(HomeFeatureId.FITNESS, result[0])
        assertEquals(HomeFeatureId.TODAY, result[1])
        assertEquals(HomeFeatureId.entries.size, result.size)
        assertEquals(HomeFeatureId.entries.toSet(), result.toSet())
        assertEquals(HomeFeatureId.COOK, result.last())
    }

    /**
     * 验证把第一张卡片拖到后方时，目标位置和其他卡片相对顺序正确。
     *
     * @return 无返回值；移动结果不符合桌面式排序语义时由JUnit报告失败。
     */
    @Test
    fun moveItemReordersListWithoutDroppingFeatures() {
        val original = listOf(
            HomeFeatureId.TODAY,
            HomeFeatureId.SEARCH,
            HomeFeatureId.LEDGER,
            HomeFeatureId.FITNESS
        )

        val result = moveFeatureCenterItem(original, fromIndex = 0, toIndex = 2)

        assertEquals(
            listOf(
                HomeFeatureId.SEARCH,
                HomeFeatureId.LEDGER,
                HomeFeatureId.TODAY,
                HomeFeatureId.FITNESS
            ),
            result
        )
    }

    /**
     * 验证越界拖动不会破坏现有顺序。
     *
     * @return 无返回值；越界输入改变列表时由JUnit报告失败。
     */
    @Test
    fun invalidMoveKeepsOriginalOrder() {
        val original = HomeFeatureId.entries.toList()

        assertEquals(original, moveFeatureCenterItem(original, -1, 2))
        assertEquals(original, moveFeatureCenterItem(original, 2, original.size))
    }
}
