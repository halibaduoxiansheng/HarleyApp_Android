package com.example.harleyapp

import com.example.harleyapp.model.AppVisualTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证角色风格主题从本地枚举名称恢复时的兼容行为。
 */
class AppearanceModelsTest {

    /**
     * 验证当前版本支持的名称可以恢复为原主题。
     *
     * @return 无返回值；恢复错误时由JUnit报告失败。
     */
    @Test
    fun supportedThemeNameRestoresTheme() {
        assertEquals(
            AppVisualTheme.SAKURA_GIRL,
            AppVisualTheme.fromStoredName("SAKURA_GIRL")
        )
    }

    /**
     * 验证旧版本缺失值或未来未知名称会安全回退到经典蓝。
     *
     * @return 无返回值；回退结果不稳定时由JUnit报告失败。
     */
    @Test
    fun missingOrUnknownThemeFallsBackToClassic() {
        assertEquals(
            AppVisualTheme.CLASSIC_BLUE,
            AppVisualTheme.fromStoredName(null)
        )
        assertEquals(
            AppVisualTheme.CLASSIC_BLUE,
            AppVisualTheme.fromStoredName("REMOVED_THEME")
        )
    }
}
