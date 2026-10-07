package com.rtcomm.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 列表 → 详情 的共享元素展开/回收动画（聊天、AI 会话等复用）。
 *
 * 共享元素只有两个，且都在**同一个 drawBehind 块**里用同一个进度绘制：
 *  1. 卡片表面：矩形铺满全屏，圆角 16dp → 0dp。
 *  2. 标题：从列表卡片标题的**实测矩形**移动到详情页头部标题的**实测矩形**。
 *
 * 为什么必须实测：列表标题位置由 ListItem 内边距决定，头部标题位置受状态栏
 * 内边距影响。任何写死的偏移都会让动画第一帧对不上，看起来就是「卡/跳」。
 *
 * 性能约定：动画期间零重组。进度只在绘制期读取，标题文本只在打开时测量一次。
 */
internal class PlainVar<T>(var value: T)

internal class ExpandFlowState {
    var selectedId by mutableStateOf<String?>(null)
    var selectedTitle by mutableStateOf("")
    var busy by mutableStateOf(false)
    var detailMounted by mutableStateOf(false)

    /** 卡片矩形与卡片标题矩形（已换算为相对本组件原点），打开瞬间快照。 */
    var start by mutableStateOf<Rect?>(null)
    var startTitle by mutableStateOf<Rect?>(null)
    /** 卡片标题的可用宽度：保证共享标题的换行/省略与卡片内完全一致。 */
    var titleWidth by mutableFloatStateOf(0f)

    /** 详情页头部标题的实测矩形：由详情页在首帧上报，之后不再变化。 */
    val headerTitle = PlainVar<Rect?>(null)
    /** 本组件在 root 中的原点，用于坐标换算（大屏 rail 模式下有横向偏移）。 */
    val origin = PlainVar(Offset.Zero)

    /** 单一进度：进入与退出复用同一条时间线。 */
    val transition: Animatable<Float, AnimationVector1D> = Animatable(0f)
}

/** 卡片圆角 16dp；展开到全屏时收敛为 0。 */
private const val CARD_CORNER_DP = 16f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun eased(t: Float) = FastOutSlowInEasing.transform(t)

/** 平滑 S 曲线：两端导数为 0，起步与落位都不突兀。 */
private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

private fun titleProgress(t: Float) = smoothstep((t - 0.04f) / 0.84f)

private fun sharedAlpha(p: Float) = ((0.92f - p) / 0.40f).coerceIn(0f, 1f)

/**
 * @param titleOf 深链场景下根据 id 解析标题（普通点击由列表直接给出标题）
 * @param listContent 列表内容；回调 respectively 打开某项、上报某项容器矩形/标题矩形
 * @param detailContent 详情内容；回调 respectively 进度、上报头部标题矩形、关闭
 */
