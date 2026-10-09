package cn.huohuas001.virga.core.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomCommandTest {
    @Test fun `templates expand once and use verified names only`() {
        val command = CustomCommand("补给", "give {player} {0} {1}")
        assertEquals("give Lee minecraft:bread 2", command.expand("minecraft:bread 2", "Lee"))
        assertFailsWith<IllegalArgumentException> { command.expand("bread", "Lee") }
        assertFailsWith<IllegalArgumentException> { command.expand("bread 2", null) }
        assertFailsWith<IllegalArgumentException> { command.expand("bread 2", "@a") }
        assertEquals("say {player}", CustomCommand("通知", "say {params}", requireBinding = false).expand("{player}", null))
    }
    @Test fun `public parameters reject selectors quotes control text and payloads`() {
        val command = CustomCommand("补给", "give {player} bread {0}", permission = CustomCommandPermission.MEMBER)
        assertEquals("give Lee bread 2", command.expand("2", "Lee"))
        listOf("@a", "2\nstop", "\"2\"", "{}", "2;op", "{player}").forEach {
            assertFailsWith<IllegalArgumentException> { command.expand(it, "Lee") }
        }
    }
    @Test fun `validation rejects duplicate reserved dynamic protected and private placeholders`() {
        val valid = CustomCommand("补给", "give {player} minecraft:bread 1")
        CustomCommand.validate(listOf(valid), CoreCommandRouter.reservedTokens())
        assertFalse(valid.toString().contains("minecraft:bread"))
        listOf(CustomCommand("帮助", "list"), CustomCommand("补给", "{0} hi"),
            CustomCommand("补给", "virga passwd"), CustomCommand("补给", "execute run minecraft:stop"),
            CustomCommand("补给", "say {user}"), CustomCommand("补给", "say {group}"),
            CustomCommand("补给", "say hi\nstop"), CustomCommand("补给", "list", cooldownSeconds = 0)).forEach {
            assertFailsWith<IllegalArgumentException> { CustomCommand.validate(listOf(it), CoreCommandRouter.reservedTokens()) }
        }
        assertFailsWith<IllegalArgumentException> { CustomCommand.validate(listOf(valid, valid), emptySet()) }
    }
    @Test fun `safe feedback never exposes ids paths secrets stacks or command echo`() {
        listOf("member_openid=ABC", "A".repeat(32), "AppSecret: xyz", "D:\\plugins\\private.yml",
            "/home/minecraft/config.yml", "java.lang.IllegalArgumentException", "at File.kt:42",
            "https://host/private?token=x", "bearer anything", "<@abc>", "plugins/Virga/config.yml",
            "com.private.CommandHandler", "00000000-0000-0000-0000-000000000001").forEach {
            assertEquals(CommandFeedback.HIDDEN, CommandFeedback.safe(it))
        }
        assertEquals(CommandFeedback.HIDDEN, CommandFeedback.safe("opaque-secret", listOf("opaque-secret")))
        assertEquals(CommandFeedback.HIDDEN, CommandFeedback.safe("Executed give Lee bread 2", command = "give Lee bread 2"))
        assertEquals(CommandFeedback.EMPTY, CommandFeedback.safe(""))
        assertEquals("已给予 Lee 2 个面包", CommandFeedback.safe("§a已给予 Lee 2 个面包"))
        assertTrue(CommandFeedback.safe("行\n".repeat(1000)).lines().size <= 8)
    }
}
