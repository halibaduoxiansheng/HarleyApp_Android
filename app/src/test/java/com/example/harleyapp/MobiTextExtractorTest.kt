package com.example.harleyapp

import com.example.harleyapp.data.MobiParseException
import com.example.harleyapp.data.MobiParseFailure
import com.example.harleyapp.data.MobiTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 验证无DRM MOBI容器、PalmDOC正文解压和安全拒绝边界。 */
class MobiTextExtractorTest {

    /**
     * 验证未压缩、UTF-8编码的标准MOBI正文可以完整提取。
     *
     * @return 无返回值；文字丢失或容器字段解析错误时由JUnit报告失败。
     */
    @Test
    fun uncompressedUtf8MobiExtractsText() {
        val expected = "<html><body>第一章 Hello MOBI</body></html>"

        withTemporaryMobi(buildMobi(expected, compression = COMPRESSION_NONE)) { file ->
            assertEquals(expected, MobiTextExtractor.extract(file))
        }
    }

    /**
     * 验证PalmDOC压缩记录可以恢复中英文混合正文。
     *
     * @return 无返回值；字面量段解压或UTF-8解码错误时由JUnit报告失败。
     */
    @Test
    fun palmDocCompressedMobiExtractsText() {
        val expected = "<html><body>第二章 PalmDOC 正文测试</body></html>"
        val compressed = encodeAsPalmDocLiteralRuns(expected.toByteArray(Charsets.UTF_8))

        withTemporaryMobi(
            bytes = buildMobi(
                text = expected,
                compression = COMPRESSION_PALMDOC,
                storedTextRecord = compressed
            )
        ) { file ->
            assertEquals(expected, MobiTextExtractor.extract(file))
        }
    }

