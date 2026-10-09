package com.example.harleyapp.system.webcapture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 网页屏幕录制实际采用的视频编码参数。
 *
 * @param width 编码宽度，单位为像素。
 * @param height 编码高度，单位为像素。
 * @param frameRate 目标帧率。
 * @param bitRate 视频目标码率，单位bit/s。
 */
data class WebsiteRecordingVideoConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int
)

/**
 * 为当前设备H.264硬件编码器选择不超过屏幕原始像素的最高可用录屏参数。
 *
 * 使用方法：
 * 前台服务在创建MediaRecorder前调用。函数优先保留屏幕原始宽高，只有编码器明确不支持时才逐级
 * 缩小；帧率最高取60fps，码率按像素量计算并限制在编码器声明范围内。
 *
 * @param sourceWidth 当前全屏内容宽度，单位为像素。
 * @param sourceHeight 当前全屏内容高度，单位为像素。
 * @return 可交给MediaRecorder的视频配置。
 */
fun selectHighestWebsiteRecordingConfiguration(
    sourceWidth: Int,
    sourceHeight: Int
): WebsiteRecordingVideoConfiguration {
    val safeWidth = sourceWidth.coerceAtLeast(MIN_VIDEO_EDGE)
    val safeHeight = sourceHeight.coerceAtLeast(MIN_VIDEO_EDGE)
    val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { type ->
                type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            }
        }
    val videoCapabilities = runCatching {
        codecInfo?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
    }.getOrNull()

    if (videoCapabilities == null) {
        val width = safeWidth.floorToAlignment(DEFAULT_VIDEO_ALIGNMENT)
        val height = safeHeight.floorToAlignment(DEFAULT_VIDEO_ALIGNMENT)
        val frameRate = DEFAULT_VIDEO_FRAME_RATE
        return WebsiteRecordingVideoConfiguration(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = desiredVideoBitRate(width, height, frameRate)
        )
    }

    val widthAlignment = videoCapabilities.widthAlignment.coerceAtLeast(1)
    val heightAlignment = videoCapabilities.heightAlignment.coerceAtLeast(1)
    val selectedSize = RECORDING_SIZE_SCALES.firstNotNullOfOrNull { scale ->
        val candidateWidth = (safeWidth * scale).roundToInt().floorToAlignment(widthAlignment)
        val candidateHeight = (safeHeight * scale).roundToInt().floorToAlignment(heightAlignment)
        if (candidateWidth < MIN_VIDEO_EDGE || candidateHeight < MIN_VIDEO_EDGE) {
            null
        } else {
            runCatching {
                candidateWidth to candidateHeight
            }.getOrNull()?.takeIf { (width, height) ->
                runCatching { videoCapabilities.isSizeSupported(width, height) }
                    .getOrDefault(false)
            }
        }
    } ?: run {
        val fallbackWidth = safeWidth
            .coerceIn(
                videoCapabilities.supportedWidths.lower,
                videoCapabilities.supportedWidths.upper
            )
            .floorToAlignment(widthAlignment)
        val fallbackHeight = safeHeight
            .coerceIn(
                videoCapabilities.supportedHeights.lower,
                videoCapabilities.supportedHeights.upper
            )
            .floorToAlignment(heightAlignment)
        fallbackWidth to fallbackHeight
    }

    val supportedFrameRates = runCatching {
        videoCapabilities.getSupportedFrameRatesFor(selectedSize.first, selectedSize.second)
    }.getOrNull()
    val frameRate = floor(
        supportedFrameRates?.upper?.coerceAtMost(MAX_VIDEO_FRAME_RATE.toDouble())
            ?: DEFAULT_VIDEO_FRAME_RATE.toDouble()
    ).toInt().coerceAtLeast(MIN_VIDEO_FRAME_RATE)
    val desiredBitRate = desiredVideoBitRate(
        width = selectedSize.first,
        height = selectedSize.second,
        frameRate = frameRate
    )
    val bitRate = desiredBitRate.coerceIn(
        videoCapabilities.bitrateRange.lower,
        videoCapabilities.bitrateRange.upper
    )
    return WebsiteRecordingVideoConfiguration(
        width = selectedSize.first,
        height = selectedSize.second,
        frameRate = frameRate,
        bitRate = bitRate
    )
}

