package cn.huohuas001.virga.server.inventory

import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.platform.wasRefused

/** Bridges the Java inventory data sources to [VirgaScheduler]. */
class SchedulerOnlinePlayerAccess(
    private val server: GameServer,
    private val scheduler: VirgaScheduler
) : OnlinePlayerAccess {
    override fun isGlobalThread(): Boolean = scheduler.isGlobalThread()

    override fun executeGlobal(task: Runnable): Boolean = !scheduler.global { task.run() }.wasRefused()

    override fun ownsPlayer(player: GamePlayer): Boolean = scheduler.isGlobalThread()

    override fun executeForPlayer(player: GamePlayer, task: Runnable, retired: Runnable): Boolean =
        scheduler.entity(player, { retired.run() }) { task.run() }

    override fun findExactPlayer(playerName: String): GamePlayer? = server.playerExact(playerName)
}
