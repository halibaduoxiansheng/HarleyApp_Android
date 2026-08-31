package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.harleyapp.R
import com.example.harleyapp.data.AppLockRepository
import com.example.harleyapp.model.CompanionCategory
import kotlinx.coroutines.delay

/**
 * 显示用户在首页“我的伙伴”中选择的动态伙伴和halibaduo逐字打印启动画面。
 *
 * 使用方法：
 * MainActivity首次组合时显示本页面。文字打印完成并短暂停留后自动调用[onFinished]；外层使用
 * rememberSaveable记录完成状态，旋转屏幕不会重复播放。动画不访问网络，也不会阻塞数据加载。
 *
 * @param onFinished 动画完整播放后的回调。
 * @param companionCategory 用户当前选择的伙伴分类。
 * @param companionLevel 用户伙伴当前等级，用于显示已经解锁的形态。
 * @param modifier 外部全屏修饰器。
 * @return 无返回值，直接输出启动动画。
 */
@Composable
fun HalibaduoStartupScreen(
    companionCategory: CompanionCategory,
    companionLevel: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(0) }
    val typingCompleted = visibleCharacters >= HALIBADUO_WORD.length

    LaunchedEffect(Unit) {
        HALIBADUO_WORD.indices.forEach { index ->
            visibleCharacters = index + 1
            delay(STARTUP_CHARACTER_DELAY_MILLIS)
        }
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF143A58),
                        Color(0xFF081825),
                        Color(0xFF03080F)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = Color(0xFF38D7FF).copy(alpha = 0.11f)
            val step = size.minDimension / 9f
            var x = 0f
            while (x <= size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x6638D7FF), Color.Transparent)
                ),
                radius = size.minDimension * 0.34f,
                center = center
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedCompanion(
                category = companionCategory,
                level = companionLevel,
                modifier = Modifier.size(190.dp)
            )

            // 增大伙伴与文字之间的留白：组合保持居中时伙伴略上移、文字略下移，避免形态动画遮挡字母。
            Spacer(modifier = Modifier.height(44.dp))

            // 每个字母使用固定宽度槽位，未打印字符仅隐藏颜色，完整单词不会重新测量、平移或缩放。
            Row(
                modifier = Modifier
                    .width(279.dp)
                    .height(58.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                HALIBADUO_WORD.forEachIndexed { index, character ->
                    Text(
                        modifier = Modifier.width(31.dp),
                        text = character.toString(),
                        color = if (index < visibleCharacters) {
                            Color(0xFF7EE8FF)
                        } else {
                            Color.Transparent
                        },
                        fontSize = 35.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Text(
                modifier = Modifier.padding(top = 10.dp),
                text = if (typingCompleted) {
                    "PERSONAL LIFE SYSTEM · READY"
                } else {
                    "PERSONAL LIFE SYSTEM · INITIALIZING"
                },
                color = Color(0xFF89AFC4),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * 显示App启动密码验证和24小时忘记密码恢复状态。
 *
 * 使用方法：
 * 启动动画结束且仓库仍启用密码锁时由MainActivity调用。验证成功或恢复等待到期后调用
 * [onUnlocked]进入主页面。返回键在锁屏期间被拦截，避免绕过验证返回已创建的业务界面。
 *
 * @param repository 本地密码摘要和恢复申请仓库。
 * @param onUnlocked 密码正确或24小时恢复到期后的回调。
 * @param modifier 外部全屏修饰器。
 * @return 无返回值，直接输出密码锁页面。
 */
@Composable
fun AppUnlockScreen(
    repository: AppLockRepository,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var password by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var showRecoveryDialog by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lockState by remember { mutableStateOf(repository.getState()) }
    val remainingMillis = lockState.recoveryRemainingMillis(nowMillis)

    BackHandler { /* 锁屏期间不允许返回到未验证的业务页面。 */ }

    LaunchedEffect(lockState.recoveryRequestedAtMillis) {
        while (lockState.enabled && lockState.recoveryRequestedAtMillis > 0L) {
            nowMillis = System.currentTimeMillis()
            if (repository.completeRecoveryIfDue(nowMillis)) {
                onUnlocked()
                break
            }
            delay(1_000L)
        }
    }

    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            title = { Text("申请忘记密码解锁") },
            text = {
                Text(
                    "提交后密码锁仍会保持24小时。倒计时结束时App自动清除本地密码锁；" +
                        "等待期间无法提前取消或绕过。清除App数据也会移除本地数据，请不要把它当作找回方式。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (repository.requestRecovery()) {
                            lockState = repository.getState()
                            nowMillis = System.currentTimeMillis()
                            message = "已提交申请，将在24小时后自动解锁"
                        } else {
                            message = "申请保存失败，请重试"
                        }
                        showRecoveryDialog = false
                    }
                ) {
                    Text("确认申请")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    modifier = Modifier.size(92.dp),
                    painter = painterResource(R.drawable.ic_family_launcher_art),
                    contentDescription = "App宠物密码锁"
                )
                Text(
                    text = "halibaduo 已锁定",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "请输入“我的”页面设置的本地密码",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    onValueChange = { password = it.take(32) },
                    label = { Text("App密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = password.isNotBlank(),
                    onClick = {
                        if (repository.verifyPassword(password)) {
                            password = ""
                            onUnlocked()
                        } else {
                            message = "密码不正确"
                        }
                    }
                ) {
                    Text("解锁")
                }

                if (lockState.recoveryRequestedAtMillis > 0L) {
                    Text(
                        text = "忘记密码解锁倒计时：${formatRecoveryDuration(remainingMillis)}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                } else {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showRecoveryDialog = true }
                    ) {
                        Text("忘记密码，申请24小时后解锁")
                    }
                }

                AnimatedVisibility(
                    visible = message.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = message,
                        color = if (message.contains("失败") || message.contains("不正确")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 格式化24小时恢复倒计时。
 *
 * @param remainingMillis 剩余毫秒数。
 * @return HH:mm:ss格式，负值按00:00:00显示。
 */
private fun formatRecoveryDuration(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private const val HALIBADUO_WORD = "halibaduo"
private const val STARTUP_CHARACTER_DELAY_MILLIS = 135L