/**
 * 创建并准备只录画面的高画质MediaRecorder。
 *
 * @param outputFile 视频临时MP4文件。
 * @param configuration 已按设备能力选择的编码参数。
 * @return 已prepare但尚未start的MediaRecorder。
 */
fun createWebsiteVideoRecorder(
    outputFile: File,
    configuration: WebsiteRecordingVideoConfiguration
): MediaRecorder {
    outputFile.parentFile?.mkdirs()
    outputFile.delete()
    @Suppress("DEPRECATION")
    val recorder = MediaRecorder()
    try {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(outputFile.absolutePath)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(configuration.width, configuration.height)
        recorder.setVideoFrameRate(configuration.frameRate)
        recorder.setVideoEncodingBitRate(configuration.bitRate)
        recorder.prepare()
        return recorder
    } catch (error: Throwable) {
        runCatching { recorder.release() }
        throw error
    }
}

/**
 * 把MediaRecorder画面轨与可选AAC音轨合并到最终MP4。
 *
 * 使用方法：
 * 服务停止全部编码器后调用。音轨为空、损坏或不存在时只复制视频轨；两个提取器都从首个样本归零，
 * 并按时间顺序交错写入，避免长录屏产生音画时间戳倒退。
 *
 * @param videoFile MediaRecorder生成的只含画面临时文件。
 * @param audioFile 可选的只含AAC音轨临时文件。
 * @param destination 系统MediaStore待写入文件描述符。
 * @param audioStartOffsetMicros 音频相对视频实际晚启动的时间，单位为微秒。
 * @return 最终文件实际包含AAC音轨时返回true，否则返回false。
 */
fun muxWebsiteRecording(
    videoFile: File,
    audioFile: File?,
    destination: FileDescriptor,
    audioStartOffsetMicros: Long = 0L
): Boolean {
    val videoExtractor = MediaExtractor()
    val audioExtractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        videoExtractor.setDataSource(videoFile.absolutePath)
        val videoSourceTrack = findTrack(videoExtractor, VIDEO_MIME_PREFIX)
        check(videoSourceTrack >= 0) { "Recorded file does not contain a video track" }
        videoExtractor.selectTrack(videoSourceTrack)
        val videoFormat = videoExtractor.getTrackFormat(videoSourceTrack)

        val audioSourceTrack = audioFile
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { file ->
                runCatching {
                    audioExtractor.setDataSource(file.absolutePath)
                    findTrack(audioExtractor, AUDIO_MIME_PREFIX)
                }.getOrElse { error ->
                    Log.w(TAG, "Unable to read captured playback audio track", error)
                    -1
                }
            }
            ?: -1
        val hasAudioTrack = audioSourceTrack >= 0
        if (hasAudioTrack) {
            audioExtractor.selectTrack(audioSourceTrack)
        }

        muxer = MediaMuxer(destination, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val destinationVideoTrack = muxer.addTrack(videoFormat)
        val destinationAudioTrack = if (hasAudioTrack) {
            muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
        } else {
            -1
        }
        if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
        }
        muxer.start()

        val videoFirstTime = videoExtractor.sampleTime.coerceAtLeast(0L)
        val audioFirstTime = if (hasAudioTrack) {
            audioExtractor.sampleTime.coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val videoBuffer = ByteBuffer.allocateDirect(sampleBufferSize(videoFormat))
        val audioFormat = if (hasAudioTrack) {
            audioExtractor.getTrackFormat(audioSourceTrack)
        } else {
            null
        }
        val audioBuffer = audioFormat?.let { ByteBuffer.allocateDirect(sampleBufferSize(it)) }
        val sampleInfo = MediaCodec.BufferInfo()

        var videoAvailable = videoExtractor.sampleTime >= 0L
        var audioAvailable = hasAudioTrack && audioExtractor.sampleTime >= 0L
        while (videoAvailable || audioAvailable) {
            val videoTime = if (videoAvailable) {
                videoExtractor.sampleTime - videoFirstTime
            } else {
                Long.MAX_VALUE
            }
            val audioTime = if (audioAvailable) {
                audioExtractor.sampleTime - audioFirstTime + audioStartOffsetMicros.coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
            if (videoTime <= audioTime) {
                videoAvailable = writeCurrentSample(
                    extractor = videoExtractor,
                    muxer = muxer,
                    destinationTrack = destinationVideoTrack,
                    buffer = videoBuffer,
                    normalizedTimeMicros = videoTime,
                    info = sampleInfo
                )
            } else {
                audioAvailable = writeCurrentSample(
                    extractor = audioExtractor,
                    muxer = muxer,
                    destinationTrack = destinationAudioTrack,
                    buffer = requireNotNull(audioBuffer),
                    normalizedTimeMicros = audioTime,
                    info = sampleInfo
                )
            }
        }
        return hasAudioTrack
    } finally {
        runCatching { videoExtractor.release() }
        runCatching { audioExtractor.release() }
        muxer?.let { activeMuxer ->
            runCatching { activeMuxer.stop() }
            runCatching { activeMuxer.release() }
        }
    }
}

/** @return 当前整数向下对齐后的正数尺寸。 */
private fun Int.floorToAlignment(alignment: Int): Int {
    val safeAlignment = alignment.coerceAtLeast(1)
    return (this / safeAlignment * safeAlignment).coerceAtLeast(safeAlignment)
}

/** @return 按像素、帧率和高质量系数计算的H.264目标码率。 */
private fun desiredVideoBitRate(width: Int, height: Int, frameRate: Int): Int {
    return (width.toLong() * height * frameRate * BITS_PER_PIXEL_PER_FRAME)
        .toLong()
        .coerceIn(MIN_VIDEO_BIT_RATE.toLong(), MAX_VIDEO_BIT_RATE.toLong())
        .toInt()
}

/** @return 第一个MIME前缀匹配的轨道索引，不存在时返回-1。 */
private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
    for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        if (format.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
            return index
        }
    }
    return -1
}

