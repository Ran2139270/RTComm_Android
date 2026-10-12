package com.rtcomm.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConvEntity::class, MessageEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun convDao(): ConvDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var inst: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToJson TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN sendState TEXT")
            }
        }

        /**
         * v2 → v3：引入账号隔离列。
         * 旧行无法安全归属到任何账号（历史版本所有账号共用缓存），
         * 而本地缓存只是服务端镜像，直接清空即可由网络重新填充。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room cannot alter a primary key in place. Rebuild the two cache tables with
                // accountKey in the composite key, then intentionally discard unassignable
                // legacy cache rows (the server remains the source of truth).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversations_v3 (" +
                        "id TEXT NOT NULL, accountKey TEXT NOT NULL, type TEXT NOT NULL, " +
                        "name TEXT, createdBy TEXT, createdAt TEXT, lastMessageJson TEXT, " +
                        "unreadCount INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id, accountKey))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS messages_v3 (" +
                        "id TEXT NOT NULL, conversationId TEXT NOT NULL, accountKey TEXT NOT NULL, " +
                        "senderId TEXT NOT NULL, senderJson TEXT, messageType TEXT NOT NULL, content TEXT, " +
                        "fileId TEXT, fileJson TEXT, replyToMessageId TEXT, replyToJson TEXT, sendState TEXT, " +
                        "isDeleted INTEGER NOT NULL, createdAt TEXT, createdAtIdx INTEGER NOT NULL, " +
                        "PRIMARY KEY(id, conversationId, accountKey))",
                )
                db.execSQL("DROP TABLE conversations")
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE conversations_v3 RENAME TO conversations")
                db.execSQL("ALTER TABLE messages_v3 RENAME TO messages")
            }
        }

        /**
         * v3 → v4：新增待发媒体的本地预览/上传源与上传进度列。
         * 纯增量列，旧缓存行保持默认值即可。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN localPreviewUri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN localUploadPath TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN uploadProgress REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN uploadTotalBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5：为热路径查询补索引（不改变任何列/数据，仅加速）。
         * 索引名必须与 Room 由 @Entity(indices) 生成的默认名一致，迁移测试才能通过。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_accountKey_updatedAt " +
                        "ON conversations(accountKey, updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationId_accountKey_createdAtIdx_id " +
                        "ON messages(conversationId, accountKey, createdAtIdx, id)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rtcomm.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { inst = it }
            }
    }
}
