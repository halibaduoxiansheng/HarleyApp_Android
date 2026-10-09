package com.example.harleyapp

import com.example.harleyapp.data.addBookmarkedPageToDateHierarchy
import com.example.harleyapp.data.organizeRootDateFoldersByYearMonth
import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsiteShortcut
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证浏览器一键收藏按“年/月/日”归档的纯数据转换，不读取系统时钟或Android本地存储。
 *
 * 使用方法：
 * 测试直接传入固定日期和固定id，在`testDebugUnitTest`中稳定覆盖首次创建、复用、旧数据迁移和
 * 标识冲突边界。
 */
class WebsiteBookmarkPersistenceModelsTest {

    /**
     * 验证首次收藏会创建“年/月/日”三级文件夹，并把网站安全地放入日期文件夹。
     *
     * @return 无返回值；层级、名称或网站安全默认值错误时由JUnit报告失败。
     */
    @Test
    fun bookmarkCreatesYearMonthDateHierarchyWithSafeDefaults() {
        val updated = requireNotNull(
            addBookmarkedPageToDateHierarchy(
                library = WebsiteLibrary(),
                bookmarkDate = LocalDate.of(2026, 9, 19),
                newYearFolderId = "year-folder",
                newMonthFolderId = "month-folder",
                newDateFolderId = "date-folder",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com/page"
            )
        )

        val yearFolder = updated.folders.first { folder -> folder.id == "year-folder" }
        val monthFolder = updated.folders.first { folder -> folder.id == "month-folder" }
        val dateFolder = updated.folders.first { folder -> folder.id == "date-folder" }
        val website = updated.websites.single()
        assertEquals("2026年", yearFolder.name)
        assertNull(yearFolder.parentId)
        assertEquals("9月", monthFolder.name)
        assertEquals(yearFolder.id, monthFolder.parentId)
        assertEquals("2026-09-19", dateFolder.name)
        assertEquals(monthFolder.id, dateFolder.parentId)
        assertFalse(dateFolder.isPinned)
        assertEquals(dateFolder.id, website.folderId)
        assertFalse(website.showOnHome)
        assertFalse(website.isPinned)
        assertEquals(0L, website.openCount)
    }

    /**
     * 验证同日收藏复用现有三级目录，并把新网站追加到日期文件夹现有直属内容之后。
     *
     * @return 无返回值；重复创建文件夹或新网站排序未追加时由JUnit报告失败。
     */
    @Test
    fun bookmarkReusesExistingHierarchyAndAppendsContent() {
        val yearFolder = WebsiteFolder("existing-year", "2026年", sortOrder = 10)
        val monthFolder = WebsiteFolder(
            id = "existing-month",
            name = "9月",
            parentId = yearFolder.id,
            sortOrder = 10
        )
        val dateFolder = WebsiteFolder(
            id = "existing-date",
            name = "2026-09-19",
            parentId = monthFolder.id,
            sortOrder = 10
        )
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
            addBookmarkedPageToDateHierarchy(
                library = WebsiteLibrary(
                    websites = listOf(existingWebsite),
                    folders = listOf(yearFolder, monthFolder, dateFolder, childFolder)
                ),
                bookmarkDate = LocalDate.of(2026, 9, 19),
                newYearFolderId = "unused-year",
                newMonthFolderId = "unused-month",
                newDateFolderId = "unused-date",
                bookmarkId = "new-site",
                pageTitle = "新网站",
                normalizedUrl = "https://new.example.com"
            )
        )

