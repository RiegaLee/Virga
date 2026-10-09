package cn.huohuas001.virga.core.bot.addon

import cn.huohuas001.virga.core.bot.events.commands.RegisteredCommand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object AddonManager {
    private val addons = ConcurrentHashMap<String, Addon>()
    private val addonCommands = ConcurrentHashMap<String, CopyOnWriteArrayList<RegisteredCommand>>()

    val size: Int get() = addons.size

    @JvmOverloads
    fun register(addon: Addon, commands: List<RegisteredCommand> = emptyList()) {
        addons[addon.name] = addon
        commands.forEach { addCommand(addon.name, it) }
    }

    fun allAddons(): List<Addon> = addons.values.sortedBy { it.name }

    fun commandsOf(addonName: String): List<RegisteredCommand> = addonCommands[addonName]?.toList().orEmpty()

    fun addCommand(addonName: String, command: RegisteredCommand) {
        if (!addons.containsKey(addonName)) return
        val commands = addonCommands.computeIfAbsent(addonName) { CopyOnWriteArrayList() }
        if (commands.none { it.command == command.command }) commands += command
    }

    fun clear() {
        addonCommands.clear()
        addons.clear()
    }

    operator fun contains(name: String): Boolean = addons.containsKey(name)
}
