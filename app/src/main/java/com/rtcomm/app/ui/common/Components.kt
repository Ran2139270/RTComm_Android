package com.rtcomm.app.ui.common

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.WsState
import com.rtcomm.app.ui.theme.AppColors

/** 群头像堆叠所需的最小信息。 */
data class AvatarSeed(val name: String, val avatarUrl: String? = null)

private fun colorFor(seed: String): Color {
    if (seed.isEmpty()) return AppColors.AvatarPalette[0]
    val idx = (seed.hashCode() and 0x7fffffff) % AppColors.AvatarPalette.size
    return AppColors.AvatarPalette[idx]
}

private fun initials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().uppercaseChar().toString()
}

/** 拼出可加载的头像完整地址；空串/相对路径都能处理。 */
/**
 * 是否使用圆角方形头像。由主题根部注入一次，避免每个头像各自订阅 StateFlow
 * （长列表里每行一个订阅会带来可观开销，也是会话列表滚动卡顿的诱因）。
 */
val LocalRoundedAvatars = androidx.compose.runtime.staticCompositionLocalOf { false }

private fun avatarRequest(context: android.content.Context, avatarUrl: String?): ImageRequest? =
    avatarUrl?.takeIf { it.isNotBlank() }?.let { path ->
        val full = if (path.startsWith("http")) path else Api.baseUrl.trimEnd('/') + path
        ImageRequest.Builder(context).data(full).crossfade(150).build()
    }

/**
 * 首字母头像（无网络图片依赖），可选在线小圆点、机器人标记与自定义头像。
 *
 * [group] 非空时渲染群头像：把成员头像拼成 2×2 网格（最多 4 个），
 * 与 QQ/微信的群聊头像一致；没有成员头像时退回首字母。
 * [onClick] 非空时整块可点击（用于打开头像查看/修改弹层）。
 */
@Composable
fun InitialsAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    online: Boolean? = null,
    isBot: Boolean = false,
    /** 自定义头像地址（相对 `/api/...` 或完整 URL）；为空时显示首字母。 */
    avatarUrl: String? = null,
    /** 群头像成员列表；非空时忽略 [avatarUrl] 走拼图。 */
    group: List<AvatarSeed>? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val roundedAvatars = LocalRoundedAvatars.current
    val avatarShape: androidx.compose.ui.graphics.Shape =
        if (roundedAvatars) RoundedCornerShape(size * 0.3f) else CircleShape
    // 可点击头像必须带可读标签与按钮角色，否则 TalkBack 会读成「未命名的可点区域」。
    val clickModifier = if (onClick != null) {
        Modifier
            .clickable(onClickLabel = "查看头像", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = name }
    } else {
        Modifier
    }
    Box(modifier = modifier.size(size).then(clickModifier)) {
        val groupCover = remember(avatarUrl) { avatarRequest(context, avatarUrl) }
        if (group != null && group.isNotEmpty()) {
            // 群已设置自定义头像时优先展示，否则回退成员拼图。
            GroupAvatar(members = group, size = size, cover = groupCover)
        } else {
            val avatarModel = remember(avatarUrl) { avatarRequest(context, avatarUrl) }
            Box(
                Modifier.size(size).clip(avatarShape).background(colorFor(name)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isBot) "\uD83E\uDD16" else initials(name),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.42f).sp,
                )
            }
            // 自定义头像覆盖在首字母之上；加载失败/未完成时自然露出首字母兜底。
            if (avatarModel != null) {
                AsyncImage(
                    model = avatarModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(avatarShape),
                )
            }
        }
        if (online != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size * 0.20f)
                        .clip(CircleShape)
                        .background(if (online) AppColors.Online else AppColors.Offline),
                )
            }
        }
    }
}

