package com.example.harleyapp.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationModelSnapshot
import com.example.harleyapp.model.LiveTranslationModelStage
import com.example.harleyapp.model.LiveTranslationSessionStatus
import com.example.harleyapp.model.LiveTranslationSettings
import com.example.harleyapp.model.LiveTranslationSnapshot
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import com.example.harleyapp.model.MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT
import com.example.harleyapp.model.MAX_LIVE_TRANSLATION_TEXT_SIZE_SP
import com.example.harleyapp.model.MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT
import com.example.harleyapp.model.MIN_LIVE_TRANSLATION_TEXT_SIZE_SP
import com.example.harleyapp.system.LiveTranslationController
import com.example.harleyapp.ui.components.harleyCardBorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 字幕背景滑块每次调整的不透明度百分比。 */
private const val BACKGROUND_OPACITY_STEP_PERCENT = 5

/** 前台服务接到启动命令并发布STARTING状态的最长等待时间。 */
private const val START_REQUEST_TIMEOUT_MILLIS = 6_000L

/**
 * 用户一次点击“开始翻译”后的系统授权推进步骤。
 *
 * 使用方法：
 * 页面把步骤保存在可恢复状态中，每个Activity Result回调只推进到下一步，确保录音权限、悬浮窗
 * 权限、非阻断通知权限和MediaProjection授权不会同时弹出。WAITING状态用于防止重组重复启动系统页。
 */
private enum class LiveTranslationStartStep {
    NONE,
    CHECK_RECORD_AUDIO,
    WAITING_RECORD_AUDIO,
    WAITING_RECORD_AUDIO_SETTINGS,
    CHECK_OVERLAY,
    WAITING_OVERLAY,
    CHECK_NOTIFICATION,
    WAITING_NOTIFICATION,
    REQUEST_MEDIA_PROJECTION,
    WAITING_MEDIA_PROJECTION
}

/**
 * 显示跨应用实时翻译的模型准备、权限检查、字幕设置和会话控制页面。
 *
 * 使用方法：
 * 功能中心切换到LIVE_TRANSLATION详情时调用本函数。用户选择英语或日语并一次性准备共享的英日
 * 双语质量优先模型，再点击“开始内录并翻译”。页面依次申请录音、悬浮窗和系统内录授权；服务
 * 开始后即使返回功能中心也会继续工作，必须通过本页或前台服务通知明确停止。
 *
 * @param onBack 返回功能中心概览的回调；返回不会停止正在运行的翻译服务。
 * @param modifier 外层传入的安全区域和页面布局修饰器。
 * @return 无返回值，直接输出实时翻译设置与状态页面。
 */
