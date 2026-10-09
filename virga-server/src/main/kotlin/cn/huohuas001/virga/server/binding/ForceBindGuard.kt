package cn.huohuas001.virga.server.binding

import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.game.GamePlayer
import java.time.Instant

/** Admission-only gate run after the vanilla login; no OP or QQ role bypass. */
internal class ForceBindGuard(
    private val settings: () -> VirgaSettings,
    authorityHandle: Any,
    private val scheduler: VirgaScheduler,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val bindingAvailable: () -> Boolean,
    private val qqConnected: () -> Boolean,
    private val authenticated: (GamePlayer) -> Boolean
) : AutoCloseable {
    private val authority = authorityHandle as StandaloneBindingAuthority
    @Volatile private var closed = false
    private val unavailableReported = java.util.concurrent.atomic.AtomicBoolean()
    private class Session(val player: GamePlayer) {
        override fun equals(other: Any?): Boolean = other is Session && player === other.player
        override fun hashCode(): Int = System.identityHashCode(player)
    }
    private val checking = java.util.concurrent.ConcurrentHashMap<Session, Boolean>()

    /** Called by the platform join callback on the server thread. */
    fun onJoin(player: GamePlayer) {
        scheduler.entity(player, retired = {}) { onAuthenticated(player) }
    }

    private fun operational(): Boolean = bindingAvailable() && qqConnected() &&
        settings().bot.allowedGroups.any { settings().isCommandAllowed(it, "绑定") }

    /** Returns true when the guard owns this prompt, including the already-bound case. */
    fun onAuthenticated(player: GamePlayer): Boolean {
        val current = settings()
        if (closed || !player.isOnline() || !current.binding.forceBind ||
            isBindingExempt(player.name, current.binding.verifyExempt) || !authenticated(player)) return false
        if (!operational()) {
            if (unavailableReported.compareAndSet(false, true)) {
                logger.warning("强制绑定暂时放行：机器人或绑定不可用，或没有开放绑定的群；配置开关保持不变。")
            }
            return false
        }
        unavailableReported.set(false)
        val name = player.name
        val uuid = player.uuid
        // Never rotate a token still being delivered.
        val session = Session(player)
        if (checking.putIfAbsent(session, true) != null) return true
        executors.submitState("检查入服绑定门槛") {
            if (closed || !settings().binding.forceBind || !operational()) return@submitState null
            authority.observeAuthenticatedPlayer(name, uuid)
            if (authority.hasVerifiedBinding(name, uuid)) null else authority.issueForceGameCode(name, uuid)
        }.whenComplete { result, error ->
            if (error != null) {
                checking.remove(session)
                logger.warning("强制绑定检查暂不可用，已临时放行；未创建绑定或权限。")
            } else if (result?.status == StandaloneBindingAuthority.GameCodeIssue.Status.CODE_AVAILABLE) {
                val accepted = scheduler.entity(player, retired = { checking.remove(session) }) {
                    try {
                        if (!closed && player.isOnline() && settings().binding.forceBind && operational() &&
                            !isBindingExempt(name, settings().binding.verifyExempt)) {
                            val seconds = BindingTimeDisplay.remainingSeconds(Instant.now(), result.expiresAt)
                            player.kick(forceBindingKickText(
                                settings().serverName, result.code, seconds, settings().binding.forceBindGroups
                            ))
                        }
                    } finally { checking.remove(session) }
                }
                if (!accepted) checking.remove(session)
            } else checking.remove(session)
        }
        return true
    }

    override fun close() { closed = true; checking.clear() }
}

internal fun isBindingExempt(name: String, names: Set<String>): Boolean = names.any { it.equals(name, ignoreCase = true) }

internal fun forceBindingKickText(server: String, code: String, seconds: Long, groups: List<String>): String {
    val destination = if (groups.isEmpty()) "指定玩家 QQ 群" else "QQ 群 ${groups.joinToString("、")}"
    return "$server · 需要绑定 QQ\n\n" +
        "先把 QQ 和游戏账号绑定好，再回来玩吧～\n" +
        "请在$destination @机器人发送：\n\n/绑定 $code\n\n" +
        "验证码 $seconds 秒内有效，绑定成功后重新入服。\n再次入服会刷新验证码。"
}
