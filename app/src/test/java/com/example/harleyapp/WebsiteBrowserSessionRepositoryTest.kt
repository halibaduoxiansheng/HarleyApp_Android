package com.example.harleyapp

import com.example.harleyapp.data.decodeWebsiteBrowserSession
import com.example.harleyapp.data.encodeWebsiteBrowserSession
import com.example.harleyapp.model.MAX_WEBSITE_BROWSER_TAB_COUNT
import com.example.harleyapp.model.WEBSITE_BROWSER_NEW_TAB_TITLE
import com.example.harleyapp.model.WebsiteBrowserSession
import com.example.harleyapp.model.WebsiteBrowserTab
import com.example.harleyapp.model.WebsiteShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证网站浏览会话JSON的纯编解码、损坏数据过滤与冷启动回退行为。
 *
 * 使用方法：
 * 在项目根目录运行gradlew testDebugUnitTest并指定本测试类。测试只处理内存字符串和数据模型，
 * 不创建Android Context、SharedPreferences或WebView，因此失败时可以直接定位会话格式规则。
 */
class WebsiteBrowserSessionRepositoryTest {

    /**
     * 验证两个标签往返编解码后顺序、空白页和活动标签都保持不变。
     *
     * @return 无返回值；任一允许持久化的字段丢失或写入额外网页数据时由JUnit报告失败。
     */
    @Test
    fun sessionRoundTripPreservesOnlyLightweightBrowserFields() {
        val session = WebsiteBrowserSession(
            tabs = listOf(
                WebsiteBrowserTab(
                    id = "tab-home",
                    websiteId = "website-home",
                    title = "示例首页",
                    url = "https://example.com/home"
                ),
                WebsiteBrowserTab(id = "tab-blank")
            ),
            activeTabId = "tab-blank"
        )

        val encoded = encodeWebsiteBrowserSession(session)
        assertNotNull(encoded)
        assertTrue(encoded.orEmpty().contains("\"version\":1"))
        assertFalse(encoded.orEmpty().contains("cookie", ignoreCase = true))
        assertFalse(encoded.orEmpty().contains("history", ignoreCase = true))

        val restored = decodeWebsiteBrowserSession(
            encodedSession = encoded,
            fallbackWebsite = null,
            fallbackTabId = "unused-fallback"
        )

        assertEquals(session, restored)
    }

    /**
     * 验证读取时会丢弃空标识、非HTTP网址和重复项，并只保留前八个有效标签。
     *
     * @return 无返回值；非法数据进入模型、重复标签覆盖首项或数量超过上限时由JUnit报告失败。
     */
    @Test
    fun decodeFiltersInvalidDuplicateAndExcessTabs() {
        val validTabs = (1..9).joinToString(separator = ",") { index ->
            """{"id":"tab-$index","website_id":"website-$index","title":"页面$index","url":"https://example.com/$index"}"""
        }
        val encoded = """
            {
              "version": 1,
              "active_tab_id": "tab-9",
              "tabs": [
                {"id":"","website_id":null,"title":"空标识","url":null},
                {"id":"tab-1","website_id":"duplicate","title":"重复项","url":"https://duplicate.example.com"},
                {"id":"bad-url","website_id":null,"title":"错误协议","url":"ftp://example.com/file"},
                $validTabs
              ]
            }
        """.trimIndent()

        val restored = decodeWebsiteBrowserSession(
            encodedSession = encoded,
            fallbackWebsite = null,
            fallbackTabId = "unused-fallback"
        )

        assertEquals(MAX_WEBSITE_BROWSER_TAB_COUNT, restored.tabs.size)
        assertEquals((1..8).map { index -> "tab-$index" }, restored.tabs.map { tab -> tab.id })
        assertEquals("重复项", restored.tabs.first().title)
        assertEquals("tab-1", restored.activeTabId)
        assertTrue(restored.tabs.none { tab -> tab.id == "bad-url" })
    }

