package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.groups.GroupInfoCache
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupInfoCacheTest {
    private class TestClock : java.time.Clock() {
        @Volatile private var now = 0L
        override fun getZone() = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = java.time.Instant.ofEpochMilli(now)
        override fun millis() = now
        fun advance(duration: Duration) { now += duration.toMillis() }
    }
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `lookups are deduplicated, spaced out and notify on change`() {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        val updates = AtomicInteger()
        val cache = GroupInfoCache(
            fetch = { id -> calls += id to System.nanoTime(); CompletableFuture.completedFuture(GroupInfoCache.Info("群 $id", 12)) },
            onUpdate = { updates.incrementAndGet() },
            spacing = Duration.ofMillis(150)
        )
        try {
            repeat(3) { cache.request("GROUPAAAA1") }
            cache.request("GROUPBBBB2")
            cache.request("bad id")
            awaitUntil { cache.get("GROUPBBBB2") != null }
            assertEquals(listOf("GROUPAAAA1", "GROUPBBBB2"), calls.map { it.first }, "one call per group, invalid ids skipped")
            assertTrue(calls[1].second - calls[0].second >= Duration.ofMillis(140).toNanos(), "calls are spaced for the 30 QPM limit")
            assertEquals(GroupInfoCache.Info("群 GROUPAAAA1", 12), cache.get("GROUPAAAA1"))
            assertEquals(2, updates.get())
            cache.request("GROUPAAAA1")
            Thread.sleep(300)
            assertEquals(2, calls.size, "fresh entries are not fetched again")
        } finally {
            cache.close()
        }
    }

    @Test
    fun `failures keep the previous name and are retried later`() {
        val attempts = AtomicInteger()
        val cache = GroupInfoCache(
            fetch = { _ ->
                if (attempts.incrementAndGet() == 1) CompletableFuture.completedFuture(GroupInfoCache.Info("老群名", 5))
                else CompletableFuture.failedFuture(IllegalStateException("offline"))
            },
            onUpdate = {},
            spacing = Duration.ofMillis(10),
            refreshAfter = Duration.ZERO,
            retryAfter = Duration.ZERO
        )
        try {
            cache.request("GROUPAAAA1")
            awaitUntil { cache.get("GROUPAAAA1") != null }
            cache.request("GROUPAAAA1")
            awaitUntil { attempts.get() >= 2 }
            Thread.sleep(100)
            assertEquals("老群名", cache.get("GROUPAAAA1")?.name)
        } finally {
            cache.close()
        }
        val empty = GroupInfoCache(fetch = { CompletableFuture.completedFuture(null) }, onUpdate = {})
        empty.request("GROUPCCCC3")
        Thread.sleep(100)
        assertNull(empty.get("GROUPCCCC3"), "no QQ session means no name yet")
        empty.close()
    }

    @Test
    fun `renamed groups refresh within a minute and manual refresh is throttled`() {
        val clock = TestClock()
        val calls = AtomicInteger()
        val updates = AtomicInteger()
        val cache = GroupInfoCache(fetch = {
            CompletableFuture.completedFuture(GroupInfoCache.Info(if (calls.incrementAndGet() == 1) "旧群名" else "新群名", 8))
        }, onUpdate = { updates.incrementAndGet() }, clock = clock, spacing = Duration.ofMillis(10))
        try {
            cache.request("GROUPAAAA1")
            awaitUntil { cache.get("GROUPAAAA1")?.name == "旧群名" }
            repeat(5) { cache.request("GROUPAAAA1", force = true) }
            Thread.sleep(40)
            assertEquals(1, calls.get(), "manual refresh cannot spam QQ")
            clock.advance(Duration.ofSeconds(10))
            cache.request("GROUPAAAA1", force = true)
            awaitUntil { cache.get("GROUPAAAA1")?.name == "新群名" }
            assertEquals(2, updates.get())
            clock.advance(Duration.ofSeconds(61))
            cache.request("GROUPAAAA1")
            awaitUntil { calls.get() == 3 }
        } finally { cache.close() }
    }

    @Test
    fun `pending lookups are not requeued and failed auto refresh keeps its backoff`() {
        val clock = TestClock()
        val pending = CompletableFuture<GroupInfoCache.Info?>()
        val calls = AtomicInteger()
        val cache = GroupInfoCache(fetch = { calls.incrementAndGet(); pending }, onUpdate = {},
            clock = clock, spacing = Duration.ofMillis(10))
        try {
            cache.request("GROUPAAAA1")
            awaitUntil { calls.get() == 1 }
            repeat(10) { cache.request("GROUPAAAA1", force = true) }
            pending.completeExceptionally(IllegalStateException("offline"))
            Thread.sleep(40)
            assertEquals(1, calls.get())
            clock.advance(Duration.ofMinutes(1))
            cache.request("GROUPAAAA1")
            Thread.sleep(40)
            assertEquals(1, calls.get(), "failed lookup still backs off for five minutes")
        } finally { cache.close() }
    }
}
