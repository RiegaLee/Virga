package cn.huohuas001.virga.server

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.SenderSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupEnrollmentEligibilityTest {
    private fun message(role: String, content: String) = BotMessage(
        "message", "group", "group", SenderSnapshot("sdk-id", "member", "nickname", role),
        content, content, "", 1, emptyList(), emptyList()
    )

    @Test fun `any message that mentions the bot from the owner or an administrator is a request`() {
        listOf("<@bot> 接入本群", "<@bot> 你好", "<@bot>").forEach { content ->
            assertTrue(isEligibleEnrollmentMessage(message("owner", content)), content)
            assertTrue(isEligibleEnrollmentMessage(message("admin", content)), content)
        }
    }

    @Test fun `ordinary members and messages without a mention never qualify`() {
        assertFalse(isEligibleEnrollmentMessage(message("member", "<@bot> 接入本群")))
        assertFalse(isEligibleEnrollmentMessage(message("owner", "接入本群")))
    }
}
