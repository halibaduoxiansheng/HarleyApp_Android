package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.DEFAULT_HOME_FEATURE_IDS
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.decodeHomeFeatureIds

/**
 * 保存用户选择在首页“功能中心”展示的功能。
 *
 * 使用方法：
 * 使用Application Context创建仓库。App启动时调用[getSelectedFeatures]恢复选择；用户在首页
 * 管理弹窗确认后调用[saveSelectedFeatures]覆盖保存。功能的固定展示顺序由HomeFeatureId决定，
 * 本仓库只保存是否展示，不会影响完整功能中心中的入口。
 *
 * @param context Android上下文，内部转换为Application Context，避免持有Activity。
 */
class HomeFeatureRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取首页当前选择的功能。
     *
     * 首次安装或从旧版本升级且尚未保存过该配置时，返回首页原有的“今日总览、每日热点、
     * 手机流量”三项。用户主动保存空集合后会继续返回空集合，不会擅自恢复默认值。
     *
     * @return 当前版本可识别的功能集合。
     */
    fun getSelectedFeatures(): Set<HomeFeatureId> {
        if (!preferences.contains(KEY_SELECTED_FEATURES)) {
            return DEFAULT_HOME_FEATURE_IDS
        }

        val storedNames = preferences.getStringSet(KEY_SELECTED_FEATURES, emptySet())
            ?.toSet()
            .orEmpty()
        return decodeHomeFeatureIds(storedNames)
    }

    /**
     * 覆盖保存需要展示在首页的功能。
     *
     * 使用方法：
     * 管理弹窗点击“保存”时传入完整选择集合。允许传入空集合，表示首页只保留管理入口，
     * 用户以后仍可重新添加功能。
     *
     * @param features 用户确认后的完整功能集合。
     *
     * @return 同步写入成功返回true，写入失败返回false。
     */
    fun saveSelectedFeatures(features: Set<HomeFeatureId>): Boolean {
        val success = preferences.edit()
            .putStringSet(
                KEY_SELECTED_FEATURES,
                features.mapTo(linkedSetOf()) { featureId -> featureId.name }
            )
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist home feature selection")
        }

        return success
    }

    private companion object {
        const val TAG = "HomeFeatureRepository"
        const val PREFERENCE_NAME = "harley_home_features"
        const val KEY_SELECTED_FEATURES = "selected_features"
    }
}
