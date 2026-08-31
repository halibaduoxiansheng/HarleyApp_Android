package com.example.harleyapp.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** 普通定时提醒与微信等待提醒各自独立保存提示音的目标类型。 */
enum class ReminderSoundTarget(val displayName: String) {
    SCHEDULED("普通定时提醒"),
    WECHAT("微信等待提醒")
}

/**
 * HarleyApp内置提示音配置。
 *
 * 使用方法：
 * 页面通过[entries]展示全部选项并使用[displayName]和[description]说明效果；保存时使用枚举名称，
 * 播放时由[AppReminderSoundPlayer]读取内部音符序列实时合成。所有非静音选项均为App自己的短音序，
 * 不读取手机来电铃声或系统通知铃声文件。
 *
 * @param displayName 展示给用户的提示音名称。
 * @param description 提示音听感和节奏说明。
 * @param steps 用于实时合成的音符与间隔序列。
 */
enum class AppReminderSound(
    val displayName: String,
    val description: String,
    internal val steps: List<ReminderSoundStep>
) {
    FUTURE_PULSE(
        displayName = "未来脉冲",
        description = "三段上升电子音，清晰醒目",
        steps = listOf(
            ReminderSoundStep(659.25, 150, 55),
            ReminderSoundStep(987.77, 170, 55),
            ReminderSoundStep(1_318.51, 340, 0)
        )
    ),
    STAR_BELL(
        displayName = "星光铃",
        description = "明亮的星点旋律，节奏舒展",
        steps = listOf(
            ReminderSoundStep(783.99, 190, 70),
            ReminderSoundStep(1_174.66, 240, 70),
            ReminderSoundStep(987.77, 180, 45),
            ReminderSoundStep(1_567.98, 360, 0)
        )
    ),
    CYBER_DOUBLE(
        displayName = "赛博双音",
        description = "两组短促电子脉冲，科技感更强",
        steps = listOf(
            ReminderSoundStep(880.00, 120, 45),
            ReminderSoundStep(1_319.00, 180, 110),
            ReminderSoundStep(880.00, 120, 45),
            ReminderSoundStep(1_480.00, 260, 0)
        )
    ),
    SOFT_WAVE(
        displayName = "柔和波纹",
        description = "温和的三和弦，不会过于刺耳",
        steps = listOf(
            ReminderSoundStep(523.25, 250, 65, 0.42),
            ReminderSoundStep(659.25, 270, 65, 0.42),
            ReminderSoundStep(783.99, 430, 0, 0.44)
        )
    ),
    CRYSTAL_RISE(
        displayName = "晶体上升",
        description = "四阶清脆上升音，适合定时提醒",
        steps = listOf(
            ReminderSoundStep(698.46, 130, 40),
            ReminderSoundStep(880.00, 150, 40),
            ReminderSoundStep(1_046.50, 180, 40),
            ReminderSoundStep(1_396.91, 330, 0)
        )
    ),
    VIBRATION_ONLY(
        displayName = "仅振动",
        description = "不播放声音，只保留系统允许的振动",
        steps = emptyList()
    );

    companion object {

        /**
         * 从本地保存值恢复提示音选项。
         *
         * @param storageValue SharedPreferences中保存的枚举名称，允许为空或旧版未知值。
         *
         * @return 匹配时返回对应提示音；未知值安全回退为[DEFAULT]。
         */
        fun fromStorageValue(storageValue: String?): AppReminderSound {
            return entries.firstOrNull { sound -> sound.name == storageValue }
                ?: DEFAULT
        }

        /** 新安装和旧版本迁移时使用的默认App内提示音。 */
        val DEFAULT = FUTURE_PULSE
    }
}

/**
 * 单个合成音符的参数。
 *
 * @param frequencyHz 基频赫兹数。
 * @param durationMillis 音符持续毫秒数。
 * @param gapAfterMillis 音符结束后的静音间隔毫秒数。
 * @param amplitude 相对振幅，范围0到1。
 */
