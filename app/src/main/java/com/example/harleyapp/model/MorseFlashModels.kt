package com.example.harleyapp.model

import java.text.Normalizer
import java.util.Locale

/**
 * 摩斯闪光每个时间单位的默认时长，单位为毫秒。
 *
 * 使用方法：
 * 页面未提供速度调节时，直接把本常量作为[createMorseFlashPlan]的`unitDurationMillis`参数；
 * 点、划和各级间隔都会以此为基准按国际摩斯时序成倍换算。
 */
const val MORSE_DEFAULT_UNIT_DURATION_MILLIS: Long = 200L

/**
 * 单次摩斯闪光允许接收的最大原始输入长度，按Unicode码点数量计算。
 *
 * 使用方法：
 * 文本输入框应使用本常量限制用户继续输入并显示字数；[createMorseFlashPlan]也会只处理前
 * 160个Unicode字符，并通过[MorseFlashPlan.inputWasTruncated]禁止调用方静默发送残缺内容。
 */
const val MORSE_MAX_INPUT_LENGTH: Int = 160

/**
 * 描述摩斯闪光计划中的一个连续亮灯或灭灯阶段。
 *
 * 使用方法：
 * 执行器按[durationMillis]等待当前阶段，并依据[isLightOn]设置手电筒开关；遍历完计划或
 * 用户主动停止后，都应由执行器再次确保手电筒处于关闭状态。
 *
 * @param isLightOn `true`表示本阶段打开手电筒，`false`表示本阶段关闭手电筒。
 * @param durationMillis 本阶段持续的毫秒数，由点、划或间隔的时间单位乘以单位时长得到。
 */
data class MorseFlashStep(
    val isLightOn: Boolean,
    val durationMillis: Long
)

/**
 * 一段文字完成摩斯编码后得到的不可变闪光计划。
 *
 * 使用方法：
 * 页面在文字变化时调用[createMorseFlashPlan]并保存本对象，使用[encodedText]展示编码预览，
 * 使用[totalDurationMillis]展示预计完成时间；只有[canStart]为`true`时才允许把[steps]交给
 * 手电筒执行器。不支持字符会保留在[unsupportedCharacters]中，便于一次性提示用户修改。
 *
 * @param encodedText 摩斯编码预览；字母之间用一个空格分隔，单词之间用` / `分隔。
 * @param steps 按执行顺序排列的亮灯和灭灯阶段；有效计划从亮灯开始并以亮灯结束。
 * @param unsupportedCharacters NFKC归一化后仍无法编码的Unicode字符，按原有出现顺序保留且不去重。
 * @param inputWasTruncated true表示原始输入超过160个Unicode字符，当前计划仅供预览且不可启动。
 */
data class MorseFlashPlan(
    val encodedText: String,
    val steps: List<MorseFlashStep>,
    val unsupportedCharacters: List<String>,
    val inputWasTruncated: Boolean
) {
    /**
     * 完整执行全部阶段所需的预计毫秒数。
     *
     * 页面可将本值格式化为秒或分秒；它只包含实际亮灭阶段，不包含启动准备和停止清理耗时。
     */
    val totalDurationMillis: Long = steps.sumOf(MorseFlashStep::durationMillis)

    /**
     * 当前计划是否具备启动条件。
     *
     * 至少存在一个亮灯阶段、输入未超长且没有不支持字符时为`true`；具体页面仍可叠加300秒
     * 上限、相机权限和设备是否支持闪光灯等运行条件。
     */
    val canStart: Boolean = !inputWasTruncated &&
        unsupportedCharacters.isEmpty() &&
        steps.any(MorseFlashStep::isLightOn)
}