@Composable
fun LiveTranslationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) {
        LiveTranslationController(context.applicationContext)
    }
    val sessionSnapshot by controller.sessionSnapshot.collectAsState()
    val modelSnapshot by controller.modelSnapshot.collectAsState()
    var settings by remember(controller) {
        mutableStateOf(controller.loadSettings())
    }
    var recordAudioGranted by remember {
        mutableStateOf(hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO))
    }
    var recordAudioRequestedBefore by remember(controller) {
        mutableStateOf(controller.hasRequestedRecordAudioPermission())
    }
    var recordAudioRequiresSettings by remember {
        mutableStateOf(
            !recordAudioGranted &&
                recordAudioRequestedBefore &&
                !shouldShowRecordAudioPermissionRationale(context)
        )
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    var overlayPermissionGranted by remember {
        mutableStateOf(controller.hasOverlayPermission())
    }
    var startStep by rememberSaveable {
        mutableStateOf(LiveTranslationStartStep.NONE)
    }
    var pageMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var pageMessageIsError by rememberSaveable {
        mutableStateOf(false)
    }
    var startRequestPending by rememberSaveable {
        mutableStateOf(false)
    }
    var lastSessionStatusName by rememberSaveable {
        mutableStateOf(sessionSnapshot.sessionStatus.name)
    }

    // 页面提示文字与错误级别始终成对更新，避免权限失败被误画成普通成功提示。
    val publishPageMessage: (String?, Boolean) -> Unit = { message, isError ->
        pageMessage = message
        pageMessageIsError = message != null && isError
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionData = result.data
        startStep = LiveTranslationStartStep.NONE
        if (result.resultCode != Activity.RESULT_OK || projectionData == null) {
            startRequestPending = false
            publishPageMessage("你已取消系统内录授权，未开始实时翻译。", false)
            return@rememberLauncherForActivityResult
        }

        startRequestPending = true
        runCatching {
            controller.start(result.resultCode, projectionData, settings)
        }.onSuccess {
            publishPageMessage("正在启动内录、离线识别和悬浮字幕，请稍候。", false)
        }.onFailure {
            startRequestPending = false
            publishPageMessage("系统未能启动实时翻译，请查看下方错误说明后重试。", true)
            controller.refresh()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionGranted = hasNotificationPermission(context)
        // 通知权限只影响通知抽屉中的可见性，不阻断用户已经主动发起的内录授权流程。
        if (startStep == LiveTranslationStartStep.WAITING_NOTIFICATION) {
            startStep = LiveTranslationStartStep.REQUEST_MEDIA_PROJECTION
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = controller.hasOverlayPermission()
        controller.refresh()
        if (startStep == LiveTranslationStartStep.WAITING_OVERLAY) {
            if (overlayPermissionGranted) {
                startStep = LiveTranslationStartStep.CHECK_NOTIFICATION
            } else {
                startStep = LiveTranslationStartStep.NONE
                publishPageMessage(
                    "需要允许HarleyApp显示在其他应用上层，才能展示悬浮字幕。",
                    true
                )
            }
        }
    }

    val recordAudioSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        recordAudioGranted = hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO)
        recordAudioRequiresSettings = !recordAudioGranted &&
            recordAudioRequestedBefore &&
            !shouldShowRecordAudioPermissionRationale(context)
        if (startStep == LiveTranslationStartStep.WAITING_RECORD_AUDIO_SETTINGS) {
            if (recordAudioGranted) {
                startStep = LiveTranslationStartStep.CHECK_OVERLAY
                publishPageMessage(null, false)
            } else {
                startStep = LiveTranslationStartStep.NONE
                publishPageMessage(
                    "录音权限仍未开启，请在HarleyApp应用设置的权限页面中允许录音。",
                    true
                )
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioGranted = granted &&
            hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO)
        recordAudioRequestedBefore = true
        recordAudioRequiresSettings = !recordAudioGranted &&
            !shouldShowRecordAudioPermissionRationale(context)
        if (startStep == LiveTranslationStartStep.WAITING_RECORD_AUDIO) {
            if (recordAudioGranted) {
                startStep = LiveTranslationStartStep.CHECK_OVERLAY
                publishPageMessage(null, false)
            } else {
                startStep = LiveTranslationStartStep.NONE
                publishPageMessage(
                    if (recordAudioRequiresSettings) {
                        "系统已不再弹出录音权限询问，请点击下方“打开设置”手动允许。"
                    } else {
                        "需要录音权限才能读取系统允许内录的播放声音。"
                    },
                    true
                )
            }
        }
    }

    val requestRecordAudioPermission: () -> Unit = {
        controller.markRecordAudioPermissionRequested()
        recordAudioRequestedBefore = true
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 从系统权限页返回时重新读取真实状态；不能把Activity Result是否返回误当成已经授权。
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recordAudioGranted =
                    hasRuntimePermission(context, Manifest.permission.RECORD_AUDIO)
                recordAudioRequestedBefore = controller.hasRequestedRecordAudioPermission()
                recordAudioRequiresSettings = !recordAudioGranted &&
                    recordAudioRequestedBefore &&
                    !shouldShowRecordAudioPermissionRationale(context)
                notificationPermissionGranted = hasNotificationPermission(context)
                overlayPermissionGranted = controller.hasOverlayPermission()
                controller.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(controller) {
        controller.refresh()
    }

    // 事实会话状态一旦推进，就清除上一阶段的瞬时提示，并解除本地启动提交锁。
    LaunchedEffect(sessionSnapshot.sessionStatus) {
        val currentStatusName = sessionSnapshot.sessionStatus.name
        if (currentStatusName != lastSessionStatusName) {
            lastSessionStatusName = currentStatusName
            startRequestPending = false
            publishPageMessage(null, false)
        }
    }

    // 正常服务会在数秒内发布STARTING；超时仍停在可启动状态时允许用户重试，避免页面永久锁死。
    LaunchedEffect(startRequestPending, sessionSnapshot.sessionStatus) {
        if (startRequestPending && sessionSnapshot.canStart) {
            delay(START_REQUEST_TIMEOUT_MILLIS)
            if (startRequestPending && sessionSnapshot.canStart) {
                startRequestPending = false
                publishPageMessage("启动请求未被系统接管，请确认HarleyApp仍在前台后重试。", true)
            }
        }
    }

    // 单一状态机串行启动权限页，避免多个Launcher同一帧竞争并导致某一步授权结果丢失。
    LaunchedEffect(startStep) {
        when (startStep) {
            LiveTranslationStartStep.CHECK_RECORD_AUDIO -> {
                if (recordAudioGranted) {
                    startStep = LiveTranslationStartStep.CHECK_OVERLAY
                } else if (recordAudioRequiresSettings) {
                    startStep = LiveTranslationStartStep.WAITING_RECORD_AUDIO_SETTINGS
                    runCatching {
                        recordAudioSettingsLauncher.launch(controller.applicationSettingsIntent())
                    }.onFailure {
                        startStep = LiveTranslationStartStep.NONE
                        publishPageMessage(
                            "无法打开HarleyApp应用设置，请手动进入系统设置并允许录音权限。",
                            true
                        )
                    }
                } else {
                    startStep = LiveTranslationStartStep.WAITING_RECORD_AUDIO
                    runCatching {
                        requestRecordAudioPermission()
                    }.onFailure {
                        startStep = LiveTranslationStartStep.NONE
                        publishPageMessage(
                            "无法打开录音权限请求，请在系统设置中为HarleyApp授权。",
                            true
                        )
                    }
                }
            }

            LiveTranslationStartStep.CHECK_OVERLAY -> {
                overlayPermissionGranted = controller.hasOverlayPermission()
                if (overlayPermissionGranted) {
                    startStep = LiveTranslationStartStep.CHECK_NOTIFICATION
                } else {
                    startStep = LiveTranslationStartStep.WAITING_OVERLAY
                    runCatching {
                        overlayPermissionLauncher.launch(controller.overlayPermissionIntent())
                    }.onFailure {
                        startStep = LiveTranslationStartStep.NONE
                        publishPageMessage(
                            "无法打开悬浮窗授权页，请在系统设置中允许显示在其他应用上层。",
                            true
                        )
                    }
                }
            }

            LiveTranslationStartStep.CHECK_NOTIFICATION -> {
                notificationPermissionGranted = hasNotificationPermission(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationPermissionGranted
                ) {
                    startStep = LiveTranslationStartStep.WAITING_NOTIFICATION
                    runCatching {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }.onFailure {
                        // 请求通知失败仍继续内录；前台服务状态仍可从本页和系统任务管理器查看。
                        startStep = LiveTranslationStartStep.REQUEST_MEDIA_PROJECTION
                    }
                } else {
                    startStep = LiveTranslationStartStep.REQUEST_MEDIA_PROJECTION
                }
            }

            LiveTranslationStartStep.REQUEST_MEDIA_PROJECTION -> {
                startStep = LiveTranslationStartStep.WAITING_MEDIA_PROJECTION
                runCatching {
                    mediaProjectionLauncher.launch(controller.mediaProjectionIntent())
                }.onFailure {
                    startStep = LiveTranslationStartStep.NONE
                    publishPageMessage(
                        "无法打开系统内录授权页，请重启HarleyApp后重试。",
                        true
                    )
                }
            }

            LiveTranslationStartStep.NONE,
            LiveTranslationStartStep.WAITING_RECORD_AUDIO,
            LiveTranslationStartStep.WAITING_RECORD_AUDIO_SETTINGS,
            LiveTranslationStartStep.WAITING_OVERLAY,
            LiveTranslationStartStep.WAITING_NOTIFICATION,
            LiveTranslationStartStep.WAITING_MEDIA_PROJECTION -> Unit
        }
    }

    val settingsLocked = sessionSnapshot.isSessionActive ||
        startStep != LiveTranslationStartStep.NONE ||
        startRequestPending
    val playbackCaptureSupported = controller.isPlaybackCaptureSupported()

    FeatureDetailScaffold(
        modifier = modifier,
        title = "实时翻译",
        subtitle = "内录英语或日语音频，并在其他应用上层显示简体中文字幕",
        onBack = onBack
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LiveTranslationUsageCard()
            }

            item {
                LiveTranslationLanguageCard(
                    settings = settings,
                    enabled = !settingsLocked,
                    onLanguageSelected = { language ->
                        val updatedSettings = settings.copy(sourceLanguage = language).normalized()
                        settings = updatedSettings
                        controller.saveSettings(updatedSettings)
                        controller.refresh()
                        val message = when (language) {
                            LiveTranslationSourceLanguage.ENGLISH ->
                                "已选择英语音频，目标语言为简体中文。"

                            LiveTranslationSourceLanguage.JAPANESE ->
                                "已选择日语音频，目标语言为简体中文。"
                        }
                        publishPageMessage(message, false)
                    }
                )
            }

            item {
                LiveTranslationModelCard(
                    snapshot = modelSnapshot,
                    controlsEnabled = !settingsLocked && playbackCaptureSupported,
                    onPrepare = {
                        publishPageMessage(null, false)
                        runCatching(controller::prepareModels).onFailure {
                            publishPageMessage(
                                "无法开始准备本地模型，请检查存储空间和网络后重试。",
                                true
                            )
                            controller.refresh()
                        }
                    },
                    onCancel = {
                        runCatching(controller::cancelModelPreparation).onFailure {
                            publishPageMessage(
                                "暂时无法取消模型准备，请等待当前安装步骤结束。",
                                true
                            )
                        }
                    }
                )
            }

            item {
                LiveTranslationPermissionCard(
                    playbackCaptureSupported = playbackCaptureSupported,
                    recordAudioGranted = recordAudioGranted,
                    recordAudioRequiresSettings = recordAudioRequiresSettings,
                    overlayPermissionGranted = overlayPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    controlsEnabled = !settingsLocked && playbackCaptureSupported,
                    onRequestRecordAudio = {
                        runCatching {
                            if (recordAudioRequiresSettings) {
                                recordAudioSettingsLauncher.launch(
                                    controller.applicationSettingsIntent()
                                )
                            } else {
                                requestRecordAudioPermission()
                            }
                        }.onFailure {
                            publishPageMessage(
                                "无法打开录音权限页面，请手动进入系统设置为HarleyApp授权。",
                                true
                            )
                        }
                    },
                    onRequestOverlay = {
                        overlayPermissionLauncher.launch(controller.overlayPermissionIntent())
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    }
                )
            }

            item {
                LiveTranslationCaptionSettingsCard(
                    settings = settings,
                    enabled = !settingsLocked,
                    onSettingsChanged = { updatedSettings ->
                        val normalizedSettings = updatedSettings.normalized()
                        settings = normalizedSettings
                        controller.saveSettings(normalizedSettings)
                    }
                )
            }

            item {
                LiveTranslationStatusCard(
                    snapshot = sessionSnapshot,
                    modelSnapshot = modelSnapshot
                )
            }

            pageMessage?.let { message ->
                item {
                    LiveTranslationMessageCard(
                        message = message,
                        isError = pageMessageIsError
                    )
                }
            }

            sessionSnapshot.errorMessage?.takeIf(String::isNotBlank)?.let { errorMessage ->
                item {
                    LiveTranslationMessageCard(message = errorMessage, isError = true)
                }
            }

            item {
                LiveTranslationActionCard(
                    playbackCaptureSupported = playbackCaptureSupported,
                    modelReady = modelSnapshot.isReady,
                    sessionSnapshot = sessionSnapshot,
                    startFlowActive = startStep != LiveTranslationStartStep.NONE ||
                        startRequestPending,
                    onStart = {
                        publishPageMessage(null, false)
                        if (!playbackCaptureSupported) {
                            publishPageMessage(
                                "当前系统低于Android 10，不支持播放音频内录。",
                                true
                            )
                        } else if (!modelSnapshot.isReady) {
                            publishPageMessage(
                                "请先一次性准备英日双语本地模型，再开始实时翻译。",
                                true
                            )
                        } else if (sessionSnapshot.canStart) {
                            startStep = LiveTranslationStartStep.CHECK_RECORD_AUDIO
                        }
                    },
                    onStop = {
                        startStep = LiveTranslationStartStep.NONE
                        runCatching(controller::stop).onSuccess {
                            publishPageMessage("正在停止翻译并移除悬浮字幕。", false)
                        }.onFailure {
                            publishPageMessage(
                                "停止请求未能发送，请稍后重试或从系统通知中停止。",
                                true
                            )
                            controller.refresh()
                        }
                    }
                )
            }

            item {
                LiveTranslationLimitationsCard()
            }
        }
    }
}

