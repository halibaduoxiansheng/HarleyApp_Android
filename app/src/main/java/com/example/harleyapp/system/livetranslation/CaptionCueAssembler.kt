package com.example.harleyapp.system.livetranslation

import java.text.Normalizer

/**
 * 本地识别器一次partial或final输入产生的字幕合并结果。
 *
 * @param displayText 当前字幕浮层应显示的原文。
 * @param finalTextForTranslation 新确认且应提交本地翻译的final正文；partial或空白final时为null。
 * @param isFinalInput 本次调用是否来自识别器final回调。
 * @param accepted 本次输入是否被接纳为新的partial或final；重复partial和空白输入为false。
 * @param displayTextChanged 本次输入是否真正改变了浮层原文；final与其最后partial相同时可能为false。
 */
data class CaptionCueAssembly(
    val displayText: String,
    val finalTextForTranslation: String?,
    val isFinalInput: Boolean,
    val accepted: Boolean,
    val displayTextChanged: Boolean
) {

    /** @return 本次结果包含一条需要提交翻译的新final时返回true。 */
    val shouldTranslate: Boolean
        get() = finalTextForTranslation != null
}

/**
 * 合并流式语音识别的反复partial修订，并把每个独立语音片段的final交给翻译流程。
 *
 * 使用方法：
 * 一个识别会话创建一个实例；识别器partial回调调用[acceptPartial]，端点final回调调用
 * [acceptFinal]，会话结束或识别器重建时调用[reset]。本类只保留当前partial和最近final，不保存
 * 完整字幕历史，也不依赖Android运行时。所有调用应来自同一识别协程。
 */
class CaptionCueAssembler {

    private var currentPartialText = ""
    private var latestFinalText = ""

    /**
     * 接收识别器对当前话语的临时修订。
     *
     * 使用方法：
     * 可以高频传入SenseVoice等识别器的partial结果。多余空白会统一为单个空格；纯标点、未知字符、
     * 模型控制标签和与当前partial完全相同的结果会被忽略，避免噪声字幕或重复刷新。
     *
     * @param text 当前尚未确认的识别原文。
     * @return 浮层显示内容和是否发生有效变化；partial永远不会要求翻译final。
     */
    fun acceptPartial(text: String): CaptionCueAssembly {
        val normalizedText = normalizeCaptionText(text)
        val previousDisplayText = currentDisplayText()
        if (normalizedText.isBlank() || normalizedText == currentPartialText) {
            return CaptionCueAssembly(
                displayText = previousDisplayText,
                finalTextForTranslation = null,
                isFinalInput = false,
                accepted = false,
                displayTextChanged = false
            )
        }

        currentPartialText = normalizedText
        val displayText = currentDisplayText()
        return CaptionCueAssembly(
            displayText = displayText,
            finalTextForTranslation = null,
            isFinalInput = false,
            accepted = true,
            displayTextChanged = displayText != previousDisplayText
        )
    }

    /**
     * 接收识别器确认的一整条话语，并决定是否需要执行翻译。
     *
     * 使用方法：
     * 每次VAD确认一个独立语音片段时传入final正文。即使正文与上一片段完全相同，也必须作为
     * 新台词再次交给翻译，因为“はい”“Yes”等短句通常不足0.8秒，不一定先产生partial。上游应
     * 通过片段边界保证每个片段只调用一次，而不能用正文相等来猜测重复回调。
     *
     * @param text 已确认的识别原文。
     * @return 每个非空final都会通过finalTextForTranslation返回；空白final不会触发翻译。
     */
    fun acceptFinal(text: String): CaptionCueAssembly {
        val normalizedText = normalizeCaptionText(text)
        val previousDisplayText = currentDisplayText()
        if (normalizedText.isBlank()) {
            currentPartialText = ""
            return CaptionCueAssembly(
                displayText = currentDisplayText(),
                finalTextForTranslation = null,
                isFinalInput = true,
                accepted = false,
                displayTextChanged = currentDisplayText() != previousDisplayText
            )
        }

        latestFinalText = normalizedText
        currentPartialText = ""
        return CaptionCueAssembly(
            displayText = latestFinalText,
            finalTextForTranslation = latestFinalText,
            isFinalInput = true,
            accepted = true,
            displayTextChanged = latestFinalText != previousDisplayText
        )
    }

