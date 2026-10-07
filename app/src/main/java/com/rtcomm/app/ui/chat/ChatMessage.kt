package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.Message
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.common.rememberReducedMotion
import com.rtcomm.app.ui.theme.Alpha
import com.rtcomm.app.ui.theme.Corner
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 从 ChatScreen.kt 拆出的消息气泡与分隔线（同包 internal，供 ChatScreen 调用）。

/** 日期分隔：聊天中每天一条，居中弱化显示。 */
@Composable
internal fun DateDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(Corner.thumb),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** 未读分界：reverseLayout 下放在首条未读消息的顶部，正好把已读与新消息分开。 */
@Composable
internal fun UnreadDivider() {
    val color = MaterialTheme.colorScheme.error
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = Alpha.divider))
        Text(
            "以下为新消息",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = color.copy(alpha = Alpha.divider))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    m: Message,
    isMe: Boolean,
    isGroup: Boolean,
    modifier: Modifier = Modifier,
    onDownload: (com.rtcomm.app.data.MessageFile) -> Unit,
    onReply: (Message) -> Unit,
    onQuotedMessageClick: (String) -> Unit,
    onLongPress: () -> Unit,
    onAvatarClick: (com.rtcomm.app.data.PublicUser) -> Unit = {},
    onImageClick: (com.rtcomm.app.data.MessageFile) -> Unit = {},
    onCancelUpload: (Message) -> Unit = {},
    onRetryUpload: (Message) -> Unit = {},
    onToggleSelect: (String) -> Unit = {},
    /** 右滑：进入多选并选中该消息。 */
    onSelect: (String) -> Unit = {},
    /** 多选模式下的选中态；进入多选后单击=切换选中，长按不再弹菜单。 */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** 紧凑消息密度（个性化）。 */
    compact: Boolean = false,
) {
    val who = m.sender?.displayName ?: m.senderId.take(6)
    val isBot = m.sender?.isBot == true
    val reducedMotion = rememberReducedMotion()
    // 气泡最大宽度按屏幕比例，但设置上限：手机上仍是屏幕的 76%，平板/桌面下不会拉成一整行。
    val maxBubbleWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.76f)
        .coerceAtMost(560f).dp
    /**
     * 拖动偏移拆成两段：
     *  - [dragX] 拖动中的实时值，**只在布局/绘制期读取**。之前它在组合期被读了两次
     *    （animateFloatAsState 的目标值 + align 判断），拖动时每帧重组整条消息。
     *  - [settle] 松手后接管偏移并用 spring 消化到 0，取代原来 `dragX = 0f` 的瞬移。
     */
    var dragX by remember(m.id) { mutableFloatStateOf(0f) }
    val settle = remember(m.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // 非可观察持有器：拖动期间写它不会触发重组。
    val thresholdHit = remember(m.id) { Flag() }
    // 阈值按 dp 换算，避免高 DPI 设备上「轻轻一划就触发回复」。
    val replyThreshold = with(density) { 72.dp.toPx() }
    val dragLimit = with(density) { 112.dp.toPx() }
    val iconRevealDistance = with(density) { 96.dp.toPx() }

    fun settleBack() {
        val from = dragX
        scope.launch {
            // 先让 settle 接管当前偏移，再把实时值清零——同一帧内完成，不会闪一下。
            settle.snapTo(from)
            dragX = 0f
            settle.animateTo(0f, spring(dampingRatio = 0.80f, stiffness = 620f))
        }
    }

    // 左滑引用、右滑多选；阈值足够高，避免浏览消息时误触。本地未发送成功的消息不可回复/多选。
    val replySwipe = Modifier.pointerInput(m.id, m.isDeleted, m.isLocalPending, selectionMode) {
        if (!selectionMode && !m.isDeleted && !m.isLocalPending) detectHorizontalDragGestures(
            onDragStart = { thresholdHit.value = false },
            onHorizontalDrag = { change, amount ->
                change.consume()
                dragX = (dragX + amount).coerceIn(-dragLimit, dragLimit)
                // 首次越过阈值给一次触感反馈，越过后再往回滑不会重复震动。
                if (!thresholdHit.value && kotlin.math.abs(dragX) >= replyThreshold) {
                    thresholdHit.value = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDragEnd = {
                when {
                    dragX >= replyThreshold -> onSelect(m.id)   // 右滑：进入多选并选中
                    dragX <= -replyThreshold -> onReply(m)      // 左滑：引用回复
                }
                settleBack()
            },
            onDragCancel = { settleBack() },
        )
    }
    Box(modifier.fillMaxWidth().padding(vertical = if (compact) 1.dp else 3.dp)) {
        if (!m.isDeleted) {
            // 两个方向各一个提示图标，靠绘制期 alpha 控制显隐，避免组合期读拖动量。
            // 右滑（内容右移）露出多选图标；左滑露出引用图标。
            Icon(
                imageVector = Icons.Filled.Checklist,
                contentDescription = "滑动多选",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 18.dp)
                    .graphicsLayer {
                        val d = dragX + settle.value
                        alpha = if (d > 0f) (d / iconRevealDistance).coerceIn(0f, 1f) else 0f
                    },
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
                    .graphicsLayer {
                        val d = dragX + settle.value
                        alpha = if (d < 0f) (-d / iconRevealDistance).coerceIn(0f, 1f) else 0f
                    },
            )
        }
        Row(
            replySwipe
                .fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset((dragX + settle.value).roundToInt(), 0) },
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
        if (!isMe) {
            InitialsAvatar(
                name = who,
                size = 34.dp,
                isBot = isBot,
                avatarUrl = m.sender?.avatarUrl,
                onClick = m.sender?.let { s -> { onAvatarClick(s) } },
            )
            Spacer(Modifier.width(Space.sm))
        }
        Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = maxBubbleWidth)) {
            if (!isMe) {
                // 群聊才逐条重复昵称；私聊的昵称头部已经给了，重复显示反而吵。
                // 机器人始终保留 AI 标记，无论单聊还是群聊。
                val showName = isGroup
                if (showName || isBot) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showName) {
                            Text(who, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isBot) {
                            if (showName) Spacer(Modifier.width(Space.xs))
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(Corner.chip)) {
                                Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            // 选中描边与气泡共用同一个 shape，避免圆角不一致；圆角大小可个性化。
            // 由主题根部通过 CompositionLocal 下发，避免每个气泡各自订阅 StateFlow。
            val cornerStyle = com.rtcomm.app.ui.common.LocalBubbleCorner.current
            val big = when (cornerStyle) {
                "small" -> 10.dp
                "large" -> 24.dp
                else -> 18.dp
            }
            val tail = if (cornerStyle == "large") 6.dp else 5.dp
            val bubbleShape = if (isMe) {
                RoundedCornerShape(big, big, tail, big)
            } else {
                RoundedCornerShape(big, big, big, tail)
            }
            // 图片/视频消息去掉气泡底色与内边距：媒体本身无边框、无背景板。
            val compactMedia = !m.isDeleted && m.replyToMessageId == null &&
                (m.messageType == "image" || m.messageType == "video")
            val baseBubbleColor = when {
                compactMedia -> androidx.compose.ui.graphics.Color.Transparent
                m.isDeleted -> MaterialTheme.colorScheme.surfaceVariant
                isMe -> if (m.sendState == com.rtcomm.app.data.SendState.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary
                isBot -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            // 选中态：气泡叠加主色（媒体保持透明），配合高对比描边，深色主题下也一眼可辨。
            val bubbleColor = if (selected && !compactMedia) {
                lerp(baseBubbleColor, MaterialTheme.colorScheme.primary, 0.24f)
            } else baseBubbleColor
            Surface(
                color = bubbleColor,
                tonalElevation = if (isMe && !compactMedia) 1.dp else 0.dp,
                shadowElevation = if (isMe && !compactMedia) 1.dp else 0.dp,
                contentColor = when {
                    compactMedia -> MaterialTheme.colorScheme.onSurface
                    m.isDeleted -> MaterialTheme.colorScheme.onSurfaceVariant
                    isMe -> if (m.sendState == com.rtcomm.app.data.SendState.Failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                    isBot -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                shape = bubbleShape,
                modifier = Modifier
                    .motionPress(pressedScale = 0.992f)
                    // 选中描边用颜色过渡淡入淡出，替代边框硬切；未选中时描边透明不占视觉。
                    .border(
                        2.5.dp,
                        animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Transparent,
                            animationSpec = tween(if (reducedMotion) 0 else 160),
                            label = "selection-border",
                        ).value,
                        bubbleShape,
                    )
                    .combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect(m.id) },
                        onLongClick = { if (selectionMode) onToggleSelect(m.id) else onLongPress() },
                    ),
            ) {
                Column(if (compactMedia) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // P2：引用的原文预览
                    if (m.replyToMessageId != null && !m.isDeleted) {
                        // 引用预览的行内 Markdown 解析较重：按引用内容缓存，避免每次重组重算（滚动卡顿）。
                        val quoted = remember(
                            m.replyToMessageId,
                            m.replyTo?.id,
                            m.replyTo?.content,
                            m.replyTo?.isDeleted,
                            m.replyTo?.messageType,
                            m.replyTo?.senderId,
                        ) {
                            when {
                                m.replyTo?.isDeleted == true -> androidx.compose.ui.text.AnnotatedString("↩ 原消息已撤回")
                                m.replyTo != null -> {
                                    val r = m.replyTo
                                    val sender = r.sender?.displayName?.takeIf { it.isNotBlank() }
                                        ?: r.senderId.take(6).ifBlank { "对方" }
                                    val preview = when (r.messageType) {
                                        "image" -> androidx.compose.ui.text.AnnotatedString("[图片]")
                                        "video" -> androidx.compose.ui.text.AnnotatedString("[视频]")
                                        "audio" -> androidx.compose.ui.text.AnnotatedString("[语音]")
                                        "file" -> androidx.compose.ui.text.AnnotatedString("[文件] " + (r.file?.fileName ?: ""))
                                        else -> com.rtcomm.app.ui.common.Markdown.previewInline(r.content).let {
                                            if (it.text.isEmpty()) androidx.compose.ui.text.AnnotatedString("[消息]") else it
                                        }
                                    }
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        append("$sender：")
                                        append(preview)
                                    }
                                }
                                else -> androidx.compose.ui.text.AnnotatedString("↩ 引用消息")
                            }
                        }
                        Surface(
                            color = LocalContentColor.current.copy(alpha = Alpha.hairline),
                            shape = RoundedCornerShape(7.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onQuotedMessageClick(m.replyToMessageId) },
                        ) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(2.dp).height(22.dp).background(LocalContentColor.current.copy(alpha = 0.72f), RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    quoted,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalContentColor.current.copy(alpha = 0.82f),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    when {
                        m.isDeleted -> Text("消息已撤回", style = MaterialTheme.typography.bodyMedium)
                        // 自定义表情：按比例缩小渲染（以内容标记区分普通图片）。
                        m.isSticker && m.file?.id?.isNotBlank() == true ->
                            StickerImage(m.file)
                        m.isSticker && m.localPreviewUri != null ->
                            StickerLocalImage(m.localPreviewUri)
                        // 待上传媒体（新流程）：缩略图/图标 + 进度环 + 百分比 + 取消/重试。
                        m.isLocalPending && m.localUploadPath != null &&
                            m.messageType != "text" && m.messageType != "markdown" ->
                            PendingUploadContent(
                                m = m,
                                onCancel = { onCancelUpload(m) },
                                onRetry = { onRetryUpload(m) },
                            )
                        // 这里依赖 Kotlin 智能转换：分支条件已保证非空，不能再写 !! 或冗余判空。
                        m.messageType == "image" && m.localPreviewUri != null ->
                            InlineLocalImage(uri = m.localPreviewUri, fileName = m.file?.fileName ?: "图片")
                        m.messageType == "image" && m.file != null -> InlineImage(file = m.file, onClick = onImageClick)
                        m.messageType == "audio" && m.file != null -> AudioPlayer(file = m.file)
                        m.messageType == "video" && m.file != null -> {
                            val video = m.file
                            var showVideo by remember(m.id) { mutableStateOf(false) }
                            VideoThumb(file = video, onClick = { showVideo = true })
                            if (showVideo) VideoPlayerDialog(file = video, onDismiss = { showVideo = false })
                        }
                        m.isFileType -> {
                            val fm = m.file
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                                Spacer(Modifier.width(Space.sm))
                                Column(Modifier.weight(1f, fill = false).widthIn(max = 190.dp)) {
                                    Text(fm?.fileName ?: "文件", style = MaterialTheme.typography.bodyMedium, maxLines = 2, fontWeight = FontWeight.Medium)
                                    Text(FileTransfer.humanSize(fm?.fileSize ?: 0), style = MaterialTheme.typography.labelSmall)
                                }
                                if (fm != null) {
                                    IconButton(onClick = { onDownload(fm) }) { Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_download)) }
                                }
                            }
                        }
                        // 文本与 Markdown 消息统一走 Markdown 渲染（全局）。
                        else -> com.rtcomm.app.ui.common.MarkdownText(src = m.content, compact = true)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // P1：发送状态指示（Queued = 已排队等待自动重发）。
                // 状态在「发送中 → 排队 → 失败」间变化时淡切，避免图标硬跳。
                Crossfade(
                    targetState = m.sendState,
                    animationSpec = tween(if (reducedMotion) 0 else 160),
                    label = "send-state",
                ) { st ->
                    when (st) {
                        com.rtcomm.app.data.SendState.Sending ->
                            Icon(Icons.Filled.HourglassEmpty, contentDescription = "发送中", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        com.rtcomm.app.data.SendState.Queued ->
                            Icon(Icons.Filled.Schedule, contentDescription = "等待重发", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        com.rtcomm.app.data.SendState.Failed ->
                            Icon(Icons.Filled.ErrorOutline, contentDescription = "发送失败", modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                        else -> Box(Modifier)
                    }
                }
                Spacer(Modifier.width(3.dp))
                AnimatedVisibility(
                    visible = m.editedAt != null,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)),
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Space.xs))
                    }
                }
                Text(Format.time(m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
}
