package com.example.harleyapp

import com.example.harleyapp.data.addBookmarkedPageToDateFolder
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsiteShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证浏览器一键收藏按日期归档的纯数据转换，不读取系统时钟或Android本地存储。
 *
 * 使用方法：
 * 测试直接传入固定日期名称和固定id，在`testDebugUnitTest`中稳定覆盖首次创建、复用和冲突边界。
 */
class WebsiteBookmarkPersistenceModelsTest {

    /**
     * 验证首次收藏会创建根目录日期文件夹，并把网站安全地放入其中。
     *
     * @return 无返回值；归属、默认首页开关、置顶状态或打开次数初值错误时由JUnit报告失败。
     */
    @Test
    fun bookmarkCreatesRootDateFolderWithSafeDefaults() {
        val updated = requireNotNull(
            addBookmarkedPageToDateFolder(
                library = WebsiteLibrary(),
                dateFolderName = "2026-09-19",
                newFolderId = "date-folder",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com/page"
            )
        )

        val folder = updated.folders.single()
        val website = updated.websites.single()
        assertEquals("2026-09-19", folder.name)
        assertNull(folder.parentId)
        assertFalse(folder.isPinned)
        assertEquals(folder.id, website.folderId)
        assertFalse(website.showOnHome)
        assertFalse(website.isPinned)
        assertEquals(0L, website.openCount)
    }

    /**
     * 验证同日收藏复用现有根日期文件夹，并把新网站追加到现有直属内容之后。
     *
     * @return 无返回值；重复创建文件夹或新网站排序未追加时由JUnit报告失败。
     */
    @Test
    fun bookmarkReusesRootDateFolderAndAppendsContent() {
        val dateFolder = WebsiteFolder("existing-date", "2026-09-19", sortOrder = 10)
        val childFolder = WebsiteFolder(
            id = "child",
            name = "子目录",
            parentId = dateFolder.id,
            sortOrder = 20
        )
        val existingWebsite = WebsiteShortcut(
            id = "existing-site",
            title = "已有网站",
            url = "https://existing.example.com",
            folderId = dateFolder.id,
            sortOrder = 30
        )
        val updated = requireNotNull(
            addBookmarkedPageToDateFolder(
                library = WebsiteLibrary(
                    websites = listOf(existingWebsite),
                    folders = listOf(dateFolder, childFolder)
                ),
                dateFolderName = "2026-09-19",
                newFolderId = "unused-folder",
                bookmarkId = "new-site",
                pageTitle = "新网站",
                normalizedUrl = "https://new.example.com"
            )
        )

        assertEquals(2, updated.folders.size)
        val newWebsite = updated.websites.first { website -> website.id == "new-site" }
        assertEquals(dateFolder.id, newWebsite.folderId)
        assertEquals(40, newWebsite.sortOrder)
    }

    /**
     * 验证嵌套同名文件夹不会被误当作当天根目录，函数会另外创建根日期文件夹。
     *
     * @return 无返回值；新网站落入嵌套同名文件夹时由JUnit报告失败。
     */
    @Test
    fun nestedDateFolderIsNotReused() {
        val parent = WebsiteFolder("parent", "父目录")
        val nested = WebsiteFolder(
            id = "nested-date",
            name = "2026-09-19",
            parentId = parent.id
        )
        val updated = requireNotNull(
            addBookmarkedPageToDateFolder(
                library = WebsiteLibrary(folders = listOf(parent, nested)),
                dateFolderName = "2026-09-19",
                newFolderId = "root-date",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com"
            )
        )

        val rootDate = updated.folders.first { folder -> folder.id == "root-date" }
        assertNull(rootDate.parentId)
        assertEquals(rootDate.id, updated.websites.single().folderId)
    }

    /**
     * 验证多个根级同名日期文件夹存在时稳定复用顺序最小、id最小的文件夹，不合并用户数据。
     *
     * @return 无返回值；选择规则不稳定或文件夹被删除合并时由JUnit报告失败。
     */
    @Test
    fun duplicateRootDateFoldersReuseDeterministicFirst() {
        val later = WebsiteFolder("z-folder", "2026-09-19", sortOrder = 10)
        val selected = WebsiteFolder("a-folder", "2026-09-19", sortOrder = 10)
        val updated = requireNotNull(
            addBookmarkedPageToDateFolder(
                library = WebsiteLibrary(folders = listOf(later, selected)),
                dateFolderName = "2026-09-19",
                newFolderId = "unused",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com"
            )
        )

        assertEquals(2, updated.folders.size)
        assertEquals(selected.id, updated.websites.single().folderId)
    }

    /**
     * 验证新文件夹与新网站不能共用同一标识，避免生成无法被仓库保存的跨类型重复节点。
     *
     * @return 无返回值；标识冲突仍返回网站库时由JUnit报告失败。
     */
    @Test
    fun bookmarkRejectsFolderWebsiteIdCollision() {
        val updated = addBookmarkedPageToDateFolder(
            library = WebsiteLibrary(),
            dateFolderName = "2026-09-19",
            newFolderId = "same-id",
            bookmarkId = "same-id",
            pageTitle = "测试页面",
            normalizedUrl = "https://example.com"
        )

        assertNull(updated)
    }

    /**
     * 验证极端顺序值不会在追加时整数溢出成负数并跑到列表首位。
     *
     * @return 无返回值；追加顺序为负数或无法生成网站时由JUnit报告失败。
     */
    @Test
    fun bookmarkOrderDoesNotOverflowBelowZero() {
        val dateFolder = WebsiteFolder("date", "2026-09-19")
        val existing = WebsiteShortcut(
            id = "maximum",
            title = "极端顺序",
            url = "https://maximum.example.com",
            folderId = dateFolder.id,
            sortOrder = Int.MAX_VALUE
        )
        val updated = addBookmarkedPageToDateFolder(
            library = WebsiteLibrary(
                websites = listOf(existing),
                folders = listOf(dateFolder)
            ),
            dateFolderName = "2026-09-19",
            newFolderId = "unused",
            bookmarkId = "bookmark",
            pageTitle = "测试页面",
            normalizedUrl = "https://example.com"
        )

        assertNotNull(updated)
        assertEquals(Int.MAX_VALUE, updated?.websites?.first { it.id == "bookmark" }?.sortOrder)
    }
}
