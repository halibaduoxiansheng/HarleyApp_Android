package com.example.harleyapp.system.webcapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 把Android允许内录的系统播放声音编码为独立AAC/MP4音轨。
 *
 * 使用方法：
 * 网页录屏服务创建视频编码器后调用[create]，随后调用[start]。停止视频前调用[stopAndJoin]，成功
 * 时得到只含AAC音轨的MP4文件，再与画面轨合并。仅Android 10及以上可用；网站或播放器禁止内录
 * 时可能得到静音，本类不会回退录制麦克风。
 *
 * @param audioRecord 已配置播放声音捕获的AudioRecord。
 * @param encoder AAC编码器。
 * @param muxer 输出独立音轨的MediaMuxer。
 * @param outputFile 独立音轨临时文件。
 * @param sampleRate 采样率，单位Hz。
 * @param channelCount 声道数。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioMp4Recorder private constructor(
    private val audioRecord: AudioRecord,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int
) {

    private val running = AtomicBoolean(false)
    private val startedLatch = CountDownLatch(1)
    private var workerThread: Thread? = null
    @Volatile
    private var startupError: Throwable? = null
    @Volatile
    private var completedSuccessfully = false
    private var maximumAbsoluteSample = 0

    /** AudioRecord实际进入录制状态后的单调时钟纳秒值，用于与视频轨对齐。 */
    @Volatile
    var startedAtElapsedRealtimeNanos: Long? = null
        private set

    /**
     * 启动AudioRecord、AAC编码和独立音轨写入线程。
     *
     * @return 工作线程在限定时间内确认开始时返回true；失败会释放资源并返回false。
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        workerThread = Thread(::recordLoop, "Harley-Web-Audio").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        val signaled = runCatching {
            startedLatch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!signaled || startupError != null) {
            running.set(false)
            runCatching { audioRecord.stop() }
            runCatching { workerThread?.join(STOP_TIMEOUT_MILLIS) }
            return false
        }
        return true
    }

    /**
     * 停止采集并等待编码器写完EOS。
     *
     * @return 生成了有效AAC音轨时返回临时文件，否则删除半成品并返回null。
     */
    fun stopAndJoin(): File? {
        running.set(false)
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
        runCatching { workerThread?.join(STOP_TIMEOUT_MILLIS) }
        if (workerThread?.isAlive == true) {
            Log.w(TAG, "Playback audio encoder did not stop before timeout")
            workerThread?.interrupt()
        }
        return outputFile.takeIf { completedSuccessfully && it.isFile && it.length() > 0L }
            ?: run {
                outputFile.delete()
                null
            }
    }

    /**
     * 在独立线程中持续读取PCM、送入AAC编码器并写入MediaMuxer。
     *
     * @return 无返回值；所有原生资源均在finally中释放。
     */
    private fun recordLoop() {
        var muxerStarted = false
        var muxerTrackIndex = -1
        var totalFrames = 0L
        var inputEnded = false
        var outputEnded = false
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Playback AudioRecord is not initialized"
            }
            encoder.start()
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not start"
            }
            startedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            startedLatch.countDown()

            while (!outputEnded) {
                val inputIndex = if (inputEnded) {
                    MediaCodec.INFO_TRY_AGAIN_LATER
                } else {
                    encoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
                }
                if (!inputEnded && inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                        ?: error("AAC encoder input buffer is unavailable")
                    inputBuffer.clear()
                    if (running.get()) {
                        val readBytes = audioRecord.read(
                            inputBuffer,
                            inputBuffer.remaining(),
                            AudioRecord.READ_BLOCKING
                        )
                        if (readBytes > 0) {
                            val sampleView = inputBuffer
                                .duplicate()
                                .order(ByteOrder.nativeOrder())
                            sampleView.position(0)
                            sampleView.limit(readBytes)
                            val shortSamples = sampleView.asShortBuffer()
                            var sampleIndex = 0
                            while (sampleIndex < shortSamples.limit()) {
                                val value = kotlin.math.abs(shortSamples.get(sampleIndex).toInt())
                                if (value > maximumAbsoluteSample) {
                                    maximumAbsoluteSample = value
                                }
                                sampleIndex += AUDIO_LEVEL_SAMPLE_STRIDE
                            }
                            val presentationTimeMicros = totalFrames * MICROS_PER_SECOND / sampleRate
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                readBytes,
                                presentationTimeMicros,
                                0
                            )
                            totalFrames += readBytes / (BYTES_PER_PCM_SAMPLE * channelCount)
                        } else if (!running.get()) {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                totalFrames * MICROS_PER_SECOND / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        }
                    } else {
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            totalFrames * MICROS_PER_SECOND / sampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    }
                }

                var draining = true
                while (draining) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(
                        bufferInfo,
                        CODEC_TIMEOUT_MICROS
                    )) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed twice" }
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                ?: error("AAC encoder output buffer is unavailable")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                            }
                            val reachedEnd =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (reachedEnd) {
                                completedSuccessfully = muxerStarted &&
                                    totalFrames > 0L &&
                                    maximumAbsoluteSample >= MIN_AUDIBLE_ABSOLUTE_SAMPLE
                                outputEnded = true
                                draining = false
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            startupError = startupError ?: error
            Log.e(TAG, "Playback audio capture failed", error)
        } finally {
            startedLatch.countDown()
            running.set(false)
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    companion object {
        private const val TAG = "WebPlaybackAudio"
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE_HZ = 48_000
        private const val STEREO_BIT_RATE = 192_000
        private const val MONO_BIT_RATE = 128_000
        private const val BYTES_PER_PCM_SAMPLE = 2
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val CODEC_TIMEOUT_MICROS = 10_000L
        private const val START_TIMEOUT_SECONDS = 3L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val AUDIO_LEVEL_SAMPLE_STRIDE = 32
        private const val MIN_AUDIBLE_ABSOLUTE_SAMPLE = 16

        /**
         * 创建播放声音捕获器，优先48kHz立体声，不支持时回退48kHz单声道。
         *
         * @param context 用于检查RECORD_AUDIO权限。
         * @param projection 当前网页录屏使用的一次性MediaProjection。
         * @param outputFile AAC/MP4临时音轨文件。
         * @return 尚未启动的音轨录制器；权限或设备配置不支持时抛出异常，由服务回退为无声视频。
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        fun create(
            context: Context,
            projection: MediaProjection,
            outputFile: File
        ): PlaybackAudioMp4Recorder {
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "Record audio permission is not granted" }

            outputFile.parentFile?.mkdirs()
            outputFile.delete()
            val captureConfiguration = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val stereoRecord = createAudioRecord(
                context = context,
                captureConfiguration = captureConfiguration,
                channelMask = AudioFormat.CHANNEL_IN_STEREO,
                channelCount = 2
            )
            val channelCount: Int
            val audioRecord: AudioRecord
            if (stereoRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = stereoRecord
                channelCount = 2
            } else {
                runCatching { stereoRecord?.release() }
                audioRecord = createAudioRecord(
                    context = context,
                    captureConfiguration = captureConfiguration,
                    channelMask = AudioFormat.CHANNEL_IN_MONO,
                    channelCount = 1
                ) ?: error("Unable to create playback AudioRecord")
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "Playback AudioRecord is not initialized"
                }
                channelCount = 1
            }

            val format = MediaFormat.createAudioFormat(
                AUDIO_MIME_TYPE,
                SAMPLE_RATE_HZ,
                channelCount
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    if (channelCount == 2) STEREO_BIT_RATE else MONO_BIT_RATE
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_INPUT_BUFFER_BYTES)
            }
            val encoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            return PlaybackAudioMp4Recorder(
                audioRecord = audioRecord,
                encoder = encoder,
                muxer = muxer,
                outputFile = outputFile,
                sampleRate = SAMPLE_RATE_HZ,
                channelCount = channelCount
            )
        }

        /**
         * 创建指定声道布局的播放声音AudioRecord。
         *
         * @param context 用于在真正创建AudioRecord前再次确认可撤销的录音权限。
         * @param captureConfiguration 当前MediaProjection对应的捕获过滤器。
         * @param channelMask 输入声道掩码。
         * @param channelCount 声道数，用于计算缓冲区。
         * @return AudioRecord；平台拒绝配置时返回null。
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createAudioRecord(
            context: Context,
            captureConfiguration: AudioPlaybackCaptureConfiguration,
            channelMask: Int,
            channelCount: Int
        ): AudioRecord? {
            // 权限可能在界面授权后、服务真正创建采集器前被撤销，因此底层仍需就地复核。
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBytes <= 0) return null
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(channelMask)
                .build()
            return runCatching {
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(
                        max(minimumBytes, SAMPLE_RATE_HZ * channelCount * BYTES_PER_PCM_SAMPLE / 2)
                    )
                    .setAudioPlaybackCaptureConfig(captureConfiguration)
                    .build()
            }.getOrNull()
        }

        private const val AUDIO_INPUT_BUFFER_BYTES = 32_768
    }
}
