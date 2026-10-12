package com.rtcomm.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FCM 设备令牌注册器。
 *
 * - [register]：登录后调用。取当前 FCM 令牌并 POST /api/push/token（服务端以 token
 *   作主键，同一设备换登录用户时归属随之更新，旧用户停止收到本机推送）。
 * - [unregister]：显式登出 / 换号 / 换服务器时，务必在清空会话（clearSession/reset）
 *   **之前**调用；此处同步快照当前服务器地址与令牌，之后即使全局状态被清空，
 *   注销请求仍会正确发往旧服务器并携带旧令牌。
 * - [onNewToken]：FCM 主动轮换令牌时（本地存在会话）自动重注册。
 *
 * 未配置 Firebase（[FirebaseInit.isEnabled]=false）时全部为 no-op。
 */
object PushRegistrar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次成功获取到的 FCM 令牌；登出时无需再异步获取即可直接注销。 */
    @Volatile private var lastFcmToken: String? = null

    /** 登录态下注册（或刷新）本设备的推送令牌。可在主线程调用。 */
    fun register() {
        if (!FirebaseInit.isEnabled) return
        if (Api.token.isEmpty()) return
        fetchToken { token ->
            lastFcmToken = token
            scope.launch { retry("推送令牌注册") { Api.registerPushToken(token) } }
        }
    }

    /** FCM 主动轮换令牌：本地有会话时重注册（可能运行在被拉起的全新进程）。 */
    fun onNewToken(token: String) {
        lastFcmToken = token
        if (!FirebaseInit.isEnabled) return
        if (Api.token.isEmpty()) return
        scope.launch { retry("推送令牌注册") { Api.registerPushToken(token) } }
    }

    /**
     * 退避重试 + 日志 + 诊断上报。
     * 旧实现全部 `runCatching` 静默吞掉，注册失败时用户端收不到推送却毫无线索。
     */
    private suspend fun retry(area: String, attempts: Int = 4, block: suspend () -> Unit) {
        var delayMs = 1000L
        var last: Throwable? = null
        for (attempt in 1..attempts) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                Log.w("PushRegistrar", "$area 失败（第 $attempt/$attempts 次）", e)
                if (attempt < attempts) {
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
        FirebaseInit.appContext?.let { AppDiagnostics.record(it, area, last) }
    }

    /**
     * 注销本设备令牌。必须在清空会话之前调用：同步读取 [Api.baseUrl]/[Api.token]
     * 快照后再异步发起 DELETE，保证请求发往正确的旧账号。
     */
    fun unregister() {
        if (!FirebaseInit.isEnabled) return
        val baseUrl = Api.baseUrl
        val bearer = Api.token
        if (bearer.isEmpty()) return
        val known = lastFcmToken
        if (known != null) {
            lastFcmToken = null
            scope.launch { retry("推送令牌注销", attempts = 3) { Api.unregisterPushToken(baseUrl, bearer, known) } }
            return
        }
        // 本进程还没拿到过令牌：异步取一次再注销（快照已捕获，不受后续清空影响）。
        fetchToken { token ->
            scope.launch { retry("推送令牌注销", attempts = 3) { Api.unregisterPushToken(baseUrl, bearer, token) } }
        }
    }

    private fun fetchToken(onToken: (String) -> Unit) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = task.result
                if (task.isSuccessful && !token.isNullOrEmpty()) {
                    onToken(token)
                } else {
                    Log.w("PushRegistrar", "获取 FCM 令牌失败", task.exception)
                }
            }
        }.onFailure { Log.w("PushRegistrar", "FirebaseMessaging 不可用", it) }
    }
}
