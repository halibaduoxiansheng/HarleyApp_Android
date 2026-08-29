package com.example.harleyapp.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.harleyapp.notification.WechatNotificationListenerService

/**
 * 查询并打开Android系统“通知使用权”设置。
 *
 * 使用方法：
 * 使用Application Context创建实例。设置页面先调用isGranted显示当前授权状态，用户点击授权按钮时
 * 调用createSettingsIntent并通过Activity Result启动；返回App后重新调用isGranted刷新状态，并调用
 * requestRebindIfGranted恢复覆盖安装或系统回收后尚未重连的通知监听服务。
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
     * 查询通知监听服务在当前App进程中是否已经实际连接Android通知管理器。
     *
     * @return 服务已收到onListenerConnected回调返回true，仅有授权记录但尚未绑定时返回false。
     */
    fun isConnected(): Boolean {
        return WechatNotificationListenerService.isListenerConnected()
    }

    /**
     * 已获得通知使用权时，请求Android重新绑定本应用通知监听服务。
     *
     * 使用方法：
     * App启动以及从通知使用权设置页返回时调用。该请求不会授予权限，只修复授权记录仍在但系统
     * 尚未重新连接服务的状态；未授权时不会发起任何系统调用。
     *
     * @return 已授权且重绑请求成功提交返回true；未授权或系统拒绝请求时返回false。
     */
    fun requestRebindIfGranted(): Boolean {
        if (!isGranted()) {
            return false
        }

        return try {
            NotificationListenerService.requestRebind(serviceComponent)
            Log.i(TAG, "Notification listener rebind requested")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected notification listener rebind", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to request notification listener rebind", error)
            false
        }
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
