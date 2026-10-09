package cn.huohuas001.virga.core.bot.provider

import cn.huohuas001.virga.core.bot.VirgaHost
import java.util.concurrent.atomic.AtomicReference

object BotShared {
    private val plugin = AtomicReference<VirgaHost?>()

    fun setInstance(instance: VirgaHost) {
        plugin.set(instance)
    }

    fun clear(instance: VirgaHost) {
        plugin.compareAndSet(instance, null)
    }

    fun getPlugin(): VirgaHost = checkNotNull(plugin.get()) { "VirgaHost host is not initialized" }
}
