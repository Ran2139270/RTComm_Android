package com.rtcomm.app.data

import com.rtcomm.app.data.db.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** WebSocket 连接状态。 */
enum class WsState { Disconnected, Connecting, Connected }

/**
 * 全局实时状态（内存）。消息列表保持“最新在前”。
 *
 * 数据权威性约定（Room 的定位）：
 * - **服务端是唯一权威**：会话列表、消息历史都以服务端返回为准。
 * - Room 只是「冷启动秒开 + 离线可读」的镜像，绝不作为写入来源，也不参与冲突裁决。
 * - 本地独有的只有两类：未发送完成的乐观消息（sendState != null）与阅读位置，
 *   它们以 localId 为键参与合并，服务端返回同 id 时由 confirmPending 替换。
 * - 缓存有上限（内存 24 个会话、Room 每会话 500 条），超限丢最旧；历史仍可从服务端取回。
 *
 * 并发模型：所有内存状态的“读取→修改→写回”都在同一把锁内完成。
 * WS 回调（OkHttp 线程）、Compose 主线程与重试协程都会调用这里的 mutation，
 * 不加锁时并发消息会互相覆盖（丢消息、未读少加、会话顺序回退）。
 *
 * 账号隔离：Room 按 [accountKey]（server|userId）分区；`reset()` 会取消全部重试、
 * 清空待导航状态，并异步清理旧账号缓存。
 */
object AppState {
    /** 自动重试最多尝试次数（之后落到 Failed，只能手动重发）。 */
    private const val MAX_SEND_ATTEMPTS = 6
    /** 退避上限。 */
    private const val MAX_RETRY_DELAY_MS = 30_000L

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    @Volatile private var cacheEpoch = 0L

    /** 保护全部内存状态的复合更新；持锁期间不做网络/磁盘 IO。 */
    private val lock = Any()

    val currentUser = MutableStateFlow<PublicUser?>(null)
    val loggedIn = MutableStateFlow(false)
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val wsState = MutableStateFlow(WsState.Disconnected)
    val onlineUserIds = MutableStateFlow<Set<String>>(emptySet())
    val typing = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val activeConversationId = MutableStateFlow<String?>(null)
    val themeMode = MutableStateFlow("system")
    /** Material You 动态取色开关（Android 12+ 生效）。 */
    val dynamicColor = MutableStateFlow(false)
    /** AMOLED 纯黑表面（仅深色主题生效）。 */
    val amoled = MutableStateFlow(false)
    /** 外观/聊天个性化（由本地偏好初始化）。 */
    val themePreset = MutableStateFlow("blue")
    /** 自定义主色 ARGB（-1 未设置）。 */
    val customPrimary = MutableStateFlow(-1)
    /** 深色时段（themeMode=time）。 */
    val darkStart = MutableStateFlow(19)
    val darkEnd = MutableStateFlow(7)
    val fontScale = MutableStateFlow("default")
    val chatDensity = MutableStateFlow("comfortable")
    /** 聊天背景（内置样式 key）。 */
    val chatWallpaper = MutableStateFlow("default")
    /** 自定义聊天背景图片路径（null 表示用内置样式）。 */
    val chatBackground = MutableStateFlow<String?>(null)
    /** 自定义主界面背景图片路径。 */
    val appBackground = MutableStateFlow<String?>(null)
    /** 主界面背景暗化强度（0..1）与模糊半径（dp，仅 Android 12+）。 */
    val bgDim = MutableStateFlow(0.82f)
    val bgBlur = MutableStateFlow(0)
    /** 聊天背景暗化强度（0..1）与模糊半径（dp，仅 Android 12+）；与主界面独立。 */
    val chatBgDim = MutableStateFlow(0.10f)
    val chatBgBlur = MutableStateFlow(0)
    /** 字体族：system/serif/mono。 */
    val fontFamily = MutableStateFlow("system")
    /** AI 深度思考开关与思考内容展示开关。 */
    val aiThinking = MutableStateFlow(false)
    val aiShowThinking = MutableStateFlow(true)
    /** 聊天气泡圆角：small/medium/large。 */
    val bubbleCorner = MutableStateFlow("medium")
    /** 列表头像圆角方形开关。 */
    val roundedAvatars = MutableStateFlow(false)
    val reduceMotion = MutableStateFlow("system")
    // 减少透明/毛玻璃：true=关闭实时模糊，标题栏用纯半透明。
    val reduceTransparency = MutableStateFlow(false)
    val timeFormat = MutableStateFlow("system")
    /** 快捷回复（由设置页写入，聊天页订阅，避免「改了要重进才生效」）。 */
    val quickReplies = MutableStateFlow<List<String>>(emptyList())
    /** 自定义表情（本地图片/GIF 路径），聊天输入区订阅。 */
    val customEmojis = MutableStateFlow<List<String>>(emptyList())
    /**
     * 由 MainActivity 注入，供没有 AuthStore 参数的组件持久化偏好（如自定义表情）。
     */
    var authStore: AuthStore? = null
    /**
     * 系统是否为 24 小时制。非可观察值：由 Activity 在启动/回前台时刷新，
     * 供非 Composable 的 [com.rtcomm.app.ui.common.Format] 在「跟随系统」时读取。
     */
    @Volatile var system24Hour: Boolean = true
    val pendingOpenConversationId = MutableStateFlow<String?>(null)
    /** 与 [pendingOpenConversationId] 搭配：打开后要定位到的消息 id（搜索结果跳转用）。 */
    val pendingJumpMessageId = MutableStateFlow<String?>(null)
    val inForeground = MutableStateFlow(false)

