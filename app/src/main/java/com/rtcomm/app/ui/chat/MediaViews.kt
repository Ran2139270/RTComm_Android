package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import coil.imageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.Headers
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.Message
import com.rtcomm.app.data.MessageFile
import com.rtcomm.app.data.SendState
import com.rtcomm.app.ui.theme.Alpha
import com.rtcomm.app.ui.theme.Corner
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.DialogAppear
import com.rtcomm.app.ui.common.rememberReducedMotion
import kotlinx.coroutines.delay

/** 私有媒体请求统一带 Authorization，JWT 不再进入 URL。 */
private fun mediaHeaders() = Headers.Builder().add("Authorization", "Bearer ${Api.token}").build()

/** DefaultHttpDataSource/DefaultMediaSourceFactory 属于 Media3 UnstableApi，这里显式 opt-in。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun authenticatedPlayer(context: android.content.Context, file: MessageFile): ExoPlayer {
    val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(
        mapOf("Authorization" to "Bearer ${Api.token}"),
    )
    return ExoPlayer.Builder(context).setMediaSourceFactory(
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory),
    ).build().apply { setMediaItem(MediaItem.fromUri(Api.downloadUrl(file.id))) }
}

@Composable
fun InlineLocalImage(uri: String, fileName: String, modifier: Modifier = Modifier) {
    var fullscreen by remember(uri) { mutableStateOf(false) }
    val context = LocalContext.current
    Box(
        modifier = modifier.size(width = 200.dp, height = 140.dp).clip(RoundedCornerShape(Corner.thumb))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { fullscreen = true },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(uri).crossfade(120).build(),
            contentDescription = fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (fullscreen) {
        val request = remember(uri) { ImageRequest.Builder(context).data(uri).build() }
        FullscreenImageDialog(fileName, request, onDismiss = { fullscreen = false })
    }
}

/**
 * 保存图片到相册并把结果转成可展示文案（成功提示 / 中文错误）。
 * 三个图片查看入口（本地待发、远端单图、会话统一查看器）共用，避免各写一份 try。
 */
private suspend fun saveToGalleryOrError(
    context: android.content.Context,
    request: ImageRequest,
    name: String,
): String = runCatching { saveImageToGallery(context, request, name) }
    .getOrElse { "保存失败：${it.message ?: "未知错误"}" }

/**
 * 单图全屏查看：本地待发图与远端图共用同一实现。
 * 调用方负责构造 [request]（本地图 `data(uri)`；远端图需带 [mediaHeaders]），
 * 两者在此统一渲染与「保存到相册」流程，不再各自维护一份 AlertDialog。
 */
@Composable
private fun FullscreenImageDialog(name: String, request: ImageRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { status = saveToGalleryOrError(context, request, name) }
            }) { Text("保存到相册") }
        },
        text = {
            Column {
                AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                )
                status?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/**
 * 把图片存入系统相册。
 *
 * 直接复用 Coil 已解码的图片（含鉴权头），所以不重复走一次网络下载；
 * 用 Size.ORIGINAL 重新解码，保存的是原始分辨率而不是屏幕上的缩略图。
 *
 * Android 10+ 走 MediaStore（无需任何存储权限）；低版本存到应用图片目录并触发媒体扫描。
 */
/** Drawable → Bitmap；Coil 通常给 BitmapDrawable，其余情况绘制成新位图。 */
private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        drawable.bitmap?.let { return it }
    }
    val w = drawable.intrinsicWidth.coerceAtLeast(1)
    val h = drawable.intrinsicHeight.coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    return bmp
}

private suspend fun saveImageToGallery(
    context: android.content.Context,
    requestBase: ImageRequest,
    displayName: String,
): String {
    val fullRequest = requestBase.newBuilder()
        .size(coil.size.Size.ORIGINAL)
        .build()
    // 只用 ImageResult.drawable（稳定 API），不依赖 SuccessResult/Image 的具体实现。
    val drawable = context.imageLoader.execute(fullRequest).drawable
        ?: throw IllegalStateException("图片还没加载完成")
    val bitmap = drawableToBitmap(drawable)
    // 保证“文件扩展名 = 实际编码 = MIME”三者一致：
    // 原名 .png 的走 PNG（保留透明通道），其余统一 JPEG，避免 .png 文件里装 JPEG 数据。
    val isPng = displayName.endsWith(".png", true)
    val baseName = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_")
        .ifBlank { "rtcomm_${System.currentTimeMillis()}" }
    val fileName = "$baseName.${if (isPng) "png" else "jpg"}"
    val format = if (isPng) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
    val mime = if (isPng) "image/png" else "image/jpeg"

    return withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/RTComm",
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册条目")
            resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(format, 95, os)
            } ?: throw IllegalStateException("无法写入相册")
            // 必须清掉 IS_PENDING，否则图库里看不到这张图
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "已保存到相册（Pictures/RTComm）"
        } else {
            val dir = java.io.File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                "RTComm",
            ).apply { mkdirs() }
            val out = java.io.File(dir, fileName)
            out.outputStream().use { bitmap.compress(format, 95, it) }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(out.absolutePath), arrayOf(mime), null,
            )
            "已保存到 ${out.absolutePath}"
        }
    }
}

