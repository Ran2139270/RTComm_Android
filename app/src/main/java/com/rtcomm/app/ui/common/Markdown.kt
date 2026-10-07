package com.rtcomm.app.ui.common

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Compose 原生 Markdown 渲染器（P2）。
 *
 * 安全规则（重构文档约定）：绝不渲染服务端 HTML / JS —— 所有 <tag> 一律作为
 * 转义文本剔除标签只留内容；链接仅展示 URL 文本，不做可点击跳转（防钓鱼）。
 * 支持：# 标题、**粗体**、*斜体*、`行内代码`、```代码块```、- 列表、> 引用。
 */
object Markdown {

    /** 预编译正则：stripHtml 在流式渲染中每次 flush 都会调用，避免重复编译。 */
    private val HTML_TAG = Regex("<[^>]*>")

    /** 是否含 Markdown 标记（块级/行内）。用于纯文本快速渲染，避免对普通消息逐行解析。 */
    private val MARKUP_HINT = Regex(
        "(?m)^\\s*(#{1,6}\\s|>|[-*+]\\s|\\d+\\.\\s|```|\\|)" +
            "|\\*\\*|__|~~|`|!\\[|\\[[^\\]]+\\]\\(|https?://",
    )

    fun hasMarkup(s: String): Boolean = MARKUP_HINT.containsMatchIn(s)

    /** 剔除 HTML 标签（只留标签内的文本内容）。 */
    fun stripHtml(s: String): String = s.replace(HTML_TAG, "")

    /** 标题：`# 内容` ~ `###### 内容`。 */
    internal val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    /** 无序列表项：`- 内容` / `* 内容` / `+ 内容`。 */
    internal val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    /** 任务列表项（在 bullet 内容内）：`[ ] 内容` / `[x] 内容`。 */
    internal val TASK = Regex("^\\[([ xX])\\]\\s+(.*)$")
    /** 有序列表：`1. 内容`。 */
    internal val ORDERED_LIST = Regex("^\\s*(\\d+)\\.\\s+(.*)$")

