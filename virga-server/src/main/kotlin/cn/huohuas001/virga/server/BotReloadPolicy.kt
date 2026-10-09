package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.config.BotSettings

/**
 * Keeps the live QQ SDK session intact while applying settings that are safe to refresh.
 * The SDK shutdown path terminates shared static executors and cannot be used as a reconnect primitive.
 */
internal object BotReloadPolicy {
    fun applyWithoutReconnect(previous: BotSettings, configured: BotSettings): BotSettings = BotSettings(
        enabled = previous.enabled,
        appId = previous.appId,
        secret = previous.secret,
        displayName = configured.displayName,
        allowedGroups = configured.allowedGroups,
        suppressSdkConsoleOutput = previous.suppressSdkConsoleOutput
    )

    fun requiresFullRestart(previous: BotSettings, configured: BotSettings): Boolean =
        previous.enabled != configured.enabled ||
            previous.appId != configured.appId ||
            previous.secret != configured.secret ||
            previous.suppressSdkConsoleOutput != configured.suppressSdkConsoleOutput
}
