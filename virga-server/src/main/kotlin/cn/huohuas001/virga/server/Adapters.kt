package cn.huohuas001.virga.server

import cn.huohuas001.virga.api.PluginDescriptor
import cn.huohuas001.virga.api.PluginLogger
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.api.TaskScheduler
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.core.runtime.RuntimeTaskHandle
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.time.Duration
import java.util.concurrent.CompletableFuture

class GameAddonScheduler(
    private val scheduler: VirgaScheduler,
    private val executors: RuntimeExecutors,
    private val logger: PluginLogger
) : TaskScheduler {
    // "Sync" addon work runs on the server thread.
    override fun runSync(task: Runnable): TaskHandle {
        val guardedTask = guarded(task)
        return scheduler.global { guardedTask.run() }
    }

    override fun runAsync(task: Runnable): TaskHandle {
        val future = CompletableFuture<Void>()
        val accepted = executors.execute("Addon 异步任务") {
            try {
                guarded(task).run()
                future.complete(null)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        if (!accepted) future.cancel(false)
        return FutureHandle(future)
    }

    override fun runLater(delay: Duration, task: Runnable): TaskHandle = RuntimeHandle(
        executors.schedule("Addon 延迟任务", delay) { guarded(task).run() }
    )

    override fun runTimer(initialDelay: Duration, period: Duration, task: Runnable): TaskHandle = RuntimeHandle(
        executors.scheduleAtFixedRate("Addon 定时任务", initialDelay, period) { guarded(task).run() }
    )

    private fun guarded(task: Runnable) = Runnable {
        try {
            task.run()
        } catch (error: Throwable) {
            logger.error("Addon scheduled task failed", error)
            if (error is VirtualMachineError || error is LinkageError) throw error
        }
    }
}

class GamePluginLogger(private val logger: cn.huohuas001.virga.core.VirgaLogger, descriptor: PluginDescriptor) : PluginLogger {
    private val prefix = "[addon:${descriptor.id}/${descriptor.version}] "
    override fun info(message: String) = logger.info(prefix + message)
    override fun warning(message: String) = logger.warning(prefix + message)
    override fun error(message: String, error: Throwable) = logger.error(prefix + message, error)
}

private class FutureHandle(private val future: CompletableFuture<*>) : TaskHandle {
    override fun cancel(): Boolean = future.cancel(false)
    override fun isClosed(): Boolean = future.isDone || future.isCancelled
    override fun close() { cancel() }
}

private class RuntimeHandle(private val handle: RuntimeTaskHandle) : TaskHandle {
    override fun cancel(): Boolean = handle.cancel()
    override fun isClosed(): Boolean = handle.isClosed()
    override fun close() = handle.close()
}