/**
 * 内联图片消息：无边框、无背景板、按原始宽高比显示，不裁剪。
 * 最大宽度不超过气泡宽度、最大高度不超过屏幕 60%；加载中保持占位比例避免跳动。
 */
@Composable
fun InlineImage(file: MessageFile, modifier: Modifier = Modifier, onClick: ((MessageFile) -> Unit)? = null) {
    var fullscreen by remember { mutableStateOf(false) }
    var loaded by remember(file.id) { mutableStateOf(false) }
    var failed by remember(file.id) { mutableStateOf(false) }
    var retry by remember(file.id) { mutableStateOf(0) }
    // 原始宽高比（w/h）。未知前用 4:3 占位，加载完成后再校正，避免大面积跳动。
    var ratio by remember(file.id) { mutableFloatStateOf(4f / 3f) }
    val url = Api.downloadUrl(file.id)
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val maxW = (screenW * 0.72f).dp
    val maxH = (screenH * 0.6f).dp
    // 在 maxW×maxH 内按比例适配。
    val w = if (maxW / ratio <= maxH) maxW else maxH * ratio
    val h = if (maxW / ratio <= maxH) maxW / ratio else maxH
    Box(
        modifier.size(width = w, height = h).clip(RoundedCornerShape(Corner.small))
            .clickable {
                when {
                    // 优先交给统一查看器（会话内左右滑）；未提供时退回单图全屏。
                    loaded && onClick != null -> onClick(file)
                    loaded -> fullscreen = true
                    failed -> { failed = false; retry++ }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(url).headers(mediaHeaders()).crossfade(180).setParameter("retry", retry).build(),
            contentDescription = file.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state ->
                loaded = true; failed = false
                val d = state.result.drawable
                val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
                if (iw > 0 && ih > 0) ratio = iw.toFloat() / ih
            },
            onError = { loaded = false; failed = true },
        )
        // 占位转圈与失败提示淡入淡出，替代「啪」地出现/消失。
        AnimatedVisibility(
            visible = !loaded && !failed,
            enter = fadeIn(tween(if (reducedMotion) 0 else 160)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
        ) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        }
        AnimatedVisibility(
            visible = failed,
            enter = fadeIn(tween(if (reducedMotion) 0 else 160)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.BrokenImage, contentDescription = "加载失败，点击重试", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("点击重试", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (fullscreen) {
        val request = remember(url) { ImageRequest.Builder(context).data(url).headers(mediaHeaders()).build() }
        FullscreenImageDialog(file.fileName, request, onDismiss = { fullscreen = false })
    }
}

/**
 * 自定义表情：按原始宽高比缩小显示（最长边 120dp），不裁剪；GIF 自动播放。
 */
@Composable
fun StickerImage(file: MessageFile) {
    val context = LocalContext.current
    val request = remember(file.id) {
        ImageRequest.Builder(context).data(Api.downloadUrl(file.id)).headers(mediaHeaders()).build()
    }
    StickerBox(model = request, key = file.id, contentDescription = file.fileName)
}

/** 本地待发/乐观表情：直接以本地文件渲染，上传完成前也能看到。 */
@Composable
fun StickerLocalImage(uri: String) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context).data(java.io.File(uri)).build()
    }
    StickerBox(model = request, key = uri, contentDescription = null)
}

@Composable
private fun StickerBox(model: Any?, key: Any?, contentDescription: String?) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var ratio by remember(key) { mutableFloatStateOf(1f) }
    // 原始最长边（dp）：小表情按原始尺寸显示，避免被放大到 120dp 而模糊。
    var intrinsicSideDp by remember(key) { mutableFloatStateOf(0f) }
    val maxSideDp = 120f
    val sideDp = if (intrinsicSideDp > 0f) minOf(maxSideDp, intrinsicSideDp) else maxSideDp
    val w = if (ratio >= 1f) sideDp.dp else (sideDp * ratio).dp
    val h = if (ratio >= 1f) (sideDp / ratio).dp else sideDp.dp
    Box(Modifier.size(w, h), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state ->
                val d = state.result.drawable
                val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
                if (iw > 0 && ih > 0) {
                    ratio = iw.toFloat() / ih
                    intrinsicSideDp = with(density) { maxOf(iw, ih).toDp().value }
                }
            },
        )
    }
}

