package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationSourceLanguage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证实时翻译模型管线中不依赖Android运行时的关键完整性与并发契约。
 *
 * 使用方法：
 * 由`app:testDebugUnitTest`自动执行；测试不联网、不下载模型，也不创建ONNX会话。
 */
class LiveTranslationModelPipelineTest {

    /**
     * 验证固定M2M100清单同时绑定三个ONNX文件、SentencePiece、官方JSON词表、总长度和不可变提交URL。
     *
     * @return 无返回值；文件遗漏、总长度漂移或URL退回可变分支时由JUnit报告失败。
     */
    @Test
    fun pinnedM2m100FilesHaveCompleteImmutableContract() {
        val files = M2m100ModelRepository.PINNED_FILES
        assertEquals(5, files.size)
        assertEquals(M2m100ModelRepository.TOTAL_DOWNLOAD_SIZE_BYTES, files.sumOf { it.sizeBytes })
        assertEquals(
            setOf(
                M2m100ModelRepository.ENCODER_FILE_NAME,
                M2m100ModelRepository.DECODER_FILE_NAME,
                M2m100ModelRepository.DECODER_WITH_PAST_FILE_NAME,
                M2m100ModelRepository.SENTENCEPIECE_FILE_NAME,
                M2m100ModelRepository.VOCABULARY_FILE_NAME
            ),
            files.mapTo(mutableSetOf()) { it.fileName }
        )
        files.forEach { file ->
            assertTrue(file.url.contains("cce333fc730dab2125fbc054a18fa2565db31095"))
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(file.sizeBytes > 0L)
        }
    }

    /** 验证英日官方piece经过`vocab.json`映射后生成精确encoder golden向量。 */
    @Test
    fun officialVocabularyIdsBuildEnglishAndJapaneseEncoderGoldenVectors() {
        val vocabulary = goldenVocabulary()

        val englishTextIds = mapM2m100SentencePiecesToModelIds(
            sentencePieces = listOf("▁Hello", "▁world", "!"),
            vocabulary = vocabulary
        )
        assertArrayEquals(
            longArrayOf(128_022L, 65_761L, 55_185L, 30L, 2L),
            buildM2m100EncoderInput(
                textModelIds = englishTextIds,
                sourceLanguage = LiveTranslationSourceLanguage.ENGLISH
            )
        )

        val japaneseTextIds = mapM2m100SentencePiecesToModelIds(
            sentencePieces = listOf("▁今", "日は", "いい", "天", "気", "ですね", "。"),
            vocabulary = vocabulary
        )
        assertArrayEquals(
            longArrayOf(
                128_046L,
                20_511L,
                30_299L,
                13_223L,
                2_801L,
                8_271L,
                23_067L,
                37L,
                2L
            ),
            buildM2m100EncoderInput(
                textModelIds = japaneseTextIds,
                sourceLanguage = LiveTranslationSourceLanguage.JAPANESE
            )
        )
    }

    /** 验证中文官方模型ID反向解码正确，并且UNK及控制ID不会显示替换符乱码。 */
    @Test
    fun officialChineseIdsDecodeWithoutUnknownReplacementGibberish() {
        val vocabulary = goldenVocabulary()

        assertEquals(
            "你好世界。",
            decodeM2m100ModelTokenIds(
                modelTokenIds = longArrayOf(
                    M2m100Tokenizer.EOS_TOKEN_ID,
                    M2m100Tokenizer.CHINESE_LANGUAGE_TOKEN_ID,
                    78_023L,
                    3L,
                    3L,
                    3_051L,
                    7_052L,
                    37L,
                    128_111L,
                    999_999L
                ),
                vocabulary = vocabulary
            )
        )
    }

    /**
     * 验证翻译输出只在完整EOS结束且token及正文均有效时发布。
     *
     * @return 无返回值；未知token、截断生成、纯标点或重复循环被当成可展示译文时由JUnit报告失败。
     */
    @Test
    fun generatedTranslationQualityGateRejectsBrokenOrNoisyOutput() {
        assertEquals(
            "你好!!",
            requireUsableM2m100Translation(
                modelTokenIds = longArrayOf(78_023L, 3_051L),
                reachedEndOfSentence = true,
                decodedText = "你好!!!!!"
            )
        )

        val invalidCases = listOf(
            Triple(longArrayOf(78_023L, 3L), true, "你好"),
            Triple(longArrayOf(78_023L), false, "你"),
            Triple(longArrayOf(37L), true, "！？……"),
            Triple(LongArray(8) { 78_023L }, true, "你你你你你你你你")
        )
        invalidCases.forEach { (tokens, reachedEos, text) ->
            assertTrue(
                runCatching {
                    requireUsableM2m100Translation(tokens, reachedEos, text)
                }.isFailure
            )
        }
    }

    /** 验证JSON解析保留大小写不同piece，并把连续缺失piece融合为单个UNK。 */
    @Test
    fun vocabularyJsonIsCaseSensitiveAndFusesUnknownPieces() {
        val vocabulary = parseM2m100VocabularyJson(
            "{\"<s>\":0,\"<pad>\":1,\"</s>\":2,\"<unk>\":3," +
                "\"\\u2581a\":8,\"\\u2581A\":18}"
        )

        assertEquals(8, vocabulary.modelIdForPiece("▁a"))
        assertEquals(18, vocabulary.modelIdForPiece("▁A"))
        assertArrayEquals(
            longArrayOf(3L, 8L),
            mapM2m100SentencePiecesToModelIds(
                sentencePieces = listOf("missing-one", "missing-two", "▁a"),
                vocabulary = vocabulary
            )
        )
    }

