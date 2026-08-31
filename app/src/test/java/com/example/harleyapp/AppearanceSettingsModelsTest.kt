package com.example.harleyapp

import com.example.harleyapp.data.DEFAULT_STARTUP_ANIMATION_ENABLED
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证不依赖Android存储的外观设置默认值。 */
class AppearanceSettingsModelsTest {

    /**
     * 验证首次安装和旧版本升级后默认保留启动动画。
     *
     * @return 无返回值；默认值被意外关闭时由JUnit报告失败。
     */
    @Test
    fun startupAnimationIsEnabledByDefault() {
        assertTrue(DEFAULT_STARTUP_ANIMATION_ENABLED)
    }
}