internal data class ReminderSoundStep(
    val frequencyHz: Double,
    val durationMillis: Int,
    val gapAfterMillis: Int,
    val amplitude: Double = 0.58
)

/**
 * 持久化普通提醒和微信提醒各自选择的HarleyApp内置提示音。
 *
 * 使用方法：
 * 使用Application Context创建实例；打开选择器时调用[getSound]，用户确认后调用[saveSound]。
 * 后台声音服务同样调用[getSound]，因此退出页面或重启App后仍会使用已保存选择。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ReminderSoundRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取指定提醒类型当前保存的提示音。
     *
     * @param target 普通定时提醒或微信等待提醒。
     *
     * @return 已保存提示音；首次使用或旧值无效时返回默认“未来脉冲”。
     */
    fun getSound(target: ReminderSoundTarget): AppReminderSound {
        return AppReminderSound.fromStorageValue(
            preferences.getString(target.storageKey(), null)
        )
    }

    /**
     * 保存指定提醒类型的提示音。
     *
     * @param target 普通定时提醒或微信等待提醒。
     * @param sound 用户在App内确认的提示音。
     *
     * @return SharedPreferences同步写入成功返回true，否则返回false。
     */
    fun saveSound(target: ReminderSoundTarget, sound: AppReminderSound): Boolean {
        return preferences.edit().putString(target.storageKey(), sound.name).commit()
    }

    /** @return 当前提醒类型对应的稳定SharedPreferences键名。 */
    private fun ReminderSoundTarget.storageKey(): String {
        return when (this) {
            ReminderSoundTarget.SCHEDULED -> KEY_SCHEDULED_SOUND
            ReminderSoundTarget.WECHAT -> KEY_WECHAT_SOUND
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "reminder_sound_preferences"
        const val KEY_SCHEDULED_SOUND = "scheduled_sound"
        const val KEY_WECHAT_SOUND = "wechat_sound"
    }
}

/**
 * 使用AudioTrack实时合成并播放HarleyApp内置短提示音。
 *
 * 使用方法：
 * 页面试听或后台提醒服务创建实例后调用[play]；切换试听、关闭弹窗或服务结束时调用[stop]。
 * 播放使用闹钟音频用途和音量，与手机来电铃声文件完全分离。每次新播放会先停止旧音序，播放
 * 完成后工作线程自动释放AudioTrack。
 */
class AppReminderSoundPlayer {

    private val stateLock = Any()
    private var generation = 0L
    private var activeTrack: AudioTrack? = null

