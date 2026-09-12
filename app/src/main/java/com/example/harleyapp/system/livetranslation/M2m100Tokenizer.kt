package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationSourceLanguage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * 读取M2M100随模型发布的SentencePiece BPE文件和官方`vocab.json`，完成英日文本到模型ID的双向转换。
 *
 * 使用方法：
 * [OnDeviceLiveTranslator.initialize]在加载ONNX会话前创建实例；随后每条字幕调用[encode]，生成
 * `[源语言ID, 正文Piece..., EOS]`。解码器生成结束后调用[decode]还原中文。实现不加载动态代码、
 * 不访问网络，也不会把字幕写入磁盘或日志。
 *
 * SentencePiece文件只负责规范化后的BPE分词和合并分数，最终piece与模型ID必须通过官方`vocab.json`
 * 双向查询。两份文件的存储顺序并不相同，不能把SentencePiece原始序号加一后当成模型ID。语言ID严格
 * 遵循原模型128004词表基址：英语128022、日语128046、中文128102。
 *
 * @param modelFile 已通过[M2m100ModelRepository]字节数及SHA-256校验的`sentencepiece.bpe.model`。
 * @param vocabularyFile 已通过[M2m100ModelRepository]字节数及SHA-256校验的UTF-8 `vocab.json`。
 */
