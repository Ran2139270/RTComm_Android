package com.rtcomm.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * ANSI 转义序列 → Compose AnnotatedString。
 * 支持：SGR（30-37/90-97 前景色、1 加粗、0 重置、22 取消加粗）；
 * 其他 CSI / OSC 序列直接剔除（不渲染控制噪声）。
 * 不可信输入安全：只解析颜色样式，绝不执行任何内容。
 */
object AnsiText {

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    private val PALETTE = mapOf(
        30 to Color(0xFF4E5966), 31 to Color(0xFFE5484D), 32 to Color(0xFF46A758),
        33 to Color(0xFFFFB224), 34 to Color(0xFF3E63DD), 35 to Color(0xFF8E4EC6),
        36 to Color(0xFF00A2C7), 37 to Color(0xFFDFE3E8),
        90 to Color(0xFF8B949E), 91 to Color(0xFFFF6E5E), 92 to Color(0xFF5AE48B),
        93 to Color(0xFFFFD679), 94 to Color(0xFF7D9BFF), 95 to Color(0xFFC29AF2),
        96 to Color(0xFF5CD7F5), 97 to Color(0xFFFFFFFF),
    )

    /** 样式边界：从 at 偏移起生效的 (color, bold)。 */
    private class Bound(val at: Int, val color: Color?, val bold: Boolean)

    /** 解析一段含 ANSI 序列的文本为带样式的 AnnotatedString。 */
    fun parse(raw: String): AnnotatedString {
        val sb = StringBuilder()
        val bounds = ArrayList<Bound>()
        var color: Color? = null
        var bold = false
        var i = 0
        val n = raw.length

        while (i < n) {
            val c = raw[i]
            if (c == ESC) {
                if (i + 1 < n && raw[i + 1] == '[') {
                    // CSI：ESC [ 参数... 终止字母
                    var j = i + 2
                    while (j < n && !raw[j].isLetter()) j++
                    if (j >= n) break // 未闭合，丢弃尾部
                    val body = raw.substring(i + 2, j)
                    if (raw[j] == 'm') {
                        // 应用新的 SGR 参数，然后记录边界
                        val parts = body.split(';').filter { it.isNotEmpty() }
                        if (parts.isEmpty() || parts[0] == "0") {
                            color = null; bold = false
                        } else for (p in parts) {
                            val code = p.toIntOrNull() ?: continue
                            when {
                                code == 0 -> { color = null; bold = false }
                                code == 1 -> bold = true
                                code == 22 -> bold = false
                                code in 30..37 || code in 90..97 -> color = PALETTE[code]
                                code == 39 -> color = null
                            }
                        }
                        bounds.add(Bound(sb.length, color, bold))
                    }
                    i = j + 1
                } else if (i + 1 < n && raw[i + 1] == ']') {
                    // OSC：ESC ] ... BEL 或 ESC \
                    var j = i + 2
                    while (j < n && raw[j] != BEL && !(raw[j] == ESC && j + 1 < n && raw[j + 1] == '\\')) j++
                    i = if (j < n && raw[j] == BEL) j + 1 else j + 2
                } else {
                    i += 2 // 其他两字符转义（ESC M 等）
                }
            } else {
                sb.append(c)
                i++
            }
        }

        return buildAnnotatedString {
            append(sb.toString())
            for (idx in bounds.indices) {
                val b = bounds[idx]
                if (b.color == null && !b.bold) continue
                val end = if (idx + 1 < bounds.size) bounds[idx + 1].at else sb.length
                if (end > b.at) {
                    addStyle(
                        SpanStyle(color = b.color ?: Color.Unspecified, fontWeight = if (b.bold) FontWeight.Bold else null),
                        b.at, end,
                    )
                }
            }
        }
    }
}
