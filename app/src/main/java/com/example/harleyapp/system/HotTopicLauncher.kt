package com.example.harleyapp.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.normalizeHotTopicUrl

/**
 * 把热点的官方HTTPS链接优先交给对应平台App，并提供浏览器回退。
 *
 * 使用方法：
 * 使用Application Context创建启动器。用户点击热点后调用[open]；若微博、百度、知乎或抖音
 * 官方App已安装且声明可以处理该HTTPS链接，Android会直接打开对应App，否则自动使用系统
 * 可处理网页链接的应用。整个过程不使用非公开深链协议，也不会尝试安装或唤醒无关应用。
 *
 * @param context Android上下文，内部转换为Application Context。
 */
class HotTopicLauncher(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * 安全打开一条热点的原平台链接。
     *
     * @param topic 用户点击的热点，链接会在启动前再次执行HTTPS和官方域名校验。
     *
     * @return 官方App或浏览器任一启动请求成功时返回true；链接不合法或没有应用可处理时返回false。
     */
    fun open(topic: HotTopic): Boolean {
        val normalizedUrl = normalizeHotTopicUrl(
            platform = topic.platform,
            rawUrl = topic.url,
            title = topic.title
        ) ?: return false
        val uri = normalizedUrl.toUri()
        val platformIntent = createViewIntent(uri).apply {
            setPackage(topic.platform.preferredPackageName)
        }

        if (startSafely(platformIntent, logFailure = false)) {
            return true
        }

        return startSafely(
            intent = createViewIntent(uri),
            logFailure = true
        )
    }

    /**
     * 创建适用于Application Context的网页查看Intent。
     *
     * @param uri 已通过官方域名校验的HTTPS地址。
     *
     * @return 带新任务标记的ACTION_VIEW Intent。
     */
    private fun createViewIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 捕获应用未安装、链接不受支持及系统拒绝启动等异常。
     *
     * @param intent 待发送的跳转Intent。
     * @param logFailure 是否在最终回退仍失败时记录英文错误日志；首次官方App尝试失败属于正常回退路径。
     *
     * @return Intent成功交给Android系统时返回true，否则返回false。
     */
    private fun startSafely(
        intent: Intent,
        logFailure: Boolean
    ): Boolean {
        return runCatching {
            applicationContext.startActivity(intent)
            true
        }.getOrElse { error ->
            if (logFailure) {
                Log.e(TAG, "No application could open the hot topic URL", error)
            }
            false
        }
    }

    private companion object {
        const val TAG = "HotTopicLauncher"
    }
}