    /**
     * 顶层共享 FAB 的触发信号：MainScaffold 的 FAB 位于 NavHost 动画之外（切 Tab 时不会被一起
     * 缩放/平移），点击后发信号，对应页面消费并复位，再打开自己的弹层/选择器。
     */
    val fabNewConversation = MutableStateFlow(false)
    val fabCreateBot = MutableStateFlow(false)
    val fabRegisterDevice = MutableStateFlow(false)
    val fabUploadFile = MutableStateFlow(false)

    /** 当前账号隔离键；空串表示未登录。 */
    @Volatile var accountKey: String = ""
        private set

    private fun keyOf(user: PublicUser?): String =
        user?.let { "${Api.baseUrl.trimEnd('/')}|${it.id}" } ?: ""

    private fun persist(block: suspend () -> Unit) {
        val epoch = cacheEpoch
        persistScope.launch {
            persistenceMutex.withLock {
                if (epoch == cacheEpoch) runCatching { block() }
            }
        }
    }

    /** 每写入多少条消息才真正执行一次 Room 裁剪，避免每条消息都跑一次 DELETE。 */
    private const val PRUNE_EVERY_MESSAGE_WRITES = 20
    private val pruneCounters =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /**
     * 持久化消息后按上限裁剪该会话的 Room 缓存。
     * 旧实现只在翻历史（appendOlder）时裁剪，日常收发不让缓存有界，Room 会无限膨胀。
     * 用计数节流，兼顾“有界”与写入开销。
     */
    private suspend fun saveMessagesAndPrune(convId: String, list: List<Message>, replace: Boolean) {
        Repo.saveMessages(convId, list, replace)
        val n = pruneCounters
            .computeIfAbsent(convId) { java.util.concurrent.atomic.AtomicInteger() }
            .incrementAndGet()
        if (n % PRUNE_EVERY_MESSAGE_WRITES == 0) Repo.pruneMessages(convId)
    }

    /** 登录成功：绑定账号隔离键并进入登录态。 */
    fun signIn(user: PublicUser?) {
        val key = keyOf(user)
        var staleKey: String? = null
        var oldJobs: List<Job> = emptyList()
        synchronized(lock) {
            // 账号变化（含登录、换号、隐式登出）时与 reset() 做同样的事：
            // 失效此前排队的持久化写入，并取消旧账号的自动重试，避免旧数据写进新分区。
            if (accountKey != key) {
                staleKey = accountKey.ifEmpty { null }
                cacheEpoch++
                conversations.value = emptyList()
                messages.value = emptyMap()
                onlineUserIds.value = emptySet()
                typing.value = emptyMap()
                activeConversationId.value = null
                pendingOpenConversationId.value = null
                pendingJumpMessageId.value = null
                convRecency.clear()
                oldJobs = retryJobs.values.toList()
                retryJobs.clear()
            }
            accountKey = key
            Repo.accountKey = key
            currentUser.value = user
            loggedIn.value = user != null
        }
        oldJobs.forEach { it.cancel() }
        staleKey?.let { old ->
            persistScope.launch { persistenceMutex.withLock { runCatching { Repo.clearAccount(old) } } }
        }
    }

