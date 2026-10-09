package cn.huohuas001.virga.panel.groups

import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ group names and member counts from `GET /v2/groups/{group_openid}/info`, fetched one at a
 * time with [spacing] between calls so the 30-per-minute limit is never reached. Results are
 * kept in memory; [onUpdate] wakes the group page when a name or count changes.
 */
class GroupInfoCache(
    private val fetch: (String) -> CompletableFuture<Info?>,
    private val onUpdate: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val spacing: Duration = Duration.ofMillis(2500),
    private val refreshAfter: Duration = Duration.ofMinutes(1),
    private val retryAfter: Duration = Duration.ofMinutes(5),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
    private val manualRefreshAfter: Duration = Duration.ofSeconds(10),
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "virga-panel-group-info").apply { isDaemon = true }
    }
) : AutoCloseable {
    data class Info(val name: String, val memberCount: Int?)

    private data class Entry(val info: Info?, val checkedAt: Long, val failed: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val queue = LinkedHashSet<String>()
    private var inFlight: String? = null // Guarded by queue; repeated page refreshes must not enqueue the active lookup.
    private val draining = AtomicBoolean(false)
    @Volatile private var closed = false

    fun get(groupOpenId: String): Info? = entries[groupOpenId]?.info

    /** Queues a lookup when the group has no fresh information (cheap to call on every event). */
    fun request(groupOpenId: String, force: Boolean = false) {
        if (closed || !GroupIds.valid(groupOpenId)) return
        val entry = entries[groupOpenId]
        val age = entry?.let { clock.millis() - it.checkedAt }
        val due = entry == null || age!! >= when {
            force -> manualRefreshAfter.toMillis()
            entry.failed -> retryAfter.toMillis()
            else -> refreshAfter.toMillis()
        }
        if (!due) return
        val added = synchronized(queue) { inFlight != groupOpenId && queue.add(groupOpenId) }
        if (added && draining.compareAndSet(false, true)) executor.execute(::drainNext)
    }

    private fun drainNext() {
        if (closed) return
        val next = synchronized(queue) { queue.firstOrNull()?.also { queue.remove(it); inFlight = it } }
        if (next == null) {
            draining.set(false)
            // A request may have slipped in between the empty check and the flag reset.
            if (synchronized(queue) { queue.isNotEmpty() } && draining.compareAndSet(false, true)) executor.execute(::drainNext)
            return
        }
        val previous = entries[next]
        val future = runCatching { fetch(next) }.getOrElse { CompletableFuture.failedFuture(it) }
        future.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { info, failure ->
            val usable = info?.takeIf { failure == null && it.name.isNotBlank() }
            entries[next] = Entry(usable ?: previous?.info, clock.millis(), usable == null)
            synchronized(queue) { inFlight = null }
            if (usable != null && usable != previous?.info) runCatching(onUpdate)
            if (!closed) executor.schedule(::drainNext, spacing.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }
}
