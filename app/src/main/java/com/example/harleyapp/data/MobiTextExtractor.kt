package com.example.harleyapp.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.math.min

/**
 * MOBI解析失败的稳定原因。
 *
 * 使用方法：
 * [MobiTextExtractor]解析失败时会通过[MobiParseException.failure]返回本枚举，电子书仓库据此向
 * 用户展示准确原因，避免把DRM、特殊压缩和文件损坏统一显示成模糊的“导入失败”。
 */
internal enum class MobiParseFailure {
    DRM_PROTECTED,
    UNSUPPORTED_COMPRESSION,
    UNSUPPORTED_ENCODING,
    TEXT_TOO_LARGE,
    INVALID_FILE
}

/**
 * MOBI解析过程使用的受控异常。
 *
 * @param failure 可供页面转换为中文反馈的稳定失败类型。
 * @param detail 仅供英文日志定位问题的详细信息，不直接显示给用户。
 */
internal class MobiParseException(
    val failure: MobiParseFailure,
    detail: String
) : IllegalArgumentException(detail)

/**
 * 在本机提取无DRM MOBI、AZW与AZW3中的可阅读HTML正文。
 *
 * 使用方法：
 * 电子书仓库确认扩展名属于Kindle格式后调用[extract]。解析器读取Palm Database记录表、
 * PalmDOC头和MOBI头，支持未压缩正文以及最常见的PalmDOC压缩；解析全程限制记录数量、单记录
 * 大小和最终正文大小，避免损坏文件造成越界读取或异常内存占用。
 *
 * 当前不会绕过Kindle DRM，也不支持较少见的HUFF/CDIC压缩。遇到这些文件会返回明确失败原因，
 * 不会把原文件登记成无法打开的书籍。
 */
internal object MobiTextExtractor {

    /**
     * 提取一本Kindle容器中的完整正文。
     *
     * @param file 已复制到App受控目录且不超过电子书总大小上限的原文件。
     * @param onRecordProgress 正文记录解析进度回调；第一个参数是已完成记录数，第二个参数是
     * 正文记录总数。默认空实现，已有调用方无需关心进度也可继续直接调用。
     * @return 按MOBI声明字符集解码后的HTML正文；上层可继续转换为纯文本并分页。
     * @throws MobiParseException 文件受DRM保护、压缩或编码不支持、正文过大、容器损坏时抛出。
     */
    fun extract(
        file: File,
        onRecordProgress: (completedRecords: Int, totalRecords: Int) -> Unit = { _, _ -> }
    ): String {
        if (!file.isFile || file.length() < MIN_PDB_BYTES) {
            fail(MobiParseFailure.INVALID_FILE, "MOBI file is missing or too small")
        }

        RandomAccessFile(file, "r").use { input ->
            val fileLength = input.length()
            input.seek(PDB_RECORD_COUNT_OFFSET)
            val recordCount = input.readUnsignedShort()
            if (recordCount < MIN_RECORD_COUNT || recordCount > MAX_RECORD_COUNT) {
                fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI record count: $recordCount")
            }

            val recordTableEnd = PDB_HEADER_BYTES + recordCount.toLong() * PDB_RECORD_ENTRY_BYTES
            if (recordTableEnd > fileLength) {
                fail(MobiParseFailure.INVALID_FILE, "MOBI record table exceeds file length")
            }

            val recordOffsets = LongArray(recordCount)
            repeat(recordCount) { index ->
                input.seek(PDB_HEADER_BYTES + index.toLong() * PDB_RECORD_ENTRY_BYTES)
                recordOffsets[index] = input.readInt().toLong() and UNSIGNED_INT_MASK
            }
            validateRecordOffsets(recordOffsets, recordTableEnd, fileLength)

            val headerRecord = readRecord(
                input = input,
                start = recordOffsets[0],
                end = recordOffsets.getOrElse(1) { fileLength },
                maximumBytes = MAX_HEADER_RECORD_BYTES,
                label = "header"
            )
            val header = parseHeader(headerRecord, recordCount)
            // PalmDOC头已经声明了解压后的准确正文长度。一次性分配最终数组可避免
            // ByteArrayOutputStream扩容及toByteArray再次复制大正文，显著降低长篇小说导入峰值内存。
            val output = ByteArray(header.textLength)
            var outputSize = 0
            val progressInterval = (header.textRecordCount / MAX_PROGRESS_CALLBACKS)
                .coerceAtLeast(1)
            onRecordProgress(0, header.textRecordCount)

            for (recordIndex in 1..header.textRecordCount) {
                val rawRecord = readRecord(
                    input = input,
                    start = recordOffsets[recordIndex],
                    end = recordOffsets.getOrElse(recordIndex + 1) { fileLength },
                    maximumBytes = MAX_COMPRESSED_RECORD_BYTES,
                    label = "text_$recordIndex"
                )
                val contentRecord = removeTrailingRecordData(rawRecord, header.extraDataFlags)
                val remainingBytes = header.textLength - outputSize
                if (remainingBytes <= 0) break

                val decodedRecord = when (header.compression) {
                    PALMDOC_COMPRESSION_NONE -> contentRecord
                    PALMDOC_COMPRESSION_STANDARD -> decompressPalmDoc(
                        source = contentRecord,
                        maximumOutputBytes = min(remainingBytes, MAX_DECOMPRESSED_RECORD_BYTES)
                    )
                    PALMDOC_COMPRESSION_HUFF -> fail(
                        MobiParseFailure.UNSUPPORTED_COMPRESSION,
                        "HUFF/CDIC-compressed MOBI is not supported"
                    )
                    else -> fail(
                        MobiParseFailure.UNSUPPORTED_COMPRESSION,
                        "Unknown MOBI compression: ${header.compression}"
                    )
                }
                val writableBytes = min(decodedRecord.size, remainingBytes)
                decodedRecord.copyInto(
                    destination = output,
                    destinationOffset = outputSize,
                    startIndex = 0,
                    endIndex = writableBytes
                )
                outputSize += writableBytes
                if (
                    recordIndex == header.textRecordCount ||
                    recordIndex % progressInterval == 0
                ) {
                    onRecordProgress(recordIndex, header.textRecordCount)
                }
            }

            if (outputSize != header.textLength) {
                fail(
                    MobiParseFailure.INVALID_FILE,
                    "MOBI text length mismatch: expected ${header.textLength}, got $outputSize"
                )
            }
            return decodeText(output, outputSize, header.textEncoding)
                .replace("\u0000", "")
                .trim()
                .ifBlank {
                    fail(MobiParseFailure.INVALID_FILE, "MOBI text is empty")
                }
        }
    }

