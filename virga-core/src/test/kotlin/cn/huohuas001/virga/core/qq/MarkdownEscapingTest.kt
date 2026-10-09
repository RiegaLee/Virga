package cn.huohuas001.virga.core.qq

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownEscapingTest {
    @Test
    fun `minecraft underscores stay literal inside markdown`() {
        assertEquals("\\_RiegaLee\\_", escapeMarkdownText("_RiegaLee_"))
        assertEquals("h1nt0n\\_X", escapeMarkdownText("h1nt0n_X"))
    }

    @Test
    fun `other inline markdown punctuation is escaped`() {
        assertEquals("\\*name\\* \\[one\\] \\~old\\~", escapeMarkdownText("*name* [one] ~old~"))
    }
}
