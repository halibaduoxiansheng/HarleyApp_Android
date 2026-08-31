package com.example.harleyapp.data

import android.content.Context
import com.example.harleyapp.BuildConfig
import java.security.MessageDigest

/**
 * 验证并保存本机开发者模式状态。
 *
 * 使用方法：
 * “我的”页面提交用户输入后调用[verifyAndEnable]；App启动时调用[isEnabled]恢复状态，退出开发者
 * 模式时调用[disable]。源码和SharedPreferences都不保存原始密钥，APK只包含构建阶段注入的
 * SHA-256摘要。开发者偏好文件不在AppBackupManager白名单中，因此换机或重装后必须重新验证。
 *
 * 该模式只控制本机虚拟等级和金币，不承担付费或服务端权限认证。攻击者仍可能修改APK绕过本地
 * 判断；如果将来权限涉及服务器或真实资产，必须改为服务端签发并校验短期令牌。
 *
 * @param context Android上下文，内部只保留Application Context。
 */
class DeveloperModeRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )
    private val configuredHash = BuildConfig.DEVELOPER_KEY_SHA256
        .trim()
        .lowercase()
        .takeIf { hash -> hash.matches(SHA256_HEX_REGEX) }
        .orEmpty()

    /** @return true表示当前APK已经配置有效的开发者密钥摘要。 */
    fun isConfigured(): Boolean = configuredHash.isNotEmpty()

    /**
     * 判断本机是否仍处于已验证开发者模式。
     *
     * @return 已启用且启用时的摘要仍与当前APK配置一致时返回true；更换密钥后自动失效。
     */
    fun isEnabled(): Boolean {
        return isConfigured() &&
            preferences.getBoolean(KEY_ENABLED, false) &&
            preferences.getString(KEY_VERIFIED_HASH, null) == configuredHash
    }

    /**
     * 校验用户输入并在成功后持久开启开发者模式。
     *
     * @param input 用户在密码输入框中提供的原始密钥；只在内存中计算摘要，不写文件和日志。
     * @return 摘要恒定时间比较成功且状态写入成功时返回true。
     */
    fun verifyAndEnable(input: String): Boolean {
        if (!isConfigured() || input.isEmpty()) return false

        val inputHash = MessageDigest.getInstance(SHA256_ALGORITHM)
            .digest(input.toByteArray(Charsets.UTF_8))
        val configuredHashBytes = configuredHash.hexToBytes() ?: return false
        if (!MessageDigest.isEqual(inputHash, configuredHashBytes)) return false

        return preferences.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_VERIFIED_HASH, configuredHash)
            .commit()
    }

    /**
     * 退出开发者模式。
     *
     * @return 本机验证状态成功清除时返回true；伙伴等级和金币不会被修改。
     */
    fun disable(): Boolean {
        return preferences.edit().clear().commit()
    }

    /** @return 64位十六进制字符串对应的32字节数组；格式错误返回null。 */
    private fun String.hexToBytes(): ByteArray? {
        if (!matches(SHA256_HEX_REGEX)) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val PREFERENCE_NAME = "harley_developer_mode"
        const val KEY_ENABLED = "enabled"
        const val KEY_VERIFIED_HASH = "verified_hash"
        const val SHA256_ALGORITHM = "SHA-256"
        val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
    }
}
