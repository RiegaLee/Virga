package cn.huohuas001.virga.server.platform

import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.server.game.GamePlayer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only way Virga hops onto the game thread. A dedicated server ticks every
 * level on one "Server thread"; Virga drains its queue once per tick from the
 * end-of-tick server event, so tasks never interleave with vanilla logic mid-tick.
 *
 * Tick arguments below 1 are raised to 1. Callers must never block on the returned
 * futures with `get()` or `join()`: the game thread only produces immutable snapshots.
 */
interface VirgaScheduler {
    val platformName: String

    /** Runs on the server thread on the next tick. */
    fun global(task: () -> Unit): TaskHandle

    fun globalLater(ticks: Long, task: () -> Unit): TaskHandle

    fun globalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle

    /**
     * Runs [task] for [player] on the server thread; [retired] runs instead when the player
     * has left by then. Returns false when the player is already gone or the scheduler is closed.
     */
    fun entity(player: GamePlayer, retired: () -> Unit, task: () -> Unit): Boolean

    /** Evaluates [action] for an online [player], inline when already on the server thread. */
    fun <T> supplyForPlayer(player: GamePlayer, action: (GamePlayer) -> T): CompletableFuture<T>

    /** Evaluates [action] on the server thread, inline when already there. */
    fun <T> supplyGlobal(action: () -> T): CompletableFuture<T>

    fun isGlobalThread(): Boolean

    /** Cancels every pending and repeating task; later submissions are refused. Idempotent. */
    fun cancelAll()
}

class PlayerUnavailableException(val playerName: String) :
    IllegalStateException("玩家 $playerName 已经离开服务器，Virga 读不到他的数据了")

class SchedulerClosedException : IllegalStateException("Virga 调度层已经关闭")

/** True when the scheduler refused this task (closed); false once accepted, even if already run. */
fun TaskHandle.wasRefused(): Boolean = this === RefusedHandle

internal object RefusedHandle : TaskHandle {
    override fun cancel(): Boolean = false
    override fun isClosed(): Boolean = true
    override fun close() = Unit
}

/**
 * Tick-driven implementation. [tick] must be called by the platform once per server tick on
 * the server thread; [isServerThread] identifies that thread.
 *
 * Since 1.21.2 an empty server pauses ticking (`pause-when-empty-seconds`), but it still runs
 * tasks handed to its executor. [wake] is that executor: next-tick work submitted from other
 * threads (panel requests, QQ commands) goes through it so a paused server keeps answering.
 * Delayed and repeating tasks keep counting real server ticks.
 */
