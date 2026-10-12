package com.rtcomm.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.ForwardItem
import com.rtcomm.app.data.MessageFile
import com.rtcomm.app.ui.common.AppTopBarContent
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 合并转发单条内容的富渲染：文本 / Markdown、图片（点开全屏）、自定义表情、语音、视频（点开播放）、文件（点按下载）。
 * 聊天记录详情页与管理后台共用同一实现，保证观感一致。
 */
@Composable
fun ForwardItemContent(item: ForwardItem, modifier: Modifier = Modifier) {
    val file = item.file
    when {
        item.type == "sticker" && file != null -> StickerImage(file)
        item.type == "image" && file != null -> InlineImage(file, modifier)
        item.type == "audio" && file != null -> AudioPlayer(file, modifier)
        item.type == "video" && file != null -> {
            var show by remember(file.id) { mutableStateOf(false) }
            VideoThumb(file, onClick = { show = true }, modifier = modifier)
            if (show) VideoPlayerDialog(file = file, onDismiss = { show = false })
        }
        item.type == "file" && file != null -> ForwardFileRow(file)
        item.type == "forward" ->
            Text("[聊天记录]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> MarkdownText(src = item.text, compact = true, modifier = modifier)
    }
}

/** 文件行：点按下载到应用下载目录，并给出反馈。 */
@Composable
private fun ForwardFileRow(file: MessageFile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(file.id) { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) { FileTransfer.download(context, file.id, file.fileName) }
                    }.isSuccess
                    downloading = false
                    Toast.makeText(context, if (ok) "已下载：${file.fileName}" else "下载失败", Toast.LENGTH_SHORT).show()
                }
            }
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 220.dp)) {
            Text(file.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, fontWeight = FontWeight.Medium)
            Text(
                if (downloading) "下载中…" else FileTransfer.humanSize(file.fileSize),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 聊天记录详情：整屏、按时间顺序、富渲染；作为共享元素展开的**详情层**渲染
 * （由 [ChatFlow] 的 ExpandFlow 驱动，进度 0→1 表示展开）。
 *
 * 背景必须透明：展开/回收期间由 ExpandFlow 的共享卡片提供不透明背景，这里若画不透明背景
 * 会把展开动画整个盖住（与聊天页同理）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ForwardDetailScreen(
    content: String,
    progress: () -> Float = { 1f },
    onHeaderTitleBounds: (Rect) -> Unit = {},
    onBack: () -> Unit,
) {
    val parsed = com.rtcomm.app.data.parseForward(content)
    val title = parsed?.title?.takeIf { it.isNotBlank() } ?: "聊天记录"
    val items = parsed?.items ?: emptyList()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 头部随展开进度淡入（与聊天页一致的观感）。
            Box(
                Modifier.graphicsLayer { alpha = ((progress() - 0.45f) / 0.40f).coerceIn(0f, 1f) },
            ) {
                AppTopBarContent(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            modifier = Modifier.onGloballyPositioned { onHeaderTitleBounds(it.boundsInRoot()) },
                        )
                    },
                    onBack = onBack,
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .graphicsLayer { alpha = ((progress() - 0.40f) / 0.40f).coerceIn(0f, 1f) },
            ) {
                if (items.isEmpty()) {
                    Text(
                        "聊天记录为空",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        items(items) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                                    if (item.isBot) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                "AI",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            )
                                        }
                                    }
                                    item.time?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            Format.time(it),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                item.reply?.let { r ->
                                    Spacer(Modifier.height(4.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier
                                                    .width(2.dp)
                                                    .height(18.dp)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "${r.sender}：${r.text}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Box(Modifier.padding(10.dp)) { ForwardItemContent(item) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
