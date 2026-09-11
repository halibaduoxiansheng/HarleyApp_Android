package com.example.harleyapp

import com.example.harleyapp.model.MaoQuoteParseResult
import com.example.harleyapp.model.parseMaoQuoteUtf8
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证由用户提供PDF生成的Debug离线资源仍能被App解析器完整读取。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew :app:testDebugUnitTest --tests *MaoQuoteBundledAssetTest`。测试只核对资源
 * 的结构、数量和开发授权标记，不在测试日志中打印任何正文。
 */
class MaoQuoteBundledAssetTest {

    /**
     * 验证Debug资产包含33章、427则内容、逐则出处以及非生产授权标记。
     *
     * @return 无返回值；资源缺失、解析失败、内容漏项或误标为可再分发时由JUnit报告失败。
     */
    @Test
    fun debugAssetContainsCompleteUserProvidedEdition() {
        val assetFile = listOf(
            File("app/src/debug/assets/mao_quotes.txt"),
            File("src/debug/assets/mao_quotes.txt")
        ).firstOrNull(File::isFile)
        assertTrue("Debug Mao quote asset is missing", assetFile != null)

        val parseResult = parseMaoQuoteUtf8(requireNotNull(assetFile).readBytes())
        assertTrue(parseResult is MaoQuoteParseResult.Success)
        val document = (parseResult as MaoQuoteParseResult.Success).document

        assertEquals("毛主席语录", document.metadata.title)
        assertEquals(33, document.chapters.size)
        assertEquals(427, document.quotes.size)
        assertEquals("一、共产党", document.chapters.first().title)
        assertEquals("三十三、学习", document.chapters.last().title)
        assertFalse(document.metadata.redistributionAuthorized)
        assertTrue(document.metadata.source.contains("A9838F41AE38"))
        assertTrue(document.quotes.all { quote -> "\n出处：" in quote.text })

        // 当前内容尚未完成生产再分发许可，只能出现在Debug source set；这项检查避免后续移动文件时
        // 不小心让运行时门禁之外的原始字节进入Release APK。
        val forbiddenReleaseAssets = listOf(
            File("app/src/main/assets/mao_quotes.txt"),
            File("src/main/assets/mao_quotes.txt"),
            File("app/src/release/assets/mao_quotes.txt"),
            File("src/release/assets/mao_quotes.txt")
        )
        assertTrue(forbiddenReleaseAssets.none(File::exists))
    }
}
