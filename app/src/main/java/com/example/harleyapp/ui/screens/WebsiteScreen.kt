package com.example.harleyapp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.harleyapp.model.WebsiteShortcut
import java.util.Locale

/**
 * 在应用内部安全加载用户当前选择的网站，并提供刷新、返回和外部浏览器入口。
 *
 * 使用方法：
 * 由HarleyApp在“网站”导航项选中时调用。离开页面时会主动销毁WebView，
 * 防止Chromium渲染对象长期占用内存。
 *
 * @param website 当前需要加载的网站；用户删除全部网站时传null。
 * @param onManageWebsites 无网站时返回首页添加网站的回调。
 * @param modifier 外部传入的页面安全边距。
 *
 * @return 无返回值，直接输出网站浏览页面。
 */
@Composable
fun WebsiteScreen(
    website: WebsiteShortcut?,
    onManageWebsites: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (website == null) {
        EmptyWebsiteScreen(
            modifier = modifier,
            onManageWebsites = onManageWebsites
        )
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var loadingProgress by remember {
        mutableIntStateOf(0)
    }
    var errorText by remember {
        mutableStateOf<String?>(null)
    }
    var canGoBack by remember {
        mutableStateOf(false)
    }
    val webView = remember(context, website.id, website.url) {
        createWebsiteWebView(
            context = context,
            initialUrl = website.url,
            onProgressChanged = { progress ->
                loadingProgress = progress
            },
            onNavigationStateChanged = { canNavigateBack ->
                canGoBack = canNavigateBack
            },
            onError = { message ->
                errorText = message
            }
        )
    }

    // 优先让系统返回键回到网页历史记录，而不是直接退出当前App。
    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    // WebView包含独立渲染进程资源，页面离开时必须显式停止并销毁。
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    enabled = canGoBack,
                    onClick = {
                        webView.goBack()
                    }
                ) {
                    Text(text = "← 返回")
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = website.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = website.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = {
                        errorText = null
                        webView.reload()
                    }
                ) {
                    Text(text = "刷新")
                }

                TextButton(
                    onClick = {
                        openExternalUrl(context, website.url)
                    }
                ) {
                    Text(text = "浏览器")
                }
            }
        }

        if (loadingProgress in 0..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    webView
                }
            )

            errorText?.let { message ->
                WebsiteErrorView(
                    message = message,
                    onRetry = {
                        errorText = null
                        webView.loadUrl(website.url)
                    },
                    onOpenBrowser = {
                        openExternalUrl(context, website.url)
                    }
                )
            }

            if (loadingProgress == 0 && errorText == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/**
 * 用户删除全部网站后显示空状态，并引导回首页添加。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param onManageWebsites 返回首页网站管理区域的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyWebsiteScreen(
    modifier: Modifier,
    onManageWebsites: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "还没有可访问的网站",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                text = "返回首页添加网站并选择卡片颜色。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onManageWebsites) {
                Text(text = "返回首页添加")
            }
        }
    }
}

/**
 * 创建并配置只用于HTTPS网页浏览的WebView。
 *
 * @param context 页面上下文，用于创建WebView和打开外部协议。
 * @param initialUrl 当前网站的完整HTTPS网址。
 * @param onProgressChanged 页面加载进度变化回调，范围为0到100。
 * @param onNavigationStateChanged 是否可以网页后退的状态回调。
 * @param onError 主页面加载失败时的中文错误提示回调；传null表示清除旧错误。
 *
 * @return 已完成安全设置并开始加载网站的WebView实例。
 */
private fun createWebsiteWebView(
    context: Context,
    initialUrl: String,
    onProgressChanged: (Int) -> Unit,
    onNavigationStateChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit
): WebView {
    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
        }

        webViewClient = HarleyWebViewClient(
            context = context,
            onPageStartedCallback = {
                onError(null)
            },
            onPageFinishedCallback = {
                onNavigationStateChanged(canGoBack())
            },
            onMainFrameErrorCallback = { message ->
                onError(message)
            }
        )
        webChromeClient = HarleyWebChromeClient(onProgressChanged)
        loadUrl(initialUrl)
    }
}

/**
 * 显示网页主文档加载失败后的可恢复提示。
 *
 * @param message 具体错误说明。
 * @param onRetry 重新加载网站回调。
 * @param onOpenBrowser 使用系统浏览器打开回调。
 *
 * @return 无返回值。
 */
@Composable
private fun WebsiteErrorView(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "网页暂时无法打开",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                text = message.ifBlank { "请检查网络连接或稍后重试。" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRetry) {
                    Text(text = "重新加载")
                }
                TextButton(onClick = onOpenBrowser) {
                    Text(text = "使用浏览器")
                }
            }
        }
    }
}

/**
 * 使用系统默认浏览器或支持该链接的应用打开网址。
 *
 * @param context 用于启动Activity的上下文。
 * @param url 完整HTTPS网址。
 *
 * @return 成功发送启动请求返回true；设备没有可用浏览器时返回false。
 */
private fun openExternalUrl(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.e(TAG, "No application can open the website", error)
        false
    }
}

/**
 * 处理WebView页面导航和主文档错误。
 *
 * @param context 用于打开非HTTP协议的上下文。
 * @param onPageStartedCallback 主页面开始加载回调。
 * @param onPageFinishedCallback 主页面加载完成回调。
 * @param onMainFrameErrorCallback 主页面失败回调。
 */
private class HarleyWebViewClient(
    private val context: Context,
    private val onPageStartedCallback: () -> Unit,
    private val onPageFinishedCallback: () -> Unit,
    private val onMainFrameErrorCallback: (String) -> Unit
) : WebViewClient() {

    /**
     * 主页面开始加载时清除旧错误。
     *
     * @param view 当前WebView。
     * @param url 正在加载的网址。
     * @param favicon 网站图标，可能为null。
     *
     * @return 无返回值。
     */
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStartedCallback()
    }

    /**
     * 页面加载结束后更新网页是否可以后退。
     *
     * @param view 当前WebView。
     * @param url 已完成加载的网址。
     *
     * @return 无返回值。
     */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback()
    }

    /**
     * 仅处理主文档加载错误，忽略图片等子资源错误，避免整页被错误遮挡。
     *
     * @param view 当前WebView。
     * @param request 失败的资源请求。
     * @param error 系统返回的网页错误。
     *
     * @return 无返回值。
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)

        if (request?.isForMainFrame == true) {
            val description = error?.description?.toString().orEmpty()
            Log.e(TAG, "Main frame failed to load: $description")
            onMainFrameErrorCallback(
                if (description.isBlank()) "请检查网络连接或域名是否可以访问。" else description
            )
        }
    }

    /**
     * HTTPS链接继续在内置WebView打开，电话等其他协议交给系统应用处理。
     *
     * @param view 当前WebView。
     * @param request 即将跳转的网页请求。
     *
     * @return 返回false表示WebView自行加载；返回true表示已交给外部应用。
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        if (scheme == "http" || scheme == "https") {
            return false
        }

        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to open external URL scheme", error)
            true
        }
    }
}

/**
 * 把WebView加载进度转发给Compose状态。
 *
 * @param onProgressChangedCallback 进度变化回调，范围为0到100。
 */
private class HarleyWebChromeClient(
    private val onProgressChangedCallback: (Int) -> Unit
) : WebChromeClient() {

    /**
     * 接收Chromium页面加载进度。
     *
     * @param view 当前WebView。
     * @param newProgress 新进度，范围为0到100。
     *
     * @return 无返回值。
     */
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress.coerceIn(0, 100))
    }
}

private const val TAG = "WebsiteScreen"
