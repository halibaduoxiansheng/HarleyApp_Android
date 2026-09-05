package com.example.harleyapp

import com.example.harleyapp.data.initialWebsiteShortcuts
import com.example.harleyapp.data.removeObsoleteBuiltInWebsites
import com.example.harleyapp.model.normalizeWebsiteUrl
import com.example.harleyapp.model.nextWebsiteCarouselPage
import com.example.harleyapp.model.resolveDefaultWebsiteId
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.model.BrowserBookmarkNodeSnapshot
import com.example.harleyapp.model.EDGE_BROWSER_PACKAGE_NAME
import com.example.harleyapp.model.GOOGLE_APP_PACKAGE_NAME
import com.example.harleyapp.model.canPlaceWebsiteFolder
import com.example.harleyapp.model.formatWebsiteThemeColor
import com.example.harleyapp.model.homeCarouselWebsites
import com.example.harleyapp.model.isValidWebsiteBackgroundFileName
import com.example.harleyapp.model.moveWebsiteNodesToFolder
import com.example.harleyapp.model.parseWebsiteThemeColor
import com.example.harleyapp.model.resolveBrowserBookmarkPage
import com.example.harleyapp.model.resolveSharedBrowserPage
import com.example.harleyapp.model.websiteFolderPath
import com.example.harleyapp.model.websiteMatchesQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证自定义网站输入的纯网址规范化逻辑，不依赖Android设备或WebView。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class WebsiteModelsTest {

    /**
     * 验证Edge地址栏省略HTTPS时仍能保存完整路径，并优先使用网页标题。
     *
     * @return 无返回值；网址、查询参数或标题丢失时由JUnit报告失败。
     */
    @Test
    fun edgeAddressBarResolvesCurrentPageBookmark() {
        val page = resolveBrowserBookmarkPage(
            packageName = EDGE_BROWSER_PACKAGE_NAME,
            nodes = listOf(
                BrowserBookmarkNodeSnapshot(
                    resourceId = "com.microsoft.emmx:id/url_bar",
                    className = "android.widget.EditText",
                    text = "example.com/news?id=7"
                ),
                BrowserBookmarkNodeSnapshot(
                    className = "android.widget.TextView",
                    text = "今日新闻",
                    insideWebView = true
                )
            )
        )

        assertEquals("https://example.com/news?id=7", page?.url)
        assertEquals("今日新闻", page?.title)
    }

    /**
     * 验证Google App地址栏能够读取明确HTTPS网址，正文中的其他链接不会抢占当前页。
     *
     * @return 无返回值；选中正文链接或错误标题时由JUnit报告失败。
     */
    @Test
    fun googleAppAddressBarWinsOverPageLinks() {
        val page = resolveBrowserBookmarkPage(
            packageName = GOOGLE_APP_PACKAGE_NAME,
            nodes = listOf(
                BrowserBookmarkNodeSnapshot(
                    resourceId = "com.google.android.googlequicksearchbox:id/url_bar",
                    text = "https://developer.android.com/guide"
                ),
                BrowserBookmarkNodeSnapshot(
                    text = "https://unrelated.example.com",
                    insideWebView = true
                )
            )
        )

        assertEquals("https://developer.android.com/guide", page?.url)
        assertEquals("developer.android.com", page?.title)
    }

    /**
     * 验证通用浏览器地址栏可以作为厂商浏览器兜底，并拒绝搜索词和内部页面。
     *
     * @return 无返回值；普通搜索词被误收藏或合法厂商地址栏无法识别时由JUnit报告失败。
     */
    @Test
    fun genericBrowserResolverRejectsSearchTerms() {
        val genericPage = resolveBrowserBookmarkPage(
            packageName = "com.vendor.browser",
            nodes = listOf(
                BrowserBookmarkNodeSnapshot(
                    resourceId = "com.vendor.browser:id/address_bar_text",
                    text = "www.example.org/articles/1"
                )
            )
        )

        assertEquals("https://www.example.org/articles/1", genericPage?.url)
        assertNull(
            resolveBrowserBookmarkPage(
                packageName = EDGE_BROWSER_PACKAGE_NAME,
                nodes = listOf(
                    BrowserBookmarkNodeSnapshot(
                        resourceId = "com.microsoft.emmx:id/url_bar",
                        text = "今天有什么新闻"
                    )
                )
            )
        )
        assertNull(
            resolveBrowserBookmarkPage(
                packageName = EDGE_BROWSER_PACKAGE_NAME,
                nodes = listOf(
                    BrowserBookmarkNodeSnapshot(
                        resourceId = "com.microsoft.emmx:id/url_bar",
                        text = "edge://newtab"
                    )
                )
            )
        )
    }

    /**
     * 验证系统分享接收器完整保留路径、查询参数和片段，并优先采用浏览器给出的标题。
     *
     * @return 无返回值；完整网址或页面标题丢失时由JUnit报告失败。
     */
    @Test
    fun sharedBrowserPageKeepsExactUrlAndTitle() {
        val page = resolveSharedBrowserPage(
            sharedText = "https://example.com/harley-test/path?item=7#saved",
            sharedTitle = "测试文章"
        )

        assertEquals("https://example.com/harley-test/path?item=7#saved", page?.url)
        assertEquals("测试文章", page?.title)
    }

    /**
     * 验证带说明文字的浏览器分享仍能提取HTTP网址，同时拒绝内部协议和普通文字。
     *
     * @return 无返回值；错误内容被收藏或有效网址无法提取时由JUnit报告失败。
     */
    @Test
    fun sharedBrowserPageExtractsOnlyWebUrls() {
        val page = resolveSharedBrowserPage(
            sharedText = "推荐页面：https://developer.android.com/guide。"
        )

        assertEquals("https://developer.android.com/guide", page?.url)
        assertEquals("developer.android.com", page?.title)
        assertNull(resolveSharedBrowserPage("edge://newtab"))
        assertNull(resolveSharedBrowserPage("今天有什么新闻"))
    }

    /**
     * 验证新安装不再自动写入开发者网站、搜书网站或其他代码内置收藏。
     *
     * @return 无返回值；初始列表出现任何网站时由JUnit报告失败。
     */
    @Test
    fun freshInstallStartsWithEmptyWebsiteCollection() {
        assertTrue(initialWebsiteShortcuts().isEmpty())
    }

    /**
     * 验证升级清理只删除旧内置稳定id，不会按网址误删用户自己收藏的相同网站。
     *
     * @return 无返回值；旧内置项残留或用户收藏被误删时由JUnit报告失败。
     */
    @Test
    fun upgradeRemovesOnlyObsoleteBuiltInWebsiteIds() {
        val migrated = removeObsoleteBuiltInWebsites(
            listOf(
                WebsiteShortcut("default_halibaduo", "旧内置首页", "http://www.halibaduo.cn"),
                WebsiteShortcut("builtin_jiumo_diary", "旧内置搜书", "https://www.jiumodiary.com/"),
                WebsiteShortcut("user-copy", "用户自己的首页", "http://www.halibaduo.cn")
            )
        )

        assertEquals(listOf("user-copy"), migrated.map(WebsiteShortcut::id))
    }

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
     * 验证任意合法域名的HTTPS地址与HTTP地址使用相同入口校验，不依赖域名白名单。
     *
     * @return 无返回值；合法HTTPS地址被拒绝或被替换协议时由JUnit报告失败。
     */
    @Test
    fun explicitHttpsIsAcceptedWithoutDomainAllowlist() {
        assertEquals(
            "https://another-domain.example/path",
            normalizeWebsiteUrl("https://another-domain.example/path")
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

    /**
     * 验证首页轮播只包含用户勾选的网站，并遵守文件夹树与同层顺序。
     *
     * @return 无返回值；筛选或展开顺序不符时由JUnit报告失败。
     */
    @Test
    fun homeCarouselUsesSelectedBookmarksInTreeOrder() {
        val folder = WebsiteFolder(
            id = "work",
            name = "工作",
            sortOrder = 0
        )
        val library = WebsiteLibrary(
            folders = listOf(folder),
            websites = listOf(
                WebsiteShortcut(
                    id = "root",
                    title = "根目录",
                    url = "https://root.example.com",
                    sortOrder = 10
                ),
                WebsiteShortcut(
                    id = "hidden",
                    title = "不在首页",
                    url = "https://hidden.example.com",
                    folderId = folder.id,
                    showOnHome = false,
                    sortOrder = 0
                ),
                WebsiteShortcut(
                    id = "selected",
                    title = "文件夹网站",
                    url = "https://selected.example.com",
                    folderId = folder.id,
                    sortOrder = 10
                )
            )
        )

        assertEquals(
            listOf("selected", "root"),
            homeCarouselWebsites(library).map { website -> website.id }
        )
    }

    /**
     * 验证搜索同时支持网站名称、网址、文件夹路径和非连续字符顺序匹配。
     *
     * @return 无返回值；任一应命中的关键词未命中时由JUnit报告失败。
     */
    @Test
    fun bookmarkSearchMatchesTitleUrlFolderAndSubsequence() {
        val website = WebsiteShortcut(
            id = "docs",
            title = "Android开发文档",
            url = "https://developer.android.com"
        )

        assertTrue(websiteMatchesQuery(website, "学习 / 编程", "开发"))
        assertTrue(websiteMatchesQuery(website, "学习 / 编程", "developer"))
        assertTrue(websiteMatchesQuery(website, "学习 / 编程", "学编"))
        assertFalse(websiteMatchesQuery(website, "学习 / 编程", "财务"))
    }

    /**
     * 验证文件夹路径按根到叶子生成，并阻止移动到后代目录或超过五层。
     *
     * @return 无返回值；路径、循环保护或层级限制错误时由JUnit报告失败。
     */
    @Test
    fun folderPlacementPreventsCyclesAndExcessDepth() {
        val folders = (1..5).map { depth ->
            WebsiteFolder(
                id = "level$depth",
                name = "第${depth}层",
                parentId = if (depth == 1) null else "level${depth - 1}",
                sortOrder = depth
            )
        }
        val library = WebsiteLibrary(folders = folders)

        assertEquals(
            "第1层 / 第2层 / 第3层",
            websiteFolderPath(folders, "level3")
        )
        assertFalse(canPlaceWebsiteFolder(library, "level1", "level3"))
        assertFalse(canPlaceWebsiteFolder(library, folderId = null, candidateParentId = "level5"))
        assertTrue(canPlaceWebsiteFolder(library, folderId = null, candidateParentId = "level4"))
    }

    /**
     * 验证已有网站和整个文件夹子树能够一起追加到目标目录末尾。
     *
     * @return 无返回值；归属、子树结构或追加顺序不符时由JUnit报告失败。
     */
    @Test
    fun existingWebsitesAndFoldersMoveToTargetFolder() {
        val target = WebsiteFolder(id = "target", name = "目标", sortOrder = 0)
        val movingFolder = WebsiteFolder(id = "moving", name = "项目", sortOrder = 0)
        val childFolder = WebsiteFolder(
            id = "child",
            name = "子目录",
            parentId = movingFolder.id,
            sortOrder = 0
        )
        val library = WebsiteLibrary(
            folders = listOf(target, movingFolder, childFolder),
            websites = listOf(
                WebsiteShortcut(
                    id = "existing",
                    title = "目标原有",
                    url = "https://existing.example.com",
                    folderId = target.id,
                    sortOrder = 20
                ),
                WebsiteShortcut(
                    id = "moving-site",
                    title = "待移动",
                    url = "https://moving.example.com",
                    sortOrder = 0
                ),
                WebsiteShortcut(
                    id = "nested",
                    title = "内部网站",
                    url = "https://nested.example.com",
                    folderId = childFolder.id,
                    sortOrder = 0
                )
            )
        )

        val moved = requireNotNull(
            moveWebsiteNodesToFolder(
                library = library,
                targetFolderId = target.id,
                websiteIds = setOf("moving-site"),
                folderIds = setOf(movingFolder.id)
            )
        )

        assertEquals(target.id, moved.folders.first { it.id == movingFolder.id }.parentId)
        assertEquals(movingFolder.id, moved.folders.first { it.id == childFolder.id }.parentId)
        assertEquals(target.id, moved.websites.first { it.id == "moving-site" }.folderId)
        assertEquals(childFolder.id, moved.websites.first { it.id == "nested" }.folderId)
        assertEquals(30, moved.folders.first { it.id == movingFolder.id }.sortOrder)
        assertEquals(40, moved.websites.first { it.id == "moving-site" }.sortOrder)
    }

    /**
     * 验证同时选择父目录、子目录和内部网站时只移动父目录，内部结构不会被拆散。
     *
     * @return 无返回值；重复选择导致子内容被提升到目标目录时由JUnit报告失败。
     */
    @Test
    fun selectedParentKeepsSelectedDescendantsInsideSubtree() {
        val target = WebsiteFolder(id = "target", name = "目标")
        val parent = WebsiteFolder(id = "parent", name = "父目录")
        val child = WebsiteFolder(id = "child", name = "子目录", parentId = parent.id)
        val nestedWebsite = WebsiteShortcut(
            id = "nested",
            title = "内部网站",
            url = "https://nested.example.com",
            folderId = child.id
        )
        val library = WebsiteLibrary(
            folders = listOf(target, parent, child),
            websites = listOf(nestedWebsite)
        )

        val moved = requireNotNull(
            moveWebsiteNodesToFolder(
                library = library,
                targetFolderId = target.id,
                websiteIds = setOf(nestedWebsite.id),
                folderIds = setOf(parent.id, child.id)
            )
        )

        assertEquals(target.id, moved.folders.first { it.id == parent.id }.parentId)
        assertEquals(parent.id, moved.folders.first { it.id == child.id }.parentId)
        assertEquals(child.id, moved.websites.first { it.id == nestedWebsite.id }.folderId)
    }

    /**
     * 验证不能把文件夹移动到自己的后代目录。
     *
     * @return 无返回值；循环移动未被拒绝时由JUnit报告失败。
     */
    @Test
    fun movingFolderIntoDescendantIsRejected() {
        val parent = WebsiteFolder(id = "parent", name = "父目录")
        val child = WebsiteFolder(id = "child", name = "子目录", parentId = parent.id)
        val library = WebsiteLibrary(folders = listOf(parent, child))

        assertNull(
            moveWebsiteNodesToFolder(
                library = library,
                targetFolderId = child.id,
                websiteIds = emptySet(),
                folderIds = setOf(parent.id)
            )
        )
    }

    /**
     * 验证自定义主题色支持带井号和不带井号输入，并统一保存为不透明ARGB。
     *
     * @return 无返回值；颜色解析或格式化不稳定时由JUnit报告失败。
     */
    @Test
    fun customWebsiteThemeColorRoundTrips() {
        val color = parseWebsiteThemeColor("#2f80ed")

        assertEquals(0xFF2F80EDL, color)
        assertEquals("#2F80ED", formatWebsiteThemeColor(color))
        assertEquals(0xFFFFAA00L, parseWebsiteThemeColor("FFAA00"))
        assertNull(parseWebsiteThemeColor("#12345"))
        assertNull(parseWebsiteThemeColor("#GG0000"))
    }

    /**
     * 验证背景图片字段只接受App生成的UUID JPEG文件名，拒绝目录穿越和其他扩展名。
     *
     * @return 无返回值；危险文件名被接受时由JUnit报告失败。
     */
    @Test
    fun websiteBackgroundFileNameRejectsUnsafePaths() {
        assertTrue(isValidWebsiteBackgroundFileName(null))
        assertTrue(
            isValidWebsiteBackgroundFileName(
                "123e4567-e89b-12d3-a456-426614174000.jpg"
            )
        )
        assertFalse(isValidWebsiteBackgroundFileName("../background.jpg"))
        assertFalse(isValidWebsiteBackgroundFileName("background.png"))
    }
}
