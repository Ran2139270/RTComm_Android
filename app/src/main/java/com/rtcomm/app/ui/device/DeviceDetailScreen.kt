package com.rtcomm.app.ui.device

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.CommandResult
import com.rtcomm.app.data.Device
import com.rtcomm.app.data.RemoteSession
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.DialogAppear
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.Reveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(deviceId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<Device?>(null) }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CommandResult?>(null) }
    var sessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmCmd by remember { mutableStateOf<String?>(null) }
    var showTerminal by remember { mutableStateOf(false) }

    suspend fun reloadSessions() {
        val r = withContext(Dispatchers.IO) { runCatching { Api.deviceSessions(deviceId) } }
        r.onSuccess { sessions = it }
    }
    LaunchedEffect(deviceId) {
        val r = withContext(Dispatchers.IO) { runCatching { Api.device(deviceId) } }
        r.onSuccess { device = it }; r.onFailure { err = Api.userMessage(it) }
        reloadSessions()
    }

    fun execute(cmd: String) {
        if (cmd.isBlank() || running) return
        running = true; err = null; result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Api.runCommand(deviceId, cmd, 30) }
                    .getOrElse { CommandResult(command = cmd, error = Api.userMessage(it)) }
            }
            result = r; running = false
            reloadSessions()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = device?.deviceName ?: "设备",
                onBack = onBack,
                titleStyle = MaterialTheme.typography.titleMedium,
                actions = {
                    IconButton(onClick = { showTerminal = true }, enabled = device?.isOnline == true) {
                        Icon(Icons.Filled.Terminal, "实时终端")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 设备详情异步拉取，加载完成时展开淡入，避免内容瞬间顶出来。
            Reveal(device != null) {
                device?.let { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = {}, enabled = false, label = { Text(if (d.isOnline) stringResource(R.string.status_online) else stringResource(R.string.status_offline)) })
                        Spacer(Modifier.width(Space.sm))
                        Text("Agent " + (d.agentVersion ?: "未知"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!d.isOnline) {
                        Spacer(Modifier.height(6.dp))
                        Text("设备当前离线，命令与终端不可用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            err?.let { ErrorBanner(it) }

            Spacer(Modifier.height(Space.lg))
            Text("执行一次性命令", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.sm))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("命令") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                trailingIcon = {
                    IconButton(
                        enabled = command.isNotBlank() && !running && device?.isOnline == true,
                        onClick = { val c = command.trim(); if (isDangerous(c)) confirmCmd = c else execute(c) },
                    ) {
                        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.PlayArrow, "执行")
                    }
                },
            )
            Reveal(result != null) {
                result?.let { r -> Spacer(Modifier.height(Space.md)); CommandResultCard(r) }
            }

            Spacer(Modifier.height(20.dp))
            Text("会话与命令历史", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.sm))
            if (sessions.isEmpty()) {
                Text("暂无会话记录", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Reveal(sessions.isNotEmpty()) {
                Column {
                    sessions.forEach { s -> SessionCard(deviceId, s, onChanged = { scope.launch { reloadSessions() } }) }
                }
            }
        }
    }

    confirmCmd?.let { cmd ->
        AppAlertDialog(
            onDismissRequest = { confirmCmd = null },
            title = { Text("危险操作确认") },
            text = { Text("命令「" + cmd + "」可能造成不可逆影响，确定执行吗？") },
            confirmButton = { TextButton(onClick = { confirmCmd = null; execute(cmd) }) { Text("确定执行", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCmd = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showTerminal) {
        TerminalDialog(deviceId = deviceId, onDismiss = { showTerminal = false; scope.launch { reloadSessions() } })
    }
}

@Composable
private fun CommandResultCard(r: CommandResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    r.error != null -> AssistChip(onClick = {}, enabled = false, label = { Text("错误") })
                    r.timedOut -> AssistChip(onClick = {}, enabled = false, label = { Text("超时") })
                    else -> AssistChip(onClick = {}, enabled = false, label = { Text("退出码 " + (r.exitCode?.toString() ?: "-")) })
                }
            }
            Spacer(Modifier.height(Space.sm))
            // 结果可能极大（如递归列目录）：截断后再渲染，避免一次性排版超大字符串卡死 UI。
            val text = (r.error ?: r.output?.ifBlank { "(无输出)" } ?: "(无输出)").take(50_000)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    text,
                    modifier = Modifier.padding(10.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SessionCard(deviceId: String, s: RemoteSession, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<List<com.rtcomm.app.data.RemoteCommand>>(emptyList()) }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (s.status == "active") "活动会话" else "已关闭") + " · " + Format.dateTime(s.createdAt), style = MaterialTheme.typography.bodyMedium)
                    Text(s.id.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = {
                    expanded = !expanded
                    if (expanded && commands.isEmpty()) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Api.sessionCommands(deviceId, s.id) } }
                            r.onSuccess { commands = it }
                        }
                    }
                }) { Text(if (expanded) "收起" else "命令") }
                if (s.status == "active") {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { Api.closeSession(deviceId, s.id) } }
                            onChanged()
                        }
                    }) { Text(stringResource(R.string.action_close), color = MaterialTheme.colorScheme.error) }
                }
            }
            Reveal(expanded) {
                Column {
                Spacer(Modifier.height(6.dp))
                if (commands.isEmpty()) {
                    Text("无命令记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    commands.forEach { c ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text("$ " + c.commandText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            if (!c.output.isNullOrBlank()) {
                                Text(c.output.take(2000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

private fun isDangerous(cmd: String): Boolean {
    // 先归一化空白（含制表符/多空格），避免 "rm    -rf" 这类简单变形绕过黑名单。
    val c = cmd.lowercase().replace(Regex("\\s+"), " ").trim()
    val patterns = listOf(
        "rm -rf", "rm -r", "rm -f", "mkfs", "dd ", "dd if=", "dd of=", "wipefs", "fdisk",
        "shutdown", "reboot", "poweroff", "init 0", "init 6", "> /dev/", "chmod -r", "chown -r",
        ":(){", "format ", "del /f", "del /q", "rd /s",
    )
    return patterns.any { c.contains(it) }
}

@Composable
private fun TerminalDialog(deviceId: String, onDismiss: () -> Unit) {
    val output = remember { mutableStateOf("") }
    val connected = remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    fun append(s: String) {
        output.value = (output.value + s).takeLast(20000)
    }

    val client = remember {
        com.rtcomm.app.ws.TerminalClient(
            deviceId = deviceId,
            onEvent = { type, data ->
                when (type) {
                    "remote:session" -> { connected.value = true; append("[会话已建立]\n") }
                    "remote:output" -> append((data["output"] as? String) ?: "")
                    "remote:exit" -> append("\n[命令结束 exit=" + ((data["exitCode"] as? Double)?.toInt()?.toString() ?: "-") + "]\n")
                    "remote:error" -> append("\n[错误] " + ((data["error"] as? String) ?: "") + "\n")
                    "remote:session_closed" -> { append("\n[会话已关闭]\n"); connected.value = false }
                }
            },
            onClosed = { connected.value = false },
        )
    }

    DisposableEffect(Unit) {
        client.connect()
        onDispose { client.close() }
    }
    LaunchedEffect(output.value) { scrollState.animateScrollTo(scrollState.maxValue) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DialogAppear {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.86f), shape = RoundedCornerShape(Corner.medium)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Terminal, null)
                    Spacer(Modifier.width(Space.sm))
                    Text("实时终端", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, enabled = false, label = { Text(if (connected.value) "已连接" else "未连接") })
                }
                Spacer(Modifier.height(Space.sm))
                // P2：终端尺寸变化 → 主 WS 发 remote:resize（需 sessionId + deviceId；等宽字符约 8dp 宽 / 18dp 高）
                var termSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
                Surface(
                    color = com.rtcomm.app.ui.theme.AppColors.TerminalBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f).fillMaxWidth()
                        .onSizeChanged { sz ->
                            val newSize = androidx.compose.ui.geometry.Size(sz.width.toFloat(), sz.height.toFloat())
                            if (newSize != termSize) {
                                termSize = newSize
                                val sid = client.sessionId
                                if (sid != null && connected.value) {
                                    // onSizeChanged 返回的是像素；8dp/18dp 必须先按 density 换算成像素，
                                    // 否则高密度设备上列数会被放大数倍，终端换行/光标与服务端不一致。
                                    val charWidthPx = with(density) { 8.dp.toPx() }
                                    val lineHeightPx = with(density) { 18.dp.toPx() }
                                    val cols = (sz.width / charWidthPx).toInt().coerceIn(1, 1000)
                                    val rows = (sz.height / lineHeightPx).toInt().coerceIn(1, 1000)
                                    com.rtcomm.app.ws.WsClient.send(
                                        "remote:resize",
                                        mapOf("sessionId" to sid, "deviceId" to deviceId, "cols" to cols, "rows" to rows),
                                    )
                                }
                            }
                        },
                ) {
                    Text(
                        com.rtcomm.app.ui.common.AnsiText.parse(output.value.ifBlank { "正在连接…" }),
                        modifier = Modifier.padding(10.dp).fillMaxSize().verticalScroll(scrollState),
                        fontFamily = FontFamily.Monospace,
                        color = com.rtcomm.app.ui.theme.AppColors.TerminalForeground,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入命令回车执行") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(
                        onClick = {
                            val c = input.trim()
                            if (c.isNotEmpty() && connected.value) {
                                // 交互终端同样拦截危险命令，避免绕过快捷命令输入框的确认。
                                if (isDangerous(c)) {
                                    append("已阻止危险命令：" + c + "\n")
                                } else {
                                    append("$ " + c + "\n")
                                    if (!client.sendCommand(c)) append("终端命令未送达，请重新连接后重试\n")
                                }
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && connected.value,
                    ) { Icon(Icons.Filled.PlayArrow, "执行") }
                }
                Spacer(Modifier.height(Space.xs))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭终端") }
            }
        }
        }
    }
}