        assertEquals(4, updated.folders.size)
        val newWebsite = updated.websites.first { website -> website.id == "new-site" }
        assertEquals(dateFolder.id, newWebsite.folderId)
        assertEquals(40, newWebsite.sortOrder)
    }

    /**
     * 验证错误父目录中的同名日期文件夹不会被误用，收藏会另外创建标准三级目录。
     *
     * @return 无返回值；新网站落入用户其他层级的同名文件夹时由JUnit报告失败。
     */
    @Test
    fun misplacedDateFolderIsNotReused() {
        val parent = WebsiteFolder("parent", "父目录")
        val misplacedDate = WebsiteFolder(
            id = "misplaced-date",
            name = "2026-09-19",
            parentId = parent.id
        )
        val updated = requireNotNull(
            addBookmarkedPageToDateHierarchy(
                library = WebsiteLibrary(folders = listOf(parent, misplacedDate)),
                bookmarkDate = LocalDate.of(2026, 9, 19),
                newYearFolderId = "year",
                newMonthFolderId = "month",
                newDateFolderId = "correct-date",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com"
            )
        )

        val correctDate = updated.folders.first { folder -> folder.id == "correct-date" }
        assertEquals("month", correctDate.parentId)
        assertEquals(parent.id, updated.folders.first { it.id == misplacedDate.id }.parentId)
        assertEquals(correctDate.id, updated.websites.single().folderId)
    }

    /**
     * 验证正确月份下存在多个同名日期文件夹时稳定复用排序最小、id最小的一项，不合并用户数据。
     *
     * @return 无返回值；选择规则不稳定或文件夹被删除合并时由JUnit报告失败。
     */
    @Test
    fun duplicateDateFoldersReuseDeterministicFirst() {
        val yearFolder = WebsiteFolder("year", "2026年")
        val monthFolder = WebsiteFolder("month", "9月", parentId = yearFolder.id)
        val later = WebsiteFolder(
            id = "z-folder",
            name = "2026-09-19",
            parentId = monthFolder.id,
            sortOrder = 10
        )
        val selected = WebsiteFolder(
            id = "a-folder",
            name = "2026-09-19",
            parentId = monthFolder.id,
            sortOrder = 10
        )
        val updated = requireNotNull(
            addBookmarkedPageToDateHierarchy(
                library = WebsiteLibrary(
                    folders = listOf(yearFolder, monthFolder, later, selected)
                ),
                bookmarkDate = LocalDate.of(2026, 9, 19),
                newYearFolderId = "unused-year",
                newMonthFolderId = "unused-month",
                newDateFolderId = "unused-date",
                bookmarkId = "bookmark",
                pageTitle = "测试页面",
                normalizedUrl = "https://example.com"
            )
        )

        assertEquals(4, updated.folders.size)
        assertEquals(selected.id, updated.websites.single().folderId)
    }

    /**
     * 验证新文件夹与新网站不能共用同一标识，避免生成无法被仓库保存的跨类型重复节点。
     *
     * @return 无返回值；标识冲突仍返回网站库时由JUnit报告失败。
     */
    @Test
    fun bookmarkRejectsFolderWebsiteIdCollision() {
        val updated = addBookmarkedPageToDateHierarchy(
            library = WebsiteLibrary(),
            bookmarkDate = LocalDate.of(2026, 9, 19),
            newYearFolderId = "same-id",
            newMonthFolderId = "month",
            newDateFolderId = "date",
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
        val yearFolder = WebsiteFolder("year", "2026年")
        val monthFolder = WebsiteFolder("month", "9月", parentId = yearFolder.id)
        val dateFolder = WebsiteFolder(
            id = "date",
            name = "2026-09-19",
            parentId = monthFolder.id
        )
        val existing = WebsiteShortcut(
            id = "maximum",
            title = "极端顺序",
            url = "https://maximum.example.com",
            folderId = dateFolder.id,
            sortOrder = Int.MAX_VALUE
        )
        val updated = addBookmarkedPageToDateHierarchy(
            library = WebsiteLibrary(
                websites = listOf(existing),
                folders = listOf(yearFolder, monthFolder, dateFolder)
            ),
            bookmarkDate = LocalDate.of(2026, 9, 19),
            newYearFolderId = "unused-year",
            newMonthFolderId = "unused-month",
            newDateFolderId = "unused-date",
            bookmarkId = "bookmark",
            pageTitle = "测试页面",
            normalizedUrl = "https://example.com"
        )

        assertNotNull(updated)
        assertEquals(Int.MAX_VALUE, updated?.websites?.first { it.id == "bookmark" }?.sortOrder)
    }

    /**
     * 验证旧版根目录日期文件夹会按各自日期归入同一个年份下的对应月份。
     *
     * @return 无返回值；日期文件夹、内部网站或子目录的归属被错误改写时由JUnit报告失败。
     */
    @Test
    fun legacyRootDateFoldersMoveIntoMatchingYearAndMonth() {
        val septemberDate = WebsiteFolder("september-date", "2026-09-19", sortOrder = 20)
        val octoberDate = WebsiteFolder("october-date", "2026-10-07", sortOrder = 10)
        val childFolder = WebsiteFolder(
            id = "child",
            name = "保留子目录",
            parentId = septemberDate.id
        )
        val website = WebsiteShortcut(
            id = "saved-site",
            title = "已收藏网站",
            url = "https://saved.example.com",
            folderId = septemberDate.id
        )
        var generatedId = 0
        val updated = requireNotNull(
            organizeRootDateFoldersByYearMonth(
                library = WebsiteLibrary(
                    websites = listOf(website),
                    folders = listOf(septemberDate, octoberDate, childFolder)
                ),
                newFolderId = { "generated-${generatedId++}" }
            )
        )

        val yearFolder = updated.folders.single { folder -> folder.name == "2026年" }
        val septemberFolder = updated.folders.single { folder ->
            folder.parentId == yearFolder.id && folder.name == "9月"
        }
        val octoberFolder = updated.folders.single { folder ->
            folder.parentId == yearFolder.id && folder.name == "10月"
        }
        assertEquals(
            septemberFolder.id,
            updated.folders.first { it.id == septemberDate.id }.parentId
        )
        assertEquals(
            octoberFolder.id,
            updated.folders.first { it.id == octoberDate.id }.parentId
        )
        assertEquals(septemberDate.id, updated.folders.first { it.id == childFolder.id }.parentId)
        assertEquals(septemberDate.id, updated.websites.single().folderId)
    }

    /**
     * 验证迁移会复用已经位于正确位置的年份、月份文件夹，不创建重复层级。
     *
     * @return 无返回值；既有年月目录未复用或根日期文件夹未归位时由JUnit报告失败。
     */
    @Test
    fun legacyMigrationReusesExistingYearAndMonthFolders() {
        val yearFolder = WebsiteFolder("year", "2026年")
        val monthFolder = WebsiteFolder("month", "10月", parentId = yearFolder.id)
        val dateFolder = WebsiteFolder("date", "2026-10-07")
        var generatedId = 0
        val updated = requireNotNull(
            organizeRootDateFoldersByYearMonth(
                library = WebsiteLibrary(
                    folders = listOf(yearFolder, monthFolder, dateFolder)
                ),
                newFolderId = { "unused-${generatedId++}" }
            )
        )

        assertEquals(3, updated.folders.size)
        assertEquals(monthFolder.id, updated.folders.first { it.id == dateFolder.id }.parentId)
    }

    /**
     * 验证普通文件夹、无效日期名称和已经嵌套的日期同名文件夹不会被旧数据迁移误动。
     *
     * @return 无返回值；非迁移目标的父目录发生变化时由JUnit报告失败。
     */
    @Test
    fun legacyMigrationIgnoresNonRootOrInvalidDateFolders() {
        val ordinaryFolder = WebsiteFolder("ordinary", "2026-10")
        val invalidDateFolder = WebsiteFolder("invalid", "2026-02-31")
        val nestedDateFolder = WebsiteFolder(
            id = "nested-date",
            name = "2026-10-07",
            parentId = ordinaryFolder.id
        )
        val library = WebsiteLibrary(
            folders = listOf(ordinaryFolder, invalidDateFolder, nestedDateFolder)
        )
        val updated = requireNotNull(
            organizeRootDateFoldersByYearMonth(
                library = library,
                newFolderId = { "unused" }
            )
        )

        assertEquals(library, updated)
    }
}