    /**
     * 读取PalmDOC与MOBI头中的解析参数。
     *
     * @param bytes PDB的第一条记录。
     * @param recordCount PDB记录总数，用于约束正文记录数量。
     * @return 已验证的压缩方式、正文长度、记录数量、字符集和尾随数据标志。
     */
    private fun parseHeader(bytes: ByteArray, recordCount: Int): MobiHeader {
        if (bytes.size < MIN_MOBI_HEADER_RECORD_BYTES) {
            fail(MobiParseFailure.INVALID_FILE, "MOBI header record is too small")
        }
        val compression = readUnsignedShort(bytes, PALMDOC_COMPRESSION_OFFSET)
        val textLengthLong = readUnsignedInt(bytes, PALMDOC_TEXT_LENGTH_OFFSET)
        if (textLengthLong <= 0L || textLengthLong > MAX_MOBI_TEXT_BYTES) {
            fail(
                if (textLengthLong > MAX_MOBI_TEXT_BYTES) {
                    MobiParseFailure.TEXT_TOO_LARGE
                } else {
                    MobiParseFailure.INVALID_FILE
                },
                "Invalid MOBI text length: $textLengthLong"
            )
        }
        val textRecordCount = readUnsignedShort(bytes, PALMDOC_TEXT_RECORD_COUNT_OFFSET)
        if (textRecordCount <= 0 || textRecordCount >= recordCount) {
            fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI text record count: $textRecordCount")
        }
        val encryptionType = readUnsignedShort(bytes, PALMDOC_ENCRYPTION_OFFSET)
        if (encryptionType != PALMDOC_ENCRYPTION_NONE) {
            fail(MobiParseFailure.DRM_PROTECTED, "MOBI encryption type is $encryptionType")
        }
        if (!bytes.matchesAscii(MOBI_HEADER_OFFSET, MOBI_SIGNATURE)) {
            fail(MobiParseFailure.INVALID_FILE, "Missing MOBI header signature")
        }

        val mobiHeaderLength = readUnsignedInt(bytes, MOBI_HEADER_LENGTH_OFFSET)
        val mobiHeaderEnd = MOBI_HEADER_OFFSET + mobiHeaderLength
        if (
            mobiHeaderLength < MIN_MOBI_HEADER_BYTES ||
            mobiHeaderEnd > bytes.size.toLong()
        ) {
            fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI header length: $mobiHeaderLength")
        }
        val textEncoding = readUnsignedInt(bytes, MOBI_TEXT_ENCODING_OFFSET)
        val extraDataFlags = if (
            mobiHeaderEnd >= MOBI_EXTRA_DATA_FLAGS_OFFSET + U16_BYTES &&
            MOBI_EXTRA_DATA_FLAGS_OFFSET + U16_BYTES <= bytes.size
        ) {
            readUnsignedShort(bytes, MOBI_EXTRA_DATA_FLAGS_OFFSET)
        } else {
            0
        }

        return MobiHeader(
            compression = compression,
            textLength = textLengthLong.toInt(),
            textRecordCount = textRecordCount,
            textEncoding = textEncoding,
            extraDataFlags = extraDataFlags
        )
    }

