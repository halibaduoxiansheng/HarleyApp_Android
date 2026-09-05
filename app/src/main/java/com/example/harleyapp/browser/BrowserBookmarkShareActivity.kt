package com.example.harleyapp.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.example.harleyapp.data.WebsiteRepository
import com.example.harleyapp.model.WebsiteBookmarkSaveResult
import com.example.harleyapp.model.resolveSharedBrowserPage

/**
 * 接收浏览器通过Android系统分享面板发送的完整网页地址，并写入现有网站收藏。
 *
 * 使用方法：
 * 浏览器中选择“分享”，再选择“收藏到HarleyApp”。Edge的一键悬浮球也会在用户主动点击后
 * 通过同一条系统分享链路进入本Activity。页面不显示业务界面，保存完成后立即结束并返回原
 * 浏览器；只处理[text/plain]中的HTTP或HTTPS地址，不接收文件、图片或其他协议。
 *
 * @return 本类由Android根据ACTION_SEND Intent创建，业务代码不应直接实例化。
 */
class BrowserBookmarkShareActivity : Activity() {

    /**
     * 处理本次网页分享、显示本地保存结果并立即返回来源浏览器。
     *
     * @param savedInstanceState Android恢复Activity时提供的状态；本页面不保存独立界面状态。
     * @return 无返回值；无论分享是否有效都会结束Activity，避免留在最近任务列表。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedBookmark(intent)
        finish()
    }

    /**
     * 校验系统分享Intent，并把合法网页交给统一收藏仓库保存。
     *
     * @param sourceIntent 浏览器或系统分享面板交付的ACTION_SEND Intent。
     * @return 无返回值；保存结果通过中文Toast反馈，日志不记录标题或网址内容。
     */
    private fun handleSharedBookmark(sourceIntent: Intent?) {
        if (sourceIntent?.action != Intent.ACTION_SEND) {
            showResultMessage("没有收到可收藏的网页地址")
            return
        }

        val sharedText = sourceIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            .orEmpty()
        val sharedTitle = sourceIntent.getCharSequenceExtra(Intent.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
            .ifBlank {
                sourceIntent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            }
        val page = resolveSharedBrowserPage(
            sharedText = sharedText,
            sharedTitle = sharedTitle
        )
        if (page == null) {
            Log.w(TAG, "Rejected browser share without a valid web URL")
            showResultMessage("分享内容里没有可收藏的网页地址")
            return
        }

        val result = WebsiteRepository(applicationContext).saveBookmarkedPage(
            pageTitle = page.title,
            pageUrl = page.url
        )
        when (result) {
            WebsiteBookmarkSaveResult.SAVED ->
                showResultMessage("已收藏到HarleyApp网站收藏")
            WebsiteBookmarkSaveResult.ALREADY_SAVED ->
                showResultMessage("当前网页已经收藏")
            WebsiteBookmarkSaveResult.INVALID_URL ->
                showResultMessage("当前分享不是可收藏的网页地址")
            WebsiteBookmarkSaveResult.SAVE_FAILED -> {
                Log.e(TAG, "Failed to save browser bookmark from share intent")
                showResultMessage("收藏保存失败，请稍后重试")
            }
        }
    }

    /**
     * 用短时Toast向用户反馈分享收藏结果。
     *
     * @param message 面向用户的中文结果说明。
     * @return 无返回值。
     */
    private fun showResultMessage(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 日志标签；日志内容只描述流程状态，不包含用户分享的网址或标题。 */
        private const val TAG = "BrowserBookmarkShare"
    }
}
