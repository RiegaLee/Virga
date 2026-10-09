package cn.huohuas001.virga.panel.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** In-memory panel sessions with idle and absolute expiry; nothing survives a restart. */
class SessionManager(
    private val clock: Clock = Clock.systemUTC(),
    private val idleTimeout: Duration = Duration.ofHours(1),
    private val absoluteTimeout: Duration = Duration.ofHours(12),
    private val maxSessions: Int = 16,
    private val random: SecureRandom = SecureRandom()
) {
    private data class Session(val createdAt: Instant, @Volatile var lastSeen: Instant)

    // Keyed by a SHA-256 of the token so a heap dump does not reveal live bearer tokens.
    private val sessions = ConcurrentHashMap<String, Session>()

    fun issue(): String {
        purgeExpired()
        while (sessions.size >= maxSessions) {
            sessions.entries.minByOrNull { it.value.lastSeen }?.let { sessions.remove(it.key) } ?: break
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val now = clock.instant()
        sessions[key(token)] = Session(now, now)
        return token
    }

    /** True when the token is live; refreshes its idle timer. */
    fun validate(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length > 128) return false
        val id = key(token)
        val session = sessions[id] ?: return false
        val now = clock.instant()
        if (expired(session, now)) {
            sessions.remove(id)
            return false
        }
        session.lastSeen = now
        return true
    }

    fun revoke(token: String?) {
        if (!token.isNullOrBlank()) sessions.remove(key(token))
    }

    fun revokeAll() = sessions.clear()

    fun activeCount(): Int {
        purgeExpired()
        return sessions.size
    }

    private fun purgeExpired() {
        val now = clock.instant()
        sessions.entries.removeIf { expired(it.value, now) }
    }

    private fun expired(session: Session, now: Instant): Boolean =
        now.isAfter(session.lastSeen.plus(idleTimeout)) || now.isAfter(session.createdAt.plus(absoluteTimeout))

    private fun key(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
}
