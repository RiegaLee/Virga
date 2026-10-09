package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MentionSnapshot
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.core.VirgaLogger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupMembershipTrackerTest {
    @Test
    fun `unknown membership still attempts mention and only known absence suppresses it`() {
        assertTrue(GroupMembershipStatus.PRESENT.shouldAttemptMention())
        assertTrue(GroupMembershipStatus.UNKNOWN.shouldAttemptMention())
        assertFalse(GroupMembershipStatus.ABSENT.shouldAttemptMention())
    }

    @Test
    fun `unknown state is never guessed and observed facts survive restart`() {
        val directory = createTempDirectory("virga-membership-")
        val state = directory.resolve("members.properties")
        val tracker = GroupMembershipTracker(state, SilentLogger)

        assertEquals(GroupMembershipStatus.UNKNOWN, tracker.status("group-a", "user-a"))
        tracker.mark("group-a", "user-a", GroupMembershipStatus.PRESENT, "[插件管]_RiegaLee_")
        assertEquals(GroupMembershipStatus.PRESENT, tracker.status("group-a", "user-a"))
        assertEquals("[插件管]_RiegaLee_", tracker.displayName("group-a", "user-a"))

        val reloaded = GroupMembershipTracker(state, SilentLogger)
        assertEquals(GroupMembershipStatus.PRESENT, reloaded.status("group-a", "user-a"))
        assertEquals("[插件管]_RiegaLee_", reloaded.displayName("group-a", "user-a"))
        reloaded.mark("group-a", "user-a", GroupMembershipStatus.ABSENT)

        assertEquals(
            GroupMembershipStatus.ABSENT,
            GroupMembershipTracker(state, SilentLogger).status("group-a", "user-a")
        )
    }

    @Test
    fun `repeated leave and join transitions keep the latest ordered state`() {
        val state = createTempDirectory("virga-membership-rejoin-").resolve("members.properties")
        val tracker = GroupMembershipTracker(state, SilentLogger)

        tracker.mark("group-a", "user-a", GroupMembershipStatus.PRESENT)
        tracker.mark("group-a", "user-a", GroupMembershipStatus.ABSENT)
        tracker.mark("group-a", "user-a", GroupMembershipStatus.PRESENT)
        tracker.mark("group-a", "user-a", GroupMembershipStatus.ABSENT)

        assertEquals(GroupMembershipStatus.ABSENT, tracker.status("group-a", "user-a"))
        assertEquals(
            GroupMembershipStatus.ABSENT,
            GroupMembershipTracker(state, SilentLogger).status("group-a", "user-a")
        )
    }

    @Test
    fun `sender and explicitly mentioned members are observed as present`() {
        val state = createTempDirectory("virga-membership-mentions-").resolve("members.properties")
        val tracker = GroupMembershipTracker(state, SilentLogger)
        val message = BotMessage(
            "message-a",
            "group-a",
            "group-a",
            SenderSnapshot(null, "sender-a", "sender", null),
            "/查绑 @member",
            "/查绑 @member",
            null,
            1,
            listOf(MentionSnapshot(null, "member-a", "member", null)),
            emptyList()
        )

        tracker.observe(message)

        assertEquals(GroupMembershipStatus.PRESENT, tracker.status("group-a", "sender-a"))
        assertEquals(GroupMembershipStatus.PRESENT, tracker.status("group-a", "member-a"))
        assertEquals("sender", tracker.displayName("group-a", "sender-a"))
        assertEquals("member", tracker.displayName("group-a", "member-a"))
    }

    private object SilentLogger : VirgaLogger {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
