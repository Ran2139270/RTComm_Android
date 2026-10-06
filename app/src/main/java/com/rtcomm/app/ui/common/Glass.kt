package com.rtcomm.app.ui.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 默认毛玻璃模糊半径。 */
private val DefaultBlurRadius = 26.dp

/** 是否启用真·毛玻璃：仅 Android 12+ 且用户未关闭且非低内存设备。 */
@Composable
fun rememberGlassEnabled(): Boolean {
    val reduceTransparency = LocalReduceTransparency.current
    val ctx = LocalContext.current
    return remember(reduceTransparency) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !reduceTransparency &&
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.isLowRamDevice != true
    }
}

/**
 * 可复用的「毛玻璃标题栏」容器：真正的背景高斯模糊（非单纯半透明）。
 *
 * 原理（Compose 1.7 GraphicsLayer）：
 *  1. [content] 正常绘制，同时被录制进一个 [androidx.compose.ui.graphics.layer.GraphicsLayer]；
 *  2. [header] 覆盖在顶部，在其边界内把该图层**带 RenderEffect 模糊后**绘制，再叠一层半透明着色。
 * 因此标题栏会实时模糊其正下方的滚动内容 / 壁纸，形成 iOS 式毛玻璃。
 *
 * 仅在 [rememberGlassEnabled] 为 true 时走毛玻璃路径；SDK<31 / 低内存 / 用户开启「减少毛玻璃」
 * 时自动降级为「半透明着色」（无模糊），避免无谓的全屏离屏合成。
 * 两个内容须是内部 Box 的直接同级、共用左上角原点（本组件已保证），
 * 这样模糊副本才能与背景精确对齐。可用于聊天页、详情页等任意需要毛玻璃标题栏的场景。
 *
 * @param headerHeight 标题栏总高度（应包含状态栏内边距）。
 * @param blurRadius 模糊半径（dp）。
 * @param tint 叠加着色（含透明度）。
 */
@Composable
fun GlassHeaderBox(
    headerHeight: Dp,
    modifier: Modifier = Modifier,
    blurRadius: Dp = DefaultBlurRadius,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    header: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    // 降级路径：不建 GraphicsLayer、不做离屏录制与高斯模糊，直接画内容 + 半透明着色标题栏。
    // 覆盖 SDK<31 / 低内存 / 用户主动关闭三种情况，避免白付一次全屏离屏往返。
    if (!rememberGlassEnabled()) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), content = content)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(headerHeight)
                    .background(tint),
                content = header,
            )
        }
        return
    }

    // 毛玻璃路径：此处已保证 SDK>=S，故删掉原来内层的 if (SDK >= S) 判断。
    val layer = rememberGraphicsLayer()
    val blurPx = with(LocalDensity.current) { blurRadius.toPx() }
    // 只录制/模糊标题栏覆盖的那条区域，而不是整屏内容——离屏录制成本从“全屏”降到“标题栏高度”。
    val headerPx = with(LocalDensity.current) { headerHeight.toPx() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record {
                        clipRect(top = 0f, bottom = headerPx) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    drawContent()
                },
            content = content,
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(headerHeight)
                .drawWithContent {
                    layer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    clipRect { drawLayer(layer) }
                    layer.renderEffect = null
                    drawContent()
                }
                .background(tint),
            content = header,
        )
    }
}
