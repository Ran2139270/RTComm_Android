package com.rtcomm.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 已安装应用版本查询。
 *
 * 本地版本号**以 PackageManager 的 longVersionCode 为准**（运行时的真值），
 * 而不是编译期常量：某些安装/覆盖场景下两者可能不一致，用错会导致
 * 「已更新到最新版却仍提示更新」。
 */
object AppVersion {

    fun code(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        long.toInt()
    }.getOrDefault(com.rtcomm.app.BuildConfig.VERSION_CODE)

    fun name(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: com.rtcomm.app.BuildConfig.VERSION_NAME
}
