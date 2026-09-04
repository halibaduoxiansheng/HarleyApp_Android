package com.example.harleyapp.charging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 在手机重启或HarleyApp升级后恢复用户已经开启的充电监控服务。
 *
 * 使用方法：
 * 由AndroidManifest注册BOOT_COMPLETED与MY_PACKAGE_REPLACED。接收器只读取用户之前保存的开关，
 * 不会在默认关闭状态下自行启用功能；系统或厂商拒绝后台前台服务启动时保留设置，等待用户下次
 * 打开HarleyApp后由控制器再次恢复。
 */
class ChargingMonitorBootReceiver : BroadcastReceiver() {

    /**
     * 处理重启和应用升级完成广播。
     *
     * @param context Android提供的接收器上下文。
     * @param intent 系统广播，其他Action会被忽略。
     * @return 无返回值；启动失败只记录英文日志，不阻塞系统广播线程。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        if (!ChargingEffectPreferences(context).isEnabled()) {
            return
        }
        if (!ChargingMonitorService.start(context)) {
            Log.e(TAG, "Charging monitor could not be restored after system event")
        }
    }

    private companion object {
        const val TAG = "ChargingMonitorBoot"
    }
}
