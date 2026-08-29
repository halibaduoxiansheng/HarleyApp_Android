package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.UserGender
import com.example.harleyapp.model.UserProfile
import org.json.JSONObject

/**
 * 管理用户基础资料的本地仓库。
 *
 * 使用方法：
 * 使用Application Context创建实例；进入“我的”页面时调用[getProfile]查询，用户确认编辑后调用
 * [saveProfile]新增或更新，二次确认后调用[deleteProfile]清除。所有字段只保存在应用私有Room兼容
 * 存储中，不访问网络；头像仅保存系统文档Uri文本，实际图片仍由系统授权控制。
 *
 * @param context Android上下文，用于打开本应用私有存储。
 */
class UserProfileRepository(context: Context) {

    private val preferences = RoomBackedPreferences.create(
        context = context,
        preferenceName = PREFERENCE_NAME
    )

    /**
     * 查询当前用户资料。
     *
     * @return 已保存且可以解析的资料；尚未创建或本地内容损坏时返回null。
     */
    fun getProfile(): UserProfile? {
        val rawProfile = preferences.getString(KEY_PROFILE, null) ?: return null
        return runCatching {
            val json = JSONObject(rawProfile)
            sanitizeProfile(
                UserProfile(
                    avatarUri = json.optString(JSON_AVATAR_URI),
                    name = json.optString(JSON_NAME),
                    gender = runCatching {
                        UserGender.valueOf(
                            json.optString(JSON_GENDER, UserGender.UNSPECIFIED.name)
                        )
                    }.getOrDefault(UserGender.UNSPECIFIED),
                    ageYears = json.optInt(JSON_AGE_YEARS, 0),
                    heightCm = json.optInt(JSON_HEIGHT_CM, 0),
                    weightKg = json.optDouble(JSON_WEIGHT_KG, 0.0)
                )
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to parse user profile", error)
        }.getOrNull()
    }

    /**
     * 新增或更新完整用户资料。
     *
     * 使用方法：
     * 编辑弹窗先完成中文校验，再把输入值组成[UserProfile]传入。仓库会再次裁剪文本和限制数值，
     * 防止异常状态写入；同一个本地键始终覆盖为最新资料，因此再次修改不会产生重复用户。
     *
     * @param profile 页面提交的完整资料。
     *
     * @return 保存成功时返回经过清理的资料；字段无效或写入失败时返回null。
     */
    @Synchronized
    fun saveProfile(profile: UserProfile): UserProfile? {
        val safeProfile = sanitizeProfile(profile) ?: return null
        val json = JSONObject()
            .put(JSON_AVATAR_URI, safeProfile.avatarUri)
            .put(JSON_NAME, safeProfile.name)
            .put(JSON_GENDER, safeProfile.gender.name)
            .put(JSON_AGE_YEARS, safeProfile.ageYears)
            .put(JSON_HEIGHT_CM, safeProfile.heightCm)
            .put(JSON_WEIGHT_KG, safeProfile.weightKg)
        val success = preferences.edit()
            .putString(KEY_PROFILE, json.toString())
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist user profile")
            return null
        }
        return safeProfile
    }

    /**
     * 删除当前用户资料。
     *
     * 使用方法：
     * 页面必须先弹出二次确认；删除后健康建议消失，但已经同步到运动功能的项目和历史运动记录
     * 会继续保留，避免删除个人资料时连带破坏独立的运动数据。
     *
     * @return 当前没有资料或删除成功时返回true，写入失败时返回false。
     */
    @Synchronized
    fun deleteProfile(): Boolean {
        if (!preferences.contains(KEY_PROFILE)) {
            return true
        }
        val success = preferences.edit().remove(KEY_PROFILE).commit()
        if (!success) {
            Log.e(TAG, "Failed to delete user profile")
        }
        return success
    }

    /**
     * 清理用户资料字段并执行最终范围校验。
     *
     * @param profile 原始资料。
     *
     * @return 可以保存的资料；任一必填字段不符合范围时返回null。
     */
    private fun sanitizeProfile(profile: UserProfile): UserProfile? {
        val safeProfile = profile.copy(
            avatarUri = profile.avatarUri.trim().take(MAX_AVATAR_URI_LENGTH),
            name = profile.name.trim().take(MAX_NAME_LENGTH)
        )
        return safeProfile.takeIf(UserProfile::isReadyForHealthAdvice)
    }

    private companion object {
        const val TAG = "UserProfileRepository"
        const val PREFERENCE_NAME = "harley_user_profile"
        const val KEY_PROFILE = "profile_v1"
        const val JSON_AVATAR_URI = "avatar_uri"
        const val JSON_NAME = "name"
        const val JSON_GENDER = "gender"
        const val JSON_AGE_YEARS = "age_years"
        const val JSON_HEIGHT_CM = "height_cm"
        const val JSON_WEIGHT_KG = "weight_kg"
        const val MAX_NAME_LENGTH = 30
        const val MAX_AVATAR_URI_LENGTH = 2_048
    }
}
