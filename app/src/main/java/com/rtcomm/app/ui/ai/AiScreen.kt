package com.rtcomm.app.ui.ai

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.BotSummary
import com.rtcomm.app.data.ProviderPreset
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.ConnectionChip
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.LoadingBox
import com.rtcomm.app.ui.common.MetadataTag
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.common.rememberReducedMotion
import com.rtcomm.app.ui.profile.AvatarTarget
import com.rtcomm.app.ui.profile.rememberAvatarController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AiScreen(
    onOpenBot: (String, String) -> Unit,
    onBotBounds: (String, Rect) -> Unit = { _, _ -> },
    onBotTitleBounds: (String, Rect) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    val avatar = rememberAvatarController()
    val wsState by AppState.wsState.collectAsState()

    var bots by remember { mutableStateOf<List<BotSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    // 本地过滤：机器人多时按昵称/用户名快速查找。
    val visibleBots = remember(bots, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) bots else bots.filter {
            it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
    }

    suspend fun load(refresh: Boolean) {
        if (refresh) refreshing = true else loading = true
        err = null
        // 先显缓存（秒开），再拉网络校验刷新。
        if (!refresh && bots.isEmpty()) {
            val cached = withContext(Dispatchers.IO) { com.rtcomm.app.data.PrefsCache.load(context, "bots") }
            if (cached != null) {
                runCatching { com.google.gson.Gson().fromJson(cached, Array<BotSummary>::class.java)?.toList() }
                    .getOrNull()?.let { bots = it; loading = false }
            }
        }
        val r = withContext(Dispatchers.IO) { runCatching { Api.bots() } }
        r.onSuccess {
            bots = it
            withContext(Dispatchers.IO) {
                com.rtcomm.app.data.PrefsCache.save(context, "bots", com.google.gson.Gson().toJson(it))
            }
        }
        r.onFailure { err = Api.userMessage(it) }
        loading = false; refreshing = false
    }
    LaunchedEffect(Unit) { load(false) }

    // 共享 FAB（在 MainScaffold，切 Tab 时不抖动）发出的「新建机器人」信号。
    LaunchedEffect(Unit) {
        AppState.fabCreateBot.collect { if (it) { showCreate = true; AppState.fabCreateBot.value = false } }
    }

    // 预取机器人头像到本地缓存，列表滚动/重进秒显。
    LaunchedEffect(bots) {
        val loader = context.imageLoader
        bots.forEach { b ->
            b.avatarUrl?.takeIf { it.isNotBlank() }?.let { path ->
                val full = if (path.startsWith("http")) path else Api.baseUrl.trimEnd('/') + path
                loader.enqueue(coil.request.ImageRequest.Builder(context).data(full).build())
            }
        }
    }

    // 顶部栏固定（pinned）：和首页一致，滚动时毛玻璃标题栏不会被翻过。
    val titleScroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(titleScroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = "AI 助手",
                scrollBehavior = titleScroll,
                actions = { ConnectionChip(wsState); Spacer(Modifier.width(Space.sm)) },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { load(true) } },
            state = rememberPullToRefreshState(),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (err != null) ErrorBanner(err!!, reducedMotion = reduced, onRetry = { scope.launch { load(false) } })
                if (bots.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索机器人") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                FadeSwap(
                    if (loading && bots.isEmpty()) 0
                    else if (bots.isEmpty()) 1
                    else if (visibleBots.isEmpty()) 2
                    else 3,
                ) { st ->
                when (st) {
                    0 -> LoadingBox()
                    1 -> EmptyState(
                        Icons.Filled.SmartToy,
                        "暂无 AI 机器人",
                        if (AppState.currentUser.value?.isAdmin == true) "创建第一个机器人开始对话" else "管理员可在此创建机器人",
                        action = if (AppState.currentUser.value?.isAdmin == true) {
                            { Button(onClick = { showCreate = true }) { Text("新建机器人") } }
                        } else null,
                    )
                    2 -> EmptyState(Icons.Filled.Search, "没有匹配的机器人", "试试其他关键词")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleBots, key = { it.id }, contentType = { "bot" }) { b ->
                            BotRow(
                                b = b,
                                modifier = Modifier.animateItem(),
                                onBounds = { onBotBounds(b.id, it) },
                                onTitleBounds = { onBotTitleBounds(b.id, it) },
                                onClick = { onOpenBot(b.id, b.displayName) },
                                onOpenAvatar = {
                                    avatar.open(
                                        AvatarTarget(
                                            name = b.displayName,
                                            avatarUrl = b.avatarUrl,
                                            isMe = false,
                                            username = b.username,
                                            isBot = true,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }

    if (showCreate) {
        CreateBotDialog(
            onDismiss = { showCreate = false },
            onCreated = { message ->
                showCreate = false
                scope.launch {
                    load(true)
                    message?.let { snackbar.showSnackbar(it) }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BotRow(
    b: BotSummary,
    modifier: Modifier = Modifier,
    onBounds: (Rect) -> Unit,
    onTitleBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    onOpenAvatar: () -> Unit = {},
) {
    Card(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .motionPress(pressedScale = 0.99f)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(b.displayName, isBot = true, online = b.isOnline, avatarUrl = b.avatarUrl, onClick = onOpenAvatar)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    b.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.onGloballyPositioned { onTitleBounds(it.boundsInRoot()) },
                )
                Text("@" + b.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.modelProvider?.let { MetadataTag(it) }
                    b.modelName?.let { MetadataTag(it) }
                    b.personality?.let { MetadataTag(it) }
                    b.visibility?.let { v ->
                        MetadataTag(when (v) { "public" -> "公开"; "shared" -> "共享"; else -> "私有" })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateBotDialog(onDismiss: () -> Unit, onCreated: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("friendly") }
    var provider by remember { mutableStateOf("echo") }
    var modelName by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("private") }
    var providers by remember { mutableStateOf<List<ProviderPreset>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { Api.providers() } }
        r.onSuccess { providers = it }
    }

    val providerOptions = providers.ifEmpty {
        listOf(
            ProviderPreset(id = "echo", label = "Echo（测试）"),
            ProviderPreset(id = "openai", label = "OpenAI"),
            ProviderPreset(id = "anthropic", label = "Anthropic"),
            ProviderPreset(id = "ollama", label = "Ollama（本地）"),
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 AI 机器人") },
        text = {
            // 字段较多，小屏/横屏下必须可滚动，否则按钮与可见性选项会被挤出屏幕。
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("密码（≥6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("性格", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("friendly", "professional", "humorous", "custom").forEach {
                        FilterChip(selected = personality == it, onClick = { personality = it }, label = { Text(it) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("模型提供者", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    providerOptions.forEach { p ->
                        FilterChip(
                            selected = provider == p.id,
                            onClick = {
                                provider = p.id
                                if (modelName.isBlank()) p.model?.let { modelName = it }
                            },
                            label = { Text(p.label) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(modelName, { modelName = it }, label = { Text("模型名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("可见性（创建后可在配置面板改为指定用户）", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("private" to "仅自己", "public" to "所有人").forEach { (key, label) ->
                        FilterChip(selected = visibility == key, onClick = { visibility = key }, label = { Text(label) })
                    }
                }
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (username.isBlank() || password.isBlank()) { err = "用户名和密码必填"; return@TextButton }
                busy = true; err = null
                scope.launch {
                    val created = withContext(Dispatchers.IO) {
                        runCatching {
                            Api.createBot(
                                com.rtcomm.app.data.CreateBotReq(
                                    username = username.trim(), password = password,
                                    nickname = nickname.ifBlank { null }, personality = personality,
                                    modelProvider = provider, modelName = modelName.ifBlank { null },
                                ),
                            )
                        }
                    }
                    busy = false
                    created.onSuccess { bot ->
                        if (bot == null) {
                            err = "创建失败"
                            return@onSuccess
                        }
                        val name = bot.nickname?.takeIf { it.isNotBlank() } ?: bot.username
                        // 机器人已创建成功；后续可见性设置失败只算“部分成功”，不能报成整体失败。
                        if (visibility != "private") {
                            val vis = withContext(Dispatchers.IO) {
                                runCatching { Api.updateBotVisibility(bot.id, visibility) }
                            }
                            onCreated(
                                vis.fold(
                                    onSuccess = { "机器人「$name」已创建" },
                                    onFailure = { "机器人「$name」已创建，但可见性设置失败：" + Api.userMessage(it) },
                                ),
                            )
                        } else {
                            onCreated("机器人「$name」已创建")
                        }
                    }
                    created.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
