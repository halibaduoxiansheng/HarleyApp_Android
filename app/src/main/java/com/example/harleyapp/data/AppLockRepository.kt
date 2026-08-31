package com.example.harleyapp.data

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 忘记密码申请后的固定等待时间：24小时。 */
const val APP_LOCK_RECOVERY_WAIT_MILLIS = 24L * 60L * 60L * 1_000L

/**
 * App本地密码锁的当前状态。
 *
 * @param enabled 是否需要在启动动画后验证密码。
 * @param recoveryRequestedAtMillis 忘记密码申请时间；未申请时为0。
 */
data class AppLockState(
    val enabled: Boolean = false,
    val recoveryRequestedAtMillis: Long = 0L
) {

    /**
     * 计算忘记密码申请距离自动解锁还剩多久。
     *
     * @param nowMillis 当前系统时间。
     * @return 剩余毫秒数；未申请或已经到期时返回0。
     */
    fun recoveryRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        if (recoveryRequestedAtMillis <= 0L) return 0L
        return (recoveryRequestedAtMillis + APP_LOCK_RECOVERY_WAIT_MILLIS - nowMillis)
            .coerceAtLeast(0L)
    }
}

/**
 * 保存、验证和恢复App本地密码锁。
 *
 * 使用方法：
 * MainActivity启动时读取[getState]决定是否显示锁屏；“我的”页面使用[enable]、[changePassword]
 * 和[disable]管理密码。忘记密码时调用[requestRecovery]开始24小时等待，锁屏每秒检查
 * [completeRecoveryIfDue]，到期后自动清除密码锁。密码只以随机盐PBKDF2摘要保存，不保存明文。
 *
 * 注意：这是个人隐私防误触功能，不是系统级加密保险箱；清除App数据或卸载仍会移除本地密码。
 *
 * @param context Android上下文，内部只保存Application Context。
 */
class AppLockRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /** @return 当前是否启用密码锁以及恢复申请时间。 */
    fun getState(): AppLockState {
        return AppLockState(
            enabled = preferences.getBoolean(KEY_ENABLED, false) && hasPasswordMaterial(),
            recoveryRequestedAtMillis = preferences.getLong(KEY_RECOVERY_REQUESTED_AT, 0L)
        )
    }

    /**
     * 首次启用密码锁。
     *
     * @param password 用户输入的新密码，长度必须为4至32个字符。
     * @return 保存成功返回true；密码格式不合法或磁盘写入失败返回false。
     */
    fun enable(password: String): Boolean {
        if (!isValidPassword(password)) return false
        return savePassword(password, enabled = true)
    }

    /**
     * 验证当前密码。
     *
     * @param password 用户输入的候选密码。
     * @return PBKDF2摘要恒定时间比较通过返回true。
     */
    fun verifyPassword(password: String): Boolean {
        val salt = decode(KEY_SALT) ?: return false
        val expectedHash = decode(KEY_PASSWORD_HASH) ?: return false
        val actualHash = derivePasswordHash(password, salt)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    /**
     * 修改密码并取消未完成的忘记密码申请。
     *
     * @param currentPassword 当前密码。
     * @param newPassword 新密码。
     * @return 当前密码正确、新密码合法且保存成功返回true。
     */
    fun changePassword(currentPassword: String, newPassword: String): Boolean {
        if (!verifyPassword(currentPassword) || !isValidPassword(newPassword)) return false
        return savePassword(newPassword, enabled = true)
    }

    /**
     * 使用当前密码关闭密码锁。
     *
     * @param currentPassword 当前密码。
     * @return 验证和清理全部密码材料成功返回true。
     */
    fun disable(currentPassword: String): Boolean {
        if (!verifyPassword(currentPassword)) return false
        return clearLock()
    }

    /**
     * 创建忘记密码申请。
     *
     * @param nowMillis 申请时间，默认使用当前系统时间。
     * @return 密码锁已启用且时间保存成功返回true；重复申请保留第一次时间并返回true。
     */
    fun requestRecovery(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!getState().enabled) return false
        if (preferences.getLong(KEY_RECOVERY_REQUESTED_AT, 0L) > 0L) return true
        return preferences.edit()
            .putLong(KEY_RECOVERY_REQUESTED_AT, nowMillis.coerceAtLeast(1L))
            .commit()
    }

    /**
     * 到达24小时后自动关闭密码锁。
     *
     * @param nowMillis 当前系统时间。
     * @return 已到期并成功解锁返回true；尚未到期或没有申请返回false。
     */
    fun completeRecoveryIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val state = getState()
        if (!state.enabled || state.recoveryRequestedAtMillis <= 0L) return false
        if (state.recoveryRemainingMillis(nowMillis) > 0L) return false
        return clearLock()
    }

    /** @return 4至32字符且不含首尾空格时返回true。 */
    fun isValidPassword(password: String): Boolean {
        return password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
            password == password.trim()
    }

    /** 保存随机盐和PBKDF2摘要。 */
    private fun savePassword(password: String, enabled: Boolean): Boolean {
        return runCatching {
            val salt = ByteArray(PASSWORD_SALT_BYTES).also(SecureRandom()::nextBytes)
            val hash = derivePasswordHash(password, salt)
            preferences.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_PASSWORD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                .remove(KEY_RECOVERY_REQUESTED_AT)
                .commit()
        }.onFailure { error ->
            Log.e(TAG, "Failed to save app lock password", error)
        }.getOrDefault(false)
    }

    /** @return 删除启用状态、摘要、随机盐和恢复申请是否成功。 */
    private fun clearLock(): Boolean {
        return preferences.edit()
            .remove(KEY_ENABLED)
            .remove(KEY_SALT)
            .remove(KEY_PASSWORD_HASH)
            .remove(KEY_RECOVERY_REQUESTED_AT)
            .commit()
    }

    /** @return 随机盐和密码摘要都存在时返回true。 */
    private fun hasPasswordMaterial(): Boolean {
        return !preferences.getString(KEY_SALT, null).isNullOrBlank() &&
            !preferences.getString(KEY_PASSWORD_HASH, null).isNullOrBlank()
    }

    /** 解码SharedPreferences中的Base64字段。 */
    private fun decode(key: String): ByteArray? {
        return runCatching {
            preferences.getString(key, null)
                ?.let { value -> Base64.decode(value, Base64.NO_WRAP) }
        }.getOrNull()
    }

    /** 使用PBKDF2-HMAC-SHA256生成固定长度密码摘要。 */
    private fun derivePasswordHash(password: String, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PASSWORD_HASH_BITS
        )
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }

    private companion object {
        const val TAG = "AppLockRepository"
        const val PREFERENCE_NAME = "harley_app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_SALT = "salt"
        const val KEY_PASSWORD_HASH = "password_hash"
        const val KEY_RECOVERY_REQUESTED_AT = "recovery_requested_at"
        const val MIN_PASSWORD_LENGTH = 4
        const val MAX_PASSWORD_LENGTH = 32
        const val PASSWORD_SALT_BYTES = 16
        const val PASSWORD_HASH_BITS = 256
        const val PBKDF2_ITERATIONS = 120_000
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}
