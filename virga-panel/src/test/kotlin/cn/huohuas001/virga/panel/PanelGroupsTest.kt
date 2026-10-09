package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.groups.GroupIds
import cn.huohuas001.virga.panel.groups.GroupNotes
import cn.huohuas001.virga.panel.groups.RecentGroups
import cn.huohuas001.virga.panel.settings.PanelSettingsCatalog
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelGroupsTest {
    @Test
    fun `group ids are validated and shortened for display`() {
        assertTrue(GroupIds.valid("0A1B2C3D4E5F60718293A4B5C6D7E8F9"))
        assertFalse(GroupIds.valid("../etc/passwd"))
        assertFalse(GroupIds.valid("short"))
        assertEquals("0A1B2C…E8F9", GroupIds.display("0A1B2C3D4E5F60718293A4B5C6D7E8F9"))
    }

    @Test
    fun `recent groups keep only the newest entries`() {
        val clock = MutableClock()
        val recent = RecentGroups(capacity = 2, clock = clock)
        listOf("GROUPAAAA1", "GROUPBBBB2", "GROUPCCCC3").forEach {
            recent.record(it)
            clock.advance(Duration.ofSeconds(1))
        }
        recent.record("bad id")
        assertEquals(setOf("GROUPBBBB2", "GROUPCCCC3"), recent.snapshot().keys)
    }

    @Test
    fun `group events replace the last message and wake the page`() {
        val recent = RecentGroups()
        val waiting = recent.awaitChange(0)
        recent.recordNote("GROUPAAAA1", RecentGroups.MESSAGES_OFF_NOTE)
        assertTrue(waiting.isDone)
        assertEquals(RecentGroups.MESSAGES_OFF_NOTE, recent.snapshot()["GROUPAAAA1"]?.lastMessage)
        recent.recordNote("bad id", RecentGroups.REMOVED_NOTE)
        assertEquals(setOf("GROUPAAAA1"), recent.snapshot().keys)
    }

    @Test
    fun `message previews drop mention tags and are clipped`() {
        assertEquals(
            "Lee：/帮助 一下",
            RecentGroups.preview("Lee", "<@!0A1B2C3D4E5F60718293A4B5C6D7E8F9> /帮助\n\u0007 一下")
        )
        assertFalse(RecentGroups.preview("Lee", "<qqbot-at-user id=\"0A1B2C3D\" /> hi")!!.contains("0A1B2C3D"))
        assertEquals(RecentGroups.PREVIEW_LENGTH + 1, RecentGroups.preview(null, "字".repeat(80))!!.length)
        assertNull(RecentGroups.preview("Lee", "<@!0A1B2C3D>   "))
        val recent = RecentGroups()
        recent.record("GROUPAAAA1", "Lee", "你好")
        assertEquals("Lee：你好", recent.snapshot()["GROUPAAAA1"]!!.lastMessage)
    }

    @Test
    fun `notes are cleaned, persisted in UTF-8 and removable`() {
        val file = Files.createTempDirectory("panel").resolve("state/notes.properties")
        val notes = GroupNotes(file)
        notes.set("GROUPAAAA1", "  主群\u0007备注  ")
        notes.set("GROUPBBBB2", "x".repeat(100))
        assertEquals("主群备注", GroupNotes(file).get("GROUPAAAA1"))
        assertEquals(GroupNotes.MAX_LENGTH, GroupNotes(file).get("GROUPBBBB2")!!.length)
        notes.set("GROUPAAAA1", "")
        assertNull(GroupNotes(file).get("GROUPAAAA1"))
        assertEquals(setOf("GROUPBBBB2"), GroupNotes(file).ids())
    }

    @Test
    fun `settings catalog never exposes credentials or panel access`() {
        val keys = PanelSettingsCatalog.DEFINITIONS.map { it.key }
        assertTrue(keys.none { it.startsWith("bot.") || it.startsWith("administrators.") || it == "panel.enabled" || it == "panel.port" })
        assertFalse(PanelSettingsCatalog.isAllowed("bot.secret"))
    }
}
