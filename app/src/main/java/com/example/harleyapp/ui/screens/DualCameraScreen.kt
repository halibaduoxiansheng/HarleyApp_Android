package com.example.harleyapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 显示前置、后置摄像头同时取景的双画面页面。
 *
 * 使用方法：
 * 从功能中心进入页面时传入[onBack]。页面会在首次进入时申请相机权限，获得权限后查询CameraX报告的
 * 并发相机组合，并只在同一个组合中找到前置和后置摄像头后启动预览。竖屏时两路画面上下各占一半，
 * 横屏时左右各占一半；点击任一画面会立即交换显示位置，在某个画面上双指捏合只会调整该镜头。
 *
 * 页面离开组合树、权限被收回或生命周期销毁时会解除本页创建的全部CameraX绑定。设备未提供前后摄
 * 并发组合，或相机暂时被其他应用占用时，会显示中文原因和重试入口。
 *
 * @param onBack 用户点击顶部返回按钮或触发系统返回键时执行的回调。
 * @param modifier 外部传入的页面修饰器，通常用于承接导航容器提供的安全边距。
 * @return 无返回值，直接输出完整的双摄像头预览页面。
 */
@Composable
fun DualCameraScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by rememberSaveable {
        mutableStateOf(false)
    }
    var bindingAttempt by rememberSaveable {
        mutableIntStateOf(0)
    }
    var startupState by remember {
        mutableStateOf(DualCameraStartupState.STARTING)
    }
    var startupMessage by remember {
        mutableStateOf<String?>(null)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) {
            startupState = DualCameraStartupState.STARTING
            startupMessage = null
            bindingAttempt += 1
        }
    }
    val applicationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = granted
        if (granted) {
            startupState = DualCameraStartupState.STARTING
            startupMessage = null
            bindingAttempt += 1
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    FeatureDetailScaffold(
        modifier = modifier,
        title = "双面摄像头",
        subtitle = "点击任一画面交换位置，双指捏合可单独缩放",
        onBack = onBack
    ) { contentModifier ->
        if (cameraPermissionGranted) {
            DualCameraViewport(
                modifier = contentModifier,
                bindingAttempt = bindingAttempt,
                startupState = startupState,
                startupMessage = startupMessage,
                onStarting = {
                    startupState = DualCameraStartupState.STARTING
                    startupMessage = null
                },
                onReady = {
                    startupState = DualCameraStartupState.READY
                    startupMessage = null
                },
                onUnavailable = { message ->
                    startupState = DualCameraStartupState.UNAVAILABLE
                    startupMessage = message
                },
                onFailure = { message ->
                    startupState = DualCameraStartupState.FAILED
                    startupMessage = message
                },
                onRetry = {
                    startupState = DualCameraStartupState.STARTING
                    startupMessage = null
                    bindingAttempt += 1
                }
            )
        } else {
            DualCameraPermissionContent(
                modifier = contentModifier,
                onRequestPermission = {
                    permissionRequested = true
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onOpenSettings = {
                    applicationSettingsLauncher.launch(
                        createDualCameraSettingsIntent(context)
                    )
                }
            )
        }
    }
}

/**
 * 承载两路Compose取景器、横竖屏等分布局、位置交换和启动状态提示。
 *
 * 使用方法：
 * 仅在相机权限已经获得时调用。前摄和后摄取景器在组合树中的身份与顺序始终固定，点击交换只改变
 * 两个placeable最终落在第一半或第二半的坐标，不会更换SurfaceProvider，也不会解除或重建相机会话。
 * 这样可以在保留两枚独立[Camera]做分路变焦的同时，避免移动传统PreviewView带来的越界或黑屏。
 *
 * @param bindingAttempt 绑定轮次标识，每次递增都会释放旧绑定并重新尝试启动两路相机。
 * @param startupState 当前启动状态，用于决定显示加载、不可用或失败提示。
 * @param startupMessage 不可用或失败时面向用户显示的中文说明，为空时使用默认说明。
 * @param onStarting 开始新一轮CameraX初始化时执行的回调。
 * @param onReady 前后两路均已产生SurfaceRequest且两枚相机均进入OPEN状态时执行的回调。
 * @param onUnavailable 设备没有报告前后摄并发组合时执行的回调，参数为中文说明。
 * @param onFailure CameraX初始化或绑定异常时执行的回调，参数为中文说明。
 * @param onRetry 用户点击重试按钮时执行的回调。
 * @param modifier 外部传入的布局修饰器。
 * @return 无返回值，直接输出双路预览区域及状态浮层。
 */
@Composable
private fun DualCameraViewport(
    bindingAttempt: Int,
    startupState: DualCameraStartupState,
    startupMessage: String?,
    onStarting: () -> Unit,
    onReady: () -> Unit,
    onUnavailable: (String) -> Unit,
    onFailure: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val targetRotation = rememberDualCameraDisplayRotation()
    val lifecycleStarted = rememberDualCameraLifecycleStarted()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var frontFirst by rememberSaveable {
        mutableStateOf(false)
    }
    var frontSurfaceRequest by remember {
        mutableStateOf<SurfaceRequest?>(null)
    }
    var backSurfaceRequest by remember {
        mutableStateOf<SurfaceRequest?>(null)
    }
    var frontCamera by remember {
        mutableStateOf<Camera?>(null)
    }
    var backCamera by remember {
        mutableStateOf<Camera?>(null)
    }
    val frontZoomRatio = rememberReportedCameraZoomRatio(frontCamera)
    val backZoomRatio = rememberReportedCameraZoomRatio(backCamera)
    val frontCameraState = rememberReportedCameraState(frontCamera)
    val backCameraState = rememberReportedCameraState(backCamera)

    ConcurrentCameraBinding(
        bindingAttempt = bindingAttempt,
        targetRotation = targetRotation,
        onStarting = {
            frontSurfaceRequest = null
            backSurfaceRequest = null
            onStarting()
        },
        onFrontSurfaceRequest = { request ->
            frontSurfaceRequest = request
        },
        onBackSurfaceRequest = { request ->
            backSurfaceRequest = request
        },
        onCamerasReady = { readyFrontCamera, readyBackCamera ->
            frontCamera = readyFrontCamera
            backCamera = readyBackCamera
        },
        onSessionReleased = {
            frontCamera = null
            backCamera = null
            frontSurfaceRequest = null
            backSurfaceRequest = null
        },
        onUnavailable = onUnavailable,
        onFailure = onFailure
    )

    LaunchedEffect(
        frontSurfaceRequest,
        backSurfaceRequest,
        frontCameraState,
        backCameraState,
        startupState
    ) {
        val stateError = frontCameraState?.error ?: backCameraState?.error
        val bothCamerasOpen =
            frontSurfaceRequest != null &&
                backSurfaceRequest != null &&
                frontCameraState?.type == CameraState.Type.OPEN &&
                backCameraState?.type == CameraState.Type.OPEN

        if (stateError != null) {
            Log.w(TAG, "Concurrent camera state error: ${stateError.code}")
            onFailure(createDualCameraStateErrorMessage(stateError))
        } else if (bothCamerasOpen) {
            onReady()
        } else if (startupState == DualCameraStartupState.READY) {
            // 从后台恢复等场景中，相机会先经过CLOSED/OPENING；此时撤销过期的“已打开”语义。
            onStarting()
        }
    }

    LaunchedEffect(
        bindingAttempt,
        frontCamera,
        backCamera,
        startupState,
        lifecycleStarted
    ) {
        if (
            frontCamera != null &&
            backCamera != null &&
            startupState == DualCameraStartupState.STARTING &&
            lifecycleStarted
        ) {
            // PENDING_OPEN可能长期不携带错误；限定等待时间，避免设备被占用时页面无限转圈。
            delay(DUAL_CAMERA_OPEN_TIMEOUT_MILLIS)
            val latestFrontState = frontCamera?.cameraInfo?.cameraState?.value
            val latestBackState = backCamera?.cameraInfo?.cameraState?.value
            val openedAtDeadline =
                frontSurfaceRequest != null &&
                    backSurfaceRequest != null &&
                    latestFrontState?.error == null &&
                    latestBackState?.error == null &&
                    latestFrontState?.type == CameraState.Type.OPEN &&
                    latestBackState?.type == CameraState.Type.OPEN
            if (!openedAtDeadline) {
                Log.w(TAG, "Concurrent cameras did not open within timeout")
                onFailure(
                    "前后摄像头长时间未能同时打开。请关闭占用摄像头的应用后重试。"
                )
            }
        }
    }

    val readinessModifier = if (startupState == DualCameraStartupState.READY) {
        Modifier.semantics {
            contentDescription = "双路相机已打开"
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(readinessModifier)
    ) {
        DualCameraLensLayout(
            modifier = Modifier.fillMaxSize(),
            isLandscape = isLandscape,
            frontFirst = frontFirst,
            frontContent = {
                DualCameraPane(
                    modifier = Modifier.fillMaxSize(),
                    lens = DualCameraLens.FRONT,
                    surfaceRequest = frontSurfaceRequest,
                    camera = frontCamera,
                    zoomRatio = frontZoomRatio,
                    onSwapRequested = { frontFirst = !frontFirst }
                )
            },
            backContent = {
                DualCameraPane(
                    modifier = Modifier.fillMaxSize(),
                    lens = DualCameraLens.BACK,
                    surfaceRequest = backSurfaceRequest,
                    camera = backCamera,
                    zoomRatio = backZoomRatio,
                    onSwapRequested = { frontFirst = !frontFirst }
                )
            }
        )

        when (startupState) {
            DualCameraStartupState.STARTING -> {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(text = "正在启动前后双摄像头…")
                    }
                }
            }

            DualCameraStartupState.UNAVAILABLE,
            DualCameraStartupState.FAILED -> {
                DualCameraFailureCard(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    message = startupMessage ?: "双摄像头当前不可用，请稍后重试。",
                    onRetry = onRetry
                )
            }

            DualCameraStartupState.READY -> Unit
        }
    }
}