    /**
     * 行内解析：**粗** / __粗__ / *斜* / `code` / ~~删除~~ / [文本](链接)。
     * 链接只展示文本（不可点击，防钓鱼）；各类标记贪心最小匹配，未闭合则按原文输出。
     */
    fun inline(src: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = src.length
        while (i < n) {
            when {
                // 图片 ![alt](url)：安全起见不加载外链，仅展示 alt/URL 文本并加下划线。
                src.startsWith("![", i) -> {
                    val close = src.indexOf(']', i + 2)
                    if (close > i + 1 && close + 1 < n && src[close + 1] == '(') {
                        val urlEnd = src.indexOf(')', close + 2)
                        if (urlEnd > close) {
                            val alt = src.substring(i + 2, close)
                            val url = src.substring(close + 2, urlEnd)
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                                append("🖼 " + alt.ifBlank { url })
                            }
                            i = urlEnd + 1
                        } else { append(src[i]); i++ }
                    } else { append(src[i]); i++ }
                }
                // 粗斜体：*** 或 ___（必须放在 ** 之前）
                src.startsWith("***", i) || src.startsWith("___", i) -> {
                    val marker = src.substring(i, i + 3)
                    val end = src.indexOf(marker, i + 3)
                    if (end > i + 3) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 3, end))
                        }
                        i = end + 3
                    } else { append(src[i]); i++ }
                }
                // 粗体：** 或 __（必须放在斜体 * 之前）
                src.startsWith("**", i) || src.startsWith("__", i) -> {
                    val marker = src.substring(i, i + 2)
                    val end = src.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(src[i]); i++ }
                }
                // 删除线：~~
                src.startsWith("~~", i) -> {
                    val end = src.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(src.substring(i + 2, end)) }
                        i = end + 2
                    } else { append(src[i]); i++ }
                }
                // 行内代码
                src[i] == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22888888))) {
                            append(src.substring(i + 1, end))
                        }
                        i = end + 1
                    } else { append(src[i]); i++ }
                }
                // 链接 [文本](url)：只渲染文本并加下划线
                src[i] == '[' -> {
                    val close = src.indexOf(']', i + 1)
                    if (close > i && close + 1 < n && src[close + 1] == '(') {
                        val urlEnd = src.indexOf(')', close + 2)
                        if (urlEnd > close) {
                            val label = src.substring(i + 1, close)
                            val url = src.substring(close + 2, urlEnd)
                            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                                append(label.ifBlank { url })
                            }
                            i = urlEnd + 1
                        } else { append(src[i]); i++ }
                    } else { append(src[i]); i++ }
                }
                // 斜体：单个 *（内容内不能再含 *，避免误吞）
                src[i] == '*' -> {
                    val end = src.indexOf('*', i + 1)
                    if (end > i + 1 && !src.substring(i + 1, end).contains('*')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(src[i]); i++ }
                }
                // 裸链接：http(s)://…（到空白或常见收尾标点为止）
                src.startsWith("http://", i) || src.startsWith("https://", i) -> {
                    var j = i
                    while (j < n && !src[j].isWhitespace() &&
                        src[j] != ')' && src[j] != ']' && src[j] != '>' &&
                        src[j] != '，' && src[j] != '。' && src[j] != '；' && src[j] != '：'
                    ) j++
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(src.substring(i, j)) }
                    i = j
                }
                else -> { append(src[i]); i++ }
            }
        }
    }

    /**
     * 单/多行预览专用：剥离块级标记（标题/引用/列表/任务/分隔线/代码围栏）并压平为一行，
     * 但保留行内样式（粗体/斜体/代码/链接）。用于引用预览、会话列表最后一条、搜索命中等
     * 只有一两行、放不下块级排版的地方。
     */
    fun previewInline(src: String?): AnnotatedString {
        val raw = stripHtml(src ?: "")
        if (raw.isBlank()) return AnnotatedString("")
        val sb = StringBuilder()
        raw.lineSequence().forEach { line ->
            var t = line.trim()
            if (t.isEmpty()) return@forEach
            if (t.length >= 3 && t.all { it == '-' || it == '*' || it == '_' }) return@forEach
            if (t.startsWith("```")) return@forEach
            HEADING.matchEntire(t)?.let { t = it.groupValues[2].trim() }
            t = t.removePrefix(">").trim()
            BULLET.matchEntire(t)?.let { t = it.groupValues[1].trim() }
            TASK.matchEntire(t)?.let { t = it.groupValues[2].trim() }
            ORDERED_LIST.matchEntire(t)?.let { t = it.groupValues[2].trim() }
            if (t.isEmpty()) return@forEach
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        return inline(sb.toString())
    }
}

