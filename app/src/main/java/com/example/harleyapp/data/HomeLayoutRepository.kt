package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.HomeLayoutPreferences
import com.example.harleyapp.model.HomeSectionId
import com.example.harleyapp.model.decodeHiddenHomeSections
import com.example.harleyapp.model.normalizeHomeLayoutPreferences
import com.example.harleyapp.model.normalizeHomeSectionOrder

/**
 * 保存首页内容模块的显示状态和排列顺序。
 *
 * 使用方法：
 * 使用Application Context创建仓库。App启动时调用[getLayout]恢复布局；用户在“整理首页”对话框
 * 点击保存后调用[saveLayout]一次性覆盖顺序和隐藏集合。首页搜索栏不在本仓库管理范围内，始终显示。
 *
 * @param context Android上下文，内部转换为Application Context，避免持有Activity。
 */
class HomeLayoutRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取并兼容当前版本全部首页模块的布局。
     *
     * @return 完整、无重复的模块顺序和当前有效隐藏集合；首次安装时沿用原首页顺序且全部显示。
     */
    fun getLayout(): HomeLayoutPreferences {
        val storedOrder = preferences.getString(KEY_SECTION_ORDER, null)
            ?.split(ORDER_SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()
        val storedHidden = preferences.getStringSet(KEY_HIDDEN_SECTIONS, emptySet())
            ?.toSet()
            .orEmpty()

        return HomeLayoutPreferences(
            sectionOrder = normalizeHomeSectionOrder(storedOrder),
            hiddenSections = decodeHiddenHomeSections(storedHidden)
        )
    }

    /**
     * 同步保存首页模块的完整顺序和隐藏状态。
     *
     * 使用方法：
     * 对话框确认时传入完整布局。本函数会先补齐遗漏模块并清理无效隐藏项，再通过一次commit保证两项
     * 配置同时成功或同时失败；失败时保留页面当前布局并记录英文日志。
     *
     * @param layout 用户确认后的首页完整布局。
     * @return 同步写入成功返回true，失败返回false。
     */
    fun saveLayout(layout: HomeLayoutPreferences): Boolean {
        val normalizedLayout = normalizeHomeLayoutPreferences(layout)
        val success = preferences.edit()
            .putString(
                KEY_SECTION_ORDER,
                normalizedLayout.sectionOrder.joinToString(ORDER_SEPARATOR) { section ->
                    section.name
                }
            )
            .putStringSet(
                KEY_HIDDEN_SECTIONS,
                normalizedLayout.hiddenSections.mapTo(linkedSetOf(), HomeSectionId::name)
            )
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist home layout")
        }

        return success
    }

    private companion object {
        const val TAG = "HomeLayoutRepository"
        const val PREFERENCE_NAME = "harley_home_layout"
        const val KEY_SECTION_ORDER = "section_order"
        const val KEY_HIDDEN_SECTIONS = "hidden_sections"
        const val ORDER_SEPARATOR = ","
    }
}
