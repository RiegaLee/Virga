package cn.huohuas001.virga.panel.groups

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

object GroupIds {
    private val VALID = Regex("[A-Za-z0-9_-]{8,64}")

    fun valid(id: String): Boolean = VALID.matches(id)

    /** Group OpenIDs are shown shortened so screenshots do not leak them. */
    fun display(id: String): String = if (id.length <= 10) id else id.take(6) + "…" + id.takeLast(4)
}

/**
 * Groups that recently sent messages (allowed or not), newest kept, bounded. The last message
 * preview (sender nickname and text) only lives in memory so unnamed groups can be recognised.
 */
class RecentGroups(private val capacity: Int = 50, private val clock: Clock = Clock.systemUTC()) {
    data class Seen(val lastSeenMillis: Long, val lastMessage: String?)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val version = AtomicLong()
    private val waiters = ConcurrentLinkedQueue<CompletableFuture<Long>>()

    fun record(groupOpenId: String, senderName: String? = null, content: String? = null) =
        put(groupOpenId, preview(senderName, content))

    /** The bot was just added to this group: list it before anyone speaks. */
    fun recordJoin(groupOpenId: String) = put(groupOpenId, JOINED_NOTE)

    /** A group event without a message (removed, messages switched off/on), shown in place of the last message. */
    fun recordNote(groupOpenId: String, note: String) = put(groupOpenId, note)

    private fun put(groupOpenId: String, lastMessage: String?) {
        if (!GroupIds.valid(groupOpenId)) return
        seen[groupOpenId] = Seen(clock.millis(), lastMessage)
        if (seen.size > capacity) {
            seen.entries.sortedBy { it.value.lastSeenMillis }.take(seen.size - capacity).forEach { seen.remove(it.key, it.value) }
        }
        changed()
    }

    fun snapshot(): Map<String, Seen> = HashMap(seen)

    /** Anything shown on the group page changed (activity, allow list, notes): wake waiting pages. */
    fun changed() {
        val current = version.incrementAndGet()
        while (true) waiters.poll()?.complete(current) ?: break
    }

    /**
     * Long poll: completes with the new version as soon as it differs from [since], so the
     * page updates instantly without holding an HTTP thread. Callers add their own timeout.
     */
    fun awaitChange(since: Long): CompletableFuture<Long> {
        val current = version.get()
        if (current != since) return CompletableFuture.completedFuture(current)
        val waiter = CompletableFuture<Long>()
        waiters += waiter
        waiter.whenComplete { _, _ -> waiters.remove(waiter) }
        // Re-check after registering so a change racing with the add is never missed.
        val latest = version.get()
        if (latest != since) waiter.complete(latest)
        return waiter
    }

    companion object {
        const val PREVIEW_LENGTH = 40
        const val JOINED_NOTE = "（机器人刚被拉进这个群）"
        const val REMOVED_NOTE = "（机器人已被移出这个群，已自动取消允许）"
        const val MESSAGES_OFF_NOTE = "（群管理员关闭了机器人消息，已自动取消允许）"
        const val MESSAGES_ON_NOTE = "（群管理员重新开启了机器人消息，需要时请重新允许）"
        // QQ mention / markup tags such as <@!123> or <qqbot-at-user id="..."/> carry OpenIDs.
        private val TAGS = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")

        fun preview(senderName: String?, content: String?): String? {
            val text = content?.replace(TAGS, " ")?.filterNot { it.isISOControl() }?.replace(SPACES, " ")?.trim().orEmpty()
            if (text.isEmpty()) return null
            val name = senderName?.filterNot { it.isISOControl() }?.trim()?.take(16).orEmpty()
            val clipped = if (text.length > PREVIEW_LENGTH) text.take(PREVIEW_LENGTH) + "…" else text
            return if (name.isEmpty()) clipped else "$name：$clipped"
        }
    }
}

/** Local, panel-only remarks for groups, stored in `state/panel-group-notes.properties`. */
class GroupNotes(private val file: Path) {
    private val notes = ConcurrentHashMap<String, String>().apply {
        if (Files.isRegularFile(file)) {
            val properties = Properties()
            Files.newBufferedReader(file).use(properties::load)
            properties.stringPropertyNames().filter(GroupIds::valid).forEach { put(it, properties.getProperty(it)) }
        }
    }

    fun get(groupOpenId: String): String? = notes[groupOpenId]

    fun ids(): Set<String> = notes.keys.toSet()

    @Synchronized
    fun set(groupOpenId: String, note: String) {
        require(GroupIds.valid(groupOpenId)) { "群标识无效" }
        val cleaned = clean(note)
        if (cleaned.isEmpty()) notes.remove(groupOpenId) else notes[groupOpenId] = cleaned
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "panel-group-notes-", ".tmp")
        try {
            val properties = Properties().apply { notes.forEach { (id, text) -> setProperty(id, text) } }
            Files.newBufferedWriter(temporary).use { properties.store(it, "Virga panel group notes") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val MAX_LENGTH = 40

        fun clean(note: String): String =
            note.filterNot { it.isISOControl() }.trim().take(MAX_LENGTH)
    }
}
