package com.rtcomm.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 全局「减少动态效果」开关。
 *
 * 由 [com.rtcomm.app.ui.theme.RtcommTheme] 在根部提供一次，
 * 组件直接读取即可。之前每个组件/每个列表项都调用 `Format.animationsReduced(context)`，
 * 会反复读 `Settings.Global`（列表项里是每行一次），既慢又容易不一致。
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** 读取全局减少动态效果标记（主题根部已注入）。 */
@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current

/**
 * 气泡圆角样式（small/medium/large），由主题根部注入一次。
 * 之前每个气泡都 `AppState.bubbleCorner.collectAsState()`，长列表里每条消息一个订阅；
 * 圆角属于全局主题，用 CompositionLocal 下发即可，无需逐条订阅。
 */
val LocalBubbleCorner = staticCompositionLocalOf { "medium" }

/**
 * 全局「减少透明 / 毛玻璃」开关，由主题根部注入一次。
 * true = 关闭实时高斯模糊，标题栏退化为纯半透明着色（省一次全屏离屏合成 + 模糊）。
 */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/**
 * App-wide motion durations. Keep transitions short enough to acknowledge an action
 * without delaying the next interaction.
 */
data class MotionSpec(
    val reduced: Boolean,
    val quick: Int,
    val standard: Int,
    val emphasized: Int,
)

@Composable
fun rememberMotionSpec(): MotionSpec {
    val reduced = LocalReducedMotion.current
    return remember(reduced) {
        MotionSpec(
            reduced = reduced,
            quick = if (reduced) 0 else Motion.Quick,
            standard = if (reduced) 0 else Motion.Standard,
            emphasized = if (reduced) 0 else Motion.Emphasized,
        )
    }
}

/**
 * 统一动效常量。所有时长/缓动集中在这里，避免各页面各写一套数字。
 * 时长参考 Material Motion：短反馈 ~120ms，常规过渡 ~200ms，强调 ~280-400ms。
 */
object Motion {
    const val Quick = 120
    const val Standard = 200
    const val Emphasized = 280
    const val Expand = 400

    /** 顶层 Tab 交叉淡入淡出：出入等时长，稍长于 [Standard] 以让交叠更顺滑。 */
    const val TabCrossfade = 220

    /**
     * 交互反馈弹簧参数：用于按压、选中、开关、发送键等可被快速打断的动作。
     * 阻尼略高（≈0.82）几乎不回弹，只让反馈“活”起来，不显得玩具化。
     */
    const val SpringDamping = 0.82f
    const val SpringStiffness = 900f

    /** 稍柔和的弹簧：用于尺寸/位置强调（展开、切换指示器），允许轻微回弹。 */
    const val SpringGentleDamping = 0.72f
    const val SpringGentleStiffness = 420f

    /** 标准缓动（加速后减速）。 */
    val StandardEasing: Easing = FastOutSlowInEasing

    /** 强调出场：起步稍快、落位柔和。 */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 进场：缓慢起步。 */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 出场：快速离开。 */
    val ExitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 按减少动态效果降级时长。 */
    fun duration(reduced: Boolean, ms: Int): Int = if (reduced) 0 else ms
}

/**
 * A small, low-cost press acknowledgement for cards and bubbles. It observes pointer
 * input without consuming it, therefore click, long-press and drag gestures still work.
 */
@Composable
fun Modifier.motionPress(pressedScale: Float = 0.985f): Modifier {
    // 用可组合扩展而不是 Modifier.composed{}：后者不可跳过（skippable），
    // 在长列表里每个气泡/卡片都会走一遍 composition。
    val motion = rememberMotionSpec()
    if (motion.reduced) return this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Motion.SpringDamping,
            stiffness = Motion.SpringStiffness,
        ),
        label = "pressScale",
    )
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
