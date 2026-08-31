package com.example.harleyapp.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.ui.theme.HarleyAppTheme

/**
 * 展示HarleyApp读取Health Connect步数的用途和隐私说明。
 *
 * 使用方法：
 * 不需要业务代码主动启动。用户在系统健康权限页面点击本App的隐私政策后，Android会根据清单中的
 * ACTION_SHOW_PERMISSIONS_RATIONALE或VIEW_PERMISSION_USAGE入口自动打开本页面。
 */
class HealthConnectPermissionsRationaleActivity : ComponentActivity() {

    /**
     * 创建健康权限用途说明页面。
     *
     * 使用方法：
     * 由Android系统创建Activity时自动调用，业务代码不应手动调用。
     *
     * @param savedInstanceState Activity重建时保存的状态，首次打开通常为null。
     *
     * @return 无返回值，直接设置Compose页面内容。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HarleyAppTheme {
                HealthPermissionRationaleContent(
                    onClose = ::finish
                )
            }
        }
    }
}

/**
 * 绘制健康步数权限用途和关闭入口。
 *
 * 使用方法：
 * 由[HealthConnectPermissionsRationaleActivity]在主题内部调用，也可用于Compose预览或后续设置页复用。
 *
 * @param onClose 用户阅读完成后关闭当前说明页的回调。
 *
 * @return 无返回值，直接输出说明页面。
 */
@Composable
private fun HealthPermissionRationaleContent(onClose: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "步数数据使用说明",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Harley生活助手只读取健康数据共享中的每日步数汇总，用于运动页面显示今日步数、目标进度和日期区间汇总。",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "App不会写入或修改系统健康数据，不读取心率、睡眠、位置等其他健康信息，也不会把读取到的步数上传到服务器。你可以随时在系统健康数据共享设置中撤销权限。",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClose
            ) {
                Text(text = "我知道了")
            }
        }
    }
}
