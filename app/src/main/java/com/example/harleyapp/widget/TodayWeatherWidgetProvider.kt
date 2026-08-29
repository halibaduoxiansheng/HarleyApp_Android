package com.example.harleyapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.weatherDescription
import com.example.harleyapp.weather.WeatherRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 在系统桌面显示已缓存天气和本机今日摘要的小组件。
 *
 * 使用方法：
 * 用户从系统桌面的小组件选择器添加“Harley今日天气”。小组件只读取App已经保存的天气缓存、
 * 账目、提醒和运动记录，不在后台申请位置或访问网络；点击整个组件会打开Harley App。
 */
class TodayWeatherWidgetProvider : AppWidgetProvider() {

    /**
     * 系统创建或要求刷新小组件时渲染全部实例。
     *
     * @param context 广播上下文。
     * @param appWidgetManager 系统小组件管理器。
     * @param appWidgetIds 本次需要更新的组件编号。
     */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        /**
         * 在App内数据或天气更新后刷新桌面上的全部组件。
         *
         * @param context Android上下文。
         * @return 无返回值；尚未添加小组件时安全跳过。
         */
        fun updateAll(context: Context) {
            val applicationContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(applicationContext)
            val component = ComponentName(
                applicationContext,
                TodayWeatherWidgetProvider::class.java
            )
            manager.getAppWidgetIds(component).forEach { appWidgetId ->
                updateWidget(applicationContext, manager, appWidgetId)
            }
        }

        /**
         * 读取本机缓存并渲染一个RemoteViews实例。
         *
         * @param context Android上下文。
         * @param manager 系统小组件管理器。
         * @param appWidgetId 目标组件编号。
         */
        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val todayEpochDay = LocalDate.now().toEpochDay()
            val weather = WeatherRepository(context).getCached()
            val ledgerEntries = LedgerRepository(context).getEntries()
                .filter { entry -> entry.dateEpochDay == todayEpochDay }
            val reminderCount = ReminderRepository(context).getReminders().count { reminder ->
                Instant.ofEpochMilli(reminder.nextTriggerAtMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate() == LocalDate.now()
            }
            val fitness = FitnessRepository(context).getTodayRecord(todayEpochDay)
            val activeFitnessCount = fitness.items.count { item -> item.count > 0 }
            val expenseCents = ledgerEntries
                .filter { entry -> entry.type == LedgerType.EXPENSE }
                .sumOf { entry -> entry.amountCents }

            val views = RemoteViews(context.packageName, R.layout.widget_today_weather)
            if (weather == null) {
                views.setTextViewText(R.id.widget_weather, "打开App查询天气")
                views.setTextViewText(R.id.widget_weather_detail, "需要前台粗略位置权限")
                views.setTextViewText(R.id.widget_updated_at, "不会在后台定位")
            } else {
                views.setTextViewText(
                    R.id.widget_weather,
                    "${weather.temperatureCelsius.roundToInt()}° " +
                        weatherDescription(weather.weatherCode)
                )
                views.setTextViewText(
                    R.id.widget_weather_detail,
                    "最高${weather.maxTemperatureCelsius.roundToInt()}° / " +
                        "最低${weather.minTemperatureCelsius.roundToInt()}° · " +
                        "降水${weather.precipitationProbabilityPercent}%"
                )
                views.setTextViewText(
                    R.id.widget_updated_at,
                    "天气更新 ${formatWidgetTime(weather.fetchedAtMillis)}"
                )
            }
            views.setTextViewText(
                R.id.widget_today_summary,
                "账目${ledgerEntries.size}条 · 支出¥${expenseCents / 100} · " +
                    "提醒${reminderCount}项 · 运动${activeFitnessCount}项"
            )

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TODAY, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }

        /** @return 小组件使用的天气缓存更新时间。 */
        private fun formatWidgetTime(timestamp: Long): String {
            return Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(WIDGET_TIME_FORMATTER)
        }

        const val EXTRA_OPEN_TODAY = "open_today_overview"
        private val WIDGET_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    }
}
