package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.HomeFeatureId
import com.example.harleyapp.model.normalizeFeatureCenterOrder

/**
 * 保存功能中心卡片由用户长按拖动形成的排列顺序。
 *
 * 使用方法：
 * 使用Application Context创建本仓库；App启动时调用[getOrder]恢复顺序，拖动松手后调用
 * [saveOrder]覆盖保存完整列表。本仓库只保存位置，不删除、隐藏或修改任何功能数据。
 *
 * @param context Android上下文，内部转换成Application Context，避免持有Activity。
 */
class FeatureCenterOrderRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取并兼容当前版本全部功能的排列顺序。
     *
     * @return 完整、无重复的功能顺序；首次安装时返回枚举默认顺序。
     */
    fun getOrder(): List<HomeFeatureId> {
        val storedNames = preferences.getString(KEY_FEATURE_ORDER, null)
            ?.split(ORDER_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        return normalizeFeatureCenterOrder(storedNames)
    }

    /**
     * 持久化用户拖动完成后的完整功能顺序。
     *
     * @param order 当前功能中心从左到右、从上到下的完整顺序。
     *
     * @return 同步写入成功返回true；失败时记录英文日志并返回false。
     */
    fun saveOrder(order: List<HomeFeatureId>): Boolean {
        val normalizedOrder = normalizeFeatureCenterOrder(
            order.map(HomeFeatureId::name)
        )
        val success = preferences.edit()
            .putString(
                KEY_FEATURE_ORDER,
                normalizedOrder.joinToString(ORDER_SEPARATOR) { featureId -> featureId.name }
            )
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist feature center order")
        }

        return success
    }

    private companion object {
        const val TAG = "FeatureOrderRepository"
        const val PREFERENCE_NAME = "harley_feature_center"
        const val KEY_FEATURE_ORDER = "feature_order"
        const val ORDER_SEPARATOR = ","
    }
}