class TickScheduler(
    override val platformName: String,
    private val isServerThread: () -> Boolean,
    private val onError: (String, Throwable) -> Unit,
    private val wake: ((Runnable) -> Unit)? = null
) : VirgaScheduler {
    private val closed = AtomicBoolean(false)
    private val incoming = ConcurrentLinkedQueue<Scheduled>()
    /**
     * Only touched on the server thread. Ordered by due tick, then by arrival, so a tick only
     * looks at the head instead of scanning every pending task.
     */
    private val waiting = java.util.PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.dueTick }, { it.sequence }))
    private var nextSequence = 0L
    @Volatile
    private var currentTick = 0L

    private inner class Scheduled(
        var dueTick: Long,
        val period: Long,
        val action: () -> Unit
    ) : TaskHandle {
        var sequence = 0L
        val cancelled = AtomicBoolean(false)
        override fun cancel(): Boolean = cancelled.compareAndSet(false, true)
        override fun isClosed(): Boolean = cancelled.get() || closed.get()
        override fun close() { cancel() }
    }

    override fun global(task: () -> Unit): TaskHandle = schedule(1L, 0L, task)

    override fun globalLater(ticks: Long, task: () -> Unit): TaskHandle = schedule(ticks.coerceAtLeast(1L), 0L, task)

    override fun globalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle =
        schedule(delayTicks.coerceAtLeast(1L), periodTicks.coerceAtLeast(1L), task)

    private fun schedule(delay: Long, period: Long, task: () -> Unit): TaskHandle {
        if (closed.get()) return RefusedHandle
        val scheduled = Scheduled(currentTick + delay, period, task)
        val executor = wake
        if (executor != null && delay == 1L && period == 0L && !isServerThread()) {
            try {
                executor(Runnable { runOnce(scheduled) })
                return scheduled
            } catch (_: RuntimeException) {
                // Executor unavailable (stopping): fall back to the tick queue.
            }
        }
        incoming.add(scheduled)
        return scheduled
    }

    /** Runs a one-shot task handed to [wake]; cancellation and shutdown still apply. */
    private fun runOnce(task: Scheduled) {
        if (closed.get() || !task.cancelled.compareAndSet(false, true)) return
        try {
            task.action()
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            onError("Virga 计划任务执行失败", error)
        }
    }

    override fun entity(player: GamePlayer, retired: () -> Unit, task: () -> Unit): Boolean {
        if (closed.get() || !player.isOnline()) return false
        return !global { if (player.isOnline()) task() else retired() }.wasRefused()
    }

    override fun <T> supplyForPlayer(player: GamePlayer, action: (GamePlayer) -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (closed.get()) return result.also { it.completeExceptionally(SchedulerClosedException()) }
        if (!player.isOnline()) return result.also { it.completeExceptionally(PlayerUnavailableException(player.name)) }
        if (isGlobalThread()) {
            complete(result) { action(player) }
            return result
        }
        val accepted = entity(player, { result.completeExceptionally(PlayerUnavailableException(player.name)) }) {
            complete(result) { action(player) }
        }
        if (!accepted) {
            result.completeExceptionally(if (closed.get()) SchedulerClosedException() else PlayerUnavailableException(player.name))
        }
        return result
    }

    override fun <T> supplyGlobal(action: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (closed.get()) return result.also { it.completeExceptionally(SchedulerClosedException()) }
        if (isGlobalThread()) {
            complete(result, action)
            return result
        }
        if (global { complete(result, action) }.wasRefused()) result.completeExceptionally(SchedulerClosedException())
        return result
    }

    private fun <T> complete(result: CompletableFuture<T>, action: () -> T) {
        try {
            result.complete(action())
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            if (error is VirtualMachineError) throw error
        }
    }

    override fun isGlobalThread(): Boolean = isServerThread()

    /** Called by the platform at the end of every server tick, on the server thread. */
    fun tick() {
        if (closed.get()) return
        val now = ++currentTick
        while (true) enqueue(incoming.poll() ?: break)
        // Cancelled long timers would otherwise wait for their due tick before being dropped.
        if (now % PURGE_PERIOD == 0L) waiting.removeIf { it.cancelled.get() }
        if (waiting.isEmpty() || waiting.peek().dueTick > now) return
        val due = ArrayList<Scheduled>()
        while (waiting.isNotEmpty() && waiting.peek().dueTick <= now) due += waiting.poll()
        for (task in due) {
            if (task.cancelled.get() || closed.get()) continue
            try {
                task.action()
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                onError("Virga 计划任务执行失败", error)
            }
            if (task.period > 0 && !task.cancelled.get() && !closed.get()) {
                task.dueTick = now + task.period
                enqueue(task)
            }
        }
    }

    private fun enqueue(task: Scheduled) {
        task.sequence = nextSequence++
        waiting.add(task)
    }

    override fun cancelAll() {
        if (!closed.compareAndSet(false, true)) return
        while (true) (incoming.poll() ?: break).cancel()
        // `waiting` belongs to the server thread; cancelling marks entries so they never run.
        if (isServerThread()) {
            waiting.forEach { it.cancel() }
            waiting.clear()
        }
    }

    private companion object {
        const val PURGE_PERIOD = 1200L
    }
}