/** @return 单次样本缓冲区大小，至少保留4MB以容纳高码率关键帧。 */
private fun sampleBufferSize(format: MediaFormat): Int {
    val declaredSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
    } else {
        0
    }
    return declaredSize.coerceIn(MIN_SAMPLE_BUFFER_BYTES, MAX_SAMPLE_BUFFER_BYTES)
}

/**
 * 写入提取器当前样本并前进到下一样本。
 *
 * @return 前进后仍存在下一样本时返回true。
 */
private fun writeCurrentSample(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    destinationTrack: Int,
    buffer: ByteBuffer,
    normalizedTimeMicros: Long,
    info: MediaCodec.BufferInfo
): Boolean {
    buffer.clear()
    val sampleSize = extractor.readSampleData(buffer, 0)
    if (sampleSize < 0) return false
    // 自身生成的MP4是明文完整采样；重封装时仅把关键帧标记转换为MediaCodec标记空间。
    val bufferFlags = if (
        extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
    ) {
        MediaCodec.BUFFER_FLAG_KEY_FRAME
    } else {
        0
    }
    info.set(
        0,
        sampleSize,
        normalizedTimeMicros.coerceAtLeast(0L),
        bufferFlags
    )
    muxer.writeSampleData(destinationTrack, buffer, info)
    return extractor.advance() && extractor.sampleTime >= 0L
}

private const val TAG = "WebsiteRecordingMedia"
private const val VIDEO_MIME_PREFIX = "video/"
private const val AUDIO_MIME_PREFIX = "audio/"
private const val MIN_VIDEO_EDGE = 320
private const val DEFAULT_VIDEO_ALIGNMENT = 2
private const val DEFAULT_VIDEO_FRAME_RATE = 30
private const val MIN_VIDEO_FRAME_RATE = 24
private const val MAX_VIDEO_FRAME_RATE = 60
private const val BITS_PER_PIXEL_PER_FRAME = 0.16
private const val MIN_VIDEO_BIT_RATE = 8_000_000
private const val MAX_VIDEO_BIT_RATE = 100_000_000
private const val MIN_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
private const val MAX_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024
private val RECORDING_SIZE_SCALES = listOf(1.0, 0.9, 0.8, 0.75, 2.0 / 3.0, 0.5, 0.4)
