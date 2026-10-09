package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.core.VirgaLogger
import io.github.kloping.qqbot.api.v2.GroupMemberEvent
import io.github.kloping.qqbot.impl.ListenerHost
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64
import java.util.Properties

enum class GroupMembershipStatus {
    PRESENT,
    ABSENT,
    UNKNOWN
}

fun GroupMembershipStatus.shouldAttemptMention(): Boolean = this != GroupMembershipStatus.ABSENT

/** Stores only membership facts observed from group traffic or official member events. */
class GroupMembershipTracker(
    private val stateFile: Path,
    private val logger: VirgaLogger
) : ListenerHost() {
    private data class Key(val groupOpenId: String, val userOpenId: String)
    private data class Record(
        val status: GroupMembershipStatus,
        val observedAt: Long,
        val displayName: String? = null
    )

    private val lock = Any()
    private val records = load()

    fun observe(message: BotMessage) {
        val senderOpenId = message.sender.openId?.trim().takeUnless { it.isNullOrEmpty() }
            ?: message.sender.id?.trim().orEmpty()
        mark(message.groupOpenId, senderOpenId, GroupMembershipStatus.PRESENT, message.sender.username)
        message.mentions.forEach { mention ->
            val mentionedOpenId = mention.openId?.trim().takeUnless { it.isNullOrEmpty() }
                ?: mention.id?.trim().orEmpty()
            mark(message.groupOpenId, mentionedOpenId, GroupMembershipStatus.PRESENT, mention.username)
        }
    }

    fun status(groupOpenId: String, userOpenId: String): GroupMembershipStatus = synchronized(lock) {
        records[Key(groupOpenId.trim(), userOpenId.trim())]?.status ?: GroupMembershipStatus.UNKNOWN
    }

    fun displayName(groupOpenId: String, userOpenId: String): String? = synchronized(lock) {
        records[Key(groupOpenId.trim(), userOpenId.trim())]?.displayName
    }

    /** A member the bot has seen in a group (spoke or was mentioned) and who has not left. */
    data class SeenMember(val userOpenId: String, val displayName: String?, val observedAtSeconds: Long)

    /** Members seen in [groupOpenId], most recently recorded first. */
    fun recentMembers(groupOpenId: String, limit: Int = 200): List<SeenMember> = synchronized(lock) {
        val group = groupOpenId.trim()
        records.entries
            .filter { it.key.groupOpenId == group && it.value.status != GroupMembershipStatus.ABSENT }
            .sortedByDescending { it.value.observedAt }
            .take(limit)
            .map { SeenMember(it.key.userOpenId, it.value.displayName, it.value.observedAt) }
    }

    @EventReceiver
    fun onGroupMemberEvent(event: GroupMemberEvent) {
        val userOpenId = event.memberOpenId?.trim().takeUnless { it.isNullOrEmpty() }
            ?: event.userOpenId?.trim().orEmpty()
        val status = if (event.isRemoved) GroupMembershipStatus.ABSENT else GroupMembershipStatus.PRESENT
        mark(event.groupOpenId.orEmpty(), userOpenId, status)
    }

    internal fun mark(
        groupOpenId: String,
        userOpenId: String,
        status: GroupMembershipStatus,
        displayName: String? = null
    ) {
        val key = Key(groupOpenId.trim(), userOpenId.trim())
        if (key.groupOpenId.isEmpty() || key.userOpenId.isEmpty()) return
        val safeDisplayName = displayName
            ?.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }
        synchronized(lock) {
            val previous = records[key]
            val effectiveDisplayName = safeDisplayName ?: previous?.displayName
            if (previous?.status == status && previous.displayName == effectiveDisplayName) return
            records[key] = Record(status, Instant.now().epochSecond, effectiveDisplayName)
            try {
                persist()
            } catch (error: Throwable) {
                if (previous == null) records.remove(key) else records[key] = previous
                logger.error("QQ群成员状态保存失败，已回退本次更新", error)
            }
        }
    }

    private fun load(): MutableMap<Key, Record> {
        if (!Files.isRegularFile(stateFile)) return linkedMapOf()
        return runCatching {
            val properties = Properties()
            Files.newInputStream(stateFile).use(properties::load)
            val loaded = linkedMapOf<Key, Record>()
            properties.stringPropertyNames()
                .filter { it.startsWith(MEMBER_PREFIX) }
                .forEach { property ->
                    val parts = property.removePrefix(MEMBER_PREFIX).split('.', limit = 2)
                    if (parts.size != 2) return@forEach
                    val value = properties.getProperty(property).orEmpty().split('|', limit = 3)
                    val status = runCatching { GroupMembershipStatus.valueOf(value.first()) }.getOrNull()
                        ?: return@forEach
                    if (status == GroupMembershipStatus.UNKNOWN) return@forEach
                    val key = Key(decode(parts[0]), decode(parts[1]))
                    if (key.groupOpenId.isEmpty() || key.userOpenId.isEmpty()) return@forEach
                    val displayName = value.getOrNull(2)
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::decode)
                        ?.takeIf(String::isNotEmpty)
                    loaded[key] = Record(status, value.getOrNull(1)?.toLongOrNull() ?: 0L, displayName)
                }
            loaded
        }.onFailure {
            logger.error("QQ群成员状态读取失败，将从未知状态重新学习", it)
        }.getOrDefault(linkedMapOf())
    }

    private fun persist() {
        val parent = stateFile.toAbsolutePath().parent
        Files.createDirectories(parent)
        val properties = Properties().apply {
            setProperty("version", "2")
            records.toSortedMap(compareBy<Key> { it.groupOpenId }.thenBy { it.userOpenId }).forEach { (key, record) ->
                setProperty(
                    "$MEMBER_PREFIX${encode(key.groupOpenId)}.${encode(key.userOpenId)}",
                    "${record.status.name}|${record.observedAt}|${record.displayName?.let(::encode).orEmpty()}"
                )
            }
        }
        val temporary = parent.resolve(stateFile.fileName.toString() + ".tmp")
        Files.newOutputStream(temporary).use { output: OutputStream ->
            properties.store(output, "Virga QQ group membership state")
        }
        try {
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String = runCatching {
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val MEMBER_PREFIX = "member."
    }
}