/**
 * 观察双摄页面所属生命周期是否至少处于STARTED状态。
 *
 * 使用方法：
 * 在需要启动相机等待计时的Composable中调用，并仅在返回true时累计超时时间。这样App进入后台后，
 * CameraX按生命周期关闭或等待相机不会消耗前台启动时限；重新回到前台时会从新的完整时限开始等待。
 * 函数会随LifecycleOwner变化重新注册，并在离开组合树时移除Observer。
 *
 * @return 页面生命周期当前至少为STARTED时返回true，否则返回false。
 */
@Composable
private fun rememberDualCameraLifecycleStarted(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return started
}

/**
 * 观察当前Compose宿主所在显示屏的物理旋转角度。
 *
 * 使用方法：
 * 在需要把屏幕旋转同步给CameraX的Composable中直接调用。横竖屏切换通常会引发Configuration重组，
 * 但同方向内的0°与180°切换不一定改变Configuration；本函数额外注册[DisplayManager.DisplayListener]，
 * 让这类旋转也能更新状态。组件离开组合树时会注销监听，避免页面关闭后继续持有View。
 *
 * @return 当前显示屏对应的Surface旋转常量；显示屏暂时不可用时返回ROTATION_0。
 */
@Composable
private fun rememberDualCameraDisplayRotation(): Int {
    val context = LocalContext.current
    val view = LocalView.current
    var rotation by remember(view) {
        mutableIntStateOf(view.display?.rotation ?: AndroidSurface.ROTATION_0)
    }

    DisposableEffect(context, view) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                val currentDisplay = view.display ?: return
                if (currentDisplay.displayId == displayId) {
                    rotation = currentDisplay.rotation
                }
            }
        }

        rotation = view.display?.rotation ?: AndroidSurface.ROTATION_0
        displayManager.registerDisplayListener(listener, null)

        onDispose {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    return rotation
}