    /**
     * 解压PalmDOC标准压缩记录。
     *
     * @param source 已移除MOBI尾随元数据的单条压缩记录。
     * @param maximumOutputBytes 本条记录允许生成的最大字节数。
     * @return 解压后的正文片段。
     */
    private fun decompressPalmDoc(source: ByteArray, maximumOutputBytes: Int): ByteArray {
        val output = ExpandableByteBuffer(
            initialCapacity = min((source.size * 2).coerceAtLeast(32), maximumOutputBytes),
            maximumCapacity = maximumOutputBytes
        )
        var index = 0
        while (index < source.size) {
            val value = source[index].toInt() and 0xFF
            index += 1
            when {
                value in 0x01..0x08 -> {
                    if (index + value > source.size) {
                        fail(MobiParseFailure.INVALID_FILE, "PalmDOC literal run exceeds record")
                    }
                    output.append(source, index, value)
                    index += value
                }
                value <= 0x7F -> output.append(value.toByte())
                value <= 0xBF -> {
                    if (index >= source.size) {
                        fail(MobiParseFailure.INVALID_FILE, "PalmDOC back reference is truncated")
                    }
                    val pair = (value shl 8) or (source[index].toInt() and 0xFF)
                    index += 1
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 0x07) + PALMDOC_MIN_REFERENCE_LENGTH
                    output.copyFromHistory(distance, length)
                }
                else -> {
                    output.append(' '.code.toByte())
                    output.append((value xor 0x80).toByte())
                }
            }
        }
        return output.toByteArray()
    }

    /**
     * 根据MOBI头标志移除每条正文记录末尾的索引数据和多字节重叠区。
     *
     * @param source 原始记录。
     * @param extraDataFlags PalmDOC记录起点0xF2位置的尾随数据位图。
     * @return 仅包含压缩正文的记录；没有额外数据时直接返回原数组。
     */
    private fun removeTrailingRecordData(source: ByteArray, extraDataFlags: Int): ByteArray {
        if (extraDataFlags == 0 || source.isEmpty()) return source
        var trailingBytes = 0
        var indexedFlags = extraDataFlags ushr 1
        while (indexedFlags != 0) {
            if (indexedFlags and 1 != 0) {
                val entrySize = readBackwardVariableWidthValue(
                    source = source,
                    endExclusive = source.size - trailingBytes
                )
                if (entrySize <= 0 || entrySize > source.size - trailingBytes) {
                    fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI trailing data size")
                }
                trailingBytes += entrySize
            }
            indexedFlags = indexedFlags ushr 1
        }
        if (extraDataFlags and 1 != 0) {
            val markerIndex = source.lastIndex - trailingBytes
            if (markerIndex < 0) {
                fail(MobiParseFailure.INVALID_FILE, "Missing MOBI multibyte trailer")
            }
            trailingBytes += (source[markerIndex].toInt() and 0x03) + 1
        }
        if (trailingBytes !in 0..source.size) {
            fail(MobiParseFailure.INVALID_FILE, "MOBI trailing data exceeds record")
        }
        return if (trailingBytes == 0) source else source.copyOf(source.size - trailingBytes)
    }

    /** @return 从记录末尾反向读取的MOBI七位变长整数。 */
    private fun readBackwardVariableWidthValue(source: ByteArray, endExclusive: Int): Int {
        if (endExclusive <= 0) {
            fail(MobiParseFailure.INVALID_FILE, "Missing MOBI variable-width value")
        }
        var value = 0L
        var cursor = endExclusive - 1
        var consumed = 0
        while (cursor >= 0 && consumed < MAX_VARIABLE_WIDTH_BYTES) {
            val current = source[cursor].toInt() and 0xFF
            value = (value shl 7) or (current and 0x7F).toLong()
            consumed += 1
            if (value > Int.MAX_VALUE) {
                fail(MobiParseFailure.INVALID_FILE, "MOBI variable-width value is too large")
            }
            if (current and 0x80 != 0) return value.toInt()
            cursor -= 1
        }
        fail(MobiParseFailure.INVALID_FILE, "Unterminated MOBI variable-width value")
    }

    /**
     * 按MOBI声明代码页解码正文数组中的有效区域。
     *
     * @param bytes 解压后的固定容量正文数组。
     * @param length 数组中实际写入的有效字节数。
     * @param encoding MOBI头声明的代码页。
     * @return 解码后的HTML正文。
     */
    private fun decodeText(bytes: ByteArray, length: Int, encoding: Long): String {
        val charset = when (encoding) {
            MOBI_ENCODING_UTF8 -> Charsets.UTF_8
            MOBI_ENCODING_WINDOWS_1252 -> Charset.forName("windows-1252")
            else -> fail(
                MobiParseFailure.UNSUPPORTED_ENCODING,
                "Unsupported MOBI text encoding: $encoding"
            )
        }
        return String(bytes, 0, length, charset)
    }

    /** @return 从文件指定区间读取的单条PDB记录。 */
    private fun readRecord(
        input: RandomAccessFile,
        start: Long,
        end: Long,
        maximumBytes: Int,
        label: String
    ): ByteArray {
        val length = end - start
        if (length <= 0L || length > maximumBytes) {
            fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI $label record length: $length")
        }
        return ByteArray(length.toInt()).also { bytes ->
            input.seek(start)
            input.readFully(bytes)
        }
    }

    /** 验证记录偏移严格递增并处于文件内部。 */
    private fun validateRecordOffsets(offsets: LongArray, tableEnd: Long, fileLength: Long) {
        var previous = tableEnd - 1
        offsets.forEach { offset ->
            if (offset < tableEnd || offset <= previous || offset >= fileLength) {
                fail(MobiParseFailure.INVALID_FILE, "Invalid MOBI record offset: $offset")
            }
            previous = offset
        }
    }

    /** @return 大端无符号16位整数。 */
    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + U16_BYTES > bytes.size) {
            fail(MobiParseFailure.INVALID_FILE, "MOBI u16 field is out of bounds")
        }
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    /** @return 大端无符号32位整数，以Long承载避免符号溢出。 */
    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + U32_BYTES > bytes.size) {
            fail(MobiParseFailure.INVALID_FILE, "MOBI u32 field is out of bounds")
        }
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    /** @return 指定位置是否与ASCII标记完全一致。 */
    private fun ByteArray.matchesAscii(offset: Int, expected: String): Boolean {
        if (offset < 0 || offset + expected.length > size) return false
        return expected.indices.all { index ->
            this[offset + index].toInt() and 0xFF == expected[index].code
        }
    }

    /** 统一抛出带稳定分类的解析异常。 */
    private fun fail(failure: MobiParseFailure, detail: String): Nothing {
        throw MobiParseException(failure, detail)
    }

    /** MOBI头解析后的最小运行参数。 */
    private data class MobiHeader(
        val compression: Int,
        val textLength: Int,
        val textRecordCount: Int,
        val textEncoding: Long,
        val extraDataFlags: Int
    )

    /**
     * PalmDOC解压使用的有界动态字节缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @param maximumCapacity 允许扩展的最大容量。
     */
    private class ExpandableByteBuffer(
        initialCapacity: Int,
        private val maximumCapacity: Int
    ) {
        private var bytes = ByteArray(initialCapacity.coerceIn(1, maximumCapacity.coerceAtLeast(1)))
        private var size = 0

        /** 追加一个字节，超过安全上限时终止解析。 */
        fun append(value: Byte) {
            ensureCapacity(size + 1)
            bytes[size] = value
            size += 1
        }

        /** 追加来源数组的指定片段。 */
        fun append(source: ByteArray, offset: Int, length: Int) {
            ensureCapacity(size + length)
            source.copyInto(bytes, destinationOffset = size, startIndex = offset, endIndex = offset + length)
            size += length
        }

        /** 按PalmDOC距离与长度复制已经解压的历史字节，支持重叠复制。 */
        fun copyFromHistory(distance: Int, length: Int) {
            if (distance <= 0 || distance > size) {
                fail(MobiParseFailure.INVALID_FILE, "PalmDOC back reference distance is invalid")
            }
            repeat(length) {
                append(bytes[size - distance])
            }
        }

        /** @return 当前有效字节的紧凑副本。 */
        fun toByteArray(): ByteArray = bytes.copyOf(size)

        /** 确保目标容量可写，并严格限制最大解压大小。 */
        private fun ensureCapacity(required: Int) {
            if (required < 0 || required > maximumCapacity) {
                fail(MobiParseFailure.TEXT_TOO_LARGE, "PalmDOC record expands beyond safe limit")
            }
            if (required <= bytes.size) return
            var nextCapacity = bytes.size
            while (nextCapacity < required) {
                nextCapacity = min(maximumCapacity, (nextCapacity * 2).coerceAtLeast(required))
                if (nextCapacity == bytes.size) break
            }
            if (nextCapacity < required) {
                fail(MobiParseFailure.TEXT_TOO_LARGE, "PalmDOC buffer cannot grow further")
            }
            bytes = bytes.copyOf(nextCapacity)
        }
    }

    private const val PDB_HEADER_BYTES = 78L
    private const val PDB_RECORD_ENTRY_BYTES = 8L
    private const val PDB_RECORD_COUNT_OFFSET = 76L
    private const val MIN_PDB_BYTES = 94L
    private const val MIN_RECORD_COUNT = 2
    private const val MAX_RECORD_COUNT = 65_000
    private const val MAX_HEADER_RECORD_BYTES = 4 * 1024 * 1024
    private const val MAX_COMPRESSED_RECORD_BYTES = 16 * 1024 * 1024
    private const val MAX_DECOMPRESSED_RECORD_BYTES = 16 * 1024 * 1024
    private const val MAX_MOBI_TEXT_BYTES = 80L * 1024L * 1024L
    private const val MAX_PROGRESS_CALLBACKS = 200
    private const val MAX_VARIABLE_WIDTH_BYTES = 5
    private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
    private const val U16_BYTES = 2
    private const val U32_BYTES = 4

    private const val PALMDOC_COMPRESSION_OFFSET = 0
    private const val PALMDOC_TEXT_LENGTH_OFFSET = 4
    private const val PALMDOC_TEXT_RECORD_COUNT_OFFSET = 8
    private const val PALMDOC_ENCRYPTION_OFFSET = 12
    private const val PALMDOC_COMPRESSION_NONE = 1
    private const val PALMDOC_COMPRESSION_STANDARD = 2
    private const val PALMDOC_COMPRESSION_HUFF = 17_480
    private const val PALMDOC_ENCRYPTION_NONE = 0
    private const val PALMDOC_MIN_REFERENCE_LENGTH = 3

    private const val MOBI_HEADER_OFFSET = 16
    private const val MOBI_HEADER_LENGTH_OFFSET = MOBI_HEADER_OFFSET + 4
    private const val MOBI_TEXT_ENCODING_OFFSET = MOBI_HEADER_OFFSET + 12
    // 该字段的0xF2是相对整个PalmDOC记录起点，而不是相对偏移16字节后的“MOBI”签名。
    private const val MOBI_EXTRA_DATA_FLAGS_OFFSET = 0xF2
    private const val MIN_MOBI_HEADER_RECORD_BYTES = 32
    private const val MIN_MOBI_HEADER_BYTES = 16L
    private const val MOBI_SIGNATURE = "MOBI"
    private const val MOBI_ENCODING_WINDOWS_1252 = 1_252L
    private const val MOBI_ENCODING_UTF8 = 65_001L
}
