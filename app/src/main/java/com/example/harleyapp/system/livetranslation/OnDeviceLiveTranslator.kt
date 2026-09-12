package com.example.harleyapp.system.livetranslation

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import java.io.File
import java.nio.LongBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 使用M2M100-418M INT8模型把英文或日文短字幕翻译为简体中文。
 *
 * 使用方法：
 * 服务先从[LiveTranslationModelManager.requireReadyModelDirectories]取得翻译目录，再构造本类并在开始
 * AudioRecord之前调用[initialize]。初始化会一次加载encoder、首次decoder和带KV缓存decoder；任一模型
 * 不兼容或内存不足都会抛出异常，调用方必须终止整个会话。每条最终ASR字幕串行调用[translate]，会话结束
 * 在`finally`调用[close]。所有推理严格在设备本地执行，本类没有联网、遥测或正文持久化代码。
 *
 * @param modelDirectory 已由模型管理器校验完成的M2M100模型目录。
 */
class OnDeviceLiveTranslator(
    private val modelDirectory: File
) : AutoCloseable {

    private data class ModelContract(
        val encoderOutputName: String,
        val initialCacheOutputByInput: Map<String, String>,
        val nextDecoderCacheOutputByInput: Map<String, String>,
        val decoderCacheInputNames: Set<String>,
        val encoderCacheInputNames: Set<String>,
        val withPastNeedsEncoderHiddenStates: Boolean,
        val withPastNeedsEncoderAttentionMask: Boolean
    )

    private data class Resources(
        val environment: OrtEnvironment,
        val sessionOptions: OrtSession.SessionOptions,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val decoderWithPast: OrtSession,
        val tokenizer: M2m100Tokenizer,
        val contract: ModelContract
    )

    private val lifecycleLock = ReentrantLock(true)
    private var resources: Resources? = null
    private var closed = false
    private val cache = object : LinkedHashMap<LiveTranslationCacheKey, String>(
        CACHE_CAPACITY,
        CACHE_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LiveTranslationCacheKey, String>?
        ): Boolean = size > CACHE_CAPACITY
    }

    /**
     * 加载并核验三个ONNX会话、SentencePiece模型与官方模型词表。
     *
     * 使用方法：
     * 只在会话启动协程调用一次，并等待成功后再打开AudioRecord。重复成功调用安全；[close]之后禁止重新
     * 初始化。SessionOptions会保留到三个会话全部关闭，符合ONNX Runtime资源所有权要求。
     *
     * @return 无返回值；文件缺失、模型I/O契约不符、原生库错误或内存不足时抛出原异常。
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            check(!closed) { "Live translator is closed" }
            if (resources != null) return@withLock

            var options: OrtSession.SessionOptions? = null
            var encoder: OrtSession? = null
            var decoder: OrtSession? = null
            var decoderWithPast: OrtSession? = null
            var tokenizer: M2m100Tokenizer? = null
            try {
                requireModelFile(M2m100ModelRepository.ENCODER_FILE_NAME)
                requireModelFile(M2m100ModelRepository.DECODER_FILE_NAME)
                requireModelFile(M2m100ModelRepository.DECODER_WITH_PAST_FILE_NAME)
                val sentencePieceFile = requireModelFile(M2m100ModelRepository.SENTENCEPIECE_FILE_NAME)
                val vocabularyFile = requireModelFile(M2m100ModelRepository.VOCABULARY_FILE_NAME)

                val environment = OrtEnvironment.getEnvironment()
                val createdOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                options = createdOptions
                val createdTokenizer = M2m100Tokenizer(sentencePieceFile, vocabularyFile)
                tokenizer = createdTokenizer
                val createdEncoder = environment.createSession(
                    File(modelDirectory, M2m100ModelRepository.ENCODER_FILE_NAME).absolutePath,
                    createdOptions
                )
                encoder = createdEncoder
                val createdDecoder = environment.createSession(
                    File(modelDirectory, M2m100ModelRepository.DECODER_FILE_NAME).absolutePath,
                    createdOptions
                )
                decoder = createdDecoder
                val createdDecoderWithPast = environment.createSession(
                    File(
                        modelDirectory,
                        M2m100ModelRepository.DECODER_WITH_PAST_FILE_NAME
                    ).absolutePath,
                    createdOptions
                )
                decoderWithPast = createdDecoderWithPast
                val contract = validateModelContract(
                    createdEncoder,
                    createdDecoder,
                    createdDecoderWithPast
                )
                resources = Resources(
                    environment = environment,
                    sessionOptions = createdOptions,
                    encoder = createdEncoder,
                    decoder = createdDecoder,
                    decoderWithPast = createdDecoderWithPast,
                    tokenizer = createdTokenizer,
                    contract = contract
                )
                Log.i(TAG, "Local M2M100 translator initialized")
            } catch (error: Throwable) {
                closeQuietly("decoder-with-past session", decoderWithPast)
                closeQuietly("decoder session", decoder)
                closeQuietly("encoder session", encoder)
                closeQuietly("tokenizer", tokenizer)
                closeQuietly("session options", options)
                Log.e(TAG, "Failed to initialize local M2M100 translator", error)
                throw error
            }
        }
    }

    /**
     * 把一条已确认外语字幕翻译为中文。
     *
     * 使用方法：
     * 必须在[initialize]成功后从单一翻译协程调用。相同语言及相同规范正文会命中32项进程内缓存；日志只
     * 记录阶段与异常，不记录原文或译文。协程取消会在每个ONNX步骤之间生效。
     *
     * @param text 英文或日文ASR最终字幕。
     * @param sourceLanguage 用户为本次会话选择的源语言。
     * @return M2M100贪心生成的中文译文。
     */
    suspend fun translate(
        text: String,
        sourceLanguage: LiveTranslationSourceLanguage
    ): String {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) return ""
        val cacheKey = LiveTranslationCacheKey(sourceLanguage, normalizedText)

        return withContext(Dispatchers.Default) {
            val context = coroutineContext
            lifecycleLock.withLock {
                context.ensureActive()
                check(!closed) { "Live translator is closed" }
                val active = checkNotNull(resources) { "Live translator is not initialized" }
                cache[cacheKey]?.let { return@withLock it }

                try {
                    runInference(active, normalizedText, sourceLanguage, context).also { translated ->
                        cache[cacheKey] = translated
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to translate a live caption locally", error)
                    throw error
                }
            }
        }
    }

    /**
     * 执行encoder、首次decoder与带缓存decoder的完整生成序列。
     *
     * 首次decoder只输入EOS 2并只保留48个KV缓存，不消费其logits；第一次带缓存调用强制输入中文语言
     * token 128102，得到首个正文token。后续每步只输入上一个token，更新24个decoder cache并始终复用
     * 首次结果中的24个encoder cache。Result保持到下一个run结束后再关闭，因此不复制大块Float缓存。
     */
    private fun runInference(
        active: Resources,
        text: String,
        sourceLanguage: LiveTranslationSourceLanguage,
        coroutineContext: CoroutineContext
    ): String {
        val encoded = M2m100Tokenizer.truncateEncoderInput(
            active.tokenizer.encode(text, sourceLanguage),
            MAX_INPUT_TOKEN_COUNT
        )
        val attentionMask = LongArray(encoded.size) { 1L }

        OnnxTensor.createTensor(
            active.environment,
            LongBuffer.wrap(encoded),
            longArrayOf(1L, encoded.size.toLong())
        ).use { inputIdsTensor ->
            OnnxTensor.createTensor(
                active.environment,
                LongBuffer.wrap(attentionMask),
                longArrayOf(1L, attentionMask.size.toLong())
            ).use { attentionMaskTensor ->
                active.encoder.run(
                    mapOf(
                        INPUT_IDS to inputIdsTensor,
                        ATTENTION_MASK to attentionMaskTensor
                    )
                ).use { encoderResult ->
                    val encoderHiddenStates = requireTensor(
                        encoderResult,
                        active.contract.encoderOutputName
                    )
                    return runDecoder(
                        active = active,
                        encoderHiddenStates = encoderHiddenStates,
                        encoderAttentionMask = attentionMaskTensor,
                        inputTokenCount = encoded.size,
                        coroutineContext = coroutineContext
                    )
                }
            }
        }
    }

    /** 运行首次缓存构造和后续贪心解码，并严格管理所有Result及输入tensor。 */
    private fun runDecoder(
        active: Resources,
        encoderHiddenStates: OnnxTensor,
        encoderAttentionMask: OnnxTensor,
        inputTokenCount: Int,
        coroutineContext: CoroutineContext
    ): String {
        val firstDecoderInput = longArrayOf(M2m100Tokenizer.EOS_TOKEN_ID)
        OnnxTensor.createTensor(
            active.environment,
            LongBuffer.wrap(firstDecoderInput),
            longArrayOf(1L, 1L)
        ).use { firstInputTensor ->
            active.decoder.run(
                mapOf(
                    INPUT_IDS to firstInputTensor,
                    ENCODER_HIDDEN_STATES to encoderHiddenStates,
                    ENCODER_ATTENTION_MASK to encoderAttentionMask
                )
            ).use { firstDecoderResult ->
                val outputTokens = ArrayList<Long>()
                val maxOutputTokens = (inputTokenCount * 2 + 8)
                    .coerceIn(MIN_OUTPUT_TOKEN_COUNT, MAX_OUTPUT_TOKEN_COUNT)
                var nextInputToken = M2m100Tokenizer.CHINESE_LANGUAGE_TOKEN_ID
                var previousStepResult: OrtSession.Result? = null
                var reachedEndOfSentence = false

                try {
                    for (stepIndex in 0 until maxOutputTokens) {
                        coroutineContext.ensureActive()
                        val stepInput = longArrayOf(nextInputToken)
                        val newStepResult = OnnxTensor.createTensor(
                            active.environment,
                            LongBuffer.wrap(stepInput),
                            longArrayOf(1L, 1L)
                        ).use { stepInputTensor ->
                            val stepInputs = buildWithPastInputs(
                                active = active,
                                stepInputTensor = stepInputTensor,
                                encoderHiddenStates = encoderHiddenStates,
                                encoderAttentionMask = encoderAttentionMask,
                                firstDecoderResult = firstDecoderResult,
                                previousStepResult = previousStepResult
                            )
                            active.decoderWithPast.run(stepInputs)
                        }

                        val generatedToken = try {
                            argmaxLastToken(requireTensor(newStepResult, LOGITS))
                        } catch (error: Throwable) {
                            newStepResult.close()
                            throw error
                        }
                        previousStepResult?.close()
                        previousStepResult = null
                        if (generatedToken == M2m100Tokenizer.EOS_TOKEN_ID) {
                            reachedEndOfSentence = true
                            newStepResult.close()
                            break
                        }

                        outputTokens += generatedToken
                        nextInputToken = generatedToken
                        previousStepResult = newStepResult
                    }
                } finally {
                    previousStepResult?.close()
                }
                val generatedTokens = outputTokens.toLongArray()
                val decodedText = active.tokenizer.decode(generatedTokens)
                return requireUsableM2m100Translation(
                    modelTokenIds = generatedTokens,
                    reachedEndOfSentence = reachedEndOfSentence,
                    decodedText = decodedText
                )
            }
        }
    }

    /** 按已核验的名称把输入token、固定encoder cache和当前decoder cache组装给带缓存decoder。 */
    private fun buildWithPastInputs(
        active: Resources,
        stepInputTensor: OnnxTensor,
        encoderHiddenStates: OnnxTensor,
        encoderAttentionMask: OnnxTensor,
        firstDecoderResult: OrtSession.Result,
        previousStepResult: OrtSession.Result?
    ): Map<String, OnnxTensor> = buildMap {
        put(INPUT_IDS, stepInputTensor)
        if (active.contract.withPastNeedsEncoderHiddenStates) {
            put(ENCODER_HIDDEN_STATES, encoderHiddenStates)
        }
        if (active.contract.withPastNeedsEncoderAttentionMask) {
            put(ENCODER_ATTENTION_MASK, encoderAttentionMask)
        }
        active.contract.encoderCacheInputNames.forEach { inputName ->
            put(
                inputName,
                requireTensor(
                    firstDecoderResult,
                    active.contract.initialCacheOutputByInput.getValue(inputName)
                )
            )
        }
        active.contract.decoderCacheInputNames.forEach { inputName ->
            val outputName = if (previousStepResult == null) {
                active.contract.initialCacheOutputByInput.getValue(inputName)
            } else {
                active.contract.nextDecoderCacheOutputByInput.getValue(inputName)
            }
            put(inputName, requireTensor(previousStepResult ?: firstDecoderResult, outputName))
        }
    }

    /** 从形状`[batch, sequence, vocabulary]`的最后一个位置执行确定性argmax。 */
    private fun argmaxLastToken(logits: OnnxTensor): Long {
        val shape = logits.info.shape
        check(shape.size == 3 && shape.last() > 0L) { "Unexpected M2M100 logits shape" }
        val vocabularySize = shape.last().toInt()
        val values = logits.floatBuffer
        val start = values.limit() - vocabularySize
        check(start >= 0) { "M2M100 logits buffer is smaller than its vocabulary" }
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (index in 0 until vocabularySize) {
            val value = values.get(start + index)
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        check(bestValue.isFinite()) { "M2M100 logits do not contain a finite value" }
        return bestIndex.toLong()
    }

    /**
     * 在真正推理前按名称、数量、数据类型和rank核验固定模型I/O，防止转换版本错配后静默输出错误。
     */
    private fun validateModelContract(
        encoder: OrtSession,
        decoder: OrtSession,
        decoderWithPast: OrtSession
    ): ModelContract {
        requireTensorNode(encoder.inputInfo, INPUT_IDS, OnnxJavaType.INT64, 2)
        requireTensorNode(encoder.inputInfo, ATTENTION_MASK, OnnxJavaType.INT64, 2)
        val encoderOutputName = encoder.outputNames.singleOrNull { outputName ->
            runCatching {
                requireTensorNode(encoder.outputInfo, outputName, OnnxJavaType.FLOAT, 3)
                true
            }.getOrDefault(false)
        } ?: error("M2M100 encoder must expose one FLOAT rank-3 output")

        requireTensorNode(decoder.inputInfo, INPUT_IDS, OnnxJavaType.INT64, 2)
        requireTensorNode(decoder.inputInfo, ENCODER_HIDDEN_STATES, OnnxJavaType.FLOAT, 3)
        requireTensorNode(decoder.inputInfo, ENCODER_ATTENTION_MASK, OnnxJavaType.INT64, 2)
        requireTensorNode(decoder.outputInfo, LOGITS, OnnxJavaType.FLOAT, 3)
        requireTensorNode(decoderWithPast.inputInfo, INPUT_IDS, OnnxJavaType.INT64, 2)
        requireTensorNode(decoderWithPast.outputInfo, LOGITS, OnnxJavaType.FLOAT, 3)

        val cacheInputs = decoderWithPast.inputNames.filterTo(linkedSetOf()) {
            it.startsWith(PAST_KEY_VALUES_PREFIX)
        }
        val decoderCacheInputs = cacheInputs.filterTo(linkedSetOf()) { it.contains(DECODER_CACHE_PART) }
        val encoderCacheInputs = cacheInputs.filterTo(linkedSetOf()) { it.contains(ENCODER_CACHE_PART) }
        check(cacheInputs.size == TOTAL_CACHE_TENSOR_COUNT)
        check(decoderCacheInputs.size == DECODER_CACHE_TENSOR_COUNT)
        check(encoderCacheInputs.size == ENCODER_CACHE_TENSOR_COUNT)
        cacheInputs.forEach { requireTensorNode(decoderWithPast.inputInfo, it, OnnxJavaType.FLOAT, 4) }

        val initialCacheMap = mapCacheOutputs(decoder.outputNames, cacheInputs)
        val nextDecoderCacheMap = mapCacheOutputs(decoderWithPast.outputNames, decoderCacheInputs)
        check(initialCacheMap.size == TOTAL_CACHE_TENSOR_COUNT)
        check(nextDecoderCacheMap.size == DECODER_CACHE_TENSOR_COUNT)

        return ModelContract(
            encoderOutputName = encoderOutputName,
            initialCacheOutputByInput = initialCacheMap,
            nextDecoderCacheOutputByInput = nextDecoderCacheMap,
            decoderCacheInputNames = decoderCacheInputs,
            encoderCacheInputNames = encoderCacheInputs,
            withPastNeedsEncoderHiddenStates = ENCODER_HIDDEN_STATES in decoderWithPast.inputNames,
            withPastNeedsEncoderAttentionMask = ENCODER_ATTENTION_MASK in decoderWithPast.inputNames
        )
    }

    /** 把`present.*`输出名转换为带缓存decoder要求的`past_key_values.*`输入名。 */
    private fun mapCacheOutputs(
        outputNames: Set<String>,
        expectedInputNames: Set<String>
    ): Map<String, String> = buildMap {
        outputNames.filter { it.startsWith(PRESENT_PREFIX) }.forEach { outputName ->
            val inputName = PAST_KEY_VALUES_PREFIX + outputName.removePrefix(PRESENT_PREFIX)
            if (inputName in expectedInputNames) put(inputName, outputName)
        }
    }

    /** 核验指定节点为预期类型与rank的tensor。 */
    private fun requireTensorNode(
        nodeMap: Map<String, NodeInfo>,
        name: String,
        expectedType: OnnxJavaType,
        expectedRank: Int
    ) {
        val tensorInfo = nodeMap[name]?.info as? TensorInfo
            ?: error("Required M2M100 tensor node is missing")
        check(tensorInfo.type == expectedType && tensorInfo.shape.size == expectedRank) {
            "M2M100 tensor node contract mismatch"
        }
    }

    /** 按名称取得Result中的tensor；禁止依赖可能变化的输出顺序。 */
    private fun requireTensor(result: OrtSession.Result, name: String): OnnxTensor {
        return result.get(name).orElse(null) as? OnnxTensor
            ?: error("Required M2M100 output tensor is missing")
    }

    /** @return 存在的固定模型文件；路径不满足契约时抛出异常。 */
    private fun requireModelFile(fileName: String): File {
        return File(modelDirectory, fileName).also { file ->
            require(file.isFile) { "Required M2M100 model file is missing" }
        }
    }

    /**
     * 幂等释放三个Session、Tokenizer、SessionOptions及小型译文缓存。
     *
     * @return 无返回值；如果翻译仍在执行会等待该步退出，避免关闭仍被原生推理读取的Result。
     */
    override fun close() {
        lifecycleLock.withLock {
            if (closed) return
            closed = true
            val active = resources
            resources = null
            cache.clear()
            closeQuietly("decoder-with-past session", active?.decoderWithPast)
            closeQuietly("decoder session", active?.decoder)
            closeQuietly("encoder session", active?.encoder)
            closeQuietly("tokenizer", active?.tokenizer)
            closeQuietly("session options", active?.sessionOptions)
            Log.i(TAG, "Local M2M100 translator closed")
        }
    }

    /** 关闭单一资源；日志使用英文且不包含路径或字幕。 */
    private fun closeQuietly(label: String, closeable: AutoCloseable?) {
        if (closeable == null) return
        runCatching { closeable.close() }
            .onFailure { error -> Log.e(TAG, "Failed to close $label", error) }
    }

    companion object {
        private const val TAG = "LiveM2mTranslator"
        private const val CACHE_CAPACITY = 32
        private const val CACHE_LOAD_FACTOR = 0.75f
        private const val MAX_INPUT_TOKEN_COUNT = 64
        private const val MIN_OUTPUT_TOKEN_COUNT = 16
        private const val MAX_OUTPUT_TOKEN_COUNT = 96
        private const val TOTAL_CACHE_TENSOR_COUNT = 48
        private const val DECODER_CACHE_TENSOR_COUNT = 24
        private const val ENCODER_CACHE_TENSOR_COUNT = 24
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val ENCODER_HIDDEN_STATES = "encoder_hidden_states"
        private const val ENCODER_ATTENTION_MASK = "encoder_attention_mask"
        private const val LOGITS = "logits"
        private const val PRESENT_PREFIX = "present."
        private const val PAST_KEY_VALUES_PREFIX = "past_key_values."
        private const val DECODER_CACHE_PART = ".decoder."
        private const val ENCODER_CACHE_PART = ".encoder."
    }
}