@Composable
fun ExpandFlow(
    titleOf: (String) -> String = { "" },
    listBottomPadding: Dp = 0.dp,
    deepLink: Flow<String?>? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onOverlayVisibleChange: (Boolean) -> Unit = {},
    listContent: @Composable (
        onOpen: (id: String, title: String) -> Unit,
        onBounds: (id: String, rect: Rect) -> Unit,
        onTitleBounds: (id: String, rect: Rect) -> Unit,
    ) -> Unit,
    detailContent: @Composable (
        id: String,
        progress: () -> Float,
        onHeaderTitleBounds: (Rect) -> Unit,
        onClose: () -> Unit,
    ) -> Unit,
) {
    // 系统「减少动态效果」时把时长压到 0，退化为瞬时呈现。
    val reducedMotion = rememberReducedMotion()
    val duration = if (reducedMotion) 0 else 400
    val scope = rememberCoroutineScope()
    val st = remember { ExpandFlowState() }

    // 普通 HashMap：卡片几何写入不会触发任何重组。
    val boundsMap = remember { HashMap<String, Rect>() }
    val titleMap = remember { HashMap<String, Rect>() }

    // duration 会随「减少动态效果」开关变化；用 rememberUpdatedState 让下面 remember 的
    // 稳定 lambda 始终读到最新值，而不是首次组合时捕获的旧值。
    val currentDuration = rememberUpdatedState(duration)
    val onOpen: (String, String) -> Unit =
        remember { { id: String, title: String -> openDetail(st, id, title, boundsMap, titleMap, scope, currentDuration.value) } }
    val onBounds: (String, Rect) -> Unit = remember { { id, rect -> boundsMap[id] = rect } }
    val onTitleBounds: (String, Rect) -> Unit = remember { { id, rect -> titleMap[id] = rect } }
    val onHeaderTitleBounds: (Rect) -> Unit = remember { { rect -> st.headerTitle.value = rect } }
    val onClose: () -> Unit = remember { { closeDetail(st, boundsMap, titleMap, scope, currentDuration.value) } }
    // 稳定引用：否则详情页会随本组件重组而重组，动画中白白多一次整页重组。
    val progressProvider: () -> Float = remember { { eased(st.transition.value) } }

    // 覆盖层可见性上报（MainScaffold 据此隐藏底栏）。
    //
    // 注意：这里**不**用 rememberSaveable 持久化「已打开的详情」。底部标签用
    // saveState/restoreState 切换，一旦持久化，返回该标签时会自动恢复上次打开的会话
    // （表现为「点底栏切到会话/AI 就莫名打开一个会话」）。而旋转等配置变化由
    // MainActivity 的 configChanges 直接处理、组合不重建，本就不需要恢复。
    LaunchedEffect(st.selectedId) {
        onOverlayVisibleChange(st.selectedId != null)
    }

    // 深链：走与点卡片完全相同的展开路径（标题在此之前会被解析）。
    if (deepLink != null) {
        LaunchedEffect(Unit) {
            deepLink.collect { id ->
                if (id != null) {
                    openDetail(st, id, titleOf(id), boundsMap, titleMap, scope, duration, deepLinkOpen = true)
                    onDeepLinkConsumed()
                }
            }
        }
    }

    // 系统返回键 / 返回手势：与关闭按钮共用同一条反向时间线。
    BackHandler(enabled = st.selectedId != null) {
        closeDetail(st, boundsMap, titleMap, scope, duration)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { st.origin.value = it.positionInRoot() },
    ) {
        // 关键性能点：给列表一个自己的 RenderNode，避免动画每帧重录整张列表。
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = listBottomPadding)
                .graphicsLayer(),
        ) {
            listContent(
                { id, title -> onOpen(id, title) },
                onBounds,
                onTitleBounds,
            )
        }

        val active = st.selectedId
        if (active != null) {
            // 层 1：卡片表面 + 共享标题（纯绘制，无额外图层、无布局、无重组）。
            ExpansionLayer(state = st)

            // 层 2：真实详情内容，入场动画全部在 graphicsLayer 内读进度。
            if (st.detailMounted) {
                detailContent(active, progressProvider, onHeaderTitleBounds, onClose)
            }
        }
    }
}

/** 把窗口坐标的几何换算成本组件内坐标并快照，供绘制期使用。 */
private fun captureGeometry(
    st: ExpandFlowState,
    id: String,
    title: String,
    boundsMap: Map<String, Rect>,
    titleMap: Map<String, Rect>,
) {
    val origin = st.origin.value
    st.start = boundsMap[id]?.translate(-origin.x, -origin.y)
    val titleRect = titleMap[id]
    st.startTitle = titleRect?.translate(-origin.x, -origin.y)
    if (titleRect != null) st.titleWidth = titleRect.width
    st.selectedTitle = title
}

