package cn.huohuas001.virga.core.access

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.api.PrincipalRole
import cn.huohuas001.virga.core.config.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossGroupRootTest {
    private fun settings(roots: Set<String>, trust: Boolean = false) = VirgaSettings(
        1, BotSettings(true, "test-app", "test-secret", "测试机", setOf("a", "b"), true), "测试服", "",
        roots, trust, BridgeSettings(false, false, "", "", ""),
        PlayerNoticeSettings(false, false, "", ""), RemoteCommandSettings(false, true),
        RuntimeSettings(1, 8, 8, 30, 1024)
    )
    private fun message(group: String, member: String, role: String = "MEMBER") = BotMessage(
        "message", group, group, SenderSnapshot("sdk-id", member, "same-nickname", role),
        "", "", "", 1, emptyList(), emptyList()
    )

    @Test fun `configured member root applies in both groups without game binding or union`() {
        var current = settings(setOf("root"))
        val access = AccessControl({ current }, InMemoryAdministratorRepository())
        assertEquals(PrincipalRole.OWNER, access.role(message("a", "root")))
        assertEquals(PrincipalRole.OWNER, access.role(message("b", "root")))
        assertFalse(access.isRoot("sdk-id"))
        assertFalse(access.canRemove("root"))
        current = settings(emptySet())
        assertFalse(access.isRoot("root"))
    }

    @Test fun `group owners and local administrators never become root or gain cross group admin`() {
        val repository = InMemoryAdministratorRepository(mapOf("a" to setOf("local-admin")))
        val access = AccessControl({ settings(setOf("root")) }, repository)
        assertTrue(access.isAdministrator(message("a", "local-admin")))
        assertFalse(access.isAdministrator(message("b", "local-admin")))
        assertFalse(access.isRoot("local-admin"))
        assertFalse(access.isAdministrator(message("b", "another", "OWNER")))
        val trusted = AccessControl({ settings(setOf("root"), true) }, repository)
        assertEquals(PrincipalRole.ADMIN, trusted.role(message("b", "another", "OWNER")))
        assertFalse(trusted.isRoot("another"))
    }
}
