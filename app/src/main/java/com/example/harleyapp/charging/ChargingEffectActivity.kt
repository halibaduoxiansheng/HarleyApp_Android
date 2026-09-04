package com.example.harleyapp.charging

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 展示一次2.5秒全屏未来感充电效果，并在需要时点亮屏幕、显示于锁屏上方。
 *
 * 使用方法：
 * 监控服务通过[createIntent]且`preview=false`启动；设置页面预览时传入`preview=true`。页面不读取
 * HarleyApp账户或锁屏内容，只展示Android公开的本机电池数据，到时、拔线或用户点击后立即退出。
 */
class ChargingEffectActivity : ComponentActivity() {

    /**
     * 配置锁屏可见、息屏唤醒和沉浸式窗口，然后加载Compose充电动画。
     *
     * @param savedInstanceState Activity重建状态，首次启动时通常为null。
     * @return 无返回值。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWakeAndLockScreenWindow()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val preview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true
        setContent {
            ChargingEffectTheme {
                ChargingEffectRoute(
                    preview = preview,
                    onFinished = ::finish
                )
            }
        }
    }

    /**
     * 配置不同Android版本对应的锁屏显示和点亮屏幕能力。
     *
     * @return 无返回值；不会请求解除设备锁屏，也不会保持屏幕超过动画生命周期。
     */
    private fun configureWakeAndLockScreenWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    companion object {

        /**
         * 创建启动短时充电效果页面的显式Intent。
         *
         * @param context Android上下文，仅用于确定目标组件。
         * @param preview true表示设置页手动预览，未连接电源也完整播放；false表示真实插电触发。
         * @return 包含后台新任务、禁止转场和排除最近任务标志的Intent。
         */
        fun createIntent(context: Context, preview: Boolean): Intent {
            return Intent(context, ChargingEffectActivity::class.java).apply {
                putExtra(EXTRA_PREVIEW, preview)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        }

        private const val EXTRA_PREVIEW = "preview"
    }
}

/**
 * 按固定时长刷新电池快照、播放倒计时动画，并在真实充电过程中拔线时提前关闭。
 *
 * @param preview 是否为用户从设置页主动发起的无条件预览。
 * @param onFinished 动画结束、用户点击或拔线时关闭Activity的回调。
 * @return 无返回值，直接输出完整充电效果。
 */
@Composable
private fun ChargingEffectRoute(
    preview: Boolean,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val batteryReader = remember(context) {
        ChargingBatteryReader(context.applicationContext)
    }
    var snapshot by remember {
        mutableStateOf(batteryReader.read())
    }
    val remainingProgress = remember {
        Animatable(1f)
    }

    BackHandler(onBack = onFinished)

    // 固定总时长独立于电池读取耗时，确保效果不会因为厂商接口阻塞而超过2.5秒。
    LaunchedEffect(Unit) {
        remainingProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = CHARGING_EFFECT_DURATION_MILLIS.toInt(),
                easing = LinearEasing
            )
        )
        onFinished()
    }

    // 动画期间短周期读取瞬时净电流；系统广播更新较慢时，电量、电压和温度保持最近有效值。
    LaunchedEffect(preview) {
        var realConnectionSeen = snapshot.isConnected
        while (isActive) {
            val latestSnapshot = withContext(Dispatchers.Default) {
                batteryReader.read()
            }
            snapshot = latestSnapshot
            realConnectionSeen = realConnectionSeen || latestSnapshot.isConnected
            if (!preview && realConnectionSeen && !latestSnapshot.isConnected) {
                onFinished()
                break
            }
            delay(BATTERY_REFRESH_INTERVAL_MILLIS)
        }
    }

    ChargingEffectScreen(
        snapshot = snapshot,
        preview = preview,
        remainingProgress = remainingProgress.value,
        onDismiss = onFinished
    )
}

/**
 * 绘制未来感充电动画、百分比和四项实时指标。
 *
 * @param snapshot 当前电池状态快照。
 * @param preview 是否显示预览标识。
 * @param remainingProgress 从1递减到0的2.5秒剩余进度。
 * @param onDismiss 用户点击任意区域时立即关闭的回调。
 * @return 无返回值，直接输出沉浸式全屏界面。
 */