private fun openDetail(
    st: ExpandFlowState,
    id: String,
    title: String,
    boundsMap: Map<String, Rect>,
    titleMap: Map<String, Rect>,
    scope: CoroutineScope,
    duration: Int,
    deepLinkOpen: Boolean = false,
) {
    if (st.busy) return
    if (st.selectedId == id) {
        if (deepLinkOpen) return
        return
    }
    // 已经在别的详情里（例如点通知深链）：直接切换，不因 selectedId 非空丢掉请求。
    val switching = st.selectedId != null
    captureGeometry(st, id, title, boundsMap, titleMap)

    st.selectedId = id
    st.detailMounted = true
    st.busy = true
    scope.launch {
        try {
            if (switching) {
                st.transition.snapTo(0f)
            } else {
                // 先让详情页完成首次组合/布局/绘制，再启动动画，避免起手一顿。
                withFrameNanos { }
                withFrameNanos { }
                st.transition.snapTo(0f)
            }
            st.transition.animateTo(1f, tween(duration, easing = LinearEasing))
        } finally {
            // 动画被取消时也要复位 busy，否则之后再也无法打开详情。
            st.busy = false
        }
    }
}

private fun closeDetail(
    st: ExpandFlowState,
    boundsMap: MutableMap<String, Rect>,
    titleMap: MutableMap<String, Rect>,
    scope: CoroutineScope,
    duration: Int,
) {
    if (st.busy || st.selectedId == null) return
    // 旋转或进程重建后 st 会被重建，起始几何丢失；此时补一次快照。
    if (st.start == null) {
        val id = st.selectedId
        if (id != null) captureGeometry(st, id, st.selectedTitle, boundsMap, titleMap)
    }
    st.busy = true
    scope.launch {
        try {
            st.transition.animateTo(0f, tween(duration, easing = LinearEasing))
        } finally {
            st.detailMounted = false
            st.selectedId = null
            st.busy = false
            // 关闭后释放几何快照，避免列表项很多时长期持有。
            boundsMap.clear()
            titleMap.clear()
        }
    }
}

/**
 * 消费掉落在本层上的所有触摸：展开覆盖层是「画」在列表之上的，本身没有可点击内容，
 * 若不拦截，空白处的点击/滑动会穿透到下方仍可点击的会话行，误开别的会话。
 * 详情内容在本层之后绘制，其自身的可点击区仍能正常命中。
 */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * 唯一的绘制节点：背景圆角矩形 + 共享标题，绘制期同一进度驱动。
 */
@Composable
private fun ExpansionLayer(state: ExpandFlowState) {
    val bg = MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onBackground
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)

    val titleLayout = remember(state.selectedTitle, state.titleWidth, titleStyle) {
        val maxWidth = state.titleWidth.roundToInt()
        measurer.measure(
            text = AnnotatedString(state.selectedTitle),
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = if (maxWidth > 1) Constraints(maxWidth = maxWidth) else Constraints(),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .blockTouches()
            .drawBehind {
                val t = state.transition.value
                val p = eased(t)
                val tp = titleProgress(t)
                val s = state.start

                val left = if (s != null) lerp(s.left, 0f, p) else 0f
                val top = if (s != null) lerp(s.top, 0f, p) else 0f
                val right = if (s != null) lerp(s.right, size.width, p) else size.width
                val bottom = if (s != null) lerp(s.bottom, size.height, p) else size.height
                val radius = if (s != null) lerp(CARD_CORNER_DP.dp.toPx(), 0f, p) else 0f
                drawRoundRect(
                    color = bg,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(radius, radius),
                )

                val from = state.startTitle
                val alpha = sharedAlpha(p)
                if (from != null && alpha > 0f) {
                    val origin = state.origin.value
                    val to = state.headerTitle.value?.translate(-origin.x, -origin.y)
                    val tx = lerp(from.left, to?.left ?: from.left, tp)
                    val ty = lerp(from.top, to?.top ?: from.top, tp)
                    clipRect(left = left, top = top, right = right, bottom = bottom) {
                        drawText(
                            textLayoutResult = titleLayout,
                            color = onBg,
                            topLeft = Offset(tx, ty),
                            alpha = alpha,
                        )
                    }
                }
            },
    )
}
