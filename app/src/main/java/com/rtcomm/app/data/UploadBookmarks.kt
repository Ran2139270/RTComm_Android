package com.rtcomm.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * P1：断点续传书签 —— 持久化「文件 sha256 → uploadId」。
 * App 重启后再次上传同一文件时，先查书签恢复未完成的上传会话
 * （后端按 uploadId 记录已收分片），跳过已传分片直接续传。
 * 会话完成/放弃即清除书签；书签带时间戳，7 天自动过期。
 */
object UploadBookmarks {
    private const val PREFS = "rtcomm_upload_bookmarks"
    private const val TTL_MS = 7L * 24 * 3600 * 1000
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences = requireNotNull(sp) { "UploadBookmarks 未初始化" }

    fun save(fileHash: String, uploadId: String) {
        prefs().edit()
            .putString(fileHash, uploadId)
            .putLong("ts_$fileHash", System.currentTimeMillis())
            .apply()
    }

    fun take(fileHash: String): String? {
        val id = prefs().getString(fileHash, null) ?: return null
        val ts = prefs().getLong("ts_$fileHash", 0L)
        return if (System.currentTimeMillis() - ts > TTL_MS) {
            remove(fileHash); null
        } else id
    }

    fun remove(fileHash: String) {
        prefs().edit().remove(fileHash).remove("ts_$fileHash").apply()
    }

    fun clearAll() {
        prefs().edit().clear().apply()
    }
}
