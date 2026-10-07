package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Space

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.AiQuota
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.FadeSwap
import com.rtcomm.app.ui.common.Reveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的」资料卡点进来的二级页面：账户资料（昵称 / 签名 / 用户名 / 密码）+ AI 额度。
 *
 * 原先这里还有一张与「我的」顶部重复的资料卡（大头像 + 点击更换头像），已移除：
 * 只保留一张卡片（在「我的」页），头像修改在卡片上完成，本页专注资料编辑与额度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(authStore: AuthStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by AppState.currentUser.collectAsState()
    var showEditName by remember { mutableStateOf(false) }
    var showBio by remember { mutableStateOf(false) }
    var showChangePwd by remember { mutableStateOf(false) }
    var showRedeem by remember { mutableStateOf(false) }
    var quota by remember { mutableStateOf<AiQuota?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { Api.aiQuota() } }
        r.onSuccess { quota = it }
    }

    Scaffold(
        topBar = { AppTopBar("个人资料", onBack = onBack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Reveal(info != null) {
                info?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            SectionTitle("账户")
            GroupCard {
                NavRow(Icons.Filled.Edit, "昵称", me?.nickname ?: me?.displayName) { showEditName = true }
                NavRow(Icons.Filled.Edit, "个性签名", me?.bio?.takeIf { it.isNotBlank() } ?: "点击填写") { showBio = true }
                NavRow(Icons.Filled.Person, "用户名", "@" + (me?.username ?: "")) {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("username", me?.username ?: ""))
                    info = "用户名已复制"
                }
                NavRow(Icons.Filled.Lock, "修改密码") { showChangePwd = true }
            }

            SectionTitle("AI 额度")
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FadeSwap(target = when {
                        quota == null -> 0
                        quota!!.unlimited -> 1
                        else -> 2
                    }) { st ->
                        when (st) {
                            0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "加载中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            1 -> Text(
                                "管理员账号不受额度限制",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> {
                                val q = quota!!
                                Column {
                                    Text(
                                        "剩余 tokens：${q.totalRemaining}（基础 ${q.baseRemaining} + 兑换 ${q.bonusRemaining}）/ 每日 ${q.dailyTokens}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "今日总结：剩余 ${q.summariesRemaining}/${q.dailySummaries} 次",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                NavRow(Icons.Filled.Redeem, "兑换码", "使用兑换码增加 AI 额度") { showRedeem = true }
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showEditName) {
        EditNicknameDialog(
            current = me?.nickname ?: "",
            onDismiss = { showEditName = false },
            onSaved = { u -> showEditName = false; authStore.saveUser(u); AppState.signIn(u) },
        )
    }
    if (showBio) {
        EditBioDialog(
            current = me?.bio ?: "",
            onDismiss = { showBio = false },
            onSaved = { u -> showBio = false; authStore.saveUser(u); AppState.signIn(u) },
        )
    }
    if (showChangePwd) {
        ChangePasswordDialog(
            onDismiss = { showChangePwd = false },
            onChanged = {
                showChangePwd = false
                val snapshot = authStore.snapshot()
                com.rtcomm.app.data.PrefsCache.clearAccount(context, authStore.currentAccountKey())
                authStore.forgetAccount(authStore.currentAccountKey())
                com.rtcomm.app.push.PushRegistrar.unregister()
                authStore.clearSession(); AppState.reset()
                com.rtcomm.app.ws.WsClient.stop()
                com.rtcomm.app.notify.RealtimeService.stop(context)
                com.rtcomm.app.notify.Notify.cancelAll(context)
                scope.launch { withContext(Dispatchers.IO) { runCatching { Api.logout(snapshot.baseUrl, snapshot.token) } } }
            },
        )
    }
    if (showRedeem) {
        RedeemDialog(
            onDismiss = { showRedeem = false },
            onRedeemed = { newQuota -> quota = newQuota; showRedeem = false; info = "兑换成功，额度已增加" },
        )
    }
}