/**
 * 把前、后摄两个身份固定的Compose节点分别测量为半屏，并按当前顺序摆放。
 *
 * 使用方法：
 * [frontContent]始终作为第一个组合节点，[backContent]始终作为第二个组合节点，禁止为了换位交换两者
 * 的组合顺序。[frontFirst]只影响placement坐标，因此CameraXViewfinder持有的SurfaceRequest不会因
 * 点击交换而离开组合树。竖屏按上下等分，横屏按左右等分，奇数像素交给第二个物理槽。
 *
 * @param isLandscape true表示横屏左右等分，false表示竖屏上下等分。
 * @param frontFirst true把前摄放在第一半，false把后摄放在第一半。
 * @param frontContent 身份固定的前置摄像头取景器及交互层。
 * @param backContent 身份固定的后置摄像头取景器及交互层。
 * @param modifier 外部传入的全屏预览区域修饰器。
 * @return 无返回值，直接输出两个各占一半且可互换坐标的取景节点。
 */
@Composable
private fun DualCameraLensLayout(
    isLandscape: Boolean,
    frontFirst: Boolean,
    frontContent: @Composable () -> Unit,
    backContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        modifier = modifier,
        content = {
            frontContent()
            backContent()
        }
    ) { measurables, constraints ->
        check(measurables.size == 2) {
            "Dual camera layout requires exactly two preview nodes"
        }

        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            constraints.minWidth
        }
        val layoutHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            constraints.minHeight
        }

        if (isLandscape) {
            val firstWidth = layoutWidth / 2
            val secondWidth = layoutWidth - firstWidth
            val frontWidth = if (frontFirst) firstWidth else secondWidth
            val backWidth = if (frontFirst) secondWidth else firstWidth
            val frontPlaceable = measurables[0].measure(
                Constraints.fixed(frontWidth, layoutHeight)
            )
            val backPlaceable = measurables[1].measure(
                Constraints.fixed(backWidth, layoutHeight)
            )

            layout(layoutWidth, layoutHeight) {
                if (frontFirst) {
                    frontPlaceable.place(0, 0)
                    backPlaceable.place(firstWidth, 0)
                } else {
                    backPlaceable.place(0, 0)
                    frontPlaceable.place(firstWidth, 0)
                }
            }
        } else {
            val firstHeight = layoutHeight / 2
            val secondHeight = layoutHeight - firstHeight
            val frontHeight = if (frontFirst) firstHeight else secondHeight
            val backHeight = if (frontFirst) secondHeight else firstHeight
            val frontPlaceable = measurables[0].measure(
                Constraints.fixed(layoutWidth, frontHeight)
            )
            val backPlaceable = measurables[1].measure(
                Constraints.fixed(layoutWidth, backHeight)
            )

            layout(layoutWidth, layoutHeight) {
                if (frontFirst) {
                    frontPlaceable.place(0, 0)
                    backPlaceable.place(0, firstHeight)
                } else {
                    backPlaceable.place(0, 0)
                    frontPlaceable.place(0, firstHeight)
                }
            }
        }
    }
}

/**
 * 显示单路CameraX Compose取景器，并处理该镜头独立的轻点换位和双指缩放。
 *
 * 使用方法：
 * 按镜头身份传入对应[SurfaceRequest]、[Camera]和CameraX报告的[zoomRatio]。单指移动未超过系统
 * touch slop时才视为轻点；本轮手势只要出现过两根手指，就只处理缩放而不会在结束时误触换位。
 * 取景器显式采用EMBEDDED模式，使实时流参与Compose常规布局、裁剪与层叠，文字角标可稳定显示其上。
 *
 * @param lens 当前节点固定对应的前置或后置镜头。
 * @param surfaceRequest 当前Preview向Compose请求的输出Surface，启动期间允许为空。
 * @param camera CameraX绑定成功后返回的相机对象，启动期间允许为空。
 * @param zoomRatio CameraInfo当前报告的缩放倍率，用于角标和下一轮缩放基准。
 * @param onSwapRequested 用户轻点当前画面、要求交换前后显示位置时执行的回调。
 * @param modifier 外部传入的等分布局修饰器。
 * @return 无返回值，直接输出一路实时预览、镜头标签与交互提示。
 */