/** 群头像：最多 4 个成员头像的 2×2 拼图；设置了自定义群头像时直接显示整图。 */
@Composable
private fun GroupAvatar(members: List<AvatarSeed>, size: Dp, cover: Any? = null) {
    val context = LocalContext.current
    val roundedAvatars = LocalRoundedAvatars.current
    val avatarShape: androidx.compose.ui.graphics.Shape =
        if (roundedAvatars) RoundedCornerShape(size * 0.3f) else CircleShape
    val shown = members.take(4)
    Box(Modifier.size(size).clip(avatarShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 2) {
                        val idx = row * 2 + col
                        val seed = shown.getOrNull(idx)
                        Box(
                            Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (seed == null) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                            } else {
                                val req = remember(seed.avatarUrl) { avatarRequest(context, seed.avatarUrl) }
                                Box(
                                    Modifier.fillMaxSize().background(colorFor(seed.name)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        initials(seed.name),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (size.value * 0.20f).sp,
                                    )
                                }
                                if (req != null) {
                                    AsyncImage(
                                        model = req,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** 只读元信息标签（provider / personality / 在线状态等），不可点击，避免误导。 */
@Composable
fun MetadataTag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(color = container, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 空状态：图标 + 标题 + 副标题，淡入。 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = rememberReducedMotion()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = true, enter = fadeIn(tween(if (reducedMotion) 0 else 240))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.md))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null) {
                    Spacer(Modifier.height(Space.xs))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (action != null) { Spacer(Modifier.height(Space.lg)); action() }
            }
        }
    }
}

/**
 * 列表加载骨架屏：几条占位行 + 缓慢扫过的微光，代替单个转圈。
 * 骨架贴合列表行结构（头像圆 + 两行文本），内容出现时视觉更连续。
 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    // 减弱动画时根本不启动无限动画（旧实现只是把值固定，动画仍在后台空转）。
    // 保持为 State：在绘制期读取，动画每帧只重绘、不重组。
    val progress: State<Float> = if (reduced) {
        remember { mutableStateOf(0.35f) }
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "skeleton-sweep",
        )
    }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    // 微光画在 drawWithCache 里：进度在绘制期读取，Brush 按尺寸缓存，避免每帧重组+重建 Brush。
    fun Modifier.shimmer(shape: Shape): Modifier = this
        .clip(shape)
        .drawWithCache {
            onDrawBehind {
                val p = progress.value
                val w = size.width
                val cx = (p * 2f - 0.5f) * w
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = androidx.compose.ui.geometry.Offset(cx - w / 2f, 0f),
                        end = androidx.compose.ui.geometry.Offset(cx + w / 2f, size.height),
                    ),
                )
            }
        }

    Column(modifier.fillMaxSize().padding(top = 6.dp)) {
        repeat(7) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).shimmer(CircleShape))
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(14.dp).shimmer(RoundedCornerShape(7.dp)))
                    Spacer(Modifier.height(Space.sm))
                    Box(Modifier.fillMaxWidth(0.82f).height(11.dp).shimmer(RoundedCornerShape(Corner.chip)))
                }
            }
        }
    }
}

/** 错误横幅：出现时轻微 shake 强调，附重试按钮。 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean? = null,
    onRetry: (() -> Unit)? = null,
) {
    val motionReduced = reducedMotion ?: rememberReducedMotion()
    val offsetX = remember { Animatable(0f) }
    LaunchedEffect(message) {
        if (motionReduced) return@LaunchedEffect
        val steps = listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f)
        for (s in steps) offsetX.animateTo(s, tween(45, easing = LinearEasing))
    }
    // 出现时淡入 + 纵向展开，替代硬跳出；shake 仍由 offsetX 驱动。
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(if (motionReduced) 0 else 180)) +
            expandVertically(tween(if (motionReduced) 0 else 220)),
    ) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(Corner.thumb))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .offset(x = offsetX.value.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** 连接状态胶囊（颜色随状态过渡）。 */
@Composable
fun ConnectionChip(state: WsState, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val (label, dot) = when (state) {
        WsState.Connected -> stringResource(R.string.status_online) to AppColors.Online
        WsState.Connecting -> "连接中" to AppColors.Warning
        WsState.Disconnected -> stringResource(R.string.status_offline) to AppColors.Offline
    }
    val animColor by animateColorAsState(dot, tween(if (reducedMotion) 0 else 220), label = "conn")
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(animColor))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 三点“正在输入 / AI 生成中”动画。 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    reducedMotion: Boolean = false,
) {
    if (reducedMotion) {
        Text("…", modifier = modifier, color = color)
        return
    }
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 统一对话框：在平台 `Dialog` 之上自绘，提供「淡入 + 轻微放大」的入场动画。
 *
 * 平台 `AlertDialog` 的出现动画由系统窗口决定、无法自定义；这里用 [DialogAppear]
 * 包裹自绘 Surface，让兑换、确认、表单等弹窗都有统一的入场手感。
 * 参数与 Material3 `AlertDialog` 的基础用法保持一致，便于替换。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Corner.extra),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogAppear {
            Surface(
                shape = shape,
                color = containerColor,
                tonalElevation = 6.dp,
                modifier = modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth(0.92f),
            ) {
                Column(Modifier.padding(top = 24.dp)) {
                    if (title != null) {
                        Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp)) { title() }
                    }
                    if (text != null) {
                        Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) { text() }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * 统一的破坏性操作二次确认对话框：文案需写明影响面。
 * 防误触（作废邀请码 / 踢下线 / 清空缓存 / 切换服务器等）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确认",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * 状态淡切：在「加载骨架 / 空状态 / 内容」之间做交叉淡入淡出，替代 `when` 的瞬间切换。
 * 传入一个可比较的状态键（如 "loading"/"empty"/"content"），内容随之淡变。
 */
@Composable
fun <T> FadeSwap(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val reduced = rememberReducedMotion()
    androidx.compose.animation.Crossfade(
        targetState = target,
        modifier = modifier,
        animationSpec = tween(if (reduced) 0 else 200),
        label = "fadeSwap",
    ) { content(it) }
}

/**
 * 展开/收起的显隐动画：用于「点开关后出现的二级内容」（设置子项、回复栏、搜索框等），
 * 以纵向展开 + 淡入代替瞬间出现；系统「减少动态效果」时退化为瞬时。
 */
@Composable
fun Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(if (reduced) 0 else 180)) + expandVertically(tween(if (reduced) 0 else 220)),
        exit = fadeOut(tween(if (reduced) 0 else 140)) + shrinkVertically(tween(if (reduced) 0 else 180)),
    ) { content() }
}

/** 顶部内容淡入包装（页面进入动画）。 */
@Composable
fun FadeInColumn(content: @Composable () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    AnimatedVisibility(
        visible = true,
        enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 12 },
    ) {
        content()
    }
}

/**
 * 自实现 `Dialog { ... }` 的统一入场：淡入 + 轻微放大。
 *
 * 平台 `AlertDialog` 的出现动画由系统窗口提供，这里只服务自绘弹层
 * （实时终端、头像全屏、加载遮罩等），避免它们「啪」地瞬间出现。
 * 系统「减少动态效果」时直接到位。
 */
@Composable
fun DialogAppear(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) progress.animateTo(1f, tween(200, easing = Motion.StandardEasing))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            val s = 0.92f + 0.08f * progress.value
            scaleX = s
            scaleY = s
        },
    ) { content() }
}

/**
 * 统一开关：选中滑块用表面色（浅色主题近白 / 深色主题深灰），
 * 避免品牌色主题下 Material3 默认的 `onPrimary`（可能是深墨色）出现「黑滑块」。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.surface,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
