package com.example.harleyapp.system

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 查询并引导用户授予定时提醒所需的“闹钟和提醒”特殊权限。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面通过[isGranted]显示当前权限状态；用户主动点击
 * “允许准时提醒”时，使用[createSettingsIntent]打开系统专用授权页。Android 11及以下不需要
 * 该特殊权限，[isGranted]会直接返回true。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ExactAlarmAccessController(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    /**
     * 判断当前系统是否允许本应用创建精确Alarm。
     *
     * @return Android 11及以下返回true；Android 12及以上返回系统实际授权结果。
     */
    fun isGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
    }

    /**
     * 创建打开本应用精确闹钟授权页的Intent。
     *
     * 使用方法：
     * 只能由用户点击按钮后通过ActivityResultLauncher启动。部分定制系统没有独立授权页时，
     * 自动回退到本应用详情页，便于用户继续检查通知与后台权限。
     *
     * @return 可交给ActivityResultLauncher启动的系统设置Intent。
     */
    fun createSettingsIntent(): Intent {
        val packageUri = Uri.parse("package:${applicationContext.packageName}")
        val exactAlarmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }

        return if (exactAlarmIntent.resolveActivity(applicationContext.packageManager) != null) {
            exactAlarmIntent
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
    }
}
