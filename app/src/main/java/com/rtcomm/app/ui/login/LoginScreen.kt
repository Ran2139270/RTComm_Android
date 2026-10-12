package com.rtcomm.app.ui.login

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.Motion
import com.rtcomm.app.ui.common.rememberReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginFlow(authStore: AuthStore, onLoggedIn: () -> Unit, onBack: (() -> Unit)? = null) {
    var mode by remember { mutableStateOf("login") } // login / register
    var showServer by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val reducedMotion = rememberReducedMotion()

    // 从「添加账号 / 退出登录」进入时，允许取消并恢复已保存账号。
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                // 键盘出现时跟随 IME 抬高，同时允许滚动将当前输入框露出。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(Space.xl))
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(Space.lg))
            Text("rtcomm", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                "多用户实时通信 · AI 助手 · 远程控制",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xl))

            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val d = Motion.duration(reducedMotion, Motion.Emphasized)
                    if (targetState == "register") {
                        (slideInHorizontally(tween(d, easing = Motion.EmphasizedEasing)) { it } + fadeIn(tween(d))) togetherWith
                            (slideOutHorizontally(tween(d, easing = Motion.EmphasizedEasing)) { -it } + fadeOut(tween(d)))
                    } else {
                        (slideInHorizontally(tween(d, easing = Motion.EmphasizedEasing)) { -it } + fadeIn(tween(d))) togetherWith
                            (slideOutHorizontally(tween(d, easing = Motion.EmphasizedEasing)) { it } + fadeOut(tween(d)))
                    }
                },
                label = "auth-card",
            ) { m ->
                if (m == "login") {
                    LoginCard(
                        authStore = authStore,
                        onLoggedIn = onLoggedIn,
                        onGoRegister = { mode = "register" },
                        onOpenServer = { showServer = true },
                    )
                } else {
                    RegisterCard(
                        authStore = authStore,
                        onLoggedIn = onLoggedIn,
                        onBack = { mode = "login" },
                    )
                }
            }
            Spacer(Modifier.height(Space.xl))
        }
        if (onBack != null) {
            IconButton(
                onClick = { onBack() },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(Space.sm),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        }
    }

    if (showServer) {
        ServerSheet(
            authStore = authStore,
            sheetState = sheetState,
            onDismiss = { showServer = false },
        )
    }
}

@Composable
private fun LoginCard(
    authStore: AuthStore,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit,
    onOpenServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card(Modifier.widthIn(max = 420.dp).fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("登录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.lg))
            AuthTextField(username, { username = it }, "用户名", Icons.Filled.Person, ImeAction.Next)
            Spacer(Modifier.height(Space.md))
            PasswordField(password, { password = it }, showPwd) { showPwd = !showPwd }
            if (err != null) { Spacer(Modifier.height(Space.sm)); ErrorBanner(err!!) }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) { err = "请输入用户名和密码"; return@Button }
                    busy = true; err = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                val r = Api.login(username.trim(), password)
                                val token = r.token
                                if (!token.isNullOrEmpty()) { authStore.save(token, r.user); AppState.signIn(r.user); r.user }
                                else throw Api.ApiException(500, "登录响应缺少令牌")
                            }
                        }
                        busy = false
                        res.onSuccess { onLoggedIn() }
                        res.onFailure { err = Api.userMessage(it) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(Space.sm)); Text("登录中…")
                } else Text("登 录")
            }
            Spacer(Modifier.height(Space.xs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onGoRegister) { Text("没有账号？注册") }
                TextButton(onClick = onOpenServer) {
                    Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs)); Text("服务器")
                }
            }
        }
    }
}

@Composable
private fun RegisterCard(
    authStore: AuthStore,
    onLoggedIn: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card(Modifier.widthIn(max = 420.dp).fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("注册新账号", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.lg))
            AuthTextField(username, { username = it }, "用户名（2-32 位）", Icons.Filled.Person, ImeAction.Next)
            Spacer(Modifier.height(Space.md))
            AuthTextField(nickname, { nickname = it }, "昵称（可选）", Icons.Filled.Person, ImeAction.Next)
            Spacer(Modifier.height(Space.md))
            PasswordField(password, { password = it }, showPwd, "密码（≥6 位）") { showPwd = !showPwd }
            Spacer(Modifier.height(Space.md))
            // 邀请制：普通账号必须凭管理员发放的一次性邀请码注册。
            AuthTextField(inviteCode, { inviteCode = it.uppercase() }, "邀请码（向管理员索取）", Icons.Filled.Key, ImeAction.Done)
            Spacer(Modifier.height(6.dp))
            Text(
                "本服务器为邀请制，注册需要一次性邀请码；已有账号登录不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (err != null) { Spacer(Modifier.height(Space.sm)); ErrorBanner(err!!) }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) { err = "用户名和密码必填"; return@Button }
                    if (inviteCode.isBlank()) { err = "请输入邀请码"; return@Button }
                    busy = true; err = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                val r = Api.register(
                                    username.trim(),
                                    password,
                                    nickname.ifBlank { null },
                                    inviteCode.trim(),
                                )
                                val token = r.token
                                if (!token.isNullOrEmpty()) { authStore.save(token, r.user); AppState.signIn(r.user); r.user }
                                else throw Api.ApiException(500, "注册响应缺少令牌")
                            }
                        }
                        busy = false
                        res.onSuccess { onLoggedIn() }
                        res.onFailure { err = Api.userMessage(it) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(Space.sm)); Text("注册中…")
                } else Text("注 册")
            }
            Spacer(Modifier.height(Space.xs))
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("返回登录") }
        }
    }
}

@Composable
private fun AuthTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(icon, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    show: Boolean,
    label: String = "密码",
    onToggle: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onToggle) {
                Icon(
                    if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (show) "隐藏密码" else "显示密码",
                )
            }
        },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(
    authStore: AuthStore,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(authStore.serverUrl) }
    var err by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("服务器地址", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.xs))
            Text(
                "默认连接官方后端。仅在自建后端时修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; err = null },
                label = { Text("Base URL") },
                singleLine = true,
                isError = err != null,
                supportingText = { Text(err ?: "必须是无路径参数的 HTTPS 地址，例如 https://your-server.example") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { url = Api.DEFAULT_BASE_URL; err = null }) { Text("恢复默认") }
                Spacer(Modifier.width(Space.sm))
                Button(onClick = {
                    // 结构化校验：只接受合法 HTTPS origin，拒绝 userinfo/query/fragment。
                    val normalized = Api.normalizeServerUrl(url)
                    if (normalized == null) {
                        err = "地址不合法，请输入 https:// 开头的服务器地址"
                    } else {
                        authStore.serverUrl = normalized
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

