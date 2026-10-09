package com.example.harleyapp.system.webcapture

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 网页视频录屏会话所处阶段。 */
enum class WebsiteRecordingStatus {
    IDLE,
    STARTING,
    RECORDING,
    SAVING,
    SAVED,
    ERROR
}

/**
 * 网页视频录屏前台服务向Compose公开的只读状态。
 *
 * @param status 当前会话阶段。
 * @param ownerTabId 发起录屏的网站标签标识，防止其他标签误停止或显示错误状态。
 * @param startedAtElapsedRealtime 实际开始编码时的系统单调时钟毫秒值。
 * @param width 实际编码宽度，单位为像素。
 * @param height 实际编码高度，单位为像素。
 * @param frameRate 实际目标帧率。
 * @param audioIncluded 是否成功启用了系统播放声音捕获。
 * @param savedUri 成功写入系统视频库后的Uri。
 * @param message 可直接展示给用户的中文状态或错误说明。
 */
data class WebsiteRecordingSnapshot(
    val status: WebsiteRecordingStatus = WebsiteRecordingStatus.IDLE,
    val ownerTabId: String? = null,
    val startedAtElapsedRealtime: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Int = 0,
    val audioIncluded: Boolean = false,
    val savedUri: Uri? = null,
    val message: String? = null
) {
    /** 当前状态是否仍占用一次录屏会话。 */
    val isBusy: Boolean
        get() = status == WebsiteRecordingStatus.STARTING ||
            status == WebsiteRecordingStatus.RECORDING ||
            status == WebsiteRecordingStatus.SAVING

    /** 当前是否已经开始向编码器写入画面。 */
    val isRecording: Boolean
        get() = status == WebsiteRecordingStatus.RECORDING
}

/**
 * 保存同一进程内网页录屏服务的最新状态。
 *
 * 使用方法：
 * Compose只读取[snapshot]；前台服务通过发布函数推进状态。新录屏开始时会覆盖上次完成提示，
 * 但已保存的系统相册文件不会受影响。
 */
object WebsiteRecordingSessionStore {

    private val mutableSnapshot = MutableStateFlow(WebsiteRecordingSnapshot())

    /** 当前网页录屏状态的只读流。 */
    val snapshot: StateFlow<WebsiteRecordingSnapshot> = mutableSnapshot.asStateFlow()

    /**
     * 发布系统授权完成后的初始化状态。
     *
     * @param ownerTabId 发起录屏的网站标签标识。
     * @return 无返回值。
     */
    fun publishStarting(ownerTabId: String) {
        mutableSnapshot.value = WebsiteRecordingSnapshot(
            status = WebsiteRecordingStatus.STARTING,
            ownerTabId = ownerTabId,
            message = "正在准备高画质录屏…"
        )
    }

    /**
     * 发布编码器已经开始写入画面的状态。
     *
     * @param ownerTabId 发起录屏的网站标签标识。
     * @param startedAtElapsedRealtime 开始编码的单调时钟毫秒值。
     * @param width 实际编码宽度。
     * @param height 实际编码高度。
     * @param frameRate 实际目标帧率。
     * @param audioIncluded 是否成功启用系统播放声音捕获。
     * @return 无返回值。
     */
    fun publishRecording(
        ownerTabId: String,
        startedAtElapsedRealtime: Long,
        width: Int,
        height: Int,
        frameRate: Int,
        audioIncluded: Boolean
    ) {
        mutableSnapshot.value = WebsiteRecordingSnapshot(
            status = WebsiteRecordingStatus.RECORDING,
            ownerTabId = ownerTabId,
            startedAtElapsedRealtime = startedAtElapsedRealtime,
            width = width,
            height = height,
            frameRate = frameRate,
            audioIncluded = audioIncluded,
            message = if (audioIncluded) {
                "正在录制画面和系统声音"
            } else {
                "正在录制高画质画面"
            }
        )
    }

    /** @return 无返回值；保留会话参数并把状态切换为正在自动保存。 */
    fun publishSaving() {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = WebsiteRecordingStatus.SAVING,
            message = "正在自动保存视频…"
        )
    }

    /**
     * 发布视频已经进入系统视频库的结果。
     *
     * @param savedUri 系统视频库Uri。
     * @param audioIncluded 最终文件是否包含系统声音轨。
     * @return 无返回值。
     */
    fun publishSaved(savedUri: Uri, audioIncluded: Boolean) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = WebsiteRecordingStatus.SAVED,
            audioIncluded = audioIncluded,
            savedUri = savedUri,
            message = if (audioIncluded) {
                "录屏已保存到相册 HarleyApp（含系统声音）"
            } else {
                "录屏已保存到相册 HarleyApp（无声视频）"
            }
        )
    }

    /**
     * 发布本次录屏未生成有效文件的结果。
     *
     * @param ownerTabId 发起录屏的网站标签标识；服务初始化前失败时也可传入。
     * @param message 面向用户的中文失败原因。
     * @return 无返回值。
     */
    fun publishError(ownerTabId: String?, message: String) {
        mutableSnapshot.value = WebsiteRecordingSnapshot(
            status = WebsiteRecordingStatus.ERROR,
            ownerTabId = ownerTabId,
            message = message
        )
    }

    /** @return 无返回值；用户开始下一次操作前可清理已显示的完成或错误状态。 */
    fun resetTerminalState() {
        if (!mutableSnapshot.value.isBusy) {
            mutableSnapshot.value = WebsiteRecordingSnapshot()
        }
    }
}
