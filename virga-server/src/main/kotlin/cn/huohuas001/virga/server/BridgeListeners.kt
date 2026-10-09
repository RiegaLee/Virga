package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.bot.QClient
import cn.huohuas001.virga.core.config.VirgaSettings

/** Game → QQ relays, called by the platform chat/join/leave events. */
class GameEventBridge(private val settings: () -> VirgaSettings) {
    fun onChat(playerName: String, raw: String) {
        val bridge = settings().bridge
        if (!bridge.gameToQq || !raw.startsWith(bridge.gamePrefix)) return
        val message = raw.removePrefix(bridge.gamePrefix)
        val text = bridge.gameToQqFormat
            .replace("{name}", playerName)
            .replace("{message}", message)
        settings().bot.allowedGroups.forEach { QClient.sendText(it, text) }
    }

    fun onJoin(playerName: String) {
        val current = settings()
        if (!current.playerNotices.joinEnabled) return
        val text = current.playerNotices.joinFormat
            .replace("{name}", playerName)
            .replace("{server}", current.serverName)
        current.bot.allowedGroups.forEach { QClient.sendText(it, text) }
    }

    fun onQuit(playerName: String) {
        val current = settings()
        if (!current.playerNotices.quitEnabled) return
        val text = current.playerNotices.quitFormat
            .replace("{name}", playerName)
            .replace("{server}", current.serverName)
        current.bot.allowedGroups.forEach { QClient.sendText(it, text) }
    }
}
