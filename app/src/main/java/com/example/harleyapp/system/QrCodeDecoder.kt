package com.example.harleyapp.system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * CameraX逐帧二维码分析器，只把亮度数据交给本机ZXing解码。
 *
 * 使用方法：
 * 创建CameraX ImageAnalysis后，通过`setAnalyzer`传入本类实例。一次识别成功后分析器自动停止
 * 回调，页面开始新一轮扫描时应创建新实例，避免同一二维码连续触发。
 *
 * @param onDecoded 首次识别到非空二维码文本后的回调，运行在线程池线程中，页面需切换到主线程。
 * @param onFailure 图像转换或解码器发生非“未识别”异常时的回调。
 */
class QrCodeAnalyzer(
    private val onDecoded: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(QR_DECODE_HINTS)
    }
    private var resultDelivered = false

    /**
     * 分析一帧YUV画面并在本机尝试识别二维码。
     *
     * @param image CameraX交付的单帧图像；本函数无论成功失败都会关闭图像。
     * @return 无返回值，识别结果通过构造回调交付。
     */
    override fun analyze(image: ImageProxy) {
        if (resultDelivered) {
            image.close()
            return
        }

        try {
            val luminance = extractCompactLuminance(image)
            val rotated = rotateLuminance(
                data = luminance,
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees
            )
            val source = PlanarYUVLuminanceSource(
                rotated.data,
                rotated.width,
                rotated.height,
                0,
                0,
                rotated.width,
                rotated.height,
                false
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            val text = result.text?.trim().orEmpty()
            if (text.isNotEmpty()) {
                resultDelivered = true
                onDecoded(text)
            }
        } catch (_: NotFoundException) {
            // 当前画面没有清晰二维码属于正常状态，下一帧继续识别。
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to analyze QR code frame", error)
            onFailure(error)
        } finally {
            reader.reset()
            image.close()
        }
    }
}

/**
 * 从系统相册选择的图片中识别第一个二维码。
 *
 * 使用方法：
 * 在协程中传入ContentResolver可读取的图片Uri。本函数自动按最长边上限缩放，避免超大照片占用
 * 过多内存；图片不会上传，也不会复制到App目录。
 *
 * @param context Android上下文，用于只读打开用户选择的图片。
 * @param imageUri 系统文件选择器返回的图片Uri。
 * @return 识别到的二维码原始文本；图片无二维码、无法读取或内容为空时返回null。
 */
suspend fun decodeQrCodeFromImage(context: Context, imageUri: Uri): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = loadScaledBitmap(context, imageUri)
            try {
                decodeBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to decode QR code from selected image", error)
        }.getOrNull()
    }
}

/** 从YUV_420_888首个亮度平面移除行填充，生成宽乘高的连续字节数组。 */
private fun extractCompactLuminance(image: ImageProxy): ByteArray {
    val plane = image.planes.firstOrNull() ?: throw IOException("Camera frame has no Y plane")
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val compactData = ByteArray(width * height)
    val rowData = ByteArray(rowStride)

    buffer.rewind()
    for (row in 0 until height) {
        val readableBytes = minOf(rowStride, buffer.remaining())
        if (readableBytes <= 0) break
        buffer.get(rowData, 0, readableBytes)
        for (column in 0 until width) {
            val sourceIndex = column * pixelStride
            if (sourceIndex < readableBytes) {
                compactData[row * width + column] = rowData[sourceIndex]
            }
        }
    }
    return compactData
}

/** 按CameraX提供的旋转角度调整亮度数组，让竖屏和横屏都使用正确宽高识别。 */
private fun rotateLuminance(
    data: ByteArray,
    width: Int,
    height: Int,
    rotationDegrees: Int
): RotatedLuminance {
    return when (rotationDegrees) {
        90 -> {
            val rotated = ByteArray(data.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    rotated[x * height + (height - y - 1)] = data[y * width + x]
                }
            }
            RotatedLuminance(rotated, height, width)
        }

        180 -> {
            val rotated = ByteArray(data.size)
            data.indices.forEach { index ->
                rotated[data.lastIndex - index] = data[index]
            }
            RotatedLuminance(rotated, width, height)
        }

        270 -> {
            val rotated = ByteArray(data.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    rotated[(width - x - 1) * height + y] = data[y * width + x]
                }
            }
            RotatedLuminance(rotated, height, width)
        }

        else -> RotatedLuminance(data, width, height)
    }
}

/** 在解码前按最长边限制读取图片，兼顾相册清晰度和内存占用。 */
private fun loadScaledBitmap(context: Context, imageUri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: throw IOException("Selected image could not be opened")
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException("Selected image dimensions were invalid")
    }

    var sampleSize = 1
    while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_IMAGE_EDGE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(imageUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: throw IOException("Selected image could not be decoded")
}

/** 把Bitmap像素交给ZXing，仅尝试二维码格式并返回第一个结果。 */
private fun decodeBitmap(bitmap: Bitmap): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    val reader = MultiFormatReader().apply {
        setHints(QR_DECODE_HINTS)
    }
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

/** 旋转后的连续亮度数组及其真实宽高。 */
private data class RotatedLuminance(
    val data: ByteArray,
    val width: Int,
    val height: Int
)

private val QR_DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.ALSO_INVERTED to true,
    DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
)

private const val TAG = "QrCodeDecoder"
private const val MAX_IMAGE_EDGE = 2_048
