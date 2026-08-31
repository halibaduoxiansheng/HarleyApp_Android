package com.example.harleyapp.system

import android.util.Log
import com.example.harleyapp.model.EbookTranslationDirection
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 阅读页离线翻译当前执行阶段。 */
enum class EbookTranslationStage {
    IDLE,
    PREPARING_MODEL,
    TRANSLATING,
    READY,
    ERROR
}

/**
 * 基于ML Kit设备端模型的中英文阅读页翻译器。
 *
 * 使用方法：
 * 阅读器创建一个实例，调用[translate]翻译当前页，离开阅读器时调用[close]释放翻译器。第一次使用
 * 某个语言方向时SDK会下载语言模型，模型准备完成后正文翻译在手机本地执行，不会把正文提交给服务器。
 * 类内部只保留少量当前会话缓存，关闭阅读器后自动清空。
 *
 * @return 该类通过公开方法返回结果，不在构造阶段返回内容。
 */
class EbookOfflineTranslator {

    private val translators = mutableMapOf<EbookTranslationDirection, Translator>()
    private val translationCache = object : LinkedHashMap<String, String>(
        MAX_CACHE_PAGES,
        CACHE_LOAD_FACTOR,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_PAGES
        }
    }

    /**
     * 准备对应方向的离线模型并翻译一页正文。
     *
     * @param text 当前页原文；空白正文直接返回空字符串。
     * @param direction 中译英或英译中方向。
     * @param onStageChanged 模型准备、翻译、成功或失败的阶段回调。
     * @return 完整页译文；任务取消或SDK失败时抛出对应异常交给页面显示。
     */
    suspend fun translate(
        text: String,
        direction: EbookTranslationDirection,
        onStageChanged: (EbookTranslationStage) -> Unit = {}
    ): String {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) return ""

        val cacheKey = "${direction.name}:${normalizedText.hashCode()}:${normalizedText.length}"
        synchronized(translationCache) {
            translationCache[cacheKey]?.let { cached ->
                onStageChanged(EbookTranslationStage.READY)
                return cached
            }
        }

        val translator = translatorFor(direction)
        return try {
            onStageChanged(EbookTranslationStage.PREPARING_MODEL)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()

            onStageChanged(EbookTranslationStage.TRANSLATING)
            val translatedChunks = splitEbookTranslationText(normalizedText).map { chunk ->
                translator.translate(chunk).awaitResult()
            }
            val translatedText = translatedChunks.joinToString(separator = "\n").trim()
            synchronized(translationCache) {
                translationCache[cacheKey] = translatedText
            }
            onStageChanged(EbookTranslationStage.READY)
            translatedText
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to translate ebook page offline", error)
            onStageChanged(EbookTranslationStage.ERROR)
            throw error
        }
    }

    /**
     * 释放已经创建的两个翻译方向和会话缓存。
     *
     * @return 无返回值；重复调用安全。
     */
    fun close() {
        translators.values.forEach(Translator::close)
        translators.clear()
        synchronized(translationCache) {
            translationCache.clear()
        }
    }

    /**
     * 按翻译方向复用SDK Translator，避免每次翻页反复初始化模型。
     *
     * @param direction 目标翻译方向。
     * @return 可用于该方向的设备端翻译器。
     */
    private fun translatorFor(direction: EbookTranslationDirection): Translator {
        return translators.getOrPut(direction) {
            val sourceLanguage: String
            val targetLanguage: String
            when (direction) {
                EbookTranslationDirection.ENGLISH_TO_CHINESE -> {
                    sourceLanguage = TranslateLanguage.ENGLISH
                    targetLanguage = TranslateLanguage.CHINESE
                }

                EbookTranslationDirection.CHINESE_TO_ENGLISH -> {
                    sourceLanguage = TranslateLanguage.CHINESE
                    targetLanguage = TranslateLanguage.ENGLISH
                }
            }
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )
        }
    }

    private companion object {
        const val TAG = "EbookOfflineTranslator"
        const val MAX_CACHE_PAGES = 24
        const val CACHE_LOAD_FACTOR = 0.75f
    }
}

/**
 * 把单页正文切成适合设备端翻译的小段，并尽量在句末或换行处断开。
 *
 * 使用方法：
 * 生产代码由[EbookOfflineTranslator.translate]调用；单元测试可直接验证切分后是否丢字。
 *
 * @param text 待翻译正文。
 * @param maximumCharacters 每段允许的最大字符数，默认480个字符。
 * @return 保持原始顺序的非空文本段；空白输入返回空列表。
 */
internal fun splitEbookTranslationText(
    text: String,
    maximumCharacters: Int = DEFAULT_TRANSLATION_CHUNK_CHARACTERS
): List<String> {
    require(maximumCharacters >= MIN_TRANSLATION_CHUNK_CHARACTERS) {
        "Translation chunk size is too small"
    }
    var remaining = text.trim()
    if (remaining.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    while (remaining.length > maximumCharacters) {
        val candidate = remaining.substring(0, maximumCharacters)
        val preferredCut = TRANSLATION_BREAK_CHARACTERS
            .map(candidate::lastIndexOf)
            .filter { index -> index >= maximumCharacters / 2 }
            .maxOrNull()
        val cutIndex = (preferredCut?.plus(1) ?: maximumCharacters).coerceAtLeast(1)
        candidate.substring(0, cutIndex).trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        remaining = remaining.substring(cutIndex).trimStart()
    }
    remaining.takeIf(String::isNotBlank)?.let(chunks::add)
    return chunks
}

/**
 * 把Google Task转换为可取消的协程等待。
 *
 * @param T Task成功结果类型。
 * @return Task成功值；失败时抛出原异常，取消时取消当前协程。
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}

private const val DEFAULT_TRANSLATION_CHUNK_CHARACTERS = 480
private const val MIN_TRANSLATION_CHUNK_CHARACTERS = 80
private val TRANSLATION_BREAK_CHARACTERS = charArrayOf('\n', '。', '！', '？', '.', '!', '?', ';', '；')
