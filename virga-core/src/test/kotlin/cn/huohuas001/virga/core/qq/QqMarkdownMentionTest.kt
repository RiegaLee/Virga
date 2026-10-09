package cn.huohuas001.virga.core.qq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QqMarkdownMentionTest {
    @Test
    fun `simple nickname keeps a clickable current protocol mention`() {
        val openId = "F0E1D2C3B4A5968778695A4B3C2D1E0F"
        assertEquals("<qqbot-at-user id=\"$openId\" />", qqMarkdownMention(openId, "Lee"))
    }

    @Test
    fun `symbolic nickname does not disable the real mention protocol`() {
        val mention = qqMarkdownMention(
            "F0E1D2C3B4A5968778695A4B3C2D1E0F",
            "[插件管]_RiegaLee_*#<>"
        )

        assertEquals("<qqbot-at-user id=\"F0E1D2C3B4A5968778695A4B3C2D1E0F\" />", mention)
        assertFalse(mention.contains('\\'))
    }

    @Test
    fun `all ascii punctuation in nickname is neutralized`() {
        val mention = qqMarkdownMention(
            "F0E1D2C3B4A5968778695A4B3C2D1E0F",
            "Admin!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        )

        assertEquals("<qqbot-at-user id=\"F0E1D2C3B4A5968778695A4B3C2D1E0F\" />", mention)
        assertFalse(mention.contains('\\'))
    }

    @Test
    fun `unicode symbols also disable protocol mention expansion`() {
        val mention = qqMarkdownMention(
            "F0E1D2C3B4A5968778695A4B3C2D1E0F",
            "【插件管理】Virga"
        )

        assertEquals("<qqbot-at-user id=\"F0E1D2C3B4A5968778695A4B3C2D1E0F\" />", mention)
    }

    @Test
    fun `unknown or invalid identities never expose protocol identifiers`() {
        assertEquals("<qqbot-at-user id=\"private-open-id\" />", qqMarkdownMention("private-open-id", null))
        assertEquals("该 QQ 用户", qqMarkdownMention("bad<id", "Lee"))
    }
}