    /** 清掉内存与本地缓存，并使此前排队的写入全部失效。 */
    fun reset() {
        val oldKey: String
        val oldJobs: List<Job>
        synchronized(lock) {
            cacheEpoch++
            oldKey = accountKey
            accountKey = ""
            Repo.accountKey = ""
            currentUser.value = null
            loggedIn.value = false
            conversations.value = emptyList()
            messages.value = emptyMap()
            wsState.value = WsState.Disconnected
            onlineUserIds.value = emptySet()
            typing.value = emptyMap()
            activeConversationId.value = null
            pendingOpenConversationId.value = null
            pendingJumpMessageId.value = null
            convRecency.clear()
            oldJobs = retryJobs.values.toList()
            retryJobs.clear()
        }
        // 取消旧账号的全部自动重试，避免登录新账号后旧消息注入新状态。
        oldJobs.forEach { it.cancel() }
        if (oldKey.isNotEmpty()) {
            persistScope.launch {
                persistenceMutex.withLock {
                    runCatching { Repo.clearAccount(oldKey) }
                }
            }
        }
    }

    fun messagesOf(convId: String): List<Message> = messages.value[convId] ?: emptyList()

    /**
     * 订阅**单个会话**的消息：其它会话的变动不会触发订阅者重组。
     * 之前 UI 直接订阅整个 `messages` Map，任何会话来消息都会让当前聊天页重组一次。
     */
    fun messagesFlow(convId: String): kotlinx.coroutines.flow.Flow<List<Message>> =
        messages.map { it[convId] ?: emptyList() }.distinctUntilChanged()

    /** 订阅单个会话的“正在输入”集合。 */
    fun typingFlow(convId: String): kotlinx.coroutines.flow.Flow<Set<String>> =
        typing.map { it[convId] ?: emptySet() }.distinctUntilChanged()

    /** 单个用户是否在线：只在该用户上线/下线时发出变化。 */
    fun onlineFlow(userId: String?): kotlinx.coroutines.flow.Flow<Boolean> =
        onlineUserIds.map { userId != null && it.contains(userId) }.distinctUntilChanged()

    /**
     * 内存中的消息缓存上限：超过后按“最近被写入”的顺序淘汰。
     * 不设上限时，长时间使用会一直缓存所有打开过的会话消息。
     */
    private const val MAX_CACHED_CONVERSATIONS = 24
    private val convRecency = ArrayDeque<String>()

    /** 记录会话被写入/访问，并在超限时淘汰最久未用（当前打开的会话永不淘汰）。 */
    private fun touchConversationLocked(convId: String) {
        convRecency.remove(convId)
        convRecency.addFirst(convId)
        if (convRecency.size <= MAX_CACHED_CONVERSATIONS) return
        val protected = activeConversationId.value
        val iterator = convRecency.listIterator(convRecency.size)
        while (iterator.hasPrevious()) {
            val candidate = iterator.previous()
            if (candidate == protected) continue
            iterator.remove()
            messages.value = messages.value.toMutableMap().apply { remove(candidate) }
            break
        }
    }

    private fun dedupeMessages(list: List<Message>): List<Message> {
        val seen = HashSet<String>()
        return list.filter { it.id.isNotBlank() && seen.add(it.id) }
    }

    private fun sortMessages(list: List<Message>): List<Message> = list.sortedWith(
        compareByDescending<Message> { it.createdAt ?: "" }.thenByDescending { it.id },
    )

    /**
     * 合并页级网络结果。消息 API 当前是分页接口，不能把“第一页”当作完整快照并清掉
     * 已缓存的历史页；单条变更一律 upsert。
     */
    fun setMessages(convId: String, list: List<Message>) {
        val incoming = dedupeMessages(list)
        synchronized(lock) {
            val byId = LinkedHashMap<String, Message>()
            incoming.forEach { byId[it.id] = it }
            messagesOf(convId).forEach { existing -> byId.putIfAbsent(existing.id, existing) }
            val merged = sortMessages(byId.values.toList())
            messages.value = messages.value.toMutableMap().apply { put(convId, merged) }
            touchConversationLocked(convId)
        }
        persist { saveMessagesAndPrune(convId, incoming, replace = false) }
    }