/** 视频缩略图与时长（本地缓存）。 */
private data class VideoMeta(val thumbPath: String?, val durationMs: Long, val ratio: Float)

/** 进程内元数据缓存：滚动来回不再重复联网抽帧。 */
private val videoMetaMemory = java.util.concurrent.ConcurrentHashMap<String, VideoMeta>()

/**
 * 用 MediaMetadataRetriever 从鉴权 URL 抽取首帧与时长，缩略图与时长/比例一起落盘缓存。
 *
 * 旧实现每次都 setDataSource（联网）取时长，且缩略图命中时比例会丢回 16:9；
 * 这里把比例/时长写入 sidecar（`<id>.meta`），下次进入直接命中缓存，不再联网。
 */
private fun loadVideoMeta(context: android.content.Context, file: MessageFile): VideoMeta {
    videoMetaMemory[file.id]?.let { return it }
    val dir = java.io.File(context.cacheDir, "video_thumbs").apply { mkdirs() }
    val out = java.io.File(dir, "${file.id}.jpg")
    val metaFile = java.io.File(dir, "${file.id}.meta")
    // 命中落盘缓存：缩略图 + 时长 + 比例都在，无需联网。
    if (out.exists() && metaFile.exists()) {
        val cached = runCatching {
            val parts = metaFile.readText().split(",")
            val d = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val r = parts.getOrNull(1)?.toFloatOrNull() ?: (16f / 9f)
            if (d > 0) VideoMeta(out.absolutePath, d, if (r > 0f) r else 16f / 9f) else null
        }.getOrNull()
        if (cached != null) {
            videoMetaMemory[file.id] = cached
            return cached
        }
    }
    val mmr = android.media.MediaMetadataRetriever()
    val meta = try {
        mmr.setDataSource(Api.downloadUrl(file.id), mapOf("Authorization" to "Bearer ${Api.token}"))
        val durationMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        var ratio = 16f / 9f
        if (!out.exists()) {
            val bmp = mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp != null) {
                if (bmp.height > 0) ratio = bmp.width.toFloat() / bmp.height
                runCatching { out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
                bmp.recycle()
            }
        }
        VideoMeta(if (out.exists()) out.absolutePath else null, durationMs, ratio)
    } catch (e: Throwable) {
        VideoMeta(null, 0L, 16f / 9f)
    } finally {
        runCatching { mmr.release() }
    }
    // 只有拿到有效元数据才落盘，避免把失败结果缓存成“永久坏数据”。
    if (meta.durationMs > 0) {
        runCatching { metaFile.writeText("${meta.durationMs},${meta.ratio}") }
        videoMetaMemory[file.id] = meta
    }
    return meta
}

/**
 * 内联视频消息：无边框、按原始比例显示，首帧缩略图 + 中央播放按钮 + 时长；点击进入播放器。
 */