/**
 * 显示功能用途、离线边界和首次模型体积说明。
 *
 * @return 无返回值，直接输出页面首张说明卡。
 */
@Composable
private fun LiveTranslationUsageCard() {
    TranslationCard(title = "使用说明") {
        Text(
            text = "支持英语→简体中文、日语→简体中文。首次需联网下载约1.37 GB固定版本模型，安装后约1.45 GB；建议至少预留2 GB可用空间。模型准备完成后，识别和翻译运行时均不联网。",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "SenseVoice Small int8与sherpa-onnx负责本地语音识别，M2M100 418M与ONNX Runtime负责英语、日语直接翻译为中文。无需账号或云端额度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "质量优先版要求手机总内存至少8 GB，开始时还需约2 GB可用内存；不足时会在读取播放器声音前停止，并提示先关闭高负载应用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "开始后切换到电影App，HarleyApp会读取系统允许内录的播放声音，并把中文字幕显示在其他应用上层。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示真实支持的源语言二选一，目标语言固定为简体中文。
 *
 * @param settings 当前字幕与源语言设置。
 * @param enabled true允许切换语言，false表示会话或授权流程正在使用当前配置。
 * @param onLanguageSelected 用户选择英语或日语后的回调。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationLanguageCard(
    settings: LiveTranslationSettings,
    enabled: Boolean,
    onLanguageSelected: (LiveTranslationSourceLanguage) -> Unit
) {
    TranslationCard(title = "翻译语言") {
        Text(
            text = "源语言",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveTranslationSourceLanguage.entries.forEach { language ->
                FilterChip(
                    selected = settings.sourceLanguage == language,
                    onClick = { onLanguageSelected(language) },
                    enabled = enabled,
                    label = { Text(sourceLanguageName(language)) }
                )
            }
        }
        Text(
            text = "目标语言：简体中文（固定）",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "模型档位：质量优先版（固定）。英语和日语共用本地语音识别模型，并由M2M100直接翻译为中文，不经在线服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示共享英日双语离线模型的准备阶段、进度和操作按钮。
 *
 * @param snapshot 控制器公开的当前模型快照。
 * @param controlsEnabled true允许开始或取消准备，false表示会话配置已经锁定。
 * @param onPrepare 用户主动一次性准备英日双语模型的回调。
 * @param onCancel 用户取消下载或可取消准备阶段的回调。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationModelCard(
    snapshot: LiveTranslationModelSnapshot,
    controlsEnabled: Boolean,
    onPrepare: () -> Unit,
    onCancel: () -> Unit
) {
    TranslationCard(title = "本地模型") {
        StatusLine(
            title = "英日双语质量优先模型",
            value = modelStageName(snapshot.stage),
            positive = snapshot.stage == LiveTranslationModelStage.READY
        )
        if (snapshot.isPreparing) {
            snapshot.progressPercent?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "准备进度 $progress%",
                    style = MaterialTheme.typography.labelMedium
                )
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = snapshot.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (snapshot.stage == LiveTranslationModelStage.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        when {
            snapshot.isPreparing -> OutlinedButton(
                onClick = onCancel,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("取消准备")
            }

            !snapshot.isReady -> Button(
                onClick = onPrepare,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (snapshot.stage == LiveTranslationModelStage.ERROR) "重新准备模型" else "准备离线模型")
            }
        }
    }
}

/**
 * 显示实时翻译依赖的系统能力和可操作授权入口。
 *
 * @param playbackCaptureSupported 当前Android版本是否支持系统播放音频内录。
 * @param recordAudioGranted 是否已授予录音运行时权限。
 * @param recordAudioRequiresSettings true表示系统不再显示标准权限框，需要进入应用设置恢复。
 * @param overlayPermissionGranted 是否允许显示在其他应用上层。
 * @param notificationPermissionGranted 是否允许在通知抽屉显示前台服务通知。
 * @param controlsEnabled true允许进入各系统授权页。
 * @param onRequestRecordAudio 请求录音权限的回调。
 * @param onRequestOverlay 打开悬浮窗特殊权限页的回调。
 * @param onRequestNotification 请求通知权限的回调。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationPermissionCard(
    playbackCaptureSupported: Boolean,
    recordAudioGranted: Boolean,
    recordAudioRequiresSettings: Boolean,
    overlayPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    controlsEnabled: Boolean,
    onRequestRecordAudio: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestNotification: () -> Unit
) {
    TranslationCard(title = "权限与系统能力") {
        PermissionActionRow(
            title = "播放音频内录",
            granted = playbackCaptureSupported,
            missingText = "需要Android 10或更高版本"
        )
        PermissionActionRow(
            title = "录音权限",
            granted = recordAudioGranted,
            missingText = if (recordAudioRequiresSettings) {
                "系统已停止再次询问，请到应用设置中允许"
            } else {
                "需要授权"
            },
            actionText = if (recordAudioRequiresSettings) "打开设置" else "授权",
            actionEnabled = controlsEnabled,
            onAction = onRequestRecordAudio
        )
        PermissionActionRow(
            title = "悬浮字幕",
            granted = overlayPermissionGranted,
            actionText = "授权",
            actionEnabled = controlsEnabled,
            onAction = onRequestOverlay
        )
        PermissionActionRow(
            title = "服务通知",
            granted = notificationPermissionGranted,
            missingText = "可继续运行，建议允许",
            actionText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "授权" else null,
            actionEnabled = controlsEnabled,
            onAction = onRequestNotification
        )
        Text(
            text = "系统内录授权不会永久保存，每次点击开始都需要在Android确认窗口中允许。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示悬浮字幕原文、字号和背景不透明度设置。
 *
 * @param settings 当前已经规范化的字幕设置。
 * @param enabled true允许编辑，false在授权或运行期间锁定，避免界面与服务配置不一致。
 * @param onSettingsChanged 任一设置变化后的完整配置回调。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationCaptionSettingsCard(
    settings: LiveTranslationSettings,
    enabled: Boolean,
    onSettingsChanged: (LiveTranslationSettings) -> Unit
) {
    TranslationCard(title = "悬浮字幕样式") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("同时显示外语原文", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "关闭后只保留简体中文字幕",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showSourceText,
                onCheckedChange = { show ->
                    onSettingsChanged(settings.copy(showSourceText = show))
                },
                enabled = enabled
            )
        }

        Text("字幕字号 ${settings.textSizeSp} sp", fontWeight = FontWeight.SemiBold)
        Slider(
            value = settings.textSizeSp.toFloat(),
            onValueChange = { value ->
                onSettingsChanged(settings.copy(textSizeSp = value.roundToInt()))
            },
            enabled = enabled,
            valueRange = MIN_LIVE_TRANSLATION_TEXT_SIZE_SP.toFloat()..
                MAX_LIVE_TRANSLATION_TEXT_SIZE_SP.toFloat(),
            steps = MAX_LIVE_TRANSLATION_TEXT_SIZE_SP -
                MIN_LIVE_TRANSLATION_TEXT_SIZE_SP - 1
        )

        Text(
            "字幕背景不透明度 ${settings.backgroundOpacityPercent}%",
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = settings.backgroundOpacityPercent.toFloat(),
            onValueChange = { value ->
                val steppedValue = (
                    value.roundToInt() / BACKGROUND_OPACITY_STEP_PERCENT
                    ) * BACKGROUND_OPACITY_STEP_PERCENT
                onSettingsChanged(
                    settings.copy(backgroundOpacityPercent = steppedValue)
                )
            },
            enabled = enabled,
            valueRange = MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT.toFloat()..
                MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT.toFloat(),
            steps = (
                MAX_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT -
                    MIN_LIVE_TRANSLATION_BACKGROUND_OPACITY_PERCENT
                ) / BACKGROUND_OPACITY_STEP_PERCENT - 1
        )
    }
}

/**
 * 显示服务生命周期、内录状态、声音电平和最新双语字幕预览。
 *
 * @param snapshot 服务公开的当前会话事实快照。
 * @param modelSnapshot 共享英日双语模型快照，用于待机时同时展示模型状态。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationStatusCard(
    snapshot: LiveTranslationSnapshot,
    modelSnapshot: LiveTranslationModelSnapshot
) {
    TranslationCard(title = "实时状态") {
        StatusLine(
            title = "翻译服务",
            value = sessionStatusName(snapshot.sessionStatus),
            positive = snapshot.sessionStatus == LiveTranslationSessionStatus.RUNNING
        )
        StatusLine(
            title = "声音采集",
            value = captureStatusName(snapshot.captureStatus),
            positive = snapshot.captureStatus == LiveTranslationCaptureStatus.CAPTURING
        )
        StatusLine(
            title = "英日双语模型",
            value = modelStageName(modelSnapshot.stage),
            positive = modelSnapshot.stage == LiveTranslationModelStage.READY
        )
        StatusLine(
            title = "声音电平",
            value = formatAudioLevel(snapshot.audioLevelDb),
            positive = snapshot.audioLevelDb.isFinite()
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.62f)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("字幕预览", fontWeight = FontWeight.Bold)
                Text(
                    text = snapshot.sourceText.ifBlank { "尚未识别到外语原文" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = snapshot.translatedText.ifBlank { "中文字幕会显示在这里" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 显示开始和停止操作，并保证启动流程中不会重复申请系统授权。
 *
 * @param playbackCaptureSupported 系统是否支持内录。
 * @param modelReady 共享英日双语模型是否就绪。
 * @param sessionSnapshot 当前服务会话快照。
 * @param startFlowActive 是否正在等待某一步系统授权。
 * @param onStart 发起串行授权和启动的回调。
 * @param onStop 停止服务并移除字幕的回调。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationActionCard(
    playbackCaptureSupported: Boolean,
    modelReady: Boolean,
    sessionSnapshot: LiveTranslationSnapshot,
    startFlowActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    TranslationCard(title = "会话控制") {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = playbackCaptureSupported &&
                modelReady &&
                sessionSnapshot.canStart &&
                !startFlowActive,
            onClick = onStart
        ) {
            Text(if (startFlowActive) "等待系统授权…" else "开始内录并翻译")
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = sessionSnapshot.canStop,
            onClick = onStop
        ) {
            Text("停止翻译并移除字幕")
        }
        Text(
            text = "返回HarleyApp或功能中心不会自动停止正在运行的翻译，请在不使用时主动停止。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示系统内录、悬浮窗、设备端翻译质量和受保护媒体的真实能力边界。
 *
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationLimitationsCard() {
    TranslationCard(title = "无法翻译时") {
        Text(
            text = "部分电影App会禁止内录，DRM受保护影片也可能只能得到静音。此时HarleyApp不会尝试绕过保护；请改用允许系统捕获音频的播放来源。",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "环境安静但持续显示“没有可用声音”时，请先确认影片正在播放且没有连接到禁止捕获的播放设备，再停止并重新授权一次。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "部分播放器会主动隐藏第三方悬浮窗。如果本页已有字幕预览但电影画面上不显示，这是播放器限制，HarleyApp不会尝试绕过。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "日语→中文和英语→中文均由本地M2M100模型直接翻译。准确度与延迟会受对白清晰度、背景音乐、影片音质和手机性能影响，请以实际影片效果为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "自动生成的字幕和译文可能不准确，不替代人工翻译；加载大模型时内存占用较高，低内存设备可能启动较慢或被系统终止。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 为实时翻译页面提供统一的卡片标题和内容间距。
 *
 * @param title 卡片中文标题。
 * @param content 卡片内部Compose内容。
 * @return 无返回值。
 */
@Composable
private fun TranslationCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = harleyCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

/**
 * 显示一行权限状态，并在缺少权限时提供可选操作按钮。
 *
 * @param title 权限或系统能力名称。
 * @param granted true表示当前满足。
 * @param missingText 未满足时显示的补充说明。
 * @param actionText 非空时显示操作按钮文字。
 * @param actionEnabled 操作按钮是否可点击。
 * @param onAction 操作按钮回调。
 * @return 无返回值。
 */
@Composable
private fun PermissionActionRow(
    title: String,
    granted: Boolean,
    missingText: String = "需要授权",
    actionText: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (granted) "已满足" else missingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) Color(0xFF008C72) else MaterialTheme.colorScheme.error
            )
        }
        if (!granted && actionText != null) {
            Spacer(modifier = Modifier.width(10.dp))
            OutlinedButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionText)
            }
        }
    }
}

