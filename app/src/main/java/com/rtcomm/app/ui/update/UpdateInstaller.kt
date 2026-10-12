package com.rtcomm.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 安装 APK 的公共逻辑：未知来源授权 + FileProvider 拉起系统安装器。 */
object UpdateInstaller {

    /**
     * 拉起系统安装器；未授予「未知来源」时先跳转授权页并回调错误提示。
     * @return 是否已成功拉起安装器
     */
    fun install(context: Context, apk: File, onError: (String) -> Unit): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            onError("请在系统页面开启「允许来自此来源的应用」，返回后再次点击“安装更新”")
            requestPermission(context, onError)
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(install) }
            .onFailure { onError("无法启动安装器：${it.message}") }
            .isSuccess
    }

    private fun requestPermission(context: Context, onError: (String) -> Unit) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
            .onFailure { onError("无法打开安装授权设置：${it.message}") }
    }
}