@Composable
private fun DualCameraPane(
    lens: DualCameraLens,
    surfaceRequest: SurfaceRequest?,
    camera: Camera?,
    zoomRatio: Float,
    onSwapRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) {
        ContextCompat.getMainExecutor(context)
    }
    val latestCamera by rememberUpdatedState(camera)
    val latestReportedZoomRatio by rememberUpdatedState(zoomRatio)
    val pendingZoomRatio = remember(camera) {
        mutableFloatStateOf(zoomRatio)
    }

    LaunchedEffect(camera, zoomRatio) {
        pendingZoomRatio.floatValue = zoomRatio
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black)
            .dualCameraGestureInput(
                lens = lens,
                gestureSessionKey = camera,
                onTap = onSwapRequested,
                onZoom = { scaleFactor ->
                    updateDualCameraZoom(
                        camera = latestCamera,
                        currentRatio = pendingZoomRatio.floatValue,
                        scaleFactor = scaleFactor,
                        callbackExecutor = mainExecutor,
                        onTargetSubmitted = { targetRatio ->
                            // 目标值仅作为同一手势的连续计算基准；界面角标显示CameraX报告值。
                            pendingZoomRatio.floatValue = targetRatio
                        },
                        onRequestRejected = {
                            pendingZoomRatio.floatValue = latestReportedZoomRatio
                        }
                    )
                }
            )
    ) {
        if (surfaceRequest != null) {
            CameraXViewfinder(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                surfaceRequest = surfaceRequest,
                implementationMode = ImplementationMode.EMBEDDED,
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                text = "${lens.displayName}  ${formatDualCameraZoomRatio(zoomRatio)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                text = "点击交换 · 双指缩放",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * 为单个相机画面安装互斥的轻点换位和多指缩放手势状态机。
 *
 * 使用方法：
 * 把本修饰器放在覆盖整个半屏的容器上。单指按下后若移动距离不超过系统touch slop，抬起时调用
 * [onTap]；一旦事件中出现两个或更多pointer，本轮永久切换为缩放模式，只把有效scaleFactor交给
 * [onZoom]，抬起时不会再触发点击。语义层同时提供可点击动作，便于无障碍服务和自动化测试操作。
 *
 * @param lens 该手势区域固定对应的镜头身份，用作pointerInput重新启动键。
 * @param gestureSessionKey 当前镜头会话的身份键；相机重新绑定后必须更换，以免手势协程继续引用旧
 * 会话的倍率累计状态和回调。
 * @param onTap 确认单指轻点后执行的换位回调。
 * @param onZoom 双指距离发生变化时执行的相对缩放回调。
 * @return 包含无障碍点击语义和触摸识别逻辑的新Modifier。
 */
private fun Modifier.dualCameraGestureInput(
    lens: DualCameraLens,
    gestureSessionKey: Any?,
    onTap: () -> Unit,
    onZoom: (Float) -> Unit
): Modifier {
    return semantics {
        onClick(label = "交换前后摄像头位置") {
            onTap()
            true
        }
    }.pointerInput(lens, gestureSessionKey) {
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val firstPosition = firstDown.position
            var usedMultiplePointers = false
            var movedBeyondTapSlop = false

            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1 || event.changes.count { it.pressed } > 1) {
                    usedMultiplePointers = true
                }

                if (usedMultiplePointers) {
                    val scaleFactor = event.calculateZoom()
                    if (
                        scaleFactor.isFinite() &&
                        scaleFactor > 0f &&
                        abs(scaleFactor - 1f) > ZOOM_GESTURE_EPSILON
                    ) {
                        onZoom(scaleFactor)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) {
                            change.consume()
                        }
                    }
                } else {
                    val firstPointer = event.changes.firstOrNull { change ->
                        change.id == firstDown.id
                    }
                    if (
                        firstPointer != null &&
                        (firstPointer.position - firstPosition).getDistance() >
                        viewConfiguration.touchSlop
                    ) {
                        movedBeyondTapSlop = true
                    }
                }
            } while (event.changes.any { change -> change.pressed })

            if (!usedMultiplePointers && !movedBeyondTapSlop) {
                onTap()
            }
        }
    }
}

/**
 * 查询并绑定同一CameraX并发组合内的一枚前摄和一枚后摄。
 *
 * 使用方法：
 * 组件先读取[ProcessCameraProvider.availableConcurrentCameraInfos]，只接受同一个子列表内同时存在前摄
 * 和后摄的组合；随后分别建立两个不同的[Preview]和[UseCaseGroup]，再以一次并发绑定启动两路。
 * 两个Preview的SurfaceProvider只把最新[SurfaceRequest]交给固定身份的Compose取景器。点击换位不会
 * 改变本组件的Effect key，所以相机会话、SurfaceRequest和两枚独立CameraControl都持续存在。
 *
 * @param bindingAttempt 绑定轮次标识，仅用户重试时变化。
 * @param targetRotation 当前显示方向；变化时直接更新现有Preview，不重建并发相机会话。
 * @param onStarting CameraX初始化开始时执行的回调。
 * @param onFrontSurfaceRequest 前摄Preview创建或更新SurfaceRequest时执行的回调。
 * @param onBackSurfaceRequest 后摄Preview创建或更新SurfaceRequest时执行的回调。
 * @param onCamerasReady 绑定成功时返回前摄Camera和后摄Camera的回调。
 * @param onSessionReleased 当前绑定失败或被释放时执行的回调，用于清除过期引用。
 * @param onUnavailable 没有找到前后摄并发组合时执行的回调，参数为中文说明。
 * @param onFailure 初始化或绑定发生异常时执行的回调，参数为中文说明。
 * @return 无返回值；绑定结果通过各回调返回，离开组合树时自动解除相机绑定。
 */
