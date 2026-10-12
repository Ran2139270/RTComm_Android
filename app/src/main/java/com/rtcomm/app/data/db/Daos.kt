package com.rtcomm.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface ConvDao {
    /**
     * 会话列表：按最近活跃倒序。
     * 加 LIMIT 兜底，避免异常账号在本地堆积出超大结果集（服务端始终是权威源）。
     */
    @Query("SELECT * FROM conversations WHERE accountKey = :accountKey ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun all(accountKey: String, limit: Int): List<ConvEntity>

    @Query("SELECT * FROM conversations WHERE id = :id AND accountKey = :accountKey")
    suspend fun byId(id: String, accountKey: String): ConvEntity?

    @Upsert
    suspend fun upsertAll(list: List<ConvEntity>)

    @Upsert
    suspend fun upsert(c: ConvEntity)

    @Query("UPDATE conversations SET unreadCount = :n WHERE id = :id AND accountKey = :accountKey")
    suspend fun setUnread(id: String, n: Int, accountKey: String)

    @Query("DELETE FROM conversations WHERE id = :id AND accountKey = :accountKey")
    suspend fun delete(id: String, accountKey: String)

    @Query("DELETE FROM conversations WHERE accountKey = :accountKey")
    suspend fun clear(accountKey: String)
}

@Dao
interface MessageDao {
    /** 该会话最新 limit 条（升序），与 REST 分页语义对齐。 */
    @Query("SELECT * FROM messages WHERE conversationId = :convId AND accountKey = :accountKey ORDER BY createdAtIdx DESC, id DESC LIMIT :limit")
    suspend fun latest(convId: String, accountKey: String, limit: Int): List<MessageEntity>

    @Upsert
    suspend fun upsertAll(list: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId AND conversationId = :convId AND accountKey = :accountKey")
    suspend fun delete(messageId: String, convId: String, accountKey: String)

    /**
     * 撤回：正文、发送者、文件、引用摘要与待发媒体引用必须一并清掉，
     * 否则仅清 content 时这些字段仍可从库里恢复出原文。
     */
    @Query(
        "UPDATE messages SET isDeleted = 1, content = '', senderJson = NULL, fileId = NULL, " +
            "fileJson = NULL, replyToMessageId = NULL, replyToJson = NULL, " +
            "localPreviewUri = NULL, localUploadPath = NULL, uploadProgress = 0, uploadTotalBytes = 0 " +
            "WHERE id = :messageId AND conversationId = :convId AND accountKey = :accountKey",
    )
    suspend fun markDeleted(messageId: String, convId: String, accountKey: String)

    @Query("DELETE FROM messages WHERE accountKey = :accountKey")
    suspend fun clear(accountKey: String)

    /**
     * 只保留该会话最新 keep 条，其余删除。
     * 本地缓存是冷启动/离线的镜像，服务端始终是权威数据源，因此缓存不能无限增长。
     */
    @Query(
        "DELETE FROM messages WHERE conversationId = :convId AND accountKey = :accountKey AND id NOT IN (" +
            "SELECT id FROM messages WHERE conversationId = :convId AND accountKey = :accountKey " +
            "ORDER BY createdAtIdx DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(convId: String, keep: Int, accountKey: String)

    @Transaction
    suspend fun replaceConversationMessages(convId: String, accountKey: String, list: List<MessageEntity>) {
        clearConversation(convId, accountKey)
        upsertAll(list)
    }

    @Query("DELETE FROM messages WHERE conversationId = :convId AND accountKey = :accountKey")
    suspend fun clearConversation(convId: String, accountKey: String)
}