    /**
     * 验证没有历史数据时优先使用调用方提供的默认网站创建活动标签。
     *
     * @return 无返回值；默认网站的来源、标题、网址或确定性标签标识未恢复时由JUnit报告失败。
     */
    @Test
    fun missingSessionCreatesFallbackWebsiteTab() {
        val fallbackWebsite = WebsiteShortcut(
            id = "website-default",
            title = "默认网站",
            url = "https://example.com/start"
        )

        val restored = decodeWebsiteBrowserSession(
            encodedSession = null,
            fallbackWebsite = fallbackWebsite,
            fallbackTabId = "fallback-tab"
        )

        assertEquals(
            WebsiteBrowserSession(
                tabs = listOf(
                    WebsiteBrowserTab(
                        id = "fallback-tab",
                        websiteId = fallbackWebsite.id,
                        title = fallbackWebsite.title,
                        url = fallbackWebsite.url
                    )
                ),
                activeTabId = "fallback-tab"
            ),
            restored
        )
    }

    /**
     * 验证未知版本、损坏JSON和没有默认网站时都安全回退为空白标签。
     *
     * @return 无返回值；旧版本被误读或回退会话没有可操作空白标签时由JUnit报告失败。
     */
    @Test
    fun unsupportedOrBrokenSessionCreatesBlankTab() {
        listOf(
            """{"version":99,"active_tab_id":"old","tabs":[]}""",
            "{broken-json"
        ).forEach { encoded ->
            val restored = decodeWebsiteBrowserSession(
                encodedSession = encoded,
                fallbackWebsite = null,
                fallbackTabId = "blank-tab"
            )

            assertEquals(1, restored.tabs.size)
            assertEquals("blank-tab", restored.activeTabId)
            assertEquals(
                WebsiteBrowserTab(
                    id = "blank-tab",
                    title = WEBSITE_BROWSER_NEW_TAB_TITLE
                ),
                restored.tabs.single()
            )
        }
    }

    /**
     * 验证写入前同样会过滤非法项并修复已经失效的活动标签。
     *
     * @return 无返回值；保存路径允许非法协议或保留不存在的活动标签时由JUnit报告失败。
     */
    @Test
    fun encodeFiltersInvalidTabsAndRepairsActiveTab() {
        val encoded = encodeWebsiteBrowserSession(
            WebsiteBrowserSession(
                tabs = listOf(
                    WebsiteBrowserTab(
                        id = "bad-url",
                        title = "错误协议",
                        url = "javascript:alert(1)"
                    ),
                    WebsiteBrowserTab(
                        id = "safe-tab",
                        websiteId = "safe-website",
                        title = "安全页面",
                        url = "https://example.com/safe"
                    )
                ),
                activeTabId = "bad-url"
            )
        )
        val restored = decodeWebsiteBrowserSession(
            encodedSession = encoded,
            fallbackWebsite = null,
            fallbackTabId = "unused-fallback"
        )

        assertEquals(listOf("safe-tab"), restored.tabs.map { tab -> tab.id })
        assertEquals("safe-tab", restored.activeTabId)
    }

    /**
     * 验证异常长标题只会被整理成安全单行快照，不会让仍然有效的标签在保存时整项消失。
     *
     * @return 无返回值；标题未截断、仍含控制字符或标签被过滤时由JUnit报告失败。
     */
    @Test
    fun encodeSanitizesLongPageTitleWithoutDroppingTab() {
        val encoded = encodeWebsiteBrowserSession(
            WebsiteBrowserSession(
                tabs = listOf(
                    WebsiteBrowserTab(
                        id = "safe-tab",
                        title = "  网页\n${"长".repeat(600)}  ",
                        url = "https://example.com/safe"
                    )
                ),
                activeTabId = "safe-tab"
            )
        )
        val restored = decodeWebsiteBrowserSession(
            encodedSession = encoded,
            fallbackWebsite = null,
            fallbackTabId = "unused-fallback"
        )

        assertEquals(1, restored.tabs.size)
        assertEquals(512, restored.tabs.single().title.length)
        assertFalse(restored.tabs.single().title.any(Char::isISOControl))
    }
}
