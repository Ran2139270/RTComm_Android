package com.rtcomm.app.ui.common

import android.content.Context
import android.provider.Settings
import com.rtcomm.app.data.AppState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 时间与文本格式化工具。 */
object Format {

    // Markdown 剥离规则提到顶层：避免每次 [mdToPlain] 都重新编译 9 个正则。
    private val reCodeBlock = Regex("```[\\s\\S]*?```")
    private val reInlineCode = Regex("`([^`]*)`")
    private val reImage = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val reLink = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val reHeading = Regex("^#{1,6}\\s*", RegexOption.MULTILINE)
    private val reQuote = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
    private val reBold = Regex("\\*\\*([^*]+)\\*\\*")
    private val reItalic = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    private val reStrike = Regex("~~([^~]+)~~")

    private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val hm12 = DateTimeFormatter.ofPattern("hh:mm a").withZone(ZoneId.systemDefault())
    private val md = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val ymd12 = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a").withZone(ZoneId.systemDefault())

    private fun parse(iso: String?): Instant? =
        if (iso.isNullOrBlank()) null else runCatching { Instant.parse(iso) }.getOrNull()

    private fun is12h(): Boolean = when (AppState.timeFormat.value) {
        "h12" -> true
        "h24" -> false
        // 「跟随系统」：读取 Activity 刷新的系统 24 小时制标记。
        else -> !AppState.system24Hour
    }

    /** 消息气泡时间戳。 */
    fun time(iso: String?): String {
        val t = parse(iso) ?: return ""
        return if (is12h()) hm12.format(t) else hm.format(t)
    }

    /** 会话列表 / 通用相对时间。 */
    fun relative(iso: String?): String {
        val t = parse(iso) ?: return ""
        val now = Instant.now()
        val sec = (now.epochSecond - t.epochSecond)
        return when {
            sec < 0 -> hm.format(t)
            sec < 60 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86_400 -> hm.format(t)
            sec < 7 * 86_400 -> md.format(t)
            else -> ymd.format(t)
        }
    }

    fun dateTime(iso: String?): String {
        val t = parse(iso) ?: return ""
        return if (is12h()) ymd12.format(t) else ymd.format(t)
    }

    /** 聊天日期分隔：今天 / 昨天 / 具体日期。 */
    fun dayLabel(iso: String?): String {
        val t = parse(iso) ?: return ""
        val zone = ZoneId.systemDefault()
        val date = t.atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> {
                val fmt = if (date.year == today.year) {
                    DateTimeFormatter.ofPattern("M月d日")
                } else {
                    DateTimeFormatter.ofPattern("yyyy年M月d日")
                }
                fmt.format(date)
            }
        }
    }

    /** 两个时间戳是否落在同一天（本地时区）。 */
    fun sameDay(a: String?, b: String?): Boolean {
        val ta = parse(a) ?: return false
        val tb = parse(b) ?: return false
        val zone = ZoneId.systemDefault()
        return ta.atZone(zone).toLocalDate() == tb.atZone(zone).toLocalDate()
    }

    /** 时长（秒）→ mm:ss / h:mm:ss。 */
    fun duration(sec: Int): String {
        if (sec <= 0) return "0:00"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * 将 Markdown 转为安全纯文本用于展示：
     * 剥离常见格式符号，绝不解释 HTML —— 满足“至少安全纯文本，不信任服务端 HTML”。
     */
    /** 纯文本结果按原文缓存：列表滚动/重组时同一段 Markdown 不再重复跑 9 次正则。 */
    private val mdPlainCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    fun mdToPlain(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        synchronized(mdPlainCache) { mdPlainCache[text] }?.let { return it }
        var s = text
        s = reCodeBlock.replace(s) { it.value.trim('`').trim() } // 代码块
        s = reInlineCode.replace(s, "$1")                        // 行内代码
        s = reImage.replace(s, "[图片]")                          // 图片
        s = reLink.replace(s, "$1")                              // 链接 → 文本
        s = reHeading.replace(s, "")                             // 标题
        s = reQuote.replace(s, "")                               // 引用
        s = reBold.replace(s, "$1")                              // 粗体
        s = reItalic.replace(s, "$1")                            // 斜体
        s = reStrike.replace(s, "$1")                            // 删除线
        val result = s.trim()
        synchronized(mdPlainCache) { mdPlainCache[text] = result }
        return result
    }

    /** 动画是否降级：应用内设置优先（on/off），否则跟随系统动画缩放。 */
    fun animationsReduced(context: Context): Boolean {
        when (AppState.reduceMotion.value) {
            "on" -> return true
            "off" -> return false
        }
        return systemAnimationsReduced(context)
    }

    /** 仅系统动画缩放（不叠加应用内偏好）。 */
    fun systemAnimationsReduced(context: Context): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale <= 0.5f
    }
}
