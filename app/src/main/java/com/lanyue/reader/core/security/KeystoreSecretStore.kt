package com.lanyue.reader.core.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 4.10.1 / 4.12 Keystore 加密存储。
 *
 * 适用范围：danmaku_sources.appSecret、webdav_credentials.password。
 * 备份导出默认脱敏：不含 secret，恢复时提示重填（ADR-017）。
 */
class KeystoreSecretStore(context: Context) {

    private val prefs = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "lanyue_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    fun put(key: String, secret: String) {
        prefs?.edit()?.putString(key, secret)?.apply()
    }

    fun get(key: String): String? = prefs?.getString(key, null)

    fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    /**
     * 备份导出默认脱敏：调用方必须使用此方法而不是 raw secret。
     * 返回 null 表示不应写入备份文件。
     */
    fun forBackup(key: String): String? = null
}
