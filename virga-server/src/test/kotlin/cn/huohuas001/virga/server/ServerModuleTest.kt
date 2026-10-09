package cn.huohuas001.virga.server

import cn.huohuas001.virga.server.config.YamlConfig
import cn.huohuas001.virga.server.game.GameColor
import cn.huohuas001.virga.server.game.GameText
import cn.huohuas001.virga.server.inventory.MojangHeadTextureResolver
import cn.huohuas001.virga.server.platform.TickScheduler
import cn.huohuas001.virga.server.platform.wasRefused
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerModuleTest {
    private val skinHash = "292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680"
    private val capeHash = "3f688e0e699b3d9fe448b5bb50a3a288f9c589762b3dae8308842122dcb81"

    private fun textures(json: String) = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun `tick scheduler runs tasks on later ticks and honours delays and cancellation`() {
        var serverThread = true
        val scheduler = TickScheduler("test", { serverThread }, onError = { _, error -> throw error })
        val order = ArrayList<String>()
        scheduler.global { order += "next" }
        scheduler.globalLater(3) { order += "third" }
        val cancelled = scheduler.global { order += "cancelled" }
        cancelled.cancel()
        assertTrue(order.isEmpty(), "nothing runs before the first tick")
        scheduler.tick()
        assertEquals(listOf("next"), order)
        scheduler.tick(); scheduler.tick()
        assertEquals(listOf("next", "third"), order)

        serverThread = false
        val future = scheduler.supplyGlobal { "value" }
        assertFalse(future.isDone, "off-thread work waits for the server thread")
        serverThread = true
        scheduler.tick()
        assertEquals("value", future.join())

        scheduler.cancelAll()
        assertTrue(scheduler.global { }.wasRefused())
    }

    @Test
    fun `off-thread one-shot work still runs while the server is paused`() {
        val executor = ArrayList<Runnable>()
        val scheduler = TickScheduler("test", { false }, { _, error -> throw error }, { executor += it })
        val future = scheduler.supplyGlobal { "answered" }
        assertEquals(1, executor.size, "next-tick work goes to the server executor, not the tick queue")
        executor.single().run()
        assertEquals("answered", future.join())
        var delayed = false
        scheduler.globalLater(5) { delayed = true }
        assertEquals(1, executor.size, "delayed work keeps counting server ticks")
        assertFalse(delayed)
    }

    @Test
    fun `timers repeat until cancelled`() {
        val scheduler = TickScheduler("test", { true }, onError = { _, error -> throw error })
        var runs = 0
        val timer = scheduler.globalTimer(1, 2) { runs++ }
        repeat(6) { scheduler.tick() }
        assertEquals(3, runs)
        timer.cancel()
        repeat(4) { scheduler.tick() }
        assertEquals(3, runs)
    }

    @Test
    fun `yaml edits keep the user's comments and other keys`() {
        val config = YamlConfig.parse(
            """
            # Virga 配置
            bot:
              # 允许的群
              groups: []
              enabled: false
            panel:
              port: 56789
            """.trimIndent()
        )
        config.set("bot.groups", listOf("GROUP_A", "GROUP_B"))
        config.set("panel.port", 56790)
        config.set("features.new-switch", true)
        val saved = config.saveToString()
        assertTrue(saved.contains("# Virga 配置"))
        assertTrue(saved.contains("# 允许的群"))
        val reloaded = YamlConfig.parse(saved)
        assertEquals(listOf("GROUP_A", "GROUP_B"), reloaded.getStringList("bot.groups"))
        assertEquals(56790, reloaded.getInt("panel.port"))
        assertTrue(reloaded.getBoolean("features.new-switch"))
        assertFalse(reloaded.getBoolean("bot.enabled", true))
        assertEquals(setOf("groups", "enabled"), reloaded.getKeys("bot"))
    }

    @Test
    fun `texture hashes come from the skin even when a cape is listed first`() {
        val value = textures(
            """{"textures":{"CAPE":{"url":"http://textures.minecraft.net/texture/$capeHash"},""" +
                """"SKIN":{"url":"http://textures.minecraft.net/texture/$skinHash","metadata":{"model":"slim"}}}}"""
        )
        assertEquals(skinHash, MojangHeadTextureResolver.textureHash(value))
        val parsed = parseTextures(value)
        assertEquals("http://textures.minecraft.net/texture/$skinHash", parsed?.first)
        assertEquals(true, parsed?.second)
        assertNull(MojangHeadTextureResolver.textureHash(textures("""{"textures":{}}""")))
    }

    @Test
    fun `legacy color codes become styled spans`() {
        val text = GameText.legacy("&d[Virga]&r 你好 &a&l绿色")
        assertEquals("[Virga] 你好 绿色", text.plain())
        assertEquals(GameColor.LIGHT_PURPLE, text.spans.first().color)
        assertTrue(text.spans.last().bold)
        assertEquals(GameColor.GREEN, text.spans.last().color)
    }
}
