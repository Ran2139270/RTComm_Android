package com.rtcomm.app.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/** 自定义表情消息的内容标记：图片消息带此标记即视为表情（区别于普通图片）。 */
const val STICKER_CONTENT = "[表情]"

/** 合并转发内容：作为 messageType="forward" 的 content（JSON）。 */
data class ForwardContent(val title: String = "", val items: List<ForwardItem> = emptyList())

/** 合并转发中的单条记录（仅文本摘要，不含原文件）。 */
data class ForwardItem(val sender: String = "", val type: String = "text", val text: String = "", val time: String? = null)

/** 解析 forward 消息内容；非法或为空返回 null。 */
fun parseForward(content: String?): ForwardContent? = runCatching {
    com.google.gson.Gson().fromJson(content, ForwardContent::class.java)
}.getOrNull()?.takeIf { !it.items.isNullOrEmpty() }

// ============================================================
// 用户与认证
// ============================================================

@Immutable
data class PublicUser(
    val id: String = "",
    val username: String = "",
    val nickname: String? = null,
    val accountType: String? = null, // human / bot
    val avatarUrl: String? = null,
    /** 个性签名。 */
    val bio: String? = null,
    val isAdmin: Boolean = false,
    /** 被管理员禁用；禁用后无法登录，令牌也会立即失效。 */
    val isDisabled: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
    val createdAt: String? = null,
) {
    val isBot: Boolean get() = accountType == "bot"
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

data class AuthLoginReq(val username: String, val password: String)

data class AuthLoginResp(
    val user: PublicUser? = null,
    val token: String? = null,
    val tokenType: String? = null,
    val expiresIn: String? = null,
)

data class RegisterReq(
    val username: String,
    val password: String,
    val nickname: String? = null,
    /** 邀请制注册：普通账号必填，由管理员生成，一码一用。 */
    val inviteCode: String? = null,
)

/** PUT /api/users/me —— 仅序列化非空字段（Gson 默认忽略 null）。 */
data class UpdateProfileReq(
    val nickname: String? = null,
    val avatarUrl: String? = null,
    /** 个性签名（空串表示清空）。 */
    val bio: String? = null,
    val currentPassword: String? = null,
    val password: String? = null,
)

data class UserWrap(val user: PublicUser? = null)
data class UsersWrap(val users: List<PublicUser> = emptyList())

// ============================================================
// 邀请码与管理后台（仅管理员可用）
// ============================================================

data class InviteCode(
    val code: String = "",
    val note: String? = null,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val usedBy: String? = null,
    val usedByName: String? = null,
    val usedAt: String? = null,
    val used: Boolean = false,
)

data class InviteStats(
    val total: Int = 0,
    val used: Int = 0,
    val unused: Int = 0,
)

data class InviteCodesResp(
    val codes: List<InviteCode> = emptyList(),
    val stats: InviteStats = InviteStats(),
)

/** 邀请码可用性检查（无需登录）。 */
data class InviteCheckResp(
    val valid: Boolean = false,
    val reason: String? = null,
)

/** 管理端账号条目：在公开字段之上补充只有管理员能看到的统计。 */
data class AdminUser(
    val id: String = "",
    val username: String = "",
    val nickname: String? = null,
    val accountType: String? = null,
    val avatarUrl: String? = null,
    val isAdmin: Boolean = false,
    val isDisabled: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
    val createdAt: String? = null,
    val conversationCount: Int = 0,
) {
    val isBot: Boolean get() = accountType == "bot"
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

data class AdminUsersResp(val users: List<AdminUser> = emptyList())

data class AdminStats(
    val users: Int = 0,
    val bots: Int = 0,
    val admins: Int = 0,
    val online: Int = 0,
    val conversations: Int = 0,
    val messages: Int = 0,
    val files: Int = 0,
    val devices: Int = 0,
    val inviteTotal: Int = 0,
    val inviteUnused: Int = 0,
)

data class AdminStatsResp(val stats: AdminStats = AdminStats())

/** 管理端会话条目（含成员概览）。 */
data class AdminConversation(
    val id: String = "",
    val type: String = "direct",
    val name: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val messageCount: Int = 0,
    val memberCount: Int = 0,
    val lastMessageAt: String? = null,
    val members: List<AdminUser> = emptyList(),
) {
    val title: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: members.map { it.displayName }.take(3).joinToString("、").ifBlank { "会话" }
    val isGroup: Boolean get() = type == "group"
}

data class AdminConversationsResp(val conversations: List<AdminConversation> = emptyList())
data class AdminMessagesResp(val messages: List<Message> = emptyList())
data class EditMessageReq(val content: String)
data class ReadAllResp(val conversations: Int = 0)

// ============================================================
// 会话与消息
// ============================================================

@Immutable
data class MessageFile(
    val id: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val url: String? = null,
    val createdAt: String? = null,
)

/** 回复原文的轻量快照，由消息接口一并返回。 */
@Immutable
data class ReplyMessage(
    val id: String = "",
    val senderId: String = "",
    val sender: PublicUser? = null,
    val messageType: String = "text",
    val content: String? = null,
    val file: MessageFile? = null,
    val isDeleted: Boolean = false,
)

@Immutable
data class Message(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val sender: PublicUser? = null,
    val messageType: String = "text", // text / markdown / image / video / audio / file
    val content: String? = null,
    val fileId: String? = null,
    val file: MessageFile? = null,
    val replyToMessageId: String? = null,
    /** 服务端随回复消息附带的原文简要信息；不再额外请求，离线缓存同样可展示。 */
    val replyTo: ReplyMessage? = null,
    val isDeleted: Boolean = false,
    /** 编辑时间；非空表示这条消息被编辑过（前端显示「已编辑」）。 */
    val editedAt: String? = null,
    val createdAt: String? = null,
    /** 本地发送状态（不来自服务端，仅客户端 UI 用；Gson 序列化时忽略）。
     *  null = 服务端已确认（网络消息反序列化即 null）；Sending/Failed = 本地乐观状态。 */
    @Transient val sendState: SendState? = null,
    /** 上传中的本地图片 URI，用于在服务器确认前显示即时缩略图；随消息缓存持久化以支持重启后续传。 */
    val localPreviewUri: String? = null,
    /** 本地待上传的缓存文件路径；上传失败时保留以便重试。 */
    val localUploadPath: String? = null,
    /** 上传进度 0..1（仅本地待发媒体）。 */
    val uploadProgress: Float = 0f,
    /** 上传总字节数（用于展示进度）。 */
    val uploadTotalBytes: Long = 0L,
) {
    val isFileType: Boolean
        get() = messageType == "file" || messageType == "image" ||
            messageType == "video" || messageType == "audio"
    /** 自定义表情：图片消息且内容为 [STICKER_CONTENT] 标记。 */
    val isSticker: Boolean
        get() = messageType == "image" && content == STICKER_CONTENT
    /** 合并转发消息。 */
    val isForward: Boolean
        get() = messageType == "forward"
    val isLocalPending: Boolean
        get() = sendState != null
}

/**
 * 本地发送状态：
 * - Sending：正在发送
 * - Queued：发送失败但已排入自动重试队列（网络恢复或退避到点会重发）
 * - Failed：已用尽重试次数，只能手动重发
 * - Sent：服务端已确认
 */
enum class SendState { Sending, Queued, Failed, Sent }

/**
 * 历史消息页。
 * 游标分页：`hasMore` 决定还能不能继续往前翻，`nextCursor` 是下一页的锚点
 * （(createdAt, id) 复合游标，新消息插入不会让旧页错位）。
 * `page`/`total` 仅为兼容旧服务端保留。
 */
data class MessagePage(
    val page: Int = 1,
    val limit: Int = 50,
    val total: Int = 0,
    val hasMore: Boolean = false,
    val nextCursor: MessageCursor? = null,
    val messages: List<Message> = emptyList(),
)

data class MessageCursor(
    val before: String = "",
    val beforeId: String = "",
)

/** 消息搜索结果条目：带上所属会话，便于点击后跳转。 */
data class MessageSearchHit(
    val message: Message? = null,
    val conversationId: String = "",
    val conversationName: String? = null,
    val conversationType: String? = null,
)

data class MessageSearchResp(
    val query: String = "",
    val results: List<MessageSearchHit> = emptyList(),
)

data class MessageWrap(val message: Message? = null)

data class ConversationMember(
    val id: String = "",
    val username: String = "",
    val nickname: String? = null,
    val accountType: String? = null,
    val avatarUrl: String? = null,
    val isAdmin: Boolean = false,
    val isOnline: Boolean = false,
    val role: String? = null,
    val joinedAt: String? = null,
    val lastReadAt: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
    val isBot: Boolean get() = accountType == "bot"
}

data class Conversation(
    val id: String = "",
    val type: String = "direct", // direct / group
    val name: String? = null, // 服务端已对 direct 计算为对方昵称
    val avatarUrl: String? = null, // 群头像（group）；direct 为空
    val createdBy: String? = null,
    val createdAt: String? = null,
    val members: List<ConversationMember> = emptyList(),
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
)

data class ConversationsWrap(val conversations: List<Conversation> = emptyList())
data class ConversationWrap(val conversation: Conversation? = null)

data class CreateConversationReq(
    val type: String,
    val name: String? = null,
    val memberIds: List<String> = emptyList(),
)

data class SendMessageReq(
    val messageType: String = "text",
    val content: String = "",
    val fileId: String? = null,
    val replyToMessageId: String? = null,
    /**
     * 客户端稳定标识（本地乐观消息 id）。重试始终复用同一个值：
     * 当前服务端尚未建立唯一约束时会忽略该字段，但一旦服务端支持即可实现幂等；
     * 在此之前客户端依靠 `new_message` 回显合并来去重。
     */
    val clientMessageId: String? = null,
)

data class AddMembersReq(val memberIds: List<String>)

// ============================================================
// AI 机器人
// ============================================================

data class BotSummary(
    val id: String = "",
    val username: String = "",
    val nickname: String? = null,
    val accountType: String? = "bot",
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val personality: String? = null,
    val replyStyle: String? = null,
    val modelProvider: String? = null,
    val modelName: String? = null,
    val ownerId: String? = null,
    /** 仅所有者/管理员可见：private / public / shared。 */
    val visibility: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

data class BotsWrap(val bots: List<BotSummary> = emptyList())

data class BotConfig(
    val personality: String? = null,
    val replyStyle: String? = null,
    val modelProvider: String? = null,
    val modelName: String? = null,
    val temperature: Double? = null,
    /** 以下字段仅机器人所有者/管理员可见（服务端按权限下发）。 */
    val knowledgeBase: String? = null,
    val memoryEnabled: Boolean? = null,
    /** 非空表示当前查看者有配置权限（服务端只对所有者/管理员下发该字段）。 */
    val hasApiKey: Boolean? = null,
    val systemPrompt: String? = null,
    /** private（仅自己/管理员）/ public（所有人）/ shared（指定用户）。 */
    val visibility: String? = null,
    val sharedUserIds: List<String> = emptyList(),
    /** 当前模型是否支持图片输入（多模态）；服务端计算下发。 */
    val vision: Boolean = false,
)

/** PUT /api/ai/bots/:botId/visibility */
data class UpdateBotVisibilityReq(
    val visibility: String,
    val userIds: List<String> = emptyList(),
)

/** /api/ai/providers 的单个提供者预设（不含任何密钥）；id 由 map key 回填。 */
data class ProviderPreset(
    val id: String = "",
    val label: String = "",
    val protocol: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    /** 该提供者可选模型名，供后台选择。 */
    val models: List<String> = emptyList(),
    val needsApiKey: Boolean = false,
)

data class ProvidersWrap(val providers: Map<String, ProviderPreset> = emptyMap())

/** GET /api/ai/providers-settings 的单个提供者配置（apiKey 只回显是否已配置）。 */
data class ProviderSettings(
    val hasApiKey: Boolean = false,
    val baseUrl: String? = null,
    val model: String? = null,
)

data class ProvidersSettingsResp(
    val settings: Map<String, ProviderSettings> = emptyMap(),
    val presets: Map<String, ProviderPreset> = emptyMap(),
)

/** PUT /api/ai/bots/:botId/config 请求体（null 字段由 Gson 省略）。 */
data class UpdateBotConfigReq(
    val personality: String? = null,
    @SerializedName("reply_style") val replyStyle: String? = null,
    @SerializedName("knowledge_base") val knowledgeBase: String? = null,
    @SerializedName("memory_enabled") val memoryEnabled: Boolean? = null,
    @SerializedName("model_provider") val modelProvider: String? = null,
    @SerializedName("model_name") val modelName: String? = null,
    @SerializedName("api_key") val apiKey: String? = null,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    val temperature: Double? = null,
)

data class BotDetail(
    val id: String = "",
    val username: String = "",
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val config: BotConfig? = null,
)

data class BotDetailWrap(val bot: BotDetail? = null)

data class CreateBotReq(
    val username: String,
    val password: String,
    val nickname: String? = null,
    val personality: String? = null,
    val modelProvider: String? = null,
    val modelName: String? = null,
)

data class ChatBotReq(
    val message: String?,
    /** 可选图片 dataUrl（≤10MB），仅在多模态模型下发送。 */
    val image: String? = null,
    /** 客户端本地时间（ISO）与时区，供服务端注入系统提示词。 */
    val time: String? = null,
    val timezone: String? = null,
    /** 是否请求模型进行“思考 / 推理”。 */
    val thinking: Boolean = false,
)
data class ChatBotResp(
    val reply: String? = null,
    val botId: String? = null,
    val humanUserId: String? = null,
    /** 模型的思考/推理内容（若提供者返回）。 */
    val reasoning: String? = null,
)

/** 流式响应分片：正常增量 [delta] 与推理增量 [reasoning] 二选一。 */
data class AiStreamChunk(val delta: String? = null, val reasoning: String? = null)

/** /reset：清空上下文的返回（删除的日志条数）。 */
data class ResetAiResp(val cleared: Int = 0)

/** /compact：手动压缩上下文的返回。 */
data class CompactAiResp(val summary: String? = null, val compacted: Int = 0, val kept: Int = 0)

/** GET /api/ai/:botId/context — 当前上下文压缩状态。 */
data class AiContextInfo(
    val hasSummary: Boolean = false,
    val summary: String? = null,
    val compactedAt: String? = null,
    val total: Int = 0,
    val keep: Int = 0,
    val autoAfter: Int = 0,
)

/** GET /api/ai/quota — 当前用户 AI 额度总览。 */
data class AiQuota(
    val unlimited: Boolean = false,
    val day: String? = null,
    val resetsAt: String? = null,
    val dailyTokens: Int = 0,
    val tokensUsedToday: Int = 0,
    val baseRemaining: Int = 0,
    val bonusRemaining: Int = 0,
    val totalRemaining: Int = 0,
    val dailySummaries: Int = 0,
    val summariesUsedToday: Int = 0,
    val summariesRemaining: Int = 0,
)

data class RedeemResp(val quota: AiQuota? = null)

/** 管理员兑换码条目。 */
data class RedeemCode(
    val code: String = "",
    val tokens: Int = 0,
    val note: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val usedBy: String? = null,
    val usedAt: String? = null,
)

data class RedeemCodesResp(val codes: List<RedeemCode> = emptyList())

data class CreateRedeemCodesResp(
    val codes: List<String> = emptyList(),
    val tokens: Int = 0,
    val expiresAt: String? = null,
)

/** 头像历史条目。 */
data class AvatarItem(
    val id: String = "",
    val mime: String? = null,
    val animated: Boolean = false,
    val createdAt: String? = null,
    val current: Boolean = false,
)

data class AvatarsResp(val avatars: List<AvatarItem> = emptyList())

data class AvatarUpsertResp(
    val user: PublicUser? = null,
    val avatars: List<AvatarItem> = emptyList(),
    val selectedId: String? = null,
)

data class AiChatLog(
    val id: String = "",
    val humanUserId: String = "",
    val botUserId: String = "",
    val conversationId: String? = null,
    val messageId: String? = null,
    val isFromHuman: Boolean = true,
    val content: String = "",
    val timestamp: String? = null,
)

data class AiChatLogsPage(
    val page: Int = 1,
    val limit: Int = 50,
    val total: Int = 0,
    val logs: List<AiChatLog> = emptyList(),
)

// ============================================================
// 文件
// ============================================================

data class FileMeta(
    val id: String = "",
    val uploaderId: String? = null,
    val fileName: String = "",
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val fileHash: String? = null,
    val url: String? = null,
    val conversationId: String? = null,
    val createdAt: String? = null,
)

data class FileMetaWrap(val file: FileMeta? = null)

/** App 新版本信息（GET /api/app/latest）。 */
data class AppUpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val notes: String? = null,
    val apkPath: String = "/api/app/download",
    val fileSize: Long = 0,
    val sha256: String? = null,
    val publishedAt: String? = null,
)

data class FileInitResp(
    val instant: Boolean = false,
    val uploadId: String? = null,
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val uploadedChunks: List<Int> = emptyList(),
    val file: FileMeta? = null,
)

data class UploadStatus(
    val uploadId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val uploadedChunks: List<Int> = emptyList(),
    val status: String = "uploading",
)

// ============================================================
// 远程设备与终端
// ============================================================

data class Device(
    val id: String = "",
    val ownerId: String? = null,
    val deviceName: String = "",
    val agentVersion: String? = null,
    val isOnline: Boolean = false,
    val lastSeen: String? = null,
    val createdAt: String? = null,
    val token: String? = null, // 仅注册时一次性返回
)

data class DevicesWrap(val devices: List<Device> = emptyList())
data class DeviceWrap(val device: Device? = null)

data class RegisterDeviceReq(val deviceName: String, val agentVersion: String? = null)

data class CommandReq(val command: String, val timeoutSec: Int? = null)

data class CommandResult(
    val sessionId: String? = null,
    val commandId: String? = null,
    val command: String? = null,
    val exitCode: Int? = null,
    val output: String? = null,
    val timedOut: Boolean = false,
    val error: String? = null,
)

data class RemoteSession(
    val id: String = "",
    val deviceId: String = "",
    val controllerUserId: String? = null,
    val status: String = "active",
    val createdAt: String? = null,
    val closedAt: String? = null,
)

data class RemoteSessionsWrap(val sessions: List<RemoteSession> = emptyList())

data class RemoteCommand(
    val id: String = "",
    val sessionId: String = "",
    val commandText: String = "",
    val output: String? = null,
    val exitCode: Int? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

data class RemoteCommandsWrap(val commands: List<RemoteCommand> = emptyList())

// ============================================================
// 通话（信令 + 通话记录）
// ============================================================

data class CreateRoomReq(val conversationId: String? = null)
data class CreateRoomResp(
    val roomId: String = "",
    val conversationId: String? = null,
    val initiatorId: String? = null,
)

/** call_logs 表以 snake_case 直出，需显式映射。 */
data class CallLog(
    val id: String = "",
    @SerializedName("room_id") val roomId: String? = null,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("initiator_id") val initiatorId: String? = null,
    val participants: List<String> = emptyList(),
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("duration_sec") val durationSec: Int = 0,
)

data class CallLogsWrap(val callLogs: List<CallLog> = emptyList())

// ============================================================
// 会话总结
// ============================================================

data class Summary(
    val id: String = "",
    val conversationId: String = "",
    val summaryType: String? = null,
    val content: String = "",
    val keywords: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val modelUsed: String? = null,
    val createdBy: String? = null,
    val createdAt: String? = null,
)

data class SummaryWrap(val summary: Summary? = null)
data class SummariesWrap(val summaries: List<Summary> = emptyList())

// ============================================================
// 偏好设置
// ============================================================

data class PreferencesResp(
    val preferences: Map<String, Any?> = emptyMap(),
)

// ============================================================
// 错误响应
// ============================================================

data class ErrBody(val error: ErrDetail? = null)
data class ErrDetail(val code: String? = null, val message: String? = null)