/**
 * 核验一次M2M100生成是否可以作为最终中文字幕展示。
 *
 * 使用方法：
 * 解码循环结束后传入全部正文token、是否实际遇到EOS以及词表解码结果。函数拒绝未完整结束、控制token、
 * 未知token、连续重复token和纯标点等异常输出，并复用字幕清洗规则移除不可见控制字符、替换字符及病态
 * 标点长串。调用方应让异常进入现有单句失败提示，不能把半句或伪乱码直接发布到悬浮窗。
 *
 * @param modelTokenIds 解码器在EOS之前生成的正文模型ID。
 * @param reachedEndOfSentence 生成循环是否真实遇到EOS 2，而不是达到长度上限后被截断。
 * @param decodedText 使用官方`vocab.json`反向解码得到的候选中文。
 * @return 已清洗且至少包含一个Unicode字母或数字的可展示译文。
 * @throws IllegalStateException 生成不完整、包含异常token或正文不可展示时抛出。
 */
internal fun requireUsableM2m100Translation(
    modelTokenIds: LongArray,
    reachedEndOfSentence: Boolean,
    decodedText: String
): String {
    check(reachedEndOfSentence) { "M2M100 generation did not terminate with EOS" }
    check(modelTokenIds.isNotEmpty()) { "M2M100 generation returned no text tokens" }

    var previousToken = Long.MIN_VALUE
    var identicalTokenRun = 0
    modelTokenIds.forEach { tokenId ->
        check(tokenId in M2M100_FIRST_TEXT_TOKEN_ID..M2M100_LAST_TEXT_TOKEN_ID) {
            "M2M100 generation returned a control, unknown, or out-of-range token"
        }
        identicalTokenRun = if (tokenId == previousToken) identicalTokenRun + 1 else 1
        check(identicalTokenRun < MAX_IDENTICAL_GENERATED_TOKEN_RUN) {
            "M2M100 generation entered a repeated-token loop"
        }
        previousToken = tokenId
    }

    val normalizedText = normalizeCaptionText(decodedText)
    check(normalizedText.isNotBlank()) { "M2M100 generation returned unusable text" }
    return normalizedText
}

private const val M2M100_FIRST_TEXT_TOKEN_ID = 4L
private const val M2M100_LAST_TEXT_TOKEN_ID = 128_003L
private const val MAX_IDENTICAL_GENERATED_TOKEN_RUN = 8

/**
 * 一条内存译文缓存的完整身份。
 *
 * @param sourceLanguage 会话源语言，避免相同正文跨语言误命中。
 * @param normalizedText 去除首尾空白后的完整正文，避免String哈希碰撞。
 */
internal data class LiveTranslationCacheKey(
    val sourceLanguage: LiveTranslationSourceLanguage,
    val normalizedText: String
)
