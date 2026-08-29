package com.example.harleyapp

import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.nextWebsiteCarouselPage
import com.example.harleyapp.model.resolveDefaultWebsiteId
import com.example.harleyapp.model.WebsiteShortcut
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
     * 验证明文HTTP会保留协议和路径，可用于只提供HTTP的站点。
     *
     * @return 无返回值；HTTP地址未被正确保留时由JUnit报告失败。
     */
    @Test
    fun explicitHttpIsAccepted() {
        assertEquals(
            "http://example.com/news",
            normalizeWebsiteUrl("http://example.com/news")
        )
    }

    /**
     * 验证非网页协议、缺少主机名和包含空格的网址会被拒绝。
     *
     * @return 无返回值；任一无效输入被接受时由JUnit报告失败。
     */
    @Test
    fun unsupportedOrMalformedUrlsAreRejected() {
        assertNull(normalizeWebsiteUrl("ftp://example.com"))
        assertNull(normalizeWebsiteUrl("https:///missing-host"))
        assertNull(normalizeWebsiteUrl("https://example.com/a path"))
    }

    /**
     * 验证已保存的默认网站仍存在时继续使用该网站。
     *
     * @return 无返回值；默认网站被错误替换时由JUnit报告失败。
     */
    @Test
    fun existingDefaultWebsiteIsPreserved() {
        val websites = listOf(
            WebsiteShortcut("first", "第一个", "https://first.example.com"),
            WebsiteShortcut("preferred", "默认项", "https://preferred.example.com")
        )

        assertEquals(
            "preferred",
            resolveDefaultWebsiteId(websites, "preferred")
        )
    }

    /**
     * 验证默认网站被删除后回退到第一项，空列表返回null。
     *
     * @return 无返回值；回退规则不符时由JUnit报告失败。
     */
    @Test
    fun missingDefaultWebsiteFallsBackToFirstItem() {
        val websites = listOf(
            WebsiteShortcut("first", "第一个", "https://first.example.com"),
            WebsiteShortcut("second", "第二个", "https://second.example.com")
        )

        assertEquals("first", resolveDefaultWebsiteId(websites, "deleted"))
        assertNull(resolveDefaultWebsiteId(emptyList(), "deleted"))
    }

    /**
     * 验证自动轮播按顺序前进，并在最后一页回到第一页。
     *
     * @return 无返回值；下一页计算错误时由JUnit报告失败。
     */
    @Test
    fun carouselMovesForwardAndWrapsToFirstPage() {
        assertEquals(1, nextWebsiteCarouselPage(currentPage = 0, pageCount = 3))
        assertEquals(2, nextWebsiteCarouselPage(currentPage = 1, pageCount = 3))
        assertEquals(0, nextWebsiteCarouselPage(currentPage = 2, pageCount = 3))
        assertNull(nextWebsiteCarouselPage(currentPage = 0, pageCount = 1))
    }
}