    /**
     * 播放一个内置提示音。
     *
     * @param sound 要试听或用于正式提醒的提示音。
     *
     * @return 音频设备接受播放或选择“仅振动”时返回true；构建、写入或播放失败时返回false。
     */
    fun play(sound: AppReminderSound): Boolean {
        stop()
        if (sound.steps.isEmpty()) {
            return true
        }

        return runCatching {
            val samples = synthesize(sound)
            val minimumBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(0)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(samples.size * Short.SIZE_BYTES, minimumBufferBytes))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            val writtenSamples = track.write(
                samples,
                0,
                samples.size,
                AudioTrack.WRITE_BLOCKING
            )
            check(writtenSamples == samples.size) {
                "Custom reminder sound write was incomplete"
            }
            track.setVolume(PLAYER_VOLUME)

            val currentGeneration = synchronized(stateLock) {
                generation += 1L
                activeTrack = track
                generation
            }
            track.play()
            startAutomaticRelease(
                track = track,
                expectedGeneration = currentGeneration,
                durationMillis = samples.size * 1_000L / SAMPLE_RATE_HZ
            )
            Log.i(TAG, "Custom reminder sound playback started: sound=${sound.name}")
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to play custom reminder sound", error)
        }.getOrDefault(false)
    }

    /**
     * 停止并释放当前提示音。
     *
     * @return 无返回值；当前没有播放时安全跳过。
     */
    fun stop() {
        val track = synchronized(stateLock) {
            generation += 1L
            activeTrack.also { activeTrack = null }
        }
        releaseTrack(track)
    }

    /**
     * 在音序自然结束后释放对应AudioTrack，并防止旧线程误停新试听。
     *
     * @param track 本次播放创建的AudioTrack。
     * @param expectedGeneration 本次播放代次编号。
     * @param durationMillis 合成PCM的预计持续时间。
     *
     * @return 无返回值；使用守护线程完成延迟释放。
     */
    private fun startAutomaticRelease(
        track: AudioTrack,
        expectedGeneration: Long,
        durationMillis: Long
    ) {
        Thread {
            runCatching {
                Thread.sleep(durationMillis + RELEASE_MARGIN_MILLIS)
            }
            val shouldRelease = synchronized(stateLock) {
                if (generation == expectedGeneration && activeTrack === track) {
                    activeTrack = null
                    true
                } else {
                    false
                }
            }
            if (shouldRelease) {
                releaseTrack(track)
            }
        }.apply {
            name = "HarleyReminderSoundRelease"
            isDaemon = true
            start()
        }
    }

    /**
     * 根据音符、包络和轻微二次谐波生成单声道16位PCM。
     *
     * @param sound 需要实时合成的提示音。
     *
     * @return 可直接写入AudioTrack的完整PCM采样数组。
     */
    private fun synthesize(sound: AppReminderSound): ShortArray {
        val totalSamples = sound.steps.sumOf { step ->
            millisToSamples(step.durationMillis + step.gapAfterMillis)
        }
        val output = ShortArray(totalSamples)
        var outputIndex = 0

        sound.steps.forEach { step ->
            val toneSamples = millisToSamples(step.durationMillis)
            val gapSamples = millisToSamples(step.gapAfterMillis)
            val envelopeSamples = millisToSamples(ENVELOPE_MILLIS)
                .coerceAtMost(toneSamples / 2)

            repeat(toneSamples) { sampleIndex ->
                val timeSeconds = sampleIndex.toDouble() / SAMPLE_RATE_HZ
                val attack = if (envelopeSamples == 0) {
                    1.0
                } else {
                    (sampleIndex.toDouble() / envelopeSamples).coerceAtMost(1.0)
                }
                val release = if (envelopeSamples == 0) {
                    1.0
                } else {
                    ((toneSamples - sampleIndex).toDouble() / envelopeSamples)
                        .coerceAtMost(1.0)
                }
                val envelope = minOf(attack, release)
                val fundamental = sin(2.0 * PI * step.frequencyHz * timeSeconds)
                val harmonic = sin(4.0 * PI * step.frequencyHz * timeSeconds) * 0.14
                val normalized = ((fundamental + harmonic) / 1.14) *
                    step.amplitude.coerceIn(0.0, 1.0) * envelope
                output[outputIndex++] = (normalized * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            outputIndex += gapSamples
        }
        return output
    }

    /** @return 指定毫秒数在当前采样率下对应的采样数量。 */
    private fun millisToSamples(durationMillis: Int): Int {
        return durationMillis.coerceAtLeast(0) * SAMPLE_RATE_HZ / 1_000
    }

    /** @return 无返回值；尽最大努力停止并释放指定AudioTrack。 */
    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) {
            return
        }
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to stop custom reminder sound", error)
        }
        runCatching(track::release).onFailure { error ->
            Log.w(TAG, "Failed to release custom reminder sound", error)
        }
    }

    private companion object {
        const val TAG = "ReminderSoundPlayer"
        const val SAMPLE_RATE_HZ = 44_100
        const val ENVELOPE_MILLIS = 14
        const val RELEASE_MARGIN_MILLIS = 180L
        const val PLAYER_VOLUME = 0.78f
    }
}
