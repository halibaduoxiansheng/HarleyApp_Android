package com.example.harleyapp.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证电子书后台朗读中不依赖Android运行时的断点恢复与会话注册边界。
 *
 * 使用方法：
 * 由Gradle的app:testDebugUnitTest任务自动执行。测试只操作纯字符串和进程内注册表，不启动TTS、
 * Service或通知，因此失败时可以优先定位为断点算法或会话交接回归，而不是设备环境差异。
 *
 * @return 测试类不直接返回结果；断言失败时由JUnit报告具体用例。
 */
class EbookReadAloudPlaybackTest {

    /**
     * 验证英文暂停发生在单词中间时，会退回当前单词开头，避免继续播放时跳过半个单词。
     *
     * @return 无返回值；恢复位置不是wonderful首字符时断言失败。
     */
    @Test
    fun englishResumeOffsetReturnsToCurrentWordStart() {
        val text = "Hello wonderful world."

        val result = findEbookReadAloudResumeOffset(
            text = text,
            reportedRangeStart = text.indexOf("wonderful") + 5
        )

        assertEquals(text.indexOf("wonderful"), result)
    }

    /**
     * 验证中文暂停发生在句中时，会回退到最近句末后的句首，以允许少量重复换取不漏读正文。
     *
     * @return 无返回值；恢复位置没有落在第二句句首时断言失败。
     */
    @Test
    fun chineseResumeOffsetReturnsToCurrentSentenceStart() {
        val text = "第一句。第二句话还没读完。"

        val result = findEbookReadAloudResumeOffset(
            text = text,
            reportedRangeStart = text.indexOf('还')
        )

        assertEquals(text.indexOf("第二句"), result)
    }

    /**
     * 验证系统回报的位置落在Emoji代理对中间时，恢复位置会退回完整字符开头，并同时限制越界输入。
     *
     * @return 无返回值；出现拆分UTF-16代理项、负数偏移或超出正文长度时断言失败。
     */
    @Test
    fun resumeOffsetNeverSplitsSurrogatePairOrLeavesTextBounds() {
        val text = "Say 😀hello"
        val lowSurrogateIndex = text.indexOf('\uD83D') + 1

        assertEquals(
            text.indexOf('\uD83D'),
            findEbookReadAloudResumeOffset(text, lowSurrogateIndex)
        )
        assertEquals(0, findEbookReadAloudResumeOffset(text, -200))
        assertEquals(text.length, findEbookReadAloudResumeOffset(text, text.length + 200))
        assertEquals(0, findEbookReadAloudResumeOffset("", 50))
    }

    /**
     * 验证页面把朗读配置交给服务时，初始页会被限制、页面列表会生成快照且token只能消费一次。
     *
     * 使用方法：
     * 用可变列表注册会话，注册后再修改原列表，然后模拟服务调用consume。取得的配置应保持注册时
     * 的页数，且重复消费同一token必须返回null，防止两个服务启动请求重复朗读同一会话。
     *
     * @return 无返回值；配置快照、页码限制或一次性消费语义变化时断言失败。
     */
    @Test
    fun registeredSessionIsClampedCopiedAndConsumedOnlyOnce() {
        val sourcePages = mutableListOf(
            EbookReadAloudPage("第一页"),
            EbookReadAloudPage("第二页")
        )
        val token = EbookReadAloudPlayback.register(
            EbookReadAloudConfig(
                bookId = "book-1",
                title = "测试书",
                author = "作者",
                pages = sourcePages,
                initialPage = 99,
                naturalReadingEnabled = true
            )
        )
        sourcePages += EbookReadAloudPage("注册后新增页")

        val registeredSession = EbookReadAloudPlayback.consume(token)

        assertNotNull(registeredSession)
        assertEquals(1, registeredSession?.config?.initialPage)
        assertEquals(2, registeredSession?.config?.pages?.size)
        assertNull(EbookReadAloudPlayback.consume(token))
        assertFalse(EbookReadAloudPlayback.unregister(token))
    }

    /**
     * 验证注册入口拒绝空书籍ID、空标题和空分页，避免服务进入无法建立媒体信息的非法会话。
     *
     * @return 无返回值；任一非法配置未抛出IllegalArgumentException时断言失败。
     */
    @Test
    fun registerRejectsIncompletePlaybackConfig() {
        val validPage = listOf(EbookReadAloudPage("正文"))

        assertTrue(
            runCatching {
                EbookReadAloudPlayback.register(
                    EbookReadAloudConfig("", "书名", "", validPage, 0, false)
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                EbookReadAloudPlayback.register(
                    EbookReadAloudConfig("book-2", "", "", validPage, 0, false)
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                EbookReadAloudPlayback.register(
                    EbookReadAloudConfig("book-3", "书名", "", emptyList(), 0, false)
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
    }
}
