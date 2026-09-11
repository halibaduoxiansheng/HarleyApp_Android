package com.example.harleyapp.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.harleyapp.model.FLASHLIGHT_MAX_FREQUENCY_HZ
import com.example.harleyapp.model.FLASHLIGHT_MAX_PERIOD_MILLIS
import com.example.harleyapp.model.FLASHLIGHT_MAX_TOTAL_DURATION_SECONDS
import com.example.harleyapp.model.FLASHLIGHT_MIN_FREQUENCY_HZ
import com.example.harleyapp.model.FLASHLIGHT_MIN_PHASE_DURATION_MILLIS
import com.example.harleyapp.model.FLASHLIGHT_MIN_TOTAL_DURATION_SECONDS
import com.example.harleyapp.model.MORSE_MAX_INPUT_LENGTH
import com.example.harleyapp.model.FlashlightSettingsIssue
import com.example.harleyapp.model.FlashlightStrobeSettings
import com.example.harleyapp.model.FlashlightTiming
import com.example.harleyapp.model.MorseFlashPlan
import com.example.harleyapp.model.countMorseInputCharacters
import com.example.harleyapp.model.createMorseFlashPlan
import com.example.harleyapp.model.limitMorseInputText
import com.example.harleyapp.model.scaleFlashlightTimingForFrequency
import com.example.harleyapp.model.validateFlashlightStrobeSettings
import com.example.harleyapp.system.FlashlightCapability
import com.example.harleyapp.system.FlashlightController
import com.example.harleyapp.ui.components.harleyCardBorder
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 描述当前唯一拥有闪光灯控制权的运行任务。
 *
 * 使用方法：
 * 用户点击普通爆闪或摩斯闪光开始按钮时创建对应子类型，并交给页面内唯一的
 * `LaunchedEffect` 执行。常亮模式不创建本对象，但开始任何运行任务前都必须先关闭常亮。
 */
private sealed interface ActiveFlashlightRun {

    /** 使用单调时钟记录的开始时间，修改系统时间不会影响运行节奏。 */
    val startedAtElapsedRealtime: Long

    /**
     * 用户点击开始瞬间冻结的普通爆闪任务。
     *
     * @param settings 明灭、总时长和亮度参数。
     * @param startedAtElapsedRealtime 任务开始时的单调时钟毫秒数。
     */
    data class Strobe(
        val settings: FlashlightStrobeSettings,
        override val startedAtElapsedRealtime: Long
    ) : ActiveFlashlightRun

    /**
     * 用户点击开始瞬间冻结的摩斯闪光任务。
     *
     * @param plan 已完成字符校验、编码和预计时长计算的闪光计划。
     * @param strengthLevel 本次任务固定使用的闪光灯亮度档位。
     * @param startedAtElapsedRealtime 任务开始时的单调时钟毫秒数。
     */
    data class Morse(
        val plan: MorseFlashPlan,
        val strengthLevel: Int,
        override val startedAtElapsedRealtime: Long
    ) : ActiveFlashlightRun
}

/** 首次安全确认通过后需要实际启动的闪光类型。 */
private enum class PendingFlashlightStart {
    STROBE,
    MORSE
}

/**
 * 提供常亮、文字摩斯闪光、可调频爆闪、总秒数、明灭时长和硬件亮度控制。
 *
 * 使用方法：
 * 功能中心传入[onBack]即可打开本页。页面只在前台运行，首次进入会申请相机权限；用户可以
 * 输入英文文字直接发送摩斯闪光，也可以使用原有普通爆闪。发送完成、手动停止、返回、进入
 * 后台或页面销毁时都会强制关灯。
 *
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外层传入的安全边距修饰器。
 *
 * @return 无返回值，直接输出完整手电筒控制页面。
 */
