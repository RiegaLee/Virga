package cn.huohuas001.virga.core.bot.events.commands

import cn.huohuas001.virga.core.bot.provider.CustomCommandDetail
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object CustomCommandRegistry {
    private val commands = ConcurrentHashMap<String, CustomCommandDetail>()

    fun replace(values: List<CustomCommandDetail>) {
        commands.clear()
        values.forEach(::register)
    }

    fun register(value: CustomCommandDetail): Boolean =
        commands.putIfAbsent(normalize(value.key), value) == null

    fun unregister(key: String): Boolean = commands.remove(normalize(key)) != null

    fun find(key: String): CustomCommandDetail? = commands[normalize(key)]

    fun snapshot(): List<CustomCommandDetail> = commands.values.sortedBy { it.key }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
