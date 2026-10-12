package com.rtcomm.app.data

import android.content.Context
import coil.ImageLoader
import java.io.File

/**
 * 应用存储占用统计与清理。
 *
 * 覆盖图片/头像/视频首帧缓存（Coil 磁盘缓存）、聊天数据库、下载文件、安装包、临时文件。
 */
object StorageManager {

    data class Entry(val id: String, val label: String, val hint: String, val bytes: Long)

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun downloadsDir(context: Context) =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "downloads")

    private fun updatesDir(context: Context) =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")

    private fun tempDirs(context: Context) = listOf(
        File(context.cacheDir, "previews"),
        File(context.cacheDir, "camera"),
        File(context.cacheDir, "voice"),
        File(context.cacheDir, "video_thumbs"),
    )

    private fun dbSize(context: Context): Long {
        val db = context.getDatabasePath("rtcomm.db")
        return dbSizeOf(db) + dbSizeOf(File(db.path + "-wal")) + dbSizeOf(File(db.path + "-shm"))
    }

    private fun dbSizeOf(f: File) = if (f.exists()) f.length() else 0L

    fun entries(context: Context, imageLoader: ImageLoader): List<Entry> = listOf(
        Entry(
            id = "image_cache",
            label = "图片 / 头像 / 视频首帧缓存",
            hint = "重进页面秒显；清除后按需重新下载",
            bytes = imageLoader.diskCache?.size ?: 0L,
        ),
        Entry(
            id = "database",
            label = "聊天记录数据库",
            hint = "本地消息缓存；清除后从服务端重新拉取",
            bytes = dbSize(context),
        ),
        Entry(
            id = "downloads",
            label = "已下载文件",
            hint = "通过文件页/聊天下载到本机的文件",
            bytes = dirSize(downloadsDir(context)),
        ),
        Entry(
            id = "updates",
            label = "安装包",
            hint = "已下载的更新安装包",
            bytes = dirSize(updatesDir(context)),
        ),
        Entry(
            id = "temp",
            label = "临时文件",
            hint = "预览副本 / 拍照 / 录音 / 视频缩略图",
            bytes = tempDirs(context).sumOf { dirSize(it) },
        ),
    )

    fun total(context: Context, imageLoader: ImageLoader): Long =
        entries(context, imageLoader).sumOf { it.bytes }

    /** 执行单项清理。 */
    fun clear(context: Context, imageLoader: ImageLoader, id: String) {
        when (id) {
            "image_cache" -> {
                runCatching { imageLoader.diskCache?.clear() }
                runCatching { imageLoader.memoryCache?.clear() }
            }
            "database" -> AppState.clearAllMessagesCache()
            "downloads" -> runCatching { downloadsDir(context).deleteRecursively() }
            "updates" -> runCatching { updatesDir(context).deleteRecursively() }
            "temp" -> tempDirs(context).forEach { runCatching { it.deleteRecursively() } }
        }
    }

    fun clearAll(context: Context, imageLoader: ImageLoader) {
        listOf("image_cache", "database", "downloads", "updates", "temp")
            .forEach { clear(context, imageLoader, it) }
    }

    fun format(bytes: Long): String = FileTransfer.humanSize(bytes)
}