@Composable
private fun ConcurrentCameraBinding(
    bindingAttempt: Int,
    targetRotation: Int,
    onStarting: () -> Unit,
    onFrontSurfaceRequest: (SurfaceRequest) -> Unit,
    onBackSurfaceRequest: (SurfaceRequest) -> Unit,
    onCamerasReady: (Camera, Camera) -> Unit,
    onSessionReleased: () -> Unit,
    onUnavailable: (String) -> Unit,
    onFailure: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnStarting by rememberUpdatedState(onStarting)
    val latestOnFrontSurfaceRequest by rememberUpdatedState(onFrontSurfaceRequest)
    val latestOnBackSurfaceRequest by rememberUpdatedState(onBackSurfaceRequest)
    val latestOnCamerasReady by rememberUpdatedState(onCamerasReady)
    val latestOnSessionReleased by rememberUpdatedState(onSessionReleased)
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    val latestOnFailure by rememberUpdatedState(onFailure)
    var activePreviewPair by remember {
        mutableStateOf<Pair<Preview, Preview>?>(null)
    }

    LaunchedEffect(activePreviewPair, targetRotation) {
        activePreviewPair?.let { (frontPreview, backPreview) ->
            // Preview允许运行中更新targetRotation，横竖屏切换无需关闭两枚物理相机。
            frontPreview.targetRotation = targetRotation
            backPreview.targetRotation = targetRotation
        }
    }

    DisposableEffect(context, lifecycleOwner, bindingAttempt) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null
        var activeFrontPreview: Preview? = null
        var activeBackPreview: Preview? = null
        var effectActive = true
        var sessionAcceptingSurfaceRequests = true

        latestOnStarting()
        providerFuture.addListener(
            {
                if (!effectActive) return@addListener

                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val concurrentPair = findFrontBackConcurrentPair(
                        provider.availableConcurrentCameraInfos
                    )
                    if (concurrentPair == null) {
                        sessionAcceptingSurfaceRequests = false
                        provider.unbindAll()
                        latestOnSessionReleased()
                        latestOnUnavailable(
                            "当前设备没有提供可同时运行的前置与后置摄像头组合。"
                        )
                        return@addListener
                    }

                    val frontPreview = createDualCameraPreview(
                        targetRotation = targetRotation,
                        callbackExecutor = mainExecutor,
                        onSurfaceRequest = { request ->
                            if (effectActive && sessionAcceptingSurfaceRequests) {
                                Log.d(
                                    TAG,
                                    "Front camera surface requested: ${request.resolution}"
                                )
                                latestOnFrontSurfaceRequest(request)
                            } else {
                                request.willNotProvideSurface()
                                Log.d(TAG, "Rejected stale front camera surface request")
                            }
                        }
                    )
                    val backPreview = createDualCameraPreview(
                        targetRotation = targetRotation,
                        callbackExecutor = mainExecutor,
                        onSurfaceRequest = { request ->
                            if (effectActive && sessionAcceptingSurfaceRequests) {
                                Log.d(
                                    TAG,
                                    "Back camera surface requested: ${request.resolution}"
                                )
                                latestOnBackSurfaceRequest(request)
                            } else {
                                request.willNotProvideSurface()
                                Log.d(TAG, "Rejected stale back camera surface request")
                            }
                        }
                    )
                    activeFrontPreview = frontPreview
                    activeBackPreview = backPreview
                    activePreviewPair = frontPreview to backPreview
                    val frontConfig = SingleCameraConfig(
                        concurrentPair.front.cameraSelector,
                        UseCaseGroup.Builder()
                            .addUseCase(frontPreview)
                            .build(),
                        lifecycleOwner
                    )
                    val backConfig = SingleCameraConfig(
                        concurrentPair.back.cameraSelector,
                        UseCaseGroup.Builder()
                            .addUseCase(backPreview)
                            .build(),
                        lifecycleOwner
                    )

                    provider.unbindAll()
                    val concurrentCamera = provider.bindToLifecycle(
                        listOf(frontConfig, backConfig)
                    )
                    val boundFrontCamera = concurrentCamera.cameras.firstOrNull { camera ->
                        camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
                    }
                    val boundBackCamera = concurrentCamera.cameras.firstOrNull { camera ->
                        camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
                    }

                    if (boundFrontCamera == null || boundBackCamera == null) {
                        throw IllegalStateException(
                            "Concurrent camera binding returned incomplete cameras"
                        )
                    }

                    Log.i(TAG, "Concurrent front and back cameras bound")
                    latestOnCamerasReady(boundFrontCamera, boundBackCamera)
                } catch (error: Exception) {
                    sessionAcceptingSurfaceRequests = false
                    Log.e(TAG, "Failed to bind concurrent front and back cameras", error)
                    clearConcurrentPreviewSurfacesSafely(
                        frontPreview = activeFrontPreview,
                        backPreview = activeBackPreview
                    )
                    activePreviewPair = null
                    try {
                        cameraProvider?.unbindAll()
                    } catch (cleanupError: Exception) {
                        Log.w(TAG, "Failed to clean up cameras after binding error", cleanupError)
                    }
                    latestOnSessionReleased()

                    val rootCause = (error as? ExecutionException)?.cause ?: error
                    val message = when (rootCause) {
                        is SecurityException -> "相机权限已失效，请返回后重新授权再尝试。"
                        is UnsupportedOperationException,
                        is IllegalArgumentException ->
                            "当前设备或当前相机配置不支持前置与后置摄像头同时运行。"

                        else -> "双摄像头启动失败。请确认没有其他应用正在使用相机，然后重试。"
                    }
                    latestOnFailure(message)
                }
            },
            mainExecutor
        )

        onDispose {
            effectActive = false
            sessionAcceptingSurfaceRequests = false
            activePreviewPair = null
            clearConcurrentPreviewSurfacesSafely(
                frontPreview = activeFrontPreview,
                backPreview = activeBackPreview
            )
            try {
                cameraProvider?.unbindAll()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to unbind concurrent cameras", error)
            }
            latestOnSessionReleased()
        }
    }
}

