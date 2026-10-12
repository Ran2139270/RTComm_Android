package com.rtcomm.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** [Format] 中不依赖 Android / 当前时间的纯文本处理逻辑。 */
class FormatTest {

    @Test
    fun mdToPlain_stripsInlineCode() {
        assertEquals("code", Format.mdToPlain("`code`"))
    }

    @Test
    fun mdToPlain_stripsCodeBlock() {
        assertEquals("val x = 1", Format.mdToPlain("```\nval x = 1\n```"))
    }

    @Test
    fun mdToPlain_replacesImageWithPlaceholder() {
        assertEquals("[图片]", Format.mdToPlain("![alt](https://example.com/a.png)"))
    }

    @Test
    fun mdToPlain_keepsLinkText() {
        assertEquals("text", Format.mdToPlain("[text](https://example.com)"))
    }

    @Test
    fun mdToPlain_stripsHeadingsAndQuotes() {
        assertEquals("Title", Format.mdToPlain("# Title"))
        assertEquals("quote", Format.mdToPlain("> quote"))
    }

    @Test
    fun mdToPlain_stripsBoldItalicStrike() {
        assertEquals("bold", Format.mdToPlain("**bold**"))
        assertEquals("italic", Format.mdToPlain("*italic*"))
        assertEquals("strike", Format.mdToPlain("~~strike~~"))
    }

    @Test
    fun mdToPlain_handlesNullAndBlank() {
        assertEquals("", Format.mdToPlain(null))
        assertEquals("", Format.mdToPlain(""))
        assertEquals("", Format.mdToPlain("   "))
    }

    @Test
    fun duration_formatsMmSsAndHHmmSs() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("0:00", Format.duration(-5))
        assertEquals("1:05", Format.duration(65))
        assertEquals("1:01:01", Format.duration(3661))
    }
}
