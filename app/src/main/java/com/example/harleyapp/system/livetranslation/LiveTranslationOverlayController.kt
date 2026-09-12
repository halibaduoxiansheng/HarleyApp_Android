package com.example.harleyapp.system.livetranslation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationSettings
import com.example.harleyapp.model.LiveTranslationSnapshot
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import kotlin.math.roundToInt

/**
 * 管理显示在播放器上方、可拖动且可停止会话的普通应用悬浮字幕。
 *
 * 使用方法：
 * 前台服务在主线程创建实例并调用[show]，每次状态变化调用[update]，停止时调用[remove]。用户从
 * 顶部把手拖动后位置会保存到应用私有偏好；关闭按钮只调用传入回调，由服务统一释放所有资源。
 * 本类不会尝试隐藏系统授权提示或绕过播放器主动禁止的悬浮窗限制。
 *
 * @param context 应用Context。
 * @param settings 当前会话固定的语言及字幕样式。
 * @param onStopRequested 用户点击停止按钮后的回调。
 */
class LiveTranslationOverlayController(
    context: Context,
    private val settings: LiveTranslationSettings,
    private val onStopRequested: () -> Unit
) {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val density = appContext.resources.displayMetrics.density
    private val sourceTextView = createTextView(
        textSizeSp = (settings.textSizeSp * SOURCE_TEXT_SCALE).coerceAtLeast(MIN_SOURCE_TEXT_SIZE_SP),
        textColor = Color.argb(220, 230, 235, 245)
    )
    private val translationTextView = createTextView(
        textSizeSp = settings.textSizeSp.toFloat(),
        textColor = Color.WHITE
    )
    private val statusTextView = createTextView(
        textSizeSp = STATUS_TEXT_SIZE_SP,
        textColor = Color.argb(210, 190, 202, 220)
    )
    private val rootView = buildRootView()
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * 在其他应用上层显示字幕，并恢复上一次拖动位置。
     *
     * @return 无返回值；缺少悬浮窗权限或已经显示时抛出异常/直接返回。
     */
    fun show() {
        if (layoutParams != null) return
        check(Settings.canDrawOverlays(appContext)) { "Overlay permission is not granted" }

        val screen = currentScreenSize()
        val width = minOf(screen.first - dp(32), dp(MAX_OVERLAY_WIDTH_DP)).coerceAtLeast(dp(240))
        val defaultX = ((screen.first - width) / 2).coerceAtLeast(0)
        val defaultY = (screen.second * DEFAULT_VERTICAL_POSITION_RATIO).roundToInt()
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_OVERLAY_X, defaultX).coerceIn(0, (screen.first - width).coerceAtLeast(0))
            y = preferences.getInt(KEY_OVERLAY_Y, defaultY).coerceIn(0, (screen.second - dp(80)).coerceAtLeast(0))
        }
        layoutParams = params
        windowManager.addView(rootView, params)
    }

    /**
     * 用会话最新快照更新电平提示、外语原文和中文译文。
     *
     * @param snapshot 服务发布的完整快照。
     * @return 无返回值；调用线程应为Android主线程。
     */
    fun update(snapshot: LiveTranslationSnapshot) {
        sourceTextView.visibility = if (settings.showSourceText) View.VISIBLE else View.GONE
        sourceTextView.text = snapshot.sourceText
        translationTextView.text = snapshot.translatedText.ifBlank {
            when (snapshot.captureStatus) {
                LiveTranslationCaptureStatus.SILENT -> "暂未检测到播放器声音"
                LiveTranslationCaptureStatus.PROJECTION_REVOKED -> "系统内录授权已结束"
                else -> "正在监听并识别…"
            }
        }
        statusTextView.text = when (snapshot.captureStatus) {
            LiveTranslationCaptureStatus.CAPTURING -> "本地识别中 · ${settings.sourceLanguage.displayName()}→中文"
            LiveTranslationCaptureStatus.SILENT -> "请确认影片正在播放，或播放器是否允许内录"
            LiveTranslationCaptureStatus.PROJECTION_REVOKED -> "请返回HarleyApp重新开始"
            LiveTranslationCaptureStatus.IDLE -> "正在准备本地字幕"
        }
    }

    /**
     * 在播放器横竖屏切换后重新限制浮层宽度和位置，保证拖动把手仍留在可见区域。
     *
     * @return 无返回值；浮层尚未显示时重复调用安全。
     */
    fun ensureVisibleAfterConfigurationChange() {
        val params = layoutParams ?: return
        val screen = currentScreenSize()
        params.width = minOf(screen.first - dp(32), dp(MAX_OVERLAY_WIDTH_DP)).coerceAtLeast(dp(240))
        params.x = params.x.coerceIn(0, (screen.first - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.second - dp(80)).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(rootView, params) }
    }

    /**
     * 从WindowManager移除字幕并清除View引用关系。
     *
     * @return 无返回值；没有显示或系统已移除时重复调用安全。
     */
    fun remove() {
        if (layoutParams == null) return
        runCatching { windowManager.removeViewImmediate(rootView) }
        layoutParams = null
    }

    /** @return 创建字幕根布局、拖动把手、停止按钮以及译文邻近的官方归属徽标。 */
    private fun buildRootView(): View {
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(
                    Color.argb(
                        (settings.backgroundOpacityFraction * 255).roundToInt(),
                        12,
                        17,
                        28
                    )
                )
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }
        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "按住并拖动实时字幕"
        }
        val handle = createTextView(HEADER_TEXT_SIZE_SP, Color.argb(220, 214, 222, 235)).apply {
            text = "⋮⋮  实时翻译"
            setPadding(0, dp(2), dp(8), dp(5))
        }
        val stop = createTextView(HEADER_TEXT_SIZE_SP, Color.WHITE).apply {
            text = "停止"
            gravity = Gravity.CENTER
            contentDescription = "停止实时翻译"
            setPadding(dp(12), dp(4), dp(6), dp(5))
            setOnClickListener { onStopRequested() }
        }
        header.addView(
            handle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(stop)
        installDragHandler(header)

        statusTextView.setPadding(0, 0, 0, dp(5))
        sourceTextView.setPadding(0, dp(2), 0, dp(3))
        translationTextView.setLineSpacing(0f, TRANSLATION_LINE_SPACING_MULTIPLIER)
        content.addView(header)
        content.addView(statusTextView)
        content.addView(sourceTextView)
        content.addView(translationTextView)
        return FrameLayout(appContext).apply { addView(content) }
    }

    /**
     * 让标题把手响应拖动并把最终位置保存在私有偏好。
     *
     * @param dragView 接收触摸事件的标题区域。
     * @return 无返回值。
     */
    private fun installDragHandler(dragView: View) {
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var moved = false
        dragView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    moved = moved ||
                        kotlin.math.abs(event.rawX - downRawX) >= density * 4f ||
                        kotlin.math.abs(event.rawY - downRawY) >= density * 4f
                    val screen = currentScreenSize()
                    params.x = (downWindowX + (event.rawX - downRawX).roundToInt())
                        .coerceIn(0, (screen.first - params.width).coerceAtLeast(0))
                    params.y = (downWindowY + (event.rawY - downRawY).roundToInt())
                        .coerceIn(0, (screen.second - dp(80)).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(rootView, params) }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    preferences.edit()
                        .putInt(KEY_OVERLAY_X, params.x)
                        .putInt(KEY_OVERLAY_Y, params.y)
                        .apply()
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        dragView.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** @return 创建采用sp字号、指定颜色且不自动省略的TextView。 */
    private fun createTextView(textSizeSp: Float, textColor: Int): TextView {
        return TextView(appContext).apply {
            textSize = textSizeSp
            setTextColor(textColor)
            includeFontPadding = false
        }
    }

    /** @return 当前可用屏幕宽高像素，供拖动边界使用。 */
    @Suppress("DEPRECATION")
    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = appContext.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /** @return 把dp转换成当前屏幕的整数像素。 */
    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val PREFERENCES_NAME = "harley_live_translation_overlay"
        const val KEY_OVERLAY_X = "overlay_x"
        const val KEY_OVERLAY_Y = "overlay_y"
        const val MAX_OVERLAY_WIDTH_DP = 520
        const val HEADER_TEXT_SIZE_SP = 13f
        const val STATUS_TEXT_SIZE_SP = 11f
        const val MIN_SOURCE_TEXT_SIZE_SP = 13f
        const val SOURCE_TEXT_SCALE = 0.72f
        const val TRANSLATION_LINE_SPACING_MULTIPLIER = 1.12f
        const val DEFAULT_VERTICAL_POSITION_RATIO = 0.68f
    }
}

/** @return 浮层状态行使用的源语言中文名。 */
private fun LiveTranslationSourceLanguage.displayName(): String {
    return when (this) {
        LiveTranslationSourceLanguage.ENGLISH -> "英语"
        LiveTranslationSourceLanguage.JAPANESE -> "日语"
    }
}
