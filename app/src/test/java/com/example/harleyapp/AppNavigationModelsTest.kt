package com.example.harleyapp

import com.example.harleyapp.ui.HOME_EXIT_CONFIRM_WINDOW_MILLIS
import com.example.harleyapp.ui.isHomeExitConfirmed
import com.example.harleyapp.ui.resolveAppBackDestinationName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证应用最外层返回路径和首页双击退出规则，不依赖真实Activity或手势设备。
 *
 * 使用方法：
 * 在项目根目录运行gradlew testDebugUnitTest，由JUnit自动执行本测试类。
 */
class AppNavigationModelsTest {

    /**
     * 验证四个底部一级页面的返回目标最终统一为首页。
     *
     * @return 无返回值；任一一级页面没有返回首页时由JUnit报告失败。
     */
    @Test
    fun topLevelSectionsReturnHome() {
        listOf("FEATURES", "WEBSITE", "PROFILE").forEach { sectionName ->
            assertEquals(
                "HOME",
                resolveAppBackDestinationName(sectionName, "FEATURES")
            )
        }
    }

    /**
     * 验证详情页先返回有效来源，来源指向自己或损坏时安全回到首页。
     *
     * @return 无返回值；详情返回链可能循环或丢失来源时由JUnit报告失败。
     */
    @Test
    fun detailSectionsReturnSourceWithoutLooping() {
        assertEquals("FEATURES", resolveAppBackDestinationName("LEDGER", "FEATURES"))
        assertEquals("SEARCH", resolveAppBackDestinationName("FITNESS", "SEARCH"))
        assertEquals("HOME", resolveAppBackDestinationName("SEARCH", "SEARCH"))
        assertEquals("HOME", resolveAppBackDestinationName("BACKUP", "BROKEN"))
    }

    /**
     * 验证首页第一次返回不退出、确认窗口内第二次退出，超时后重新提示。
     *
     * @return 无返回值；双击退出时间边界不正确时由JUnit报告失败。
     */
    @Test
    fun homeRequiresSecondBackInsideConfirmationWindow() {
        assertFalse(isHomeExitConfirmed(0L, 10_000L))
        assertTrue(isHomeExitConfirmed(10_000L, 11_000L))
        assertTrue(
            isHomeExitConfirmed(
                previousBackAtMillis = 10_000L,
                currentBackAtMillis = 10_000L + HOME_EXIT_CONFIRM_WINDOW_MILLIS
            )
        )
        assertFalse(
            isHomeExitConfirmed(
                previousBackAtMillis = 10_000L,
                currentBackAtMillis = 10_001L + HOME_EXIT_CONFIRM_WINDOW_MILLIS
            )
        )
    }
}
