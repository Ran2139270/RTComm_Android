package com.rtcomm.app.data

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
 * 本地缓存字段级加密（AES-256-GCM，密钥由 Android Keystore 保管）。
 *
 * 背景：Room 里的消息正文、发送者/文件/引用 JSON 与会话名属于私密数据，
 * 仅靠应用沙箱在 root/取证/备份镜像场景下保护不足。这里对敏感字段逐一加密，
 * 排序用的时间戳与主键保持明文（否则无法索引与排序）。
 *
 * - 仅当前缀为 [PREFIX_V1]/[PREFIX_V2] 时执行解密；历史明文行原样读取，写入时一律加密。
 * - Keystore 不可用时返回 null：调用方宁可丢缓存也不写明文。
 *
 * GCM 默认不校验密文「属于哪一行/哪一列」，攻击者拿到数据库文件后可以把某条消息的
 * 密文搬到另一条消息的 content 列，解密照样成功（字段混淆）。因此提供 [encrypt] /
 * [decrypt] 的可选 `aad`：调用方传入「行标识 + 列名」作为附加认证数据，密文就被绑定
 * 到具体位置。新写入使用 [PREFIX_V2]（必须带 AAD，缺失即失败，避免被降级绕过）；
 * 历史 [PREFIX_V1] 密文仍可读，下次写入时自动升级为 v2。
 */
object AtRestCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "rtcomm_db_at_rest"
    private const val PREFIX_V1 = "enc1:" // 旧版：无 AAD
    private const val PREFIX_V2 = "enc2:" // 新版：AAD 绑定
    private const val GCM_TAG_BITS = 128
    private const val TAG = "AtRestCrypto"

    @Volatile private var unavailable = false

    /**
     * 已解析的密钥缓存。
     *
     * 之前每次 encrypt/decrypt 都要 `KeyStore.load(null)` + `getEntry`，一次冷启动
     * 解密一屏历史（每条消息多个加密字段）会触发成千上万次 Keystore 查询。
     * SecretKey 本身是线程安全的不可变句柄，缓存后加解密只是 Cipher 运算。
     */
    @Volatile private var cachedKey: SecretKey? = null

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            if (unavailable) return null
            runCatching {
                val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
                if (existing != null) return@runCatching existing.secretKey
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
            }.onSuccess { cachedKey = it }
                .onFailure { unavailable = true }
                .getOrNull()
        }
    }

    /**
     * 加密可为空的字符串；失败返回 null（调用方应跳过该字段而不是写明文）。
     * @param aad 附加认证数据（建议「行标识|列名」）；非空时写入 v2 格式并绑定该上下文。
     */
    fun encrypt(plain: String?, aad: String? = null): String? {
        if (plain == null) return null
        val secret = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val boundAad = aad?.takeIf { it.isNotEmpty() }
            if (boundAad != null) cipher.updateAAD(boundAad.toByteArray(Charsets.UTF_8))
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            (if (boundAad != null) PREFIX_V2 else PREFIX_V1) +
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(body, Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * 解密；非加密前缀（历史明文）原样返回，密文损坏/密钥失效返回 null。
     * @param aad 与加密时一致的上下文；v2 密文必须提供，否则判定失败（不降级）。
     */
    fun decrypt(stored: String?, aad: String? = null): String? {
        if (stored == null) return null
        val v2 = stored.startsWith(PREFIX_V2)
        if (!v2 && !stored.startsWith(PREFIX_V1)) return stored // 历史明文
        val secret = key() ?: return null
        return runCatching {
            val payload = if (v2) stored.removePrefix(PREFIX_V2) else stored.removePrefix(PREFIX_V1)
            val parts = payload.split(':', limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (v2) {
                // v2 必须带 AAD；缺失或为空的调用是编程错误，直接失败而非无 AAD 解密。
                require(!aad.isNullOrEmpty()) { "缺少 AAD，拒绝解密 v2 密文" }
                cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.onFailure {
            // 密钥失效 / 数据损坏 / AAD 不匹配都应可观测，否则表现为“缓存静默消失”难排查。
            Log.w(TAG, "字段解密失败，按无缓存处理", it)
        }.getOrNull()
    }
}