    /** 仅供确认是完整服务端快照的调用方覆盖本会话缓存。 */
    fun replaceMessagesFromFullSnapshot(convId: String, list: List<Message>) {
        val normalized = sortMessages(dedupeMessages(list))
        synchronized(lock) {
            messages.value = messages.value.toMutableMap().apply { put(convId, normalized) }
            touchConversationLocked(convId)
        }
        persist { saveMessagesAndPrune(convId, normalized, replace = true) }
    }

    fun appendOlder(convId: String, older: List<Message>) {
        val added: List<Message>
        synchronized(lock) {
            val cur = messagesOf(convId)
            val existing = cur.mapTo(HashSet()) { it.id }
            added = older.filter { it.id !in existing }
            if (added.isEmpty()) return
            messages.value = messages.value.toMutableMap().apply { put(convId, sortMessages(cur + added)) }
            touchConversationLocked(convId)
        }
        persist {
            Repo.saveMessages(convId, added, replace = false)
            // 翻历史后顺带裁剪本地缓存，避免 Room 无限膨胀。
            Repo.pruneMessages(convId)
        }
    }

    fun addMessage(convId: String, msg: Message) {
        synchronized(lock) {
            if (messagesOf(convId).any { it.id == msg.id }) return
            messages.value = messages.value.toMutableMap()
                .apply { put(convId, sortMessages(listOf(msg) + messagesOf(convId))) }
            touchConversationLocked(convId)
        }
        persist { saveMessagesAndPrune(convId, listOf(msg), replace = false) }
    }

    /**
     * 收到服务端消息（WS/轮询回显）时的统一入口。
     *
     * 服务端会把 `new_message` 广播给**包括发送者在内**的全部成员。若之前 REST 发送
     * 的响应丢失（服务端已落库但客户端超时），本地会残留一条乐观消息；这里检测到
     * “自己发的、内容匹配的待发消息”时直接合并确认，而不是插入重复气泡。
     */
    fun incorporateIncoming(convId: String, msg: Message) {
        var matchedLocalId: String? = null
        synchronized(lock) {
            if (messagesOf(convId).any { it.id == msg.id }) return
            val me = currentUser.value?.id
            if (msg.senderId == me) {
                val pending = messagesOf(convId).firstOrNull { it.isLocalPending && sameOutgoing(it, msg) }
                if (pending != null) {
                    confirmPendingLocked(convId, pending.id, msg)
                    matchedLocalId = pending.id
                } else {
                    messages.value = messages.value.toMutableMap()
                        .apply { put(convId, sortMessages(listOf(msg) + messagesOf(convId))) }
                    touchConversationLocked(convId)
                }
            } else {
                messages.value = messages.value.toMutableMap()
                    .apply { put(convId, sortMessages(listOf(msg) + messagesOf(convId))) }
                touchConversationLocked(convId)
            }
        }
        val localId = matchedLocalId
        persist {
            if (localId != null) Repo.deleteMessage(convId, localId)
            saveMessagesAndPrune(convId, listOf(msg.confirmed()), replace = false)
        }
    }

    /** 判断服务端消息是否对应某条本地待发消息（用于响应丢失后的去重合并）。 */
    private fun sameOutgoing(local: Message, server: Message): Boolean {
        if (local.messageType != server.messageType) return false
        if (local.fileId != server.fileId) return false
        if (local.replyToMessageId != server.replyToMessageId) return false
        return when (local.messageType) {
            "text", "markdown" -> local.content.orEmpty().trim() == server.content.orEmpty().trim()
            else -> true // 媒体消息以 fileId 匹配即可
        }
    }

    /**
     * 服务端当前版本尚未支持 clientMessageId 唯一约束。重试前先读取最新历史，
     * 若首次请求其实已在服务端提交但响应/连接丢失，则把本地 pending 与该回显合并，
     * 不再盲目发送第二次。严格幂等仍应在服务端对 clientMessageId 建唯一索引。
     */
    private fun findServerEcho(convId: String, local: Message): Message? = runCatching {
        Api.messages(convId, limit = 50).messages.firstOrNull { candidate ->
            candidate.senderId == currentUser.value?.id && sameOutgoing(local, candidate) &&
                createdCloseEnough(local.createdAt, candidate.createdAt)
        }
    }.getOrNull()

