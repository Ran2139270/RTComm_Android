package com.rtcomm.app.ui.chat

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.FileTransfer
import coil.compose.AsyncImage
import com.rtcomm.app.data.Message
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.Motion
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.theme.Corner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 从 ChatScreen.kt 拆出的输入栏与附件/Markdown 工具栏（同包 internal，供 ChatScreen 调用）。

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatInputBar(
    input: androidx.compose.ui.text.input.TextFieldValue,
    onInputChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    uploading: Boolean,
    onSend: () -> Unit,
    markdownMode: Boolean,
    onMarkdownModeChange: (Boolean) -> Unit,
    conversationId: String,
    /** 当前回复目标，媒体占位消息需要带上。 */
    replyToId: String? = null,
    onUploadError: (String) -> Unit,
    onVoiceRecorded: (java.io.File) -> Unit,
    /** P2：群成员（@ 补全用）。 */
    members: List<com.rtcomm.app.data.ConversationMember> = emptyList(),
    isGroup: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    var previewing by rememberSaveable(markdownMode) { mutableStateOf(false) }
    // 附件类型选择：点击回形针从底部滑出，而不是直接甩一个 */* 文件选择器。
    var showAttachSheet by remember { mutableStateOf(false) }
    // 快捷回复（个性化）：订阅全局状态，设置页改动立即生效。
    val quickReplies by AppState.quickReplies.collectAsState()
    var showQuickReplies by remember { mutableStateOf(false) }
    // 自定义表情：订阅全局状态；面板内可添加、点击发送、长按删除。
    val customEmojis by AppState.customEmojis.collectAsState()
    var showEmojiSheet by remember { mutableStateOf(false) }
    var emojiToDelete by remember { mutableStateOf<String?>(null) }

    /** 用标记包裹当前选区；没有选区时把光标夹在中间，便于直接输入。 */
    fun wrapSelection(prefix: String, suffix: String = prefix) {
        val text = input.text
        val start = input.selection.min.coerceIn(0, text.length)
        val end = input.selection.max.coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val replaced = prefix + selected + suffix
        onInputChange(
            androidx.compose.ui.text.input.TextFieldValue(
                text = text.replaceRange(start, end, replaced),
                selection = androidx.compose.ui.text.TextRange(
                    start + prefix.length,
                    start + prefix.length + selected.length,
                ),
            ),
        )
    }

    /** 给光标所在行加前缀（引用 / 列表）。 */
    fun prefixLine(prefix: String) {
        val text = input.text
        val caret = input.selection.min.coerceIn(0, text.length)
        val lineStart = if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
        onInputChange(
            androidx.compose.ui.text.input.TextFieldValue(
                text = text.substring(0, lineStart) + prefix + text.substring(lineStart),
                selection = androidx.compose.ui.text.TextRange(caret + prefix.length),
            ),
        )
    }

    /**
     * 统一的媒体发送流程：选完 Uri 立即插入本地占位消息（发送中），
     * 再由 [UploadManager] 后台上传并实时更新进度；不阻塞输入框，支持多文件、取消与重试。
     */
    fun enqueueMedia(uri: Uri, forcedMime: String? = null, asSticker: Boolean = false) {
        scope.launch {
            val media = withContext(Dispatchers.IO) { FileTransfer.prepareMedia(context, uri, forcedMime) }
            // 系统相机输出位于本应用 cacheDir/camera；复制完成后不再需要原始照片。
            if (uri.authority == context.packageName + ".fileprovider") {
                withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(uri, null, null) } }
            }
            if (media == null) { onUploadError("无法读取所选文件"); return@launch }
            val localId = newLocalId()
            val pending = Message(
                id = localId,
                conversationId = conversationId,
                senderId = AppState.currentUser.value?.id ?: "",
                sender = AppState.currentUser.value,
                messageType = media.type,
                content = if (asSticker) "[表情]" else media.name,
                file = com.rtcomm.app.data.MessageFile(
                    fileName = media.name,
                    fileSize = media.size,
                    mimeType = media.mime,
                ),
                replyToMessageId = replyToId,
                createdAt = java.time.Instant.now().toString(),
                sendState = com.rtcomm.app.data.SendState.Sending,
                localPreviewUri = media.previewUri,
                localUploadPath = media.file.absolutePath,
                uploadProgress = 0f,
                uploadTotalBytes = media.size,
            )
            AppState.addPending(conversationId, pending)
            com.rtcomm.app.data.UploadManager.enqueue(conversationId, localId)
        }
    }

    // 多选：图片/视频/文件均支持一次选多个，逐个独立上传。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        uris.forEach { enqueueMedia(it) }
    }

    // 添加自定义表情：复制到私有目录并记入列表（GIF 原样保留）。
    val emojiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { FileTransfer.importEmoji(context, uri) }
            if (path != null) AppState.setCustomEmojis(AppState.customEmojis.value + path)
            else onUploadError("无法读取所选图片")
        }
    }

    // 拍照：系统相机需要我们先给出一个可写的 content URI（file_paths.xml 已开放 cacheDir/camera/）。
    // URI 必须能跨进程恢复：系统相机占内存时本进程可能被回收，回来后结果仍会送达，
    // 普通 remember 会丢 URI 导致照片上传失败（同时残留临时文件）。
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        pendingCameraUri = null
        if (!saved || uri == null) {
            // 用户取消：清掉空文件，避免缓存堆积
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            return@rememberLauncherForActivityResult
        }
        enqueueMedia(uri, "image/*")
    }

    fun launchCamera() {
        val res = runCatching {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
            pendingCameraUri = uri.toString()
            cameraLauncher.launch(uri)
        }
        // 没有相机应用等情况要给出提示，而不是静默失败
        res.onFailure { onUploadError("无法启动相机：${it.message ?: "未知错误"}") }
    }

    // 预览副本只服务于乐观气泡，超过 24h 的一律回收（含失败/中断遗留）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { FileTransfer.cleanupPreviews(context) }
    }

    // P2：@ 补全 —— 光标前最近一个未闭合的 @ 触发，跟随输入过滤
    val mentionQuery by remember(input.text) {
        derivedStateOf {
            if (!isGroup) return@derivedStateOf null
            val text = input.text
            val at = text.lastIndexOf('@')
            if (at < 0) return@derivedStateOf null
            val rest = text.substring(at + 1)
            if (rest.contains(' ') || rest.contains('\n')) null else rest.lowercase()
        }
    }
    val mentionCandidates by remember(mentionQuery, members) {
        derivedStateOf {
            val q = mentionQuery ?: return@derivedStateOf null
            members.filter { it.id != AppState.currentUser.value?.id }
                .filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q) }
                .take(6)
        }
    }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 5.dp,
        shape = RoundedCornerShape(topStart = Corner.sheet, topEnd = Corner.sheet),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            // @ 候选浮层：随候选内容柔和展开，避免输入时突兀跳动。
            AnimatedVisibility(
                visible = mentionCandidates?.isNotEmpty() == true,
                enter = expandVertically(tween(Motion.duration(reducedMotion, Motion.Standard)), expandFrom = Alignment.Bottom) + fadeIn(tween(Motion.duration(reducedMotion, Motion.Standard))),
                exit = shrinkVertically(tween(Motion.duration(reducedMotion, Motion.Quick)), shrinkTowards = Alignment.Bottom) + fadeOut(tween(Motion.duration(reducedMotion, Motion.Quick))),
            ) {
                Surface(tonalElevation = 6.dp, shape = RoundedCornerShape(Corner.small), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Column {
                        mentionCandidates.orEmpty().forEach { u ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val at = input.text.lastIndexOf('@')
                                        if (at >= 0) {
                                            val prefix = input.text.substring(0, at)
                                            val inserted = "$prefix@${u.username} "
                                            onInputChange(
                                                androidx.compose.ui.text.input.TextFieldValue(
                                                    inserted,
                                                    androidx.compose.ui.text.TextRange(inserted.length),
                                                ),
                                            )
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InitialsAvatar(name = u.displayName, size = 26.dp, isBot = u.isBot)
                                Spacer(Modifier.width(Space.sm))
                                Text(u.displayName, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(6.dp))
                                Text("@" + u.username, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // Markdown 工具栏：只在 MD 模式出现，包裹选区/加行前缀，并可在原位预览。
            Reveal(markdownMode) {
                MarkdownToolbar(
                    previewing = previewing,
                    onPreviewToggle = { previewing = !previewing },
                    onBold = { wrapSelection("**") },
                    onItalic = { wrapSelection("*") },
                    onStrike = { wrapSelection("~~") },
                    onCode = { wrapSelection("`") },
                    onCodeBlock = { wrapSelection("```\n", "\n```") },
                    onQuote = { prefixLine("> ") },
                    onList = { prefixLine("- ") },
                )
            }
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 附件与语音相邻成组：两者都是“发媒体”，中间夹 MD 开关会显得割裂。
                IconButton(
                    onClick = { if (!uploading) showAttachSheet = true },
                    enabled = !uploading,
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "附件")
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { if (!uploading) showEmojiSheet = true },
                    enabled = !uploading,
                ) {
                    Icon(Icons.Filled.EmojiEmotions, contentDescription = "表情")
                }
                if (quickReplies.isNotEmpty()) {
                    IconButton(onClick = { showQuickReplies = true }, enabled = !uploading) {
                        Icon(Icons.Filled.Star, contentDescription = "快捷回复")
                    }
                    Spacer(Modifier.width(2.dp))
                }
                Spacer(Modifier.width(Space.xs))
                VoiceRecorderButton(
                    enabled = !uploading,
                    onRecorded = { file -> onVoiceRecorded(file) },
                    onError = { onUploadError(it) },
                )
                Spacer(Modifier.width(6.dp))
                // 编辑 ↔ 原位预览之间淡切，避免切换时输入行「啪」地换成预览。
                FadeSwap(
                    target = markdownMode && previewing,
                    modifier = Modifier.weight(1f),
                ) { previewingNow ->
                    if (previewingNow) {
                        // 原位预览：与输入框同高同宽，切回编辑不跳版。
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(Corner.extra),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 1.dp,
                        ) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (input.text.isBlank()) {
                                    Text(
                                        "预览为空",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    com.rtcomm.app.ui.common.MarkdownText(src = input.text, compact = false)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = input,
                            onValueChange = onInputChange,
                            placeholder = { Text(if (markdownMode) "输入 Markdown…" else "输入消息…") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            shape = RoundedCornerShape(Corner.extra),
                            keyboardOptions = KeyboardOptions.Default,
                        )
                    }
                }
                // MD 开关移到输入框右侧、发送键之前：既保留可达性，也不再打断左侧媒体按钮。
                FilterChip(
                    selected = markdownMode,
                    onClick = { onMarkdownModeChange(!markdownMode) },
                    label = { Text("MD") },
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                val canSend = input.text.isNotBlank()
                val sendScale by animateFloatAsState(
                    targetValue = if (canSend) 1f else 0.86f,
                    animationSpec = spring(
                        dampingRatio = com.rtcomm.app.ui.common.Motion.SpringDamping,
                        stiffness = com.rtcomm.app.ui.common.Motion.SpringStiffness,
                    ),
                    label = "send-scale",
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            }
        }
    }

    // 附件类型快捷菜单：上滑选择要发送的内容类型，再拉起对应的系统选择器。
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    "发送内容",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
                AttachOption(Icons.Filled.PhotoCamera, "拍照", "用相机拍一张照片发送") {
                    showAttachSheet = false
                    launchCamera()
                }
                AttachOption(Icons.Filled.Image, "图片", "从相册选择图片") {
                    showAttachSheet = false
                    picker.launch("image/*")
                }
                AttachOption(Icons.Filled.Videocam, "视频", "选择视频文件") {
                    showAttachSheet = false
                    picker.launch("video/*")
                }
                AttachOption(Icons.Filled.Audiotrack, "音频", "选择音乐或录音") {
                    showAttachSheet = false
                    picker.launch("audio/*")
                }
                AttachOption(Icons.Filled.Folder, "文件", "任意类型文件") {
                    showAttachSheet = false
                    picker.launch("*/*")
                }
            }
        }
    }

    // 快捷回复：点选后追加到输入框。
    if (showQuickReplies) {
        ModalBottomSheet(
            onDismissRequest = { showQuickReplies = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    "快捷回复",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
                quickReplies.forEach { q ->
                    ListItem(
                        modifier = Modifier.motionPress(pressedScale = 0.99f).clickable {
                            onInputChange(androidx.compose.ui.text.input.TextFieldValue(input.text + q))
                            showQuickReplies = false
                        },
                        headlineContent = { Text(q, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }

    // 表情面板：网格展示自定义表情（图片/GIF），点击即发；长按删除；底部可添加。
    if (showEmojiSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Text(
                    "表情",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
                if (customEmojis.isEmpty()) {
                    Text(
                        "还没有自定义表情，点下方「添加表情」选择图片或 GIF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(horizontal = 12.dp),
                    ) {
                        items(customEmojis, key = { it }) { path ->
                            Box(Modifier.aspectRatio(1f).padding(6.dp), contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = java.io.File(path),
                                    contentDescription = "表情",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(Corner.small))
                                        .combinedClickable(
                                            onClick = {
                                                showEmojiSheet = false
                                                enqueueMedia(Uri.fromFile(java.io.File(path)), asSticker = true)
                                            },
                                            onLongClick = { emojiToDelete = path },
                                        ),
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { emojiPicker.launch("image/*") },
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加表情")
                }
            }
        }
    }

    // 长按表情：确认后从列表与本地文件移除。
    emojiToDelete?.let { path ->
        AppAlertDialog(
            onDismissRequest = { emojiToDelete = null },
            title = { Text("删除表情") },
            text = { Text("确定删除这个自定义表情吗？") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { java.io.File(path).delete() }
                    AppState.setCustomEmojis(AppState.customEmojis.value.filterNot { it == path })
                    emojiToDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { emojiToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 附件类型菜单项。 */
@Composable
internal fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(onClick = onClick),
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(9.dp))
            }
        },
        headlineContent = { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
    )
}

/** Markdown 编辑工具栏：横向可滚动，避免窄屏挤爆输入行。 */
@Composable
internal fun MarkdownToolbar(
    previewing: Boolean,
    onPreviewToggle: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onStrike: () -> Unit,
    onCode: () -> Unit,
    onCodeBlock: () -> Unit,
    onQuote: () -> Unit,
    onList: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AssistChip(onClick = onBold, label = { Text("B", fontWeight = FontWeight.Bold) })
        AssistChip(onClick = onItalic, label = { Text("I", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) })
        AssistChip(onClick = onStrike, label = { Text("S", textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) })
        AssistChip(onClick = onCode, label = { Text("`code`") })
        AssistChip(onClick = onCodeBlock, label = { Text("代码块") })
        AssistChip(onClick = onQuote, label = { Text("引用") })
        AssistChip(onClick = onList, label = { Text("列表") })
        AssistChip(
            onClick = onPreviewToggle,
            label = { Text(if (previewing) stringResource(R.string.action_edit) else "预览") },
            leadingIcon = {
                Icon(
                    imageVector = if (previewing) Icons.Filled.Edit else Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}
