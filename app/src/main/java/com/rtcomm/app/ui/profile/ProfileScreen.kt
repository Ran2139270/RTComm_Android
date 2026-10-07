package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.notify.Notify
import com.rtcomm.app.notify.RealtimeService
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」主页：资料卡 + 账户 / 通用 / 关于 / 管理 入口。
 *
 * 资料卡与「个人资料」二级页原有一张重复的卡片，已合并为「我的」这一张：
 * - 点卡片 → 进入二级「个人资料」页（昵称 / 签名 / 用户名 / 密码 / AI 额度）；
 * - 点头像 → 头像查看 / 修改 / 历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authStore: AuthStore,
    onOpenCalls: () -> Unit,
    onOpenAdmin: () -> Unit = {},
    onOpenProfileDetail: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val me by AppState.currentUser.collectAsState()
    // 头像查看/修改/历史整条链路（任意页面可复用）。
    val avatar = rememberAvatarController()
    var info by remember { mutableStateOf<String?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(authStore.savedAccounts()) }

    /** 退出登录：只清当前会话，保留已保存账号与本地缓存，便于一键切回（不注销服务端令牌）。 */
    fun doLogout(message: String?) {
        com.rtcomm.app.push.PushRegistrar.unregister()
        authStore.clearSession(); AppState.reset(); WsClient.stop()
        RealtimeService.stop(context)
        Notify.cancelAll(context)
        info = message
    }

    /** 删除账号：注销该账号令牌、从列表移除并清理其本地缓存（切号不会清理）。 */
    fun deleteAccount(acc: AuthStore.SavedAccount) {
        val isCurrent = acc.key == authStore.currentAccountKey()
        com.rtcomm.app.data.PrefsCache.clearAccount(context, acc.key)
        authStore.forgetAccount(acc.key)
        scope.launch { withContext(Dispatchers.IO) { runCatching { Api.logout(acc.serverUrl, acc.token) } } }
        if (isCurrent) {
            com.rtcomm.app.push.PushRegistrar.unregister()
            authStore.clearSession(); AppState.reset(); WsClient.stop()
            RealtimeService.stop(context)
            Notify.cancelAll(context)
        }
        accounts = authStore.savedAccounts()
    }

    /** 切换到已保存账号（无需重新输入密码；保留本地缓存，切回秒开）。 */
    fun switchAccount(acc: AuthStore.SavedAccount) {
        WsClient.stop()
        RealtimeService.stop(context)
        AppState.reset()
        val user = authStore.restoreAccount(acc)
        if (user != null) {
            AppState.signIn(user)
            if (authStore.autoConnectWs) WsClient.start()
            if (authStore.notificationsEnabled) {
                RealtimeService.start(context)
                // 换号后把本机 FCM 令牌重新绑定到新账号（服务端以 token 作主键覆盖）。
                com.rtcomm.app.push.PushRegistrar.register()
            }
            info = "已切换到 ${user.displayName}"
        } else {
            info = "该账号需要重新登录"
        }
    }

    /** 添加账号：不注销旧令牌，仅清本地会话并回登录页（旧账号保留可切回）。 */
    fun addAccount() {
        com.rtcomm.app.push.PushRegistrar.unregister()
        authStore.clearSession(); AppState.reset(); WsClient.stop()
        RealtimeService.stop(context)
        Notify.cancelAll(context)
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { AppTopBar("我的") },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            // 资料卡：点卡片进入二级「个人资料」页，点头像打开头像弹层。
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(Corner.large),
                modifier = Modifier
                    .fillMaxWidth()
                    // 与下方 GroupCard 同为 12dp 水平内缩，避免资料卡与分组卡片左右错位。
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(Corner.large))
                    .motionPress(pressedScale = 0.99f)
                    .clickable { onOpenProfileDetail() },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(
                        name = me?.displayName ?: "?",
                        size = 64.dp,
                        isBot = me?.isBot == true,
                        avatarUrl = me?.avatarUrl,
                        onClick = { avatar.openMe() },
                    )
                    Spacer(Modifier.width(Space.lg))
                    Column(Modifier.weight(1f)) {
                        Text(me?.displayName ?: "-", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("@" + (me?.username ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        me?.bio?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Reveal(info != null) {
                info?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(Space.sm))
                }
            }

            SectionTitle("账户")
            GroupCard {
                NavRow(
                    Icons.Filled.SwapHoriz,
                    "切换账号",
                    if (accounts.size > 1) "已保存 ${accounts.size} 个账号" else "登录多个账号，一键切换",
                ) {
                    accounts = authStore.savedAccounts()
                    showAccounts = true
                }
                NavRow(Icons.Filled.Call, "通话记录", null) { onOpenCalls() }
            }

            SectionTitle("通用")
            GroupCard {
                NavRow(Icons.Filled.Settings, "设置", "外观、通知、聊天与个性化") { onOpenSettings() }
            }

            SectionTitle("关于")
            GroupCard {
                NavRow(Icons.Filled.Info, "关于 rtcomm", "版本 ${com.rtcomm.app.BuildConfig.VERSION_NAME}") { onOpenAbout() }
            }

            if (me?.isAdmin == true) {
                SectionTitle("管理")
                GroupCard {
                    NavRow(Icons.Filled.AdminPanelSettings, "管理后台", "邀请码、全部用户与机器人") { onOpenAdmin() }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { doLogout("已退出登录（账号已保留，可随时切回）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null)
                Spacer(Modifier.width(Space.sm))
                Text("退出登录")
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (showAccounts) {
        AccountSwitcherSheet(
            accounts = accounts,
            currentKey = authStore.currentAccountKey(),
            onDismiss = { showAccounts = false },
            onSwitch = { acc -> showAccounts = false; switchAccount(acc) },
            onAdd = { showAccounts = false; addAccount() },
            onRemove = { acc -> deleteAccount(acc) },
        )
    }
}