/**
 * 创建单路Preview，并把CameraX发出的SurfaceRequest交给Compose取景器。
 *
 * 使用方法：
 * 前摄和后摄各调用一次，必须传入不同的[onSurfaceRequest]以保持镜头身份稳定。显式指定
 * [targetRotation]，让CameraXViewfinder依据SurfaceRequest中的变换信息正确处理旋转与前摄镜像。
 * SurfaceProvider回调固定在[callbackExecutor]执行，页面可安全更新Compose状态。
 *
 * @param targetRotation 当前屏幕的Surface旋转常量。
 * @param callbackExecutor SurfaceProvider回调使用的执行器，页面传入主线程执行器。
 * @param onSurfaceRequest 新的输出Surface请求到达时执行的回调。
 * @return 已配置旋转和SurfaceProvider、尚未绑定生命周期的Preview。
 */
private fun createDualCameraPreview(
    targetRotation: Int,
    callbackExecutor: Executor,
    onSurfaceRequest: (SurfaceRequest) -> Unit
): Preview {
    return Preview.Builder()
        .setTargetRotation(targetRotation)
        .build()
        .apply {
            setSurfaceProvider(callbackExecutor, onSurfaceRequest)
        }
}

/**
 * 在失败或页面退出时安全移除两路Preview的SurfaceProvider。
 *
 * 使用方法：
 * CameraX绑定流程的异常分支和DisposableEffect清理阶段调用。即使某一路清理失败，也会继续尝试
 * 另一条路径并把异常写入英文日志，避免清理异常打断后续相机解绑。
 *
 * @param frontPreview 当前绑定轮次创建的前摄Preview；尚未创建时允许为空。
 * @param backPreview 当前绑定轮次创建的后摄Preview；尚未创建时允许为空。
 * @return 无返回值；所有清理异常均在函数内部记录，不向生命周期清理调用方抛出。
 */
private fun clearConcurrentPreviewSurfacesSafely(
    frontPreview: Preview?,
    backPreview: Preview?
) {
    try {
        frontPreview?.setSurfaceProvider(null)
    } catch (error: Exception) {
        Log.w(TAG, "Failed to clear front camera surface provider", error)
    }
    try {
        backPreview?.setSurfaceProvider(null)
    } catch (error: Exception) {
        Log.w(TAG, "Failed to clear back camera surface provider", error)
    }
}

/**
 * 在CameraX报告的并发组合中寻找同组的前置、后置摄像头。
 *
 * 使用方法：
 * 把[ProcessCameraProvider.availableConcurrentCameraInfos]原样传入。函数按平台给出的组合顺序查找，
 * 只接受同一个组合里同时存在前摄与后摄的情况，不跨组合配对。
 *
 * @param combinations CameraX报告的全部可并发CameraInfo组合。
 * @return 找到时返回包含前、后CameraInfo的配对；设备不支持这种组合时返回null。
 */
