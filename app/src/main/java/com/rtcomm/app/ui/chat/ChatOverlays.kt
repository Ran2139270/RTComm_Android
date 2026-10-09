package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import com.rtcomm.app.ui.common.ActionSheet
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppSwitch
import com.rtcomm.app.ui.common.SheetAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.Conversation
import com.rtcomm.app.data.Message
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.theme.Corner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 从 ChatScreen.kt 拆出的消息相关弹层（转发 / 长按操作 / 通话 / 总结 / 成员 /
// 编辑 / 会话信息）。放在同包内、改为 internal 供 ChatScreen 调用，避免单文件过大。

/** P2：长按消息操作（回复 / 复制 / 撤回 / 重发）。 */
@Composable
internal fun ForwardMessageDialog(
    messages: List<Message>,
    currentConversationId: String,
    onDismiss: () -> Unit,
    onForward: (String, Boolean) -> Unit,
) {
    val conversations by AppState.conversations.collectAsState()
    // 多条消息时可选择「合并转发」（默认）或「逐条转发」。
    val multi = messages.size > 1
    var merge by remember { mutableStateOf(multi) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multi) "转发 ${messages.size} 条消息" else "转发消息") },
        text = {
            Column {
                Text(
                    messages.joinToString("\n") { m ->
                        when {
                            m.isSticker -> "[动画表情]"
                            m.isForward -> "[聊天记录]"
                            else -> Format.mdToPlain(m.content).ifBlank { "[文件]" }
                        }
                    }.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (multi) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilterChip(selected = merge, onClick = { merge = true }, label = { Text("合并转发") })
                        FilterChip(selected = !merge, onClick = { merge = false }, label = { Text("逐条转发") })
                    }
                }
                Spacer(Modifier.height(10.dp))
                val targets = conversations.filter { it.id != currentConversationId }
                if (targets.isEmpty()) {
                    Text("暂无其他可转发的会话", style = MaterialTheme.typography.bodyMedium)
                } else {
                    targets.take(12).forEach { conversation ->
                        TextButton(
                            onClick = { onForward(conversation.id, multi && merge) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(conversation.name ?: "聊天")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun MessageActionDialog(
    m: Message,
    isMine: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDeleteLocal: () -> Unit,
    onMultiSelect: () -> Unit,
    onRecall: () -> Unit,
    onRetry: () -> Unit,
    onAddSticker: () -> Unit,
) {
    // 长按操作面板：由居中 AlertDialog 改为带图标、可滑动的底部动作面板（ActionSheet）。
    val plain = when {
        m.isSticker -> "[动画表情]"
        m.isForward -> "[聊天记录]"
        else -> Format.mdToPlain(m.content)
    }
    val snippet = (if (plain.isNotBlank()) plain.take(48) else if (m.isFileType) "[文件]" else "") +
        if (plain.length > 48) "…" else ""
    val actions = buildList {
        if (!m.isDeleted) {
            // 本地未发送成功的消息没有合法服务端 id，引用它会导致发送永久失败，故不提供回复。
            if (!m.isLocalPending) {
                add(SheetAction(Icons.AutoMirrored.Filled.Reply, "回复", onClick = onReply))
            }
            if (!m.isFileType || m.content != null) {
                add(SheetAction(Icons.Filled.ContentCopy, "复制文本", onClick = onCopy))
            }
            // 已上传的图片可一键存为自定义表情，之后在表情面板反复使用。
            if (m.messageType == "image" && !m.fileId.isNullOrBlank()) {
                add(SheetAction(Icons.Filled.EmojiEmotions, "添加为表情", onClick = onAddSticker))
            }
            if (!m.content.isNullOrBlank() || m.fileId != null) {
                add(SheetAction(Icons.Filled.Share, "转发到其他会话", onClick = onForward))
            }
            // 只有自己的文本/Markdown 消息能编辑（与后端规则一致）
            if (isMine && !m.isLocalPending && (m.messageType == "text" || m.messageType == "markdown")) {
                add(SheetAction(Icons.Filled.Edit, stringResource(R.string.action_edit), onClick = onEdit))
            }
            add(SheetAction(Icons.Filled.Checklist, "多选", onClick = onMultiSelect))
            add(SheetAction(Icons.Filled.DeleteOutline, "仅删除本地", destructive = true, onClick = onDeleteLocal))
        }
        if (isMine && (m.sendState == com.rtcomm.app.data.SendState.Failed ||
                m.sendState == com.rtcomm.app.data.SendState.Queued)) {
            add(SheetAction(Icons.Filled.Refresh, "立即重发", onClick = onRetry))
        }
        if (isMine && !m.isDeleted && !m.isLocalPending) {
            add(SheetAction(Icons.AutoMirrored.Filled.Undo, "撤回", destructive = true, onClick = onRecall))
        }
    }
    ActionSheet(onDismiss = onDismiss, title = "消息操作", subtitle = snippet.ifBlank { null }, actions = actions)
}

@Composable
internal fun CallDialog(conversationId: String, onDismiss: () -> Unit, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var roomInfo by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    fun createRoom() {
        if (creating) return
        creating = true; err = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { Api.createRoom(conversationId) } }
            creating = false
            res.onSuccess { r ->
                roomInfo = "房间已创建：" + r.roomId + "（信令已就绪）"
                scope.launch { snackbar.showSnackbar("通话房间已创建（媒体层未实现）") }
            }
            res.onFailure { err = Api.userMessage(it) }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 只有授权通过才创建房间：拒绝权限时不应在后端留下无意义房间。
        if (granted) createRoom() else err = "麦克风权限被拒绝，无法进行语音通话"
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发起通话") },
        text = {
            Column {
                Text("将通过后端信令通道创建通话房间。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Space.sm))
                Text(
                    "说明：本版本已实现通话信令与通话记录；WebRTC 音视频媒体层尚未接入（需 org.webrtc 原生库），下一版本补齐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                roomInfo?.let { Spacer(Modifier.height(Space.sm)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) createRoom() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) { Text(if (creating) "创建中…" else "创建房间") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SummarySheet(conversationId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var list by remember { mutableStateOf<List<com.rtcomm.app.data.Summary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var generating by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching { Api.summaries(conversationId) } }
        r.onSuccess { list = it }; r.onFailure { err = Api.userMessage(it) }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxWidth().heightIn(max = 520.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("会话总结", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(
                    enabled = !generating,
                    onClick = {
                        generating = true; err = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Api.summarize(conversationId) } }
                            generating = false
                            r.onSuccess { reload() }
                            r.onFailure { err = Api.userMessage(it) }
                        }
                    },
                ) {
                    if (generating) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("生成中…") }
                    else Text("生成总结")
                }
            }
            err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(Space.sm))
            // 加载 → 空 → 列表 之间淡切；总结卡片带进入动画。
            FadeSwap(target = if (loading) 0 else if (list.isEmpty()) 1 else 2) { st ->
                when (st) {
                    0 -> CircularProgressIndicator(Modifier.padding(16.dp))
                    1 -> Text("暂无总结，点击「生成总结」创建。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> androidx.compose.foundation.lazy.LazyColumn {
                        items(list, key = { it.id }) { s ->
                            Card(Modifier.animateItem().fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(s.content, style = MaterialTheme.typography.bodyMedium)
                                    if (s.keywords.isNotEmpty()) {
                                        Spacer(Modifier.height(Space.sm))
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            s.keywords.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text((s.summaryType ?: "") + " · " + Format.dateTime(s.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembersSheet(
    conv: Conversation,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onOpenAvatar: (com.rtcomm.app.data.ConversationMember) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val meId = AppState.currentUser.value?.id
    val myRole = conv.members.firstOrNull { it.id == meId }?.role
    val canManage = conv.type == "group" && (myRole == "owner" || myRole == "admin")
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.rtcomm.app.data.PublicUser>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty() || !canManage) { results = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val r = withContext(Dispatchers.IO) { runCatching { Api.searchUsers(q, 20) } }
        r.onSuccess { list -> results = list.filter { u -> conv.members.none { it.id == u.id } } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).fillMaxWidth().heightIn(max = 560.dp)) {
            Text((conv.name ?: "会话") + " · " + conv.members.size + " 人", style = MaterialTheme.typography.titleMedium)
            err?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (canManage) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, label = { Text("搜索并添加成员") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (results.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(results, key = { it.id }) { u ->
                            ListItem(
                                modifier = Modifier.clickable(enabled = !busy) {
                                    busy = true
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { Api.addMembers(conv.id, listOf(u.id)) } }
                                        busy = false
                                        r.onSuccess { query = ""; results = emptyList(); onReload() }
                                        r.onFailure { err = Api.userMessage(it) }
                                    }
                                },
                                leadingContent = { InitialsAvatar(u.displayName, size = 36.dp, isBot = u.isBot) },
                                headlineContent = { Text(u.displayName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                supportingContent = { Text("@" + u.username, style = MaterialTheme.typography.bodySmall) },
                                trailingContent = { Icon(Icons.Filled.Group, contentDescription = stringResource(R.string.action_add)) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.sm))
            HorizontalDivider()
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                items(conv.members, key = { it.id }) { mem ->
                    ListItem(
                        modifier = Modifier.animateItem(),
                        leadingContent = { InitialsAvatar(mem.displayName, size = 40.dp, isBot = mem.isBot, avatarUrl = mem.avatarUrl, onClick = { onOpenAvatar(mem) }) },
                        headlineContent = { Text(mem.displayName + if (mem.id == meId) "（我）" else "", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        supportingContent = { Text("@" + mem.username, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (mem.role == "owner") {
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(Corner.chip)) {
                                        Text("群主", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                } else if (mem.role == "admin") {
                                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(Corner.chip)) {
                                        Text("管理员", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                                if (canManage && mem.role != "owner" && mem.id != meId) {
                                    // 请求进行中禁用，避免连点重复调用 removeMember
                                    IconButton(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            scope.launch {
                                                val r = withContext(Dispatchers.IO) { runCatching { Api.removeMember(conv.id, mem.id) } }
                                                busy = false
                                                r.onSuccess { onReload() }
                                                r.onFailure { err = Api.userMessage(it) }
                                            }
                                        },
                                    ) { Icon(Icons.Filled.Group, contentDescription = stringResource(R.string.action_remove), tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.leaveConversation(conv.id) } }
                        r.onSuccess { AppState.removeConversation(conv.id); onDismiss() }
                        r.onFailure { err = Api.userMessage(it) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出会话", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 编辑消息：预填原文，保存后由服务端广播 message_edited。 */
@Composable
internal fun EditMessageDialog(
    message: Message,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(message.id) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(message.content.orEmpty()))
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.messageType == "markdown") "编辑 Markdown" else "编辑消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.text.trim()) },
                enabled = text.text.isNotBlank() && text.text.trim() != (message.content ?: "").trim(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 聊天信息聚合弹层：通话 / 总结 / 成员 / 免打扰 / 置顶 / 搜索 / 清空 / 退出。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChatInfoSheet(
    title: String,
    isGroup: Boolean,
    memberCount: Int,
    muted: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onSummary: () -> Unit,
    onMembers: () -> Unit,
    onEditGroup: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onLeave: () -> Unit,
    /** 本会话背景覆盖；null 表示跟随全局。 */
    wallpaper: String?,
    onWallpaper: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            InfoAction(Icons.Filled.Call, "通话") { onDismiss(); onCall() }
            InfoAction(Icons.Filled.Summarize, "会话总结") { onDismiss(); onSummary() }
            InfoAction(Icons.Filled.Group, if (isGroup) "群成员（$memberCount）" else "会话成员") { onDismiss(); onMembers() }
            if (isGroup) InfoAction(Icons.Filled.Edit, "编辑群信息") { onDismiss(); onEditGroup() }
            InfoAction(Icons.Filled.Search, "搜索聊天记录") { onDismiss(); onSearch() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            InfoSwitch(Icons.Filled.Notifications, "免打扰", muted, onToggleMute)
            InfoSwitch(Icons.Filled.PushPin, "置顶会话", pinned, onTogglePin)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // 本会话背景：可覆盖全局设置或跟随全局。
            Text(
                "本会话背景",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                FilterChip(
                    selected = wallpaper == null,
                    onClick = { onWallpaper("inherit") },
                    label = { Text("跟随全局") },
                )
                ChatWallpaper.entries.forEach { w ->
                    FilterChip(
                        selected = wallpaper == w.key,
                        onClick = { onWallpaper(w.key) },
                        label = { Text(w.label) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            InfoAction(Icons.Filled.Delete, "清空聊天记录", danger = true) { onDismiss(); onClear() }
            InfoAction(Icons.AutoMirrored.Filled.Logout, "退出会话", danger = true) { onDismiss(); onLeave() }
        }
    }
}

@Composable
internal fun InfoAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(onClick = onClick),
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
    )
}

@Composable
internal fun InfoSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onChange: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = { onChange() }, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.md))
        Text(label, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 编辑群信息：修改群名称与群头像（仅群主/群内管理员可保存）。
 * 群头像在本地压成 dataUrl 后随请求提交，服务端存 URL。
 */
@Composable
internal fun GroupEditDialog(
    conversationId: String,
    initialName: String,
    initialAvatarUrl: String?,
    onDismiss: () -> Unit,
    onUpdated: (Conversation) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    // null=未修改（不动头像）；""=清除；其它=新的 dataUrl。
    var avatarUrl by remember { mutableStateOf(initialAvatarUrl) }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { com.rtcomm.app.data.FileTransfer.avatarDataUrl(context, uri, 512) }
            if (dataUrl != null) avatarUrl = dataUrl else err = "无法读取所选图片"
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑群信息") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(name = name.ifBlank { "群" }, size = 56.dp, avatarUrl = avatarUrl?.takeIf { it.isNotBlank() })
                    Spacer(Modifier.width(Space.md))
                    TextButton(onClick = { picker.launch("image/*") }) { Text("更换群头像") }
                    if (!avatarUrl.isNullOrBlank()) {
                        TextButton(onClick = { avatarUrl = "" }) { Text("移除") }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("群名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (err != null) {
                    Spacer(Modifier.height(Space.sm))
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.isNotBlank(),
                onClick = {
                    saving = true; err = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { Api.updateConversation(conversationId, name = name.trim(), avatarUrl = avatarUrl) }
                        }
                        saving = false
                        r.onSuccess { c -> if (c != null) { onUpdated(c); onDismiss() } else err = "保存失败" }
                        r.onFailure { err = Api.userMessage(it) }
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 合并转发详情：逐条展示被转发的消息。 */
@Composable
internal fun ForwardDetailDialog(content: String, onDismiss: () -> Unit) {
    val parsed = com.rtcomm.app.data.parseForward(content)
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(parsed?.title?.takeIf { it.isNotBlank() } ?: "聊天记录") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(parsed?.items ?: emptyList()) { it ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(it.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(it.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
