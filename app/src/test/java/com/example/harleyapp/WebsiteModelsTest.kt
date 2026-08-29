package com.example.harleyapp

import com.example.harleyapp.model.normalizeWebsiteUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证自定义网站输入的纯网址规范化逻辑，不依赖Android设备或WebView。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class WebsiteModelsTest {

    /**
     * 验证省略协议时自动补充HTTPS，并保留用户填写的路径。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun missingSchemeIsNormalizedToHttps() {
        assertEquals(
            "https://example.com/news",
            normalizeWebsiteUrl(" example.com/news ")
        )
    }

    /**
     * 验证明文HTTP、缺少主机名和包含空格的网址会被拒绝。
     *
     * @return 无返回值；任一无效输入被接受时由JUnit报告失败。
     */
    @Test
    fun unsafeOrMalformedUrlsAreRejected() {
        assertNull(normalizeWebsiteUrl("http://example.com"))
        assertNull(normalizeWebsiteUrl("https:///missing-host"))
        assertNull(normalizeWebsiteUrl("https://example.com/a path"))
    }
}