/**
 * 显示一行只读运行状态。
 *
 * @param title 状态名称。
 * @param value 当前状态文本。
 * @param positive true使用正常强调色，false使用普通次级文字色。
 * @return 无返回值。
 */
@Composable
private fun StatusLine(title: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = if (positive) Color(0xFF008C72) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示页面操作反馈或服务错误，不记录原始异常和敏感字幕内容。
 *
 * @param message 可直接呈现的中文反馈。
 * @param isError true使用错误配色，false使用普通提示配色。
 * @return 无返回值。
 */
@Composable
private fun LiveTranslationMessageCard(message: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
    }
}

/**
 * 检查普通运行时权限当前是否已经授予。
 *
 * @param context 用于读取权限状态的Android上下文。
 * @param permission Android权限完整名称。
 * @return 系统当前报告已授予返回true，否则返回false。
 */
private fun hasRuntimePermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * 判断Android是否仍建议为录音权限显示标准请求或解释流程。
 *
 * 使用方法：
 * 仅在已经记录过至少一次权限请求且当前仍未授权时调用；返回false表示系统可能已不再显示权限框，
 * 页面应改为提供应用详情设置入口，避免用户重复点击无响应。
 *
 * @param context 当前Compose页面上下文，内部会沿ContextWrapper找到宿主Activity。
 * @return 系统建议继续显示权限解释时返回true；找不到Activity时安全返回false。
 */