/** Markdown 块级渲染（消息气泡 / AI 回复通用）。 */
@Composable
fun MarkdownText(src: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val clean = remember(src) { Markdown.stripHtml(src ?: "") }
    // 快速路径：纯文本（无 Markdown 标记）直接 Text 渲染，跳过逐行解析。
    // 聊天列表里绝大多数是普通消息，这一步能显著降低滚动/组合开销。
    if (!remember(clean) { Markdown.hasMarkup(clean) }) {
        Text(clean, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val lines = remember(clean) { clean.lines() }
    // 行内解析结果按行缓存：滚动/重组时不再重复解析（MarkdownText 是聊天列表里最重的每项开销）。
    // 缓存跨 `clean` 存活（而不是每次文本变化就重建）：流式回复每 ~50ms 追加一次，
    // 若跟着 clean 重建会每帧重解析全文，长回复下呈 O(N²)。跨帧缓存后未变化的行直接命中。
    val inlineCache = remember { HashMap<String, AnnotatedString>() }
    if (inlineCache.size > 4096) inlineCache.clear()
    fun inline(s: String): AnnotatedString = inlineCache.getOrPut(s) { Markdown.inline(s) }
    Column(modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    // 代码块：吞到闭合 ```；首行可带语言名（```kotlin）。
                    val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        buf.appendLine(lines[i]); i++
                    }
                    i++ // 跳过闭合
                    MarkdownCodeBlock(code = buf.toString().trimEnd(), language = lang, compact = compact)
                }
                Markdown.HEADING.matches(line) -> {
                    val m = Markdown.HEADING.find(line)!!
                    val style = when (m.groupValues[1].length) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        4 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    }
                    Text(inline(m.groupValues[2]), style = style, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    i++
                }
                line.trimStart().startsWith(">") -> {
                    // 连续引用行合并为一个引用块（左侧竖线 + 底色）。
                    val buf = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(lines[i].trimStart().removePrefix(">").removePrefix(" ").trimEnd())
                        i++
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(Corner.chip),
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                Modifier.width(3.dp).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                            )
                            Text(
                                inline(buf.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Markdown.BULLET.matches(line) -> {
                    // 连续列表项合并为一个列表块；支持任务列表 [ ] / [x]。
                    Column(Modifier.padding(vertical = 2.dp)) {
                        while (i < lines.size && Markdown.BULLET.matches(lines[i])) {
                            val content = Markdown.BULLET.find(lines[i])!!.groupValues[1]
                            val task = Markdown.TASK.find(content)
                            Row(Modifier.padding(vertical = 1.dp)) {
                                if (task != null) {
                                    val done = task.groupValues[1].isNotBlank()
                                    Text(
                                        if (done) "☑ " else "☐ ",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(inline(task.groupValues[2]), style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(inline(content), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            i++
                        }
                    }
                }
                // 有序列表：1. xxx（保留序号），连续项合并为一个列表块。
                Markdown.ORDERED_LIST.matches(line) -> {
                    Column(Modifier.padding(vertical = 2.dp)) {
                        while (i < lines.size && Markdown.ORDERED_LIST.matches(lines[i])) {
                            val m = Markdown.ORDERED_LIST.find(lines[i])!!
                            Row(Modifier.padding(vertical = 1.dp)) {
                                Text(m.groupValues[1] + ". ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                Text(inline(m.groupValues[2]), style = MaterialTheme.typography.bodyMedium)
                            }
                            i++
                        }
                    }
                }
                // 表格：当前行以 | 开头且下一行是分隔行（|---|）。
                line.trim().startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1].trim()) -> {
                    val header = splitTableRow(line)
                    val aligns = parseTableAligns(lines[i + 1].trim())
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows.add(splitTableRow(lines[i])); i++
                    }
                    MarkdownTable(header, rows, aligns)
                }
                // 分隔线：--- / *** / ___
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    i++
                }
                line.isBlank() -> { Spacer(Modifier.height(Space.xs)); i++ }
                else -> { Text(inline(line), style = MaterialTheme.typography.bodyMedium); i++ }
            }
        }
    }
}

/** Markdown 表格分隔行：`|---|:--:|` 等。 */
private val TABLE_SEP = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/** 由分隔行（`:---` / `:---:` / `---:`）解析每列对齐方式。 */
private fun parseTableAligns(sepLine: String): List<TextAlign> =
    splitTableRow(sepLine).map {
        val left = it.startsWith(":")
        val right = it.endsWith(":")
        when {
            left && right -> TextAlign.Center
            right -> TextAlign.End
            else -> TextAlign.Start
        }
    }

/** 代码块：等宽字体 + 语言标签 + 一键复制。 */
@Composable
private fun MarkdownCodeBlock(code: String, language: String?, compact: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1500); copied = false }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 11.sp else 12.sp,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp)
                    .padding(end = 48.dp),
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Markdown 表格：表头加粗 + 单元格分隔，支持列对齐，窄屏可横向滚动。 */
@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, aligns: List<TextAlign>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                header.forEachIndexed { idx, cell ->
                    MarkdownCell(cell, header = true, align = aligns.getOrNull(idx) ?: TextAlign.Start)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            rows.forEach { row ->
                Row {
                    header.indices.forEach { idx ->
                        MarkdownCell(row.getOrNull(idx).orEmpty(), header = false, align = aligns.getOrNull(idx) ?: TextAlign.Start)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownCell(text: String, header: Boolean, align: TextAlign) {
    Text(
        Markdown.inline(text),
        style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else null,
        textAlign = align,
        modifier = Modifier.widthIn(min = 72.dp).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
