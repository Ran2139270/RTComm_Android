package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.AiQuota
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.data.UpdateManager
import com.rtcomm.app.data.UpdateProfileReq
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppSwitch
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页分组标题。 */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 分组卡片：圆角表面容器，把入口行包成一块（与设置页一致）。
 * 内部行用 [NavRow] 的 transparent=true，避免行自带的不透明底色盖住卡片圆角。
 */
@Composable
internal fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(Corner.medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** 开关行：整行可点击切换（无障碍 role=Switch），点击行任意处都能生效。 */
@Composable
internal fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(Space.md))
        Text(label, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 可点击的列表项（带可选副标题）。
 * 默认 [transparent]=true：不画自身底色，让所在 [GroupCard] 的圆角/底色透出来。
 * 之前默认 false，行用不透明底色盖住卡片，只在卡片上下各露 4dp 底色，看起来像「每张卡片
 * 顶/底各有一条线」。所有调用点都在卡片内，故默认透明即可。
 */
@Composable
internal fun NavRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    transparent: Boolean = true,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(onClick = onClick),
        colors = if (transparent) {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        supportingContent = if (subtitle == null) null else {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        },
        trailingContent = if (showChevron) {
            {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
    )
}

/** 分段选择器（≤4 项时使用；更多项请用 FlowRow 芯片避免窄屏溢出）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SegmentedChoice(
    title: String,
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, label) ->
                SegmentedButton(
                    selected = value == key,
                    onClick = { onSelect(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label) }
            }
        }
    }
}

@Composable
internal fun EditNicknameDialog(current: String, onDismiss: () -> Unit, onSaved: (com.rtcomm.app.data.PublicUser) -> Unit) {
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(current) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改昵称") },
        text = {
            Column {
                OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (nickname.isBlank()) { err = "昵称不能为空"; return@TextButton }
                busy = true; err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.updateProfile(UpdateProfileReq(nickname = nickname.trim())) } }
                    busy = false
                    r.onSuccess { u -> if (u != null) onSaved(u) else err = "更新失败" }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "保存中…" else stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ChangePasswordDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var currentPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(currentPwd, { currentPwd = it }, label = { Text("当前密码") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(newPwd, { newPwd = it }, label = { Text("新密码（≥6 位）") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (currentPwd.isBlank() || newPwd.length < 6) { err = "请输入当前密码与至少 6 位新密码"; return@TextButton }
                busy = true; err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.updateProfile(UpdateProfileReq(currentPassword = currentPwd, password = newPwd)) } }
                    busy = false
                    r.onSuccess { onChanged() }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "提交中…" else "确认修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun ServerDialog(authStore: AuthStore, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember { mutableStateOf(authStore.serverUrl) }
    var err by remember { mutableStateOf<String?>(null) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器地址") },
        text = {
            Column {
                Text("修改服务器后需要重新登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; err = null },
                    label = { Text("Base URL") },
                    singleLine = true,
                    isError = err != null,
                    supportingText = { Text(err ?: "必须是无路径参数的 HTTPS 地址") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = Api.normalizeServerUrl(url)
                if (normalized == null) {
                    err = "地址不合法，请输入 https:// 开头的服务器地址"
                } else if (normalized != authStore.serverUrl) {
                    onSave(normalized)
                } else onDismiss()
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 检查更新对话框：状态由 [UpdateManager] 持有，下载不随对话框销毁。 */
@Composable
internal fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    var installErr by remember { mutableStateOf<String?>(null) }

    fun requestInstallPermission() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        )
        runCatching { context.startActivity(intent) }
            .onFailure { installErr = "无法打开安装授权设置：${it.message}" }
    }

    fun install(apk: java.io.File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            installErr = "请在系统页面开启「允许来自此来源的应用」，返回后再次点击“安装更新”"
            requestInstallPermission()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apk,
        )
        val install = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(install) }
            .onSuccess { onDismiss() }
            .onFailure { installErr = "无法启动安装器：${it.message}" }
    }

    LaunchedEffect(Unit) {
        if (UpdateManager.state.value is UpdateManager.State.Idle) UpdateManager.check(context)
    }

    val currentCode = com.rtcomm.app.data.AppVersion.code(context)

    // 只按状态种类做淡切 key：下载进度刷新不会触发反复淡入。
    val stateKind = when (state) {
        is UpdateManager.State.Checking -> 0
        is UpdateManager.State.Available -> 1
        is UpdateManager.State.Downloading -> 2
        is UpdateManager.State.Downloaded -> 3
        is UpdateManager.State.Failed -> 4
        UpdateManager.State.Idle -> 5
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = {
            Column {
                FadeSwap(target = stateKind) { kind ->
                    when (kind) {
                        0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在检查新版本…")
                        }
                        1 -> (state as? UpdateManager.State.Available)?.let { s ->
                            Column {
                                if (s.info.versionCode > currentCode) {
                                    Text("发现新版本 v${s.info.versionName}", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(6.dp))
                                    s.info.notes?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Text("大小：${FileTransfer.humanSize(s.info.fileSize)}", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("已是最新版本（v${com.rtcomm.app.data.AppVersion.name(context)}）")
                                }
                            }
                        }
                        2 -> (state as? UpdateManager.State.Downloading)?.let { s ->
                            Column {
                                Text("正在下载 v${s.info.versionName}…", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(Space.xs))
                                Text("下载中 ${(s.progress * 100).toInt()}%（关闭对话框也会继续下载）", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        3 -> Column {
                            Text("新版本已下载", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "请点击“安装更新”。如首次安装，系统会要求允许本应用安装未知来源 APK。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        4 -> (state as? UpdateManager.State.Failed)?.let { s ->
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                        }
                        else -> Text("服务器暂无发布版本")
                    }
                }
                installErr?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateManager.State.Downloaded ->
                    TextButton(onClick = { install(s.apk) }) { Text("安装更新") }
                is UpdateManager.State.Available ->
                    if (s.info.versionCode > currentCode) {
                        TextButton(onClick = { UpdateManager.download(context, s.info) }) { Text("下载更新") }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    }
                is UpdateManager.State.Failed ->
                    TextButton(onClick = { UpdateManager.check(context) }) { Text(stringResource(R.string.action_retry)) }
                is UpdateManager.State.Checking -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                is UpdateManager.State.Downloading -> TextButton(onClick = onDismiss) { Text("后台下载") }
                UpdateManager.State.Idle -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

/** 编辑个性签名。 */
@Composable
internal fun EditBioDialog(current: String, onDismiss: () -> Unit, onSaved: (com.rtcomm.app.data.PublicUser) -> Unit) {
    val scope = rememberCoroutineScope()
    var bio by remember { mutableStateOf(current) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个性签名") },
        text = {
            Column {
                OutlinedTextField(
                    bio, { if (it.length <= 200) bio = it },
                    label = { Text("个性签名（≤200 字）") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                )
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.updateProfile(UpdateProfileReq(bio = bio.trim())) } }
                    busy = false
                    r.onSuccess { u -> if (u != null) onSaved(u) else err = "保存失败" }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "保存中…" else stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 输入兑换码增加 AI 额度。 */
@Composable
internal fun RedeemDialog(onDismiss: () -> Unit, onRedeemed: (AiQuota?) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AppAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("使用兑换码") },
        text = {
            Column {
                Text("输入管理员发放的兑换码以增加 AI 额度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text("兑换码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val c = code.trim()
                if (c.isBlank()) { err = "请输入兑换码"; return@TextButton }
                busy = true; err = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Api.redeemCode(c) } }
                    busy = false
                    r.onSuccess { onRedeemed(it) }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "兑换中…" else "兑换") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun QuickRepliesDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSaved: (List<String>) -> Unit,
) {
    var list by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快捷回复") },
        text = {
            Column {
                list.forEachIndexed { index, p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { list = list.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, label = { Text("新增常用语") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        val v = input.trim()
                        if (v.isNotEmpty() && list.size < 20) { list = list + v; input = "" }
                    }) { Text(stringResource(R.string.action_add)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSaved(list) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 免打扰时段设置（按本地时间小时）。 */
@Composable
internal fun DndDialog(start: Int, end: Int, onDismiss: () -> Unit, onSaved: (Int, Int) -> Unit) {
    var s by remember { mutableStateOf(start.toString()) }
    var e by remember { mutableStateOf(end.toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("免打扰时段") },
        text = {
            Column {
                Text("该时段内不发送系统通知（消息与未读不受影响）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedTextField(
                        s, { s = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("开始(0-23)") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        e, { e = it.filter { c -> c.isDigit() }.take(2) }, label = { Text("结束(0-23)") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                err?.let { Spacer(Modifier.height(Space.sm)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sv = s.toIntOrNull(); val ev = e.toIntOrNull()
                if (sv == null || ev == null || sv !in 0..23 || ev !in 0..23) { err = "请输入 0-23 的整数"; return@TextButton }
                onSaved(sv, ev)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
