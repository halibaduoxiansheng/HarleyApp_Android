package com.example.harleyapp.system.livetranslation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * 封装Android 10播放音频捕获AudioRecord的创建、读取和释放。
 *
 * 使用方法：
 * 前台服务取得本次MediaProjection后调用[create]，随后依次[start]、[read]；退出时先[stop]解除
 * 阻塞读取，再[release]。本类只请求媒体、游戏和未知用途音频，不申请语音通话或麦克风回退，
 * 也不会把PCM写入磁盘。
 *
 * @param audioRecord 已按AudioPlaybackCapture配置创建的AudioRecord。
 */
class PlaybackAudioCapture private constructor(
    private val audioRecord: AudioRecord
) {

    @Volatile
    private var released = false

    /** 每个PCM样本的采样率，供VAD和识别器统一使用。 */
    val sampleRate: Int = CAPTURE_SAMPLE_RATE_HZ

    /** 建议采集循环复用的ShortArray长度。 */
    val recommendedBufferSamples: Int = CAPTURE_BUFFER_SAMPLES

    /**
     * 开始读取系统允许捕获的播放声音。
     *
     * 启动过程中发生任何异常时，本函数会立即释放已经创建的AudioRecord；失败后的实例不可重用。
     *
     * @return 无返回值；AudioRecord未正确初始化或系统拒绝时抛出异常。
     */
    fun start() {
        check(!released) { "Playback AudioRecord has already been released" }
        try {
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Playback AudioRecord is not initialized"
            }
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Playback AudioRecord did not enter recording state"
            }
        } catch (error: Throwable) {
            runCatching(::release).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /**
     * 阻塞读取一批16位单声道PCM。
     *
     * 使用方法：
     * 只从单一采集协程调用；[stop]会让正在阻塞的读取尽快返回。返回负值是AudioRecord错误码，
     * 调用方必须结束当前会话，不能把未写入数组尾部交给识别器。
     *
     * @param destination 可复用的目标数组。
     * @return 实际有效样本数，或AudioRecord定义的负错误码。
     */
    fun read(destination: ShortArray): Int {
        check(!released) { "Playback AudioRecord has already been released" }
        return audioRecord.read(
            destination,
            0,
            destination.size,
            AudioRecord.READ_BLOCKING
        )
    }

    /**
     * 停止采集并解除可能存在的阻塞读取。
     *
     * @return 已经停止或本次成功停止时返回true；底层停止失败、可能仍有阻塞读取时返回false。
     */
    fun stop(): Boolean {
        if (released) return true
        return runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }.isSuccess
    }

    /**
     * 释放AudioRecord原生资源。
     *
     * 使用方法：
     * 采集协程退出且不再调用[read]后执行；内部会先尝试停止。
     *
     * @return 无返回值。
     */
    @Synchronized
    fun release() {
        if (released) return
        stop()
        audioRecord.release()
        released = true
    }

    companion object {
        const val CAPTURE_SAMPLE_RATE_HZ = 16_000
        private const val CAPTURE_BUFFER_DURATION_MILLIS = 200
        private const val BYTES_PER_PCM_16_SAMPLE = 2
        private const val CAPTURE_BUFFER_SAMPLES =
            CAPTURE_SAMPLE_RATE_HZ * CAPTURE_BUFFER_DURATION_MILLIS / 1_000

        /**
         * 用当前一次性MediaProjection令牌创建播放音频捕获器。
         *
         * @param context 用于在创建AudioRecord前再次确认可撤销录音权限的Android上下文。
         * @param mediaProjection 系统刚授权且尚未停止的MediaProjection。
         * @return 配置为16kHz、单声道、PCM16的捕获器，尚未开始读取。
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        fun create(
            context: Context,
            mediaProjection: MediaProjection
        ): PlaybackAudioCapture {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Record audio permission is not granted")
            }
            val captureConfiguration = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CAPTURE_SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minimumBufferBytes = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBufferBytes > 0) { "Unable to calculate playback capture buffer size" }
            val preferredBufferBytes = CAPTURE_BUFFER_SAMPLES * BYTES_PER_PCM_16_SAMPLE * 4
            val audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(max(minimumBufferBytes, preferredBufferBytes))
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build()
            return PlaybackAudioCapture(audioRecord)
        }
    }
}
