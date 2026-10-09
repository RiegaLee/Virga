package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.auth.LoginThrottle
import cn.huohuas001.virga.panel.auth.PasswordHasher
import cn.huohuas001.virga.panel.auth.PasswordStore
import cn.huohuas001.virga.panel.auth.SessionManager
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableClock(var now: Instant = Instant.parse("2026-09-29T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class AuthTest {
    private val fastHasher = PasswordHasher(iterations = 1_000)

    @Test
    fun `hash verifies only the right password and never contains it`() {
        val encoded = fastHasher.hash("correct horse battery".toCharArray())
        assertTrue(encoded.startsWith("pbkdf2-sha256$1000$"))
        assertFalse("correct horse" in encoded)
        assertTrue(fastHasher.verify("correct horse battery".toCharArray(), encoded))
        assertFalse(fastHasher.verify("correct horse batterx".toCharArray(), encoded))
        assertFalse(fastHasher.verify("x".toCharArray(), "garbage"))
        assertNotEquals(encoded, fastHasher.hash("correct horse battery".toCharArray()), "salts must differ")
    }

    @Test
    fun `store generates a password once and persists only the digest`() {
        val file = Files.createTempDirectory("panel").resolve("state/panel-credentials.properties")
        val store = PasswordStore(file, fastHasher)
        val generated = assertNotNull(store.initialize())
        assertTrue(generated.length >= 24)
        assertFalse(generated in Files.readString(file))
        assertTrue(store.verify(generated.toCharArray()))

        val reopened = PasswordStore(file, fastHasher)
        assertNull(reopened.initialize(), "an existing digest must not be regenerated")
        assertTrue(reopened.verify(generated.toCharArray()))
    }

    @Test
    fun `password change requires the current password and a long enough new one`() {
        val store = PasswordStore(Files.createTempDirectory("panel").resolve("c.properties"), fastHasher)
        val generated = store.initialize()!!
        assertEquals(PasswordStore.ChangeResult.WRONG_PASSWORD, store.change("nope".toCharArray(), "a-long-new-password".toCharArray()))
        assertEquals(PasswordStore.ChangeResult.INVALID_NEW_PASSWORD, store.change(generated.toCharArray(), "short".toCharArray()))
        assertEquals(PasswordStore.ChangeResult.CHANGED, store.change(generated.toCharArray(), "a-long-new-password".toCharArray()))
        assertFalse(store.verify(generated.toCharArray()))
        assertTrue(store.verify("a-long-new-password".toCharArray()))
        val reset = store.reset()
        assertTrue(store.verify(reset.toCharArray()))
    }

    @Test
    fun `console can set a chosen password without the current one`() {
        val store = PasswordStore(Files.createTempDirectory("panel").resolve("c.properties"), fastHasher)
        val generated = store.initialize()!!
        assertEquals(PasswordStore.ChangeResult.INVALID_NEW_PASSWORD, store.set("short".toCharArray()))
        assertTrue(store.verify(generated.toCharArray()), "a rejected password must keep the old one")
        assertEquals(PasswordStore.ChangeResult.CHANGED, store.set("my-own-console-pass".toCharArray()))
        assertFalse(store.verify(generated.toCharArray()))
        assertTrue(store.verify("my-own-console-pass".toCharArray()))
    }

    @Test
    fun `sessions expire when idle or too old and can be revoked`() {
        val clock = MutableClock()
        val sessions = SessionManager(clock, Duration.ofHours(1), Duration.ofHours(12))
        val token = sessions.issue()
        assertTrue(sessions.validate(token))
        clock.advance(Duration.ofMinutes(59))
        assertTrue(sessions.validate(token), "activity refreshes the idle timer")
        clock.advance(Duration.ofMinutes(61))
        assertFalse(sessions.validate(token), "idle for more than an hour")

        val busy = sessions.issue()
        repeat(13) {
            clock.advance(Duration.ofMinutes(59))
            sessions.validate(busy)
        }
        assertFalse(sessions.validate(busy), "absolute lifetime is 12 hours even when active")

        val a = sessions.issue()
        val b = sessions.issue()
        sessions.revoke(a)
        assertFalse(sessions.validate(a))
        assertTrue(sessions.validate(b))
        sessions.revokeAll()
        assertFalse(sessions.validate(b))
        assertFalse(sessions.validate(null))
        assertFalse(sessions.validate("forged"))
    }

    @Test
    fun `login throttle backs off exponentially after free attempts`() {
        val clock = MutableClock()
        val throttle = LoginThrottle(clock, freeAttempts = 3, maxDelay = Duration.ofSeconds(8))
        repeat(3) { throttle.recordFailure("127.0.0.1") }
        assertEquals(Duration.ZERO, throttle.retryAfter("127.0.0.1"))
        throttle.recordFailure("127.0.0.1")
        assertEquals(Duration.ofSeconds(1), throttle.retryAfter("127.0.0.1"))
        throttle.recordFailure("127.0.0.1")
        assertEquals(Duration.ofSeconds(2), throttle.retryAfter("127.0.0.1"))
        repeat(5) { throttle.recordFailure("127.0.0.1") }
        assertEquals(Duration.ofSeconds(8), throttle.retryAfter("127.0.0.1"), "capped")
        clock.advance(Duration.ofSeconds(8))
        assertEquals(Duration.ZERO, throttle.retryAfter("127.0.0.1"))
        throttle.recordSuccess("127.0.0.1")
        throttle.recordFailure("127.0.0.1")
        assertEquals(Duration.ZERO, throttle.retryAfter("127.0.0.1"), "success resets the count")
    }
}
