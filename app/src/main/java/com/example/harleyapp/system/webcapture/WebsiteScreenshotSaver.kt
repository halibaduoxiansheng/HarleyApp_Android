package com.example.harleyapp.system.webcapture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.core.graphics.createBitmap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 网页全屏截图的最终结果。
 *
 * @param savedUri 成功写入系统相册的图片Uri；失败时为null。
 * @param message 可直接显示给用户的中文结果说明。
 */
data class WebsiteScreenshotResult(
    val savedUri: Uri?,
    val message: String
) {
    /** 截图和相册写入均成功时返回true。 */
    val isSuccess: Boolean
        get() = savedUri != null
}

/**
 * 使用PixelCopy截取网页全屏View所在窗口，并把PNG原分辨率画面保存到系统相册。
 *
 * 使用方法：
 * 全屏工具栏先隐藏自身并等待一帧，再调用[captureAndSave]。Android 10及以上直接写入
 * Pictures/HarleyApp；Android 9及以下需要调用方先取得WRITE_EXTERNAL_STORAGE权限。
 * PixelCopy能读取SurfaceView最近一帧，比View.draw更适合Chromium视频；DRM或安全Surface拒绝
 * 复制时返回失败，不会创建一个空文件。
 *
 * @param context Application或Activity上下文，用于访问MediaStore。
 */
class WebsiteScreenshotSaver(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 截取指定全屏内容区域并保存为无损PNG。
     *
     * @param window 当前Activity窗口，PixelCopy从其合成Surface读取像素。
     * @param fullscreenView Chromium交给宿主的全屏View，用于确定准确截图区域。
     * @return 截图保存结果；函数不会抛出可恢复的复制或存储异常。
     */
    suspend fun captureAndSave(
        window: Window,
        fullscreenView: View
    ): WebsiteScreenshotResult {
        val captureRect = Rect()
        val hasVisibleRect = fullscreenView.getGlobalVisibleRect(captureRect)
        if (!hasVisibleRect || captureRect.width() <= 0 || captureRect.height() <= 0) {
            return WebsiteScreenshotResult(
                savedUri = null,
                message = "当前视频画面尚未准备好，请稍后重试"
            )
        }

        val bitmap = runCatching {
            createBitmap(
                captureRect.width(),
                captureRect.height(),
                Bitmap.Config.ARGB_8888
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to allocate website screenshot bitmap", error)
            return WebsiteScreenshotResult(null, "截图内存不足，请关闭部分后台应用后重试")
        }

        val copyResult = copyWindowRegion(
            window = window,
            sourceRect = captureRect,
            destination = bitmap
        )
        if (copyResult != PixelCopy.SUCCESS) {
            bitmap.recycle()
            Log.w(TAG, "Website screenshot PixelCopy failed with code $copyResult")
            return WebsiteScreenshotResult(
                savedUri = null,
                message = "该视频画面受保护或暂时无法截图"
            )
        }

        return try {
            val savedUri = withContext(Dispatchers.IO) {
                saveBitmap(bitmap)
            }
            if (savedUri == null) {
                WebsiteScreenshotResult(null, "截图保存失败，请检查存储空间后重试")
            } else {
                WebsiteScreenshotResult(savedUri, "截图已保存到相册 HarleyApp")
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 把窗口指定矩形异步复制进目标Bitmap。
     *
     * @param window 当前Activity窗口。
     * @param sourceRect 相对窗口的截图区域。
     * @param destination 尺寸与截图区域一致的可写Bitmap。
     * @return PixelCopy状态码。
     */
    private suspend fun copyWindowRegion(
        window: Window,
        sourceRect: Rect,
        destination: Bitmap
    ): Int = suspendCancellableCoroutine { continuation ->
        try {
            PixelCopy.request(
                window,
                sourceRect,
                destination,
                { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Failed to start website screenshot PixelCopy", error)
            if (continuation.isActive) {
                continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
            }
        }
    }

    /**
     * 按Android版本把PNG写入公共图片集合。
     *
     * @param bitmap 已完成PixelCopy的原分辨率位图。
     * @return 成功写入的MediaStore Uri；任一步失败时删除半成品并返回null。
     */
    private fun saveBitmap(bitmap: Bitmap): Uri? {
        val resolver = appContext.contentResolver
        val displayName = "Harley_screenshot_${timestamp()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, SCREENSHOT_MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$MEDIA_FOLDER_NAME"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val publicDirectory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    MEDIA_FOLDER_NAME
                )
                if (!publicDirectory.exists() && !publicDirectory.mkdirs()) {
                    return null
                }
                put(MediaStore.Images.Media.DATA, File(publicDirectory, displayName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            val written = resolver.openOutputStream(uri, "w")?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            } == true
            check(written) { "Bitmap compression returned false" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            uri
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to save website screenshot", error)
            resolver.delete(uri, null, null)
            null
        }
    }

    /** @return 用于文件名且不包含路径特殊字符的本地时间戳。 */
    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private companion object {
        const val TAG = "WebsiteScreenshot"
        const val MEDIA_FOLDER_NAME = "HarleyApp"
        const val SCREENSHOT_MIME_TYPE = "image/png"
    }
}
