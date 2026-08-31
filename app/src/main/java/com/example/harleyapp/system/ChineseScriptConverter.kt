package com.example.harleyapp.system

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log

/**
 * 使用Android系统ICU把繁体中文正文转换为简体中文。
 *
 * 使用方法：
 * 电子书仓库在后台读取内置中文公版书后调用[toSimplified]。转换器由系统提供，不访问网络；
 * 英文与数字会保持不变。ICU Transliterator不是线程安全对象，因此所有调用在内部串行执行。
 *
 * @return 工具对象不在创建时返回值，通过[toSimplified]提供转换结果。
 */
object ChineseScriptConverter {

    private val converter: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            null
        } else {
            runCatching {
                Transliterator.getInstance(TRANSLITERATOR_ID)
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize Chinese script converter", error)
            }.getOrNull()
        }
    }

    /**
     * 把一段繁体或繁简混合中文转换为简体。
     *
     * @param text 电子书完整解析正文。
     * @return 转换后的简体正文；系统转换器不可用时返回原文，避免书籍无法打开。
     */
    fun toSimplified(text: String): String {
        if (text.isBlank()) return text

        // Android 8和9没有公开繁简转换器，直接保留正文可确保旧设备仍能正常阅读和翻页。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Chinese script conversion requires Android 10 or newer")
            return text
        }
        val currentConverter = converter ?: return text
        return synchronized(currentConverter) {
            runCatching { currentConverter.transliterate(text) }
                .onFailure { error -> Log.e(TAG, "Failed to simplify Chinese ebook text", error) }
                .getOrDefault(text)
        }
    }

    private const val TAG = "ChineseScriptConverter"
    private const val TRANSLITERATOR_ID = "Traditional-Simplified"
}