private fun findFrontBackConcurrentPair(
    combinations: List<List<CameraInfo>>
): FrontBackCameraInfoPair? {
    combinations.forEach { combination ->
        val front = combination.firstOrNull { cameraInfo ->
            cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val back = combination.firstOrNull { cameraInfo ->
            cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        if (front != null && back != null) {
            return FrontBackCameraInfoPair(front = front, back = back)
        }
    }

    return null
}

/**
 * 观察CameraInfo当前报告的缩放倍率，并在Composable生命周期内自动注册和移除Observer。
 *
 * 使用方法：
 * 把当前镜头绑定返回的[Camera]传入；尚未绑定时传null。返回值只跟随ZoomState变化，不直接使用
 * 手势累计值。CameraX可能在底层请求最终完成之前先发布目标倍率，因此该值适合用于界面反馈，不能
 * 单独作为物理镜头已经完成变焦的证明。Camera对象更换时会自动停止观察旧LiveData，并从新镜头
 * 当前状态重新开始。
 *
 * @param camera 当前需要观察的CameraX相机，启动或释放期间允许为空。
 * @return CameraInfo最近报告的zoomRatio；相机或ZoomState尚不可用时返回1.0。
 */
@Composable
private fun rememberReportedCameraZoomRatio(camera: Camera?): Float {
    val lifecycleOwner = LocalLifecycleOwner.current
    var reportedRatio by remember(camera) {
        mutableFloatStateOf(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
    }

    DisposableEffect(camera, lifecycleOwner) {
        val zoomStateLiveData = camera?.cameraInfo?.zoomState
        if (zoomStateLiveData == null) {
            onDispose { }
        } else {
            val observer = Observer<ZoomState> { zoomState ->
                reportedRatio = zoomState.zoomRatio
            }
            zoomStateLiveData.observe(lifecycleOwner, observer)

            onDispose {
                zoomStateLiveData.removeObserver(observer)
            }
        }
    }

    return reportedRatio
}

/**
 * 观察单枚CameraX相机的打开状态和异步错误。
 *
 * 使用方法：
 * 把并发绑定返回的[Camera]传入；尚未绑定或会话已释放时传null。函数会跟随当前页面生命周期观察
 * [CameraInfo.cameraState]，相机对象改变时自动解除旧Observer并注册新Observer。调用方应同时检查
 * 前、后摄均为[CameraState.Type.OPEN]后再结束启动提示，并对[state][CameraState.getError]中的错误
 * 给出可操作的失败说明。该状态能证明CameraX已打开相机设备，但不能替代真实首帧截图验证。
 *
 * @param camera 当前需要观察的CameraX相机；绑定前和释放后允许为空。
 * @return CameraX最近报告的CameraState；当前没有相机会话或尚未发布状态时返回null。
 */
@Composable
private fun rememberReportedCameraState(camera: Camera?): CameraState? {
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember(camera) {
        mutableStateOf(camera?.cameraInfo?.cameraState?.value)
    }

    DisposableEffect(camera, lifecycleOwner) {
        val cameraStateLiveData = camera?.cameraInfo?.cameraState
        if (cameraStateLiveData == null) {
            onDispose { }
        } else {
            val observer = Observer<CameraState> { cameraState ->
                state = cameraState
            }
            cameraStateLiveData.observe(lifecycleOwner, observer)

            onDispose {
                cameraStateLiveData.removeObserver(observer)
            }
        }
    }

    return state
}

/**
 * 把CameraX相机状态错误转换为面向用户的中文处理建议。
 *
 * 使用方法：
 * [rememberReportedCameraState]返回的状态包含非空error时调用。函数只根据公开错误码分类，不记录
 * 日志，也不改变绑定；恢复型错误若随后自动回到OPEN，页面会由状态观察逻辑自动恢复为可用。
 *
 * @param error CameraX通过CameraState报告的异步相机错误。
 * @return 与错误原因匹配的中文提示，包含关闭占用应用、检查策略或重启设备等下一步建议。
 */
private fun createDualCameraStateErrorMessage(error: CameraState.StateError): String {
    return when (error.code) {
        CameraState.ERROR_CAMERA_IN_USE,
        CameraState.ERROR_MAX_CAMERAS_IN_USE ->
            "摄像头正在被其他应用或系统功能使用。请关闭占用摄像头的应用后重试。"

        CameraState.ERROR_STREAM_CONFIG ->
            "当前设备无法建立这组前后双摄预览流，请重试或改用单摄功能。"

        CameraState.ERROR_CAMERA_DISABLED ->
            "摄像头已被系统或设备管理策略禁用，请恢复权限后重试。"

        CameraState.ERROR_CAMERA_FATAL_ERROR ->
            "摄像头服务发生严重错误，请重启手机后再试。"

        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
            "系统勿扰模式阻止了摄像头启动，请关闭勿扰模式后重试。"

        CameraState.ERROR_OTHER_RECOVERABLE_ERROR ->
            "摄像头暂时无法打开，CameraX正在尝试恢复；也可以稍后手动重试。"

        else -> "双摄像头运行异常，请关闭其他相机应用后重试。"
    }
}

/**
 * 按一次捏合手势更新指定Camera的zoomRatio，并限制在该镜头真实支持的范围内。
 *
 * 使用方法：
 * 手势状态机每次得到缩放因子后调用。函数以[currentRatio]为连续计算基准，读取相机当前支持的
 * 最小、最大倍率并进行夹取，再把合法目标值交给CameraX；相机尚未绑定或手势数据无效时不执行。
 *
 * @param camera 当前画面对应的CameraX相机，启动过程中允许为空。
 * @param currentRatio 本轮手势累计的目标倍率。
 * @param scaleFactor 本次双指事件相对上一帧的缩放因子，大于1表示放大，小于1表示缩小。
 * @param callbackExecutor 观察CameraControl异步结果的执行器，页面应传主线程执行器。
 * @param onTargetSubmitted 合法目标已提交后更新手势累计基准的回调。
 * @param onRequestRejected CameraX同步或异步拒绝请求后恢复累计基准的回调。
 * @return 无返回值；无法变焦时保持原倍率不变。
 */
private fun updateDualCameraZoom(
    camera: Camera?,
    currentRatio: Float,
    scaleFactor: Float,
    callbackExecutor: Executor,
    onTargetSubmitted: (Float) -> Unit,
    onRequestRejected: () -> Unit
) {
    if (camera == null) return

    val zoomState = camera.cameraInfo.zoomState.value ?: return
    val targetRatio = calculateDualCameraZoomRatio(
        currentRatio = currentRatio,
        scaleFactor = scaleFactor,
        minZoomRatio = zoomState.minZoomRatio,
        maxZoomRatio = zoomState.maxZoomRatio
    ) ?: return
    val safeCurrentRatio = currentRatio.coerceIn(
        zoomState.minZoomRatio,
        zoomState.maxZoomRatio
    )
    if (targetRatio == safeCurrentRatio) return

    submitDualCameraZoomRatio(
        camera = camera,
        targetRatio = targetRatio,
        callbackExecutor = callbackExecutor,
        onTargetSubmitted = onTargetSubmitted,
        onRequestRejected = onRequestRejected
    )
}

/**
 * 向CameraX提交已经校验过的目标倍率，并分别处理同步和异步失败。
 *
 * 使用方法：
 * 只由[updateDualCameraZoom]调用。提交成功后立即用目标值继续累计同一手势，但可见倍率由ZoomState
 * 决定；Future异步失败时记录英文日志并调用[onRequestRejected]，从真实倍率重新开始下一次计算。
 * 页面退出导致的取消，以及连续捏合时新目标覆盖旧目标产生的OperationCanceledException，都是正常
 * 控制流：不打印告警，也不把倍率累计值回退到较旧状态。
 *
 * @param camera 接收变焦请求的CameraX相机。
 * @param targetRatio 已经校验并夹取到镜头能力范围内的目标倍率。
 * @param callbackExecutor Future结果回调使用的执行器。
 * @param onTargetSubmitted 请求同步提交成功后更新手势累计值的回调。
 * @param onRequestRejected 请求同步或异步失败后恢复累计值的回调。
 * @return 无返回值；失败仅通过日志和回调反馈，不中断页面生命周期。
 */
private fun submitDualCameraZoomRatio(
    camera: Camera,
    targetRatio: Float,
    callbackExecutor: Executor,
    onTargetSubmitted: (Float) -> Unit,
    onRequestRejected: () -> Unit
) {
    try {
        val zoomFuture = camera.cameraControl.setZoomRatio(targetRatio)
        onTargetSubmitted(targetRatio)
        zoomFuture.addListener(
            {
                try {
                    zoomFuture.get()
                } catch (_: CancellationException) {
                    // 页面离开时CameraX取消未完成请求属于预期行为，无需污染日志。
                } catch (error: Exception) {
                    val rootCause = (error as? ExecutionException)?.cause ?: error
                    if (rootCause !is CameraControl.OperationCanceledException) {
                        Log.w(TAG, "Camera zoom request failed asynchronously", error)
                        onRequestRejected()
                    }
                }
            },
            callbackExecutor
        )
    } catch (error: Exception) {
        Log.w(TAG, "Failed to update camera zoom ratio", error)
        onRequestRejected()
    }
}

/**
 * 计算一次双指手势对应的目标相机倍率，并把结果限制在镜头真实能力范围内。
 *
 * 使用方法：
 * 手势层把上一次累计的目标倍率、本帧相对缩放因子，以及CameraX报告的最小、最大倍率传入。函数不
 * 访问Android对象，可由本地单元测试覆盖边界；输入包含NaN、无穷值、非正缩放因子或无效倍率区间
 * 时返回null，调用方应保持原倍率不变。
 *
 * @param currentRatio 上一次已经累计的目标倍率。
 * @param scaleFactor 当前手势帧相对上一帧的缩放因子，大于1放大，小于1缩小。
 * @param minZoomRatio 当前镜头支持的最小倍率。
 * @param maxZoomRatio 当前镜头支持的最大倍率。
 * @return 合法时返回夹取后的目标倍率；输入无效时返回null。
 */
internal fun calculateDualCameraZoomRatio(
    currentRatio: Float,
    scaleFactor: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float
): Float? {
    if (
        !currentRatio.isFinite() ||
        !scaleFactor.isFinite() ||
        !minZoomRatio.isFinite() ||
        !maxZoomRatio.isFinite() ||
        currentRatio <= 0f ||
        scaleFactor <= 0f ||
        minZoomRatio <= 0f ||
        maxZoomRatio < minZoomRatio
    ) {
        return null
    }

    val safeCurrentRatio = currentRatio.coerceIn(minZoomRatio, maxZoomRatio)
    return (safeCurrentRatio * scaleFactor).coerceIn(minZoomRatio, maxZoomRatio)
}

/**
 * 把相机倍率格式化为画面角标中的简洁文本。
 *
 * @param ratio CameraX当前报告的zoomRatio。
 * @return 保留一位小数并带“×”单位的倍率文本，例如“1.0×”。
 */
private fun formatDualCameraZoomRatio(ratio: Float): String {
    return String.format(java.util.Locale.getDefault(), "%.1f×", ratio)
}

/**
 * 显示并发能力不足或相机启动失败时的说明和重试入口。
 *
 * @param message 面向用户显示的中文故障说明。
 * @param onRetry 用户点击重试按钮时执行的回调。
 * @param modifier 外部传入的定位与边距修饰器。
 * @return 无返回值，直接输出故障说明卡片。
 */
@Composable
private fun DualCameraFailureCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "双摄像头暂不可用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Text(text = "重新尝试")
            }
        }
    }
}

