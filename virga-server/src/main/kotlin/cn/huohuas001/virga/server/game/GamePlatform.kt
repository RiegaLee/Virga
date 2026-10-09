package cn.huohuas001.virga.server.game

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot
import java.io.InputStream
import java.nio.file.Path
import java.util.UUID

/**
 * The whole contract between Virga and the game. The platform layer (virga-forge) implements it with
 * Mojang-named Minecraft classes; nothing in `virga-server` touches Minecraft types.
 *
 * Unless stated otherwise every member must be called on the server thread
 * (see [cn.huohuas001.virga.server.platform.VirgaScheduler]).
 */
interface GameServer {
    /** "Forge" or "NeoForge" — shown in status cards and the panel. */
    val platformName: String
    val minecraftVersion: String
    val modVersion: String
    /** `config/virga` style data directory; safe from any thread. */
    val dataDirectory: Path

    fun onlinePlayers(): List<GamePlayer>
    fun player(uuid: UUID): GamePlayer?
    fun playerExact(name: String): GamePlayer?
    fun maxPlayers(): Int

    /** Average tick duration over the server's own window in milliseconds, NaN when unknown. */
    fun averageTickMillis(): Double

    fun broadcast(text: GameText)

    /**
     * Runs [command] as an isolated console-level source. Feedback lines are delivered to
     * [feedback] (possibly after this call returns). Returns false when the command was rejected.
     */
    fun dispatchCommand(command: String, feedback: (String) -> Unit): Boolean

    /** Reads a resource bundled in the mod JAR. Any thread. */
    fun resource(path: String): InputStream?

    /** Class loader of the mod JAR (panel static assets). Any thread. */
    val resourceLoader: ClassLoader

    fun isModLoaded(modId: String): Boolean

    /** True when a permission mod (LuckPerms and friends) answers permission nodes. */
    fun permissionModActive(): Boolean

    /**
     * UUID of a player who joined this server before (the server's name cache). May query
     * Mojang for unknown names, so call it off the server thread.
     */
    fun knownPlayerUuid(name: String): UUID?

    /** Allocates a locked map that only Virga draws on (e.g. the QQ connect QR code). */
    fun createMapCanvas(): GameMapCanvas
}

interface GamePlayer {
    val uuid: UUID
    val name: String

    /** Still connected; safe from any thread. */
    fun isOnline(): Boolean

    /**
     * Permission check. Uses the permission mod (LuckPerms and friends) when installed, otherwise the
     * vanilla operator level ([defaultOpLevel], 2 = command blocks / gamemasters).
     */
    fun hasPermission(node: String, defaultOpLevel: Int = 2): Boolean

    fun send(text: GameText)
    fun send(legacy: String) = send(GameText.legacy(legacy))

    /** Disconnects the player with a plain multi-line reason. */
    fun kick(reason: String)

    /** Base64 `textures` property of the live game profile, if any. */
    fun texturesProperty(): String?

    fun captureInventory(): CapturedInventory

    /** 27 Ender Chest slots, null for empty. */
    fun captureEnderChest(): List<ItemSnapshot?>

    /**
     * Opens a chest-style menu whose items cannot be taken. [onClick] receives clicks on the
     * menu's own slots; [onClose] runs once when the player closes it (or another menu replaces it).
     */
    fun openMenu(title: String, rows: Int, onClick: (slot: Int, click: MenuClick) -> Unit, onClose: () -> Unit): GameMenu
}

/** Colors a [GameMapCanvas] can paint. */
enum class MapTone { PAPER, INK, ACCENT, ACCENT_DEEP }

/** A locked 128×128 map item; server thread only. */
interface GameMapCanvas {
    val id: Int

    /** [pixels] holds [SIZE]×[SIZE] tones row by row. */
    fun draw(pixels: Array<MapTone>)

    /**
     * Puts a map showing this canvas straight into the player's main hand, moving the held item to
     * a free slot. False when the inventory is full.
     */
    fun giveTo(player: GamePlayer, name: String, lore: List<String>): Boolean

    /** Removes every copy of this map the player carries. */
    fun takeBackFrom(player: GamePlayer)

    /**
     * Keeps the map in the player's main hand: brings it back from another slot, the cursor or a
     * fresh drop on the ground. [MapHold.LOST] when it left the player for good (a container, an
     * item frame, another player, death); the caller must then invalidate whatever it shows.
     */
    fun keepInHand(player: GamePlayer): MapHold

    /** Blanks the map, so every copy, wherever it ended up, shows nothing. */
    fun clear()

    companion object {
        const val SIZE = 128
    }
}

enum class MapHold { HELD, RESTORED, LOST }

enum class MenuClick { LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, OTHER }

/** An item shown in a [GameMenu]; texts use `&` color codes. */
data class MenuIcon(
    /** Namespaced item id such as `minecraft:pink_dye`. */
    val item: String,
    val name: String,
    val lore: List<String> = emptyList(),
    val glowing: Boolean = false,
    val count: Int = 1,
    /** Shows the head of the player looking at the menu instead of [item]. */
    val viewerHead: Boolean = false
)

interface GameMenu {
    fun show(slot: Int, icon: MenuIcon?)
    fun close()
    val isOpen: Boolean
}

/** Live player inventory in vanilla storage order: hotbar 0..8, main inventory 9..35. */
class CapturedInventory(
    val storage: List<ItemSnapshot?>,
    val helmet: ItemSnapshot?,
    val chestplate: ItemSnapshot?,
    val leggings: ItemSnapshot?,
    val boots: ItemSnapshot?,
    val offhand: ItemSnapshot?
) {
    init {
        require(storage.size >= 36) { "Player inventory storage must contain 36 slots" }
    }
}


/** A sender of `/virga` commands: console or a player. */
interface GameCommandSource {
    val player: GamePlayer?
    val isConsole: Boolean
    fun hasPermission(node: String, defaultOpLevel: Int = 2): Boolean
    fun reply(text: GameText)
    fun reply(legacy: String) = reply(GameText.legacy(legacy))
}
