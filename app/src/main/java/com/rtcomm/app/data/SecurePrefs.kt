package com.rtcomm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 自研的加密 SharedPreferences（AES-256-GCM，密钥由 Android Keystore 保管），
 * 用于替代已废弃的 `androidx.security:security-crypto`（F-32）。
 *
 * - 值逐个加密后以 `enc1:` 前缀存入普通 SharedPreferences；**键名保持明文**
 *   （键只是 "token"/"user" 等逻辑名，不含敏感内容，明文便于 [SharedPreferences.getAll] 判断存在性）。
 * - 实现 [SharedPreferences] 接口，原调用点（AuthStore / PrefsCache）零改动。
 * - 仅支持 String 的读写/移除/清空；其它类型未使用（保留接口实现，不做隐式字符串化以免误读）。
 * - Keystore 不可用（密钥失效/损坏）时 [open] 返回 null，调用方沿用「宁可要求重新登录，
 *   也绝不把 JWT 降级写明文」的策略。
 */
object SecurePrefs {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "rtcomm_secure_prefs"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128
    private const val TAG = "SecurePrefs"
    private const val FILE = "rtcomm_secure_v2"

    @Volatile private var cachedKey: SecretKey? = null
    @Volatile private var unavailable = false

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            if (unavailable) return null
            runCatching {
                val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?: run {
                        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                        generator.init(
                            KeyGenParameterSpec.Builder(
                                ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                        generator.generateKey()
                    }
            }.onSuccess { cachedKey = it }
                .onFailure { unavailable = true }
                .getOrNull()
        }
    }

    private fun encrypt(plain: String): String? {
        val secret = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(body, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "偏好值加密失败，本次不写入", it) }.getOrNull()
    }

    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored // 兼容历史明文
        val secret = key() ?: return null
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(':', limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "偏好值解密失败，键将视为缺失", it) }.getOrNull()
    }

    /** 打开加密偏好；Keystore 不可用返回 null（调用方应拒绝明文回退）。 */
    fun open(context: Context): SharedPreferences? {
        if (key() == null) return null
        return EncryptedPrefs(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }

    private class EncryptedPrefs(private val base: SharedPreferences) : SharedPreferences {
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, Any> {
            val out = HashMap<String, Any>()
            base.all.forEach { (k, v) -> decrypt(v as? String)?.let { out[k] = it } }
            return out
        }

        override fun getString(key: String?, defValue: String?): String? =
            if (key == null) defValue else decrypt(base.getString(key, null)) ?: defValue

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = key != null && base.contains(key)
        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.remove(listener)
        }

        private fun notifyChanged(key: String) {
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
        }

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, String?>()
            private var clearing = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            // 未使用的类型：保持接口完整，不做隐式字符串化（避免 getInt 等误读）。
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearing = true
                pending.clear()
                return this
            }

            override fun commit(): Boolean = runCatching {
                val e = base.edit()
                if (clearing) e.clear()
                pending.forEach { (k, v) ->
                    if (v == null) e.remove(k) else encrypt(v)?.let { e.putString(k, it) }
                    notifyChanged(k)
                }
                e.commit()
            }.getOrDefault(false)

            override fun apply() {
                runCatching {
                    val e = base.edit()
                    if (clearing) e.clear()
                    pending.forEach { (k, v) ->
                        if (v == null) e.remove(k) else encrypt(v)?.let { e.putString(k, it) }
                        notifyChanged(k)
                    }
                    e.apply()
                }
            }
        }
    }
}
