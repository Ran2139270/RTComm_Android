package com.rtcomm.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rtcomm.app.ui.common.FadeSwap

/** 可选聊天背景（内置，无需下载图片）。 */
enum class ChatWallpaper(val key: String, val label: String) {
    Default("default", "默认"),
    Warm("warm", "暖阳"),
    Cool("cool", "冷调"),
    Mint("mint", "薄荷"),
    Dusk("dusk", "暮色"),
    Grid("grid", "网格"),
    Dots("dots", "点阵"),
    Diagonal("diagonal", "斜纹"),
    Sunset("sunset", "晚霞"),
    Paper("paper", "纸张");

    companion object {
        fun from(key: String?): ChatWallpaper = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * 聊天背景层：在消息列表下方绘制一层柔和背景。
 *
 * 用绘制而非图片，避免额外资源与下载；颜色取自主题并叠加低透明度，
 * 保证浅色/深色下消息气泡都清晰可读。
 */
@Composable
fun ChatWallpaperBackground(style: ChatWallpaper, modifier: Modifier = Modifier) {
    // 壁纸样式切换时淡切，替代瞬间替换（含切回「默认」的淡出）。
    FadeSwap(target = style, modifier = modifier.fillMaxSize()) { s ->
        if (s != ChatWallpaper.Default) WallpaperLayer(s)
    }
}

@Composable
private fun WallpaperLayer(style: ChatWallpaper) {
    val scheme = MaterialTheme.colorScheme
    val base = scheme.background
    val a = scheme.primary
    val b = scheme.tertiary
    // 必须真正发射一个节点：之前只构造了 Modifier 链却没有挂到任何节点上，导致 9 种
    // 内置壁纸全部不渲染。这里用 Box 承载该修饰链。
    Box(
        Modifier
            .fillMaxSize()
            // 用 drawWithCache：按尺寸一次性构建渐变 Brush，避免每帧（滚动/动画）重复分配。
            .drawWithCache {
            val warm = Brush.linearGradient(
                listOf(base, Color(0xFFFFE9D5).copy(alpha = 0.55f), base),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            val cool = Brush.linearGradient(
                listOf(base, Color(0xFFDCEBFF).copy(alpha = 0.55f), base),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            val mint = Brush.linearGradient(
                listOf(base, Color(0xFFD8F5E6).copy(alpha = 0.55f), base),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
            )
            val dusk = Brush.linearGradient(
                listOf(a.copy(alpha = 0.18f), base, b.copy(alpha = 0.18f)),
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
            )
            val sunset = Brush.verticalGradient(
                listOf(Color(0xFFFFE0B2).copy(alpha = 0.6f), base, Color(0xFFB39DDB).copy(alpha = 0.4f)),
            )
            onDrawBehind {
            when (style) {
                ChatWallpaper.Warm -> drawRect(warm)
                ChatWallpaper.Cool -> drawRect(cool)
                ChatWallpaper.Mint -> drawRect(mint)
                ChatWallpaper.Dusk -> drawRect(dusk)
                ChatWallpaper.Grid -> {
                    drawRect(base)
                    val step = 28.dp.toPx()
                    val line = scheme.outlineVariant.copy(alpha = 0.5f)
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += step
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
                ChatWallpaper.Dots -> {
                    drawRect(base)
                    val step = 22.dp.toPx()
                    val dot = scheme.outlineVariant.copy(alpha = 0.6f)
                    var y = step / 2f
                    while (y < size.height) {
                        var x = step / 2f
                        while (x < size.width) {
                            drawCircle(dot, radius = 1.6f, center = Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                ChatWallpaper.Diagonal -> {
                    drawRect(base)
                    val step = 26.dp.toPx()
                    val line = scheme.outlineVariant.copy(alpha = 0.45f)
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(line, Offset(x, 0f), Offset(x + size.height, size.height), strokeWidth = 1f)
                        x += step
                    }
                }
                ChatWallpaper.Sunset -> drawRect(sunset)
                ChatWallpaper.Paper -> {
                    drawRect(base)
                    val step = 24.dp.toPx()
                    val line = scheme.outlineVariant.copy(alpha = 0.35f)
                    var y = step
                    while (y < size.height) {
                        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }
                ChatWallpaper.Default -> Unit
            }
            }
            },
    )
}
