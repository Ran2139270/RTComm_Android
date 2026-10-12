package com.rtcomm.app.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地诊断日志：**崩溃**、**ANR（主线程卡死）**与**事件面包屑**，写入应用私有目录，
 * 供用户在「关于 → 崩溃日志」查看/复制（也可由「复制诊断信息」一并带出）。
 *
 * 隐私与数据安全（刻意约束）：
 *  - 只记录 **异常类型、堆栈帧、运行环境（版本/机型/内存/屏幕/网络状态）与无内容的事件**；
 *  - **不记录** 消息内容、账号、昵称、令牌、服务器地址、会话/消息 ID；
 *  - 异常 message 截断，并抹掉其中可能夹带内容的长引号串（JSON/URL 等）；
 *  - 仅本地存储、**不联网、不自动上传**，只有用户主动「复制」时才外带。
 */
object CrashLog {
    private const val DIR = "crashes"
    private const val MAX_FILES = 8
    private const val MAX_TOTAL = 48 * 1024
    private const val MAX_MESSAGE = 200
    private const val ANR_THRESHOLD_MS = 5_000L

    @Volatile private var installed = false

    /** 安装全局未捕获异常处理器 + 主线程卡死看门狗；须在 Application.onCreate 早期调用。 */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "crash", thread, error, null) }
            // 交回系统默认处理器，保证系统崩溃对话框/上报仍照常。
            prev?.uncaughtException(thread, error)
        }
        startAnrWatchdog(app)
    }

    /** 最近一条（崩溃或 ANR）的可读文本；null 表示暂无。 */
    fun latest(context: Context): String? =
        entries(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /** 记录条数（崩溃 + ANR）。 */
    fun count(context: Context): Int = entries(context).size

    /** 按时间倒序列出记录文件。 */
    fun entries(context: Context): List<File> = runCatching {
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    // ────────────────────────── 内部实现 ──────────────────────────

    private fun dir(app: Context) = File(app.filesDir, DIR).apply { mkdirs() }

    private fun write(app: Context, kind: String, thread: Thread?, error: Throwable?, stack: String?) {
        val text = buildReport(app, kind, thread, error, stack)
        runCatching { File(dir(app), "$kind-${System.currentTimeMillis()}.txt").writeText(text) }
        entries(app).drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun buildReport(
        app: Context,
        kind: String,
        thread: Thread?,
        error: Throwable?,
        stack: String?,
    ): String = buildString {
        append("时间：").append(Instant.now()).append('\n')
        append("类型：").append(kind).append('\n')
        append('\n').append(header(app))
        breadcrumbs().takeIf { it.isNotEmpty() }?.let { append(it) }
        if (error != null) {
            append("\n== 异常 ==\n")
            append("线程：").append(thread?.name ?: "-").append('\n')
            append("类型：").append(error.javaClass.name).append('\n')
            error.message?.let { append("信息：").append(sanitize(it)).append('\n') }
            append("堆栈：\n").append(stack ?: stackOf(error))
        }
    }.take(MAX_TOTAL)

    private fun header(app: Context): String {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val dm = app.resources.displayMetrics
        return buildString {
            append("== 应用 ==\n")
            append("版本：").append(com.rtcomm.app.BuildConfig.VERSION_NAME)
                .append(" (").append(com.rtcomm.app.BuildConfig.VERSION_CODE).append(") ")
                .append(if (com.rtcomm.app.BuildConfig.DEBUG) "debug" else "release").append('\n')
            append("== 设备 ==\n")
            append(Build.MANUFACTURER).append(' ').append(Build.BRAND).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
            append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
                .append("  ABI ").append(Build.SUPPORTED_ABIS.joinToString("/")).append('\n')
            append("== 运行环境 ==\n")
            append("Locale ").append(Locale.getDefault()).append("  TZ ").append(TimeZone.getDefault().id).append('\n')
            append("屏幕 ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append(" @").append(dm.density).append('\n')
            append("内存 ").append((rt.totalMemory() - rt.freeMemory()) / mb)
                .append("MB / 上限 ").append(rt.maxMemory() / mb).append("MB\n")
            append("启动时长 ").append(SystemClock.elapsedRealtime() / 1000).append("s\n")
            append("实时连接 ").append(AppState.wsState.value).append('\n')
        }
    }

    private fun breadcrumbs(): String {
        val events = AppDiagnostics.events.value
        if (events.isEmpty()) return ""
        return "\n== 最近事件（无消息内容）==\n" + events.take(20).joinToString("\n") + "\n"
    }

    private fun stackOf(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    /** 截断异常信息，并抹掉可能夹带内容的长引号串（如 JSON / URL）。 */
    private fun sanitize(raw: String): String {
        val redacted = raw
            .replace(Regex("\"[^\"]{24,}\""), "\"…\"")
            .replace(Regex("'[^']{24,}'"), "'…'")
        return if (redacted.length > MAX_MESSAGE) redacted.take(MAX_MESSAGE) + "…" else redacted
    }

    /** 主线程卡死看门狗：每 5s 探测一次主线程响应；超时则记录主线程堆栈。 */
    private fun startAnrWatchdog(app: Context) {
        val main = Looper.getMainLooper().thread
        val handler = Handler(Looper.getMainLooper())
        Thread {
            while (true) {
                val tick = AtomicInteger(0)
                handler.post { tick.incrementAndGet() }
                try {
                    Thread.sleep(ANR_THRESHOLD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (tick.get() == 0) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        val stack = main.stackTrace.joinToString("\n") { "\tat $it" }
                        val pseudo = RuntimeException("主线程疑似卡死（>${ANR_THRESHOLD_MS / 1000}s）")
                        write(app, "anr", main, pseudo, stack)
                    }
                    // 冷却：避免同一段卡顿被连续重复记录。
                    try { Thread.sleep(ANR_THRESHOLD_MS) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }.apply { isDaemon = true; name = "anr-watchdog"; start() }
    }
}
