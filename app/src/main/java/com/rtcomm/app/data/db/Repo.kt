package com.rtcomm.app.data.db

import android.content.Context
import com.google.gson.Gson
import com.rtcomm.app.data.AtRestCrypto
import com.rtcomm.app.data.Conversation
import com.rtcomm.app.data.Message
import com.rtcomm.app.data.MessageFile
import com.rtcomm.app.data.PublicUser
import com.rtcomm.app.data.ReplyMessage
import com.rtcomm.app.data.SendState
import java.time.Instant

/**
 * 本地缓存仓库：Room ↔ 领域模型互转；会话与消息的离线读写入口。
 *
 * 账号隔离：所有读写都限定在当前账号的 [accountKey]（server|userId），
 * 由 AppState 在登录/登出时维护，避免切换账号后读到上一账号的缓存。
 * 敏感字段落库前经 [AtRestCrypto] 加密。
 * 所有方法为 suspend，调用方在 IO 协程执行。
 */
object Repo {
    private val gson = Gson()
    private var db: AppDatabase? = null

    /** 当前账号隔离键（server|userId）。 */
    @Volatile var accountKey: String = ""

    fun init(context: Context) {
        if (db == null) db = AppDatabase.get(context)
    }

    private fun convDao() = requireNotNull(db) { "Repo 未初始化" }.convDao()
    private fun messageDao() = requireNotNull(db) { "Repo 未初始化" }.messageDao()

    // ---------- 转换 ----------

    private fun parseTime(iso: String?): Long =
        if (iso.isNullOrBlank()) 0L else runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    private fun msgToEntity(m: Message, key: String): MessageEntity = MessageEntity(
        id = m.id,
        conversationId = m.conversationId,
        accountKey = key,
        senderId = m.senderId,
        // AAD 绑定「账号|消息|列名」：密文被搬到其它行/列会被认证失败，防止字段混淆。
        senderJson = AtRestCrypto.encrypt(m.sender?.let { gson.toJson(it) }, aad = "msg|$key|${m.id}|sender"),
        messageType = m.messageType,
        content = AtRestCrypto.encrypt(m.content, aad = "msg|$key|${m.id}|content"),
        fileId = m.fileId,
        fileJson = AtRestCrypto.encrypt(m.file?.let { gson.toJson(it) }, aad = "msg|$key|${m.id}|file"),
        replyToMessageId = m.replyToMessageId,
        replyToJson = AtRestCrypto.encrypt(m.replyTo?.let { gson.toJson(it) }, aad = "msg|$key|${m.id}|replyTo"),
        sendState = m.sendState?.name,
        isDeleted = m.isDeleted,
        createdAt = m.createdAt,
        createdAtIdx = parseTime(m.createdAt),
        // 本地待发媒体的路径/预览 URI 同样是私密信息，一并加密（历史明文仍可读）。
        localPreviewUri = AtRestCrypto.encrypt(m.localPreviewUri, aad = "msg|$key|${m.id}|preview"),
        localUploadPath = AtRestCrypto.encrypt(m.localUploadPath, aad = "msg|$key|${m.id}|upload"),
        uploadProgress = m.uploadProgress,
        uploadTotalBytes = m.uploadTotalBytes,
    )

    private fun entityToMsg(e: MessageEntity): Message {
        val idAad = "msg|${e.accountKey}|${e.id}"
        return Message(
            id = e.id,
            conversationId = e.conversationId,
            senderId = e.senderId,
            sender = AtRestCrypto.decrypt(e.senderJson, aad = "$idAad|sender")?.let {
                runCatching { gson.fromJson(it, PublicUser::class.java) }.getOrNull()
            },
            messageType = e.messageType,
            content = AtRestCrypto.decrypt(e.content, aad = "$idAad|content"),
            fileId = e.fileId,
            file = AtRestCrypto.decrypt(e.fileJson, aad = "$idAad|file")?.let {
                runCatching { gson.fromJson(it, MessageFile::class.java) }.getOrNull()
            },
            replyToMessageId = e.replyToMessageId,
            replyTo = AtRestCrypto.decrypt(e.replyToJson, aad = "$idAad|replyTo")?.let {
                runCatching { gson.fromJson(it, ReplyMessage::class.java) }.getOrNull()
            },
            isDeleted = e.isDeleted,
            createdAt = e.createdAt,
            // 进程在发送途中被杀时，遗留的 Sending 必须降级为 Queued，
            // 否则这条消息既不会自动重试，也没有手动重发入口，会永久卡住。
            sendState = e.sendState?.let { runCatching { SendState.valueOf(it) }.getOrNull() }
                ?.let { if (it == SendState.Sending) SendState.Queued else it },
            localPreviewUri = AtRestCrypto.decrypt(e.localPreviewUri, aad = "$idAad|preview"),
            localUploadPath = AtRestCrypto.decrypt(e.localUploadPath, aad = "$idAad|upload"),
            uploadProgress = e.uploadProgress,
            uploadTotalBytes = e.uploadTotalBytes,
        )
    }

