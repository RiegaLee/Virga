package cn.huohuas001.virga.server

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.server.game.GameColor
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.game.GameText
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionException

/**
 * One-shot, in-game-confirmed enrollment for a QQ group.
 *
 * Unknown groups remain blocked unless a server administrator explicitly opens a short enrollment
 * window. Only an incoming group owner/administrator message with the fixed enrollment phrase can
 * become a candidate, and the player who opened the window must confirm it in game.
 */
internal class GroupEnrollmentService(
    private val server: GameServer,
    private val scheduler: VirgaScheduler,
    private val settings: () -> VirgaSettings,
    private val gateway: MessageGateway,
    private val logger: VirgaLogger,
    private val qqConnected: () -> Boolean,
    private val enrollGroup: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val lock = Any()
    private var waiting: WaitingSession? = null
    private var candidate: Candidate? = null

    fun begin(player: GamePlayer) {
        if (!qqConnected()) {
            player.send("&d[Virga]&r QQ 机器人还没连上，暂时不能接入群哦。")
            return
        }

        val now = clock()
        synchronized(lock) {
            cleanupExpired(now)
            val active = waiting
            if (active != null && active.playerId != player.uuid) {
                player.send("&d[Virga]&r 另一位管理员正在接入 QQ 群，等对方完成或取消吧。")
                return
            }
            waiting = WaitingSession(player.uuid, now + WAITING_MILLIS)
            candidate = null
        }

        player.send("&d[Virga]&r 已经打开 QQ 群接入窗口，10 分钟内有效～")
        player.send("&d[Virga]&r 请让目标群的群主或管理员在群里 @机器人 随便发一句话。")
        player.send("&d[Virga]&r 普通成员的消息不会进到游戏里；收到请求后还要由你确认。")
    }

    fun cancel(player: GamePlayer) {
        val cancelled = synchronized(lock) {
            cleanupExpired(clock())
            if (waiting?.playerId != player.uuid) false
            else {
                waiting = null
                candidate = null
                true
            }
        }
        player.send(if (cancelled) {
            "&d[Virga]&r 已经取消 QQ 群接入，配置没有改动。"
        } else {
            "&d[Virga]&r 你现在没有正在进行的 QQ 群接入哦。"
        })
    }

    /** Runs before the normal allowed-group gate. Returns true only when the message is consumed. */
    fun routeIngress(message: BotMessage): Boolean {
        if (settings().isAllowedGroup(message.groupOpenId)) return false
        if (!isEligibleEnrollmentMessage(message)) return false

        val captured = synchronized(lock) {
            val now = clock()
            cleanupExpired(now)
            val active = waiting ?: return@synchronized null
            if (candidate != null) return@synchronized null
            Candidate(
                id = UUID.randomUUID().toString().replace("-", "").take(10),
                playerId = active.playerId,
                groupOpenId = message.groupOpenId,
                senderName = safeDisplay(message.sender.username),
                senderRole = normalizedRole(message.sender.role),
                content = safeDisplay(message.content),
                expiresAt = now + CONFIRMATION_MILLIS
            ).also { candidate = it }
        } ?: return false

        gateway.replyText(message.toReference(), "接入请求已发送到游戏内，请等待服务器管理员确认。")
            .whenComplete { result, error ->
                if (error != null || result == null || !result.isSuccess) {
                    logger.warning("QQ 群接入等待回执发送失败：${error?.message ?: result?.diagnostic}")
                }
            }
        scheduler.global { showCandidate(captured) }
        logger.info("已捕获一条由 QQ 群主或管理员发送的群接入请求，等待游戏内确认。")
        return true
    }

    fun confirm(player: GamePlayer, requestId: String) {
        val captured = takeCandidate(player, requestId) ?: return
        // The allow list and config.yml are written on the server thread.
        scheduler.supplyGlobal { enrollGroup(captured.groupOpenId) }.whenComplete { added, failure ->
            if (failure == null) {
                player.send(if (added) {
                    "&d[Virga]&r QQ 群接好了，配置和白名单都已更新～"
                } else {
                    "&d[Virga]&r 这个 QQ 群本来就在名单里。"
                })
                return@whenComplete
            }
            val error = (failure as? CompletionException)?.cause ?: failure
            logger.error("保存 QQ 群接入配置失败", error)
            player.send("&d[Virga]&r 保存 QQ 群接入配置失败了：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun reject(player: GamePlayer, requestId: String) {
        if (takeCandidate(player, requestId) == null) return
        player.send("&d[Virga]&r 已经拒绝这次 QQ 群接入，配置没有改动。")
    }

    private fun showCandidate(captured: Candidate) {
        val current = synchronized(lock) {
            cleanupExpired(clock())
            candidate?.takeIf { it.id == captured.id }
        } ?: return
        val player = server.player(current.playerId)
        if (player == null || !player.isOnline() || !scheduler.entity(player, retired = { dropCandidate(current) }) { sendCandidate(player, current) }) {
            dropCandidate(current)
        }
    }

    private fun dropCandidate(current: Candidate) {
            synchronized(lock) {
                if (candidate?.id == current.id) {
                    candidate = null
                    waiting = null
                }
            }
    }

    private fun sendCandidate(player: GamePlayer, current: Candidate) {
        player.send(GameText.EMPTY)
        player.send(GameText.of("[Virga] 收到一条 QQ 群接入请求", GameColor.LIGHT_PURPLE))
        player.send(GameText.of("发送者：${current.senderName}", GameColor.GRAY))
        player.send(GameText.of("群身份：${roleDisplayName(current.senderRole)}", GameColor.GRAY))
        player.send(GameText.of("消息内容：${current.content}", GameColor.GRAY))
        player.send(
            GameText.of("[确认接入]", GameColor.GREEN, bold = true, click = GameText.Click.RunCommand("/virga group confirm ${current.id}")) +
                GameText.of("    ") +
                GameText.of("[取消]", GameColor.RED, bold = true, click = GameText.Click.RunCommand("/virga group reject ${current.id}"))
        )
        player.send(GameText.of("确认请求 2 分钟内有效。", GameColor.YELLOW))
    }

    private fun takeCandidate(player: GamePlayer, requestId: String): Candidate? = synchronized(lock) {
        cleanupExpired(clock())
        val current = candidate
        if (current == null || !current.id.equals(requestId, ignoreCase = true)) {
            player.send("&d[Virga]&r 这条接入请求不存在或者已经过期。")
            return@synchronized null
        }
        if (current.playerId != player.uuid) {
            player.send("&d[Virga]&r 没有权限。")
            return@synchronized null
        }
        candidate = null
        waiting = null
        current
    }

    private fun cleanupExpired(now: Long) {
        val active = waiting
        if (active != null && active.expiresAt < now) {
            waiting = null
            candidate = null
            return
        }
        if (candidate?.expiresAt?.let { it < now } == true) {
            candidate = null
            waiting = null
        }
    }

    override fun close() {
        synchronized(lock) {
            waiting = null
            candidate = null
        }
    }

    private data class WaitingSession(val playerId: UUID, val expiresAt: Long)

    private data class Candidate(
        val id: String,
        val playerId: UUID,
        val groupOpenId: String,
        val senderName: String,
        val senderRole: String,
        val content: String,
        val expiresAt: Long
    )

    private companion object {
        const val WAITING_MILLIS = 10 * 60 * 1_000L
        const val CONFIRMATION_MILLIS = 2 * 60 * 1_000L
    }
}

/**
 * While the in-game window is open, any message that @-mentions the bot from the group owner or a
 * group administrator is an enrollment request; the in-game admin still has to confirm it.
 */
internal fun isEligibleEnrollmentMessage(message: BotMessage): Boolean =
    normalizedRole(message.sender.role) in PRIVILEGED_QQ_ROLES && hasBotMention(message)

private fun hasBotMention(message: BotMessage): Boolean =
    message.mentions.any { it.isBot || it.isSelfMention } ||
        QQ_MENTION_PATTERN.containsMatchIn(message.content)

private fun normalizedRole(role: String?): String = role.orEmpty().trim().lowercase(Locale.ROOT)

private fun roleDisplayName(role: String): String = when (role) {
    "owner" -> "群主"
    "admin" -> "群管理员"
    else -> "未知"
}

/**
 * In-game display text. QQ mentions arrive as `<@member-openid>`; in an enrollment request they
 * address the bot, so they are shown as "@机器人" and the OpenID never reaches the screen.
 */
private fun safeDisplay(value: String): String = value
    .replace(QQ_MENTION_PATTERN, "@机器人")
    .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .replace('§', '＃')
    .trim()
    .take(120)
    .ifBlank { "（未提供）" }

private val PRIVILEGED_QQ_ROLES = setOf("owner", "admin")
private val QQ_MENTION_PATTERN = Regex("<@!?[^>]+>")
