package cn.huohuas001.virga.server

import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor
import cn.huohuas001.virga.features.inventory.armor.EquipmentAssetResolver
import cn.huohuas001.virga.features.inventory.asset.BundledAssetBootstrap
import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider
import cn.huohuas001.virga.features.inventory.model.InventorySlot
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot
import cn.huohuas001.virga.features.inventory.model.SlotType
import cn.huohuas001.virga.features.inventory.renderer.EnderChestRenderer
import cn.huohuas001.virga.features.inventory.renderer.InventoryRenderMetadata
import cn.huohuas001.virga.features.inventory.renderer.Java2DInventoryRenderer
import cn.huohuas001.virga.features.inventory.renderer.ThemeLoader
import cn.huohuas001.virga.features.inventory.skin.PlayerModelRenderer
import cn.huohuas001.virga.features.inventory.skin.PlayerPreviewService
import cn.huohuas001.virga.features.onlinelist.model.PlayerSnapshot
import cn.huohuas001.virga.features.onlinelist.model.ServerSnapshot
import cn.huohuas001.virga.features.onlinelist.render.OnlineListRenderer
import cn.huohuas001.virga.features.onlinelist.skin.AvatarCache
import cn.huohuas001.virga.features.performance.model.PerformanceSnapshot
import cn.huohuas001.virga.features.performance.render.PerformanceRenderer
import cn.huohuas001.virga.features.render.BackdropLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every Virga image with sample data into build/brand-preview so the visual style
 * can be checked by eye; the assertions only guard that rendering works offline.
 */
class BrandPreviewTest {
    private val output: Path = Paths.get("build", "brand-preview").also { Files.createDirectories(it) }
    private val now: Instant = Instant.parse("2026-10-08T12:30:00Z")

    @Test
    fun `status card renders every mood`() {
        val renderer = PerformanceRenderer("")
        write("status.png", renderer.render(normalStatus()))
        write("status-warning.png", renderer.render(
            PerformanceSnapshot("Virga 测试服", now, 17.6, 46.0, 74.0, 52.0, 5_900_000_000L, 8_000_000_000L, 31, 40)))
        write("status-critical.png", renderer.render(
            PerformanceSnapshot("Virga 测试服", now, 11.2, 88.0, 97.0, 91.0, 7_700_000_000L, 8_000_000_000L, 38, 40)))
        write("status-unavailable.png", renderer.render(
            PerformanceSnapshot("Virga 测试服", now, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0L, 0L, 0, 0)))
    }

    /** A deliberately busy admin picture: the glass layers must keep text and items readable. */
    @Test
    fun `custom backgrounds stay readable`() {
        val folder = Files.createTempDirectory("virga-backgrounds")
        BackdropLibrary.Surface.entries.forEach { surface ->
            javax.imageio.ImageIO.write(busyPicture(1600, 900), "png", folder.resolve(surface.id() + ".png").toFile())
        }
        val custom = BackdropLibrary(folder, { BackdropLibrary.Options(true, 15) }, Logger.getAnonymousLogger())
        write("custom-status.png", PerformanceRenderer(custom, "", null, "TPS").render(normalStatus()))
        write("custom-online.png", onlineRenderer(custom).render(ServerSnapshot("Virga 测试服", 40, samplePlayers(), now)))
        val (theme, snapshot, _) = inventorySample()
        write("custom-inventory.png", Java2DInventoryRenderer(theme) { custom.get(BackdropLibrary.Surface.INVENTORY) }
            .render(snapshot, null, InventoryRenderMetadata.realtime(now)).bytes)
    }

    private fun normalStatus() =
        PerformanceSnapshot("Virga 测试服", now, 19.8, 23.4, 37.0, 21.0, 3_200_000_000L, 8_000_000_000L, 12, 40)

    private fun onlineRenderer(backdrops: BackdropLibrary) = OnlineListRenderer(
        backdrops, AvatarCache(Logger.getAnonymousLogger(), { it.run() }, 500, 500, 16, false), 3, "", "POWERED BY Virga", "steve"
    )

    private fun samplePlayers() = listOf("RiegaLee", "PinkPiggy", "RobotH", "CarrotFan", "Notch", "Alex_2026", "Builder", "Miner")
        .mapIndexed { index, name -> PlayerSnapshot(name, UUID.nameUUIDFromBytes(name.toByteArray()).toString(), null, index == 0) }

    private fun busyPicture(width: Int, height: Int): java.awt.image.BufferedImage {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        val colors = listOf(0x2E7D32, 0xF9A825, 0x1565C0, 0xC62828, 0x6A1B9A, 0x00838F).map { java.awt.Color(it) }
        for (i in 0 until 40) {
            graphics.color = colors[i % colors.size]
            graphics.fillPolygon(intArrayOf(i * 80 - height, i * 80 + 40 - height, i * 80 + 40, i * 80), intArrayOf(height, height, 0, 0), 4)
        }
        graphics.color = java.awt.Color(255, 255, 255, 120)
        for (i in 0 until 14) graphics.fillOval((i * 173) % width, (i * 97) % height, 160, 160)
        graphics.dispose()
        return image
    }

