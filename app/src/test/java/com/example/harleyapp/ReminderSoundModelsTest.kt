package com.example.harleyapp

import com.example.harleyapp.notification.AppReminderSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证HarleyApp内置提示音的持久化恢复和选项完整性。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行，不需要连接Android音频设备。
 */
class ReminderSoundModelsTest {

    /**
     * 验证每个提示音枚举名称均可从SharedPreferences字符串稳定恢复。
     *
     * @return 无返回值；任一选项无法恢复时由JUnit报告失败。
     */
    @Test
    fun everyReminderSoundRestoresFromStorageValue() {
        AppReminderSound.entries.forEach { sound ->
            assertEquals(sound, AppReminderSound.fromStorageValue(sound.name))
        }
    }

    /**
     * 验证空值和旧版未知值安全回退到默认App提示音。
     *
     * @return 无返回值；迁移结果不稳定时由JUnit报告失败。
     */
    @Test
    fun unknownReminderSoundFallsBackToDefault() {
        assertEquals(AppReminderSound.DEFAULT, AppReminderSound.fromStorageValue(null))
        assertEquals(
            AppReminderSound.DEFAULT,
            AppReminderSound.fromStorageValue("OLD_SYSTEM_RINGTONE")
        )
    }

    /**
     * 验证App至少提供五种有声选项以及一个仅振动选项。
     *
     * @return 无返回值；提示音选项被误删时由JUnit报告失败。
     */
    @Test
    fun reminderSoundCatalogContainsAudibleAndVibrationOptions() {
        val audibleCount = AppReminderSound.entries.count { sound ->
            sound != AppReminderSound.VIBRATION_ONLY
        }

        assertTrue(audibleCount >= 5)
        assertTrue(AppReminderSound.VIBRATION_ONLY.steps.isEmpty())
    }
}