private fun shouldShowRecordAudioPermissionRationale(context: Context): Boolean {
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.RECORD_AUDIO
    )
}

/**
 * 从主题或配置包装Context中查找真正承载权限请求的Activity。
 *
 * @return 找到Activity时返回实例；包装链结束仍未找到时返回null。
 */
private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

/**
 * 检查前台服务通知是否能够出现在通知抽屉。
 *
 * @param context 用于读取通知运行时权限的Android上下文。
 * @return Android 13以下或通知权限已经授予时返回true，否则返回false。
 */
private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasRuntimePermission(context, Manifest.permission.POST_NOTIFICATIONS)
}

/**
 * 返回源语言的用户可见名称。
 *
 * @param language 英语或日语源语言。
 * @return 与真实模型一致的中文名称。
 */
private fun sourceLanguageName(language: LiveTranslationSourceLanguage): String {
    return when (language) {
        LiveTranslationSourceLanguage.ENGLISH -> "英语"
        LiveTranslationSourceLanguage.JAPANESE -> "日语"
    }
}

/**
 * 把模型准备枚举转换为页面状态文字。
 *
 * @param stage 当前模型阶段。
 * @return 可直接展示的中文阶段说明。
 */
private fun modelStageName(stage: LiveTranslationModelStage): String {
    return when (stage) {
        LiveTranslationModelStage.NOT_INSTALLED -> "尚未准备"
        LiveTranslationModelStage.DOWNLOADING -> "正在下载"
        LiveTranslationModelStage.INSTALLING -> "正在安装"
        LiveTranslationModelStage.PREPARING_TRANSLATION -> "正在准备翻译模型"
        LiveTranslationModelStage.READY -> "可以离线使用"
        LiveTranslationModelStage.ERROR -> "准备失败"
    }
}

