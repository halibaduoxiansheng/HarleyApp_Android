package com.example.harleyapp.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.harleyapp.notification.WechatNotificationListenerService

/**
 * 查询并打开Android系统“通知使用权”设置。
 *
 * 使用方法：
 * 使用Application Context创建实例。设置页面先调用isGranted显示当前授权状态，用户点击授权按钮时
 * 调用createSettingsIntent并通过Activity Result启动；返回App后重新调用isGranted刷新状态。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class NotificationAccessController(context: Context) {

    private val applicationContext = context.applicationContext
    private val serviceComponent = ComponentName(
        applicationContext,
        WechatNotificationListenerService::class.java
    )

    /**
     * 判断本应用的微信通知监听服务是否已经获得通知使用权。
     *
     * @return 系统授权列表包含当前服务返回true，否则返回false。
     */
    fun isGranted(): Boolean {
        val enabledComponents = Settings.Secure.getString(
            applicationContext.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS
        ).orEmpty()

        return enabledComponents
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { component -> component == serviceComponent }
    }

    /**
     * 创建通知使用权设置Intent，并优先定位到当前服务详情。
     *
     * @return 可交给Activity Result启动的系统设置Intent；若详情页不可用则回退到服务列表页。
     */
    fun createSettingsIntent(): Intent {
        val detailIntent = Intent(ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, serviceComponent.flattenToString())
        if (detailIntent.resolveActivity(applicationContext.packageManager) != null) {
            return detailIntent
        }

        Log.i(TAG, "Notification listener detail settings unavailable, using list settings")
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    private companion object {
        const val TAG = "NotificationAccess"
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        const val ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS =
            "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
        const val EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME =
            "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
    }
}
