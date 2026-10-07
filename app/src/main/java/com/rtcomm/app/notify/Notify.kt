package com.rtcomm.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.rtcomm.app.MainActivity
import com.rtcomm.app.R
import com.rtcomm.app.data.AppState

/**
 * 本地通知工具：消息通知（会话不在前台时）。
 * 权限：Android 13+ 需要 POST_NOTIFICATIONS 运行时权限（由调用方申请）。
 */
object Notify {
    const val CHANNEL_MSG = "rtcomm_messages"
    /** 关闭「通知声音」后使用的静音频道。 */
    const val CHANNEL_MSG_SILENT = "rtcomm_messages_silent"
    /** 同时关闭声音与震动。 */
    const val CHANNEL_MSG_MUTE = "rtcomm_messages_mute"
    const val CHANNEL_SERVICE = "rtcomm_service"
    const val ID_SERVICE = 1
    /** 同组通知会由系统折叠；按会话派生 id，保证一个会话只留一条通知。 */
    private const val GROUP_MESSAGES = "rtcomm.messages"

    /** AuthStore 只读 SharedPreferences；复用单例，避免每条通知都新建一次。 */
    @Volatile private var cachedAuthStore: com.rtcomm.app.data.AuthStore? = null

    private fun authStore(context: Context): com.rtcomm.app.data.AuthStore =
        cachedAuthStore ?: com.rtcomm.app.data.AuthStore(context.applicationContext).also { cachedAuthStore = it }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MSG,
                context.getString(R.string.notif_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_desc)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MSG_SILENT,
                context.getString(R.string.notif_channel_messages_silent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_silent_desc)
                setSound(null, null)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MSG_MUTE,
                context.getString(R.string.notif_channel_messages_mute),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_messages_mute_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notif_channel_service_desc)
                setShowBadge(false)
            },
        )
    }

    private fun channelFor(sound: Boolean, vibrate: Boolean): String = when {
        !sound && !vibrate -> CHANNEL_MSG_MUTE
        !sound -> CHANNEL_MSG_SILENT
        else -> CHANNEL_MSG
    }

    private fun inDndWindow(start: Int, end: Int, hour: Int): Boolean = when {
        start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end
    }

    private fun contentIntent(context: Context, conversationId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                putExtra("openConversationId", conversationId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun showMessage(context: Context, conversationTitle: String, body: String, conversationId: String) {
        if (AppState.currentUser.value == null) return
        val store = authStore(context)
        // 总开关关闭时不再发送（服务端事件仍会到，但不打扰用户）
        if (!store.notificationsEnabled) return
        // 单会话免打扰：仍然收消息、仍然计未读，只是不打扰
        if (store.isMuted(conversationId)) return
        // 全局免打扰时段：窗口内不打扰（消息与未读不受影响）
        if (store.dndEnabled && inDndWindow(store.dndStart, store.dndEnd, java.time.LocalTime.now().hour)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val channel = channelFor(store.notifSound, store.notifVibrate)
        val privacy = store.notificationPreview
        val safeTitle = if (privacy == "generic") context.getString(R.string.notif_new_message_title) else conversationTitle
        val safeBody = if (privacy == "generic") context.getString(R.string.notif_new_message_body) else body
        // 锁屏/通知栏的公开版本永远不含正文，用户选择 full 也只是在解锁后可见。
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(context.getString(R.string.notif_new_message_title))
            .setContentText(context.getString(R.string.notif_new_message_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, conversationId))
            .build()
        // MessagingStyle + 会话派生 id：同一会话只保留一条通知，内容随最新消息更新，
        // 系统通知栏也能正确显示为一条对话（而不是刷屏的多条独立通知）。
        val me = Person.Builder().setName("我").build()
        val sender = Person.Builder().setName(safeTitle).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (privacy == "generic") null else conversationTitle)
            .addMessage(safeBody, System.currentTimeMillis(), sender)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_msg)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setStyle(style)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentIntent(context, conversationId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_MESSAGES)
            .build()
        NotificationManagerCompat.from(context).notify(conversationId.hashCode(), n)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
