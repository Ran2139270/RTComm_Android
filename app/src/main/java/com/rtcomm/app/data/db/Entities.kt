package com.rtcomm.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话缓存表：未读数持久化，杀进程后徽标不丢。
 *
 * 索引：列表与 prune 都按 accountKey 过滤、updatedAt 排序；主键最左列是 id，
 * 无法命中该排序，所以补一个 (accountKey, updatedAt) 覆盖索引，避免全表扫描 + 临时排序。
 */
@Entity(
    tableName = "conversations",
    primaryKeys = ["id", "accountKey"],
    indices = [Index(value = ["accountKey", "updatedAt"])],
)
data class ConvEntity(
    val id: String,
    /** 账号隔离键（server|userId）：不同账号/服务器的缓存互不可见。 */
    val accountKey: String = "",
    val type: String,
    val name: String?,
    val createdBy: String?,
    val createdAt: String?,
    val lastMessageJson: String?, // Gson 序列化的 Message
    val unreadCount: Int = 0,
    val updatedAt: Long = 0,      // 排序戳
)

/**
 * 消息缓存表：按会话分页读取，进聊天页先显缓存再拉网络。
 *
 * 索引：所有查询都是 `conversationId = ? AND accountKey = ?` 再按
 * `createdAtIdx DESC, id DESC` 排序；主键最左列是 id，用不上。补一个与排序完全一致的
 * 覆盖索引，让 latest/prune 等按会话分页的查询直接走索引扫描。
 */
@Entity(
    tableName = "messages",
    primaryKeys = ["id", "conversationId", "accountKey"],
    indices = [Index(value = ["conversationId", "accountKey", "createdAtIdx", "id"])],
)
data class MessageEntity(
    val id: String,
    val conversationId: String,
    /** 账号隔离键（server|userId）。 */
    val accountKey: String = "",
    val senderId: String,
    val senderJson: String?,     // Gson 序列化的 PublicUser
    val messageType: String,
    val content: String?,
    val fileId: String?,
    val fileJson: String?,
    val replyToMessageId: String?,
    /** 原引用摘要与本地发送状态都必须持久化，避免离线恢复时丢失语义。 */
    val replyToJson: String?,
    val sendState: String?,
    val isDeleted: Boolean,
    val createdAt: String?,
    val createdAtIdx: Long,      // epoch millis，排序用（按 ISO 时间解析；失败取 0）
    /** 待发媒体的本地预览/上传源与进度：持久化以支持退出会话/重启后继续上传。 */
    val localPreviewUri: String? = null,
    val localUploadPath: String? = null,
    val uploadProgress: Float = 0f,
    val uploadTotalBytes: Long = 0L,
)
