package com.example.harleyapp.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.harleyapp.model.QrScanContent
import com.example.harleyapp.model.QrScanContentType
import com.example.harleyapp.model.parseQrScanContent
import com.example.harleyapp.system.QrCodeAnalyzer
import com.example.harleyapp.system.decodeQrCodeFromImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 提供相机实时识别、相册图片识别和安全结果处理的二维码页面。
 *
 * 使用方法：
 * 功能中心传入[onBack]即可打开。页面首次进入时申请相机权限，未授权仍可从相册选择图片；识别
 * 成功后先展示内容，网页只有在用户点击“确认打开”后才交给系统浏览器。
 *
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外层传入的安全边距修饰器。
 * @return 无返回值，直接输出完整二维码扫描页面。
 */
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by rememberSaveable {
        mutableStateOf(false)
    }
    var rawResult by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var scanSession by rememberSaveable {
        mutableIntStateOf(0)
    }
    var camera by remember {
        mutableStateOf<Camera?>(null)
    }
    var torchEnabled by rememberSaveable {
        mutableStateOf(false)
    }
    var isGalleryDecoding by remember {
        mutableStateOf(false)
    }
    var operationMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    val scanContent = remember(rawResult) {
        rawResult?.let(::parseQrScanContent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        operationMessage = if (granted) null else "未获得相机权限，你仍可以从相册识别二维码"
        if (granted) scanSession += 1
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isGalleryDecoding = true
            operationMessage = null
            val decoded = decodeQrCodeFromImage(context, uri)
            if (decoded == null) {
                operationMessage = "没有在这张图片中识别到二维码"
            } else {
                rawResult = decoded
                torchEnabled = false
            }
            isGalleryDecoding = false
        }
    }
    val applicationSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        cameraPermissionGranted = granted
        if (granted) {
            operationMessage = null
            scanSession += 1
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
        title = "二维码扫描",
        subtitle = "相机画面和识别过程全部留在本机",
        onBack = onBack
    ) { contentModifier ->
        LazyColumn(
            modifier = contentModifier,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ScannerPrivacyCard()
            }

            if (scanContent == null && cameraPermissionGranted) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(MaterialTheme.colorScheme.scrim),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            QrCameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                scanSession = scanSession,
                                onCameraReady = { availableCamera ->
                                    camera = availableCamera
                                    if (availableCamera == null) torchEnabled = false
                                },
                                onDecoded = { decoded ->
                                    rawResult = decoded
                                    torchEnabled = false
                                    operationMessage = null
                                },
                                onFailure = {
                                    operationMessage = "相机暂时无法识别，请重试或从相册选择图片"
                                }
                            )
                            Surface(
                                modifier = Modifier.padding(14.dp),
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    text = "将二维码完整放入画面",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            if (scanContent == null && !cameraPermissionGranted) {
                item {
                    CameraPermissionCard(
                        onRequestPermission = {
                            permissionRequested = true
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onOpenSettings = {
                            applicationSettingsLauncher.launch(createApplicationSettingsIntent(context))
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (scanContent == null && cameraPermissionGranted) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                            onClick = {
                                val targetEnabled = !torchEnabled
                                camera?.cameraControl?.enableTorch(targetEnabled)
                                torchEnabled = targetEnabled
                            }
                        ) {
                            Text(text = if (torchEnabled) "关闭手电筒" else "打开手电筒")
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !isGalleryDecoding,
                        onClick = { galleryLauncher.launch("image/*") }
                    ) {
                        Text(text = if (isGalleryDecoding) "正在识别" else "从相册识别")
                    }
                }
            }

            operationMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            scanContent?.let { content ->
                item {
                    ScanResultCard(
                        content = content,
                        onCopy = {
                            copyQrResult(context, content.rawValue)
                            operationMessage = "识别内容已复制"
                        },
                        onOpenWeb = content.safeWebUrl?.let { safeUrl ->
                            {
                                if (!openSafeWebUrl(context, safeUrl)) {
                                    operationMessage = "没有找到可以打开网页的应用"
                                }
                            }
                        },
                        onScanAgain = {
                            rawResult = null
                            operationMessage = null
                            torchEnabled = false
                            scanSession += 1
                        }
                    )
                }
            }
        }
    }
}

