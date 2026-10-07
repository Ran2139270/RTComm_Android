package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.FadeSwap

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 1:1 头像裁切对话框。
 *
 * 相比旧版增加：
 *  - **圆形遮罩 + 三分参考线**：头像最终是圆的，按圆裁更直观；
 *  - **旋转 / 镜像 / 重置**；
 *  - **缩放滑块**（旧版只能双指，单手/鼠标不好用）；
 *  - 输出 512×512 JPEG（旧版 256，放大后偏糊）。
 *
 * 裁切状态（中心点 cx/cy 与边长 side）提升到本对话框，「确定」直接读取，
 * 不再经全局 `object` + `SideEffect` 同步：旧实现里平移只触发重绘、不触发重组，
 * `SideEffect` 不会执行，导致平移后的取景没写回，裁切用的仍是初始居中值（表现为「裁切不生效」）。
 */
@Composable
fun AvatarCropDialog(
    uri: android.net.Uri,
    onCancel: () -> Unit,
    onCropped: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var rotation by remember(uri) { mutableIntStateOf(0) }
    var flipped by remember(uri) { mutableStateOf(false) }
    var working by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        source = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1536) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
        }
    }

    // 旋转/镜像后得到工作位图；裁切逻辑始终作用在工作位图上。
    // 每次重算都回收上一张：原图可近 37MB（ARGB_8888），连续旋转不回收会 OOM。
    LaunchedEffect(source, rotation, flipped) {
        val src = source
        if (src == null || src.isRecycled) { working = null; return@LaunchedEffect }
        val next = withContext(Dispatchers.Default) { applyOrientation(src, rotation, flipped) }
        val prev = working
        working = next
        if (prev != null && prev !== src && prev !== next && !prev.isRecycled) prev.recycle()
    }

    // 弹窗关闭/切图时回收全部位图，避免对话框退出后仍持有大内存。
    val bitmaps = remember(uri) { arrayOfNulls<Bitmap>(2) }
    SideEffect { bitmaps[0] = source; bitmaps[1] = working }
    DisposableEffect(uri) {
        onDispose {
            bitmaps[0]?.takeIf { !it.isRecycled }?.recycle()
            bitmaps[1]?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    // 裁切状态跟随工作位图（旋转/镜像换图时按新尺寸复位为居中最大方形）。
    var cropSide by remember(working) { mutableFloatStateOf(working?.let { min(it.width, it.height).toFloat() } ?: 0f) }
    var cropCx by remember(working) { mutableFloatStateOf(working?.let { it.width / 2f } ?: 0f) }
    var cropCy by remember(working) { mutableFloatStateOf(working?.let { it.height / 2f } ?: 0f) }

    AppAlertDialog(
        onDismissRequest = onCancel,
        title = { Text("裁切头像") },
        text = {
            // 位图解码完成前显示转圈，完成后淡切到裁切编辑器。
            val bmp = working
            FadeSwap(target = bmp == null) { isLoading ->
                if (isLoading) {
                    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                } else {
                    val current = working
                    if (current != null) {
                        CropEditor(
                            bmp = current,
                            cx = cropCx,
                            cy = cropCy,
                            side = cropSide,
                            onChange = { x, y, s -> cropCx = x; cropCy = y; cropSide = s },
                            onRotate = { rotation = (rotation + 90) % 360 },
                            onFlip = { flipped = !flipped },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = working != null, onClick = {
                val b = working ?: return@TextButton
                val cx = cropCx; val cy = cropCy; val side = cropSide
                scope.launch {
                    val dataUrl = withContext(Dispatchers.IO) { cropToDataUrl(b, cx, cy, side) }
                    if (dataUrl != null) onCropped(dataUrl) else onCancel()
                }
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 旋转 [degrees]（90 的倍数）并可选水平镜像，返回新位图。 */
private fun applyOrientation(src: Bitmap, degrees: Int, flipH: Boolean): Bitmap {
    val m = Matrix()
    m.postRotate(degrees.toFloat())
    if (flipH) m.postScale(-1f, 1f)
    if (degrees == 0 && !flipH) return src
    return runCatching {
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrDefault(src)
}

/** 从工作位图按中心点 [cx]/[cy] 与边长 [side] 裁出方形，缩放到 512×512 输出 JPEG dataUrl。 */
private fun cropToDataUrl(bmp: Bitmap, cx: Float, cy: Float, side: Float): String? = runCatching {
    val s = side.toInt().coerceIn(1, min(bmp.width, bmp.height))
    val x = (cx - s / 2f).toInt().coerceIn(0, (bmp.width - s).coerceAtLeast(0))
    val y = (cy - s / 2f).toInt().coerceIn(0, (bmp.height - s).coerceAtLeast(0))
    val cropped = Bitmap.createBitmap(bmp, x, y, s, s)
    val out = Bitmap.createScaledBitmap(cropped, 512, 512, true)
    val bytes = java.io.ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
    if (cropped !== bmp) cropped.recycle()
    if (out !== cropped) out.recycle()
    "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
}.getOrNull()

@Composable
private fun CropEditor(
    bmp: Bitmap,
    cx: Float,
    cy: Float,
    side: Float,
    onChange: (cx: Float, cy: Float, side: Float) -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
) {
    val imgW = bmp.width.toFloat()
    val imgH = bmp.height.toFloat()
    val maxSide = min(imgW, imgH)
    val image = remember(bmp) { bmp.asImageBitmap() }
    // pointerInput 只在 bmp 变化时重启,手势 lambda 会捕获旧的 cx/cy/side;
    // 用 updatedState 让手势内始终读到最新取景,再经 onChange 写回提升后的状态。
    val current = rememberUpdatedState(Triple(cx, cy, side))

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(Corner.medium))
                .background(Color.Black)
                .pointerInput(bmp) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val (curCx, curCy, curSide) = current.value
                        val viewS = size.width.toFloat().coerceAtLeast(1f)
                        val newSide = (curSide / zoom).coerceIn(maxSide / 8f, maxSide)
                        val ratio = newSide / viewS
                        val nx = (curCx - pan.x * ratio).coerceIn(newSide / 2f, imgW - newSide / 2f)
                        val ny = (curCy - pan.y * ratio).coerceIn(newSide / 2f, imgH - newSide / 2f)
                        onChange(nx, ny, newSide)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val s = side
                val left = (cx - s / 2f).coerceIn(0f, imgW - s)
                val top = (cy - s / 2f).coerceIn(0f, imgH - s)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(left.toInt(), top.toInt()),
                    srcSize = IntSize(s.toInt(), s.toInt()),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
                // 圆形遮罩：圆外压暗，明确“最终只保留圆内”。
                val radius = min(size.width, size.height) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val scrim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
                }
                drawPath(scrim, Color.Black.copy(alpha = 0.5f))
                // 三分参考线。
                val lineColor = Color.White.copy(alpha = 0.35f)
                val w = size.width
                val h = size.height
                for (i in 1..2) {
                    val x = w * i / 3f
                    val y = h * i / 3f
                    drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                // 圆环。
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = radius - 1f,
                    center = center,
                    style = Stroke(width = 2f),
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRotate) { Icon(Icons.Filled.Rotate90DegreesCcw, contentDescription = "旋转") }
            IconButton(onClick = onFlip) { Icon(Icons.Filled.Flip, contentDescription = "镜像") }
            Text("缩放", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = (maxSide / side).coerceIn(1f, 8f),
                onValueChange = { v ->
                    val newSide = (maxSide / v).coerceIn(maxSide / 8f, maxSide)
                    val nx = cx.coerceIn(newSide / 2f, imgW - newSide / 2f)
                    val ny = cy.coerceIn(newSide / 2f, imgH - newSide / 2f)
                    onChange(nx, ny, newSide)
                },
                valueRange = 1f..8f,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                onChange(imgW / 2f, imgH / 2f, maxSide)
            }) { Icon(Icons.Filled.Refresh, contentDescription = "重置") }
        }
    }
}