    /** 验证常见英日字幕规范化覆盖空白、全角标点、组合字符、控制字符和emoji。 */
    @Test
    fun sentencePieceNormalizationMatchesCommonOfficialCases() {
        assertEquals("▁Hello,▁world!", normalizeM2m100SentencePieceText("  Ｈｅｌｌｏ，   world！  "))
        assertEquals("▁Café", normalizeM2m100SentencePieceText("Cafe\u0301"))
        assertEquals("▁カタカナ▁映画", normalizeM2m100SentencePieceText("ｶﾀｶﾅ　映画"))
        assertEquals("▁a▁b", normalizeM2m100SentencePieceText("a \u200Eb"))
        assertEquals("▁emoji▁😀", normalizeM2m100SentencePieceText("emoji 😀"))
    }

    /** 验证encoder截断始终保留源语言前缀和EOS，并固定英日中语言ID。 */
    @Test
    fun encoderControlTokenContractCannotDrift() {
        assertEquals(128_022L, M2m100Tokenizer.ENGLISH_LANGUAGE_TOKEN_ID)
        assertEquals(128_046L, M2m100Tokenizer.JAPANESE_LANGUAGE_TOKEN_ID)
        assertEquals(128_102L, M2m100Tokenizer.CHINESE_LANGUAGE_TOKEN_ID)
        assertEquals(
            listOf(128_022L, 11L, 12L, 2L),
            M2m100Tokenizer.truncateEncoderInput(
                longArrayOf(128_022L, 11L, 12L, 13L, 14L, 2L),
                maxTokenCount = 4
            ).toList()
        )
    }

    /** 验证只有精确的HTTP Range起点与总长度才允许追加到模型片段。 */
    @Test
    fun rangeResumeRejectsMismatchedServerResponses() {
        assertTrue(isExpectedContentRange("bytes 100-199/1000", 100L, 1000L))
        assertFalse(isExpectedContentRange("bytes 99-199/1000", 100L, 1000L))
        assertFalse(isExpectedContentRange("bytes 100-199/999", 100L, 1000L))
        assertFalse(isExpectedContentRange(null, 100L, 1000L))
    }

    /**
     * 验证缓存使用完整正文而不是`String.hashCode()`，避免等长哈希碰撞复用错误译文。
     *
     * @return 无返回值；两个已知哈希碰撞字符串产生相同缓存身份时由JUnit报告失败。
     */
    @Test
    fun cacheKeyKeepsFullTextWhenStringHashesCollide() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals("Aa".length, "BB".length)

        val first = LiveTranslationCacheKey(
            sourceLanguage = LiveTranslationSourceLanguage.ENGLISH,
            normalizedText = "Aa"
        )
        val second = LiveTranslationCacheKey(
            sourceLanguage = LiveTranslationSourceLanguage.ENGLISH,
            normalizedText = "BB"
        )

        assertNotEquals(first, second)
    }

    /**
     * 验证安装标记同时绑定格式版本、模型发行版本、归档及三个必需文件哈希。
     *
     * @return 无返回值；字段遗漏、顺序漂移或许可证哈希不参与标记时由JUnit报告失败。
     */
    @Test
    fun installMarkerBindsVersionAndEveryRequiredArchiveFile() {
        val marker = buildAsrInstallMarker(
            schemaVersion = 2,
            modelVersion = "sense-voice-fixed-version",
            archiveSha256 = "archive-hash",
            modelSha256 = "model-hash",
            tokensSha256 = "tokens-hash",
            licenseSha256 = "license-hash"
        )

        assertEquals(
            listOf(
                "schema_version=2",
                "model_version=sense-voice-fixed-version",
                "archive_sha256=archive-hash",
                "model_sha256=model-hash",
                "tokens_sha256=tokens-hash",
                "license_sha256=license-hash"
            ).joinToString("\n"),
            marker
        )
        assertNotEquals(
            marker,
            buildAsrInstallMarker(
                schemaVersion = 2,
                modelVersion = "sense-voice-fixed-version",
                archiveSha256 = "archive-hash",
                modelSha256 = "model-hash",
                tokensSha256 = "tokens-hash",
                licenseSha256 = "changed-license-hash"
            )
        )
    }

    /**
     * 验证迟到的旧任务结束回调不能清除新任务，当前任务自身结束时才释放占用。
     *
     * @return 无返回值；身份比较退化为值比较或旧回调清空新任务时由JUnit报告失败。
     */
    @Test
    fun staleCompletionCannotClearCurrentPreparationTask() {
        val staleTask = TestTask(id = 7)
        val currentTask = TestTask(id = 7)

        assertEquals(staleTask, currentTask)
        assertSame(currentTask, clearTaskIfOwner(currentTask, staleTask))
        assertNull(clearTaskIfOwner(currentTask, currentTask))
    }

    /**
     * 创建仅含本组官方golden token的稀疏词表。
     *
     * @return ID取自Meta M2M100-418M官方`vocab.json`的测试词表。
     */
    private fun goldenVocabulary(): M2m100ModelVocabulary {
        return parseM2m100VocabularyJson(
            "{" +
                "\"<s>\":0,\"<pad>\":1,\"</s>\":2,\"<unk>\":3," +
                "\"!\":30,\"。\":37,\"天\":2801,\"好\":3051,\"世界\":7052," +
                "\"気\":8271,\"いい\":13223,\"▁今\":20511,\"ですね\":23067," +
                "\"日は\":30299,\"▁world\":55185,\"▁Hello\":65761,\"▁你\":78023" +
                "}"
        )
    }

    /** 仅用于证明任务释放必须比较对象身份，而不能依赖值相等。 */
    private data class TestTask(val id: Int)
}