    @Test
    fun `online list renders sample players and the empty state`() {
        val avatars = AvatarCache(Logger.getAnonymousLogger(), { it.run() }, 500, 500, 16, false)
        val renderer = OnlineListRenderer(avatars, 3, "", "POWERED BY Virga")
        write("online.png", renderer.render(ServerSnapshot("Virga 测试服", 40, samplePlayers(), now)))
        write("online-empty.png", renderer.render(ServerSnapshot("Virga 测试服", 40, emptyList(), now)))
    }


    @Test
    fun `inventory and ender chest render with the generated theme`() {
        val (theme, snapshot, themeRoot, packRoot) = inventorySample()
        // Same 3D preview path as the runtime, with the bundled default skin (no network).
        val area = theme.layout.playerPreview
        val playerPreview = PlayerPreviewService(
            true, null, Files.createTempDirectory("virga-preview-cache"), Logger.getLogger("BrandPreviewTest"),
            "3d", EquipmentAssetResolver(packRoot.resolve("armor")),
            area?.width ?: PlayerModelRenderer.WIDTH, area?.height ?: PlayerModelRenderer.HEIGHT
        ).preview(snapshot)
        write("inventory.png", Java2DInventoryRenderer(theme).render(snapshot, playerPreview, InventoryRenderMetadata.realtime(now)).bytes)
        write("ender-chest.png", EnderChestRenderer(theme, themeRoot.resolve("ender-chest-background.png"))
            .render(snapshot, null, InventoryRenderMetadata.offline(now)).bytes)
    }

    private data class InventorySample(
        val theme: cn.huohuas001.virga.features.inventory.renderer.Theme,
        val snapshot: InventorySnapshot,
        val themeRoot: Path,
        val packRoot: Path
    )

    private fun inventorySample(): InventorySample {
        val install = BundledAssetBootstrap.install(Files.createTempDirectory("virga-assets")) { path ->
            BrandPreviewTest::class.java.classLoader.getResourceAsStream(path.removePrefix("/"))
        }
        val themeRoot = install.themesRoot.resolve("virga")
        val theme = ThemeLoader.load(themeRoot, VanillaImportedAssetProvider.open(install.vanillaRoot), install.customRoot.resolve("overrides/items"))
        fun item(key: String, amount: Int = 1) = ItemSnapshot("minecraft:$key", amount, 0, 0, null, null, false, null)
        val storage = (0 until 27).map { index ->
            val key = listOf("carrot", "porkchop", "cooked_porkchop", "golden_carrot", "oak_log", "redstone", "diamond", "", "")[index % 9]
            if (key.isEmpty()) InventorySlot.empty(SlotType.STORAGE, index) else InventorySlot.of(SlotType.STORAGE, index, item(key, 1 + index * 3 % 64))
        }
        val hotbar = (0 until 9).map { index ->
            val key = listOf("diamond_sword", "diamond_pickaxe", "bow", "torch", "bread", "player_head", "shield", "carrot_on_a_stick", "saddle")[index]
            when {
                key == "player_head" && index == 5 -> InventorySlot.of(SlotType.HOTBAR, index, ItemSnapshot("minecraft:player_head", 1, 0, 0, null, null, false, null, null, null,
                    cn.huohuas001.virga.features.inventory.head.PlayerHeadVisualDescriptor(UUID.nameUUIDFromBytes("OfflinePlayer:Notch".toByteArray()), "Notch")))
                else -> InventorySlot.of(SlotType.HOTBAR, index, item(key, if (key == "torch") 48 else 1))
            }
        }
        val armor = listOf(
            InventorySlot.of(SlotType.ARMOR_HEAD, 0, ItemSnapshot("minecraft:diamond_helmet", 1, 0, 363, null, null, false, null,
                ArmorVisualDescriptor(ArmorVisualDescriptor.Slot.HEAD, "minecraft:diamond_helmet", "minecraft:diamond", null, null, null, false))),
            InventorySlot.empty(SlotType.ARMOR_CHEST, 0),
            InventorySlot.empty(SlotType.ARMOR_LEGS, 0),
            InventorySlot.empty(SlotType.ARMOR_FEET, 0)
        )
        val snapshot = InventorySnapshot(
            InventorySnapshot.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), "RiegaLee", now, "Virga 测试服", "preview",
            storage, hotbar, armor, InventorySlot.of(SlotType.OFFHAND, 0, item("player_head"))
        )
        return InventorySample(theme, snapshot, themeRoot, install.packRoot)
    }

    private fun write(name: String, bytes: ByteArray) {
        assertTrue(bytes.size > 1024, "$name should be a real PNG")
        Files.write(output.resolve(name), bytes)
    }
}
