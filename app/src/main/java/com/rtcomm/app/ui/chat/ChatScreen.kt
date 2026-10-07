package com.rtcomm.app.ui.chat
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.imageLoader
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.Conversation
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.Message
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.ui.common.TypingDots
import com.rtcomm.app.ui.common.AppTopBarContent
import com.rtcomm.app.ui.common.GlassHeaderBox
import com.rtcomm.app.ui.common.TopBarTitle
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.common.Motion
import com.rtcomm.app.ui.theme.Alpha
import com.rtcomm.app.ui.theme.Corner
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 非可观察的布尔持有器：拖动过程中写它不会触发重组。 */
internal class Flag(var value: Boolean = false)

/** 本地乐观消息 id：毫秒 + 进程内自增，避免同一毫秒内连发两条时 id 碰撞。 */
private val localIdSeq = java.util.concurrent.atomic.AtomicLong()
internal fun newLocalId(): String = "local_${System.currentTimeMillis()}_${localIdSeq.incrementAndGet()}"

/** [com.rtcomm.app.data.MessageCursor] 的可保存 Saver（进程重建后保留分页锚点）。 */
private val MessageCursorSaver: androidx.compose.runtime.saveable.Saver<com.rtcomm.app.data.MessageCursor?, List<String>> =
    androidx.compose.runtime.saveable.Saver(
        save = { c -> if (c == null) emptyList() else listOf(c.before, c.beforeId) },
        restore = { l -> if (l.size == 2) com.rtcomm.app.data.MessageCursor(l[0], l[1]) else null },
    )

/**
 * 与 [runCatching] 相同，但**不吞掉协程取消**。
 *
 * `runCatching` 会把 [kotlinx.coroutines.CancellationException] 一起捕获并转成
 * `Result.failure`，于是切会话导致旧 load 被取消后，`onFailure/onSuccess` 仍会继续执行、
 * 把旧会话的数据写进新会话的 UI。取消必须继续向上抛出。
 */
internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** 能否转发：撤回的、无内容的、以及尚未上传完成的本地媒体都不能转发（否则会生成坏消息）。 */
private fun Message.canForward(): Boolean {
    val hasFile = !fileId.isNullOrBlank()
    if (localUploadPath != null && !hasFile) return false
    if (isDeleted) return false
    if (content.isNullOrBlank() && !hasFile) return false
    return true
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    /**
     * 共享元素展开进度（0→1）。刻意用 lambda 而不是 Float：
     * lambda 只在绘制阶段被读取，动画期间不会重组本页（含 LazyColumn）。
     * 时间线：0.375 = 150ms 消息入场，0.625 = 250ms 输入框入场（总时长 400ms）。
     */
    progress: () -> Float = { 1f },
    /**
     * 上报聊天头部标题的实测矩形，作为共享标题的**终点**。
     * 之前把终点写死成 72/26，而真实标题受状态栏内边距影响位置完全不同，所以看着会跳。
     */
    onHeaderTitleBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    // 只订阅本会话：别的会话来消息不会让本页重组（之前订阅的是整个 messages Map）。
    val msgs by remember(conversationId) { AppState.messagesFlow(conversationId) }
        .collectAsStateWithLifecycle(emptyList())
    val typingUserIds by remember(conversationId) { AppState.typingFlow(conversationId) }
        .collectAsStateWithLifecycle(emptySet())
    val meId = AppState.currentUser.value?.id
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    // 头像查看/修改/历史统一入口（聊天内头像、成员列表都可复用）。
    val avatar = com.rtcomm.app.ui.profile.rememberAvatarController()

    // 会话态一律按 conversationId 重建：列表内切换会话时组合位置不变，
    // 若只 remember 不 key，上一个会话的标题/草稿/回复目标会串到新会话。
    var conv by remember(conversationId) { mutableStateOf<Conversation?>(null) }
    // 用 TextFieldValue 而不是 String：Markdown 工具栏需要「包裹当前选区」。
    var input by rememberSaveable(conversationId, stateSaver = androidx.compose.ui.text.input.TextFieldValue.Saver) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var loadingInit by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    // 旋转/重建后保留分页游标，否则 page 回到 1 会导致「加载更多」失效。
    var page by rememberSaveable(conversationId) { mutableIntStateOf(1) }
    var total by rememberSaveable(conversationId) { mutableIntStateOf(0) }
    /**
     * 游标分页：下一页锚点；hasMoreCursor=false 表示已经到最早一条。
     * 必须与 page 一样走 rememberSaveable：否则进程被回收后 page 保留了、
     * 游标却回退到 null，「加载更多」按钮会失效或重复拉取。
     */
    var nextCursor by rememberSaveable(conversationId, stateSaver = MessageCursorSaver) {
        mutableStateOf<com.rtcomm.app.data.MessageCursor?>(null)
    }
    var hasMoreCursor by rememberSaveable(conversationId) { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showMembers by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showCall by remember { mutableStateOf(false) }
    /** 浏览历史时收到的新消息数量，点击浮钮返回最新。 */
    var unreadNewCount by remember { mutableIntStateOf(0) }
    var previousFirstMessageId by remember { mutableStateOf<String?>(null) }
    /**
     * 首条未读消息 id。服务端只返回未读**数量**，本地按「排除自己发送的最近 N 条」定位。
     * 打开会话时快照一次，之后不再变（滚动/新消息不会移动分界线）。
     */
    var firstUnreadId by rememberSaveable(conversationId) { mutableStateOf<String?>(null) }
    var replyTo by remember(conversationId) { mutableStateOf<Message?>(null) }
    var composeMarkdown by remember { mutableStateOf(false) }
    /** P2：长按消息操作菜单目标。 */
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    /** 待选择转发目标的消息（支持多选批量转发）。 */
    var forwardTargets by remember { mutableStateOf<List<Message>>(emptyList()) }
    /** 正在编辑的消息（编辑自己的文本消息）。 */
    var editTarget by remember(conversationId) { mutableStateOf<Message?>(null) }
    /** 多选模式：长按菜单进入，点按消息切换选中。 */
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 稳定的多选切换回调：否则 selectedIds 每次变化都会让所有可见气泡拿到新 lambda 而整体重组。
    val toggleSelect: (String) -> Unit = remember {
        { id ->
            selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        }
    }
    // 右滑进入多选：立即选中该条。
    val startSelect: (String) -> Unit = remember {
        { id ->
            selectionMode = true
            selectedIds = setOf(id)
        }
    }
    /** 聊天信息聚合入口（通话/总结/成员/免打扰/置顶/搜索/清空/退出）。 */
    var showInfo by remember { mutableStateOf(false) }
    /** 编辑群信息（群名/群头像）。 */
    var showEditGroup by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    /** 统一图片查看器起始下标（null = 关闭）。 */
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    /** 打开查看器时的图片快照：新消息到达不会让页码/图片漂移。 */
    var viewerImages by remember { mutableStateOf<List<com.rtcomm.app.data.MessageFile>?>(null) }
    val authStore = remember { com.rtcomm.app.data.AuthStore(context) }
    var muted by remember(conversationId) { mutableStateOf(authStore.isMuted(conversationId)) }
    var pinned by remember(conversationId) { mutableStateOf(authStore.isPinned(conversationId)) }

    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val hasMore = hasMoreCursor
    /** 消息密度（个性化）。 */
    val chatDensity by AppState.chatDensity.collectAsState()
    val compactMessages = chatDensity == "compact"
    /** 聊天背景（个性化）：本会话覆盖优先，否则跟随全局。 */
    val chatWallpaper by AppState.chatWallpaper.collectAsState()
    /** 自定义聊天背景图（优先于内置样式）。 */
    val chatBackground by AppState.chatBackground.collectAsState()
    /** 聊天背景的模糊/暗化：与主界面独立，可在设置里分别调节。 */
    val chatBgBlur by AppState.chatBgBlur.collectAsState()
    val chatBgDim by AppState.chatBgDim.collectAsState()
    var convWallpaper by remember(conversationId) { mutableStateOf(authStore.wallpaperFor(conversationId)) }
    val effectiveWallpaper = convWallpaper ?: chatWallpaper
    /** 会话内搜索命中（本地过滤当前已加载消息）。只在输入/消息变化时重算，而不是每次重组。 */
    val searchHits = remember(msgs, searchOpen, searchQuery) {
        if (searchOpen && searchQuery.isNotBlank()) {
            msgs.filter { it.content?.contains(searchQuery, ignoreCase = true) == true }.take(30)
        } else emptyList()
    }
    /** 本会话全部图片消息（统一查看器的数据源，顺序与列表一致）。 */
    val imageFiles = remember(msgs) {
        msgs.filter { it.messageType == "image" && it.file != null }.mapNotNull { it.file }
    }

    /** reverseLayout 下 index=0 就是最新消息；自己发送时始终回到这里。 */
    fun scrollToLatest() {
        unreadNewCount = 0
        scope.launch { listState.animateScrollToItem(0) }
    }

    /** 已读上报：优先 WS，断开时用 REST 兜底。 */
    fun reportRead(messageId: String) {
        if (WsClient.isConnected() &&
            WsClient.send("mark_read", mapOf("conversationId" to conversationId, "messageIds" to listOf(messageId)))
        ) {
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.markMessageRead(messageId) } }
        }
    }

    suspend fun loadInitial() {
        loadingInit = true; err = null
        // 1) 先读本地缓存（Room），秒开
        val cached = withContext(Dispatchers.IO) {
            // 按 id 单查：不必解密整张会话表再 firstOrNull。
            runCatchingCancellable { com.rtcomm.app.data.db.Repo.cachedConversation(conversationId) }.getOrNull()
        }
        cached?.let { c ->
            conv = c
            val cachedMsgs = withContext(Dispatchers.IO) {
                runCatchingCancellable { com.rtcomm.app.data.db.Repo.cachedMessages(conversationId, 50) }.getOrDefault(emptyList())
            }
            if (cachedMsgs.isNotEmpty() && AppState.messagesOf(conversationId).isEmpty()) {
                AppState.setMessages(conversationId, cachedMsgs)
                total = maxOf(total, cachedMsgs.size)
                // 进程重启后，之前 Sending 的消息在读取缓存时被规范化为 Queued；
                // 这里立刻恢复自动重试，用户不必手动点重发。
                AppState.retryQueuedNow()
            }
            loadingInit = false
        }
        // 2) 再拉网络刷新
        val res = withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val detail = runCatchingCancellable { Api.conversation(conversationId) }.getOrNull()
                val pageData = Api.messages(conversationId, limit = 50)
                detail to pageData
            }
        }
        res.onSuccess { (detail, pageData) ->
            if (detail != null) { conv = detail; AppState.upsertConversation(detail) }
            AppState.setMessages(conversationId, pageData.messages)
            // 游标分页：是否还能往前翻由服务端 hasMore 决定，不再用 total 推算
            // （total 在有乐观插入的新消息时并不等于本地列表长度）。
            hasMoreCursor = pageData.hasMore
            nextCursor = pageData.nextCursor
            total = maxOf(pageData.total, AppState.messagesOf(conversationId).size)
            page = 1
        }
        res.onFailure { if (cached == null) err = Api.userMessage(it) }
        loadingInit = false
        // 打开会话即标记已读
        AppState.clearUnread(conversationId)
        // 已读上报只对服务端消息有意义，跳过本地待发（其 id 是本地临时 id）
        val lastServer = AppState.messagesOf(conversationId).firstOrNull { !it.isLocalPending }
        if (lastServer != null) reportRead(lastServer.id)
        // 进程重启/重新进入会话：自动续传未完成的媒体上传。
        AppState.pendingMediaUploads(conversationId)
            .forEach { com.rtcomm.app.data.UploadManager.enqueue(conversationId, it) }
    }

    suspend fun loadOlder() {
        if (loadingMore || !hasMoreCursor) return
        val cursor = nextCursor ?: return
        loadingMore = true
        val res = withContext(Dispatchers.IO) {
            runCatchingCancellable { Api.messages(conversationId, before = cursor.before, beforeId = cursor.beforeId, limit = 50) }
        }
        res.onSuccess { p ->
            AppState.appendOlder(conversationId, p.messages)
            hasMoreCursor = p.hasMore
            nextCursor = p.nextCursor
        }
        res.onFailure { err = Api.userMessage(it) }
        loadingMore = false
    }

    /** 把当前引用对象转成与服务端同构的快照，随乐观气泡一起显示，避免发送期间退化为占位符。 */
    fun replySnapshot(r: Message?): com.rtcomm.app.data.ReplyMessage? = r?.let {
        com.rtcomm.app.data.ReplyMessage(
            id = it.id,
            senderId = it.senderId,
            sender = it.sender,
            messageType = it.messageType,
            content = it.content,
            file = it.file,
            isDeleted = it.isDeleted,
        )
    }

    /** P1：乐观发送 —— 本地先插 Sending 气泡，成功替换 / 失败标记可重试。 */
    fun sendText(text: String, replyToId: String? = null, messageType: String = "text") {
        if (text.isBlank()) return
        val localId = newLocalId()
        val pending = Message(
            id = localId, conversationId = conversationId, senderId = meId ?: "",
            sender = AppState.currentUser.value, messageType = messageType, content = text,
            replyToMessageId = replyToId,
            replyTo = if (replyTo?.id != null && replyTo?.id == replyToId) replySnapshot(replyTo) else null,
            createdAt = java.time.Instant.now().toString(), sendState = com.rtcomm.app.data.SendState.Sending,
        )
        AppState.addPending(conversationId, pending)
        scrollToLatest()
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Api.sendMessage(
                        conversationId, text, messageType, null, replyToId,
                        clientMessageId = localId,
                    )
                }
            }
            res.onSuccess { m ->
                if (m != null) {
                    AppState.confirmPending(conversationId, localId, m)
                    AppState.bumpConversation(conversationId, m, false)
                    scrollToLatest()
                } else AppState.failAndQueueRetry(conversationId, localId)
            }
            res.onFailure {
                // 进自动重试队列（指数退避 + WS 恢复立即重发），不再直接定格为失败。
                AppState.failAndQueueRetry(conversationId, localId)
                com.rtcomm.app.data.AppDiagnostics.record(context, "发送消息", it)
                err = Api.userMessage(it)
            }
        }
    }

    fun sendFile(
        meta: com.rtcomm.app.data.FileMeta,
        localPreviewUri: String?,
        replyToId: String?,
        /** 语音等场景需要显式指定类型（不能只靠 MIME 推断）。 */
        forcedType: String? = null,
        contentOverride: String? = null,
    ) {
        val isImage = meta.mimeType?.startsWith("image/") == true
        val localId = newLocalId()
        val pending = Message(
            id = localId, conversationId = conversationId, senderId = meId ?: "",
            sender = AppState.currentUser.value,
            messageType = forcedType ?: if (isImage) "image" else "file",
            content = contentOverride ?: meta.fileName,
            fileId = meta.id,
            file = com.rtcomm.app.data.MessageFile(
                id = meta.id, fileName = meta.fileName, fileSize = meta.fileSize,
                mimeType = meta.mimeType, url = meta.url, createdAt = meta.createdAt,
            ),
            replyToMessageId = replyToId,
            replyTo = if (replyTo?.id != null && replyTo?.id == replyToId) replySnapshot(replyTo) else null,
            createdAt = java.time.Instant.now().toString(),
            sendState = com.rtcomm.app.data.SendState.Sending,
            localPreviewUri = if (isImage) localPreviewUri else null,
        )
        AppState.addPending(conversationId, pending)
        scrollToLatest()
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Api.sendMessage(
                        conversationId, contentOverride ?: meta.fileName, pending.messageType, meta.id, replyToId,
                        clientMessageId = localId,
                    )
                }
            }
            res.onSuccess { m ->
                if (m != null) {
                    AppState.confirmPending(conversationId, localId, m)
                    AppState.bumpConversation(conversationId, m, false)
                    scrollToLatest()
                } else AppState.failAndQueueRetry(conversationId, localId)
            }
            res.onFailure {
                AppState.failAndQueueRetry(conversationId, localId)
                err = Api.userMessage(it)
            }
        }
    }

    /** 录制完成后上传并作为语音消息发出。 */
    fun sendVoice(file: java.io.File) {
        uploading = true; uploadProgress = 0f; err = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    FileTransfer.upload(
                        FileTransfer.Picked(file, file.name, file.length(), "audio/mp4"),
                        conversationId,
                        onProgress = { done, total ->
                            uploadProgress = if (total > 0) done.toFloat() / total else 0f
                        },
                    )
                }
            }
            uploading = false
            file.delete() // 上传完成即删，避免缓存目录堆积
            res.onSuccess { meta -> sendFile(meta, null, replyTo?.id, forcedType = "audio", contentOverride = "[语音]") }
            res.onFailure { err = "语音发送失败：" + Api.userMessage(it) }
        }
    }

    /**
     * 手动重发：直接复用原消息重发，保留 fileId / replyToMessageId。
     * 之前这里是「删掉旧的再当纯文本发一次」，图片和文件消息会因此退化成文本。
     */
    fun retrySend(localId: String) {
        val m = AppState.messagesOf(conversationId).firstOrNull { it.id == localId }
        if (m?.localUploadPath != null) {
            // 媒体上传失败：重新走上传（本地文件仍在）。
            com.rtcomm.app.data.UploadManager.enqueue(conversationId, localId)
        } else {
            AppState.retryPendingNow(conversationId, localId)
        }
    }

    DisposableEffect(conversationId) {
        AppState.activeConversationId.value = conversationId
        onDispose {
            AppState.activeConversationId.value = null
            WsClient.send("typing", mapOf("conversationId" to conversationId, "isTyping" to false))
            // 共享音频播放器是应用级的，离开聊天页要显式停掉。
            com.rtcomm.app.ui.chat.stopAudioPlayback()
        }
    }
    LaunchedEffect(conversationId) { loadInitial() }

    // 预取最近图片缩略图到本地缓存：滚动到消息时直接命中，不再逐个转圈。
    LaunchedEffect(conversationId, msgs.size) {
        val loader = context.imageLoader
        val headers = okhttp3.Headers.Builder().add("Authorization", "Bearer ${Api.token}").build()
        msgs.asSequence()
            .filter { it.messageType == "image" && it.file != null }
            .take(24)
            .forEach { m ->
                val f = m.file ?: return@forEach
                loader.enqueue(
                    coil.request.ImageRequest.Builder(context)
                        .data(Api.downloadUrl(f.id))
                        .headers(headers)
                        .build(),
                )
            }
    }

    // 搜索结果跳转：初次加载完成后，把目标消息逐页翻出来并滚到它。
    LaunchedEffect(conversationId, loadingInit) {
        if (loadingInit) return@LaunchedEffect
        val target = AppState.pendingJumpMessageId.value ?: return@LaunchedEffect
        AppState.pendingJumpMessageId.value = null
        var index = msgs.indexOfFirst { it.id == target }
        var guard = 0
        while (index < 0 && hasMoreCursor && guard < 40) {
            loadOlder()
            index = AppState.messagesOf(conversationId).indexOfFirst { it.id == target }
            guard++
        }
        if (index >= 0) listState.scrollToItem(index) else err = "这条消息已不在可访问的历史中"
    }

    // 未读分界快照：只在第一次拿到「未读数 > 0 且有消息」时定位，避免后续变化让分界线漂移。
    LaunchedEffect(conversationId, conv?.unreadCount, msgs.size) {
        if (firstUnreadId != null) return@LaunchedEffect
        val unread = conv?.unreadCount ?: 0
        if (unread > 0 && msgs.isNotEmpty()) {
            // msgs 最新在前；跳过自己发的，取第 unread 条（下标 unread-1）作为首条未读。
            firstUnreadId = msgs.filter { it.senderId != meId }.getOrNull(unread - 1)?.id
        }
    }

    // 输入中：只在「开始输入」时发一次 typing:true；停止 1.5s 或清空输入时补发 typing:false。
    // 之前每次按键都重发 true、且清空时不发 false，对端会一直显示「正在输入…」。
    val typingFlag = remember { Flag() }
    LaunchedEffect(input.text) {
        val active = input.text.isNotBlank()
        if (active && !typingFlag.value) {
            WsClient.send("typing", mapOf("conversationId" to conversationId, "isTyping" to true))
            typingFlag.value = true
        }
        if (active) kotlinx.coroutines.delay(1500)
        if (typingFlag.value) {
            WsClient.send("typing", mapOf("conversationId" to conversationId, "isTyping" to false))
            typingFlag.value = false
        }
    }

    // 新消息（列表 index 0）到达时，仅在底部附近自动定位，避免用户查看历史时被强制拉回。
    val isAtNewest by remember {
        derivedStateOf { (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80) }
    }
    /** 当前用户自己插入的新消息不应显示“新消息”提示，且始终保持底部定位。 */
    LaunchedEffect(msgs.firstOrNull()?.id) {
        val first = msgs.firstOrNull()
        val firstId = first?.id
        val isOwnMessage = first?.senderId == meId
        if (firstId != null && previousFirstMessageId != null && firstId != previousFirstMessageId) {
            if (isOwnMessage || isAtNewest) {
                unreadNewCount = 0
                listState.animateScrollToItem(0)
            } else {
                unreadNewCount++
            }
        }
        previousFirstMessageId = firstId
    }

    // 页面停留在本会话且用户位于最新位置时才上报已读；正在翻看历史时不清理未读，
    // 否则会出现「界面提示 N 条新消息、服务端未读却已清零」的矛盾。
    LaunchedEffect(msgs.firstOrNull()?.id, isAtNewest) {
        val first = msgs.firstOrNull() ?: return@LaunchedEffect
        if (first.isLocalPending || first.senderId == meId) return@LaunchedEffect
        if (AppState.activeConversationId.value == conversationId && isAtNewest) {
            AppState.clearUnread(conversationId)
            reportRead(first.id)
            unreadNewCount = 0
        }
    }

    // 分页：滚动到较旧一端时加载更多
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            msgs.isNotEmpty() && last >= msgs.size - 3
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore) { if (shouldLoadMore && hasMore) loadOlder() }

    /** 定位引用消息：内存中已有则直接滚动，否则逐页加载直到找到或没有更多。 */
    fun jumpToQuotedMessage(messageId: String) {
        scope.launch {
            var target = AppState.messagesOf(conversationId).indexOfFirst { it.id == messageId }
            var previousSize = AppState.messagesOf(conversationId).size
            var guard = 0
            while (target < 0 && guard < 40) {
                loadOlder()
                val currentSize = AppState.messagesOf(conversationId).size
                if (currentSize <= previousSize) break
                previousSize = currentSize
                target = AppState.messagesOf(conversationId).indexOfFirst { it.id == messageId }
                guard++
            }
            if (target >= 0) {
                listState.animateScrollToItem(target)
                snackbar.showSnackbar("已定位到引用消息")
            } else {
                snackbar.showSnackbar("引用消息不在当前可访问的历史中")
            }
        }
    }

    val othersTyping = typingUserIds.any { it != meId }
    val peer = conv?.let { c -> if (c.type == "direct") c.members.firstOrNull { it.id != meId } else null }
    // 只订阅这位用户的在线状态：其他用户上下线不会让本页重组。
    val peerOnline by remember(peer?.id) { AppState.onlineFlow(peer?.id) }
        .collectAsStateWithLifecycle(peer?.isOnline == true)
    val subtitle = when {
        othersTyping -> "对方正在输入…"
        conv?.type == "group" -> "群组 · ${conv?.members?.size ?: 0} 人"
        peer != null -> if (peerOnline || peer.isOnline) stringResource(R.string.status_online) else stringResource(R.string.status_offline)
        else -> ""
    }

    // 全部在 graphicsLayer 里按需计算：读的是 lambda，重组不会发生。
    val messagesLayer = Modifier.graphicsLayer {
        val p = progress()
        val c = ((p - 0.375f) / 0.625f).coerceIn(0f, 1f)
        alpha = c
        translationY = (1f - c) * 16.dp.toPx()
    }
    val composerLayer = Modifier.graphicsLayer {
        val p = progress()
        val c = ((p - 0.625f) / 0.375f).coerceIn(0f, 1f)
        alpha = c
        translationY = (1f - c) * 72.dp.toPx()
    }
    val headerLayer = Modifier.graphicsLayer {
        // 与共享标题的淡出窗口（0.52 → 0.92）严格对齐，交接点位置基本重合。
        alpha = ((progress() - 0.52f) / 0.40f).coerceIn(0f, 1f)
    }

    // —— F-22：稳定传给 MessageBubble 的回调 ——
    // 之前每个回调都是内联 lambda，父级每次重组（新消息、输入中状态等）都会生成新实例，
    // 让列表里所有可见气泡参数不相等而整体重组。这里收敛为 remember 稳定引用：只捕获稳定
    // 对象（状态委托 / remember 的 scope、snackbar / 控制器）；随消息变化的 imageFiles 用
    // rememberUpdatedState 读取最新值，lambda 本身保持稳定。
    val imageFilesRef = rememberUpdatedState(imageFiles)
    val onDownloadStable: (com.rtcomm.app.data.MessageFile) -> Unit = remember(scope, context, snackbar) {
        { file -> scope.launch { downloadFile(context, file.id, file.fileName, snackbar) } }
    }
    val onReplyStable: (Message) -> Unit = remember(conversationId) { { replyTo = it } }
    val onQuotedClickStable: (String) -> Unit = remember(conversationId) { { id -> jumpToQuotedMessage(id) } }
    val onAvatarClickStable: (com.rtcomm.app.data.PublicUser) -> Unit = remember(avatar) { { u -> avatar.openUser(u) } }
    val onImageClickStable: (com.rtcomm.app.data.MessageFile) -> Unit = remember {
        { file ->
            val files = imageFilesRef.value
            val idx = files.indexOfFirst { it.id == file.id }
            if (idx >= 0) {
                // 快照当前列表：查看期间新消息到达不会移动已打开的图片/页码。
                viewerImages = files
                viewerIndex = idx
            }
        }
    }
    val onCancelUploadStable: (Message) -> Unit = remember(conversationId) {
        { um -> com.rtcomm.app.data.UploadManager.cancel(conversationId, um.id) }
    }
    val onRetryUploadStable: (Message) -> Unit = remember(conversationId) {
        { um -> com.rtcomm.app.data.UploadManager.enqueue(conversationId, um.id) }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusTop + 64.dp
    Scaffold(
        // 展开期间由 ConversationFlow 的展开层提供不透明背景，这里保持透明避免二次覆盖。
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // 顶栏改为毛玻璃覆盖层（实时模糊其正下方内容/壁纸），Scaffold 不再为它占位。
        topBar = {},
        // contentWindowInsets 归零后 Scaffold 不会为 Snackbar 预留安全区，
        // 须自行避开导航栏与输入法，否则提示会压在输入栏下方或被 IME 遮住。
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime),
                ),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { pad ->
        GlassHeaderBox(
            headerHeight = headerHeight,
            header = {
                // 多选态与正常态顶栏之间淡切，替代硬替换。
                FadeSwap(target = selectionMode) { selecting ->
                if (selecting) {
                    // 多选态顶栏：只保留退出与计数，避免误触其它操作。
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selectionMode = false; selectedIds = emptySet() }) {
                                Icon(Icons.Filled.Close, "退出多选")
                            }
                        },
                        title = { TopBarTitle("已选 ${selectedIds.size} 条") },
                        actions = {
                            TextButton(onClick = {
                                val picked = msgs.filter { selectedIds.contains(it.id) }
                                val text = picked
                                    .mapNotNull { it.content?.takeIf { c -> c.isNotBlank() } }
                                    .joinToString(separator = "\n")
                                if (text.isBlank()) {
                                    scope.launch { snackbar.showSnackbar("选中的消息没有可复制的文本") }
                                } else {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                                    scope.launch { snackbar.showSnackbar("已复制 ${picked.size} 条") }
                                }
                            }) { Text(stringResource(R.string.action_copy)) }
                            TextButton(onClick = {
                                val picked = msgs.filter { selectedIds.contains(it.id) }
                                val forwardable = picked.filter { it.canForward() }
                                when {
                                    forwardable.isEmpty() ->
                                        scope.launch { snackbar.showSnackbar("选中的消息无法转发（媒体可能还在上传）") }
                                    forwardable.size < picked.size -> {
                                        scope.launch { snackbar.showSnackbar("已跳过 ${picked.size - forwardable.size} 条待上传/已撤回的消息") }
                                        forwardTargets = forwardable
                                    }
                                    else -> forwardTargets = forwardable
                                }
                            }) { Text("转发") }
                        },
                    )
                } else {
                    AppTopBarContent(
                        modifier = headerLayer,
                        onBack = onBack,
                        title = {
                            Column {
                                Text(
                                    conv?.name ?: "聊天",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.onGloballyPositioned { onHeaderTitleBounds(it.boundsInRoot()) },
                                )
                                // 副标题在「在线 / 正在输入 / 群组」间淡切，避免文字硬跳。
                                Crossfade(
                                    targetState = subtitle,
                                    animationSpec = tween(if (reducedMotion) 0 else 160),
                                    label = "chat-subtitle",
                                ) { s ->
                                    if (s.isNotEmpty()) {
                                        Text(s, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.MoreVert, "聊天信息") }
                        },
                    )
                }
                }
            },
        ) {
        Box(Modifier.fillMaxSize().padding(pad)) {
            // 背景层必须随展开进度淡入：展开期间由 ExpandFlow 的共享卡片提供不透明背景，
            // 这里若从第一帧就画不透明背景，会把整块「卡片展开」动画盖住（AI 页无背景层所以不受影响）。
            Box(
                Modifier.matchParentSize().graphicsLayer {
                    alpha = ((progress() - 0.35f) / 0.45f).coerceIn(0f, 1f)
                },
            ) {
                // 全屏聊天背景：放在玻璃标题栏正下方，让标题栏实时模糊它。
                val hasCustomBg = chatBackground != null
                if (hasCustomBg) {
                    coil.compose.AsyncImage(
                        model = java.io.File(chatBackground!!),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.matchParentSize().then(
                            if (chatBgBlur > 0 && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(chatBgBlur.dp) else Modifier,
                        ),
                    )
                } else {
                    ChatWallpaperBackground(style = ChatWallpaper.from(effectiveWallpaper), modifier = Modifier.matchParentSize())
                }
                // 暗化遮罩：自定义图片用 chatBgDim（默认 10%，与旧版一致）；
                // 内置样式在默认值下不叠加，只有用户调高时才生效，保持默认观感不变。
                val dimAlpha = if (hasCustomBg) {
                    chatBgDim.coerceIn(0f, 0.9f)
                } else {
                    (chatBgDim - 0.10f).coerceIn(0f, 0.9f)
                }
                if (dimAlpha > 0f) {
                    Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = dimAlpha)))
                }
            }
        Column(Modifier.fillMaxSize().padding(top = headerHeight)) {
            Reveal(uploading) {
                LinearProgressIndicator(progress = { uploadProgress }, modifier = Modifier.fillMaxWidth())
            }
            err?.let { ErrorBanner(it) }
            // 会话内搜索：输入框 + 命中列表（本地过滤已加载消息）。
            Reveal(searchOpen) {
                Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("在会话中搜索") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { searchOpen = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, "关闭搜索")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (searchHits.isNotEmpty()) {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                            items(searchHits, key = { "hit-" + it.id }) { hit ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        val idx = msgs.indexOfFirst { it.id == hit.id }
                                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                                        searchOpen = false; searchQuery = ""
                                    },
                                    headlineContent = {
                                        Text(
                                            com.rtcomm.app.ui.common.Markdown.previewInline(hit.content).let {
                                                if (it.text.isNotEmpty()) it
                                                else androidx.compose.ui.text.AnnotatedString(if (hit.isFileType) "[文件]" else "[消息]")
                                            },
                                            maxLines = 1,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            (hit.sender?.displayName ?: "") + " · " + Format.dateTime(hit.createdAt),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(messagesLayer),
            ) {
                // 首屏加载 → 空 → 消息列表 之间淡切（与 AI 会话一致），替代硬替换。
                FadeSwap(target = if (loadingInit && msgs.isEmpty()) 0 else if (msgs.isEmpty()) 1 else 2) { st ->
                when (st) {
                    0 -> CircularProgressIndicator(Modifier.align(Alignment.Center), strokeWidth = 3.dp)
                    1 -> Text("还没有消息，发送第一条吧", Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compactMessages) 3.dp else 8.dp),
                    ) {
                        itemsIndexed(
                            items = msgs,
                            key = { _, m -> m.id },
                            contentType = { _, m -> m.messageType },
                        ) { i, m ->
                            // 反向布局下 i+1 更旧；日期不同说明 m 是当天最早的一条，在其上方画日期分隔。
                            val showDate = i == msgs.lastIndex || !Format.sameDay(m.createdAt, msgs[i + 1].createdAt)
                            Column(if (reducedMotion) Modifier else Modifier.animateItem()) {
                                if (showDate) DateDivider(Format.dayLabel(m.createdAt))
                                // 分界线画在首条未读消息的顶部：reverseLayout 下它正好位于已读与新消息之间。
                                if (m.id == firstUnreadId) UnreadDivider()
                                MessageBubble(
                                    m = m,
                                    isMe = m.senderId == meId,
                                    isGroup = conv?.type == "group",
                                    selectionMode = selectionMode,
                                    selected = selectedIds.contains(m.id),
                                    compact = compactMessages,
                                    onToggleSelect = toggleSelect,
                                    onSelect = startSelect,
                                    onDownload = onDownloadStable,
                                    onReply = onReplyStable,
                                    onQuotedMessageClick = onQuotedClickStable,
                                    // 只有它必须按消息捕获，故按消息内容稳定：内容不变则引用不变。
                                    onLongPress = remember(m, haptic) {
                                        {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actionTarget = m
                                        }
                                    },
                                    onAvatarClick = onAvatarClickStable,
                                    onImageClick = onImageClickStable,
                                    onCancelUpload = onCancelUploadStable,
                                    onRetryUpload = onRetryUploadStable,
                                )
                            }
                        }
                        if (loadingMore) {
                            item(key = "loadingMore") {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = unreadNewCount > 0,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)) + slideInVertically(tween(if (reducedMotion) 0 else 180)) { it / 2 },
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                ) {
                    Button(onClick = {
                        unreadNewCount = 0
                        scope.launch { listState.animateScrollToItem(0) }
                    }) { Text("↓ ${unreadNewCount} 条新消息") }
                }
                // 「定位未读」：跳到分界线并收起，避免和「N 条新消息」抢同一个位置。
                androidx.compose.animation.AnimatedVisibility(
                    visible = firstUnreadId != null,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)),
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) {
                    FilledTonalButton(onClick = {
                        val target = msgs.indexOfFirst { it.id == firstUnreadId }
                        if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                        firstUnreadId = null
                    }) { Text("未读") }
                }
                // 「回到底部」：向上翻阅历史时提供快捷返回（与 AI 会话一致）。
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtNewest && msgs.isNotEmpty(),
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)) + slideInVertically(tween(if (reducedMotion) 0 else 180)) { it / 2 },
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 60.dp),
                ) {
                    FilledTonalIconButton(onClick = { scrollToLatest() }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到底部")
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = othersTyping,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)) + slideInVertically(tween(if (reducedMotion) 0 else 180)) { it / 2 },
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(Corner.small), color = MaterialTheme.colorScheme.surfaceVariant) {
                        TypingDots(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), reducedMotion = reducedMotion)
                    }
                }
            }

            // 输入区在 250ms 后从底部进入；退出时沿同一路径先离开。
            Column(composerLayer) {
                // 回复引用预览条：从输入区上方自然展开/收起。
            AnimatedVisibility(
                visible = replyTo != null,
                enter = expandVertically(tween(if (reducedMotion) 0 else 220), expandFrom = Alignment.Bottom) + fadeIn(tween(if (reducedMotion) 0 else 180)),
                exit = shrinkVertically(tween(if (reducedMotion) 0 else 180), shrinkTowards = Alignment.Bottom) + fadeOut(tween(if (reducedMotion) 0 else 140)),
            ) {
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(3.dp).height(34.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "回复 " + (replyTo?.sender?.displayName?.takeIf { it.isNotBlank() } ?: "对方"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                replyTo?.let { r ->
                                    when {
                                        r.isDeleted -> androidx.compose.ui.text.AnnotatedString("[原消息已撤回]")
                                        r.messageType == "image" -> androidx.compose.ui.text.AnnotatedString("[图片]")
                                        r.messageType == "video" -> androidx.compose.ui.text.AnnotatedString("[视频]")
                                        r.messageType == "audio" -> androidx.compose.ui.text.AnnotatedString("[语音]")
                                        else -> com.rtcomm.app.ui.common.Markdown.previewInline(r.content).let {
                                            if (it.text.isNotEmpty()) it
                                            else androidx.compose.ui.text.AnnotatedString(if (r.isFileType) "[文件]" else "")
                                        }
                                    }
                                } ?: androidx.compose.ui.text.AnnotatedString(""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, "取消回复") }
                    }
                }
            }

            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                uploading = uploading,
                onSend = {
                    val text = input.text.trim()
                    if (text.isEmpty()) return@ChatInputBar
                    sendText(text, replyTo?.id, if (composeMarkdown) "markdown" else "text")
                    input = androidx.compose.ui.text.input.TextFieldValue("")
                    replyTo = null
                },
                markdownMode = composeMarkdown,
                onMarkdownModeChange = { composeMarkdown = it },
                conversationId = conversationId,
                replyToId = replyTo?.id,
                members = conv?.members ?: emptyList(),
                isGroup = conv?.type == "group",
                onUploadError = { err = it },
                onVoiceRecorded = { file -> sendVoice(file) },
            )
            }
        }
        }
        }
    }

    // P2：长按消息操作
    actionTarget?.let { target ->
        MessageActionDialog(
            m = target,
            isMine = target.senderId == meId,
            onDismiss = { actionTarget = null },
            onReply = { replyTo = target; actionTarget = null },
            onCopy = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(target.content ?: ""))
                actionTarget = null
                scope.launch { snackbar.showSnackbar("已复制") }
            },
            onForward = {
                // 图片/文件现在也能转发：后端允许把自己上传的文件转发到其它会话。
                val hasFile = !target.fileId.isNullOrBlank()
                when {
                    // 还在本地上传的媒体没有 fileId，转发出去会是一条坏消息。
                    target.localUploadPath != null && !hasFile -> {
                        actionTarget = null
                        scope.launch { snackbar.showSnackbar("媒体还在上传中，请等待上传完成后再转发") }
                    }
                    target.isDeleted || (target.content.isNullOrBlank() && !hasFile) -> {
                        actionTarget = null
                        scope.launch { snackbar.showSnackbar("这条消息无法转发") }
                    }
                    else -> {
                        forwardTargets = listOf(target)
                        actionTarget = null
                    }
                }
            },
            onEdit = {
                editTarget = target
                actionTarget = null
            },
            onDeleteLocal = {
                val id = target.id
                actionTarget = null
                AppState.deleteLocal(conversationId, id)
                scope.launch { snackbar.showSnackbar("已在本机删除（对方仍可见）") }
            },
            onMultiSelect = {
                selectionMode = true
                selectedIds = setOf(target.id)
                actionTarget = null
            },
            onRecall = {
                val id = target.id
                actionTarget = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.deleteMessage(id) } }
                    r.onSuccess { AppState.markDeleted(conversationId, id) }
                    r.onFailure { err = Api.userMessage(it) }
                }
            },
            onRetry = { retrySend(target.id); actionTarget = null },
            onAddSticker = {
                val file = target.file
                val fileId = target.fileId
                actionTarget = null
                if (file == null || fileId.isNullOrBlank()) {
                    scope.launch { snackbar.showSnackbar("图片尚未上传完成") }
                } else {
                    scope.launch {
                        val path = withContext(Dispatchers.IO) {
                            com.rtcomm.app.data.FileTransfer.importEmojiFromFile(context, fileId, file.fileName)
                        }
                        if (path != null) {
                            AppState.setCustomEmojis(AppState.customEmojis.value + path)
                            snackbar.showSnackbar("已添加为表情")
                        } else {
                            snackbar.showSnackbar("添加失败，请重试")
                        }
                    }
                }
            },
        )
    }

    // 编辑消息
    editTarget?.let { target ->
        EditMessageDialog(
            message = target,
            onDismiss = { editTarget = null },
            onSave = { newContent ->
                editTarget = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Api.editMessage(target.id, newContent) }
                    }
                    r.onSuccess { updated ->
                        if (updated != null) AppState.upsertMessage(conversationId, updated)
                        snackbar.showSnackbar("已保存")
                    }.onFailure { err = Api.userMessage(it) }
                }
            },
        )
    }

    forwardTargets.takeIf { it.isNotEmpty() }?.let { targets ->
        ForwardMessageDialog(
            messages = targets,
            currentConversationId = conversationId,
            onDismiss = { forwardTargets = emptyList() },
            onForward = { targetConversationId ->
                forwardTargets = emptyList()
                selectionMode = false
                selectedIds = emptySet()
                scope.launch {
                    // 逐条转发：保持顺序，任一条失败不阻断其余，最后汇总结果。
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            var ok = 0
                            targets.forEach { one ->
                                val sent = runCatching {
                                    Api.sendMessage(
                                        targetConversationId,
                                        one.content.orEmpty(),
                                        one.messageType,
                                        one.fileId,
                                    )
                                }.getOrNull()
                                if (sent != null) {
                                    AppState.addMessage(targetConversationId, sent)
                                    AppState.bumpConversation(targetConversationId, sent, false)
                                    ok++
                                }
                            }
                            ok to targets.size
                        }
                    }
                    result.onSuccess { (ok, totalCount) ->
                        snackbar.showSnackbar(
                            if (ok == totalCount) "已转发 $ok 条" else "转发完成 $ok/$totalCount 条",
                        )
                    }.onFailure { snackbar.showSnackbar("转发失败：${Api.userMessage(it)}") }
                }
            },
        )
    }

    // 统一图片查看器：会话内左右滑。用打开时的快照，避免新消息把当前图片顶走。
    val viewerSnapshot = viewerImages
    val viewerStart = viewerIndex
    if (viewerSnapshot != null && viewerStart != null) {
        MediaViewerDialog(
            images = viewerSnapshot,
            initialIndex = viewerStart,
            onDismiss = {
                viewerIndex = null
                viewerImages = null
            },
        )
    }

    if (showMembers) conv?.let {
        MembersSheet(
            it,
            onDismiss = { showMembers = false },
            onReload = { scope.launch { loadInitial() } },
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
        )
    }
    if (showSummary) SummarySheet(conversationId, onDismiss = { showSummary = false })
    if (showCall) CallDialog(conversationId, onDismiss = { showCall = false }, snackbar = snackbar)

    // 聊天信息聚合：把原先散落在顶栏的多个图标与长按菜单收口到一处。
    if (showInfo) {
        ChatInfoSheet(
            title = conv?.name ?: "聊天",
            isGroup = conv?.type == "group",
            memberCount = conv?.members?.size ?: 0,
            muted = muted,
            pinned = pinned,
            onDismiss = { showInfo = false },
            onCall = { showCall = true },
            onSummary = { showSummary = true },
            onMembers = { showMembers = true },
            onEditGroup = { showEditGroup = true },
            onToggleMute = {
                muted = !muted
                authStore.setMuted(conversationId, muted)
            },
            onTogglePin = {
                pinned = !pinned
                authStore.setPinned(conversationId, pinned)
            },
            onSearch = { searchOpen = true },
            onClear = { confirmClear = true },
            onLeave = { confirmLeave = true },
            wallpaper = convWallpaper,
            onWallpaper = { key ->
                if (key == "inherit") {
                    convWallpaper = null
                    authStore.setConversationWallpaper(conversationId, null)
                } else {
                    convWallpaper = key
                    authStore.setConversationWallpaper(conversationId, key)
                }
            },
        )
    }
    if (showEditGroup) {
        GroupEditDialog(
            conversationId = conversationId,
            initialName = conv?.name ?: "",
            initialAvatarUrl = conv?.avatarUrl,
            onDismiss = { showEditGroup = false },
            onUpdated = { c -> AppState.upsertConversation(c) },
        )
    }
    if (confirmClear) {
        AppAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空聊天记录") },
            text = { Text("仅清除本机记录，对方仍可见。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    AppState.clearMessages(conversationId)
                    scope.launch { snackbar.showSnackbar("已清空本机聊天记录") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmLeave) {
        AppAlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("退出会话") },
            text = { Text("退出后该会话将从列表中移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Api.leaveConversation(conversationId) } }
                        r.onSuccess { AppState.removeConversation(conversationId); onBack() }
                        r.onFailure { err = Api.userMessage(it) }
                    }
                }) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private suspend fun downloadFile(context: android.content.Context, fileId: String, fileName: String, snackbar: SnackbarHostState) {
    val res = withContext(Dispatchers.IO) { runCatching { FileTransfer.download(context, fileId, fileName) } }
    res.onSuccess { file ->
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, FileTransfer.guessMime(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val choice = snackbar.showSnackbar("已保存到应用目录：" + file.name, actionLabel = "打开")
        if (choice == SnackbarResult.ActionPerformed) {
            runCatching { context.startActivity(open) }
        }
    }
    res.onFailure { snackbar.showSnackbar("下载失败：" + Api.userMessage(it)) }
}


