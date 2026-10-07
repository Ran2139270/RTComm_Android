package com.rtcomm.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

/**
 * 全局 ImageLoader 必须由 Application 实现 [ImageLoaderFactory] 才会被 Coil 采用：
 * Coil 的 `imageLoader(context)` 只会检查 `applicationContext` 是否实现该接口
 * （见 Coil.newImageLoader 字节码），在 Activity 上实现会被忽略，导致
 * GIF/WebP 动态头像退化为静态首帧。此处统一注册动态图解码器。
 */
class RtcommApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // 数据层在 Application 初始化：前台服务可能被系统单独拉起（此时没有 Activity
        // 走启动装配），若不初始化，new_message 会因 Repo 未就绪而被静默丢弃（消息不落库）。
        com.rtcomm.app.data.db.Repo.init(this)
        com.rtcomm.app.data.UploadBookmarks.init(this)
        com.rtcomm.app.data.AppDiagnostics.initialize(this)
        // FCM：运行时手动初始化（缺配置时自动降级为 no-op）。必须在 Application 阶段完成，
        // 以便被杀进程被 RtcommMessagingService 拉起时 FirebaseApp 已就绪。
        com.rtcomm.app.push.FirebaseInit.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                // API 28+ 用系统 ImageDecoder（支持动态 GIF/WebP），低版本回退 GifDecoder（仅 GIF）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            // 本地优先：图片/头像/视频首帧持久化到磁盘，重进页面秒显，再按需联网校验。
            .memoryCache {
                coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            // 忽略服务端缓存头：头像/图片 URL 带版本参数，按 URL 永久缓存更符合“本地优先”。
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
}
