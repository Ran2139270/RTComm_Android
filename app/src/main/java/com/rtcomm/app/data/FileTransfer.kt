package com.rtcomm.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 文件上传/下载。
 * - 上传：init(秒传检测) → 逐片 POST 二进制（失败重试、进度回调）→ complete
 * - 下载：OkHttp 流式写入 App 私有目录（进度回调、可取消）
 * - SHA-256 流式计算，绝不一次性读入超大文件
 * - JWT 仅用于请求头 / 临时下载 URL，不写入日志或分享文本
 */
object FileTransfer {

    /** 与服务端 MAX_FILE_SIZE 对齐（默认 2GB）。 */
    private const val MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024
    /** 服务端默认分片 8MB；这里允许到 16MB，同时设置硬上限防止恶意元数据导致 OOM。 */
    private const val MAX_CHUNK_SIZE = 16 * 1024 * 1024
    /** 分片数硬上限（2GB / 32KB 最小分片）。 */
    private const val MAX_CHUNKS = 65536
    /** 落盘前要求保留的最小可用空间。 */
    private const val MIN_FREE_SPACE_BYTES = 64L * 1024 * 1024
    /** 动态头像（GIF/WebP）原样上传上限：10MB（与服务端 users.js 校验对齐；静态头像 5MB）。 */
    const val MAX_ANIMATED_AVATAR_BYTES = 10 * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val OCTET = "application/octet-stream".toMediaType()

    data class Picked(val file: File, val name: String, val size: Long, val mime: String)

    /**
     * 把 content Uri 拷贝到 cache 临时文件，并解析出文件名/大小/MIME。
     * 拷贝过程累计字节并在超过 [MAX_FILE_SIZE] 时中断，避免恶意/异常 Provider 无限写入。
     */
    fun copyToCache(context: Context, uri: Uri): Picked {
        val resolver = context.contentResolver
        var name = "file"
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (size > MAX_FILE_SIZE) throw Api.ApiException(413, "文件超过大小上限（2GB）")
        val mime = resolver.getType(uri) ?: guessMime(name)
        val dir = File(context.cacheDir, "uploads").apply { mkdirs() }
        if (dir.usableSpace < MIN_FREE_SPACE_BYTES) throw Api.ApiException(507, "设备存储空间不足")
        val tmp = File(dir, "up_${System.currentTimeMillis()}_${name.take(64).replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        try {
            val input = resolver.openInputStream(uri) ?: throw Api.ApiException(400, "无法读取所选文件")
            input.use { ins ->
                tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_SIZE) throw Api.ApiException(413, "文件超过大小上限（2GB）")
                        os.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (size <= 0) size = tmp.length()
        return Picked(tmp, name, size, mime)
    }

    /**
     * 发送前移除 GPS / 设备型号等敏感 EXIF。压缩路径重编码会自然丢弃元数据，
     * 但“尺寸已达标直接原样发送”的路径会保留原图元数据，因此统一先剥离。
     */
    fun stripSensitiveExif(file: File) {
        runCatching {
            val exif = ExifInterface(file.absolutePath)
            var changed = false
            SENSITIVE_EXIF_TAGS.forEach { tag ->
                if (exif.getAttribute(tag) != null) {
                    exif.setAttribute(tag, null)
                    changed = true
                }
            }
            if (changed) exif.saveAttributes()
        }
    }

    private val SENSITIVE_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
    )

    /**
     * 把所选图片复制到应用私有目录作为自定义背景（聊天/主界面）。
     * 返回持久化后的绝对路径；失败返回 null。
     */
    fun importBackground(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val out = File(dir, "bg_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os -> ins.copyTo(os) }
        } ?: return@runCatching null
        if (out.length() <= 0) { out.delete(); return@runCatching null }
        out.absolutePath
    }.getOrNull()

