package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.bot.provider.HExecution
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Remote console commands from QQ, executed on the server thread with an isolated source. */
class GameCommandExecution(
    private val server: GameServer,
    private val scheduler: VirgaScheduler,
    private val output: String = ""
) : HExecution {
    override fun getRawString(): String = output

    override fun execute(command: String): CompletableFuture<HExecution> {
        val clean = command.trim().removePrefix("/")
        if (clean.isBlank()) {
            return CompletableFuture.completedFuture(GameCommandExecution(server, scheduler, "命令不能为空"))
        }
        if (!pending.tryAcquire()) return CompletableFuture.failedFuture(IllegalStateException("Command feedback capacity reached"))
        return scheduler.supplyGlobal {
            val feedback = StringBuilder()
            var closed = false
            // The isolated source only captures this invocation, never global console/logger traffic.
            val accepted = server.dispatchCommand(clean) { line ->
                synchronized(feedback) {
                    if (!closed && feedback.length < 8192) {
                        feedback.append(line.take(8192 - feedback.length))
                        if (feedback.length < 8192) feedback.append('\n')
                    }
                }
            }
            // Some commands answer after dispatch returns; the timer only reads a private buffer.
            CompletableFuture.supplyAsync({
                val result = synchronized(feedback) { closed = true; feedback.toString() }
                if (!accepted && result.isBlank()) "服务器没有接受这条命令，Virga 也没办法。" else result
            }, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
        }.thenCompose { it }.thenApply<HExecution> { result ->
            GameCommandExecution(server, scheduler, result)
        }.whenComplete { _, _ -> pending.release() }
    }

    private companion object { val pending = Semaphore(64) }
}
