package com.rtcomm.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * 全局 REST 客户端（OkHttp 4.12）。
 *
 * - 统一注入 Authorization: Bearer <token>
 * - 统一解析后端 { error: { code, message } } 错误体，抛出中文 ApiException
 * - HTTP 401 触发 onUnauthorized（清理登录态并回到登录页）
 *
 * 所有方法均为阻塞调用，调用方须在 IO 协程中执行。
 */
object Api {
    /** 默认服务器地址：公开源码不内置任何生产地址，首次启动需在登录页填写。 */
    const val DEFAULT_BASE_URL = ""

    @Volatile var baseUrl: String = DEFAULT_BASE_URL
    @Volatile var token: String = ""

    /** 收到 401 时的回调（由 App 层注册：清 token + 跳登录）。 */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    /**
     * 认证代际：登录、登出、切换服务器时递增。
     * 每个请求在发起时快照当前代际，只有 401 到达时仍处于同一代际才触发
     * [onUnauthorized]，避免旧账号/旧服务器的迟到响应把新登录态清掉。
     */
    @Volatile private var authGeneration: Long = 0L

    @Synchronized
    fun bumpAuthGeneration(): Long {
        authGeneration += 1
        return authGeneration
    }

    @Synchronized
    fun currentAuthGeneration(): Long = authGeneration