    private fun createdCloseEnough(a: String?, b: String?): Boolean {
        val left = runCatching { java.time.Instant.parse(a).toEpochMilli() }.getOrNull() ?: return false
        val right = runCatching { java.time.Instant.parse(b).toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(left - right) <= 2 * 60 * 1000L
    }

    /** 本地发送中的消息也持久化，以便进程重启后仍能展示失败/重试状态。 */
    fun addPending(convId: String, msg: Message) {
        synchronized(lock) {
            if (messagesOf(convId).any { it.id == msg.id }) return
            messages.value = messages.value.toMutableMap()
                .apply { put(convId, sortMessages(listOf(msg) + messagesOf(convId))) }
            touchConversationLocked(convId)
        }
        persist { saveMessagesAndPrune(convId, listOf(msg), replace = false) }
    }

    fun confirmPending(convId: String, localId: String, confirmed: Message) {
        synchronized(lock) { confirmPendingLocked(convId, localId, confirmed) }
        persist {
            Repo.deleteMessage(convId, localId)
            saveMessagesAndPrune(convId, listOf(confirmed.confirmed()), replace = false)
        }
    }

    private fun confirmPendingLocked(convId: String, localId: String, confirmed: Message) {
        val confirmed2 = confirmed.confirmed()
        val updated = messagesOf(convId).filterNot { it.id == confirmed2.id }.toMutableList()
        val localIndex = updated.indexOfFirst { it.id == localId }
        if (localIndex >= 0) updated[localIndex] = confirmed2 else updated.add(0, confirmed2)
        messages.value = messages.value.toMutableMap().apply { put(convId, sortMessages(updated)) }
    }

    /** 服务端确认后的消息：清掉所有本地待发字段。 */
    private fun Message.confirmed(): Message = copy(
        sendState = null,
        localPreviewUri = null,
        localUploadPath = null,
        uploadProgress = 0f,
        uploadTotalBytes = 0L,
    )

    fun failPending(convId: String, localId: String) {
        updatePendingState(convId, localId, SendState.Failed)
    }

    /** 就地改本地待发消息的状态（Sending / Queued / Failed）。 */
    private fun updatePendingState(convId: String, localId: String, state: SendState) {
        val updated: Message
        synchronized(lock) {
            val cur = messagesOf(convId)
            val idx = cur.indexOfFirst { it.id == localId }
            if (idx < 0) return
            val list = cur.toMutableList()
            list[idx] = list[idx].copy(sendState = state)
            updated = list[idx]
            messages.value = messages.value.toMutableMap().apply { put(convId, sortMessages(list)) }
        }
        persist { saveMessagesAndPrune(convId, listOf(updated), replace = false) }
    }

    /**
     * 更新待发媒体的上传进度。默认只改内存（进度回调很密集），
     * 传 persist=true 时才落盘（用于进程重启后仍能显示大致进度）。
     */
    fun updateUploadProgress(convId: String, localId: String, progress: Float, total: Long, persist: Boolean = false) {
        val p = progress.coerceIn(0f, 1f)
        val updated: Message
        synchronized(lock) {
            val cur = messagesOf(convId)
            val idx = cur.indexOfFirst { it.id == localId }
            if (idx < 0) return
            val old = cur[idx]
            // 进度没变就直接返回，避免为重复回调白白复制整张 map/列表。
            if (old.uploadProgress == p && old.uploadTotalBytes == total && old.sendState == SendState.Sending) return
            val list = cur.toMutableList()
            list[idx] = old.copy(
                uploadProgress = p,
                uploadTotalBytes = total,
                sendState = SendState.Sending,
            )
            updated = list[idx]
            messages.value = messages.value.toMutableMap().apply { put(convId, list) }
        }
        if (persist) persist { Repo.saveMessages(convId, listOf(updated), replace = false) }
    }

    /** 待发媒体上传失败：置为 Failed，保留本地文件以便手动重试。 */
    fun setUploadFailed(convId: String, localId: String) {
        updatePendingState(convId, localId, SendState.Failed)
    }

    /** 本会话中未完成（Queued）的媒体上传 id，用于进入会话/重启后自动续传。 */
    fun pendingMediaUploads(convId: String): List<String> =
        messagesOf(convId)
            .filter { it.localUploadPath != null && it.sendState == SendState.Queued }
            .map { it.id }

    /**
     * 发送失败 → 排入自动重试队列。
     * 退避序列 1s → 2s → 4s → 8s → 16s → 30s（上限），共 [MAX_SEND_ATTEMPTS] 次；
     * 期间状态是 Queued（界面显示“等待重发”），用尽后落到 Failed 只能手动重发。
     */
    fun failAndQueueRetry(convId: String, localId: String) {
        updatePendingState(convId, localId, SendState.Queued)
        scheduleRetry(convId, localId, immediate = false)
    }

    /** 用户手动点“重发”：立刻重试，不等待退避。 */
    fun retryPendingNow(convId: String, localId: String) {
        updatePendingState(convId, localId, SendState.Queued)
        scheduleRetry(convId, localId, immediate = true)
    }

    private val retryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 重试单个待发消息。直接复用原消息的 messageType/fileId/replyToMessageId，
     * 因此图片、文件消息重发不会退化成纯文本；
     * 并携带稳定的 clientMessageId（=本地 id），服务端支持幂等后即可彻底去重。
     */
    private fun scheduleRetry(convId: String, localId: String, immediate: Boolean) {
        val key = "$convId|$localId"
        // 旧实现是 check-then-act：「先看有没有活跃任务，再 put」，两个并发调用（退避定时 /
        // WS 恢复 / 手动重发）可能同时通过判断，给同一条消息起两个重试协程，造成重复发送。
        // 这里把「判断 + 登记」放进同一把锁，保证同一 key 至多一个活跃任务；
        // 任务结束用两参 remove 只移除自己，避免误删刚登记的新任务。
        synchronized(retryJobs) {
            if (retryJobs[key]?.isActive == true) return
            retryJobs[key] = persistScope.launch {
                var waitMs = if (immediate) 0L else 1000L
                try {
                    for (attempt in 1..MAX_SEND_ATTEMPTS) {
                        if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                        if (!loggedIn.value) return@launch
                        val msg = messagesOf(convId).firstOrNull { it.id == localId } ?: return@launch
                        // 待发媒体由 UploadManager 负责（需要重新上传文件），这里只处理文本/已上传消息。
                        if (msg.localUploadPath != null) return@launch
                        // 已被别处确认或撤下队列就放弃这条任务。
                        if (msg.sendState != SendState.Queued && msg.sendState != SendState.Failed) return@launch
                        // 先查服务端回显，覆盖“服务端已提交但客户端没收到响应”的弱网场景。
                        findServerEcho(convId, msg)?.let { confirmed ->
                            confirmPending(convId, localId, confirmed)
                            bumpConversation(convId, confirmed, false)
                            return@launch
                        }
                        updatePendingState(convId, localId, SendState.Sending)
                        val sent = runCatching {
                            Api.sendMessage(
                                convId, msg.content.orEmpty(), msg.messageType, msg.fileId,
                                msg.replyToMessageId, clientMessageId = localId,
                            )
                        }.getOrNull()
                        if (sent != null) {
                            confirmPending(convId, localId, sent)
                            bumpConversation(convId, sent, false)
                            return@launch
                        }
                        updatePendingState(
                            convId, localId,
                            if (attempt >= MAX_SEND_ATTEMPTS) SendState.Failed else SendState.Queued,
                        )
                        waitMs = (waitMs.coerceAtLeast(1000L) * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                } finally {
                    coroutineContext[Job]?.let { retryJobs.remove(key, it) }
                }
            }
        }
    }

    /** 网络/WS 恢复时把所有**排队中**的消息立刻推一次，不必等退避。 */
    fun retryQueuedNow() {
        val pending = synchronized(lock) {
            messages.value.flatMap { (convId, list) ->
                // 只处理 Queued：Failed 已用尽自动重试次数，按设计只能由用户手动重发。
                list.filter { it.sendState == SendState.Queued && it.localUploadPath == null }
                    .map { convId to it.id }
            }
        }
        pending.forEach { (convId, localId) -> scheduleRetry(convId, localId, immediate = true) }
    }

    fun removePending(convId: String, localId: String) {
        synchronized(lock) {
            messages.value = messages.value.toMutableMap().apply {
                put(convId, messagesOf(convId).filterNot { it.id == localId })
            }
        }
        persist { Repo.deleteMessage(convId, localId) }
    }

    /**
     * 撤回消息：把本会话内**引用它的消息**的引用快照一并标为已撤回。
     * 快照是后端随消息下发的副本，不会自己更新；不同步的话引用块会一直显示原文。
     */
    fun markDeleted(convId: String, messageId: String) {
        val changed: List<Message>
        synchronized(lock) {
            val cur = messagesOf(convId)
            val idx = cur.indexOfFirst { it.id == messageId }
            if (idx < 0 || cur[idx].isDeleted) return
            val acc = mutableListOf<Message>()
            val updated = cur.map { m ->
                when {
                    m.id == messageId ->
                        m.copy(isDeleted = true, content = "").also { acc.add(it) }
                    m.replyTo?.id == messageId ->
                        m.copy(replyTo = m.replyTo.copy(isDeleted = true, content = "")).also { acc.add(it) }
                    else -> m
                }
            }
            changed = acc
            messages.value = messages.value.toMutableMap().apply { put(convId, sortMessages(updated)) }
            touchConversationLocked(convId)
        }
        persist {
            Repo.markMessageDeleted(convId, messageId)
            // 被同步改了引用快照的消息需要回写，否则重启后又显示原文。
            Repo.saveMessages(convId, changed.filter { it.id != messageId }, replace = false)
        }
    }

    /** 用同 id 的新版本覆盖已有消息（编辑后服务端会整体下发）。 */
    fun upsertMessage(convId: String, msg: Message) {
        val changedRefs: List<Message>
        synchronized(lock) {
            val cur = messagesOf(convId)
            val idx = cur.indexOfFirst { it.id == msg.id }
            if (idx < 0) {
                messages.value = messages.value.toMutableMap()
                    .apply { put(convId, sortMessages(listOf(msg) + cur)) }
                touchConversationLocked(convId)
                changedRefs = emptyList()
            } else {
                // 引用该消息的快照是后端下发的副本，编辑后需同步，否则引用块仍显示旧内容。
                val acc = mutableListOf<Message>()
                val updated = cur.map { m ->
                    when {
                        m.id == msg.id -> msg.confirmed()
                        m.replyTo?.id == msg.id ->
                            m.copy(replyTo = m.replyTo.copy(content = msg.content)).also { acc.add(it) }
                        else -> m
                    }
                }
                changedRefs = acc
                messages.value = messages.value.toMutableMap().apply { put(convId, sortMessages(updated)) }
                touchConversationLocked(convId)
            }
        }
        persist {
            Repo.saveMessages(convId, listOf(msg.confirmed()), replace = false)
            if (changedRefs.isNotEmpty()) Repo.saveMessages(convId, changedRefs, replace = false)
        }
    }

    /**
     * 仅在本机删除消息（不通知服务端、不影响他人），对应「仅删除本地」。
     * 本地缓存与内存同时移除；对方重新发来同 id 的更新时会重新出现。
     */
    fun deleteLocal(convId: String, messageId: String) {
        synchronized(lock) {
            val cur = messagesOf(convId)
            if (cur.none { it.id == messageId }) return
            messages.value = messages.value.toMutableMap().apply {
                put(convId, cur.filterNot { it.id == messageId })
            }
            touchConversationLocked(convId)
        }
        persist { Repo.deleteMessage(convId, messageId) }
    }

    /** 清空本会话消息（仅本机缓存；不通知服务端，对方仍可见）。 */
    fun clearMessages(convId: String) {
        synchronized(lock) {
            messages.value = messages.value.toMutableMap().apply { remove(convId) }
            convRecency.remove(convId)
        }
        persist { Repo.clearMessages(convId) }
    }

    /** 清空本账号全部消息缓存（存储管理用；下次进入会话从服务端重新拉取）。 */
    fun clearAllMessagesCache() {
        synchronized(lock) {
            messages.value = emptyMap()
            convRecency.clear()
        }
        persist { Repo.clearAllMessages() }
    }

    fun bumpConversation(convId: String, last: Message, incrementUnread: Boolean) {
        val updated: Conversation
        synchronized(lock) {
            val list = conversations.value.toMutableList()
            val idx = list.indexOfFirst { it.id == convId }
            if (idx < 0) return
            val c = list[idx]
            updated = c.copy(lastMessage = last, unreadCount = if (incrementUnread) c.unreadCount + 1 else c.unreadCount)
            list[idx] = updated
            list.removeAt(idx)
            list.add(0, updated)
            conversations.value = list
        }
        persist { Repo.updateConv(updated) }
    }

    fun clearUnread(convId: String) {
        val updated: Conversation
        synchronized(lock) {
            val list = conversations.value.toMutableList()
            val idx = list.indexOfFirst { it.id == convId }
            if (idx < 0 || list[idx].unreadCount == 0) return
            updated = list[idx].copy(unreadCount = 0)
            list[idx] = updated
            conversations.value = list
        }
        persist { Repo.setUnread(convId, 0) }
    }

    fun upsertConversation(c: Conversation) {
        synchronized(lock) {
            val list = conversations.value.toMutableList()
            val idx = list.indexOfFirst { it.id == c.id }
            if (idx >= 0) list[idx] = c else list.add(0, c)
            conversations.value = list
        }
        persist { Repo.updateConv(c) }
    }

    fun setConversations(list: List<Conversation>) {
        synchronized(lock) { conversations.value = list }
        persist { Repo.saveConversations(list) }
    }

    /** 更新自定义表情并持久化（本地文件路径列表）。 */
    fun setCustomEmojis(list: List<String>) {
        customEmojis.value = list
        authStore?.customEmojis = list
    }

    fun removeConversation(convId: String) {
        synchronized(lock) { conversations.value = conversations.value.filterNot { it.id == convId } }
        persist { Repo.deleteConversation(convId) }
    }

    /**
     * 会话成员变化事件（conversation:updated）只下发成员 id，不含完整会话，
     * 因此回查服务端详情后再 upsert；已无权限时直接移除本地会话。
     */
    fun refreshConversation(conversationId: String, dropIfInaccessible: Boolean) {
        persistScope.launch {
            val res = runCatching { Api.conversation(conversationId) }
            res.onSuccess { c -> if (c != null) upsertConversation(c) }
            res.onFailure { e ->
                if (dropIfInaccessible && e is Api.ApiException && (e.code == 403 || e.code == 404)) {
                    removeConversation(conversationId)
                }
            }
        }
    }

    /**
     * 重连后补拉整张会话列表：修复离线期间其它端的已读/未读与本地漂移。
     * 以服务端为准覆盖本地，但对正在查看的会话保持已读（用户此刻就在看）。
     */
    fun refreshConversations() {
        persistScope.launch {
            runCatching { Api.conversations() }.onSuccess { list ->
                setConversations(list)
                activeConversationId.value?.let { clearUnread(it) }
            }
        }
    }

    fun setOnline(userId: String, online: Boolean) {
        synchronized(lock) {
            val cur = onlineUserIds.value.toMutableSet()
            if (online) cur.add(userId) else cur.remove(userId)
            onlineUserIds.value = cur
        }
    }

    // 「正在输入」自愈超时：发送端的 typing:false 若丢失，到点自动清除，避免永久残留。
    private val typingTimeouts = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun setTyping(convId: String, userId: String, isTyping: Boolean) {
        synchronized(lock) {
            val map = typing.value.toMutableMap()
            val set = (map[convId] ?: emptySet()).toMutableSet()
            if (isTyping) set.add(userId) else set.remove(userId)
            map[convId] = set
            typing.value = map
        }
        val key = "$convId|$userId"
        typingTimeouts.remove(key)?.cancel()
        if (isTyping) {
            typingTimeouts[key] = persistScope.launch {
                kotlinx.coroutines.delay(6000)
                synchronized(lock) {
                    val map = typing.value.toMutableMap()
                    val set = (map[convId] ?: emptySet()).toMutableSet()
                    set.remove(userId)
                    map[convId] = set
                    typing.value = map
                }
                typingTimeouts.remove(key)
            }
        }
    }

    init {
        // WS 一旦恢复连接，立刻把所有待重发消息推一次，不用等退避到点。
        persistScope.launch {
            wsState.collect { if (it == WsState.Connected) retryQueuedNow() }
        }
    }
}