/**
 * 把实时翻译服务生命周期转换为页面状态文字。
 *
 * @param status 当前会话状态。
 * @return 可直接展示的中文状态说明。
 */
private fun sessionStatusName(status: LiveTranslationSessionStatus): String {
    return when (status) {
        LiveTranslationSessionStatus.IDLE -> "未启动"
        LiveTranslationSessionStatus.STARTING -> "正在启动"
        LiveTranslationSessionStatus.RUNNING -> "正在翻译"
        LiveTranslationSessionStatus.STOPPING -> "正在停止"
        LiveTranslationSessionStatus.ERROR -> "运行错误"
    }
}

/**
 * 把系统播放声音采集状态转换为面向用户的说明。
 *
 * @param status 当前采集状态。
 * @return 可直接展示的中文状态说明。
 */
private fun captureStatusName(status: LiveTranslationCaptureStatus): String {
    return when (status) {
        LiveTranslationCaptureStatus.IDLE -> "尚未采集"
        LiveTranslationCaptureStatus.CAPTURING -> "正在读取声音"
        LiveTranslationCaptureStatus.SILENT -> "暂未检测到可用声音"
        LiveTranslationCaptureStatus.PROJECTION_REVOKED -> "系统内录授权已撤销"
    }
}

/**
 * 格式化最近音频窗口的dBFS电平。
 *
 * @param audioLevelDb 服务计算的声音电平；没有采样时通常为负无穷。
 * @return 有效时返回整数dBFS文本，否则返回“暂无声音”。
 */
private fun formatAudioLevel(audioLevelDb: Float): String {
    return if (audioLevelDb.isFinite()) {
        "${audioLevelDb.roundToInt()} dBFS"
    } else {
        "暂无声音"
    }
}