/**
 * 显示相机权限用途，并提供再次授权和进入系统设置的入口。
 *
 * @param onRequestPermission 用户要求再次弹出相机权限申请时执行的回调。
 * @param onOpenSettings 用户要求打开当前App系统详情页时执行的回调。
 * @param modifier 外部传入的页面布局修饰器。
 * @return 无返回值，直接输出权限说明界面。
 */
@Composable
private fun DualCameraPermissionContent(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "需要相机权限",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "权限只在此页面用于同时显示前置和后置摄像头画面。离开页面后会立即释放相机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRequestPermission) {
                        Text(text = "授予权限")
                    }
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(text = "系统设置")
                    }
                }
            }
        }
    }
}

/**
 * 创建当前App系统详情页Intent，供用户在“不再询问”后手动恢复相机权限。
 *
 * @param context 用于读取当前应用包名的上下文。
 * @return 指向当前应用系统详情页的Intent。
 */
private fun createDualCameraSettingsIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
}

/** 表示双摄界面中的镜头身份，并提供稳定的中文画面标签。 */
private enum class DualCameraLens(val displayName: String) {
    FRONT("前置摄像头"),
    BACK("后置摄像头")
}

/** 表示并发相机从初始化到可用或失败的页面状态。 */
private enum class DualCameraStartupState {
    STARTING,
    READY,
    UNAVAILABLE,
    FAILED
}

/** 保存CameraX同一并发组合中已经确认可同时工作的前、后摄CameraInfo。 */
private data class FrontBackCameraInfoPair(
    val front: CameraInfo,
    val back: CameraInfo
)

private const val TAG = "DualCameraScreen"

/** 忽略浮点计算中肉眼不可见的极小缩放抖动。 */
private const val ZOOM_GESTURE_EPSILON = 0.0001f

/** 两枚相机完成异步打开所允许的最长等待时间，超时后向用户提供重试入口。 */
private const val DUAL_CAMERA_OPEN_TIMEOUT_MILLIS = 15_000L