@Composable
fun VideoThumb(file: MessageFile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    var meta by remember(file.id) { mutableStateOf<VideoMeta?>(null) }
    LaunchedEffect(file.id) {
        meta = withContext(Dispatchers.IO) { loadVideoMeta(context, file) }
    }
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val maxW = (screenW * 0.72f).dp
    val maxH = (screenH * 0.6f).dp
    val ratio = meta?.ratio?.takeIf { it > 0f } ?: (16f / 9f)
    val w = if (maxW / ratio <= maxH) maxW else maxH * ratio
    val h = if (maxW / ratio <= maxH) maxW / ratio else maxH
    val thumbPath = meta?.thumbPath
    Box(
        modifier.animateContentSize()
            .size(width = w, height = h)
            .clip(RoundedCornerShape(Corner.small))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = Alpha.scrimStrong))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 元数据异步到达后比例会从 16:9 校正为真实值：animateContentSize 平滑尺寸，
        // 缩略图淡入，避免缩略图「跳」出来。
        AnimatedVisibility(
            visible = thumbPath != null,
            enter = fadeIn(tween(if (reducedMotion) 0 else 180)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
        ) {
            if (thumbPath != null) {
                AsyncImage(
                    model = java.io.File(thumbPath),
                    contentDescription = file.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f)))
            }
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放视频",
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(56.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(file.fileName, style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
            Text(
                FileTransfer.humanSize(file.fileSize) +
                    ((meta?.durationMs ?: 0L).takeIf { it > 0 }?.let { " · " + com.rtcomm.app.ui.common.Format.duration((it / 1000).toInt()) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * 待上传媒体占位：缩略图/图标 + 进度环 + 百分比 + 取消；失败显示重试。
 * 上传在应用级协程进行，因此退出会话/旋转不会中断。
 */
@Composable
fun PendingUploadContent(
    m: Message,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val failed = m.sendState == com.rtcomm.app.data.SendState.Failed
    val progress = m.uploadProgress.coerceIn(0f, 1f)
    val boxW = 210.dp
    val boxH = 160.dp
    Column(modifier) {
        Box(
            Modifier.size(width = boxW, height = boxH).clip(RoundedCornerShape(Corner.small))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                m.messageType == "image" && m.localPreviewUri != null -> AsyncImage(
                    model = ImageRequest.Builder(ctx).data(m.localPreviewUri).build(),
                    contentDescription = m.file?.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                m.messageType == "video" -> Icon(
                    Icons.Filled.PlayArrow, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp),
                )
                else -> Icon(
                    Icons.Filled.BrokenImage, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp),
                )
            }
            // 上传中：半透明遮罩 + 进度环 + 百分比
            if (!failed) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = Alpha.scrimSoft)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                        color = androidx.compose.ui.graphics.Color.White,
                        trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${(progress * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                }
                // 取消按钮
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "取消上传", tint = androidx.compose.ui.graphics.Color.White)
                }
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error.copy(alpha = Alpha.scrimSoft)))
                Text("上传失败", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            m.file?.fileName ?: "文件",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                FileTransfer.humanSize(m.file?.fileSize ?: 0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) {
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 共享音频播放器对 UI 的可见状态。 */
private data class AudioUiState(
    val fileId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * 应用级共享音频播放器。
 *
 * 旧实现每条语音消息各建一个 ExoPlayer：一屏十几条语音就是十几个解码器/内存实例。
 * 这里全局只保留一个 ExoPlayer，切换音频时换 MediaItem；UI 通过 [state] 观察进度。
 */
@androidx.annotation.OptIn(UnstableApi::class)
private object AudioPlaybackManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = _state

    private fun ensure(context: android.content.Context): ExoPlayer {
        player?.let { return it }
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(
            mapOf("Authorization" to "Bearer ${Api.token}"),
        )
        val p = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory),
        ).build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                if (isPlaying) startTicker() else stopTicker()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(durationMs = p.duration.coerceAtLeast(0L))
                if (playbackState == Player.STATE_ENDED) {
                    // 播放结束后复位，下次点击从头播放。
                    _state.value = _state.value.copy(playing = false, positionMs = 0L)
                }
            }
        })
        player = p
        return p
    }

    fun toggle(context: android.content.Context, file: MessageFile) {
        val p = ensure(context)
        if (_state.value.fileId != file.id) {
            _state.value = AudioUiState(fileId = file.id, playing = true)
            p.setMediaItem(MediaItem.fromUri(Api.downloadUrl(file.id)))
            p.prepare()
            p.play()
        } else if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            // 播放结束后再次点击从头播放。
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    /** 拖动进度条：切到目标音频并 seek（未播放也允许预置进度）。 */
    fun seekTo(context: android.content.Context, file: MessageFile, positionMs: Long) {
        val p = ensure(context)
        if (_state.value.fileId != file.id) {
            _state.value = AudioUiState(fileId = file.id)
            p.setMediaItem(MediaItem.fromUri(Api.downloadUrl(file.id)))
            p.prepare()
        }
        val target = positionMs.coerceAtLeast(0L)
        p.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val p = player ?: break
                _state.value = _state.value.copy(
                    positionMs = p.currentPosition.coerceAtLeast(0L),
                    durationMs = p.duration.coerceAtLeast(0L),
                )
                delay(300)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** 离开聊天页时停掉，避免退到别的页面还在后台播放。 */
    fun stop() {
        player?.pause()
        player?.stop()
        stopTicker()
        _state.value = AudioUiState()
    }
}

/** 离开聊天页时调用。 */
internal fun stopAudioPlayback() = AudioPlaybackManager.stop()

@Composable
fun AudioPlayer(file: MessageFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by AudioPlaybackManager.state.collectAsState()
    val mine = ui.fileId == file.id
    val playing = mine && ui.playing
    val duration = if (mine) ui.durationMs else 0L
    val progress = if (mine && duration > 0) ui.positionMs.toFloat() / duration else 0f
    // 拖动时用本地值预览，松手才真正 seek，避免拖动过程中频繁 seek 造成卡顿。
    var scrub by remember(file.id) { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { AudioPlaybackManager.toggle(context, file) }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "暂停" else "播放")
        }
        Spacer(Modifier.width(Space.xs))
        Column(Modifier.width(180.dp)) {
            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            val shown = (scrub ?: progress).coerceIn(0f, 1f)
            Slider(
                value = shown,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    val s = scrub
                    if (s != null && duration > 0) {
                        AudioPlaybackManager.seekTo(context, file, (s * duration).toLong())
                    }
                    scrub = null
                },
                enabled = mine || duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (mine && duration > 0) {
                    val posSec = (shown * duration / 1000).toInt()
                    "$posSec / ${duration / 1000} 秒"
                } else "点击流式播放",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun VideoPlayerDialog(file: MessageFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(file.id) { authenticatedPlayer(context, file).apply { prepare(); playWhenReady = true } }
    DisposableEffect(file.id) { onDispose { player.release() } }
    // 全屏沉浸式播放，替换原来被弹窗宽度限制的小窗口。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DialogAppear {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Space.md),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
        }
    }
}

