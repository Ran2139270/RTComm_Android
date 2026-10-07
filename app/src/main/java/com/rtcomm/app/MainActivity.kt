package com.rtcomm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.db.Repo
import com.rtcomm.app.notify.Notify
import com.rtcomm.app.notify.RealtimeService
import com.rtcomm.app.ui.MainScaffold
import com.rtcomm.app.ui.login.LoginFlow
import com.rtcomm.app.ui.common.FadeInColumn
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.theme.RtcommTheme
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /**
     * 高刷新率适配：把窗口首选显示模式设为本机支持的最高刷新率，
     * 避免部分机型把本应用锁在 60Hz（列表滚动 / 动画会更跟手）。
     */
    private fun applyHighRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
            val modes = d?.supportedModes ?: return
            val cur = d.mode ?: return
            // 只在“与当前分辨率相同”的模式里挑最高刷新率：避免为了高刷切到低分辨率模式，
            // 反而因缩放合成导致滚动掉帧。
            val sameRes = modes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            val best = (if (sameRes.isNotEmpty()) sameRes else modes.toList())
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyHighRefreshRate()
        AppState.system24Hour = android.text.format.DateFormat.is24HourFormat(this)
        consumeNotifIntent(intent)
        setContent {
            val themeMode by AppState.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by AppState.dynamicColor.collectAsStateWithLifecycle()
            val themePreset by AppState.themePreset.collectAsStateWithLifecycle()
            val customPrimary by AppState.customPrimary.collectAsStateWithLifecycle()
            val darkStart by AppState.darkStart.collectAsStateWithLifecycle()
            val darkEnd by AppState.darkEnd.collectAsStateWithLifecycle()
            val fontScale by AppState.fontScale.collectAsStateWithLifecycle()
            val fontFamily by AppState.fontFamily.collectAsStateWithLifecycle()
            val roundedAvatars by AppState.roundedAvatars.collectAsStateWithLifecycle()
            val bubbleCorner by AppState.bubbleCorner.collectAsStateWithLifecycle()
            val amoled by AppState.amoled.collectAsStateWithLifecycle()
            RtcommTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                themePreset = themePreset,
                customPrimary = customPrimary,
                darkStart = darkStart,
                darkEnd = darkEnd,
                fontScale = fontScale,
                fontFamily = fontFamily,
                roundedAvatars = roundedAvatars,
                bubbleCorner = bubbleCorner,
                amoled = amoled,
            ) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // 更新 Activity 持有的 Intent，避免后续 getIntent() 仍拿到旧的一次。
        setIntent(intent)
        consumeNotifIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能在系统设置里改了 12/24 小时制，回前台时刷新。
        AppState.system24Hour = android.text.format.DateFormat.is24HourFormat(this)
    }

    /** 通知点击 → 待打开会话（由 MainScaffold 消费导航）。 */
    private fun consumeNotifIntent(intent: android.content.Intent?) {
        if (intent == null) return
        // 越权防护：本 Activity 因 LAUNCHER 必须导出，外部 App 可显式启动并附带 extra。
        // Android 14+ 可查发起方包名，非本应用一律忽略导航参数（防越权跳转/钓鱼）。
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val from = runCatching { launchedFromPackage }.getOrNull()
            if (from != null && from != packageName) return
        }
        val convId = intent.getStringExtra("openConversationId")
        // 外部 Intent 可能被伪造：只接受形如会话 id 的短字符串，避免注入任意内容。
        if (convId != null && convId.length in 1..128 &&
            convId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        ) {
            AppState.pendingOpenConversationId.value = convId
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val authStore = remember { AuthStore(context.applicationContext) }
    val loggedIn by AppState.loggedIn.collectAsStateWithLifecycle()
    /** 启动装载（会话恢复）是否完成——完成前显示加载动画。 */
    var booted by remember { mutableStateOf(false) }
    /** 启动失败原因；非空时展示可重试的错误页，而不是永久停在启动动画。 */
    var bootError by remember { mutableStateOf<String?>(null) }
    var bootAttempt by remember { mutableIntStateOf(0) }
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()

    // 通知权限（Android 13+）：登录后申请一次
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 启动装配：本地缓存初始化 + 持久化配置 + 401 回调 + 恢复会话。
    // 任一步骤抛异常都通过 finally 结束启动态，并展示错误页而非卡在启动动画。
    LaunchedEffect(bootAttempt) {
        bootError = null
        try {
            // 重初始化放 IO 线程，避免首帧卡顿（Room/诊断/通知渠道都是磁盘/系统调用）。
            withContext(Dispatchers.IO) {
                Repo.init(context.applicationContext)
                com.rtcomm.app.data.AppDiagnostics.initialize(context.applicationContext)
                com.rtcomm.app.data.UploadBookmarks.init(context.applicationContext)
                Notify.ensureChannels(context.applicationContext)
                // 首次读取加密存储（SecurePrefs；含旧库一次性迁移）会触发 Keystore 与磁盘 IO，
                // 放进 IO 块，避免冷启动时主线程 jank/ANR。
                authStore.applyToApi()
            AppState.themeMode.value = authStore.themeMode
            AppState.dynamicColor.value = authStore.dynamicColor
            AppState.themePreset.value = authStore.themePreset
            AppState.customPrimary.value = authStore.customPrimary
            AppState.darkStart.value = authStore.darkStart
            AppState.darkEnd.value = authStore.darkEnd
            AppState.fontScale.value = authStore.fontScale
            AppState.amoled.value = authStore.amoled
            AppState.chatDensity.value = authStore.chatDensity
            AppState.chatWallpaper.value = authStore.chatWallpaper
            AppState.chatBackground.value = authStore.chatBackground
            AppState.appBackground.value = authStore.appBackground
            AppState.bgDim.value = authStore.bgDim
            AppState.bgBlur.value = authStore.bgBlur
            AppState.chatBgDim.value = authStore.chatBgDim
            AppState.chatBgBlur.value = authStore.chatBgBlur
            AppState.fontFamily.value = authStore.fontFamily
            AppState.bubbleCorner.value = authStore.bubbleCorner
            AppState.roundedAvatars.value = authStore.roundedAvatars
            AppState.aiThinking.value = authStore.aiThinking
            AppState.aiShowThinking.value = authStore.aiShowThinking
            AppState.reduceMotion.value = authStore.reduceMotionPref
            AppState.reduceTransparency.value = authStore.reduceTransparencyPref
            AppState.timeFormat.value = authStore.timeFormatPref
            AppState.quickReplies.value = authStore.quickReplies
            AppState.customEmojis.value = authStore.customEmojis
            AppState.authStore = authStore
            Api.onUnauthorized = {
                // 401：清理登录态并回到登录页（可在任意线程调用）
                authStore.clearSession()
                AppState.reset()
                WsClient.stop()
                com.rtcomm.app.data.UploadBookmarks.clearAll()
                RealtimeService.stop(context.applicationContext)
            }
            }
            if (authStore.hasSession()) {
                val meResult = withContext(Dispatchers.IO) { runCatching { Api.me() } }
                val me = meResult.getOrNull()
                if (me != null) {
                    authStore.saveUser(me)
                    AppState.signIn(me)
                    if (authStore.autoConnectWs) WsClient.start()
                    if (authStore.notificationsEnabled) requestNotifPermission()
                } else {
                    // 弱网、DNS 或短暂服务异常不应把有效登录态清掉；保留缓存并允许稍后重连。
                    val failure = meResult.exceptionOrNull()
                    if (failure is Api.ApiException && (failure.code == 401 || failure.code == 403)) {
                        authStore.clearSession()
                        AppState.reset()
                    } else {
                        authStore.user()?.let { cachedUser ->
                            AppState.signIn(cachedUser)
                            if (authStore.autoConnectWs) WsClient.start()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            bootError = t.message?.takeIf { it.isNotBlank() } ?: "初始化失败"
        } finally {
            booted = true
        }
    }

    // 生命周期感知：进后台暂停 WS，回前台恢复（避免泄漏与快速重连）
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        AppState.inForeground.value = false
        WsClient.pause(keepAlive = authStore.notificationsEnabled && authStore.autoConnectWs)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        AppState.inForeground.value = true
        if (AppState.loggedIn.value && authStore.autoConnectWs) WsClient.resume()
    }

    // 登录态切换：启停前台服务（杀进程后仍可收消息）
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            if (authStore.notificationsEnabled) {
                requestNotifPermission()
                RealtimeService.start(context.applicationContext)
                // 注册 FCM 令牌：进程被杀/WS 断开时由服务端推送兜底。
                com.rtcomm.app.push.PushRegistrar.register()
            }
        } else {
            RealtimeService.stop(context.applicationContext)
            Notify.cancelAll(context.applicationContext)
        }
    }

    // 启动装载/会话恢复期间显示品牌化、低干扰的加载状态，避免闪登录页。
    if (!booted) {
        val splashMotion = rememberInfiniteTransition(label = "splash")
        val haloScale by splashMotion.animateFloat(
            initialValue = 0.90f,
            targetValue = if (reducedMotion) 0.90f else 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "splash-halo-scale",
        )
        val haloAlpha by splashMotion.animateFloat(
            initialValue = 0.18f,
            targetValue = if (reducedMotion) 0.18f else 0.42f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "splash-halo-alpha",
        )
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                    modifier = Modifier.size(104.dp),
                ) {
                    Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size((84f * haloScale).dp),
                    ) {}
                    Surface(
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        tonalElevation = 4.dp,
                        modifier = Modifier.size(64.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(
                                "R",
                                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "RTComm",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "正在准备你的会话",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.size(width = 128.dp, height = 3.dp),
                    trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        return
    }

    // 启动失败：给出明确原因与重试入口（之前异常会让协程静默取消、永久卡在启动动画）。
    bootError?.let { message ->
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(28.dp),
            ) {
                Text(
                    "启动失败",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { bootAttempt++ }) { Text("重试") }
            }
        }
        return
    }

    // 启动自动检查更新：有新版本且未被跳过时弹提示（最多跳过当前及之后 2 个版本）。
    var updatePrompt by remember { mutableStateOf<com.rtcomm.app.data.AppUpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        val info = com.rtcomm.app.data.UpdateManager.checkAndAwait(context.applicationContext)
        if (info != null) {
            if (info.versionCode > authStore.updateSkipUntil) updatePrompt = info
        } else if (com.rtcomm.app.data.UpdateManager.state.value is com.rtcomm.app.data.UpdateManager.State.Idle) {
            // 已是最新（或更高）：清除历史「跳过此版本」标记，避免残留导致后续误判。
            if (authStore.updateSkipUntil != 0) authStore.updateSkipUntil = 0
        }
    }

    AnimatedContent(
        targetState = loggedIn,
        transitionSpec = {
            (fadeIn(tween(if (reducedMotion) 0 else 220)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 160)))
        },
        label = "auth-root",
    ) { isIn ->
        // 启动动画结束/登录态切换时内容淡入，替代首帧硬出现。
        if (isIn) {
            FadeInColumn { MainScaffold(authStore = authStore) }
        } else {
            FadeInColumn {
                LoginFlow(
                    authStore = authStore,
                    onLoggedIn = {
                        // LoginScreen 已完成 signIn（绑定账号隔离键）；这里只启动实时通道。
                        if (authStore.autoConnectWs) WsClient.start()
                    },
                    // 有已保存账号时，允许从登录页取消（添加账号/退出登录）并恢复最近账号，避免被困。
                    onBack = if (authStore.savedAccounts().isNotEmpty()) {
                        {
                            val acc = authStore.savedAccounts().lastOrNull()
                            val user = acc?.let { authStore.restoreAccount(it) }
                            if (user != null) {
                                AppState.signIn(user)
                                if (authStore.autoConnectWs) WsClient.start()
                                if (authStore.notificationsEnabled) {
                                    RealtimeService.start(context.applicationContext)
                                    com.rtcomm.app.push.PushRegistrar.register()
                                }
                            }
                        }
                    } else null,
                )
            }
        }
    }

    updatePrompt?.let { info ->
        com.rtcomm.app.ui.update.UpdatePromptDialog(
            info = info,
            onLater = { updatePrompt = null },
            onSkip = {
                // 跳过当前及之后 2 个版本（最多三个）。
                authStore.updateSkipUntil = info.versionCode + 2
                updatePrompt = null
            },
        )
    }
}