/** 国际摩斯编码表，覆盖A-Z、0-9及常见ITU标点符号。 */
private val MORSE_CODE_BY_CHARACTER: Map<Char, String> = mapOf(
    'A' to ".-",
    'B' to "-...",
    'C' to "-.-.",
    'D' to "-..",
    'E' to ".",
    'F' to "..-.",
    'G' to "--.",
    'H' to "....",
    'I' to "..",
    'J' to ".---",
    'K' to "-.-",
    'L' to ".-..",
    'M' to "--",
    'N' to "-.",
    'O' to "---",
    'P' to ".--.",
    'Q' to "--.-",
    'R' to ".-.",
    'S' to "...",
    'T' to "-",
    'U' to "..-",
    'V' to "...-",
    'W' to ".--",
    'X' to "-..-",
    'Y' to "-.--",
    'Z' to "--..",
    '0' to "-----",
    '1' to ".----",
    '2' to "..---",
    '3' to "...--",
    '4' to "....-",
    '5' to ".....",
    '6' to "-....",
    '7' to "--...",
    '8' to "---..",
    '9' to "----.",
    '.' to ".-.-.-",
    ',' to "--..--",
    '?' to "..--..",
    '\'' to ".----.",
    '!' to "-.-.--",
    '/' to "-..-.",
    '(' to "-.--.",
    ')' to "-.--.-",
    '&' to ".-...",
    ':' to "---...",
    ';' to "-.-.-.",
    '=' to "-...-",
    '+' to ".-.-.",
    '-' to "-....-",
    '_' to "..--.-",
    '"' to ".-..-.",
    '$' to "...-..-",
    '@' to ".--.-."
)

/**
 * 编码阶段使用的内部字符记录，同时保存该字符前是否需要单词间隔。
 *
 * @param code 当前字符对应的点划编码。
 * @param hasWordGapBefore 当前字符与前一个可编码字符之间是否出现过连续空白。
 */
private data class EncodedMorseCharacter(
    val code: String,
    val hasWordGapBefore: Boolean
)

/**
 * 生成阶段毫秒数之前使用的内部时序，避免任意单位时长参与累计时发生静默溢出。
 *
 * @param isLightOn 本阶段是否亮灯。
 * @param durationUnits 本阶段占用的摩斯时间单位数，只会是1、3或7。
 */
private data class MorseFlashUnitStep(
    val isLightOn: Boolean,
    val durationUnits: Int
)

/**
 * 统计一段输入包含的Unicode字符数量，不把一个Emoji代理对误算成两个字符。
 *
 * 使用方法：
 * 文本输入组件可用本函数显示准确字数，并与[MORSE_MAX_INPUT_LENGTH]比较后决定是否提示截断。
 *
 * @param text 需要统计的原始字符串。
 *
 * @return 字符串内Unicode码点的数量；空字符串返回0。
 */
fun countMorseInputCharacters(text: String): Int {
    return text.codePointCount(0, text.length)
}

/**
 * 安全保留摩斯输入开头允许的Unicode字符，绝不会在Emoji代理对中间截断。
 *
 * 使用方法：
 * 页面收到文本框新值后先调用本函数再保存；若调用前的[countMorseInputCharacters]大于
 * [MORSE_MAX_INPUT_LENGTH]，应同时明确提示用户只保留了前面的内容。
 *
 * @param text 用户输入的原始字符串。
 *
 * @return 不超过[MORSE_MAX_INPUT_LENGTH]个Unicode字符的字符串；未超限时返回原字符串。
 */
fun limitMorseInputText(text: String): String {
    val characterCount = countMorseInputCharacters(text)
    if (characterCount <= MORSE_MAX_INPUT_LENGTH) return text

    val endIndex = text.offsetByCodePoints(0, MORSE_MAX_INPUT_LENGTH)
    return text.substring(0, endIndex)
}

