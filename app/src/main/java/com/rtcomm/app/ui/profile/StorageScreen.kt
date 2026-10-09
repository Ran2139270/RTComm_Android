package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Space

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.rtcomm.app.data.StorageManager
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.ConfirmDialog
import com.rtcomm.app.ui.common.FadeSwap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 存储管理：查看并清理应用的本地占用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageLoader = context.imageLoader
    var entries by remember { mutableStateOf<List<StorageManager.Entry>>(emptyList()) }
    var total by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    // 首次统计磁盘占用需要遍历缓存目录（IO 耗时），期间显示加载动画；
    // 之后的清理刷新不再回到 loading，避免每次清除都闪一下。
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = StorageManager.entries(context, imageLoader)
        entries = list
        total = list.sumOf { it.bytes }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    fun clearOne(id: String) {
        if (busy) return
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) { StorageManager.clear(context, imageLoader, id) }
            refresh()
            busy = false
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("存储管理", onBack = onBack)
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("本地已用约 ${StorageManager.format(total)}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "缓存遵循「本地优先」：先读本地秒开，再按需联网校验。清除缓存不会删除服务端数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            FadeSwap(target = loading) { isLoading ->
                if (isLoading) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                } else {
                    Column {
                        entries.forEach { e ->
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                headlineContent = { Text(e.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                supportingContent = {
                                    Text(
                                        "${e.hint} · ${StorageManager.format(e.bytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                trailingContent = {
                                    if (e.bytes > 0) {
                                        TextButton(enabled = !busy, onClick = { clearOne(e.id) }) {
                                            Text("清除", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(Space.lg))
                        Button(
                            onClick = { if (!busy) confirmClearAll = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) { Text(if (busy) "清理中…" else "清除全部缓存") }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (confirmClearAll) {
        ConfirmDialog(
            title = "清除全部缓存",
            message = "将清空本机的图片、聊天记录与文件缓存。服务端数据不受影响，但下次打开会话需要重新联网拉取。",
            confirmText = "清除全部",
            onConfirm = {
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) { StorageManager.clearAll(context, imageLoader) }
                    refresh()
                    busy = false
                }
            },
            onDismiss = { confirmClearAll = false },
        )
    }
}
