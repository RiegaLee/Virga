package cn.huohuas001.virga.core.qq

import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.api.v2.GroupMemberEvent
import io.github.kloping.qqbot.entities.qqpd.message.RawMessage
import io.github.kloping.qqbot.impl.registers.GroupMemberEventRegister
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupMemberEventRegisterTest {
    @Test
    fun `register decodes ordinary group member add and remove payloads`() {
        val data = JSONObject().apply {
            put("group_openid", "group-a")
            put("member_openid", "member-a")
            put("user_openid", "user-a")
            put("timestamp", 123456L)
        }
        val register = GroupMemberEventRegister()

        val added = register.handle(GroupMemberEventRegister.GROUP_MEMBER_ADD, data, RawMessage()) as GroupMemberEvent
        assertEquals("group-a", added.groupOpenId)
        assertEquals("member-a", added.memberOpenId)
        assertEquals("user-a", added.userOpenId)
        assertEquals(123456L, added.timestamp)
        assertFalse(added.isRemoved)

        val removed = register.handle(GroupMemberEventRegister.GROUP_MEMBER_REMOVE, data, RawMessage()) as GroupMemberEvent
        assertTrue(removed.isRemoved)
    }
}