    private fun convToEntity(c: Conversation, key: String): ConvEntity = ConvEntity(
        id = c.id,
        accountKey = key,
        type = c.type,
        name = AtRestCrypto.encrypt(c.name, aad = "conv|$key|${c.id}|name"),
        createdBy = c.createdBy,
        createdAt = c.createdAt,
        lastMessageJson = AtRestCrypto.encrypt(c.lastMessage?.let { gson.toJson(it) }, aad = "conv|$key|${c.id}|last"),
        unreadCount = c.unreadCount,
        updatedAt = parseTime(c.lastMessage?.createdAt ?: c.createdAt),
    )

    private fun entityToConv(e: ConvEntity): Conversation {
        val idAad = "conv|${e.accountKey}|${e.id}"
        return Conversation(
            id = e.id,
            type = e.type,
            name = AtRestCrypto.decrypt(e.name, aad = "$idAad|name"),
            createdBy = e.createdBy,
            createdAt = e.createdAt,
            lastMessage = AtRestCrypto.decrypt(e.lastMessageJson, aad = "$idAad|last")?.let {
                runCatching { gson.fromJson(it, Message::class.java) }.getOrNull()
            },
            unreadCount = e.unreadCount,
            members = emptyList(), // 成员列表每次从网络拉取，不落库
        )
    }

    // ---------- 会话 ----------

    suspend fun saveConversations(list: List<Conversation>) {
        val key = accountKey
        convDao().upsertAll(list.map { convToEntity(it, key) })
    }

    suspend fun updateConv(conv: Conversation) {
        val key = accountKey
        convDao().upsert(convToEntity(conv, key))
    }

    /** 会话缓存条数上限：服务端才是权威源，本地只做离线镜像。 */
    const val CONVERSATION_CACHE_LIMIT = 500

    suspend fun cachedConversations(): List<Conversation> =
        convDao().all(accountKey, CONVERSATION_CACHE_LIMIT).map { entityToConv(it) }

    /** 按 id 取单个会话缓存：避免调用方先解密整表再 firstOrNull。 */
    suspend fun cachedConversation(id: String): Conversation? =
        convDao().byId(id, accountKey)?.let { entityToConv(it) }

    suspend fun setUnread(convId: String, n: Int) { convDao().setUnread(convId, n, accountKey) }

    suspend fun deleteConversation(convId: String) { convDao().delete(convId, accountKey) }

    /** 登出/切换账号时清空该账号的本地缓存。 */
    suspend fun clearAccount(key: String) {
        convDao().clear(key)
        messageDao().clear(key)
    }

    // ---------- 消息 ----------

    suspend fun saveMessages(convId: String, list: List<Message>, replace: Boolean) {
        val key = accountKey
        val entities = list.map { msgToEntity(it, key) }
        if (replace) messageDao().replaceConversationMessages(convId, key, entities)
        else messageDao().upsertAll(entities)
    }

    suspend fun cachedMessages(convId: String, limit: Int = 50): List<Message> =
        messageDao().latest(convId, accountKey, limit).map { entityToMsg(it) }

    suspend fun markMessageDeleted(convId: String, messageId: String) {
        messageDao().markDeleted(messageId, convId, accountKey)
    }

    suspend fun deleteMessage(convId: String, messageId: String) {
        messageDao().delete(messageId, convId, accountKey)
    }

    /** 清空某个会话在本机的全部消息缓存（「清空聊天记录」用）。 */
    suspend fun clearMessages(convId: String) {
        messageDao().clearConversation(convId, accountKey)
    }

    /** 清空本账号的全部消息缓存（「存储管理 → 清空聊天缓存」，可由服务端重新拉取）。 */
    suspend fun clearAllMessages() {
        messageDao().clear(accountKey)
    }

    /** 冷启动缓存上限：超过就丢弃更旧的，历史仍可从服务端游标分页取回。 */
    const val CACHE_KEEP_PER_CONVERSATION = 500

    suspend fun pruneMessages(convId: String, keep: Int = CACHE_KEEP_PER_CONVERSATION) {
        messageDao().prune(convId, keep, accountKey)
    }
}
