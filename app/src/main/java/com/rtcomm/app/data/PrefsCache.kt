package com.rtcomm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 轻量磁盘缓存：把列表接口的最近一次 JSON 结果存到加密 SharedPreferences，
 * 页面进入时**先渲染缓存**（秒开、不转圈），再静默拉取网络校验刷新。
 *
 * 仅用于展示型列表（设备 / AI 机器人 / 通话记录 / 文件），不作为写入来源；
 * 账号切换时按 accountKey 分区，避免串号。
 *
 * 安全：设备等列表里可能含 `Device.token` 等敏感字段，因此不用普通明文偏好，
 * 改用自研 [SecurePrefs]（AES-256-GCM，Keystore 保管密钥）；Keystore 不可用时宁可不缓存，
 * 也绝不降级写明文。缓存是一次性可重建数据，切换实现时不做迁移（旧加密缓存文件直接删除）。
 */
object PrefsCache {
    private const val PREFIX = "cache_"
    private const val LEGACY_PREF = "rtcomm_cache"
    private const val PREF = "rtcomm_cache_enc"

    @Volatile private var secure: SharedPreferences? = null
    @Volatile private var secureInit = false
    @Volatile private var legacyCleared = false

    /**
     * 仅供单元测试注入的缓存后端：Robolectric 无法提供 AndroidKeystore，
     * 直接走加密实现会拿不到实例。生产路径不读取它，仍严格使用
     * [SecurePrefs]，Keystore 不可用时宁可不缓存、绝不降级写明文。
     */
    @androidx.annotation.VisibleForTesting
    internal var testBackend: SharedPreferences? = null

    @Synchronized
    private fun prefs(context: Context): SharedPreferences? {
        testBackend?.let { return it }
        if (!secureInit) {
            secureInit = true
            val appCtx = context.applicationContext
            secure = SecurePrefs.open(appCtx)
            if (secure == null) Log.w("PrefsCache", "加密缓存不可用，本次不写磁盘缓存")
            // 旧版本遗留的明文缓存（含设备令牌）与旧加密缓存文件一并清除，避免残留敏感数据。
            if (!legacyCleared) {
                legacyCleared = true
                runCatching {
                    appCtx.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE).edit().clear().apply()
                    java.io.File(appCtx.applicationInfo.dataDir, "shared_prefs/$PREF.xml").delete()
                }
            }
        }
        return secure
    }

    private fun key(accountKey: String, name: String) = "$PREFIX$accountKey|$name"

    fun save(context: Context, name: String, json: String) {
        val ak = AppState.accountKey
        prefs(context)?.edit()?.putString(key(ak, name), json)?.apply()
    }

    fun load(context: Context, name: String): String? {
        val ak = AppState.accountKey
        return prefs(context)?.getString(key(ak, name), null)
    }

    /** 登出/切号时清理该账号的缓存。 */
    fun clearAccount(context: Context, accountKey: String) {
        val p = prefs(context) ?: return
        val e = p.edit()
        p.all.keys.filter { it.startsWith("$PREFIX$accountKey|") }.forEach { e.remove(it) }
        e.apply()
    }
}
