package com.example.harleyapp.scheduled

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.example.harleyapp.data.ScheduledMessageRepository

/**
 * 用户点击到点提醒后，复制文字并打开微信图文分享确认流程。
 *
 * 使用方法：
 * 仅由ScheduledMessageReceiver生成的通知PendingIntent启动。Activity不会自动选择联系人或点击发送；
 * 它把可选图片授予微信临时只读权限，并把文字复制到剪贴板，最后由用户在微信中确认。
 */
class ScheduledShareActivity : Activity() {

    /**
     * 读取计划、准备剪贴板和Android分享Intent，然后打开微信或系统分享界面。
     *
     * @param savedInstanceState Activity重建状态，本Activity不持有需要恢复的界面状态。
     *
     * @return 无返回值；计划不存在时立即关闭当前Activity。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val messageId = intent.getLongExtra(ScheduledMessageScheduler.EXTRA_MESSAGE_ID, 0L)
        val repository = ScheduledMessageRepository(applicationContext)
        val message = repository.getMessage(messageId)
        if (message == null) {
            Log.w(TAG, "Scheduled message not found for share")
            finish()
            return
        }

        if (message.messageText.isNotBlank()) {
            copyTextToClipboard(message.messageText)
        }

        val shareIntent = try {
            createShareIntent(
                messageText = message.messageText,
                imageUriText = message.imageUri
            )
        } catch (error: SecurityException) {
            Log.e(TAG, "Image permission expired before scheduled share", error)
            Toast.makeText(this, "图片读取权限已失效，请回到App重新选择图片", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val opened = openWechatOrChooser(shareIntent)
        if (opened) {
            repository.markShareOpened(message.id, System.currentTimeMillis())
            val toastText = if (message.messageText.isNotBlank()) {
                "文字已复制，请在微信中确认联系人并粘贴发送"
            } else {
                "请在微信中确认联系人后发送图片"
            }
            Toast.makeText(this, toastText, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /**
     * 将预设文字写入系统剪贴板，便于微信忽略EXTRA_TEXT时由用户手动粘贴。
     *
     * @param messageText 预设消息正文。
     *
     * @return 无返回值。
     */
    private fun copyTextToClipboard(messageText: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("微信定时消息", messageText))
    }

    /**
     * 创建支持纯文字、纯图片或图文组合的Android ACTION_SEND Intent。
     *
     * @param messageText 可选消息文字。
     * @param imageUriText 可选图片Content URI文本。
     *
     * @return 已设置MIME类型、EXTRA_TEXT、EXTRA_STREAM和临时只读授权的Intent。
     */
    private fun createShareIntent(messageText: String, imageUriText: String): Intent {
        val imageUri = imageUriText.takeIf(String::isNotBlank)?.let(Uri::parse)
        return Intent(Intent.ACTION_SEND).apply {
            type = imageUri?.let(contentResolver::getType) ?: if (imageUri != null) {
                "image/*"
            } else {
                "text/plain"
            }
            if (messageText.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, messageText)
            }
            if (imageUri != null) {
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = ClipData.newUri(contentResolver, "微信定时图片", imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * 优先打开微信分享界面，微信不可用时回退到系统分享选择器。
     *
     * @param shareIntent 已准备好的ACTION_SEND Intent。
     *
     * @return 成功启动微信或系统选择器返回true，设备没有可处理应用时返回false。
     */
    private fun openWechatOrChooser(shareIntent: Intent): Boolean {
        return try {
            startActivity(Intent(shareIntent).setPackage(WECHAT_PACKAGE_NAME))
            Log.i(TAG, "Opened WeChat share confirmation")
            true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "WeChat share activity unavailable, opening chooser", error)
            try {
                startActivity(Intent.createChooser(shareIntent, "选择分享应用"))
                true
            } catch (chooserError: ActivityNotFoundException) {
                Log.e(TAG, "No application can handle scheduled share", chooserError)
                Toast.makeText(this, "无法打开微信或其他分享应用", Toast.LENGTH_LONG).show()
                false
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected WeChat share intent", error)
            Toast.makeText(this, "系统拒绝打开微信分享，请重新选择图片", Toast.LENGTH_LONG).show()
            false
        }
    }

    private companion object {
        const val TAG = "ScheduledShare"
        const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
    }
}
