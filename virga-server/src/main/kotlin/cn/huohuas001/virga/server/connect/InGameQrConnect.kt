package cn.huohuas001.virga.server.connect

import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.panel.qr.QqBotQrConnector
import cn.huohuas001.virga.server.game.GameMapCanvas
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.MapHold
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * `/virga connect`: the panel's QR connect flow for servers whose console or Web panel is out
 * of reach. The QR code is painted on a locked map in the admin's hand, redrawn when QQ refreshes
 * it, and taken back once the scan finished. The credentials never appear in game or chat.
 *
 * Whoever scans the code decides which bot takes over the server, so the code must never leave
 * the admin: every tick the map is pulled back into the main hand (other slots, the cursor, a
 * fresh drop); if it left for good (a container, an item frame, death) the session is cancelled
 * and the map blanked, so no copy anywhere can still be scanned. A session lasts [SESSION].
 *
 * Server thread only, except the connector's own callbacks.
 */
class InGameQrConnect(
    private val server: GameServer,
    private val scheduler: VirgaScheduler,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val settings: () -> VirgaSettings,
    /** Saves AppID/Secret and connects; completes with a message for the admin. */
    /** Saves and connects; [initiator] is told how the QQ connection ended. */
    private val saveCredentials: (appId: String, secret: String, initiator: UUID) -> CompletableFuture<String>,
    private val connector: QqBotQrConnector = QqBotQrConnector(source = "Virga", sessionTimeout = SESSION)
) : AutoCloseable {
    private var canvas: GameMapCanvas? = null
    private var holder: UUID? = null
    private var shownUrl: String? = null
    private var watcher: TaskHandle? = null
    private var generation = 0
    private var ticks = 0L
    private var lastRestoreNotice = -60L

    fun begin(player: GamePlayer) {
        if (!settings().panel.qrConnectEnabled) {
            player.send("&d[Virga]&r 扫码连接在配置里关掉了（panel.qr-connect.enabled），可以在 /virga menu 里打开。")
            return
        }
        stop(if (holder != null && holder != player.uuid) "&d[Virga]&r 另一位管理员重新开始了扫码，你手上的二维码地图收回了。" else null)
        val session = ++generation
        holder = player.uuid
        player.send("&d[Virga]&r 正在向 QQ 要二维码，稍等一下～")
        executors.submit("申请 QQ 扫码连接") {
            connector.start { credentials ->
                saveCredentials(credentials.appId, credentials.appSecret, player.uuid).whenComplete { message, error ->
                    scheduler.global {
                        val target = holder?.let(server::player)
                        if (error != null) {
                            logger.error("游戏内扫码成功，但保存 QQ 凭据失败", error)
                            target?.send("&d[Virga]&r 扫码成功了，但凭据没保存好，看看控制台日志。")
                        } else {
                            target?.send("&d[Virga]&r $message")
                        }
                    }
                }
            }
        }.whenComplete { status, error ->
            scheduler.global {
                if (session != generation) return@global
                val target = server.player(player.uuid)
                if (error != null || status?.qrUrl == null) {
                    holder = null
                    val reason = (error?.cause ?: error)?.message ?: status?.message ?: "网络错误"
                    logger.warning("游戏内扫码连接没能拿到二维码：$reason")
                    target?.send("&d[Virga]&r 暂时拿不到二维码（$reason），过一会儿再试。")
                    return@global
                }
                if (target == null) {
                    connector.cancel()
                    holder = null
                    return@global
                }
                if (show(target, status.qrUrl!!)) {
                    ticks = 0L
                    watcher = scheduler.globalTimer(1L, 1L) { tick(session) }
                }
            }
        }
    }

    fun cancel(player: GamePlayer) {
        if (holder == null) {
            player.send("&d[Virga]&r 现在没有进行中的扫码。")
            return
        }
        stop(null)
        player.send("&d[Virga]&r 扫码取消了，二维码地图也收回了。")
    }

    /** Returns false when the map could not be handed over (the session is stopped then). */
    private fun show(player: GamePlayer, url: String): Boolean {
        val map = canvas ?: server.createMapCanvas().also { canvas = it }
        map.draw(QrMapImage.render(url))
        shownUrl = url
        map.takeBackFrom(player)
        val lore = listOf("&7用手机 QQ 扫一扫，确认后 Virga 就连上了", "&7只能拿在主手上；离手会立刻作废", "&72 分钟内有效，扫完自动收回")
        if (map.giveTo(player, "&dQQ 机器人扫码连接", lore)) {
            player.send("&d[Virga]&r 二维码画在手上的地图里了：用手机 QQ 扫一扫并确认～" +
                "（${SESSION.toMinutes()} 分钟内有效；地图锁在主手上，丢出、放进容器或展示框会立刻作废；/virga connect cancel 取消）")
            return true
        }
        player.send("&d[Virga]&r 背包满了，手上的东西没地方放，二维码地图递不过去；空出一格后再输入 /virga connect。")
        stop(null)
        return false
    }

    /** Every tick: keep the map in hand; once a second: follow the QQ side. */
    private fun tick(session: Int) {
        if (session != generation) return
        val player = holder?.let(server::player)
        val map = canvas
        if (player == null || map == null) {
            stop(null)
            return
        }
        when (map.keepInHand(player)) {
            MapHold.HELD -> Unit
            MapHold.RESTORED -> if (ticks - lastRestoreNotice >= 60L) {
                lastRestoreNotice = ticks
                player.send("&d[Virga]&r 二维码地图只能拿在主手上，已经放回你手里了。")
            }
            MapHold.LOST -> {
                logger.warning("游戏内扫码连接：${player.name} 手上的二维码地图离手，已作废这次扫码。")
                stop("&d[Virga]&r 二维码地图离开了你的手，为了安全这次扫码已经作废；需要的话重新输入 /virga connect。")
                return
            }
        }
        if (ticks++ % 20L == 0L) watch(session)
    }

    private fun watch(session: Int) {
        if (session != generation) return
        val player = holder?.let(server::player)
        if (player == null) {
            stop(null)
            return
        }
        val status = connector.status()
        when (status.phase) {
            QqBotQrConnector.Phase.WAITING -> {
                val url = status.qrUrl
                if (url != null && url != shownUrl) {
                    canvas?.draw(QrMapImage.render(url))
                    shownUrl = url
                    player.send("&d[Virga]&r 二维码过期了，地图上已经换成新的。")
                }
            }
            QqBotQrConnector.Phase.COMPLETED -> {
                release(player)
                player.send("&d[Virga]&r 扫码成功～正在保存凭据并连接 QQ。")
            }
            QqBotQrConnector.Phase.FAILED, QqBotQrConnector.Phase.CANCELLED -> {
                release(player)
                player.send("&d[Virga]&r ${status.message}，二维码地图收回了。")
            }
            QqBotQrConnector.Phase.IDLE -> release(player)
        }
    }

    /** Ends the watch but keeps the connector's result (used once the scan itself finished). */
    private fun release(player: GamePlayer) {
        watcher?.cancel()
        watcher = null
        canvas?.takeBackFrom(player)
        canvas?.clear()
        holder = null
        shownUrl = null
    }

    private fun stop(notice: String?) {
        val previous = holder?.let(server::player)
        watcher?.cancel()
        watcher = null
        if (holder != null) connector.cancel()
        if (previous != null) {
            canvas?.takeBackFrom(previous)
            if (notice != null) previous.send(notice)
        }
        // Blank every copy, wherever it ended up.
        canvas?.clear()
        holder = null
        shownUrl = null
        generation++
    }

    override fun close() {
        watcher?.cancel()
        canvas?.clear()
        connector.close()
    }

    companion object {
        /** How long one QR session stays scannable (QQ refreshes the code inside it). */
        val SESSION: Duration = Duration.ofMinutes(2)
    }
}
