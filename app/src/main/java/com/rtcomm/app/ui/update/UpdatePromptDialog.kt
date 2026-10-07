package com.rtcomm.app.ui.update

import com.rtcomm.app.ui.theme.Space

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.AppUpdateInfo
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.UpdateManager
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.FadeSwap

/**
 * 启动时的新版本提示框：展示更新内容，可「稍后」「跳过此版本」或「立即更新」。
 * 跳过由调用方持久化（最多跳过当前及之后 2 个版本）。
 */
@Composable
fun UpdatePromptDialog(
    info: AppUpdateInfo,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    var installErr by remember { mutableStateOf<String?>(null) }

    // 只认当前提示版本的下载/完成状态，避免展示其它版本的旧结果。
    val downloading = (state as? UpdateManager.State.Downloading)?.takeIf { it.info.versionCode == info.versionCode }
    val downloaded = (state as? UpdateManager.State.Downloaded)?.takeIf { it.info.versionCode == info.versionCode }
    val failed = state as? UpdateManager.State.Failed

    AppAlertDialog(
        onDismissRequest = onLater,
        title = { Text("发现新版本 v${info.versionName}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
            ) {
                info.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                }
                Text("安装包大小：${FileTransfer.humanSize(info.fileSize)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 下载/完成/失败状态之间淡切，替代瞬间跳变。
                // 只按状态种类做 key，进度刷新不会触发反复淡入；数据用可空安全访问，
                // 过渡期间旧状态数据已清空时渲染为空，不会崩溃。
                FadeSwap(target = when {
                    downloading != null -> 1
                    downloaded != null -> 2
                    failed != null -> 3
                    else -> 0
                }) { st ->
                    when (st) {
                        1 -> downloading?.let { d ->
                            Column {
                                Spacer(Modifier.height(Space.md))
                                Text("正在下载…", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(Space.xs))
                                Text("${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        2 -> {
                            Spacer(Modifier.height(Space.md))
                            Text("安装包已下载，可直接安装。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        3 -> failed?.let { f ->
                            Column {
                                Spacer(Modifier.height(Space.md))
                                Text(f.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        else -> Unit
                    }
                }
                installErr?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                downloaded != null -> TextButton(onClick = {
                    installErr = null
                    UpdateInstaller.install(context, downloaded.apk) { installErr = it }
                }) { Text("安装更新") }
                downloading != null -> TextButton(onClick = onLater) { Text("后台下载") }
                else -> TextButton(onClick = {
                    installErr = null
                    UpdateManager.download(context, info)
                }) { Text("立即更新") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onLater) { Text("稍后") }
                TextButton(onClick = onSkip) { Text("跳过此版本") }
            }
        },
    )
}
