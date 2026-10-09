package cn.huohuas001.virga.core.qq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QqPanelFieldsTest {
    @Test
    fun `uses QQ display width rules`() {
        assertEquals(7, QqPanelFields.displayWidth("abc你好"))
        assertEquals(4, QqPanelFields.displayWidth("极光"))
    }

    @Test
    fun `shortens long descriptions without splitting unicode code points`() {
        val normalized = assertNotNull(
            QqPanelFields.normalize(
                QqPanelCommand("查在线", "查看在线玩家文字列表（图片故障回退）")
            )
        )

        assertTrue(QqPanelFields.displayWidth(normalized.description) <= QqPanelFields.MAX_DESCRIPTION_WIDTH)
        assertTrue(normalized.description.startsWith("查看在线玩家文字列表"))
    }

    @Test
    fun `rejects a command name that QQ cannot invoke intact`() {
        assertNull(QqPanelFields.normalize(QqPanelCommand("这是一个非常非常长的命令", "说明")))
    }

    @Test
    fun `keeps valid command text and collapses whitespace`() {
        assertEquals(
            QqPanelCommand("在线列表", "生成 在线玩家列表", true),
            QqPanelFields.normalize(QqPanelCommand(" 在线列表 ", "生成\n在线玩家列表", true))
        )
    }
}
