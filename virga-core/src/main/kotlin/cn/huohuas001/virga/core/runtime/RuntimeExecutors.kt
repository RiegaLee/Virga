package cn.huohuas001.virga.core.runtime

import cn.huohuas001.virga.core.VirgaLogger
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Shared, bounded background runtime used by QQ, state work and addon asynchronous tasks. */
class RuntimeExecutors(
    workerThreads: Int,
    queueCapacity: Int,
    private val scheduledTaskLimit: Int,
    private val logger: VirgaLogger,
    renderQueueCapacity: Int = 8,
    skinThreads: Int = 4,
    skinQueueCapacity: Int = 64,
    stateQueueCapacity: Int = 64
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scheduledTasks = AtomicInteger(0)
    private val worker = ThreadPoolExecutor(
        workerThreads,
        workerThreads,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(queueCapacity),
        NamedDaemonThreadFactory("virga-worker"),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val render = boundedPool(1, renderQueueCapacity, "virga-render")
    private val skin = boundedPool(skinThreads, skinQueueCapacity, "virga-skin")
    private val state = boundedPool(1, stateQueueCapacity, "virga-state")
    private val timer = ScheduledThreadPoolExecutor(
        1,
        NamedDaemonThreadFactory("virga-timer")
    ).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    fun execute(taskName: String, task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            worker.execute(guarded(taskName, task))
            true
        } catch (_: RejectedExecutionException) {
            logger.warning("后台队列已满，已拒绝任务：$taskName")
            false
        }
    }

    fun <T> submit(taskName: String, task: () -> T): CompletableFuture<T> {
        return submitTo(worker, taskName, task)
    }

    fun <T> submitRender(taskName: String, task: () -> T): CompletableFuture<T> =
        submitTo(render, taskName, task)

    fun <T> submitState(taskName: String, task: () -> T): CompletableFuture<T> =
        submitTo(state, taskName, task)

    fun <T> submitSkin(taskName: String, task: () -> T): CompletableFuture<T> =
        submitTo(skin, taskName, task)

    fun executeState(taskName: String, task: Runnable) {
        if (closed.get()) throw RejectedExecutionException("Virga runtime is closed")
        state.execute(guarded(taskName) { task.run() })
    }

    fun executeSkin(taskName: String, task: Runnable) {
        if (closed.get()) throw RejectedExecutionException("Virga runtime is closed")
        skin.execute(guarded(taskName) { task.run() })
    }

    private fun <T> submitTo(pool: ThreadPoolExecutor, taskName: String, task: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (closed.get()) {
            result.completeExceptionally(RejectedExecutionException("Virga runtime is closed"))
            return result
        }
        try {
            pool.execute(guarded(taskName) {
            try {
                result.complete(task())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
            })
        } catch (_: RejectedExecutionException) {
            logger.warning("后台队列已满，已拒绝任务：$taskName")
            result.completeExceptionally(RejectedExecutionException("Virga runtime rejected $taskName"))
        }
        return result
    }

    fun schedule(taskName: String, delay: Duration, task: () -> Unit): RuntimeTaskHandle {
        require(!delay.isNegative) { "delay must not be negative" }
        reserveScheduledSlot(taskName)
        val released = AtomicBoolean(false)
        val future = try {
            timer.schedule({
                try {
                    guarded(taskName, task).run()
                } finally {
                    releaseOnce(released)
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            releaseOnce(released)
            throw error
        }
        return RuntimeTaskHandle(future) { releaseOnce(released) }
    }

    fun scheduleAtFixedRate(
        taskName: String,
        initialDelay: Duration,
        period: Duration,
        task: () -> Unit
    ): RuntimeTaskHandle {
        require(!initialDelay.isNegative) { "initialDelay must not be negative" }
        require(!period.isZero && !period.isNegative) { "period must be positive" }
        reserveScheduledSlot(taskName)
        val released = AtomicBoolean(false)
        val future = try {
            timer.scheduleAtFixedRate(
                guarded(taskName, task),
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
            )
        } catch (error: Throwable) {
            releaseOnce(released)
            throw error
        }
        return RuntimeTaskHandle(future) { releaseOnce(released) }
    }

    private fun reserveScheduledSlot(taskName: String) {
        check(!closed.get()) { "Virga runtime is closed" }
        val count = scheduledTasks.incrementAndGet()
        if (count > scheduledTaskLimit) {
            scheduledTasks.decrementAndGet()
            throw RejectedExecutionException("Scheduled task limit reached for $taskName")
        }
    }

    private fun releaseOnce(released: AtomicBoolean) {
        if (released.compareAndSet(false, true)) scheduledTasks.decrementAndGet()
    }

    private fun guarded(taskName: String, task: () -> Unit) = Runnable {
        try {
            task()
        } catch (error: Throwable) {
            logger.error("后台任务执行失败：$taskName", error)
            if (error is VirtualMachineError || error is ThreadDeath || error is LinkageError) throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        worker.shutdownNow()
        render.shutdownNow()
        skin.shutdownNow()
        state.shutdown()
        timer.awaitTermination(5, TimeUnit.SECONDS)
        worker.awaitTermination(5, TimeUnit.SECONDS)
        render.awaitTermination(5, TimeUnit.SECONDS)
        skin.awaitTermination(5, TimeUnit.SECONDS)
        if (!state.awaitTermination(15, TimeUnit.SECONDS)) state.shutdownNow()
    }

    fun isClosed(): Boolean = closed.get()

    fun queuedTaskCount(): Int = worker.queue.size

    fun scheduledTaskCount(): Int = scheduledTasks.get()

    fun queuedRenderCount(): Int = render.queue.size

    fun queuedStateCount(): Int = state.queue.size

    private fun boundedPool(threads: Int, capacity: Int, name: String) = ThreadPoolExecutor(
        threads,
        threads,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(capacity),
        NamedDaemonThreadFactory(name),
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
}

class RuntimeTaskHandle internal constructor(
    private val future: ScheduledFuture<*>,
    private val onCancel: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun cancel(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        val cancelled = future.cancel(false)
        onCancel()
        return cancelled
    }

    fun isClosed(): Boolean = closed.get() || future.isCancelled || future.isDone

    override fun close() {
        cancel()
    }
}

private class NamedDaemonThreadFactory(private val prefix: String) : ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(task: Runnable): Thread = Thread(task, "$prefix-${sequence.incrementAndGet()}").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
    }
}
