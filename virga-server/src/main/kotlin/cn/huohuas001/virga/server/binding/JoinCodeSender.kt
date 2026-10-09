package cn.huohuas001.virga.server.binding

import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.time.Instant
import java.util.concurrent.CompletionException

/**
 * Delivers binding codes in game. A modded server has no login plugin: the vanilla session
 * check is the authentication, so an unbound player receives a code right after joining.
 */
internal class JoinCodeSender(
    private val scheduler: VirgaScheduler,
    private val authority: StandaloneBindingAuthority,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val messages: () -> MessageCatalog,
    private val enabled: () -> Boolean,
    private val onJoined: (GamePlayer) -> Boolean
) : AutoCloseable {
    @Volatile
    private var closed = false

    /** `/authcode`: revokes the previous code and shows a fresh one. */
    fun sendGameCode(player: GamePlayer) {
        val name = player.name
        val uuid = player.uuid
        executors.submitState("轮换绑定验证码") {
            authority.rotateGameCode(name, uuid)
        }.whenComplete { result, error -> sendIssue(player, result, error) }
    }

    /** Called on the server thread when a player has joined. */
    fun onJoin(player: GamePlayer) {
        if (closed || !enabled()) return
        if (onJoined(player)) return
        val name = player.name
        val uuid = player.uuid
        executors.submitState("进服后自动发放绑定码") {
            authority.observeAuthenticatedPlayer(name, uuid)
            if (authority.hasVerifiedBinding(name)) null else authority.issueGameCode(name, uuid)
        }.whenComplete { result, error ->
            if (result != null || error != null) sendIssue(player, result, error)
        }
    }

    private fun sendIssue(
        player: GamePlayer,
        result: StandaloneBindingAuthority.GameCodeIssue?,
        error: Throwable?
    ) {
        scheduler.entity(player, retired = {}) { sendIssueToPlayer(player, result, error) }
    }

    private fun sendIssueToPlayer(
        player: GamePlayer,
        result: StandaloneBindingAuthority.GameCodeIssue?,
        error: Throwable?
    ) {
        if (closed || !player.isOnline()) return
        val catalog = messages().binding
        val text = if (error != null || result == null) {
            if (error != null) logger.error("生成绑定验证码失败", unwrap(error))
            catalog.codeFailed
        } else when (result.status) {
            StandaloneBindingAuthority.GameCodeIssue.Status.CODE_AVAILABLE -> {
                val seconds = BindingTimeDisplay.remainingSeconds(Instant.now(), result.expiresAt)
                messages().render(
                    if (result.reused) catalog.codeReused else catalog.codeCreated,
                    mapOf("code" to result.code, "seconds" to seconds)
                )
            }
            StandaloneBindingAuthority.GameCodeIssue.Status.CONFIRMATION_PENDING -> catalog.confirmationPendingGame
            StandaloneBindingAuthority.GameCodeIssue.Status.ALREADY_BOUND -> catalog.alreadyBound
            else -> catalog.codeFailed
        }
        val issuedCode = result
            ?.takeIf { it.status == StandaloneBindingAuthority.GameCodeIssue.Status.CODE_AVAILABLE }
            ?.code
        player.send(bindingGameMessage(messages().decorate(text), issuedCode))
    }

    override fun close() {
        closed = true
    }

    private fun unwrap(error: Throwable?): Throwable {
        var current = error ?: IllegalStateException("unknown failure")
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }
}
