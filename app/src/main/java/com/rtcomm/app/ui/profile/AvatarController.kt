package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.AvatarItem
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.PublicUser
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.DialogAppear
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 头像弹层要展示的目标。 */
data class AvatarTarget(
    val name: String,
    val avatarUrl: String?,
    val isMe: Boolean,
    val username: String? = null,
    val bio: String? = null,
    val isBot: Boolean = false,
)

/**
 * 头像控制器：把「点头像 → 查看/修改/历史/裁剪」的整条链路收口到一处。
 *
 * 之前这些入口散落在「我的」页顶部、底部「头像与历史」、「移除当前头像」三处，
 * 且只有本人可用。现在任意头像（会话、聊天、成员、AI）都能复用同一套交互。
 *
 * 用法：在页面组合中 `val avatar = rememberAvatarController()`，
 * 然后 `InitialsAvatar(..., onClick = { avatar.openUser(peer) })`。
 */
class AvatarController(
    val openMe: () -> Unit,
    val openUser: (PublicUser) -> Unit,
    /** 直接打开任意目标（成员、搜索结果等没有完整 PublicUser 的场景）。 */
    val open: (AvatarTarget) -> Unit,
)

@Composable
fun rememberAvatarController(): AvatarController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by AppState.currentUser.collectAsState()
    val meState = rememberUpdatedState(me)

    var viewing by remember { mutableStateOf<AvatarTarget?>(null) }
    var editing by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<AvatarTarget?>(null) }
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var avatars by remember { mutableStateOf<List<AvatarItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun applyUser(user: PublicUser?) {
        if (user != null) {
            runCatching { AuthStore(context).saveUser(user) }
            AppState.signIn(user)
        }
    }

    fun uploadAvatar(dataUrl: String) {
        busy = true; busyLabel = "正在上传头像…"; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.uploadAvatar(dataUrl) } }
            busy = false; busyLabel = null
            r.onSuccess { resp ->
                // 服务端应返回 user；若缺失则回查 /auth/me，确保头像立刻刷新。
                val user = resp.user ?: withContext(Dispatchers.IO) { runCatching { Api.me() }.getOrNull() }
                applyUser(user)
                avatars = resp.avatars
                message = "头像已更新"
                user?.let { u ->
                    viewing = AvatarTarget(u.displayName, u.avatarUrl, true, u.username, u.bio, u.isBot)
                }
            }.onFailure { message = "上传失败：${Api.userMessage(it)}" }
        }
    }

    fun loadAvatars() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.avatars() } }
            r.onSuccess { avatars = it }
        }
    }

    /** 是否为动态图（GIF/WebP）：优先 MIME，兜底看路径后缀。 */
    fun isAnimated(uri: Uri): Boolean {
        val mime = FileTransfer.mimeOf(context, uri)
        if (mime == "image/gif" || mime == "image/webp") return true
        val path = uri.lastPathSegment?.lowercase() ?: ""
        return path.endsWith(".gif") || path.endsWith(".webp")
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isAnimated(uri)) {
            // 动态头像：原样读取并上传（保留动画），全程显示加载动画。
            busy = true; busyLabel = "正在上传头像…"; message = null
            scope.launch {
                val dataUrl = withContext(Dispatchers.IO) { runCatching { FileTransfer.rawDataUrl(context, uri) }.getOrNull() }
                if (dataUrl == null) {
                    busy = false; busyLabel = null
                    message = "动态头像需为 ≤10MB 的 GIF/WebP"
                } else uploadAvatar(dataUrl)
            }
        } else {
            // 静态图：进入裁切，确认后再上传（上传时同样有加载动画）。
            cropUri = uri
        }
    }

    // 相机输出到 cacheDir/camera，复用 FileProvider（与聊天拍照一致）。
    var pendingCameraUri by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        pendingCameraUri = null
        if (!saved || uri == null) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            return@rememberLauncherForActivityResult
        }
        cropUri = uri
    }

    fun launchCamera() {
        runCatching {
            val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "avatar_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            pendingCameraUri = uri.toString()
            camera.launch(uri)
        }.onFailure { message = "无法启动相机：${it.message ?: "未知错误"}" }
    }

    fun removeAvatar() {
        busy = true; busyLabel = "正在移除头像…"; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.clearAvatar() } }
            busy = false; busyLabel = null
            r.onSuccess { resp ->
                val user = resp.user ?: withContext(Dispatchers.IO) { runCatching { Api.me() }.getOrNull() }
                applyUser(user)
                avatars = resp.avatars
                message = "已移除当前头像"
                user?.let { u ->
                    viewing = AvatarTarget(u.displayName, u.avatarUrl, true, u.username, u.bio, u.isBot)
                }
            }.onFailure { message = "移除失败：${Api.userMessage(it)}" }
        }
    }

    fun selectAvatar(id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.selectAvatar(id) } }
            r.onSuccess { resp ->
                val user = resp.user ?: withContext(Dispatchers.IO) { runCatching { Api.me() }.getOrNull() }
                applyUser(user)
                avatars = resp.avatars
                message = "已切换头像"
                historyOpen = false
                user?.let { u ->
                    viewing = AvatarTarget(u.displayName, u.avatarUrl, true, u.username, u.bio, u.isBot)
                }
            }.onFailure { message = "切换失败：${Api.userMessage(it)}" }
        }
    }

    fun deleteAvatar(id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.deleteAvatar(id) } }
            r.onSuccess { resp -> applyUser(resp.user); avatars = resp.avatars }
                .onFailure { message = Api.userMessage(it) }
        }
    }

    val controller = remember {
        AvatarController(
            openMe = {
                meState.value?.let { u ->
                    viewing = AvatarTarget(u.displayName, u.avatarUrl, true, u.username, u.bio, u.isBot)
                }
            },
            openUser = { u ->
                val isMe = u.id == meState.value?.id
                viewing = AvatarTarget(u.displayName, u.avatarUrl, isMe, u.username, u.bio, u.isBot)
            },
            open = { t -> viewing = t },
        )
    }

    // ── 弹层 ────────────────────────────────────────────────────
    viewing?.let { target ->
        AvatarViewerSheet(
            target = target,
            busy = busy,
            message = message,
            onDismiss = { viewing = null; message = null },
            onViewFull = { fullscreen = target },
            onEdit = { editing = true; viewing = null; message = null },
            onCopyId = {
                target.username?.let { clipboardText(context, it) }
                message = "已复制用户名"
            },
        )
    }

    if (editing) {
        AvatarEditSheet(
            hasAvatar = me?.avatarUrl != null,
            busy = busy,
            onDismiss = { editing = false; message = null },
            onCamera = { editing = false; launchCamera() },
            onGallery = { editing = false; gallery.launch("image/*") },
            onHistory = { editing = false; loadAvatars(); historyOpen = true },
            onRemove = { editing = false; removeAvatar() },
        )
    }

    if (historyOpen) {
        AvatarHistorySheet(
            avatars = avatars,
            onDismiss = { historyOpen = false },
            onUpload = { historyOpen = false; gallery.launch("image/*") },
            onSelect = { selectAvatar(it) },
            onDelete = { deleteAvatar(it) },
        )
    }

    fullscreen?.let { target ->
        AvatarFullscreen(target, onDismiss = { fullscreen = null })
    }

    cropUri?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onCancel = { cropUri = null },
            onCropped = { data -> cropUri = null; uploadAvatar(data) },
        )
    }

    // 上传/移除头像时的加载动画（不再无反馈干等）。
    busyLabel?.let { label ->
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            DialogAppear {
            Surface(shape = RoundedCornerShape(Corner.medium), tonalElevation = 6.dp) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Space.md))
                    Text(label)
                }
            }
            }
        }
    }

    // 若结果提示没有对应弹层可展示（例如动态头像超限），用独立对话框反馈，避免"点了没反应"。
    if (message != null && viewing == null && !editing && !historyOpen) {
        AppAlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(message ?: "") },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }

    return controller
}