    /**
     * 把所选图片/GIF 复制到应用私有目录作为自定义表情。
     * 原样保留（不重编码），GIF 动画得以保留。返回绝对路径；失败返回 null。
     */
    fun importEmoji(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, "emojis").apply { mkdirs() }
        val ext = mimeOf(context, uri).substringAfter('/', "").ifBlank { "img" }
        val out = File(dir, "emo_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os -> ins.copyTo(os) }
        } ?: return@runCatching null
        if (out.length() <= 0) { out.delete(); return@runCatching null }
        out.absolutePath
    }.getOrNull()

    /**
     * 把一条已上传图片消息的服务器文件保存为自定义表情，返回本地路径；失败返回 null。
     * 阻塞调用，请在 IO 线程执行。
     */
    fun importEmojiFromFile(context: Context, fileId: String, fileName: String): String? = runCatching {
        val src = download(context, fileId, fileName)
        try {
            val dir = File(context.filesDir, "emojis").apply { mkdirs() }
            val ext = src.name.substringAfterLast('.', "").ifBlank { "img" }
            val out = File(dir, "emo_${System.currentTimeMillis()}.$ext")
            src.copyTo(out, overwrite = true)
            if (out.length() <= 0) { out.delete(); null } else out.absolutePath
        } finally {
            src.delete()
        }
    }.getOrNull()

    /**
     * 为 AI 图片输入准备 dataUrl：图片原样（≤maxBytes）；过大则压缩为 JPEG。
     * 非图片返回 null。
     */
    fun aiImageDataUrl(context: Context, uri: Uri, maxBytes: Int = 10 * 1024 * 1024): String? {
        // 服务端只接受这四种图片 MIME；其余（HEIC/HEIF 等）必须先转成 JPEG。
        val allowed = setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif")
        return runCatching {
            val mime0 = mimeOf(context, uri)
            if (!mime0.startsWith("image/")) return@runCatching null
            val original = copyToCache(context, uri)
            try {
                val mime = original.mime.ifBlank { mime0 }.lowercase()
                // 先用已知大小预判：原始文件可能接近 2GB，直接 readBytes 会 OOM。
                if (mime in allowed && original.size in 1..maxBytes.toLong() && original.size <= Int.MAX_VALUE) {
                    val bytes = original.file.readBytes()
                    if (bytes.isNotEmpty() && bytes.size <= maxBytes) {
                        return@runCatching "data:$mime;base64," +
                            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                }
                // 过大或格式不被接受：强制转成 JPEG 再发（skipBelowBytes=0 确保不被「省略重编码」短路）。
                val compressed = compressImage(original, skipBelowBytes = 0L)
                try {
                    val cmime = compressed.mime.ifBlank { "image/jpeg" }.lowercase()
                    if (cmime !in allowed || compressed.file.length() !in 1..maxBytes.toLong()) {
                        null
                    } else {
                        val cbytes = compressed.file.readBytes()
                        if (cbytes.isEmpty() || cbytes.size > maxBytes) null
                        else "data:$cmime;base64," + android.util.Base64.encodeToString(cbytes, android.util.Base64.NO_WRAP)
                    }
                } finally {
                    if (compressed.file !== original.file) compressed.file.delete()
                }
            } finally {
                original.file.delete()
            }
        }.getOrNull()
    }

    /** 本地待发媒体的准备结果。 */
    data class LocalMedia(
        val file: File,
        val name: String,
        val size: Long,
        val mime: String,
        val type: String,
        val previewUri: String?,
    )

    /**
     * 把所选 Uri 准备成本地待发媒体：拷贝到缓存、图片压缩、生成预览副本。
     * 返回的 [LocalMedia.file] 作为上传源保留，供失败重试；每次上传使用其临时副本。
     */
    fun prepareMedia(context: Context, uri: Uri, forcedMime: String? = null): LocalMedia? = runCatching {
        val original = copyToCache(context, uri)
        val mime = forcedMime ?: original.mime
        val prepared = if (mime.startsWith("image/")) compressImage(original) else original
        if (prepared.file != original.file) runCatching { original.file.delete() }
        val src = prepared.file
        if (!src.exists() || src.length() <= 0) return@runCatching null
        val type = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "file"
        }
        val preview = if (mime.startsWith("image/")) {
            makePreviewCopy(context, src, prepared.name)?.let { Uri.fromFile(it).toString() }
        } else null
        LocalMedia(src, prepared.name, src.length(), prepared.mime, type, preview)
    }.getOrNull()

    /** 为乐观图片气泡生成独立预览副本：上传完成后原文件会被删除，预览必须另存。 */
    fun makePreviewCopy(context: Context, src: File, name: String): File? = runCatching {        val dir = File(context.cacheDir, "previews").apply { mkdirs() }
        val safe = name.take(48).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "preview.jpg" }
        val dst = File(dir, "pv_${System.currentTimeMillis()}_$safe")
        src.copyTo(dst, overwrite = true)
        dst
    }.getOrNull()

    /** 清理超过 [maxAgeMs] 的预览副本（消息确认后不再需要，失败/丢弃的也要兜底回收）。 */
    fun cleanupPreviews(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        runCatching {
            val dir = File(context.cacheDir, "previews")
            val deadline = System.currentTimeMillis() - maxAgeMs
            dir.listFiles()?.forEach { if (it.lastModified() < deadline) it.delete() }
        }
    }

    /** 读取 Uri 的 MIME（用于判断是否动态图片）。 */
    fun mimeOf(context: Context, uri: Uri): String =
        context.contentResolver.getType(uri)?.lowercase() ?: ""

    /**
     * 原样读取（不重编码）图片为 base64 dataUrl，用于 GIF/WebP 动态头像；
     * 非动态格式或超过 [maxBytes] 返回 null。
     * 默认 10MB：动态头像不重编码，放宽上限以容纳更高帧数/分辨率的动图。
     */
    fun rawDataUrl(context: Context, uri: Uri, maxBytes: Int = MAX_ANIMATED_AVATAR_BYTES): String? {
        return runCatching {
            val mime = mimeOf(context, uri)
            if (mime != "image/gif" && mime != "image/webp") return null
            // 先查 size，再用「有界读取」兜底：任意 Provider 都不能让它一次读入超大内容。
            val known = contentSize(context, uri)
            if (known != null && (known <= 0 || known > maxBytes.toLong())) return null
            val bytes = readCapped(context, uri, maxBytes) ?: return null
            if (bytes.isEmpty()) return null
            "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 查询 ContentResolver 中 Uri 的字节数；未知返回 null。 */
    private fun contentSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()?.takeIf { it >= 0 }

    /** 以 [maxBytes] 为硬上限读取流；超限立即返回 null，绝不把超大内容读进内存。 */
    private fun readCapped(context: Context, uri: Uri, maxBytes: Int): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    /**
     * 把所选图片压成小尺寸 JPEG 的 base64 dataUrl，用于自定义头像。
     * 失败或超过 500KB 返回 null。
     */
    fun avatarDataUrl(context: Context, uri: Uri, maxDim: Int = 256): String? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, maxDim) }
            val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            try {
                val scaled = scaleWithin(decoded, maxDim)
                try {
                    val out = java.io.ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    val bytes = out.toByteArray()
                    if (bytes.isEmpty() || bytes.size > 500 * 1024) return null
                    "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    /**
     * 上传前压缩图片。
     *
     * 手机原图动辄 4-12MB，直传既慢又费流量，对方拉取也慢。这里在**本地**先下采样：
     * 长边不超过 [maxDim]，JPEG 质量 [quality]，并修正 EXIF 方向（否则压缩后图会躺着）。
     *
     * 保守策略（宁可大一点也不出错）：
     * - 非图片、GIF（会丢动图）、PNG（会丢透明通道）一律原样返回
     * - 尺寸与体积都已达标时直接返回原文件，不做无谓的重编码；但仍会先剥离敏感 EXIF
     * - 任何异常都回退到原文件，绝不因为压缩失败而让发送失败
     */
    fun compressImage(
        picked: Picked,
        maxDim: Int = 1600,
        quality: Int = 85,
        skipBelowBytes: Long = 400 * 1024,
    ): Picked {
        val mime = picked.mime.lowercase()
        // 所有图片先去掉定位/机型等敏感元数据（原样直传路径同样适用）。
        if (mime.startsWith("image/")) stripSensitiveExif(picked.file)
        val compressible = mime == "image/jpeg" || mime == "image/jpg" ||
            mime == "image/webp" || mime == "image/heic" || mime == "image/heif"
        if (!compressible) return picked
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(picked.file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching picked
            // 已经不大就没必要重编码（重编码本身也有损失）
            if (maxOf(w, h) <= maxDim && picked.size <= skipBelowBytes) return@runCatching picked

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(w, h, maxDim) }
            val decoded = BitmapFactory.decodeFile(picked.file.absolutePath, opts) ?: return@runCatching picked
            try {
                val scaled = scaleWithin(decoded, maxDim)
                try {
                    val oriented = applyExifOrientation(picked.file, scaled)
                    try {
                        val out = File(picked.file.parentFile, picked.file.nameWithoutExtension + "_c.jpg")
                        out.outputStream().use { os -> oriented.compress(Bitmap.CompressFormat.JPEG, quality, os) }
                        if (out.length() <= 0L) return@runCatching picked
                        // 压缩后反而更大就用原图
                        if (out.length() >= picked.size) {
                            out.delete()
                            return@runCatching picked
                        }
                        val newName = picked.name.substringBeforeLast('.', picked.name) + ".jpg"
                        Picked(out, newName, out.length(), "image/jpeg")
                    } finally {
                        if (oriented !== scaled) oriented.recycle()
                    }
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrDefault(picked)
    }

    /** 2 的幂次采样：先粗降尺寸，避免直接解码大图 OOM。 */
    private fun sampleSizeFor(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var longest = maxOf(w, h)
        while (longest / 2 >= maxDim) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleWithin(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        val nw = (src.width * ratio).toInt().coerceAtLeast(1)
        val nh = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    /** 相机拍的图方向存在 EXIF 里，解码后必须手动转正。 */
    private fun applyExifOrientation(file: File, src: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return src
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrDefault(src)
    }

    fun sha256Hex(file: File, cancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw InterruptedException("已取消")
                val n = ins.read(buf); if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 上传文件并返回文件元数据。
     * @param onProgress (已完成字节, 总字节) —— 用于进度条平滑动画
     */
    fun upload(
        picked: Picked,
        conversationId: String?,
        cancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): FileMeta {
        val file = picked.file
        try {
            val size = file.length()
        if (size <= 0) throw Api.ApiException(400, "文件为空")
        if (size > MAX_FILE_SIZE) throw Api.ApiException(413, "文件超过大小上限（2GB）")
        val hash = sha256Hex(file, cancelled)
        onProgress(0, size)

        // P1：跨重启断点续传 —— 同 hash 有未完成书签则尝试恢复上传会话
        var uploadId: String? = UploadBookmarks.take(hash)
        var chunkSize = 0
        var totalChunks = 0
        if (uploadId != null) {
            val st = runCatching { Api.uploadStatus(uploadId!!) }.getOrNull()
            // 服务端元数据必须自洽（分片大小/数量/文件大小），否则丢弃书签重新初始化。
            if (st != null && st.status == "uploading" && st.fileSize == size &&
                validChunkMeta(st.chunkSize, st.totalChunks, size)
            ) {
                chunkSize = st.chunkSize
                totalChunks = st.totalChunks
            } else {
                UploadBookmarks.remove(hash)
                uploadId = null
            }
        }
        if (uploadId == null) {
            val init = Api.filesInit(picked.name, size, picked.mime, hash, conversationId)
            if (init.instant && init.file != null) {
                onProgress(size, size)
                return init.file
            }
            uploadId = init.uploadId ?: throw Api.ApiException(500, "上传初始化失败")
            chunkSize = init.chunkSize.takeIf { it > 0 } ?: (8 * 1024 * 1024)
            totalChunks = init.totalChunks.takeIf { it > 0 }
                ?: ((size + chunkSize - 1) / chunkSize).toInt()
            if (!validChunkMeta(chunkSize, totalChunks, size)) {
                throw Api.ApiException(500, "服务端返回的分片参数不合法")
            }
            UploadBookmarks.save(hash, uploadId)
        }
        val uid: String = requireNotNull(uploadId)

        // 断点续传：查询服务端已收分片，跳过它们（越界索引一律忽略）
        val already = runCatching { Api.uploadStatus(uid).uploadedChunks }
            .getOrDefault(emptyList())
            .filter { it in 0 until totalChunks }
            .toSet()
        var done: Long = already.sumOf { idx -> chunkLen(idx, chunkSize, size, totalChunks).toLong() }
        onProgress(done, size)

        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunkSize)
            for (i in 0 until totalChunks) {
                if (cancelled()) { Api.abortUpload(uid); UploadBookmarks.remove(hash); throw InterruptedException("已取消") }
                val len = chunkLen(i, chunkSize, size, totalChunks)
                if (i in already) continue
                raf.seek(i.toLong() * chunkSize)
                var read = 0
                while (read < len) {
                    val n = raf.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
                val part = if (read == buf.size) buf else buf.copyOf(read)
                putChunkWithRetry(uid, i, part)
                done += read
                onProgress(done, size)
            }
        }
        val meta = Api.completeUpload(uid)
        UploadBookmarks.remove(hash)
        return meta
        } finally {
            // SAF 内容已复制到私有缓存；无论上传成功、取消还是失败都及时释放空间。
            file.delete()
        }
    }

    /** 校验服务端分片元数据：范围、数量上限以及与文件大小的自洽性。 */
    internal fun validChunkMeta(chunkSize: Int, totalChunks: Int, size: Long): Boolean {
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) return false
        if (totalChunks <= 0 || totalChunks > MAX_CHUNKS) return false
        if (size <= 0 || size > MAX_FILE_SIZE) return false
        val expected = (size + chunkSize - 1) / chunkSize
        return expected == totalChunks.toLong()
    }

    internal fun chunkLen(index: Int, chunkSize: Int, size: Long, total: Int): Int =
        if (index == total - 1) (size - index.toLong() * chunkSize).toInt() else chunkSize

    private fun putChunkWithRetry(uploadId: String, index: Int, bytes: ByteArray, maxRetry: Int = 3) {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < maxRetry) {
            try {
                // 与 Api 统一入口一样在发起时快照代际，401 只在本代际内才登出。
                val gen = Api.currentAuthGeneration()
                val req = Request.Builder()
                    .url("${Api.baseUrl.trimEnd('/')}/api/files/$uploadId/chunk?index=$index")
                    .addHeader("Authorization", "Bearer ${Api.token}")
                    .post(bytes.toRequestBody(OCTET))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Api.notifyHttpUnauthorized(resp.code, gen)
                        if (resp.code == 401) throw Api.ApiException(401, "登录状态已失效，请重新登录")
                        throw Api.ApiException(resp.code, "分片 $index 上传失败 (${resp.code})")
                    }
                }
                return
            } catch (e: Exception) {
                // 401 说明凭据已失效，重试无意义，直接中止。
                if (e is Api.ApiException && e.code == 401) throw e
                lastErr = e
                attempt++
                if (attempt < maxRetry) Thread.sleep(500L * attempt)
            }
        }
        throw lastErr ?: Api.ApiException(500, "分片 $index 上传失败")
    }

    /** 流式下载到 App 私有下载目录，返回本地文件。可取消。 */
    fun download(
        context: Context,
        fileId: String,
        fileName: String,
        cancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_").ifBlank { "download" }
        val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "downloads").apply { mkdirs() }
        val out = File(dir, safeName)
        val part = File(dir, "$safeName.part").apply { delete() }
        try {
        val gen = Api.currentAuthGeneration()
        val req = Request.Builder()
            .url("${Api.baseUrl.trimEnd('/')}/api/files/$fileId/download")
            .addHeader("Authorization", "Bearer ${Api.token}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Api.notifyHttpUnauthorized(resp.code, gen)
                if (resp.code == 401) throw Api.ApiException(401, "登录状态已失效，请重新登录")
                throw Api.ApiException(resp.code, "下载失败 (${resp.code})")
            }
            val body = resp.body ?: throw Api.ApiException(500, "下载响应为空")
            val total = body.contentLength()
            if (total > MAX_FILE_SIZE) throw Api.ApiException(413, "文件超过大小上限（2GB）")
            if (total > 0 && dir.usableSpace < total + MIN_FREE_SPACE_BYTES) {
                throw Api.ApiException(507, "设备存储空间不足")
            }
            var done = 0L
            body.byteStream().use { ins ->
                part.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { throw InterruptedException("已取消") }
                        val n = ins.read(buf); if (n < 0) break
                        done += n
                        // 服务端未给 Content-Length 时也必须限制累计写入，防止磁盘被打满。
                        if (done > MAX_FILE_SIZE) throw Api.ApiException(413, "文件超过大小上限（2GB）")
                        os.write(buf, 0, n)
                        onProgress(done, total)
                    }
                }
            }
        }
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) throw Api.ApiException(500, "下载文件保存失败")
        return out
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** 按完整 URL 下载（App 自更新 APK 用，走公开接口无需鉴权）。
     * 写入 .part 临时文件，校验 SHA-256 后才原子替换为可安装 APK；失败不保留损坏文件。 */
    fun downloadByUrl(
        context: Context,
        url: String,
        fileName: String,
        expectedSha256: String? = null,
        cancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_").ifBlank { "download" }
        val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, safeName)
        val part = File(dir, "$safeName.part").apply { delete() }
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Api.ApiException(resp.code, "下载失败 (${resp.code})")
                val body = resp.body ?: throw Api.ApiException(500, "下载响应为空")
                val total = body.contentLength()
                if (total > MAX_FILE_SIZE) throw Api.ApiException(413, "安装包超过大小上限")
                if (total > 0 && dir.usableSpace < total + MIN_FREE_SPACE_BYTES) {
                    throw Api.ApiException(507, "设备存储空间不足")
                }
                var done = 0L
                body.byteStream().use { ins ->
                    part.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw InterruptedException("已取消")
                            val n = ins.read(buf); if (n < 0) break
                            done += n
                            if (done > MAX_FILE_SIZE) throw Api.ApiException(413, "安装包超过大小上限")
                            os.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (expectedSha256.isNullOrBlank()) {
                throw Api.ApiException(422, "服务器未提供安装包校验信息，已拒绝安装")
            }
            if (!sha256Hex(part).equals(expectedSha256.trim(), ignoreCase = true)
            ) throw Api.ApiException(422, "安装包校验失败，请重新下载")
            // 先对临时包校验包名、签名和版本，再原子替换旧版本缓存。
            verifyUpdatePackage(context, part)
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw Api.ApiException(500, "下载文件保存失败")
            return out
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /** 校验下载的更新包只能覆盖当前包名、签名和更高版本。 */
    private fun verifyUpdatePackage(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val archive = (if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else pm.getPackageArchiveInfo(apk.absolutePath, flags))
            ?: throw Api.ApiException(422, "安装包无效")
        if (archive.packageName != context.packageName) {
            apk.delete()
            throw Api.ApiException(422, "安装包包名不匹配，已拒绝安装")
        }
        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else pm.getPackageInfo(context.packageName, flags)
        if (signerDigests(archive).isEmpty() || signerDigests(installed).isEmpty() ||
            signerDigests(archive).intersect(signerDigests(installed)).isEmpty()
        ) {
            apk.delete()
            throw Api.ApiException(422, "安装包签名与当前应用不一致，已拒绝安装")
        }
        val updateVersion = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION") archive.versionCode.toLong()
        }
        val currentVersion = if (Build.VERSION.SDK_INT >= 28) {
            installed.longVersionCode
        } else {
            @Suppress("DEPRECATION") installed.versionCode.toLong()
        }
        if (updateVersion <= currentVersion) {
            apk.delete()
            throw Api.ApiException(422, "安装包版本未升级，已拒绝安装")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.asList().orEmpty()
        } else info.signatures?.asList().orEmpty()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** 清理当前账号产生的本地下载、安装包与图片预览缓存；仅删除 App 私有目录。 */
    fun clearLocalDownloads(context: Context) {
        val root = context.getExternalFilesDir(null) ?: context.cacheDir
        listOf("downloads", "updates").forEach { name -> File(root, name).deleteRecursively() }
        File(context.cacheDir, "previews").deleteRecursively()
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "webp" -> "image/webp"; "mp4" -> "video/mp4"; "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "pdf" -> "application/pdf"
            "txt" -> "text/plain"; "md" -> "text/markdown"; "json" -> "application/json"
            "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