    /** 允许作为更新下载源的外部主机白名单（GitHub Release CDN）。 */
    private val ALLOWED_EXTERNAL_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /**
     * 把相对路径或绝对地址统一解析为完整 URL。
     * 安全：绝对 URL 仅允许与当前服务器同源，或更新下载白名单主机（GitHub）；
     * 其余一律回退到本站下载接口，避免被服务端/中间人诱导访问任意外部主机（SSRF 型）。
     */
    fun resolveUrl(pathOrUrl: String): String {
        val v = pathOrUrl.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            return url(if (v.startsWith("/")) v else "/$v")
        }
        return try {
            val u = java.net.URI(v)
            val host = u.host?.lowercase() ?: return url("/api/app/download")
            val baseHost = runCatching { java.net.URI(baseUrl).host?.lowercase() }.getOrNull()
            if (u.scheme == "https" && (host == baseHost || host in ALLOWED_EXTERNAL_HOSTS)) v
            else url("/api/app/download")
        } catch (_: Exception) {
            url("/api/app/download")
        }
    }

    /**
     * 校验并规范化服务器地址：仅接受无 userinfo/query/fragment 的 HTTPS origin。
     * 返回 null 表示输入不合法。
     */
    fun normalizeServerUrl(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val parsed = runCatching { v.toHttpUrlOrNull() }.getOrNull() ?: return null
        if (!parsed.isHttps) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.query != null || parsed.fragment != null) return null
        val path = parsed.encodedPath.trimEnd('/')
        return buildString {
            append("https://").append(parsed.host)
            if (parsed.port != 443) append(':').append(parsed.port)
            append(path)
        }
    }

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 建连尽快失败，读取/上传仍留足时间给大文件与弱网。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 流式（SSE）专用客户端：读取不设超时，避免长回复被 callTimeout 掐断。 */
    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    /** 面向 UI 的网络错误文案，避免直接泄漏底层英文异常。 */
    fun userMessage(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "请求失败"
        is java.net.UnknownHostException -> "无法连接服务器，请检查网络或服务器地址"
        is java.net.SocketTimeoutException,
        is java.io.InterruptedIOException -> "请求超时，请检查网络后重试"
        is java.net.ConnectException -> "无法连接服务器，请稍后重试"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "网络异常，请稍后重试"
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun builder(path: String, auth: Boolean): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (auth && token.isNotEmpty()) b.addHeader("Authorization", "Bearer $token")
        return b
    }

    private fun jsonBody(body: Any?) = gson.toJson(body ?: emptyMap<String, Any?>()).toRequestBody(JSON)

    /**
     * 执行请求，成功返回响应体字符串；失败抛出 ApiException（并在 401 且认证代际未变时回调）。
     * @param generation 请求发起时的认证代际快照
     */
    private fun exec(req: Request, generation: Long): String {
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string()
            if (resp.isSuccessful) return txt ?: ""
            if (resp.code == 401 && generation == currentAuthGeneration()) {
                try { onUnauthorized?.invoke() } catch (_: Exception) {}
            }
            val msg = parseError(txt) ?: "请求失败 (${resp.code})"
            throw ApiException(resp.code, msg)
        }
    }

    /**
     * 供自带 OkHttpClient 的路径（分片上传 / 流式下载）复用：
     * 401 且认证代际未变时触发统一登出回调，避免这些路径绕过 [exec] 的 401 处理。
     */
    internal fun notifyHttpUnauthorized(code: Int, generation: Long) {
        if (code == 401 && generation == currentAuthGeneration()) {
            try { onUnauthorized?.invoke() } catch (_: Exception) {}
        }
    }

    private fun parseError(txt: String?): String? {
        if (txt.isNullOrBlank()) return null
        return try { gson.fromJson(txt, ErrBody::class.java)?.error?.message } catch (_: Exception) { null }
    }

    private inline fun <reified T> parse(txt: String): T = gson.fromJson(txt, T::class.java)

    private fun get(path: String, auth: Boolean = true): String {
        val gen = currentAuthGeneration()
        return exec(builder(path, auth).get().build(), gen)
    }

    private fun post(path: String, body: Any? = null, auth: Boolean = true): String {
        val gen = currentAuthGeneration()
        return exec(builder(path, auth).post(jsonBody(body)).build(), gen)
    }

    private fun patch(path: String, body: Any? = null, auth: Boolean = true): String {
        val gen = currentAuthGeneration()
        return exec(builder(path, auth).patch(jsonBody(body)).build(), gen)
    }

    private fun put(path: String, body: Any? = null, auth: Boolean = true): String {
        val gen = currentAuthGeneration()
        return exec(builder(path, auth).put(jsonBody(body)).build(), gen)
    }

    private fun delete(path: String, auth: Boolean = true): String {
        val gen = currentAuthGeneration()
        return exec(builder(path, auth).delete().build(), gen)
    }

    // ==================== 认证 ====================

    fun login(username: String, password: String): AuthLoginResp =
        parse(post("/api/auth/login", AuthLoginReq(username, password), auth = false))

    fun register(
        username: String,
        password: String,
        nickname: String?,
        inviteCode: String?,
    ): AuthLoginResp =
        parse(post("/api/auth/register", RegisterReq(username, password, nickname, inviteCode), auth = false))

    /** 注册前校验邀请码是否可用（无需登录），用于即时提示。 */
    fun checkInvite(code: String): InviteCheckResp =
        parse(post("/api/invites/check", mapOf("code" to code), auth = false))

    fun me(): PublicUser? = parse<UserWrap>(get("/api/auth/me")).user

    /**
     * 注销令牌。默认使用当前全局会话；登出/切换服务器场景应显式传入快照，
     * 保证请求发往旧服务器、携带旧令牌，且不受后续全局状态变化影响。
     */
    fun logout(baseUrl: String? = null, bearer: String? = null) {
        runCatching {
            val target = (baseUrl ?: this.baseUrl).trimEnd('/')
            val tok = bearer ?: token
            val req = Request.Builder()
                .url("$target/api/auth/logout")
                .apply { if (tok.isNotEmpty()) addHeader("Authorization", "Bearer $tok") }
                .post(jsonBody(null))
                .build()
            client.newCall(req).execute().close()
        }
    }

    fun updateProfile(req: UpdateProfileReq): PublicUser? =
        parse<UserWrap>(put("/api/users/me", req)).user

    // ==================== 用户 ====================

    fun searchUsers(q: String, limit: Int = 20): List<PublicUser> =
        parse<UsersWrap>(get("/api/users/search?q=${enc(q)}&limit=$limit")).users

    fun userById(id: String): PublicUser? = parse<UserWrap>(get("/api/users/$id")).user

    // ==================== 会话 ====================

    fun conversations(): List<Conversation> =
        parse<ConversationsWrap>(get("/api/conversations")).conversations

    fun conversation(id: String): Conversation? =
        parse<ConversationWrap>(get("/api/conversations/$id")).conversation

    fun createConversation(type: String, name: String?, memberIds: List<String>): Conversation? =
        parse<ConversationWrap>(post("/api/conversations", CreateConversationReq(type, name, memberIds))).conversation

    /**
     * 修改群信息（群名 / 群头像，仅群主或群内管理员）。
     * [name] 传 null 表示不改；[avatarUrl] 传 null 表示不改，传 "" 表示清除群头像。
     */
    fun updateConversation(conversationId: String, name: String? = null, avatarUrl: String? = null): Conversation? {
        val body = HashMap<String, Any?>()
        if (name != null) body["name"] = name
        if (avatarUrl != null) body["avatarUrl"] = avatarUrl
        return parse<ConversationWrap>(patch("/api/conversations/$conversationId", body)).conversation
    }

    /** 游标分页拉历史；before/beforeId 为 null 时取最新一页。 */
    fun messages(
        conversationId: String,
        before: String? = null,
        beforeId: String? = null,
        limit: Int = 50,
    ): MessagePage {
        val cursor = if (before != null && beforeId != null) {
            "&before=${urlEncode(before)}&beforeId=${urlEncode(beforeId)}"
        } else {
            ""
        }
        return parse(get("/api/conversations/$conversationId/messages?limit=$limit$cursor"))
    }

    /** 搜索消息（仅限当前用户所属会话）；conversationId 可选，用于限定单个会话。 */
    fun searchMessages(q: String, conversationId: String? = null, limit: Int = 30): MessageSearchResp {
        val scoped = if (conversationId != null) "&conversationId=${urlEncode(conversationId)}" else ""
        return parse(get("/api/messages/search?q=${urlEncode(q)}&limit=$limit$scoped"))
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    fun sendMessage(
        conversationId: String,
        content: String,
        messageType: String = "text",
        fileId: String? = null,
        replyToMessageId: String? = null,
        clientMessageId: String? = null,
    ): Message? = parse<MessageWrap>(
        post(
            "/api/conversations/$conversationId/messages",
            SendMessageReq(messageType, content, fileId, replyToMessageId, clientMessageId),
        ),
    ).message

    fun addMembers(conversationId: String, memberIds: List<String>) {
        post("/api/conversations/$conversationId/members", AddMembersReq(memberIds))
    }

    fun removeMember(conversationId: String, userId: String) {
        delete("/api/conversations/$conversationId/members/$userId")
    }

    fun leaveConversation(conversationId: String) {
        post("/api/conversations/$conversationId/leave")
    }

    // ==================== 消息（撤回 / 已读 REST 兜底） ====================

    /** 撤回消息（发送者本人 / 群主 / 管理员）。 */
    fun deleteMessage(messageId: String) {
        delete("/api/messages/$messageId")
    }

    /** 以该消息为界标记所在会话已读（WS 断开时的 REST 兜底）。 */
    fun markMessageRead(messageId: String) {
        put("/api/messages/$messageId/read")
    }

    /** 编辑自己的文本 / Markdown 消息；返回更新后的消息。 */
    fun editMessage(messageId: String, content: String): Message? =
        parse<MessageWrap>(patch("/api/messages/$messageId", EditMessageReq(content))).message

    /** 一键把本人所有会话标记为已读。 */
    fun markAllRead(): ReadAllResp = parse(post("/api/messages/read-all"))

    // ==================== 会话总结 ====================

    fun summarize(conversationId: String): Summary? =
        parse<SummaryWrap>(post("/api/conversations/$conversationId/summarize")).summary

    fun summaries(conversationId: String): List<Summary> =
        parse<SummariesWrap>(get("/api/conversations/$conversationId/summaries")).summaries

    // ==================== AI ====================

    fun bots(): List<BotSummary> = parse<BotsWrap>(get("/api/ai/bots")).bots

    fun botDetail(botId: String): BotDetail? = parse<BotDetailWrap>(get("/api/ai/bots/$botId")).bot

    fun createBot(req: CreateBotReq): BotDetail? =
        parse<BotDetailWrap>(post("/api/ai/bots", req)).bot

    /** AI 提供者预设（含默认模型；不含任何密钥）。 */
    fun providers(): List<ProviderPreset> =
        parse<ProvidersWrap>(get("/api/ai/providers")).providers
            .map { (id, preset) -> preset.copy(id = id) }
            .sortedBy { it.label }

    /** 读取全局 AI 提供者默认设置（仅管理员；apiKey 不回显）。 */
    fun providersSettings(): ProvidersSettingsResp =
        parse(get("/api/ai/providers-settings"))

    /** 保存全局 AI 提供者默认设置（仅管理员）。body: { <provider>: { apiKey?, baseUrl?, model? } } */
    fun updateProvidersSettings(body: Map<String, Any?>): ProvidersSettingsResp =
        parse(put("/api/ai/providers-settings", body))

    /** 更新机器人配置（仅管理员或所有者；未设置的字段不会被修改）。 */
    fun updateBotConfig(botId: String, req: UpdateBotConfigReq): BotDetail? =
        parse<BotDetailWrap>(put("/api/ai/bots/$botId/config", req)).bot

    /** 设置机器人可见性（仅管理员或所有者）：private / public / shared。 */
    fun updateBotVisibility(botId: String, visibility: String, userIds: List<String> = emptyList()): BotDetail? =
        parse<BotDetailWrap>(put("/api/ai/bots/$botId/visibility", UpdateBotVisibilityReq(visibility, userIds))).bot

    fun chatWithBot(botId: String, message: String, image: String? = null, thinking: Boolean = false): ChatBotResp {
        val (t, tz) = clientTimeFields()
        return parse(post("/api/ai/$botId/chat", ChatBotReq(message, image, t, tz, thinking)))
    }

    /** 客户端本地时间（ISO）与时区；服务端据此注入系统提示词的「当前时间」。 */
    private fun clientTimeFields(): Pair<String, String> {
        val now = java.time.ZonedDateTime.now()
        val tz = java.time.ZoneId.systemDefault().id
        return (now.toInstant().toString() to tz)
    }

    /** 清空当前用户与该机器人的上下文记忆与历史（对应 `/reset`）。返回删除条数。 */
    fun resetAiContext(botId: String): Int =
        parse<ResetAiResp>(post("/api/ai/$botId/reset")).cleared

    /** 手动压缩上下文：把旧消息摘要化（对应 `/compact`）。 */
    fun compactAiContext(botId: String): CompactAiResp =
        parse(post("/api/ai/$botId/compact"))

    /** 读取上下文压缩状态（是否已摘要、当前条数与自动压缩阈值）。 */
    fun aiContextInfo(botId: String): AiContextInfo =
        parse(get("/api/ai/$botId/context"))

    /** 当前用户 AI 额度总览（每日 token / 总结次数 / 兑换余额）。 */
    fun aiQuota(): AiQuota = parse(get("/api/ai/quota"))

    /** 使用兑换码增加额度，返回最新额度。 */
    fun redeemCode(code: String): AiQuota? =
        parse<RedeemResp>(post("/api/ai/redeem", mapOf("code" to code))).quota

    /** 管理员：发放兑换码。 */
    fun createRedeemCodes(tokens: Int, count: Int, expiresAt: String?, note: String?): CreateRedeemCodesResp =
        parse(post("/api/ai/redeem-codes", mapOf("tokens" to tokens, "count" to count, "expiresAt" to expiresAt, "note" to note)))

    /** 管理员：兑换码列表。 */
    fun redeemCodes(): List<RedeemCode> = parse<RedeemCodesResp>(get("/api/ai/redeem-codes")).codes

    /** 管理员：作废未使用的兑换码。 */
    fun revokeRedeemCode(code: String) { delete("/api/ai/redeem-codes/${enc(code)}") }

    /** 上传自定义头像（base64 dataUrl），返回资料与头像历史。 */
    fun uploadAvatar(dataUrl: String): AvatarUpsertResp =
        parse(put("/api/users/me/avatar", mapOf("dataUrl" to dataUrl)))

    /** 头像历史列表。 */
    fun avatars(): List<AvatarItem> = parse<AvatarsResp>(get("/api/users/me/avatars")).avatars

    /** 从历史中选为当前头像。 */
    fun selectAvatar(avatarId: String): AvatarUpsertResp =
        parse(put("/api/users/me/avatars/${enc(avatarId)}"))

    /** 删除一张历史头像。 */
    fun deleteAvatar(avatarId: String): AvatarUpsertResp =
        parse(delete("/api/users/me/avatars/${enc(avatarId)}"))

    /** 清除当前头像（保留历史）。 */
    fun clearAvatar(): AvatarUpsertResp = parse(delete("/api/users/me/avatar"))

    /**
     * SSE 流式直聊：逐段发出增量文本。
     * 阻塞式网络读取运行在 [Dispatchers.IO]；调用方在主线程 collect 并更新 UI。
     * 服务端不支持流式（404/405）或中途出错时抛出异常，调用方可回退到 [chatWithBot]。
     */
    fun chatWithBotStream(botId: String, message: String, image: String? = null, thinking: Boolean = false): Flow<AiStreamChunk> = flow {
        val gen = currentAuthGeneration()
        val (t, tz) = clientTimeFields()
        val req = Request.Builder()
            .url(url("/api/ai/$botId/chat/stream"))
            .header("Accept", "text/event-stream")
            .apply { if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token") }
            .post(jsonBody(mapOf("message" to message, "image" to image, "time" to t, "timezone" to tz, "thinking" to thinking)))
            .build()
        val call = streamClient.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (resp.code == 401 && gen == currentAuthGeneration()) {
                        try { onUnauthorized?.invoke() } catch (_: Exception) {}
                    }
                    throw ApiException(resp.code, parseError(body) ?: "流式请求失败 (${resp.code})")
                }
                val source = resp.body?.source() ?: throw ApiException(500, "流式响应为空")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    val obj = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: continue
                    val errEl = obj.get("error")
                    if (errEl != null && errEl.isJsonPrimitive) throw ApiException(500, errEl.asString)
                    val d = obj.get("delta")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
                    val r = obj.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
                    if (d != null || r != null) emit(AiStreamChunk(delta = d, reasoning = r))
                }
            }
        } finally {
            // 页面退出/协程取消时，主动断开阻塞中的流式请求。
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    fun aiChatLogs(botUserId: String, humanUserId: String? = null, page: Int = 1, limit: Int = 50): AiChatLogsPage {
        val human = if (humanUserId != null) "&humanUserId=${enc(humanUserId)}" else ""
        return parse(get("/api/ai/chatlogs?botUserId=${enc(botUserId)}$human&page=$page&limit=$limit"))
    }

    // ==================== 文件 ====================

    fun filesInit(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        fileHash: String,
        conversationId: String?,
    ): FileInitResp {
        val body = mutableMapOf<String, Any?>(
            "fileName" to fileName,
            "fileSize" to fileSize,
            "mimeType" to mimeType,
            "fileHash" to fileHash,
        )
        if (!conversationId.isNullOrBlank()) body["conversationId"] = conversationId
        return parse(post("/api/files/init", body))
    }

    fun uploadStatus(uploadId: String): UploadStatus = parse(get("/api/files/uploads/$uploadId"))

    fun completeUpload(uploadId: String): FileMeta = parse(post("/api/files/$uploadId/complete"))

    fun abortUpload(uploadId: String) { runCatching { delete("/api/files/uploads/$uploadId") } }

    fun fileMeta(id: String): FileMeta? = parse<FileMetaWrap>(get("/api/files/$id")).file

    /** 媒体下载统一由带 Authorization 请求头的客户端获取，避免 JWT 出现在 URL。 */
    fun downloadUrl(fileId: String): String = url("/api/files/$fileId/download")

    /** App 更新包下载地址（公开接口）。 */
    fun appDownloadUrl(): String = url("/api/app/download")

    /** 检查 App 新版本（公开接口，无需登录态，因此不能携带 JWT）。 */
    fun appLatest(): AppUpdateInfo = parse(get("/api/app/latest", auth = false))

    // ==================== 远程设备 ====================

    fun devices(): List<Device> = parse<DevicesWrap>(get("/api/devices")).devices

    fun device(deviceId: String): Device? = parse<DeviceWrap>(get("/api/devices/$deviceId")).device

    // ==================== 邀请码 / 管理后台（仅管理员） ====================

    /** 生成邀请码（管理员）。 */
    fun createInvites(count: Int = 1, note: String? = null): InviteCodesResp =
        parse(post("/api/invites", mapOf("count" to count, "note" to note)))

    /** 邀请码列表（管理员）。 */
    fun listInvites(onlyUnused: Boolean = false): InviteCodesResp =
        parse(get("/api/invites?onlyUnused=$onlyUnused"))

    /** 作废未使用的邀请码（管理员）。 */
    fun revokeInvite(code: String) {
        delete("/api/invites/$code")
    }

    /** 管理概览统计（管理员）。 */
    fun adminStats(): AdminStats =
        parse<AdminStatsResp>(get("/api/admin/stats")).stats

    /** 禁用 / 恢复账号（管理员）。操作后由调用方重新拉取列表。 */
    fun adminSetUserDisabled(userId: String, disabled: Boolean) {
        post("/api/admin/users/$userId/" + if (disabled) "disable" else "enable")
    }

    /** 重置账号密码（管理员）。 */
    fun adminResetPassword(userId: String, password: String) {
        post("/api/admin/users/$userId/reset-password", mapOf("password" to password))
    }

    /** 踢下线：递增 auth_epoch，旧令牌立即失效（管理员）。 */
    fun adminKickUser(userId: String) {
        post("/api/admin/users/$userId/kick")
    }

    /** 全部会话概览（管理员）。 */
    fun adminConversations(limit: Int = 100): List<AdminConversation> =
        parse<AdminConversationsResp>(get("/api/admin/conversations?limit=$limit")).conversations

    /** 任意会话的消息（管理员）。 */
    fun adminConversationMessages(conversationId: String, limit: Int = 200): List<Message> =
        parse<AdminMessagesResp>(get("/api/admin/conversations/$conversationId/messages?limit=$limit")).messages

    /** 全部注册账号（管理员）：type=human|bot|all。 */
    fun adminUsers(type: String = "all", q: String? = null, limit: Int = 100): List<AdminUser> {
        val scoped = if (q.isNullOrBlank()) "" else "&q=${urlEncode(q)}"
        return parse<AdminUsersResp>(get("/api/admin/users?type=$type&limit=$limit$scoped")).users
    }

    fun registerDevice(deviceName: String, agentVersion: String?): Device =
        parse(post("/api/devices/register", RegisterDeviceReq(deviceName, agentVersion)))

    fun runCommand(deviceId: String, command: String, timeoutSec: Int?): CommandResult =
        parse(post("/api/devices/$deviceId/command", CommandReq(command, timeoutSec)))

    fun deviceSessions(deviceId: String): List<RemoteSession> =
        parse<RemoteSessionsWrap>(get("/api/devices/$deviceId/sessions")).sessions

    fun sessionCommands(deviceId: String, sessionId: String): List<RemoteCommand> =
        parse<RemoteCommandsWrap>(get("/api/devices/$deviceId/commands?sessionId=${enc(sessionId)}")).commands

    fun closeSession(deviceId: String, sessionId: String) {
        post("/api/devices/$deviceId/sessions/$sessionId/close")
    }

    // ==================== 通话 ====================

    fun createRoom(conversationId: String?): CreateRoomResp =
        parse(post("/api/calls", CreateRoomReq(conversationId)))

    fun callLogs(conversationId: String? = null): List<CallLog> {
        val q = if (conversationId != null) "?conversationId=${enc(conversationId)}" else ""
        return parse<CallLogsWrap>(get("/api/calls$q")).callLogs
    }

    // ==================== 偏好设置 ====================

    fun preferences(): Map<String, Any?> {
        val obj = gson.fromJson(get("/api/preferences"), JsonObject::class.java)
        val prefs = obj?.getAsJsonObject("preferences") ?: return emptyMap()
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(prefs, type)
    }

    fun putPreference(key: String, value: Any?) {
        put("/api/preferences/$key", mapOf("value" to value))
    }

    fun deletePreference(key: String) { delete("/api/preferences/$key") }

    // ==================== 推送（FCM 设备令牌） ====================

    /** 注册 / 刷新本设备 FCM 令牌（登录态下调用，使用当前会话鉴权）。 */
    fun registerPushToken(fcmToken: String, platform: String = "android") {
        post("/api/push/token", mapOf("token" to fcmToken, "platform" to platform))
    }

    /**
     * 注销设备令牌。登出 / 换号 / 换服务器场景须显式传入旧服务器地址与旧令牌快照，
     * 保证 DELETE 发往正确的旧账号，且不受全局会话被清空的影响。
     */
    fun unregisterPushToken(baseUrl: String, bearer: String, fcmToken: String) {
        val target = baseUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$target/api/push/token")
            .apply { if (bearer.isNotEmpty()) addHeader("Authorization", "Bearer $bearer") }
            .delete(jsonBody(mapOf("token" to fcmToken)))
            .build()
        client.newCall(req).execute().close()
    }

    // ==================== 工具 ====================

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