/** 显示扫码本地处理和安全打开规则。 */
@Composable
private fun ScannerPrivacyCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "本地识别，不上传画面",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "支持相机和相册二维码。识别到网址时不会自动跳转，会先让你确认内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 显示相机未授权时的解释、再次申请和系统设置入口。 */
@Composable
private fun CameraPermissionCard(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "需要相机权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "权限只在这个页面用于实时取景。若此前选择了不再询问，可进入系统设置重新开启。",
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

/** 显示经过安全分类的扫码结果和后续显式操作。 */
@Composable
private fun ScanResultCard(
    content: QrScanContent,
    onCopy: () -> Unit,
    onOpenWeb: (() -> Unit)?,
    onScanAgain: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    text = content.type.displayName,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(text = content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = content.detail, style = MaterialTheme.typography.bodyLarge)
            if (content.type == QrScanContentType.WEB_LINK) {
                Text(
                    text = "请确认域名可信后再打开，App不会代替你登录或填写信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                onOpenWeb?.let { openWeb ->
                    Button(modifier = Modifier.weight(1f), onClick = openWeb) {
                        Text(text = "确认打开")
                    }
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onCopy) {
                    Text(text = "复制内容")
                }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onScanAgain) {
                Text(text = "继续扫描")
            }
        }
    }
}

/**
 * 把CameraX后置相机预览嵌入Compose，并为当前扫描轮次绑定分析器。
 *
 * @param scanSession 每次递增都会解绑旧分析器并开始新的识别轮次。
 * @param onCameraReady 相机绑定成功或解绑后的回调，供页面控制手电筒。
 * @param onDecoded 首次识别到有效二维码文本后的回调，已切换到主线程。
 * @param onFailure 相机绑定或帧分析出现异常时的回调。
 * @param modifier 外部布局修饰器。
 * @return 无返回值，直接输出相机预览。
 */
@Composable
private fun QrCameraPreview(
    scanSession: Int,
    onCameraReady: (Camera?) -> Unit,
    onDecoded: (String) -> Unit,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )

    DisposableEffect(lifecycleOwner, previewView, scanSession) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null
        var effectActive = true

        providerFuture.addListener(
            {
                if (!effectActive) return@addListener
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply {
                            setAnalyzer(
                                analysisExecutor,
                                QrCodeAnalyzer(
                                    onDecoded = { decoded ->
                                        mainExecutor.execute {
                                            if (effectActive) onDecoded(decoded)
                                        }
                                    },
                                    onFailure = { error ->
                                        mainExecutor.execute {
                                            if (effectActive) onFailure(error)
                                        }
                                    }
                                )
                            )
                        }
                    provider.unbindAll()
                    val boundCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    onCameraReady(boundCamera)
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to bind QR scanner camera", error)
                    onCameraReady(null)
                    onFailure(error)
                }
            },
            mainExecutor
        )

        onDispose {
            effectActive = false
            cameraProvider?.unbindAll()
            onCameraReady(null)
            analysisExecutor.shutdown()
        }
    }
}

/** 把识别结果写入Android系统剪贴板。 */
private fun copyQrResult(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("QR code content", value))
}

/** 仅把已通过模型层校验的HTTP或HTTPS网址交给系统浏览器。 */
private fun openSafeWebUrl(context: Context, safeUrl: String): Boolean {
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, safeUrl.toUri()))
        true
    }.getOrElse { error ->
        Log.e(TAG, "Failed to open scanned web URL", error)
        false
    }
}

/** 创建当前App系统详情页Intent，返回页面时由Activity Result重新检查相机权限。 */
private fun createApplicationSettingsIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
}

private const val TAG = "QrScannerScreen"
