package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.Conversation
import com.rtcomm.app.data.PublicUser
import com.rtcomm.app.ui.common.ActionSheet
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.ConnectionChip
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.LoadingBox
import com.rtcomm.app.ui.common.SheetAction
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.common.rememberReducedMotion
import com.rtcomm.app.ui.profile.rememberAvatarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (String) -> Unit,
    onOpenCalls: () -> Unit,
    onConversationBounds: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    /** 卡片标题的实测矩形：共享标题必须从这里精确起步，不能用估算偏移。 */
    onConversationTitleBounds: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    val avatar = rememberAvatarController()
    val convs by AppState.conversations.collectAsState()
    val wsState by AppState.wsState.collectAsState()
    val onlineIds by AppState.onlineUserIds.collectAsState()
    val meId = AppState.currentUser.value?.id

    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    // 搜索词用 rememberSaveable：后台被系统回收再回来时不至于清空。
    var query by rememberSaveable { mutableStateOf("") }
    var leaveTarget by remember { mutableStateOf<Conversation?>(null) }
    /** 会话长按菜单目标（免打扰 / 退出）。 */
    var actionConv by remember { mutableStateOf<Conversation?>(null) }
    val authStore = remember { com.rtcomm.app.data.AuthStore(context) }
    /** 免打扰集合变化时触发重组（SharedPreferences 不是可观察的）。 */
    var mutedIds by remember { mutableStateOf(authStore.mutedConversations) }
    /** 置顶集合（本地偏好）。 */
    var pinnedIds by remember { mutableStateOf(authStore.pinnedConversations) }
    var msgHits by remember { mutableStateOf<List<com.rtcomm.app.data.MessageSearchHit>>(emptyList()) }
    var searchingMessages by remember { mutableStateOf(false) }
    val visibleConvs = remember(convs, query, pinnedIds) {
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) convs else convs.filter { c ->
            val title = convTitle(c, meId)
            val preview = lastPreviewText(c)
            title.lowercase().contains(q) || preview.lowercase().contains(q)
        }
        // 置顶的会话排在最前；stable sort 保持其余原有顺序。
        filtered.sortedByDescending { pinnedIds.contains(it.id) }
    }

    // 服务端消息搜索：本地过滤只能看到标题和最后一条，搜不到历史消息。
    // 150ms 防抖，避免每敲一个字都打一次接口。
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { msgHits = emptyList(); searchingMessages = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(150)
        searchingMessages = true
        // 取消（换关键词/离开页面）必须继续抛出：否则旧请求失败会覆盖新关键词的结果。
        val res = withContext(Dispatchers.IO) { runCatchingCancellable { Api.searchMessages(q, limit = 20) } }
        res.onSuccess { msgHits = it.results }
        res.onFailure { msgHits = emptyList() }
        searchingMessages = false
    }

    suspend fun load(isRefresh: Boolean) {
        if (isRefresh) refreshing = true else loading = true
        err = null
        // P1：先恢复本地缓存（秒开，离线可见）
        if (!isRefresh && AppState.conversations.value.isEmpty()) {
            val cached = withContext(Dispatchers.IO) {
                runCatchingCancellable { com.rtcomm.app.data.db.Repo.cachedConversations() }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) AppState.conversations.value = cached
        }
        val res = withContext(Dispatchers.IO) { runCatchingCancellable { Api.conversations() } }
        res.onSuccess { AppState.setConversations(it) }
        res.onFailure { err = Api.userMessage(it) }
        loading = false; refreshing = false
    }

    LaunchedEffect(Unit) { load(false) }

    // 共享 FAB（在 MainScaffold，切 Tab 时不抖动）发出的「发起会话」信号。
    LaunchedEffect(Unit) {
        AppState.fabNewConversation.collect { if (it) { showNew = true; AppState.fabNewConversation.value = false } }
    }

    // 预取会话头像到 Coil 磁盘缓存：列表滚动/重进时直接命中本地，不再转圈。
    LaunchedEffect(convs) {
        val loader = context.imageLoader
        convs.forEach { c ->
            val peer = c.members.firstOrNull { it.id != meId }
            val path = peer?.avatarUrl ?: c.members.firstOrNull { it.isBot }?.avatarUrl
            if (!path.isNullOrBlank()) {
                val full = if (path.startsWith("http")) path else com.rtcomm.app.data.Api.baseUrl.trimEnd('/') + path
                loader.enqueue(coil.request.ImageRequest.Builder(context).data(full).build())
            }
        }
    }

    // 主界面标题栏固定（pinned）：无论滚到哪里，毛玻璃「会话」标题栏都不会被挤压消失。
    val titleScroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(titleScroll.nestedScrollConnection),
        // 底部安全区由 MainScaffold 的 contentBottomInset（经 ExpandFlow.listBottomPadding）统一负责；
        // 这里若再用默认 contentWindowInsets 会叠加出约一个导航栏高度的空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "会话",
                scrollBehavior = titleScroll,
                actions = {
                    ConnectionChip(wsState)
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { Api.markAllRead() } }
                                r.onSuccess {
                                    AppState.conversations.value.forEach { AppState.clearUnread(it.id) }
                                    err = null
                                }.onFailure { err = Api.userMessage(it) }
                            }
                        },
                    ) { Icon(Icons.Filled.DoneAll, contentDescription = "全部标记已读") }
                    IconButton(onClick = onOpenCalls) { Icon(Icons.Filled.Call, contentDescription = "通话记录") }
                    Spacer(Modifier.width(Space.xs))
                },
            )
        },
    ) { pad ->
        Box(
            Modifier.padding(pad).fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(true) } },
            state = rememberPullToRefreshState(),
            // 大屏（平板/折叠屏展开/桌面）下把列表限制在可读宽度并居中，避免整屏横向拉伸。
            modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (err != null) ErrorBanner(err!!, reducedMotion = reduced, onRetry = { scope.launch { load(false) } })
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索会话或消息") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                FadeSwap(
                    if (loading && convs.isEmpty()) 0
                    else if (convs.isEmpty()) 1
                    else if (visibleConvs.isEmpty() && msgHits.isEmpty() && !searchingMessages) 2
                    else 3,
                ) { st ->
                when (st) {
                    0 -> LoadingBox()
                    1 -> EmptyState(
                        icon = Icons.Filled.Forum,
                        title = "还没有会话",
                        subtitle = "搜索用户，立即开始聊天",
                        action = { Button(onClick = { showNew = true }) { Text("发起会话") } },
                    )
                    2 -> EmptyState(Icons.Filled.Search, "没有匹配结果", "试试其他关键词")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleConvs, key = { it.id }, contentType = { "conv" }) { c ->
                            ConversationRow(
                                c = c,
                                meId = meId,
                                onlineIds = onlineIds,
                                onClick = { onOpenChat(c.id) },
                                onLongClick = { actionConv = c },
                                muted = mutedIds.contains(c.id),
                                pinned = pinnedIds.contains(c.id),
                                onBounds = { onConversationBounds(c.id, it) },
                                onTitleBounds = { onConversationTitleBounds(c.id, it) },
                                onOpenAvatar = { mem ->
                                    avatar.open(
                                        com.rtcomm.app.ui.profile.AvatarTarget(
                                            name = mem.displayName,
                                            avatarUrl = mem.avatarUrl,
                                            isMe = mem.id == meId,
                                            username = mem.username,
                                            isBot = mem.isBot,
                                        ),
                                    )
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        // 消息命中：点击后进入所属会话并定位到该条。
                        if (msgHits.isNotEmpty()) {
                            item(key = "msg-header") {
                                Text(
                                    "消息记录",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                                )
                            }
                            items(msgHits, key = { "hit-" + it.message?.id }) { hit ->
                                MessageHitRow(hit, modifier = Modifier.animateItem()) {
                                    val convId = hit.conversationId
                                    val msgId = hit.message?.id
                                    // 用统一的「打开 + 定位」通道，避免两条独立的导航路径。
                                    AppState.pendingJumpMessageId.value = msgId
                                    AppState.pendingOpenConversationId.value = convId
                                }
                            }
                        }
                        if (searchingMessages) {
                            item(key = "msg-searching") {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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

    if (showNew) {
        NewConversationSheet(
            onDismiss = { showNew = false },
            onCreated = { conv ->
                showNew = false
                AppState.upsertConversation(conv)
                onOpenChat(conv.id)
            },
        )
    }

    actionConv?.let { target ->
        val muted = mutedIds.contains(target.id)
        val pinned = pinnedIds.contains(target.id)
        ActionSheet(
            onDismiss = { actionConv = null },
            title = convTitle(target, meId),
            actions = listOf(
                SheetAction(Icons.Filled.PushPin, if (pinned) "取消置顶" else "置顶会话") {
                    authStore.setPinned(target.id, !pinned)
                    pinnedIds = authStore.pinnedConversations
                    actionConv = null
                },
                SheetAction(Icons.Filled.NotificationsOff, if (muted) "取消免打扰" else "免打扰（不再弹通知）") {
                    authStore.setMuted(target.id, !muted)
                    mutedIds = authStore.mutedConversations
                    actionConv = null
                },
                SheetAction(Icons.AutoMirrored.Filled.Logout, "退出会话", destructive = true) {
                    leaveTarget = target
                    actionConv = null
                },
            ),
        )
    }

    leaveTarget?.let { target ->
        AppAlertDialog(
            onDismissRequest = { leaveTarget = null },
            title = { Text("退出会话") },
            text = { Text("确定退出「" + convTitle(target, meId) + "」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    leaveTarget = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.leaveConversation(id) } }
                        r.onSuccess { AppState.removeConversation(id) }
                        r.onFailure { err = Api.userMessage(it) }
                    }
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaveTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation,
    meId: String?,
    onlineIds: Set<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    muted: Boolean = false,
    pinned: Boolean = false,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    onOpenAvatar: (com.rtcomm.app.data.ConversationMember) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val peer = if (c.type == "direct") c.members.firstOrNull { it.id != meId } else null
    val online = peer?.let { onlineIds.contains(it.id) || it.isOnline }
    val title = c.name?.takeIf { it.isNotBlank() } ?: peer?.displayName ?: if (c.type == "direct") "私聊" else "群组"
    val reducedMotion = rememberReducedMotion()
    // 与 AI 助手列表的 BotRow 完全同构（Card + motionPress + combinedClickable）：
    // 这样按压缩放与「共享元素展开」两种动画都走同一条路径，观感一致。
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            // 共享元素起点要在缩放层之外实测，否则按压时坐标会随缩放漂移。
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .motionPress(pressedScale = 0.99f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(
                name = title,
                online = online,
                isBot = peer?.isBot == true,
                avatarUrl = peer?.avatarUrl,
                group = if (c.type == "group") {
                    c.members.map { com.rtcomm.app.ui.common.AvatarSeed(it.displayName, it.avatarUrl) }
                } else null,
                onClick = peer?.let { p -> { onOpenAvatar(p) } },
            )
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    // 共享标题的起点：卡片的真实排版位置，精确到像素。
                    modifier = Modifier.onGloballyPositioned { onTitleBounds(it.boundsInRoot()) },
                )
                Text(
                    lastPreview(c),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Space.sm))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(Format.relative(c.lastMessage?.createdAt ?: c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(5.dp))
                if (muted) {
                    Text("静音", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                }
                AnimatedVisibility(
                    visible = c.unreadCount > 0,
                    enter = scaleIn(tween(if (reducedMotion) 0 else 180)) + fadeIn(tween(if (reducedMotion) 0 else 140)),
                    exit = scaleOut(tween(if (reducedMotion) 0 else 120)) + fadeOut(tween(if (reducedMotion) 0 else 100)),
                ) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(if (c.unreadCount > 99) "99+" else c.unreadCount.toString()) }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun MessageHitRow(
    hit: com.rtcomm.app.data.MessageSearchHit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val msg = hit.message
    val sender = msg?.sender?.displayName ?: "?"
    val body = when {
        msg == null -> androidx.compose.ui.text.AnnotatedString("")
        msg.isFileType -> androidx.compose.ui.text.AnnotatedString("[文件] " + (msg.file?.fileName ?: ""))
        else -> com.rtcomm.app.ui.common.Markdown.previewInline(msg.content).let {
            if (it.text.isEmpty()) androidx.compose.ui.text.AnnotatedString("[空消息]") else it
        }
    }
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .motionPress(pressedScale = 0.99f)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        leadingContent = { InitialsAvatar(name = sender, size = 34.dp, isBot = msg?.sender?.isBot == true) },
        headlineContent = {
            Text(
                (hit.conversationName ?: "会话") + " · " + sender,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        },
        supportingContent = { Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 2) },
        trailingContent = {
            Text(
                Format.relative(msg?.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun convTitle(c: Conversation, meId: String? = null): String {
    if (c.type == "direct" && meId != null) {
        c.members.firstOrNull { it.id != meId }?.displayName?.let { return it }
    }
    return c.name?.takeIf { it.isNotBlank() } ?: if (c.type == "direct") "私聊" else "群组"
}

/** 纯文本预览：仅用于搜索过滤匹配（Markdown 标记已压平）。 */
private fun lastPreviewText(c: Conversation): String {
    val lm = c.lastMessage ?: return "暂无消息"
    if (lm.isDeleted) return "消息已撤回"
    return when (lm.messageType) {
        "file" -> "[文件] " + (lm.file?.fileName ?: "")
        "image" -> "[图片]"
        "video" -> "[视频]"
        "audio" -> "[音频]"
        else -> Format.mdToPlain(lm.content).ifBlank { "" }
    }
}

/** 列表预览：文本消息保留行内 Markdown 样式（粗/斜/代码/链接），媒体仍用占位文案。 */
private fun lastPreview(c: Conversation): androidx.compose.ui.text.AnnotatedString {
    val lm = c.lastMessage ?: return androidx.compose.ui.text.AnnotatedString("暂无消息")
    if (lm.isDeleted) return androidx.compose.ui.text.AnnotatedString("消息已撤回")
    return when (lm.messageType) {
        "file" -> androidx.compose.ui.text.AnnotatedString("[文件] " + (lm.file?.fileName ?: ""))
        "image" -> androidx.compose.ui.text.AnnotatedString("[图片]")
        "video" -> androidx.compose.ui.text.AnnotatedString("[视频]")
        "audio" -> androidx.compose.ui.text.AnnotatedString("[音频]")
        else -> com.rtcomm.app.ui.common.Markdown.previewInline(lm.content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationSheet(
    onDismiss: () -> Unit,
    onCreated: (Conversation) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PublicUser>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<PublicUser>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        // 清空查询时同时复位 searching，否则进度条会残留空转
        if (q.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        kotlinx.coroutines.delay(300)
        val r = withContext(Dispatchers.IO) { runCatching { Api.searchUsers(q, 30) } }
        r.onSuccess { results = it }
        r.onFailure { err = Api.userMessage(it) }
        searching = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发起会话", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                FilterChip(selected = !isGroup, onClick = { isGroup = false }, label = { Text("一对一") }, leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) })
                Spacer(Modifier.width(Space.sm))
                FilterChip(selected = isGroup, onClick = { isGroup = true }, label = { Text("群组") }, leadingIcon = { Icon(Icons.Filled.Group, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(Space.md))
            if (isGroup) {
                OutlinedTextField(groupName, { groupName = it }, label = { Text("群组名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.sm))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索用户名 / 昵称") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isGroup && selected.isNotEmpty()) {
                Spacer(Modifier.height(Space.sm))
                Text("已选 " + selected.size + " 人", style = MaterialTheme.typography.labelMedium)
            }
            if (err != null) { Spacer(Modifier.height(Space.sm)); ErrorBanner(err!!) }
            Spacer(Modifier.height(Space.sm))
            Box(Modifier.heightIn(max = 320.dp)) {
                // 搜索中 → 结果 之间淡切，避免进度条与列表硬替换。
                FadeSwap(target = searching && results.isEmpty()) { loadingNow ->
                    if (loadingNow) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(results, key = { it.id }) { u ->
                                val chosen = selected.any { it.id == u.id }
                                ListItem(
                                    // 创建中禁止再次点击列表项，避免快速连点重复创建 direct 会话
                                    modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(enabled = !creating) {
                                        err = null
                                        if (isGroup) {
                                            selected = if (chosen) selected.filterNot { it.id == u.id } else selected + u
                                        } else {
                                            creating = true
                                            scope.launch {
                                                val r = withContext(Dispatchers.IO) { runCatching { Api.createConversation("direct", null, listOf(u.id)) } }
                                                creating = false
                                                r.onSuccess { c -> if (c != null) onCreated(c) else err = "创建失败" }
                                                r.onFailure { err = Api.userMessage(it) }
                                            }
                                        }
                                    },
                                    leadingContent = { InitialsAvatar(u.displayName, size = 40.dp, isBot = u.isBot) },
                                    headlineContent = { Text(u.displayName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                    supportingContent = { Text("@" + u.username, style = MaterialTheme.typography.bodySmall) },
                                    trailingContent = { if (isGroup && chosen) Icon(Icons.Filled.Add, contentDescription = "已选") },
                                )
                            }
                        }
                    }
                }
            }
            if (isGroup) {
                Spacer(Modifier.height(Space.sm))
                Button(
                    onClick = {
                        if (selected.isEmpty()) { err = "请至少选择一名成员"; return@Button }
                        creating = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { Api.createConversation("group", groupName.ifBlank { null }, selected.map { it.id }) }
                            }
                            creating = false
                            r.onSuccess { c -> if (c != null) onCreated(c) else err = "创建失败" }
                            r.onFailure { err = Api.userMessage(it) }
                        }
                    },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (creating) "创建中…" else "创建群组") }
            }
        }
    }
}
