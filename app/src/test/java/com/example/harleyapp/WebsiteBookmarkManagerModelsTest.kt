package com.example.harleyapp

import com.example.harleyapp.model.WebsiteFolder
import com.example.harleyapp.model.WebsiteLibrary
import com.example.harleyapp.model.WebsiteShortcut
import com.example.harleyapp.ui.screens.reorderWebsiteNodes
import com.example.harleyapp.ui.screens.updateWebsiteNodePinned
import com.example.harleyapp.ui.screens.websiteNodesAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证网站收藏管理页的置顶与组内拖动纯状态转换，不依赖Compose或Android设备。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew testDebugUnitTest`，JUnit会直接调用管理页公开给测试模块的内部函数。
 */
class WebsiteBookmarkManagerModelsTest {

    /**
     * 验证置顶文件夹和置顶网站共同位于普通节点前方，并继续按手动顺序混合排列。
     *
     * @return 无返回值；置顶优先级、文件夹/网站混排或普通组顺序错误时由JUnit报告失败。
     */
    @Test
    fun websiteNodesOrderPinnedMixedNodesFirst() {
        val library = WebsiteLibrary(
            folders = listOf(
                WebsiteFolder("regular-folder", "普通文件夹", sortOrder = 0),
                WebsiteFolder("pinned-folder", "置顶文件夹", sortOrder = 20, isPinned = true)
            ),
            websites = listOf(
                website("regular-site", sortOrder = 10),
                website("pinned-site", sortOrder = 10, isPinned = true)
            )
        )

        assertEquals(
            listOf(
                "website:pinned-site",
                "folder:pinned-folder",
                "folder:regular-folder",
                "website:regular-site"
            ),
            websiteNodesAt(library, parentId = null).map { node -> node.key }
        )
    }

    /**
     * 验证置顶文件夹向下拖过普通组时只移动到置顶组末尾，普通组顺序值保持不变。
     *
     * @return 无返回值；置顶节点跨组、普通节点被改写或文件夹网站不能混排时由JUnit报告失败。
     */
    @Test
    fun reorderPinnedNodeClampsDragToPinnedGroup() {
        val pinnedFolder = WebsiteFolder(
            id = "pinned-folder",
            name = "置顶文件夹",
            sortOrder = 0,
            isPinned = true
        )
        val regular = website("regular", sortOrder = 30)
        val library = WebsiteLibrary(
            folders = listOf(pinnedFolder),
            websites = listOf(
                website("pinned-site", sortOrder = 10, isPinned = true),
                regular
            )
        )

        val reordered = reorderWebsiteNodes(
            library = library,
            parentId = null,
            draggedKey = "folder:${pinnedFolder.id}",
            targetIndex = 2
        )

        assertEquals(
            listOf("website:pinned-site", "folder:pinned-folder", "website:regular"),
            websiteNodesAt(reordered, parentId = null).map { node -> node.key }
        )
        assertEquals(regular.sortOrder, reordered.websites.first { it.id == regular.id }.sortOrder)
    }

    /**
     * 验证普通网站向上拖过置顶组时只移动到普通组首位，置顶节点仍固定在最前方。
     *
     * @return 无返回值；普通节点进入置顶组或普通组内部排序失败时由JUnit报告失败。
     */
    @Test
    fun reorderRegularNodeCannotCrossPinnedBoundary() {
        val library = WebsiteLibrary(
            websites = listOf(
                website("pinned", sortOrder = 0, isPinned = true),
                website("regular-first", sortOrder = 0),
                website("regular-second", sortOrder = 10)
            )
        )

        val reordered = reorderWebsiteNodes(
            library = library,
            parentId = null,
            draggedKey = "website:regular-second",
            targetIndex = 0
        )

        assertEquals(
            listOf("website:pinned", "website:regular-second", "website:regular-first"),
            websiteNodesAt(reordered, parentId = null).map { node -> node.key }
        )
    }

    /**
     * 验证文件夹和网站都可独立切换置顶，同时无效节点不会创建伪变化。
     *
     * @return 无返回值；目标外节点被修改、取消置顶失败或无效key产生新对象时由JUnit报告失败。
     */
    @Test
    fun updatePinnedStateChangesOnlyRequestedNode() {
        val folder = WebsiteFolder("folder", "文件夹")
        val website = website("site")
        val library = WebsiteLibrary(
            folders = listOf(folder),
            websites = listOf(website)
        )

        val folderPinned = updateWebsiteNodePinned(library, "folder:${folder.id}", true)
        val bothPinned = updateWebsiteNodePinned(folderPinned, "website:${website.id}", true)
        val websiteUnpinned = updateWebsiteNodePinned(bothPinned, "website:${website.id}", false)

        assertTrue(folderPinned.folders.single().isPinned)
        assertFalse(folderPinned.websites.single().isPinned)
        assertTrue(bothPinned.websites.single().isPinned)
        assertFalse(websiteUnpinned.websites.single().isPinned)
        assertSame(library, updateWebsiteNodePinned(library, "missing", true))
    }

    /**
     * 构造排序测试使用的网站。
     *
     * @param id 网站稳定标识。
     * @param sortOrder 当前分组内顺序。
     * @param isPinned 是否属于置顶组。
     * @return 可直接加入WebsiteLibrary的测试网站。
     */
    private fun website(
        id: String,
        sortOrder: Int = 0,
        isPinned: Boolean = false
    ): WebsiteShortcut {
        return WebsiteShortcut(
            id = id,
            title = "网站-$id",
            url = "https://example.com/$id",
            sortOrder = sortOrder,
            isPinned = isPinned
        )
    }
}