@Composable
private fun ChargingEffectScreen(
    snapshot: ChargingBatterySnapshot,
    preview: Boolean,
    remainingProgress: Float,
    onDismiss: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "charging_energy")
    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_rotation"
    )
    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_rotation"
    )
    val energyPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "energy_phase"
    )
    val corePulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )
    val statusText = when {
        preview -> "VISUAL SYSTEM PREVIEW"
        snapshot.isFull -> "CHARGE COMPLETE"
        snapshot.isCharging -> "ENERGY TRANSFER ACTIVE"
        snapshot.isConnected -> "POWER LINK ESTABLISHED"
        else -> "POWER SIGNAL ACQUIRING"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF102455),
                        Color(0xFF071126),
                        Color(0xFF01030A)
                    ),
                    radius = 1_150f
                )
            )
            .clickable(onClick = onDismiss)
    ) {
        FuturisticEnergyField(
            outerRotation = outerRotation,
            innerRotation = innerRotation,
            energyPhase = energyPhase,
            corePulse = corePulse
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "HARLEY // POWER CORE",
                color = Color(0xFF78F7FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Text(
                text = statusText,
                modifier = Modifier.padding(top = 5.dp),
                color = Color(0xFFA8B5D7),
                fontSize = 11.sp,
                letterSpacing = 1.4.sp
            )

            Spacer(modifier = Modifier.weight(0.42f))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = snapshot.levelPercent?.toString() ?: "--",
                        color = Color.White,
                        fontSize = 82.sp,
                        lineHeight = 84.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(
                        text = "%",
                        modifier = Modifier.padding(bottom = 12.dp, start = 3.dp),
                        color = Color(0xFF8AF9FF),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = snapshot.powerSource.displayName,
                    color = Color(0xFFBAA7FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.weight(0.58f))

            ChargingMetricGrid(snapshot = snapshot)

            Text(
                text = "轻触关闭 · 2.5秒自动退出",
                modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
                color = Color(0xFF7E8AAC),
                fontSize = 11.sp
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color(0xFF17213A))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(remainingProgress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF7D5CFF), Color(0xFF44FAFF))
                            )
                        )
                )
            }
        }
    }
}

/**
 * 绘制网格、粒子、旋转能量环和脉冲核心，不依赖外部图片资源。
 *
 * @param outerRotation 外层能量环顺时针角度。
 * @param innerRotation 内层能量环逆时针角度。
 * @param energyPhase 粒子从外围向核心汇聚的循环进度。
 * @param corePulse 核心光晕缩放系数。
 * @return 无返回值，直接绘制到全屏Canvas。
 */
@Composable
private fun FuturisticEnergyField(
    outerRotation: Float,
    innerRotation: Float,
    energyPhase: Float,
    corePulse: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height * 0.42f)
        val shortestSide = min(size.width, size.height)
        val outerRadius = shortestSide * 0.34f
        val gridSpacing = 42.dp.toPx()

        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = Color(0xFF67E7FF).copy(alpha = 0.045f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx()
            )
            x += gridSpacing
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = Color(0xFF8B6EFF).copy(alpha = 0.04f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            y += gridSpacing
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF5CF7FF).copy(alpha = 0.22f * corePulse),
                    Color(0xFF755CFF).copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = outerRadius * 1.28f
            ),
            radius = outerRadius * 1.28f,
            center = center
        )

        repeat(ENERGY_PARTICLE_COUNT) { index ->
            val offsetPhase = (energyPhase + index.toFloat() / ENERGY_PARTICLE_COUNT) % 1f
            val angle = index * GOLDEN_ANGLE_RADIANS + outerRotation * PI.toFloat() / 180f
            val radius = outerRadius * (0.26f + 1.3f * (1f - offsetPhase))
            val particleCenter = Offset(
                x = center.x + cos(angle) * radius,
                y = center.y + sin(angle) * radius * 0.72f
            )
            val particleAlpha = sin(offsetPhase * PI).toFloat().coerceIn(0f, 1f)
            drawCircle(
                color = if (index % 3 == 0) {
                    Color(0xFF9C78FF).copy(alpha = particleAlpha * 0.8f)
                } else {
                    Color(0xFF65F6FF).copy(alpha = particleAlpha * 0.9f)
                },
                radius = (1.5f + index % 4) * density,
                center = particleCenter
            )
        }

        val outerRingTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
        val outerRingSize = Size(outerRadius * 2f, outerRadius * 2f)
        rotate(degrees = outerRotation, pivot = center) {
            repeat(4) { index ->
                drawArc(
                    color = if (index % 2 == 0) Color(0xFF50F7FF) else Color(0xFF8D6CFF),
                    startAngle = index * 90f + 8f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = outerRingTopLeft,
                    size = outerRingSize,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.9f
                )
            }
        }

        val innerRadius = outerRadius * 0.78f
        val innerRingTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
        val innerRingSize = Size(innerRadius * 2f, innerRadius * 2f)
        rotate(degrees = innerRotation, pivot = center) {
            repeat(3) { index ->
                drawArc(
                    color = Color(0xFFA989FF),
                    startAngle = index * 120f + 16f,
                    sweepAngle = 72f,
                    useCenter = false,
                    topLeft = innerRingTopLeft,
                    size = innerRingSize,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    alpha = 0.72f
                )
            }
        }

        drawCircle(
            color = Color(0xFF67F8FF).copy(alpha = 0.18f),
            radius = outerRadius * 0.56f * corePulse,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color(0xFF7AF9FF).copy(alpha = 0.16f * corePulse),
            radius = outerRadius * 0.48f * corePulse,
            center = center
        )
    }
}

