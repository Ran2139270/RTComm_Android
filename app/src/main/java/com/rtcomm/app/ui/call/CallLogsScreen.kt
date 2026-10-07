package com.rtcomm.app.ui.call

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.CallLog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.LoadingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CallLogsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var logs by remember { mutableStateOf<List<CallLog>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; err = null
        if (logs.isEmpty()) {
            val cached = withContext(Dispatchers.IO) { com.rtcomm.app.data.PrefsCache.load(context, "call_logs") }
            if (cached != null) {
                runCatching { com.google.gson.Gson().fromJson(cached, Array<CallLog>::class.java)?.toList() }
                    .getOrNull()?.let { logs = it; loading = false }
            }
        }
        val r = withContext(Dispatchers.IO) { runCatching { Api.callLogs() } }
        r.onSuccess {
            logs = it
            withContext(Dispatchers.IO) {
                com.rtcomm.app.data.PrefsCache.save(context, "call_logs", com.google.gson.Gson().toJson(it))
            }
        }
        r.onFailure { err = Api.userMessage(it) }
        loading = false
    }
    LaunchedEffect(Unit) { load() }

    // 通话信令事件（call:join/leave/hangup）到达时刷新记录，保证记录实时性。
    // 一次通话会产生多个事件，这里做 800ms 节流，避免事件风暴把接口打爆。
    LaunchedEffect(Unit) {
        var lastLoad = 0L
        com.rtcomm.app.data.WsBus.events.collect { e ->
            if (!e.type.startsWith("call:")) return@collect
            val now = System.currentTimeMillis()
            if (now - lastLoad < 800L) return@collect
            lastLoad = now
            load()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("通话记录", onBack = onBack)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "说明：通话信令与通话记录已实现；WebRTC 音视频媒体层尚未接入（计划下一版本）。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (err != null) ErrorBanner(err!!, onRetry = { scope.launch { load() } })
            FadeSwap(if (loading && logs.isEmpty()) 0 else if (logs.isEmpty()) 1 else 2) { st ->
                when (st) {
                    // 仅在还没有任何数据（含缓存）时才显示 loading，避免每次刷新都遮住已有列表。
                    0 -> LoadingBox()
                    1 -> EmptyState(Icons.Filled.Call, "暂无通话记录", "在聊天页可发起通话")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(logs, key = { it.id }) { c -> CallRow(c, modifier = Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRow(c: CallLog, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(Icons.Filled.Call, null) },
        headlineContent = { Text((c.participants.size).toString() + " 人通话", fontWeight = FontWeight.Medium) },
        supportingContent = {
            Text("开始 " + Format.dateTime(c.startTime) + " · 时长 " + Format.duration(c.durationSec), style = MaterialTheme.typography.bodySmall)
        },
    )
}