    /**
     * 清空当前partial、最近final和去重状态。
     *
     * 使用方法：
     * 新会话开始、识别器模型切换或采集流出现不可恢复错误后调用，防止上一会话的最后一句影响
     * 新会话去重。重复调用安全。
     *
     * @return 无返回值。
     */
    fun reset() {
        currentPartialText = ""
        latestFinalText = ""
    }

    /**
     * 读取当前最适合浮层展示的原文。
     *
     * @return 存在partial时返回最新partial，否则返回最近一次final；尚无识别结果时返回空字符串。
     */
    fun currentDisplayText(): String {
        return currentPartialText.ifBlank { latestFinalText }
    }
}

/**
 * 清理语音识别器输出，只让包含真实文字或数字的字幕继续显示和翻译。
 *
 * 使用方法：
 * [CaptionCueAssembler.acceptPartial]和[CaptionCueAssembler.acceptFinal]在比较、展示或提交翻译前统一
 * 调用。函数使用NFC保留日文姓名等兼容字符，只折叠空白、移除控制字符和SenseVoice元标签，并限制
 * 明显异常的同一标点长串；不会按音量、背景音乐或句子长度猜测对白是否有效。
 *
 * @param text SenseVoice返回的原始partial或final正文。
 * @return 可安全展示的正文；包含替换字符、未知词、孤立代理项或只有标点符号时返回空字符串。
 */
internal fun normalizeCaptionText(text: String): String {
    if (text.indexOf(UNICODE_REPLACEMENT_CHARACTER) >= 0 ||
        text.contains(UNKNOWN_TOKEN_TEXT, ignoreCase = true) ||
        containsUnpairedSurrogate(text)
    ) {
        return ""
    }

    val withoutMetadata = SENSE_VOICE_METADATA_TAG.replace(text, "")
    val normalized = Normalizer.normalize(withoutMetadata, Normalizer.Form.NFC)
    val cleaned = StringBuilder(normalized.length)
    var pendingSpace = false
    normalized.codePoints().forEach { codePoint ->
        val whitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
        val characterType = Character.getType(codePoint)
        when {
            whitespace -> pendingSpace = cleaned.isNotEmpty()
            Character.isISOControl(codePoint) || characterType == Character.FORMAT.toInt() -> Unit
            else -> {
                if (pendingSpace && cleaned.isNotEmpty()) cleaned.append(' ')
                cleaned.appendCodePoint(codePoint)
                pendingSpace = false
            }
        }
    }

    val compact = collapsePathologicalPunctuation(cleaned.toString().trim())
    val hasLexicalContent = compact.codePoints().anyMatch { codePoint ->
        Character.isLetterOrDigit(codePoint) && codePoint != JAPANESE_PROLONGED_SOUND_MARK
    }
    return compact.takeIf { hasLexicalContent } ?: ""
}

/**
 * 检查UTF-16字符串中是否存在无法组成合法Unicode码点的单独代理项。
 *
 * @param text 待检查字符串。
 * @return 高代理项后没有低代理项，或低代理项没有对应高代理项时返回true。
 */
private fun containsUnpairedSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val current = text[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
            }

            Character.isLowSurrogate(current) -> return true
            else -> index += 1
        }
    }
    return false
}

/**
 * 压缩同一种标点的病态长串，同时保留电影对白常见的问号加叹号和中文省略号组合。
 *
 * @param text 已完成Unicode及空白清理的字幕。
 * @return 仅缩短超出可读上限的相同标点后的字幕。
 */
private fun collapsePathologicalPunctuation(text: String): String {
    return PUNCTUATION_COLLAPSERS.fold(text) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }
}

private const val UNICODE_REPLACEMENT_CHARACTER = '\uFFFD'
private const val UNKNOWN_TOKEN_TEXT = "<unk>"
private const val JAPANESE_PROLONGED_SOUND_MARK = 0x30FC
private val SENSE_VOICE_METADATA_TAG = Regex("<\\|[^|<>\\r\\n]{1,40}\\|>")
private val PUNCTUATION_LIMITS = linkedMapOf(
    '.' to 3,
    '…' to 2,
    '!' to 2,
    '?' to 2,
    '！' to 2,
    '？' to 2,
    '。' to 1,
    '、' to 1,
    ',' to 1,
    ':' to 1,
    ';' to 1,
    '，' to 1,
    '：' to 1,
    '；' to 1
)
private val PUNCTUATION_COLLAPSERS = PUNCTUATION_LIMITS.map { (character, limit) ->
    Regex("${Regex.escape(character.toString())}{${limit + 1},}") to character.toString().repeat(limit)
}