@Composable
fun FlashlightScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val controller = remember(context.applicationContext) {
        FlashlightController(context.applicationContext)
    }
    val capability = controller.capability

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by rememberSaveable {
        mutableStateOf(false)
    }
    var onDurationMillis by rememberSaveable {
        mutableIntStateOf(DEFAULT_PHASE_DURATION_MILLIS)
    }
    var offDurationMillis by rememberSaveable {
        mutableIntStateOf(DEFAULT_PHASE_DURATION_MILLIS)
    }
    var totalDurationText by rememberSaveable {
        mutableStateOf(DEFAULT_TOTAL_DURATION_SECONDS.toString())
    }
    var morseText by rememberSaveable {
        mutableStateOf("")
    }
    var strengthLevel by rememberSaveable {
        mutableIntStateOf(capability.defaultStrengthLevel)
    }
    var activeRun by remember {
        mutableStateOf<ActiveFlashlightRun?>(null)
    }
    var steadyLightEnabled by remember {
        mutableStateOf(false)
    }
    var remainingMillis by remember {
        mutableLongStateOf(0L)
    }
    var statusMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var safetyAccepted by rememberSaveable {
        mutableStateOf(false)
    }
    var pendingStart by remember {
        mutableStateOf<PendingFlashlightStart?>(null)
    }

    val timing = remember(onDurationMillis, offDurationMillis) {
        FlashlightTiming(onDurationMillis, offDurationMillis)
    }
    val morsePlan = remember(morseText) {
        createMorseFlashPlan(morseText)
    }
    val totalDurationSeconds = totalDurationText.toIntOrNull()
    val totalDurationValid = totalDurationSeconds in
        FLASHLIGHT_MIN_TOTAL_DURATION_SECONDS..FLASHLIGHT_MAX_TOTAL_DURATION_SECONDS
    val morseDurationValid = morsePlan.totalDurationMillis <=
        FLASHLIGHT_MAX_TOTAL_DURATION_SECONDS * MILLIS_PER_SECOND
    val lightActive = activeRun != null || steadyLightEnabled
    val latestLightActive by rememberUpdatedState(lightActive)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        statusMessage = if (granted) {
            "相机权限已授予，可以控制手电筒"
        } else {
            "未获得相机权限，无法控制手电筒"
        }
    }

    // 所有主动和被动停止路径复用同一收口，避免灯光状态只在界面上停止而硬件仍保持点亮。
    val stopAllLighting: (String?) -> Unit = { message ->
        activeRun = null
        steadyLightEnabled = false
        remainingMillis = 0L
        val turnOffResult = controller.turnOff()
        statusMessage = when {
            !turnOffResult.success -> turnOffResult.message
            message != null -> message
            else -> statusMessage
        }
    }

    // 点击开始时冻结全部参数；运行中的滑块被禁用，不会让同一次爆闪在中途突然改变节奏。
    val startStrobe: () -> Unit = start@{
        val durationSeconds = totalDurationSeconds
        if (durationSeconds == null) {
            statusMessage = "请输入1～300之间的总时长"
            return@start
        }
        val settings = FlashlightStrobeSettings(
            totalDurationSeconds = durationSeconds,
            timing = timing,
            strengthLevel = strengthLevel
        )
        val issue = validateFlashlightStrobeSettings(settings)
        if (issue != null) {
            statusMessage = flashlightIssueMessage(issue)
            return@start
        }
        if (!cameraPermissionGranted) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return@start
        }
        if (!capability.isAvailable) {
            statusMessage = "当前设备没有可用的后置闪光灯"
            return@start
        }

        steadyLightEnabled = false
        controller.turnOff()
        remainingMillis = durationSeconds * MILLIS_PER_SECOND
        statusMessage = "爆闪运行中，离开本页会立即关灯"
        activeRun = ActiveFlashlightRun.Strobe(
            settings = settings,
            startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        )
    }

    // 摩斯任务同样在开始瞬间冻结编码结果和亮度，确保用户输入变化不会改写正在发送的内容。
    val startMorse: () -> Unit = start@{
        if (morsePlan.inputWasTruncated) {
            statusMessage = "输入内容超过${MORSE_MAX_INPUT_LENGTH}个字符，请缩短后再开始"
            return@start
        }
        if (morsePlan.unsupportedCharacters.isNotEmpty()) {
            statusMessage = "输入中包含无法编码的字符，请先修改后再开始"
            return@start
        }
        if (morsePlan.steps.isEmpty()) {
            statusMessage = "请输入需要转换为摩斯闪光的文字"
            return@start
        }
        if (!morseDurationValid) {
            statusMessage = "预计用时超过5分钟，请缩短输入内容"
            return@start
        }
        if (!cameraPermissionGranted) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return@start
        }
        if (!capability.isAvailable) {
            statusMessage = "当前设备没有可用的后置闪光灯"
            return@start
        }

        steadyLightEnabled = false
        controller.turnOff()
        remainingMillis = morsePlan.totalDurationMillis
        statusMessage = "摩斯闪光发送中，预计约 ${formatMorseDuration(morsePlan.totalDurationMillis)}"
        activeRun = ActiveFlashlightRun.Morse(
            plan = morsePlan,
            strengthLevel = strengthLevel,
            startedAtElapsedRealtime = SystemClock.elapsedRealtime()
        )
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 普通爆闪和摩斯闪光共用唯一执行协程，避免旧任务清理时误关掉新任务刚点亮的灯。
    LaunchedEffect(activeRun) {
        val run = activeRun ?: return@LaunchedEffect
        var completedNormally = false
        var failureMessage: String? = null

        try {
            when (run) {
                is ActiveFlashlightRun.Strobe -> {
                    // 普通爆闪继续用总截止时间兜底，协程调度延迟不会把用户设定时长向后拖长。
                    val deadlineElapsedRealtime = run.startedAtElapsedRealtime +
                        run.settings.totalDurationSeconds * MILLIS_PER_SECOND
                    while (true) {
                        val beforeOnMillis = deadlineElapsedRealtime -
                            SystemClock.elapsedRealtime()
                        if (beforeOnMillis <= 0L) {
                            completedNormally = true
                            break
                        }
                        remainingMillis = beforeOnMillis

                        val turnOnResult = controller.turnOn(run.settings.strengthLevel)
                        if (!turnOnResult.success) {
                            failureMessage = turnOnResult.message
                            break
                        }
                        delay(
                            minOf(
                                run.settings.timing.onDurationMillis.toLong(),
                                beforeOnMillis
                            )
                        )

                        val turnOffResult = controller.turnOff()
                        if (!turnOffResult.success) {
                            failureMessage = turnOffResult.message
                            break
                        }
                        val beforeOffMillis = deadlineElapsedRealtime -
                            SystemClock.elapsedRealtime()
                        remainingMillis = beforeOffMillis.coerceAtLeast(0L)
                        if (beforeOffMillis <= 0L) {
                            completedNormally = true
                            break
                        }
                        delay(
                            minOf(
                                run.settings.timing.offDurationMillis.toLong(),
                                beforeOffMillis
                            )
                        )
                    }
                }

                is ActiveFlashlightRun.Morse -> {
                    var plannedRemainingMillis = run.plan.totalDurationMillis

                    for (step in run.plan.steps) {
                        val controlResult = if (step.isLightOn) {
                            controller.turnOn(run.strengthLevel)
                        } else {
                            controller.turnOff()
                        }
                        if (!controlResult.success) {
                            failureMessage = controlResult.message
                            break
                        }

                        // 每个点、划或间隔都完整执行；倒计时按计划余量刷新，因此系统命令耗时只会
                        // 让“预计”结束时间出现少量合理误差，不会吞掉文字末尾的闪光内容。
                        val remainingAfterStep = plannedRemainingMillis - step.durationMillis
                        val stepDeadlineElapsedRealtime = SystemClock.elapsedRealtime() +
                            step.durationMillis
                        while (true) {
                            val stepRemainingMillis = stepDeadlineElapsedRealtime -
                                SystemClock.elapsedRealtime()
                            if (stepRemainingMillis <= 0L) break

                            remainingMillis = remainingAfterStep + stepRemainingMillis
                            delay(minOf(stepRemainingMillis, MORSE_COUNTDOWN_REFRESH_MILLIS))
                        }
                        plannedRemainingMillis = remainingAfterStep
                    }

                    if (failureMessage == null) {
                        completedNormally = true
                    }
                }
            }
        } finally {
            val finalTurnOffResult = controller.turnOff()
            if (!finalTurnOffResult.success && failureMessage == null) {
                failureMessage = finalTurnOffResult.message
            }
        }

        if (activeRun == run) {
            activeRun = null
            remainingMillis = 0L
            statusMessage = when {
                failureMessage != null -> failureMessage
                completedNormally && run is ActiveFlashlightRun.Morse ->
                    "摩斯闪光已完整发送，手电筒已关闭"

                completedNormally -> "爆闪已按设定时长结束，手电筒已关闭"
                run is ActiveFlashlightRun.Morse -> "摩斯闪光已停止，手电筒已关闭"
                else -> "爆闪已停止，手电筒已关闭"
            }
        }
    }

    // 自动息屏会令页面进入后台，因此运行期间保持屏幕唤醒，确保用户设定的秒数能够完整执行。
    DisposableEffect(rootView, lightActive) {
        rootView.keepScreenOn = lightActive
        onDispose {
            rootView.keepScreenOn = false
        }
    }

    // Android后台相机限制和安全需求都要求离开前台后立即收口，不在后台偷偷继续闪烁。
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val shouldReportAutomaticStop = latestLightActive
                activeRun = null
                steadyLightEnabled = false
                remainingMillis = 0L
                val turnOffResult = controller.turnOff()
                if (shouldReportAutomaticStop) {
                    statusMessage = if (turnOffResult.success) {
                        "App进入后台，手电筒已自动关闭"
                    } else {
                        turnOffResult.message
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.turnOff()
        }
    }

    val handleBack = {
        stopAllLighting(null)
        onBack()
    }

    if (pendingStart != null) {
        FlashlightSafetyDialog(
            onConfirm = {
                val confirmedStart = pendingStart
                pendingStart = null
                safetyAccepted = true
                when (confirmedStart) {
                    PendingFlashlightStart.STROBE -> startStrobe()
                    PendingFlashlightStart.MORSE -> startMorse()
                    null -> Unit
                }
            },
            onDismiss = {
                pendingStart = null
            }
        )
    }

    FeatureDetailScaffold(
        modifier = modifier,
        title = "手电筒",
        subtitle = "常亮、自定义爆闪与文字摩斯闪光",
        onBack = handleBack
    ) { contentModifier ->
        Box(modifier = contentModifier) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 20.dp,
                    end = 20.dp,
                    bottom = if (lightActive) 108.dp else 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            item {
                FlashlightSafetyCard()
            }

            if (!capability.isAvailable) {
                item {
                    FlashlightUnavailableCard()
                }
            } else if (!cameraPermissionGranted) {
                item {
                    FlashlightPermissionCard(
                        onRequestPermission = {
                            permissionRequested = true
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }
            }

            item {
                MorseFlashCard(
                    text = morseText,
                    plan = morsePlan,
                    durationValid = morseDurationValid,
                    running = activeRun is ActiveFlashlightRun.Morse,
                    remainingMillis = remainingMillis,
                    actionsEnabled = capability.isAvailable && cameraPermissionGranted,
                    anyLightActive = lightActive,
                    onTextChanged = { input ->
                        val inputCharacterCount = countMorseInputCharacters(input)
                        morseText = limitMorseInputText(input)
                        if (inputCharacterCount > MORSE_MAX_INPUT_LENGTH) {
                            statusMessage =
                                "摩斯闪光最多支持${MORSE_MAX_INPUT_LENGTH}个字符，已保留前面的内容"
                        }
                    },
                    onStart = {
                        if (safetyAccepted) {
                            startMorse()
                        } else {
                            pendingStart = PendingFlashlightStart.MORSE
                        }
                    },
                    onStop = {
                        stopAllLighting("摩斯闪光已手动停止并关闭")
                    }
                )
            }

            item {
                FlashlightSettingsCard(
                    timing = timing,
                    totalDurationText = totalDurationText,
                    totalDurationValid = totalDurationValid,
                    capability = capability,
                    strengthLevel = strengthLevel,
                    enabled = !lightActive && capability.isAvailable,
                    brightnessEnabled = activeRun == null && capability.isAvailable,
                    onFrequencyChanged = { frequencyHz ->
                        val adjustedTiming = scaleFlashlightTimingForFrequency(
                            currentTiming = timing,
                            targetFrequencyHz = frequencyHz
                        )
                        onDurationMillis = adjustedTiming.onDurationMillis
                        offDurationMillis = adjustedTiming.offDurationMillis
                    },
                    onOnDurationChanged = { requestedMillis ->
                        onDurationMillis = requestedMillis.coerceAtMost(
                            FLASHLIGHT_MAX_PERIOD_MILLIS - offDurationMillis
                        )
                    },
                    onOffDurationChanged = { requestedMillis ->
                        offDurationMillis = requestedMillis.coerceAtMost(
                            FLASHLIGHT_MAX_PERIOD_MILLIS - onDurationMillis
                        )
                    },
                    onTotalDurationChanged = { input ->
                        if (input.length <= MAX_TOTAL_DURATION_INPUT_LENGTH &&
                            input.all(Char::isDigit)
                        ) {
                            totalDurationText = input
                        }
                    },
                    onStrengthChanged = { requestedLevel ->
                        strengthLevel = requestedLevel
                        if (steadyLightEnabled) {
                            val result = controller.turnOn(requestedLevel)
                            if (!result.success) {
                                steadyLightEnabled = false
                                statusMessage = result.message
                            }
                        }
                    }
                )
            }

            item {
                FlashlightActionCard(
                    activeRun = activeRun,
                    steadyLightEnabled = steadyLightEnabled,
                    remainingMillis = remainingMillis,
                    actionsEnabled = capability.isAvailable && cameraPermissionGranted,
                    startEnabled = totalDurationValid,
                    onStartStrobe = {
                        if (safetyAccepted) {
                            startStrobe()
                        } else {
                            pendingStart = PendingFlashlightStart.STROBE
                        }
                    },
                    onStop = {
                        stopAllLighting("手电筒已手动停止并关闭")
                    },
                    onToggleSteady = {
                        if (steadyLightEnabled) {
                            stopAllLighting("常亮已关闭")
                        } else {
                            val result = controller.turnOn(strengthLevel)
                            if (result.success) {
                                steadyLightEnabled = true
                                statusMessage = "手电筒常亮中，离开本页会自动关闭"
                            } else {
                                statusMessage = result.message
                            }
                        }
                    }
                )
            }

                statusMessage?.let { message ->
                    item {
                        FlashlightStatusCard(message)
                    }
                }
            }

            if (lightActive) {
                Button(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .heightIn(min = 56.dp),
                    onClick = {
                        stopAllLighting("手电筒已手动停止并关闭")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "立即停止并关闭手电筒",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 显示普通爆闪和摩斯闪光可能影响眼睛、驾驶和光敏人群的固定安全提示。
 *
 * @return 无返回值，直接输出安全提示卡片。
 */
@Composable
private fun FlashlightSafetyCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = MaterialTheme.shapes.large,
        border = harleyCardBorder(alpha = 0.82f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "闪光安全提醒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "请勿直视闪光灯，也不要在驾驶、道路或可能影响光敏人群的环境中使用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * 在设备没有任何可用闪光灯时说明硬件限制。
 *
 * @return 无返回值，直接输出不可用提示卡片。
 */
@Composable
private fun FlashlightUnavailableCard() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = harleyCardBorder()
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = "当前设备没有检测到可控制的闪光灯，其他App功能仍可正常使用。",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 在相机权限缺失时提供一次明确的授权入口。
 *
 * @param onRequestPermission 请求Android相机运行时权限的回调。
 *
 * @return 无返回值，直接输出权限说明和授权按钮。
 */
@Composable
private fun FlashlightPermissionCard(onRequestPermission: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = harleyCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "需要相机权限才能控制后置闪光灯。画面不会被打开、保存或上传。",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRequestPermission) {
                Text("授权相机权限")
            }
        }
    }
}

/**
 * 提供文字输入、摩斯编码预览、预计用时以及开始和停止操作。
 *
 * 使用方法：
 * 页面把当前输入实时转换成[plan]后传入本组件。组件只负责展示和派发操作，不直接控制硬件；
 * [onStart]由页面完成安全确认和权限复查，[onStop]必须复用页面的统一关灯入口。
 *
 * @param text 用户当前输入的原始文字。
 * @param plan 根据[text]生成的摩斯闪光计划。
 * @param durationValid true表示预计时长未超过页面允许的5分钟上限。
 * @param running true表示当前唯一活动任务是摩斯闪光。
 * @param remainingMillis 运行任务按照完整点划计划计算的预计剩余毫秒数。
 * @param actionsEnabled true表示设备具备闪光灯且相机权限已经授予。
 * @param anyLightActive true表示普通爆闪、摩斯闪光或常亮中至少有一种正在占用闪光灯。
 * @param onTextChanged 原始输入变化回调，调用方负责限制最大字符数。
 * @param onStart 请求开始本次摩斯闪光的回调。
 * @param onStop 请求立即取消任务并关闭闪光灯的回调。
 *
 * @return 无返回值，直接输出摩斯闪光控制卡片。
 */
@Composable
private fun MorseFlashCard(
    text: String,
    plan: MorseFlashPlan,
    durationValid: Boolean,
    running: Boolean,
    remainingMillis: Long,
    actionsEnabled: Boolean,
    anyLightActive: Boolean,
    onTextChanged: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val unsupportedText = plan.unsupportedCharacters
        .distinct()
        .take(MAX_DISPLAYED_UNSUPPORTED_CHARACTERS)
        .joinToString(separator = " ")
    val canStart = plan.canStart && durationValid

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.large,
        border = harleyCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "摩斯闪光",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "输入英文字母、数字或常见标点，空格会按单词间隔发送。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = text,
                onValueChange = onTextChanged,
                enabled = !anyLightActive,
                label = { Text("需要闪光发送的文字") },
                placeholder = { Text("例如：SOS 或 HELP ME") },
                minLines = 3,
                maxLines = 5,
                isError = plan.inputWasTruncated ||
                    plan.unsupportedCharacters.isNotEmpty() ||
                    (plan.steps.isNotEmpty() && !durationValid),
                supportingText = {
                    Text(
                        when {
                            plan.inputWasTruncated ->
                                "内容超过${MORSE_MAX_INPUT_LENGTH}个字符，请缩短后再开始"

                            plan.unsupportedCharacters.isNotEmpty() ->
                                "暂不支持这些字符：$unsupportedText，请改用英文、数字或常见标点"

                            plan.steps.isNotEmpty() && !durationValid ->
                                "预计用时超过5分钟，请缩短内容"

                            text.isBlank() ->
                                "最多输入${MORSE_MAX_INPUT_LENGTH}个字符，输入后自动计算预计用时"

                            else ->
                                "${countMorseInputCharacters(text)}/${MORSE_MAX_INPUT_LENGTH}个字符"
                        }
                    )
                }
            )

            if (plan.encodedText.isNotEmpty()) {
                Text(
                    text = "编码预览",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = plan.encodedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = MORSE_PREVIEW_MAX_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = when {
                    running ->
                        "发送中 · 预计剩余 ${formatMorseDuration(remainingMillis)}"

                    plan.steps.isNotEmpty() ->
                        "预计用时约 ${formatMorseDuration(plan.totalDurationMillis)}"

                    else -> "预计用时将在输入后显示"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "标准节奏：点1单位、划3单位；字符间隔3单位，单词间隔7单位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = actionsEnabled && canStart && !anyLightActive,
                    onClick = onStart
                ) {
                    Text("开始摩斯闪光")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = running,
                    onClick = onStop
                ) {
                    Text("立即停止")
                }
            }
        }
    }
}

/**
 * 显示频率、明灭时长、总秒数和设备亮度档位设置。
 *
 * @param timing 当前亮灯和灭灯时序。
 * @param totalDurationText 用户输入的总运行秒数文本。
 * @param totalDurationValid 总运行秒数是否处于1～300秒。
 * @param capability 当前设备闪光灯亮度能力。
 * @param strengthLevel 当前选择的亮度档位。
 * @param enabled false时锁定爆闪时序和总时长，防止运行中改变节奏。
 * @param brightnessEnabled false时锁定亮度；常亮期间允许实时调节，爆闪期间保持启动参数不变。
 * @param onFrequencyChanged 频率滑块变化回调。
 * @param onOnDurationChanged 亮灯毫秒数变化回调。
 * @param onOffDurationChanged 灭灯毫秒数变化回调。
 * @param onTotalDurationChanged 总秒数文本变化回调。
 * @param onStrengthChanged 亮度档位变化回调；常亮时可实时生效。
 *
 * @return 无返回值，直接输出全部控制项。
 */
@Composable
private fun FlashlightSettingsCard(
    timing: FlashlightTiming,
    totalDurationText: String,
    totalDurationValid: Boolean,
    capability: FlashlightCapability,
    strengthLevel: Int,
    enabled: Boolean,
    brightnessEnabled: Boolean,
    onFrequencyChanged: (Float) -> Unit,
    onOnDurationChanged: (Int) -> Unit,
    onOffDurationChanged: (Int) -> Unit,
    onTotalDurationChanged: (String) -> Unit,
    onStrengthChanged: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = MaterialTheme.shapes.large,
        border = harleyCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "爆闪参数",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            FlashlightSlider(
                label = "频率",
                valueText = String.format(Locale.CHINA, "%.2f Hz", timing.frequencyHz),
                value = timing.frequencyHz.coerceIn(
                    FLASHLIGHT_MIN_FREQUENCY_HZ,
                    FLASHLIGHT_MAX_FREQUENCY_HZ
                ),
                valueRange = FLASHLIGHT_MIN_FREQUENCY_HZ..FLASHLIGHT_MAX_FREQUENCY_HZ,
                steps = FREQUENCY_SLIDER_STEPS,
                enabled = enabled,
                onValueChange = onFrequencyChanged
            )

            FlashlightSlider(
                label = "亮灯时长",
                valueText = "${timing.onDurationMillis} ms",
                value = timing.onDurationMillis.toFloat(),
                valueRange = FLASHLIGHT_MIN_PHASE_DURATION_MILLIS.toFloat()..
                    (FLASHLIGHT_MAX_PERIOD_MILLIS - FLASHLIGHT_MIN_PHASE_DURATION_MILLIS).toFloat(),
                enabled = enabled,
                onValueChange = { rawValue ->
                    onOnDurationChanged(roundPhaseDuration(rawValue))
                }
            )

            FlashlightSlider(
                label = "灭灯时长",
                valueText = "${timing.offDurationMillis} ms",
                value = timing.offDurationMillis.toFloat(),
                valueRange = FLASHLIGHT_MIN_PHASE_DURATION_MILLIS.toFloat()..
                    (FLASHLIGHT_MAX_PERIOD_MILLIS - FLASHLIGHT_MIN_PHASE_DURATION_MILLIS).toFloat(),
                enabled = enabled,
                onValueChange = { rawValue ->
                    onOffDurationChanged(roundPhaseDuration(rawValue))
                }
            )

            Text(
                text = "点亮占比 ${timing.dutyCyclePercent}% · 一个周期 " +
                    "${timing.onDurationMillis + timing.offDurationMillis} ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = totalDurationText,
                onValueChange = onTotalDurationChanged,
                enabled = enabled,
                label = { Text("总运行时长") },
                suffix = { Text("秒") },
                singleLine = true,
                isError = !totalDurationValid,
                supportingText = {
                    Text(
                        if (totalDurationValid) {
                            "范围1～300秒，到时自动关闭"
                        } else {
                            "请输入1～300之间的整数秒数"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            if (capability.supportsStrengthControl) {
                FlashlightSlider(
                    label = "亮度",
                    valueText = "档位 $strengthLevel/${capability.maximumStrengthLevel}",
                    value = strengthLevel.toFloat(),
                    valueRange = 1f..capability.maximumStrengthLevel.toFloat(),
                    steps = (capability.maximumStrengthLevel - 2).coerceAtLeast(0),
                    enabled = brightnessEnabled,
                    onValueChange = { rawValue ->
                        onStrengthChanged(
                            rawValue.roundToInt().coerceIn(1, capability.maximumStrengthLevel)
                        )
                    }
                )
            } else {
                Text(
                    text = "亮度：当前手机或系统仅提供固定亮度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 输出一个带标题、当前值和统一样式的数值滑块。
 *
 * @param label 设置项名称。
 * @param valueText 已格式化的当前值和单位。
 * @param value 当前滑块值。
 * @param valueRange 允许选择的闭区间。
 * @param steps 两个端点之间的离散节点数量，0表示连续滑动。
 * @param enabled 是否允许用户调整。
 * @param onValueChange 滑块数值变化回调。
 *
 * @return 无返回值，直接输出标题行和滑块。
 */
@Composable
private fun FlashlightSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    steps: Int = 0
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled
        )
    }
}

/**
 * 显示爆闪开始/停止、常亮测试和剩余时间。
 *
 * @param activeRun 当前正在运行的普通爆闪或摩斯闪光任务，null表示未运行。
 * @param steadyLightEnabled true表示当前处于常亮模式。
 * @param remainingMillis 当前爆闪任务剩余毫秒数。
 * @param actionsEnabled 当前是否具备硬件和权限条件。
 * @param startEnabled 当前总时长是否有效。
 * @param onStartStrobe 开始爆闪前置处理回调。
 * @param onStop 立即停止所有灯光回调。
 * @param onToggleSteady 切换常亮状态回调。
 *
 * @return 无返回值，直接输出运行状态和操作按钮。
 */
@Composable
private fun FlashlightActionCard(
    activeRun: ActiveFlashlightRun?,
    steadyLightEnabled: Boolean,
    remainingMillis: Long,
    actionsEnabled: Boolean,
    startEnabled: Boolean,
    onStartStrobe: () -> Unit,
    onStop: () -> Unit,
    onToggleSteady: () -> Unit
) {
    val strobeRunning = activeRun is ActiveFlashlightRun.Strobe
    val morseRunning = activeRun is ActiveFlashlightRun.Morse
    val anyLightActive = activeRun != null || steadyLightEnabled

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = harleyCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = when {
                    strobeRunning -> "爆闪中 · 剩余 ${formatRemainingSeconds(remainingMillis)} 秒"
                    morseRunning ->
                        "摩斯闪光中 · 预计剩余 ${formatMorseDuration(remainingMillis)}"

                    steadyLightEnabled -> "常亮中"
                    else -> "已停止"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (anyLightActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = actionsEnabled && startEnabled && !anyLightActive,
                    onClick = onStartStrobe
                ) {
                    Text("开始爆闪")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = anyLightActive,
                    onClick = onStop
                ) {
                    Text("立即停止")
                }
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = actionsEnabled && activeRun == null,
                onClick = onToggleSteady
            ) {
                Text(if (steadyLightEnabled) "关闭常亮" else "打开常亮测试")
            }
        }
    }
}

/**
 * 首次开始普通爆闪或摩斯闪光前要求用户明确接受安全提示。
 *
 * @param onConfirm 用户确认环境安全并继续开始的回调。
 * @param onDismiss 用户取消开始的回调。
 *
 * @return 无返回值，直接输出模态确认框。
 */
@Composable
private fun FlashlightSafetyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认开始闪光") },
        text = {
            Text("请确认闪光灯没有朝向眼睛、驾驶员或可能对闪烁敏感的人。开始后可随时立即停止。")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认并开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 显示最近一次权限、运行或硬件操作结果。
 *
 * @param message 已面向用户整理的状态文本。
 *
 * @return 无返回值，直接输出状态卡片。
 */
@Composable
private fun FlashlightStatusCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = MaterialTheme.shapes.medium,
        border = harleyCardBorder(alpha = 0.72f)
    ) {
        Text(
            modifier = Modifier.padding(14.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/**
 * 把滑块原始值规整成10ms步进，并限制在单阶段允许范围内。
 *
 * @param rawValue Compose滑块产生的浮点毫秒值。
 *
 * @return 50～1950ms之间、以10ms为步进的整数时长。
 */
private fun roundPhaseDuration(rawValue: Float): Int {
    return ((rawValue / PHASE_DURATION_STEP_MILLIS).roundToInt() *
        PHASE_DURATION_STEP_MILLIS)
        .coerceIn(
            FLASHLIGHT_MIN_PHASE_DURATION_MILLIS,
            FLASHLIGHT_MAX_PERIOD_MILLIS - FLASHLIGHT_MIN_PHASE_DURATION_MILLIS
        )
}

/**
 * 把剩余毫秒数向上取整成用户看到的剩余秒数，避免任务刚开始就少显示一秒。
 *
 * @param remainingMillis 尚未运行的毫秒数。
 *
 * @return 不小于0的整数秒数。
 */
private fun formatRemainingSeconds(remainingMillis: Long): Int {
    return ceil(remainingMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND.toDouble())
        .toInt()
}

/**
 * 把摩斯计划的毫秒数向上取整到0.1秒，并在超过一分钟时拆分成分和秒。
 *
 * 使用方法：
 * 输入计划总时长可得到开始前的预计用时；输入运行中的剩余毫秒数可得到不会提前显示为零的
 * 倒计时文案。该结果用于人类阅读，不参与实际闪光调度。
 *
 * @param durationMillis 需要格式化的预计毫秒数，负值会按零处理。
 *
 * @return 小于一分钟时返回如`5.4秒`，达到一分钟时返回如`1分05.4秒`。
 */
private fun formatMorseDuration(durationMillis: Long): String {
    val totalTenths = ceil(
        durationMillis.coerceAtLeast(0L) / MILLIS_PER_TENTH_SECOND.toDouble()
    ).toLong()
    val minutes = totalTenths / TENTHS_PER_MINUTE
    val seconds = (totalTenths % TENTHS_PER_MINUTE) / 10.0

    return if (minutes > 0L) {
        String.format(Locale.CHINA, "%d分%04.1f秒", minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%.1f秒", seconds)
    }
}

/**
 * 把纯模型校验问题转换为页面提示。
 *
 * @param issue 爆闪参数的稳定问题类型。
 *
 * @return 可直接展示给用户的中文修正建议。
 */
private fun flashlightIssueMessage(issue: FlashlightSettingsIssue): String {
    return when (issue) {
        FlashlightSettingsIssue.TOTAL_DURATION_OUT_OF_RANGE -> "总运行时长必须在1～300秒之间"
        FlashlightSettingsIssue.ON_DURATION_TOO_SHORT -> "亮灯时长不能少于50ms"
        FlashlightSettingsIssue.OFF_DURATION_TOO_SHORT -> "灭灯时长不能少于50ms"
        FlashlightSettingsIssue.PERIOD_TOO_LONG -> "亮灯和灭灯时长合计不能超过2000ms"
        FlashlightSettingsIssue.FREQUENCY_OUT_OF_RANGE -> "爆闪频率必须在0.5～10Hz之间"
    }
}

private const val DEFAULT_PHASE_DURATION_MILLIS = 100
private const val DEFAULT_TOTAL_DURATION_SECONDS = 10
private const val PHASE_DURATION_STEP_MILLIS = 10
private const val FREQUENCY_SLIDER_STEPS = 18
private const val MAX_TOTAL_DURATION_INPUT_LENGTH = 3
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_TENTH_SECOND = 100L
private const val TENTHS_PER_MINUTE = 600L
private const val MORSE_COUNTDOWN_REFRESH_MILLIS = 100L
private const val MAX_DISPLAYED_UNSUPPORTED_CHARACTERS = 8
private const val MORSE_PREVIEW_MAX_LINES = 5