private fun clipboardText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(android.content.ClipData.newPlainText("avatar", text))
}

private fun avatarFullUrl(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
    if (it.startsWith("http")) it else Api.baseUrl.trimEnd('/') + it
}

/** 头像查看弹层：大图预览 + 查看/修改/历史/复制。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarViewerSheet(
    target: AvatarTarget,
    busy: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onViewFull: () -> Unit,
    onEdit: () -> Unit,
    onCopyId: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                InitialsAvatar(
                    name = target.name,
                    avatarUrl = target.avatarUrl,
                    isBot = target.isBot,
                    size = 96.dp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                target.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            target.username?.let {
                Text(
                    "@$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            target.bio?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp).align(Alignment.CenterHorizontally),
                )
            }
            message?.let {
                Spacer(Modifier.height(Space.sm))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(Space.md))
            HorizontalDivider()
            if (target.avatarUrl != null) {
                SheetAction(Icons.Filled.Visibility, "查看头像", enabled = !busy, onClick = onViewFull)
            }
            if (target.isMe) {
                SheetAction(Icons.Filled.PhotoCamera, "修改头像", enabled = !busy, onClick = onEdit)
            }
            target.username?.let {
                SheetAction(Icons.Filled.ContentCopy, "复制用户名", enabled = true, onClick = onCopyId)
            }
        }
    }
}

/** 修改头像入口：拍照 / 相册 / 历史 / 移除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarEditSheet(
    hasAvatar: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onHistory: () -> Unit,
    onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                "修改头像",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            SheetAction(Icons.Filled.PhotoCamera, "拍照", enabled = !busy, onClick = onCamera)
            SheetAction(Icons.Filled.PhotoLibrary, "从相册选择", enabled = !busy, onClick = onGallery)
            SheetAction(Icons.Filled.Image, "从历史头像选择", enabled = !busy, onClick = onHistory)
            if (hasAvatar) {
                SheetAction(Icons.Filled.Delete, "移除当前头像", enabled = !busy, danger = true, onClick = onRemove)
            }
        }
    }
}

/** 历史头像：网格选择/删除/上传。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarHistorySheet(
    avatars: List<AvatarItem>,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val myId = AppState.currentUser.value?.id
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("历史头像", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onUpload) { Text("上传新头像") }
            }
            Text(
                "最多保留 10 张；GIF/WebP 动态头像会自动播放。点击切换，长按删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))
            if (avatars.isEmpty()) {
                Text("还没有自定义头像", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(avatars, key = { it.id }) { a ->
                        val url = "${Api.baseUrl.trimEnd('/')}/api/users/$myId/avatars/${a.id}"
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .motionPress(pressedScale = 0.94f)
                                .clickable { onSelect(a.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(context).data(url).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                            )
                            if (a.current) {
                                Box(
                                    Modifier.fillMaxSize().clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = "当前", tint = Color.White)
                                }
                            }
                        }
                        if (!a.current) {
                            TextButton(
                                onClick = { onDelete(a.id) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 全屏头像查看。 */
@Composable
private fun AvatarFullscreen(target: AvatarTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        DialogAppear {
        Surface(shape = RoundedCornerShape(Corner.large), tonalElevation = 4.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val url = avatarFullUrl(target.avatarUrl)
                Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    if (url != null) {
                        coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(context).data(url).build(),
                            contentDescription = target.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        InitialsAvatar(target.name, size = 260.dp, isBot = target.isBot)
                    }
                }
                Spacer(Modifier.height(Space.md))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
        }
    }
}

/** 弹层里的一行操作。 */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(enabled = enabled, onClick = onClick),
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
    )
}