/**
 * 会话内统一图片查看器：左右滑动浏览本会话全部图片，可保存当前图到相册。
 * 取代原先「每张图片各自弹一个 AlertDialog」的割裂体验。
 */
@Composable
fun MediaViewerDialog(
    images: List<MessageFile>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) { onDismiss(); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
    ) { images.size }
    var status by remember { mutableStateOf<String?>(null) }
    // 缩放/平移：双击在 1x / 2.5x 间切换；放大后才启用双指缩放与单指平移
    // （未放大时 transformable 关闭，不拦截 HorizontalPager 的翻页手势）。
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val resetZoom = { zoomScale = 1f; zoomOffset = androidx.compose.ui.geometry.Offset.Zero }
    // 翻页后清掉上一张的保存提示与缩放。
    LaunchedEffect(pagerState.currentPage) { status = null; resetZoom() }

    // 打开时背景淡入、图片从 0.92 轻微放大，避免“啪”地硬切；减少动态效果时直接到位。
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    val appear = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) appear.animateTo(1f, tween(220))
    }
    // 下滑关闭：向下拖动图片，超过阈值松手即关闭；否则弹回。
    val dismissThreshold = with(LocalDensity.current) { 140.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().graphicsLayer { alpha = appear.value },
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = Alpha.scrimFull),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragY
                        alpha = (1f - (dragY / (dismissThreshold * 3f))).coerceIn(0.35f, 1f)
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragY > dismissThreshold) onDismiss() else dragY = 0f
                            },
                            onVerticalDrag = { _, delta -> dragY = (dragY + delta).coerceAtLeast(0f) },
                        )
                    },
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = 12.dp,
                ) { page ->
                    val file = images[page]
                    val request = remember(file.id) {
                        ImageRequest.Builder(context)
                            .data(Api.downloadUrl(file.id))
                            .headers(mediaHeaders())
                            .build()
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = request,
                            contentDescription = file.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.82f)
                                .transformable(
                                    state = rememberTransformableState { zoomChange, panChange, _ ->
                                        zoomScale = (zoomScale * zoomChange).coerceIn(1f, 5f)
                                        zoomOffset = if (zoomScale > 1f) {
                                            zoomOffset + panChange
                                        } else {
                                            androidx.compose.ui.geometry.Offset.Zero
                                        }
                                    },
                                    enabled = zoomScale > 1f,
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onDoubleTap = { if (zoomScale > 1f) resetZoom() else zoomScale = 2.5f },
                                        onTap = { if (zoomScale > 1f) resetZoom() },
                                    )
                                }
                                .graphicsLayer {
                                    val s = (0.92f + 0.08f * appear.value) * zoomScale
                                    scaleX = s
                                    scaleY = s
                                    translationX = zoomOffset.x
                                    translationY = zoomOffset.y
                                },
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    status?.let {
                        Text(it, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(Space.sm))
                    }
                    TextButton(onClick = {
                        val file = images[pagerState.currentPage]
                        scope.launch {
                            val request = ImageRequest.Builder(context)
                                .data(Api.downloadUrl(file.id))
                                .headers(mediaHeaders())
                                .build()
                            status = saveToGalleryOrError(context, request, file.fileName)
                        }
                    }) {
                        Text("保存到相册", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}
