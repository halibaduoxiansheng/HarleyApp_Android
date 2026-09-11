package com.example.harleyapp

import com.example.harleyapp.model.MAX_WEBSITE_BROWSER_TAB_COUNT
import com.example.harleyapp.model.WEBSITE_BROWSER_NEW_TAB_TITLE
import com.example.harleyapp.model.WebsiteBrowserSession
import com.example.harleyapp.model.WebsiteBrowserTab
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.closeWebsiteBrowserTab
import com.example.harleyapp.model.createBlankWebsiteBrowserTab
import com.example.harleyapp.model.createWebsiteBrowserChildTab
import com.example.harleyapp.model.createWebsiteBrowserSession
import com.example.harleyapp.model.createWebsiteBrowserTab
import com.example.harleyapp.model.fillWebsiteBrowserTab
import com.example.harleyapp.model.selectWebsiteBrowserTab
import com.example.harleyapp.model.updateWebsiteBrowserTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 验证网站浏览器多标签会话的纯状态转换，不依赖Android设备或WebView。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class WebsiteBrowserModelsTest {

    /**
     * 验证初始会话始终包含一个已选中的新标签页。
     *
     * @return 无返回值；初始标签内容或选中状态错误时由JUnit报告失败。
     */
    @Test
    fun initialSessionContainsSelectedBlankTab() {
        val session = createWebsiteBrowserSession("blank-1")

        assertEquals(1, session.tabs.size)
        assertEquals("blank-1", session.activeTabId)
        assertEquals(WEBSITE_BROWSER_NEW_TAB_TITLE, session.tabs.single().title)
        assertNull(session.tabs.single().websiteId)
        assertNull(session.tabs.single().url)
    }

    /**
     * 验证从网站入口新建标签时追加到末尾并切换到新标签。
     *
     * @return 无返回值；网站来源、网址、标题或活动标签错误时由JUnit报告失败。
     */
    @Test
    fun websiteTabIsAppendedAndSelected() {
        val session = createWebsiteBrowserTab(
            session = createWebsiteBrowserSession("blank-1"),
            tabId = "website-1",
            website = website(id = "source-1", title = "示例网站")
        )

        assertEquals(listOf("blank-1", "website-1"), session.tabs.map(WebsiteBrowserTab::id))
        assertEquals("website-1", session.activeTabId)
        assertEquals("source-1", session.tabs.last().websiteId)
        assertEquals("示例网站", session.tabs.last().title)
        assertEquals("https://example.com/source-1", session.tabs.last().url)
    }

    /**
     * 验证网页新窗口继承来源网站归属，临时标签标识不会污染工具设置键。
     *
     * @return 无返回值；websiteId未继承或被替换成任一标签id时由JUnit报告失败。
     */
    @Test
    fun childTabInheritsSourceWebsiteIdInsteadOfTemporaryTabId() {
        val sourceSession = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "source-tab",
            website = website(id = "saved-website")
        )
        val childSession = createWebsiteBrowserChildTab(
            session = sourceSession,
            sourceTabId = "  source-tab  ",
            tabId = "temporary-child-tab",
            title = "  新窗口文章  ",
            url = " https://example.com/article "
        )
        val childTab = childSession.tabs.last()

        assertEquals("temporary-child-tab", childSession.activeTabId)
        assertEquals("temporary-child-tab", childTab.id)
        assertEquals("saved-website", childTab.websiteId)
        assertEquals("新窗口文章", childTab.title)
        assertEquals("https://example.com/article", childTab.url)
    }

    /**
     * 验证网页新窗口的来源标签不存在时不会创建孤立标签。
     *
     * @return 无返回值；无效来源仍改变标签会话时由JUnit报告失败。
     */
    @Test
    fun childTabRequiresExistingSourceTab() {
        val session = createWebsiteBrowserSession("blank-1")
        val unchangedSession = createWebsiteBrowserChildTab(
            session = session,
            sourceTabId = "missing-source",
            tabId = "temporary-child-tab",
            title = "文章",
            url = "https://example.com/article"
        )

        assertSame(session, unchangedSession)
    }

    /**
     * 验证新窗口只接受可恢复的HTTP或HTTPS地址，内部协议、用户信息和超长地址都不会创建标签。
     *
     * @return 无返回值；任一不安全地址改变会话时由JUnit报告失败。
     */
    @Test
    fun childTabRejectsUnsafeOrUnrecoverableUrls() {
        val session = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "source-tab",
            website = website(id = "source")
        )
        val invalidUrls = listOf(
            "about:blank",
            "javascript:alert(1)",
            "file:///sdcard/private.txt",
            "https://user:password@example.com/private",
            "https://example.com/${"a".repeat(8 * 1024)}"
        )

        invalidUrls.forEachIndexed { index, url ->
            val unchangedSession = createWebsiteBrowserChildTab(
                session = session,
                sourceTabId = "source-tab",
                tabId = "child-$index",
                title = "新窗口",
                url = url
            )

            assertSame(session, unchangedSession)
        }
    }

    /**
     * 验证新增空白标签后可用网站内容填充，且已加载标签不会被误覆盖。
     *
     * @return 无返回值；空白复用或覆盖保护失效时由JUnit报告失败。
     */
    @Test
    fun blankTabCanBeFilledOnlyOnce() {
        val blankSession = createBlankWebsiteBrowserTab(
            session = createWebsiteBrowserSession("blank-1"),
            tabId = "blank-2"
        )
        val filledSession = fillWebsiteBrowserTab(
            session = blankSession,
            tabId = "  blank-2  ",
            website = website(id = "first")
        )
        val unchangedSession = fillWebsiteBrowserTab(
            session = filledSession,
            tabId = "blank-2",
            website = website(id = "second")
        )

        assertEquals("blank-2", filledSession.activeTabId)
        assertEquals("first", filledSession.tabs.last().websiteId)
        assertSame(filledSession, unchangedSession)
    }

    /**
     * 验证创建函数共同遵守八个标签上限并拒绝重复标识。
     *
     * @return 无返回值；标签超过上限或重复标识被加入时由JUnit报告失败。
     */
    @Test
    fun tabCreationStopsAtMaximumCountAndRejectsDuplicateId() {
        val fullSession = (2..MAX_WEBSITE_BROWSER_TAB_COUNT).fold(
            createWebsiteBrowserSession("tab-1")
        ) { session, index ->
            createBlankWebsiteBrowserTab(session, "tab-$index")
        }

        val overLimitSession = createWebsiteBrowserTab(
            session = fullSession,
            tabId = "tab-over-limit",
            website = website(id = "over-limit")
        )
        val childOverLimitSession = createWebsiteBrowserChildTab(
            session = fullSession,
            sourceTabId = "tab-1",
            tabId = "child-over-limit",
            title = "新窗口",
            url = "https://example.com/new-window"
        )
        val sessionWithRoom = fullSession.copy(tabs = fullSession.tabs.dropLast(1))
        val duplicateSession = createBlankWebsiteBrowserTab(
            session = sessionWithRoom,
            tabId = "tab-1"
        )

        assertEquals(MAX_WEBSITE_BROWSER_TAB_COUNT, fullSession.tabs.size)
        assertSame(fullSession, overLimitSession)
        assertSame(fullSession, childOverLimitSession)
        assertSame(sessionWithRoom, duplicateSession)
    }

    /**
     * 验证页面完成加载后可分别更新标题和网址，空白回调不会清除有效数据。
     *
     * @return 无返回值；增量更新影响错误字段时由JUnit报告失败。
     */
    @Test
    fun pageMetadataUpdatePreservesMissingValues() {
        val createdSession = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "website-1",
            website = website(id = "source-1", title = "入口标题")
        )
        val titleUpdatedSession = updateWebsiteBrowserTab(
            session = createdSession,
            tabId = "  website-1  ",
            title = "文章标题"
        )
        val fullyUpdatedSession = updateWebsiteBrowserTab(
            session = titleUpdatedSession,
            tabId = "website-1",
            title = "   ",
            url = " https://example.com/article "
        )

        assertEquals("文章标题", fullyUpdatedSession.tabs.single().title)
        assertEquals("https://example.com/article", fullyUpdatedSession.tabs.single().url)
        assertEquals("source-1", fullyUpdatedSession.tabs.single().websiteId)
    }

    /**
     * 验证异常网页标题会被整理和截断，非法新网址只会保留标签最后一次有效地址。
     *
     * @return 无返回值；异常元数据导致标签消失、控制字符残留或有效地址被覆盖时由JUnit报告失败。
     */
    @Test
    fun pageMetadataIsNormalizedBeforeItEntersSession() {
        val session = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "website-1",
            website = website(id = "source-1")
        )
        val updatedSession = updateWebsiteBrowserTab(
            session = session,
            tabId = "website-1",
            title = "  页面\n${"长".repeat(600)}  ",
            url = "https://user:password@example.com/private"
        )
        val updatedTab = updatedSession.tabs.single()

        assertEquals(512, updatedTab.title.length)
        assertFalse(updatedTab.title.any(Char::isISOControl))
        assertEquals(session.tabs.single().url, updatedTab.url)
    }

    /**
     * 验证标题长度边界不会把emoji的UTF-16代理对截成无效的半个字符。
     *
     * @return 无返回值；标题超过上限或末尾残留孤立高代理项时由JUnit报告失败。
     */
    @Test
    fun pageTitleTruncationKeepsEmojiPairsIntact() {
        val session = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "website-1",
            website = website(id = "source-1")
        )
        val updatedSession = updateWebsiteBrowserTab(
            session = session,
            tabId = "website-1",
            title = "a".repeat(511) + "😀"
        )
        val title = updatedSession.tabs.single().title

        assertEquals(511, title.length)
        assertFalse(Character.isHighSurrogate(title.last()))
    }

    /**
     * 验证创建入口会统一整理标识，保存后不会再把两个标签折叠成同一个id。
     *
     * @return 无返回值；首尾空白未清理或规范化后重复标识仍被加入时由JUnit报告失败。
     */
    @Test
    fun tabIdentifiersAreNormalizedBeforeCreation() {
        val firstSession = createWebsiteBrowserSession("  tab-1  ")
        val duplicateSession = createBlankWebsiteBrowserTab(firstSession, "tab-1")

        assertEquals("tab-1", firstSession.tabs.single().id)
        assertSame(firstSession, duplicateSession)
    }

    /**
     * 验证只允许切换到存在的标签，无效标识保持原会话不变。
     *
     * @return 无返回值；有效切换失败或无效切换改变状态时由JUnit报告失败。
     */
    @Test
    fun selectionRequiresExistingTab() {
        val session = sessionWithTabs(activeTabId = "left")
        val selectedSession = selectWebsiteBrowserTab(session, "middle")
        val unchangedSession = selectWebsiteBrowserTab(selectedSession, "missing")

        assertEquals("middle", selectedSession.activeTabId)
        assertSame(selectedSession, unchangedSession)
    }

    /**
     * 验证关闭当前中间标签时优先选中原位置右侧标签。
     *
     * @return 无返回值；剩余顺序或右邻选择错误时由JUnit报告失败。
     */
    @Test
    fun closingActiveMiddleTabSelectsRightNeighbor() {
        val closedSession = closeWebsiteBrowserTab(
            session = sessionWithTabs(activeTabId = "middle"),
            tabId = "middle",
            replacementBlankTabId = "replacement"
        )

        assertEquals(listOf("left", "right"), closedSession.tabs.map(WebsiteBrowserTab::id))
        assertEquals("right", closedSession.activeTabId)
    }

    /**
     * 验证关闭最左侧活动标签后选择其右邻，并且关闭不存在的标签保持原会话实例。
     *
     * @return 无返回值；左邻边界或无效关闭改变会话时由JUnit报告失败。
     */
    @Test
    fun closingActiveLeftmostTabSelectsRightNeighborAndMissingTabIsIgnored() {
        val session = sessionWithTabs(activeTabId = "left")
        val closedSession = closeWebsiteBrowserTab(
            session = session,
            tabId = "left",
            replacementBlankTabId = "replacement"
        )
        val unchangedSession = closeWebsiteBrowserTab(
            session = closedSession,
            tabId = "missing",
            replacementBlankTabId = "replacement"
        )

        assertEquals(listOf("middle", "right"), closedSession.tabs.map(WebsiteBrowserTab::id))
        assertEquals("middle", closedSession.activeTabId)
        assertSame(closedSession, unchangedSession)
    }

    /**
     * 验证关闭最右侧当前标签时回退到左邻标签。
     *
     * @return 无返回值；关闭后没有选择左邻标签时由JUnit报告失败。
     */
    @Test
    fun closingActiveRightmostTabSelectsLeftNeighbor() {
        val closedSession = closeWebsiteBrowserTab(
            session = sessionWithTabs(activeTabId = "right"),
            tabId = "right",
            replacementBlankTabId = "replacement"
        )

        assertEquals(listOf("left", "middle"), closedSession.tabs.map(WebsiteBrowserTab::id))
        assertEquals("middle", closedSession.activeTabId)
    }

    /**
     * 验证关闭后台标签不会打断用户当前正在浏览的标签。
     *
     * @return 无返回值；活动标签被意外切换时由JUnit报告失败。
     */
    @Test
    fun closingBackgroundTabKeepsCurrentSelection() {
        val closedSession = closeWebsiteBrowserTab(
            session = sessionWithTabs(activeTabId = "right"),
            tabId = "left",
            replacementBlankTabId = "replacement"
        )

        assertEquals(listOf("middle", "right"), closedSession.tabs.map(WebsiteBrowserTab::id))
        assertEquals("right", closedSession.activeTabId)
    }

    /**
     * 验证关闭唯一标签时自动生成新的空白标签页。
     *
     * @return 无返回值；浏览器变成无标签状态或旧网页信息残留时由JUnit报告失败。
     */
    @Test
    fun closingOnlyTabCreatesReplacementBlankTab() {
        val originalSession = createWebsiteBrowserTab(
            session = WebsiteBrowserSession(),
            tabId = "only",
            website = website(id = "source-1")
        )
        val closedSession = closeWebsiteBrowserTab(
            session = originalSession,
            tabId = "only",
            replacementBlankTabId = "blank-new"
        )

        assertEquals("blank-new", closedSession.activeTabId)
        assertEquals(WEBSITE_BROWSER_NEW_TAB_TITLE, closedSession.tabs.single().title)
        assertNull(closedSession.tabs.single().websiteId)
        assertNull(closedSession.tabs.single().url)
    }

    /**
     * 构造包含左、中、右三个标签的固定测试会话。
     *
     * 使用方法：
     * 关闭和切换测试传入需要预先选中的标签id，其他标签顺序保持固定。
     *
     * @param activeTabId 初始活动标签标识。
     * @return 包含三个固定空白标签的测试会话。
     */
    private fun sessionWithTabs(activeTabId: String): WebsiteBrowserSession {
        return WebsiteBrowserSession(
            tabs = listOf(
                WebsiteBrowserTab(id = "left"),
                WebsiteBrowserTab(id = "middle"),
                WebsiteBrowserTab(id = "right")
            ),
            activeTabId = activeTabId
        )
    }

    /**
     * 构造用于测试的网站入口。
     *
     * 使用方法：
     * 测试只传入关注的来源标识和标题，网址根据来源标识稳定生成。
     *
     * @param id 网站来源标识。
     * @param title 网站入口标题。
     * @return 可传给标签会话纯函数的网站入口。
     */
    private fun website(
        id: String,
        title: String = "网站-$id"
    ): WebsiteShortcut {
        return WebsiteShortcut(
            id = id,
            title = title,
            url = "https://example.com/$id"
        )
    }
}
