package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.UploadBookmarks
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.ConfirmDialog
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.notify.Notify
import com.rtcomm.app.notify.RealtimeService
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 关于页：版本 / 检查更新 / 诊断 / 服务器地址 / 许可。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(authStore: AuthStore, onBack: () -> Unit, onOpenChangelog: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showUpdate by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    /** 已确认要切换到的服务器地址（等待二次确认）。 */
    var pendingServerUrl by remember { mutableStateOf<String?>(null) }
    var showLicense by remember { mutableStateOf(false) }
    var showCrash by remember { mutableStateOf(false) }
    var crashCount by remember { mutableStateOf(com.rtcomm.app.data.CrashLog.count(context)) }
    var info by remember { mutableStateOf<String?>(null) }

    /** 切换服务器：先以旧地址/旧令牌完成注销，再写入新地址并回到登录页。 */
    fun switchServer(newUrl: String) {
        val snapshot = authStore.snapshot()
        com.rtcomm.app.data.PrefsCache.clearAccount(context, authStore.currentAccountKey())
        authStore.forgetAccount(authStore.currentAccountKey())
        com.rtcomm.app.push.PushRegistrar.unregister()
        authStore.clearSession(); AppState.reset(); WsClient.stop()
        UploadBookmarks.clearAll()
        FileTransfer.clearLocalDownloads(context)
        RealtimeService.stop(context)
        Notify.cancelAll(context)
        authStore.serverUrl = newUrl
        scope.launch { withContext(Dispatchers.IO) { runCatching { Api.logout(snapshot.baseUrl, snapshot.token) } } }
        info = "服务器地址已更新，请重新登录"
    }

    Scaffold(
        topBar = {
            AppTopBar("关于", onBack = onBack)
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_waft), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(Space.md))
                    Text("风驿", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Waft · 让消息随风而至",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "版本 ${com.rtcomm.app.data.AppVersion.name(context)}（${com.rtcomm.app.data.AppVersion.code(context)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Reveal(info != null) {
                info?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            SectionTitle("更新")
            GroupCard {
                NavRow(Icons.Filled.SystemUpdate, "检查更新", "当前版本 ${com.rtcomm.app.data.AppVersion.name(context)}") { showUpdate = true }
                NavRow(Icons.Filled.Description, "历史更新内容", "查看全部版本的更新记录") { onOpenChangelog() }
            }
            SectionTitle("诊断与支持")
            GroupCard {
                NavRow(Icons.Filled.BugReport, "复制诊断信息", "不包含聊天内容、账号或令牌；含最近崩溃记录") {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Waft diagnostics", com.rtcomm.app.data.AppDiagnostics.report(context)))
                    info = "诊断信息已复制，可粘贴到问题反馈中"
                }
                NavRow(Icons.Filled.CloudQueue, "服务器地址", authStore.serverUrl) { showServer = true }
                NavRow(
                    Icons.Filled.BugReport,
                    "崩溃日志",
                    if (crashCount > 0) "$crashCount 条记录 · 查看 / 复制" else "暂无崩溃记录",
                ) { showCrash = true }
                NavRow(Icons.Filled.Description, "开源许可", "第三方组件与许可") { showLicense = true }
            }
            Spacer(Modifier.height(28.dp))
        }
        }
    }

    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false })
    if (showServer) {
        ServerDialog(
            authStore = authStore,
            onDismiss = { showServer = false },
            onSave = { v -> showServer = false; pendingServerUrl = v },
        )
    }
    pendingServerUrl?.let { url ->
        ConfirmDialog(
            title = "切换服务器",
            message = "将切换到「$url」。本机会清空当前账号的聊天缓存与登录态，并需要重新登录，未完成的发送会丢失。",
            confirmText = "切换并退出登录",
            onConfirm = { switchServer(url) },
            onDismiss = { pendingServerUrl = null },
        )
    }
    if (showLicense) {
        AppAlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "本应用基于 Kotlin / Jetpack Compose 构建，使用了 AndroidX、OkHttp、Gson、Coil、Media3、Room 等开源组件，均遵循各自的开源许可（Apache-2.0 等）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (showCrash) {
        val log = com.rtcomm.app.data.CrashLog.latest(context)
        AppAlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text("崩溃日志") },
            text = {
                if (log == null) {
                    Text("暂无崩溃记录。\n\n（仅记录异常堆栈、版本与机型，不含消息内容、账号或令牌。）")
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = log != null,
                    onClick = {
                        log?.let {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Waft crash log", it))
                            info = "崩溃日志已复制，可粘贴到问题反馈中"
                        }
                        showCrash = false
                    },
                ) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.rtcomm.app.data.CrashLog.clear(context)
                    crashCount = 0
                    showCrash = false
                }) { Text("清除") }
            },
        )
    }
}