/**
 * 以两行玻璃面板展示电流、电压、温度和百分比。
 *
 * @param snapshot 当前电池快照。
 * @return 无返回值，系统未开放的单项数据明确显示“未开放”。
 */
@Composable
private fun ChargingMetricGrid(snapshot: ChargingBatterySnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChargingMetricTile(
                modifier = Modifier.weight(1f),
                label = "BATTERY CURRENT",
                value = formatChargingCurrent(snapshot.currentMilliAmps),
                accent = Color(0xFF56F3FF)
            )
            ChargingMetricTile(
                modifier = Modifier.weight(1f),
                label = "BATTERY VOLTAGE",
                value = formatBatteryVoltage(snapshot.voltageVolts),
                accent = Color(0xFF8CA9FF)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChargingMetricTile(
                modifier = Modifier.weight(1f),
                label = "TEMPERATURE",
                value = formatBatteryTemperature(snapshot.temperatureCelsius),
                accent = Color(0xFFBE8CFF)
            )
            ChargingMetricTile(
                modifier = Modifier.weight(1f),
                label = "CAPACITY",
                value = snapshot.levelPercent?.let { "$it%" } ?: "未开放",
                accent = Color(0xFF6DFFD4)
            )
        }
    }
}

/**
 * 显示一项带发光强调色的充电指标。
 *
 * @param label 指标英文标题。
 * @param value 已格式化的实时值或“未开放”。
 * @param accent 当前指标的发光强调色。
 * @param modifier 外部布局修饰器。
 * @return 无返回值。
 */
@Composable
private fun ChargingMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF111A32).copy(alpha = 0.84f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFF7785A8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Text(
                text = value,
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                fontSize = 20.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                maxLines = 1
            )
        }
    }
}

/**
 * 把电池端毫安净电流格式化为易读文本。
 *
 * @param currentMilliAmps 电池端瞬时净电流，系统未开放时为null。
 * @return 绝对值达到1000mA时使用A，否则使用mA；不可用时返回“未开放”。
 */
private fun formatChargingCurrent(currentMilliAmps: Float?): String {
    val current = currentMilliAmps ?: return "未开放"
    return if (abs(current) >= 1_000f) {
        String.format(Locale.CHINA, "%+.2f A", current / 1_000f)
    } else {
        String.format(Locale.CHINA, "%+.0f mA", current)
    }
}

/**
 * 把伏特电压格式化为两位小数。
 *
 * @param voltageVolts 电池电压，系统未开放时为null。
 * @return 带V单位的文本；不可用时返回“未开放”。
 */
private fun formatBatteryVoltage(voltageVolts: Float?): String {
    return voltageVolts?.let { voltage ->
        String.format(Locale.CHINA, "%.2f V", voltage)
    } ?: "未开放"
}

/**
 * 把电池摄氏温度格式化为一位小数。
 *
 * @param temperatureCelsius 电池温度，系统未开放时为null。
 * @return 带摄氏度单位的文本；不可用时返回“未开放”。
 */
private fun formatBatteryTemperature(temperatureCelsius: Float?): String {
    return temperatureCelsius?.let { temperature ->
        String.format(Locale.CHINA, "%.1f°C", temperature)
    } ?: "未开放"
}

/**
 * 为独立充电Activity提供固定深色Material配色，避免继承主App当前明暗主题造成闪白。
 *
 * @param content 充电效果界面内容。
 * @return 无返回值，直接应用主题并输出内容。
 */
@Composable
private fun ChargingEffectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF62F5FF),
            secondary = Color(0xFFA681FF),
            background = Color(0xFF01030A),
            surface = Color(0xFF0C1328),
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private const val CHARGING_EFFECT_DURATION_MILLIS = 2_500L
private const val BATTERY_REFRESH_INTERVAL_MILLIS = 200L
private const val ENERGY_PARTICLE_COUNT = 34
private const val GOLDEN_ANGLE_RADIANS = 2.3999631f