    /**
     * 验证MOBI记录进度从零开始并准确结束在正文记录总数。
     *
     * @return 无返回值；记录进度倒退、总数变化或没有到达终点时由JUnit报告失败。
     */
    @Test
    fun mobiRecordProgressIsMonotonicAndComplete() {
        val progress = mutableListOf<Pair<Int, Int>>()

        withTemporaryMobi(buildMobi("<p>进度正文</p>", compression = COMPRESSION_NONE)) { file ->
            MobiTextExtractor.extract(file) { completed, total ->
                progress += completed to total
            }
        }

        assertEquals(0, progress.first().first)
        assertEquals(1 to 1, progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> next.first >= previous.first })
        assertTrue(progress.all { (_, total) -> total == 1 })
    }

    /**
     * 验证真实Kindle文件常见的记录尾随索引与多字节重叠标志会在解压前正确移除。
     *
     * @return 无返回值；0xF2字段偏移或反向变长整数处理回归时由JUnit报告失败。
     */
    @Test
    fun mobiTrailingRecordDataIsRemovedBeforeDecompression() {
        val expected = "<html><body>Trailing data test</body></html>"
        val compressed = encodeAsPalmDocLiteralRuns(expected.toByteArray(Charsets.UTF_8))
        val recordWithTrailers = compressed + byteArrayOf(
            0x00,
            0x86.toByte(),
            0x80.toByte(),
            0x03,
            0x84.toByte()
        )

        withTemporaryMobi(
            buildMobi(
                text = expected,
                compression = COMPRESSION_PALMDOC,
                storedTextRecord = recordWithTrailers,
                extraDataFlags = 3
            )
        ) { file ->
            assertEquals(expected, MobiTextExtractor.extract(file))
        }
    }

    /**
     * 验证DRM标志会被明确识别，解析器不会尝试绕过加密或登记不可阅读书籍。
     *
     * @return 无返回值；未拒绝或失败分类错误时由JUnit报告失败。
     */
    @Test
    fun drmProtectedMobiIsRejectedExplicitly() {
        withTemporaryMobi(
            buildMobi("protected", compression = COMPRESSION_NONE, encryptionType = 2)
        ) { file ->
            val error = runCatching { MobiTextExtractor.extract(file) }.exceptionOrNull()

            assertTrue(error is MobiParseException)
            assertEquals(MobiParseFailure.DRM_PROTECTED, (error as MobiParseException).failure)
        }
    }

    /**
     * 使用外部提供的公开无DRM样本执行真实MOBI兼容性检查。
     *
     * 使用方法：
     * 本地验证时设置HARLEY_MOBI_SAMPLE为样本绝对路径后运行本测试；普通CI没有样本时安全跳过。
     * 该机制避免把第三方书籍二进制提交进仓库，同时允许发布前验证真实Kindle文件而非只测合成数据。
     *
     * @return 无返回值；提供样本后正文过短或没有可见文字时由JUnit报告失败。
     */
    @Test
    fun optionalPublicDomainMobiSampleExtractsReadableBody() {
        val samplePath = System.getenv("HARLEY_MOBI_SAMPLE").orEmpty()
        if (samplePath.isBlank()) return

        val extracted = MobiTextExtractor.extract(File(samplePath))

        assertTrue(extracted.length > 10_000)
        assertTrue(extracted.any(Char::isLetter))
    }

    /**
     * 构造包含一个头记录和一个正文记录的最小有效MOBI/PDB文件。
     *
     * @param text 解压后的完整UTF-8正文。
     * @param compression PalmDOC压缩标志，1为未压缩，2为PalmDOC标准压缩。
     * @param encryptionType PalmDOC加密标志，0表示无DRM。
     * @param storedTextRecord 实际写入正文记录的字节；默认直接写入UTF-8正文。
     * @param extraDataFlags 正文记录尾随数据标志；0表示没有尾随索引或多字节重叠区。
     * @return 可供解析器读取的完整测试文件字节。
     */
    private fun buildMobi(
        text: String,
        compression: Int,
        encryptionType: Int = 0,
        storedTextRecord: ByteArray = text.toByteArray(Charsets.UTF_8),
        extraDataFlags: Int = 0
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val headerRecord = ByteArray(
            if (extraDataFlags == 0) MOBI_MIN_RECORD_ZERO_BYTES else MOBI_EXTENDED_RECORD_ZERO_BYTES
        )
        writeUnsignedShort(headerRecord, 0, compression)
        writeUnsignedInt(headerRecord, 4, textBytes.size)
        writeUnsignedShort(headerRecord, 8, 1)
        writeUnsignedShort(headerRecord, 10, 4_096)
        writeUnsignedShort(headerRecord, 12, encryptionType)
        "MOBI".toByteArray(Charsets.US_ASCII).copyInto(headerRecord, destinationOffset = 16)
        writeUnsignedInt(headerRecord, 20, headerRecord.size - PALMDOC_HEADER_BYTES)
        writeUnsignedInt(headerRecord, 28, UTF8_CODE_PAGE)
        if (extraDataFlags != 0) {
            writeUnsignedShort(headerRecord, MOBI_EXTRA_DATA_FLAGS_OFFSET, extraDataFlags)
        }

        val firstRecordOffset = PDB_HEADER_BYTES + PDB_RECORD_ENTRY_BYTES * 2
        val secondRecordOffset = firstRecordOffset + headerRecord.size
        return ByteArray(secondRecordOffset + storedTextRecord.size).apply {
            writeUnsignedShort(this, 76, 2)
            writeUnsignedInt(this, 78, firstRecordOffset)
            writeUnsignedInt(this, 86, secondRecordOffset)
            headerRecord.copyInto(this, destinationOffset = firstRecordOffset)
            storedTextRecord.copyInto(this, destinationOffset = secondRecordOffset)
        }
    }

    /**
     * 使用PalmDOC的1至8字节字面量指令编码任意字节，专门用于稳定验证解压器。
     *
     * @param source 原始正文UTF-8字节。
     * @return 不依赖回溯压缩、但完全符合PalmDOC格式的压缩记录。
     */
    private fun encodeAsPalmDocLiteralRuns(source: ByteArray): ByteArray {
        return buildList<Byte> {
            source.toList().chunked(8).forEach { chunk ->
                add(chunk.size.toByte())
                addAll(chunk)
            }
        }.toByteArray()
    }

    /**
     * 把测试字节写入临时文件、执行断言并保证文件清理。
     *
     * @param bytes 完整MOBI文件字节。
     * @param block 取得临时文件后执行的测试逻辑。
     * @return 无返回值。
     */
    private fun withTemporaryMobi(bytes: ByteArray, block: (File) -> Unit) {
        val file = kotlin.io.path.createTempFile(prefix = "harley_mobi_", suffix = ".mobi").toFile()
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    /** 向字节数组指定位置写入大端无符号16位整数。 */
    private fun writeUnsignedShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    /** 向字节数组指定位置写入大端32位整数。 */
    private fun writeUnsignedInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private companion object {
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_PALMDOC = 2
        const val PDB_HEADER_BYTES = 78
        const val PDB_RECORD_ENTRY_BYTES = 8
        const val PALMDOC_HEADER_BYTES = 16
        const val MOBI_MIN_RECORD_ZERO_BYTES = 32
        const val MOBI_EXTENDED_RECORD_ZERO_BYTES = 248
        const val MOBI_EXTRA_DATA_FLAGS_OFFSET = 0xF2
        const val UTF8_CODE_PAGE = 65_001
    }
}
