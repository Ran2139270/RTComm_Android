package com.rtcomm.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** 有界、无敏感信息的本地诊断记录，用于反馈偶发问题。 */
object AppDiagnostics {
    private const val PREF = "rtcomm_diagnostics"
    private const val KEY = "recent_events"
    private const val LIMIT = 40
    val events = MutableStateFlow<List<String>>(emptyList())

    @Synchronized
    fun initialize(context: Context) {
        events.value = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toList()
            ?.sortedDescending()
            ?: emptyList()
    }

    @Synchronized
    fun record(context: Context, area: String, error: Throwable? = null) {
        val detail = error?.let { Api.userMessage(it) } ?: "完成"
        val line = "${java.time.Instant.now()} [$area] $detail"
        val next = (listOf(line) + events.value).distinct().take(LIMIT)
        events.value = next
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, next.toSet()).apply()
    }

    @Suppress("UNUSED_PARAMETER")
    fun report(context: Context): String = buildString {
        append("RTComm v").append(com.rtcomm.app.BuildConfig.VERSION_NAME)
        append("\nAndroid ").append(android.os.Build.VERSION.RELEASE)
        append("\n网络实时状态：").append(AppState.wsState.value)
        append("\n最近诊断（不含消息、账号和令牌）：\n")
        append(events.value.take(12).joinToString("\n"))
    }
}
