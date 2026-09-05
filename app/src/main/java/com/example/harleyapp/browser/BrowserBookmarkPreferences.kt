package com.example.harleyapp.browser

import android.annotation.SuppressLint
import android.content.Context

/**
 * 保存浏览器收藏悬浮球的用户开关和最后停靠位置。
 *
 * 使用方法：
 * 设置页通过[isEnabled]与[setEnabled]管理总开关；无障碍服务显示悬浮球前再次读取开关，拖动
 * 结束后通过[saveBallPosition]保存相对坐标。首次安装默认关闭，必须由用户主动同意说明并授权。
 *
 * @param context Android上下文，内部只保留Application Context对应的SharedPreferences。
 */
class BrowserBookmarkPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 查询用户是否主动启用了浏览器收藏悬浮球。
     *
     * @return 已启用返回true；首次安装、保存失败或主动关闭后返回false。
     */
    fun isEnabled(): Boolean {
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    /**
     * 同步保存浏览器收藏悬浮球总开关。
     *
     * @param enabled true表示允许无障碍服务在浏览器上显示悬浮球，false表示立即隐藏。
     * @return 数据成功落盘返回true，否则返回false。
     */
    @SuppressLint("UseKtx")
    fun setEnabled(enabled: Boolean): Boolean {
        return preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    /**
     * 读取上次拖动结束后的屏幕相对位置。
     *
     * @return 横纵坐标均限制在0到1；没有历史记录时默认停靠屏幕右侧中部。
     */
    fun getBallPosition(): BrowserBookmarkBallPosition {
        return BrowserBookmarkBallPosition(
            xFraction = preferences.getFloat(KEY_BALL_X_FRACTION, DEFAULT_BALL_X_FRACTION)
                .coerceIn(0f, 1f),
            yFraction = preferences.getFloat(KEY_BALL_Y_FRACTION, DEFAULT_BALL_Y_FRACTION)
                .coerceIn(0f, 1f)
        )
    }

    /**
     * 保存悬浮球贴边后的屏幕相对位置，使不同分辨率和横竖屏之间能够安全恢复。
     *
     * @param xFraction 横向相对位置，0表示左侧，1表示右侧。
     * @param yFraction 纵向相对位置，0表示顶部，1表示底部。
     * @return 坐标成功落盘返回true，否则返回false。
     */
    @SuppressLint("UseKtx")
    fun saveBallPosition(xFraction: Float, yFraction: Float): Boolean {
        return preferences.edit()
            .putFloat(KEY_BALL_X_FRACTION, xFraction.coerceIn(0f, 1f))
            .putFloat(KEY_BALL_Y_FRACTION, yFraction.coerceIn(0f, 1f))
            .commit()
    }

    private companion object {
        const val PREFERENCE_NAME = "browser_bookmark_ball"
        const val KEY_ENABLED = "enabled"
        const val KEY_BALL_X_FRACTION = "ball_x_fraction"
        const val KEY_BALL_Y_FRACTION = "ball_y_fraction"
        const val DEFAULT_BALL_X_FRACTION = 1f
        const val DEFAULT_BALL_Y_FRACTION = 0.48f
    }
}

/**
 * 表示悬浮球在当前屏幕可移动区域内的相对位置。
 *
 * @param xFraction 横向相对位置，取值0到1。
 * @param yFraction 纵向相对位置，取值0到1。
 */
data class BrowserBookmarkBallPosition(
    val xFraction: Float,
    val yFraction: Float
)
