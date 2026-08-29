package com.example.harleyapp.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.example.harleyapp.model.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 查询手机桌面可启动应用，并负责根据包名启动应用。
 *
 * 使用方法：
 * 在协程中调用loadLaunchableApps获取列表；用户点击快捷方式时调用launchApp。
 * 查询范围仅包含声明桌面启动入口的应用，不申请QUERY_ALL_PACKAGES权限。
 *
 * @param context Android上下文，内部使用Application Context。
 */
class InstalledAppsRepository(context: Context) {

    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    /**
     * 在后台线程读取手机上所有具有桌面启动入口的应用。
     *
     * @return 按中文显示名称排序且按包名去重的应用列表；查询失败时返回空列表。
     */
    suspend fun loadLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val collator = Collator.getInstance(Locale.CHINA)

            queryLauncherActivities(launcherIntent)
                .asSequence()
                .filter { it.activityInfo.packageName != applicationContext.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { resolveInfo ->
                    LaunchableApp(
                        packageName = resolveInfo.activityInfo.packageName,
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        icon = resolveInfo.loadIcon(packageManager).toBitmap(
                            width = ICON_SIZE_PX,
                            height = ICON_SIZE_PX,
                            config = Bitmap.Config.ARGB_8888
                        )
                    )
                }
                .sortedWith { first, second -> collator.compare(first.label, second.label) }
                .toList()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to load launchable apps", error)
            emptyList()
        }
    }

    /**
     * 启动指定包名对应的默认桌面入口。
     *
     * @param packageName 用户选中的应用包名。
     *
     * @return 成功向系统发送启动请求返回true；应用已卸载或启动异常时返回false。
     */
    fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false

        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(launchIntent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to launch package: $packageName", error)
            false
        }
    }

    /**
     * 按当前Android版本调用对应的PackageManager查询接口。
     *
     * @param intent 带MAIN和LAUNCHER分类的桌面应用查询Intent。
     *
     * @return 系统允许当前应用查看的桌面入口列表。
     */
    @Suppress("DEPRECATION")
    private fun queryLauncherActivities(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }

    private companion object {
        const val TAG = "InstalledAppsRepo"
        const val ICON_SIZE_PX = 144
    }
}
