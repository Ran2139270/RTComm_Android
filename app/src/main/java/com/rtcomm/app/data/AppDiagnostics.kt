package com.rtcomm.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 有界、无敏感信息的本地事件记录（面包屑），用于反馈偶发问题；崩溃报告会附带最近事件。
 * 只记录**事件名与安全化的错误文案**，不含消息内容、账号或令牌。
 */
object AppDiagnostics {
    private const val PREF = "rtcomm_diagnostics"
    private const val KEY = "recent_events"
    private const val LIMIT = 60

    val events = MutableStateFlow<List<String>>(emptyList())

    @Volatile private var app: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        app = context.applicationContext
        events.value = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toList()
            ?.sortedDescending()
            ?: emptyList()
    }

    /** 记录一条无内容的面包屑（供崩溃报告还原操作路径）；无需 Context。 */
    fun breadcrumb(area: String, detail: String = "") {
        val line = "${java.time.Instant.now()} [$area]${if (detail.isBlank()) "" else " $detail"}"
        val next = (listOf(line) + events.value).distinct().take(LIMIT)
        events.value = next
        app?.let { ctx ->
            runCatching {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putStringSet(KEY, next.toSet()).apply()
            }
        }
    }

    @Synchronized
    fun record(context: Context, area: String, error: Throwable? = null) {
        app = context.applicationContext
        breadcrumb(area, error?.let { Api.userMessage(it) } ?: "完成")
    }

    /** 诊断报告：运行环境 + 最近事件 + 最近一次崩溃/ANR（均不含消息、账号和令牌）。 */
    @Suppress("UNUSED_PARAMETER")
    fun report(context: Context): String = buildString {
        append("Waft v").append(com.rtcomm.app.BuildConfig.VERSION_NAME)
        append("\nAndroid ").append(android.os.Build.VERSION.RELEASE)
        append("\n网络实时状态：").append(AppState.wsState.value)
        append("\n最近诊断（不含消息、账号和令牌）：\n")
        append(events.value.take(12).joinToString("\n"))
        CrashLog.latest(context)?.let {
            append("\n\n最近崩溃/ANR（不含消息、账号和令牌）：\n").append(it)
        }
    }
}