internal class M2m100Tokenizer(
    modelFile: File,
    vocabularyFile: File
) : AutoCloseable {

    private data class VocabularyPiece(
        val text: String,
        val score: Float,
        val type: Int
    )

    private var pieces: List<VocabularyPiece> = parseModel(modelFile)
    private var sentencePieceToIndex: Map<String, Int> = buildMap(pieces.size * 2) {
        pieces.forEachIndexed { index, piece -> put(piece.text, index) }
    }
    private var modelVocabulary = parseVocabularyFile(vocabularyFile)
    private var closed = false

    init {
        check(pieces.size == EXPECTED_SENTENCEPIECE_VOCABULARY_SIZE) {
            "Unexpected M2M100 SentencePiece vocabulary size"
        }
        check(pieces.getOrNull(0)?.text == UNKNOWN_PIECE && pieces[0].type == TYPE_UNKNOWN) {
            "Unexpected M2M100 SentencePiece unknown token"
        }
        check(pieces.getOrNull(1)?.text == "<s>" && pieces[1].type == TYPE_CONTROL) {
            "Unexpected M2M100 SentencePiece BOS token"
        }
        check(pieces.getOrNull(2)?.text == "</s>" && pieces[2].type == TYPE_CONTROL) {
            "Unexpected M2M100 SentencePiece EOS token"
        }
        validateProductionVocabulary(modelVocabulary)
    }

    /**
     * 把一条英文或日文字幕编码为M2M100 encoder输入。
     *
     * 使用方法：
     * 传入ASR已经确认的短字幕和会话源语言。调用方可再通过[truncateEncoderInput]限制实时场景长度，
     * 但不能删除数组首部语言ID或末尾EOS。
     *
     * @param text 英文或日文字幕正文；空白规范化遵循模型SentencePiece训练设置。
     * @param sourceLanguage 当前会话由用户明确选择的英语或日语。
     * @return 以源语言ID开头、EOS 2结尾的INT64模型ID数组。
     */
    fun encode(
        text: String,
        sourceLanguage: LiveTranslationSourceLanguage
    ): LongArray {
        checkOpen()
        val textIds = encodeText(text)
        return buildM2m100EncoderInput(textIds, sourceLanguage)
    }

    /**
     * 把模型生成的正文ID还原为可展示中文。
     *
     * 使用方法：
     * 传入贪心解码收集的正文token，不需要手工加入中文语言ID；即使数组意外包含EOS、语言ID或填充ID，
     * 本函数也会忽略这些控制项。未知或越界token不会显示Unicode替换字符，避免字幕出现伪乱码。
     *
     * @param modelTokenIds 解码循环生成的M2M100模型词表ID。
     * @return 还原SentencePiece空格标记并去除开头模型前缀空格的译文。
     */
    fun decode(modelTokenIds: LongArray): String {
        checkOpen()
        return decodeM2m100ModelTokenIds(modelTokenIds, modelVocabulary)
    }

    /**
     * 释放词表对象，降低会话结束后的Java堆占用。
     *
     * 使用方法：
     * 由[OnDeviceLiveTranslator.close]调用；重复调用安全。关闭后继续编码或解码会明确失败。
     *
     * @return 无返回值。
     */
    override fun close() {
        if (closed) return
        closed = true
        sentencePieceToIndex = emptyMap()
        pieces = emptyList()
        modelVocabulary = M2m100ModelVocabulary.empty()
    }

    /**
     * 只编码字幕正文，不添加语言ID和EOS。
     *
     * @param text 原始字幕正文。
     * @return 已从SentencePiece ID转换为M2M100模型词表ID的正文数组。
     */
    private fun encodeText(text: String): LongArray {
        val normalized = normalizeM2m100SentencePieceText(text)
        if (normalized.isEmpty()) return LongArray(0)

        val symbols = normalized.codePoints()
            .toArray()
            .mapTo(ArrayList()) { codePoint -> String(Character.toChars(codePoint)) }

        while (symbols.size > 1) {
            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (index in 0 until symbols.lastIndex) {
                val merged = symbols[index] + symbols[index + 1]
                val pieceId = sentencePieceToIndex[merged] ?: continue
                val piece = pieces[pieceId]
                if (piece.type != TYPE_NORMAL && piece.type != TYPE_USER_DEFINED) continue
                if (piece.score > bestScore) {
                    bestScore = piece.score
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break

            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }

        val sentencePieceTokens = symbols.map { symbol ->
            if (sentencePieceToIndex.containsKey(symbol)) symbol else UNKNOWN_PIECE
        }
        return mapM2m100SentencePiecesToModelIds(sentencePieceTokens, modelVocabulary)
    }

    /** @throws IllegalStateException 词表已经释放时抛出，防止会话结束后误用空词表。 */
    private fun checkOpen() {
        check(!closed) { "M2M100 tokenizer is closed" }
    }

    companion object {
        const val EOS_TOKEN_ID = 2L
        const val ENGLISH_LANGUAGE_TOKEN_ID = 128_022L
        const val JAPANESE_LANGUAGE_TOKEN_ID = 128_046L
        const val CHINESE_LANGUAGE_TOKEN_ID = 128_102L

        private const val EXPECTED_SENTENCEPIECE_VOCABULARY_SIZE = 128_000
        private const val EXPECTED_MODEL_VOCABULARY_SIZE = 128_004
        private const val UNKNOWN_PIECE = "<unk>"
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val MAX_MODEL_FILE_SIZE_BYTES = 4L * 1024L * 1024L
        private const val MAX_VOCABULARY_FILE_SIZE_BYTES = 8L * 1024L * 1024L

        /**
         * 以严格UTF-8读取官方`vocab.json`并解析piece到模型ID的双向映射。
         *
         * @param vocabularyFile 已由模型仓库完成长度和摘要校验的词表文件。
         * @return 保留大小写差异、完整模型ID空间及反向piece查询的词表。
         */
        private fun parseVocabularyFile(vocabularyFile: File): M2m100ModelVocabulary {
            require(vocabularyFile.isFile) { "M2M100 vocabulary is missing" }
            require(vocabularyFile.length() in 1L..MAX_VOCABULARY_FILE_SIZE_BYTES) {
                "Unexpected M2M100 vocabulary length"
            }

            val bytes = vocabularyFile.readBytes()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val json = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            return parseM2m100VocabularyJson(json)
        }

        /**
         * 核验解析结果与M2M100-418M官方词表的固定边界及特殊token一致。
         *
         * @param vocabulary 待核验的双向词表。
         * @return 无返回值；条目缺失、ID不连续或特殊token错位时抛出异常。
         */
        private fun validateProductionVocabulary(vocabulary: M2m100ModelVocabulary) {
            check(vocabulary.entryCount == EXPECTED_MODEL_VOCABULARY_SIZE) {
                "Unexpected M2M100 model vocabulary size"
            }
            check(
                vocabulary.idSpaceSize == EXPECTED_MODEL_VOCABULARY_SIZE &&
                    vocabulary.hasContiguousModelIds
            ) {
                "M2M100 model vocabulary IDs are not contiguous"
            }
            check(vocabulary.modelIdForPiece("<s>") == 0) {
                "Unexpected M2M100 BOS token ID"
            }
            check(vocabulary.modelIdForPiece("<pad>") == 1) {
                "Unexpected M2M100 PAD token ID"
            }
            check(vocabulary.modelIdForPiece("</s>") == EOS_TOKEN_ID.toInt()) {
                "Unexpected M2M100 EOS token ID"
            }
            check(vocabulary.modelIdForPiece(UNKNOWN_PIECE) == M2M100_UNKNOWN_MODEL_TOKEN_ID.toInt()) {
                "Unexpected M2M100 unknown token ID"
            }
        }

        /**
         * 把已包含语言ID与EOS的encoder输入截断到实时字幕上限。
         *
         * 使用方法：
         * 翻译器在创建encoder tensor前调用。超过上限时保留第一个源语言ID、尽可能多的正文以及最后一个EOS；
         * 输入过短或末尾不是EOS表示调用契约错误，会直接失败而不是生成错位翻译。
         *
         * @param inputIds [encode]返回的完整输入。
         * @param maxTokenCount 允许进入encoder的最大token数，至少为3。
         * @return 未超限时返回原数组，超限时返回保留控制token的新数组。
         */
        internal fun truncateEncoderInput(
            inputIds: LongArray,
            maxTokenCount: Int
        ): LongArray {
            require(maxTokenCount >= 3) { "M2M100 encoder token limit must be at least 3" }
            require(inputIds.size >= 2 && inputIds.last() == EOS_TOKEN_ID) {
                "M2M100 encoder input must contain a language prefix and EOS suffix"
            }
            if (inputIds.size <= maxTokenCount) return inputIds

            return inputIds.copyOf(maxTokenCount).also { truncated ->
                truncated[truncated.lastIndex] = EOS_TOKEN_ID
            }
        }

        /**
         * 解析SentencePiece `ModelProto`中按顺序存储的词表项。
         *
         * @param modelFile 经过外层下载完整性验证的protobuf模型文件。
         * @return 保留原始ID顺序的词表项。
         */
        private fun parseModel(modelFile: File): List<VocabularyPiece> {
            require(modelFile.isFile) { "M2M100 SentencePiece model is missing" }
            require(modelFile.length() in 1L..MAX_MODEL_FILE_SIZE_BYTES) {
                "Unexpected M2M100 SentencePiece model length"
            }

            val bytes = modelFile.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val result = ArrayList<VocabularyPiece>(EXPECTED_SENTENCEPIECE_VOCABULARY_SIZE)
            while (buffer.hasRemaining()) {
                val tag = readVarint(buffer)
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                if (fieldNumber == MODEL_PIECES_FIELD_NUMBER && wireType == WIRE_LENGTH_DELIMITED) {
                    val length = readLength(buffer)
                    val limit = checkedEndPosition(buffer, length)
                    val oldLimit = buffer.limit()
                    buffer.limit(limit)
                    result += parseVocabularyPiece(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                    buffer.position(limit)
                    buffer.limit(oldLimit)
                } else {
                    skipField(buffer, wireType)
                }
            }
            return result
        }

        /**
         * 解析一个`ModelProto.SentencePiece`子消息。
         *
         * @param buffer 限制在单个子消息范围内的只读游标。
         * @return piece正文、BPE合并分数和SentencePiece类型。
         */
        private fun parseVocabularyPiece(buffer: ByteBuffer): VocabularyPiece {
            var text: String? = null
            var score = 0f
            var type = TYPE_NORMAL
            while (buffer.hasRemaining()) {
                val tag = readVarint(buffer)
                val fieldNumber = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()
                when {
                    fieldNumber == PIECE_TEXT_FIELD_NUMBER && wireType == WIRE_LENGTH_DELIMITED -> {
                        val length = readLength(buffer)
                        val data = ByteArray(length)
                        buffer.get(data)
                        text = data.toString(Charsets.UTF_8)
                    }

                    fieldNumber == PIECE_SCORE_FIELD_NUMBER && wireType == WIRE_FIXED_32 -> {
                        require(buffer.remaining() >= Int.SIZE_BYTES) { "Truncated SentencePiece score" }
                        score = buffer.float
                    }

                    fieldNumber == PIECE_TYPE_FIELD_NUMBER && wireType == WIRE_VARINT -> {
                        type = readVarint(buffer).toInt()
                    }

                    else -> skipField(buffer, wireType)
                }
            }
            return VocabularyPiece(
                text = requireNotNull(text) { "SentencePiece entry is missing text" },
                score = score,
                type = type
            )
        }

        /**
         * 读取protobuf无符号varint并拒绝截断或超过64位的输入。
         *
         * @param buffer 当前protobuf游标。
         * @return 解码后的非负Long。
         */
        private fun readVarint(buffer: ByteBuffer): Long {
            var result = 0L
            var shift = 0
            repeat(MAX_VARINT_BYTES) {
                require(buffer.hasRemaining()) { "Truncated SentencePiece varint" }
                val byte = buffer.get().toInt() and 0xFF
                if (shift == 63) require(byte and 0xFE == 0) { "Oversized SentencePiece varint" }
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Oversized SentencePiece varint")
        }

        /** @return 经边界检查、可安全转换为Int的length-delimited字段长度。 */
        private fun readLength(buffer: ByteBuffer): Int {
            val length = readVarint(buffer)
            require(length in 0L..Int.MAX_VALUE.toLong()) { "Invalid SentencePiece field length" }
            require(length <= buffer.remaining().toLong()) { "Truncated SentencePiece field" }
            return length.toInt()
        }

        /** @return 当前position加长度后的安全结束位置。 */
        private fun checkedEndPosition(buffer: ByteBuffer, length: Int): Int {
            require(length >= 0 && length <= buffer.remaining()) { "Invalid SentencePiece message length" }
            return buffer.position() + length
        }

        /**
         * 跳过当前protobuf中本实现不需要的字段，同时保持严格边界检查。
         *
         * @param buffer 当前protobuf游标。
         * @param wireType protobuf wire type。
         * @return 无返回值。
         */
        private fun skipField(buffer: ByteBuffer, wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint(buffer)
                WIRE_FIXED_64 -> advance(buffer, Long.SIZE_BYTES)
                WIRE_LENGTH_DELIMITED -> advance(buffer, readLength(buffer))
                WIRE_FIXED_32 -> advance(buffer, Int.SIZE_BYTES)
                else -> throw IllegalArgumentException("Unsupported SentencePiece wire type")
            }
        }

        /** 将protobuf游标安全前移固定字节数。 */
        private fun advance(buffer: ByteBuffer, byteCount: Int) {
            require(byteCount >= 0 && byteCount <= buffer.remaining()) {
                "Truncated SentencePiece field"
            }
            buffer.position(buffer.position() + byteCount)
        }

        private const val MAX_VARINT_BYTES = 10
        private const val MODEL_PIECES_FIELD_NUMBER = 1
        private const val PIECE_TEXT_FIELD_NUMBER = 1
        private const val PIECE_SCORE_FIELD_NUMBER = 2
        private const val PIECE_TYPE_FIELD_NUMBER = 3
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED_64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED_32 = 5
    }
}

/**
 * M2M100官方JSON词表的大小写敏感双向索引。
 *
 * 使用方法：
 * 生产代码通过[parseM2m100VocabularyJson]创建后交给编码与解码函数；测试也可使用小型固定JSON验证
 * Unicode转义、大小写不同piece以及官方golden ID。模型ID不存在时查询返回null，不猜测相邻ID。
 *
 * @param pieceToModelId piece字符串到模型ID的大小写敏感映射。
 * @param modelIdToPiece 模型ID下标到piece字符串的稀疏数组。
 */
internal class M2m100ModelVocabulary private constructor(
    private val pieceToModelId: Map<String, Int>,
    private val modelIdToPiece: Array<String?>
) {

    /** JSON中去重后的piece条目数。 */
    val entryCount: Int
        get() = pieceToModelId.size

    /** 从ID 0到最大ID所需的数组长度。 */
    val idSpaceSize: Int
        get() = modelIdToPiece.size

    /** ID空间没有空洞时为true。 */
    val hasContiguousModelIds: Boolean
        get() = modelIdToPiece.all { it != null }

    /** @return [piece]对应的精确模型ID，不存在时返回null。 */
    fun modelIdForPiece(piece: String): Int? = pieceToModelId[piece]

    /** @return [modelId]对应的piece，负数、越界或空洞ID返回null。 */
    fun pieceForModelId(modelId: Long): String? {
        if (modelId !in 0L until modelIdToPiece.size.toLong()) return null
        return modelIdToPiece[modelId.toInt()]
    }

    companion object {
        /** @return 已解析词表；重复piece或重复ID已经由调用方拒绝。 */
        internal fun create(
            pieceToModelId: Map<String, Int>,
            modelIdToPiece: Array<String?>
        ): M2m100ModelVocabulary {
            return M2m100ModelVocabulary(pieceToModelId, modelIdToPiece)
        }

        /** @return 供已关闭tokenizer释放大词表引用使用的空实例。 */
        internal fun empty(): M2m100ModelVocabulary {
            return M2m100ModelVocabulary(emptyMap(), emptyArray())
        }
    }
}

/**
 * 严格解析M2M100 `vocab.json`使用的“字符串到非负整数”JSON对象。
 *
 * 使用方法：
 * 文件层先用严格UTF-8解码，再把完整正文传入本函数。解析保留键的大小写差异，支持标准JSON转义，
 * 并拒绝重复piece、重复ID、非法Unicode代理项、负数、小数和尾随内容。
 *
 * @param json UTF-8解码后的完整JSON正文。
 * @return 可按piece或模型ID查询的双向词表。
 */
internal fun parseM2m100VocabularyJson(json: String): M2m100ModelVocabulary {
    val parsedEntries = M2m100VocabularyJsonParser(json).parse()
    require(parsedEntries.isNotEmpty()) { "M2M100 vocabulary must not be empty" }

    val maximumModelId = parsedEntries.values.maxOrNull()
        ?: throw IllegalArgumentException("M2M100 vocabulary must not be empty")
    val modelIdToPiece = arrayOfNulls<String>(maximumModelId + 1)
    parsedEntries.forEach { (piece, modelId) ->
        require(modelIdToPiece[modelId] == null) {
            "M2M100 vocabulary contains a duplicate model ID"
        }
        modelIdToPiece[modelId] = piece
    }
    return M2m100ModelVocabulary.create(parsedEntries, modelIdToPiece)
}

/**
 * 把SentencePiece已经切分出的piece字符串映射为官方M2M100模型ID。
 *
 * 使用方法：
 * tokenizer完成BPE合并后调用。piece不存在于官方词表时输出`<unk>` ID 3，并按模型`fuse_unk`
 * 规则把连续未知piece合并成一个ID。
 *
 * @param sentencePieces 按顺序排列的SentencePiece结果。
 * @param vocabulary 官方模型词表。
 * @return 可直接写入encoder正文区的模型ID数组，不包含语言ID和EOS。
 */
internal fun mapM2m100SentencePiecesToModelIds(
    sentencePieces: List<String>,
    vocabulary: M2m100ModelVocabulary
): LongArray {
    val modelIds = ArrayList<Long>(sentencePieces.size)
    var previousWasUnknown = false
    sentencePieces.forEach { piece ->
        val modelId = vocabulary.modelIdForPiece(piece)?.toLong()
            ?: M2M100_UNKNOWN_MODEL_TOKEN_ID
        val unknown = modelId == M2M100_UNKNOWN_MODEL_TOKEN_ID
        if (!unknown || !previousWasUnknown) modelIds += modelId
        previousWasUnknown = unknown
    }
    return modelIds.toLongArray()
}

/**
 * 给正文模型ID添加M2M100源语言前缀与EOS后缀。
 *
 * 使用方法：
 * tokenizer完成piece到官方模型ID映射后调用；调用方不得再次添加控制token。
 *
 * @param textModelIds 不含任何语言ID或EOS的正文模型ID。
 * @param sourceLanguage 用户为当前会话选择的英语或日语。
 * @return `[源语言ID, 正文ID..., EOS]`格式的encoder输入。
 */
internal fun buildM2m100EncoderInput(
    textModelIds: LongArray,
    sourceLanguage: LiveTranslationSourceLanguage
): LongArray {
    val sourceLanguageId = when (sourceLanguage) {
        LiveTranslationSourceLanguage.ENGLISH -> M2m100Tokenizer.ENGLISH_LANGUAGE_TOKEN_ID
        LiveTranslationSourceLanguage.JAPANESE -> M2m100Tokenizer.JAPANESE_LANGUAGE_TOKEN_ID
    }
    return LongArray(textModelIds.size + 2).also { result ->
        result[0] = sourceLanguageId
        textModelIds.copyInto(result, destinationOffset = 1)
        result[result.lastIndex] = M2m100Tokenizer.EOS_TOKEN_ID
    }
}

/**
 * 使用官方ID到piece映射把模型输出还原为可展示字幕。
 *
 * 使用方法：
 * 解码循环把生成ID完整传入。BOS、PAD、EOS、UNK、语言ID、占位ID和越界ID全部忽略；正常piece按
 * `▁`空格标记拼接。忽略UNK可避免向用户显示Unicode替换符造成的假乱码。
 *
 * @param modelTokenIds 模型生成的ID序列。
 * @param vocabulary 官方模型词表。
 * @return 去除SentencePiece前导空格后的正文。
 */
internal fun decodeM2m100ModelTokenIds(
    modelTokenIds: LongArray,
    vocabulary: M2m100ModelVocabulary
): String {
    val builder = StringBuilder()
    modelTokenIds.forEach { modelId ->
        when {
            modelId in M2M100_CONTROL_AND_UNKNOWN_TOKEN_ID_RANGE -> Unit
            modelId in M2M100_LANGUAGE_TOKEN_ID_RANGE -> Unit
            else -> vocabulary.pieceForModelId(modelId)?.let(builder::append)
        }
    }
    return builder.toString()
        .replace(M2M100_SENTENCEPIECE_SPACE_MARKER, ' ')
        .trim()
}

/** 只接受M2M100词表所需扁平JSON结构的严格游标解析器。 */
private class M2m100VocabularyJsonParser(
    private val json: String
) {
    private var index = 0

    /** @return 完整解析且没有重复piece的大小写敏感映射。 */
    fun parse(): Map<String, Int> {
        if (json.startsWith('\uFEFF')) index++
        skipWhitespace()
        expect('{')
        skipWhitespace()

        val entries = HashMap<String, Int>()
        if (consume('}')) {
            finishDocument()
            return entries
        }

        while (true) {
            val piece = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val modelId = parseNonNegativeInt()
            require(!entries.containsKey(piece)) {
                "M2M100 vocabulary contains a duplicate piece"
            }
            entries[piece] = modelId

            skipWhitespace()
            if (consume('}')) break
            expect(',')
            skipWhitespace()
        }
        finishDocument()
        return entries
    }

    /** @return 一个已处理标准JSON转义且Unicode代理项配对正确的字符串。 */
    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < json.length) {
            val character = json[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character.code < JSON_FIRST_PRINTABLE_CODE_POINT -> {
                    throw IllegalArgumentException("M2M100 vocabulary contains a control character")
                }
                character.isHighSurrogate() -> {
                    require(index < json.length && json[index].isLowSurrogate()) {
                        "M2M100 vocabulary contains an unpaired high surrogate"
                    }
                    result.append(character)
                    result.append(json[index++])
                }
                character.isLowSurrogate() -> {
                    throw IllegalArgumentException("M2M100 vocabulary contains an unpaired low surrogate")
                }
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException("M2M100 vocabulary contains an unterminated string")
    }

    /** 把当前位置的一个标准JSON转义追加到[result]。 */
    private fun appendEscape(result: StringBuilder) {
        require(index < json.length) { "M2M100 vocabulary contains an unfinished escape" }
        when (val escaped = json[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> appendUnicodeEscape(result)
            else -> throw IllegalArgumentException("M2M100 vocabulary contains an invalid escape")
        }
    }

    /** 解析一个`\\uXXXX`转义；补充平面字符必须由高、低代理项连续组成。 */
    private fun appendUnicodeEscape(result: StringBuilder) {
        val first = readUnicodeCodeUnit()
        when {
            first.isHighSurrogate() -> {
                require(index + 1 < json.length && json[index] == '\\' && json[index + 1] == 'u') {
                    "M2M100 vocabulary contains an unpaired escaped high surrogate"
                }
                index += 2
                val second = readUnicodeCodeUnit()
                require(second.isLowSurrogate()) {
                    "M2M100 vocabulary contains an invalid escaped surrogate pair"
                }
                result.append(first)
                result.append(second)
            }
            first.isLowSurrogate() -> {
                throw IllegalArgumentException("M2M100 vocabulary contains an unpaired escaped low surrogate")
            }
            else -> result.append(first)
        }
    }

    /** @return 当前四个十六进制字符代表的UTF-16 code unit。 */
    private fun readUnicodeCodeUnit(): Char {
        require(index + JSON_UNICODE_ESCAPE_DIGITS <= json.length) {
            "M2M100 vocabulary contains a truncated Unicode escape"
        }
        var value = 0
        repeat(JSON_UNICODE_ESCAPE_DIGITS) {
            val digit = json[index++].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("M2M100 vocabulary contains an invalid Unicode escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    /** @return JSON中的非负Int；拒绝前导零、小数、指数和溢出。 */
    private fun parseNonNegativeInt(): Int {
        require(index < json.length && json[index] in '0'..'9') {
            "M2M100 vocabulary model ID must be a non-negative integer"
        }
        val start = index
        if (json[index] == '0') {
            index++
            require(index >= json.length || json[index] !in '0'..'9') {
                "M2M100 vocabulary model ID must not contain a leading zero"
            }
            return 0
        }

        var value = 0L
        while (index < json.length && json[index] in '0'..'9') {
            value = value * 10L + (json[index++] - '0')
            require(value <= Int.MAX_VALUE.toLong()) {
                "M2M100 vocabulary model ID exceeds Int range"
            }
        }
        require(index > start) { "M2M100 vocabulary model ID is missing" }
        return value.toInt()
    }

    /** 文档结束后拒绝任何非空白尾随内容。 */
    private fun finishDocument() {
        skipWhitespace()
        require(index == json.length) { "M2M100 vocabulary contains trailing content" }
    }

    /** 跳过JSON规范允许的四种空白字符。 */
    private fun skipWhitespace() {
        while (index < json.length && json[index] in JSON_WHITESPACE_CHARACTERS) index++
    }

    /** 当前字符等于[expected]时消费并返回true。 */
    private fun consume(expected: Char): Boolean {
        if (index >= json.length || json[index] != expected) return false
        index++
        return true
    }

    /** 消费[expected]，不匹配时抛出结构错误。 */
    private fun expect(expected: Char) {
        require(consume(expected)) { "M2M100 vocabulary JSON has an invalid structure" }
    }
}

/**
 * 对常见英日字幕执行与M2M100 SentencePiece一致的NFKC、空白折叠与Metaspace规范化。
 *
 * 使用方法：
 * 生产编码器直接调用；单元测试使用官方慢tokenizer的golden文本验证全角标点、组合字符、连续空白、
 * 日文和补充平面字符。模型内含`nmt_nfkc`预编译映射，而Android没有直接执行该二进制映射的公开API；
 * 本实现使用Java NFKC并移除常见不可见控制/格式字符，覆盖正常电影字幕，但不宣称覆盖所有Unicode历史字符。
 * 模型`remove_extra_whitespaces=true`会移除首尾空白并折叠中间连续空白。
 *
 * @param text 原始字幕正文。
 * @return 已添加开头`▁`并把内部空白替换为`▁`的规范化文本；纯空白输入返回空字符串。
 */
internal fun normalizeM2m100SentencePieceText(text: String): String {
    val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
    val collapsed = StringBuilder(nfkc.length)
    var previousWasWhitespace = false
    nfkc.codePoints().forEach { codePoint ->
        val whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        if (whitespace) {
            if (!previousWasWhitespace) collapsed.append(' ')
            previousWasWhitespace = true
        } else if (
            Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.FORMAT.toInt() &&
            codePoint != ZERO_WIDTH_NON_JOINER &&
            codePoint != ZERO_WIDTH_JOINER
        ) {
            // nmt_nfkc会移除字幕中无显示意义的控制字符；保留连接符以免拆坏emoji及复杂文字序列。
        } else {
            collapsed.appendCodePoint(codePoint)
            previousWasWhitespace = false
        }
    }

    var firstContentIndex = 0
    while (firstContentIndex < collapsed.length && collapsed[firstContentIndex] == ' ') {
        firstContentIndex++
    }
    var contentEndIndex = collapsed.length
    while (contentEndIndex > firstContentIndex && collapsed[contentEndIndex - 1] == ' ') {
        contentEndIndex--
    }
    if (firstContentIndex == contentEndIndex) return ""

    return buildString(contentEndIndex - firstContentIndex + 1) {
        append('▁')
        for (index in firstContentIndex until contentEndIndex) {
            append(if (collapsed[index] == ' ') '▁' else collapsed[index])
        }
    }
}

private const val ZERO_WIDTH_NON_JOINER = 0x200C
private const val ZERO_WIDTH_JOINER = 0x200D
private const val M2M100_UNKNOWN_MODEL_TOKEN_ID = 3L
private const val M2M100_SENTENCEPIECE_SPACE_MARKER = '▁'
private const val M2M100_FIRST_LANGUAGE_TOKEN_ID = 128_004L
private const val M2M100_LAST_LANGUAGE_TOKEN_ID = 128_103L
private const val JSON_FIRST_PRINTABLE_CODE_POINT = 0x20
private const val JSON_UNICODE_ESCAPE_DIGITS = 4
private val M2M100_CONTROL_AND_UNKNOWN_TOKEN_ID_RANGE = 0L..3L
private val M2M100_LANGUAGE_TOKEN_ID_RANGE =
    M2M100_FIRST_LANGUAGE_TOKEN_ID..M2M100_LAST_LANGUAGE_TOKEN_ID
private val JSON_WHITESPACE_CHARACTERS = charArrayOf(' ', '\t', '\r', '\n')
