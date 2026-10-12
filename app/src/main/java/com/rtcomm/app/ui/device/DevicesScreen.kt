package com.rtcomm.app.ui.device

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.Device
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.ConnectionChip
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.LoadingBox
import com.rtcomm.app.ui.common.MetadataTag
import com.rtcomm.app.ui.common.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduced = com.rtcomm.app.ui.common.rememberReducedMotion()
    val wsState by AppState.wsState.collectAsState()

    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var showRegister by remember { mutableStateOf(false) }
    var tokenToShow by remember { mutableStateOf<Device?>(null) }

    suspend fun load(refresh: Boolean) {
        if (refresh) refreshing = true else loading = true
        err = null
        // 先显缓存（秒开），再拉网络校验刷新。
        if (!refresh && devices.isEmpty()) {
            val cached = withContext(Dispatchers.IO) { com.rtcomm.app.data.PrefsCache.load(context, "devices") }
            if (cached != null) {
                runCatching { com.google.gson.Gson().fromJson(cached, Array<Device>::class.java)?.toList() }
                    .getOrNull()?.let { devices = it; loading = false }
            }
        }
        val r = withContext(Dispatchers.IO) { runCatching { Api.devices() } }
        r.onSuccess {
            devices = it
            withContext(Dispatchers.IO) {
                com.rtcomm.app.data.PrefsCache.save(context, "devices", com.google.gson.Gson().toJson(it))
            }
        }
        r.onFailure { err = Api.userMessage(it) }
        loading = false; refreshing = false
    }
    LaunchedEffect(Unit) { load(false) }

    // 共享 FAB（在 MainScaffold，切 Tab 时不抖动）发出的「注册设备」信号。
    LaunchedEffect(Unit) {
        AppState.fabRegisterDevice.collect { if (it) { showRegister = true; AppState.fabRegisterDevice.value = false } }
    }

    // 设备上下线事件（device:status）：自动刷新列表与在线状态，无需手动下拉。
    // 一次批量上线/下线会产生一连串事件，做 800ms 节流，避免全量刷新风暴。
    LaunchedEffect(Unit) {
        var lastLoad = 0L
        com.rtcomm.app.data.WsBus.events.collect { e ->
            if (!e.type.startsWith("device:")) return@collect
            val now = System.currentTimeMillis()
            if (now - lastLoad < 800L) return@collect
            lastLoad = now
            load(false)
        }
    }

    // 顶部栏固定（pinned）：和首页一致，滚动时毛玻璃标题栏不会被翻过。
    val titleScroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.nestedScroll(titleScroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                title = "远程设备",
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
                FadeSwap(if (loading && devices.isEmpty()) 0 else if (devices.isEmpty()) 1 else 2) { st ->
                    when (st) {
                        0 -> LoadingBox()
                        1 -> EmptyState(
                            Icons.Filled.Memory,
                            "还没有设备",
                            "注册一台设备后，用 Agent 令牌连接即可远程控制",
                            action = { Button(onClick = { showRegister = true }) { Text("注册设备") } },
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(devices, key = { it.id }) { d ->
                                DeviceRow(d, modifier = Modifier.animateItem()) { onOpenDevice(d.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRegister) {
        RegisterDeviceDialog(
            onDismiss = { showRegister = false },
            onRegistered = { dev -> showRegister = false; tokenToShow = dev; scope.launch { load(true) } },
        )
    }
    tokenToShow?.let { dev -> DeviceTokenDialog(dev) { tokenToShow = null } }
}

@Composable
private fun DeviceRow(d: Device, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            // 先 motionPress 再 clip/background：保证按压缩放的是整个卡片表面，
            // 而不是只有内部内容在动。
            .motionPress(pressedScale = 0.99f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(Icons.Filled.Memory, contentDescription = null) },
        headlineContent = { Text(d.deviceName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                (d.agentVersion?.let { "Agent " + it + " · " } ?: "") + "最近在线 " + Format.relative(d.lastSeen),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (d.isOnline) {
                MetadataTag(
                    stringResource(R.string.status_online),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                MetadataTag(stringResource(R.string.status_offline))
            }
        },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun RegisterDeviceDialog(onDismiss: () -> Unit, onRegistered: (Device) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("注册设备") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("设备名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(version, { version = it }, label = { Text("Agent 版本（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (name.isBlank()) { err = "请输入设备名称"; return@TextButton }
                busy = true; err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.registerDevice(name.trim(), version.ifBlank { null }) } }
                    busy = false
                    r.onSuccess { onRegistered(it) }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "注册中…" else "注册") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeviceTokenDialog(dev: Device, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备令牌（仅显示一次）") },
        text = {
            Column {
                Text("请立即复制并妥善保存。该令牌用于 Agent 连接，关闭后将无法再次查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text(
                        dev.token ?: "(无)",
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dev.token?.let { clipboard.setText(AnnotatedString(it)) }
            }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(Space.xs)); Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
