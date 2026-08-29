package com.example.harleyapp

import com.example.harleyapp.model.compareNumericVersions
import com.example.harleyapp.model.isRemoteVersionNewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证OTA版本比较严格按数字段判断，不依赖Android系统或真实更新服务器。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class AppUpdateModelsTest {

    /**
     * 验证多位数字按数值含义比较，避免字符串比较把1.10错误判断为低于1.9。
     *
     * @return 无返回值；数字段比较错误时由JUnit报告失败。
     */
    @Test
    fun multiDigitSegmentsUseNumericOrdering() {
        assertTrue(isRemoteVersionNewer(currentVersion = "1.9", latestVersion = "1.10"))
        assertTrue(isRemoteVersionNewer(currentVersion = "1.99", latestVersion = "2.0"))
        assertFalse(isRemoteVersionNewer(currentVersion = "2.0", latestVersion = "1.99"))
    }

    /**
     * 验证缺失的末尾数字按0处理，并允许常见的v前缀。
     *
     * @return 无返回值；等价版本或v前缀处理错误时由JUnit报告失败。
     */
    @Test
    fun trailingZeroSegmentsAndVersionPrefixAreSupported() {
        assertEquals(0, compareNumericVersions("1.0", "1.0.0"))
        assertEquals(0, compareNumericVersions("v2.3", "2.3.0"))
        assertTrue(isRemoteVersionNewer(currentVersion = "v2.3", latestVersion = "2.3.1"))
    }

    /**
     * 验证相同版本和更低版本都不会触发OTA下载确认。
     *
     * @return 无返回值；非升级版本被错误接受时由JUnit报告失败。
     */
    @Test
    fun equalOrOlderRemoteVersionDoesNotTriggerUpdate() {
        assertFalse(isRemoteVersionNewer(currentVersion = "1.0", latestVersion = "1.0"))
        assertFalse(isRemoteVersionNewer(currentVersion = "1.0.1", latestVersion = "1.0"))
    }

    /**
     * 验证含字母后缀、空段或空字符串的版本会被拒绝，避免不确定顺序触发错误升级。
     *
     * @return 无返回值；无效版本被接受时由JUnit报告失败。
     */
    @Test
    fun malformedVersionsAreRejected() {
        assertNull(compareNumericVersions("1.0-beta", "1.0"))
        assertNull(compareNumericVersions("1..2", "1.0"))
        assertNull(compareNumericVersions("", "1.0"))
        assertFalse(isRemoteVersionNewer(currentVersion = "1.0", latestVersion = "latest"))
    }

    /**
     * 验证超出Long范围的数字段仍可安全比较，不因整数溢出导致崩溃或顺序反转。
     *
     * @return 无返回值；超长数字比较错误时由JUnit报告失败。
     */
    @Test
    fun veryLargeVersionSegmentsDoNotOverflow() {
        assertTrue(
            isRemoteVersionNewer(
                currentVersion = "1.99999999999999999999",
                latestVersion = "1.100000000000000000000"
            )
        )
    }
}