/**
 * 把用户文字转换为可直接顺序执行的国际摩斯闪光计划。
 *
 * 使用方法：
 * 用户输入变化时调用本函数得到预览和预计时间，点击开始时使用同一个计划的[MorseFlashPlan.steps]
 * 驱动手电筒。函数先安全截取前[MORSE_MAX_INPUT_LENGTH]个Unicode字符，再执行NFKC归一化，
 * 因此全角英文字母、数字和已有对应编码的全角标点可以正常使用；英文小写随后统一按大写编码。
 * 连续空白只产生一次7单位的单词间隔，不支持字符或超长输入都会写入结果并使计划不可启动。
 *
 * 时间规则固定为：点亮1单位、划亮3单位、同一字符的符号间灭灯1单位、字符间灭灯3单位、
 * 单词间灭灯7单位。生成结果不包含开头或结尾的灭灯阶段。
 *
 * @param text 用户输入的原始文字；超过160个Unicode字符时只生成预览并禁止启动。
 * @param unitDurationMillis 一个摩斯时间单位的毫秒数，必须大于0且不能导致总时长超出Long范围。
 *
 * @return 包含编码预览、亮灭阶段、不支持字符、预计总时长和启动条件的[MorseFlashPlan]。
 *
 * @throws IllegalArgumentException 当单位时长不是正数，或换算后的预计总时长超出Long范围时抛出。
 */
fun createMorseFlashPlan(
    text: String,
    unitDurationMillis: Long = MORSE_DEFAULT_UNIT_DURATION_MILLIS
): MorseFlashPlan {
    require(unitDurationMillis > 0L) {
        "unitDurationMillis must be greater than zero."
    }

    val inputCharacterCount = countMorseInputCharacters(text)
    val normalizedText = Normalizer.normalize(
        limitMorseInputText(text),
        Normalizer.Form.NFKC
    )
    val encodedCharacters = mutableListOf<EncodedMorseCharacter>()
    val unsupportedCharacters = mutableListOf<String>()
    var hasPendingWordGap = false

    // 按Unicode码点读取，确保一个Emoji只形成一个不支持项，不会把代理对拆成两个乱码字符。
    normalizedText.codePoints().forEach { codePoint ->
        if (Character.isWhitespace(codePoint)) {
            if (encodedCharacters.isNotEmpty()) {
                hasPendingWordGap = true
            }
            return@forEach
        }

        val characterText = String(Character.toChars(codePoint))
        val normalizedCharacter = characterText
            .uppercase(Locale.ROOT)
            .singleOrNull()
        val code = normalizedCharacter?.let(MORSE_CODE_BY_CHARACTER::get)
        if (code == null) {
            unsupportedCharacters += characterText
            return@forEach
        }

        encodedCharacters += EncodedMorseCharacter(
            code = code,
            hasWordGapBefore = hasPendingWordGap && encodedCharacters.isNotEmpty()
        )
        hasPendingWordGap = false
    }

    val encodedText = buildString {
        encodedCharacters.forEachIndexed { index, character ->
            if (index > 0) {
                append(if (character.hasWordGapBefore) " / " else " ")
            }
            append(character.code)
        }
    }
    val unitSteps = buildList {
        encodedCharacters.forEachIndexed { characterIndex, character ->
            if (characterIndex > 0) {
                add(
                    MorseFlashUnitStep(
                        isLightOn = false,
                        durationUnits = if (character.hasWordGapBefore) 7 else 3
                    )
                )
            }

            character.code.forEachIndexed { symbolIndex, symbol ->
                if (symbolIndex > 0) {
                    add(MorseFlashUnitStep(isLightOn = false, durationUnits = 1))
                }
                add(
                    MorseFlashUnitStep(
                        isLightOn = true,
                        durationUnits = if (symbol == '.') 1 else 3
                    )
                )
            }
        }
    }
    val totalDurationUnits = unitSteps.sumOf { step -> step.durationUnits.toLong() }

    require(
        totalDurationUnits == 0L ||
            unitDurationMillis <= Long.MAX_VALUE / totalDurationUnits
    ) {
        "The Morse flash duration exceeds the supported Long range."
    }

    return MorseFlashPlan(
        encodedText = encodedText,
        steps = unitSteps.map { step ->
            MorseFlashStep(
                isLightOn = step.isLightOn,
                durationMillis = step.durationUnits * unitDurationMillis
            )
        },
        unsupportedCharacters = unsupportedCharacters.toList(),
        inputWasTruncated = inputCharacterCount > MORSE_MAX_INPUT_LENGTH
    )
}
