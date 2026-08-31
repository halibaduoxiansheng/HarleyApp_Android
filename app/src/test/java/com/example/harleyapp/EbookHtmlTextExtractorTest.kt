package com.example.harleyapp

import com.example.harleyapp.data.EbookHtmlTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证大型电子书HTML转纯文本时的正文保留、危险内容忽略和进度准确性。 */
class EbookHtmlTextExtractorTest {

    /**
     * 验证段落、换行、字符实体与代理对能够正确转换，同时忽略脚本和样式正文。
     *
     * @return 无返回值；纯文本内容或段落边界错误时由JUnit报告失败。
     */
    @Test
    fun visibleTextAndEntitiesAreExtractedWithoutScriptContent() {
        val source = """
            <html><body>
            <p>第一章&nbsp;开始</p>
            <script>window.bad = '不应出现';</script>
            <style>.hidden { display:none; }</style>
            <div>A &amp; B<br>下一行 &#x1F600;</div>
            </body></html>
        """.trimIndent()

        val text = EbookHtmlTextExtractor.extract(source)

        assertEquals("第一章 开始\n\nA & B\n下一行 😀", text)
    }

    /**
     * 验证字符扫描进度从0开始、单调递增，并以完整字符数结束。
     *
     * @return 无返回值；大正文进度倒退、越界或没有到达终点时由JUnit报告失败。
     */
    @Test
    fun characterProgressIsMonotonicAndCompletesAtSourceLength() {
        val source = buildString {
            repeat(80_000) { index ->
                append("<p>第").append(index).append("段 &amp; 正文</p>")
            }
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        val text = EbookHtmlTextExtractor.extract(source) { completed, total ->
            progress += completed to total
        }

        assertTrue(text.contains("第79999段 & 正文"))
        assertEquals(0, progress.first().first)
        assertEquals(source.length, progress.last().first)
        assertTrue(progress.all { (_, total) -> total == source.length })
        assertTrue(progress.zipWithNext().all { (previous, next) -> next.first >= previous.first })
    }
}
