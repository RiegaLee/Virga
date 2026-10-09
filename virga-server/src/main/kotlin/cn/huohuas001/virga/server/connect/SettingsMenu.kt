package cn.huohuas001.virga.server.connect

import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.panel.settings.PanelSettingsCatalog
import cn.huohuas001.virga.server.config.YamlConfig
import cn.huohuas001.virga.server.game.GameMenu
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.MenuClick
import cn.huohuas001.virga.server.game.MenuIcon
import cn.huohuas001.virga.server.panel.PanelConfigWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/virga menu`: a chest menu with every setting that applies without a restart — the panel's
 * switches plus a few numbers — for admins who cannot reach the console or the Web panel.
 * Changes go through [PanelConfigWriter] (backup, atomic write, reload, audit) like the panel.
 *
 * Server thread only.
 */
class SettingsMenu(
    private val config: () -> YamlConfig,
    private val writer: PanelConfigWriter,
    private val logger: VirgaLogger,
    private val statusLines: () -> List<String>,
    private val reload: () -> Unit,
    private val startQrConnect: (GamePlayer) -> Unit,
    /** Saves brand.server-address (empty clears it), replies to the player, then runs the callback. */
    private val applyServerAddress: (player: GamePlayer, input: String, done: () -> Unit) -> Unit
) {
    /** Numbers adjustable with left (+) and right (−) clicks; shift multiplies the step by five. */
    data class NumberSetting(
        val key: String,
        val label: String,
        val item: String,
        val unit: String,
        val min: Int,
        val max: Int,
        val default: Int,
        val step: Int,
        val description: String
    )

    private class Session(val menu: GameMenu, var pending: Boolean = false)

    private val sessions = ConcurrentHashMap<UUID, Session>()
    /** Players whose next chat line is the server address, with the deadline in nanos. */
    private val addressPrompts = ConcurrentHashMap<UUID, Long>()

    fun open(player: GamePlayer) {
        if (!player.hasPermission(PERMISSION)) {
            player.send("&d[Virga]&r 你没有修改 Virga 设置的权限哦。")
            return
        }
        lateinit var session: Session
        val menu = player.openMenu(
            "Virga设置", ROWS,
            onClick = { slot, click -> clicked(player, session, slot, click) },
            onClose = { if (sessions[player.uuid] === session) sessions.remove(player.uuid) }
        )
        session = Session(menu)
        sessions[player.uuid] = session
        render(session)
    }

    private fun render(session: Session) {
        val menu = session.menu
        if (!menu.isOpen) return
        val document = config()
        // Panes only divide the menu; every button says its own state.
        for (slot in 0 until ROWS * 9) menu.show(slot, FILLER)
        menu.show(INFO_SLOT, MenuIcon(
            "minecraft:nether_star", "&d&lVirga 设置",
            statusLines().map { "&7$it" } + listOf("", "&e点一下设置服务器地址", "&8改动会立刻写进 config.yml 并生效")
        ))
        TOGGLE_SLOTS.zip(PanelSettingsCatalog.DEFINITIONS).forEach { (slot, definition) ->
            val on = document.getBoolean(definition.key, definition.defaultValue)
            val action = if (session.pending) "&8正在保存…" else if (on) "&e点一下关上" else "&e点一下打开"
            menu.show(slot, MenuIcon(
                TOGGLE_ITEMS[definition.key] ?: "minecraft:paper",
                (if (on) "&d${definition.label}  &a● 开" else "&7${definition.label}  &8○ 关"),
                listOf("&8${definition.group}") + wrap(definition.description).map { "&7$it" } +
                    listOf("", if (on) "&f当前：&a开着" else "&f当前：&7关着", action),
                glowing = on
            ))
        }
        NUMBER_SLOTS.zip(NUMBERS).forEach { (slot, setting) ->
            val value = number(document, setting)
            menu.show(slot, MenuIcon(
                setting.item, "&d${setting.label}：&f$value${setting.unit}",
                wrap(setting.description).map { "&7$it" } + listOf(
                    "&8范围 ${setting.min}–${setting.max}${setting.unit}",
                    "",
                    "&e左键 +${setting.step}，右键 −${setting.step}",
                    "&eShift 点击一次调 ${setting.step * 5}"
                ),
                count = value.coerceIn(1, 64)
            ))
        }
        menu.show(QR_SLOT, MenuIcon(
            "minecraft:map", "&d扫码连接 QQ 机器人",
            listOf("&7Virga 把二维码画在地图上交给你，", "&7用手机 QQ 扫一扫就能连上", "", "&e点一下开始")
        ))
        menu.show(RELOAD_SLOT, MenuIcon(
            "minecraft:recovery_compass", "&d重新读取配置",
            listOf("&7手动改过 config.yml / messages.yml 后点这里", "&7QQ 连接不会断开")
        ))
        menu.show(CLOSE_SLOT, MenuIcon("minecraft:barrier", "&c关闭"))
    }

    private fun clicked(player: GamePlayer, session: Session, slot: Int, click: MenuClick) {
        if (!player.hasPermission(PERMISSION)) {
            session.menu.close()
            return
        }
        when (slot) {
            CLOSE_SLOT -> session.menu.close()
            INFO_SLOT -> {
                session.menu.close()
                addressPrompts[player.uuid] = System.nanoTime() + ADDRESS_PROMPT_NANOS
                player.send("&d[Virga]&r 请直接在聊天栏输入服务器地址（例如 mc.example.com:25565），这条消息只有 Virga 看得到；" +
                    "输入「清空」删除地址，输入「取消」放弃。60 秒内有效。")
            }
            QR_SLOT -> {
                session.menu.close()
                startQrConnect(player)
            }
            RELOAD_SLOT -> {
                runCatching(reload)
                    .onSuccess { player.send("&d[Virga]&r 配置和文案重新读好了～") }
                    .onFailure { player.send("&d[Virga]&r 重载失败了：${it.message}") }
                render(session)
            }
            in TOGGLE_SLOTS -> {
                val definition = PanelSettingsCatalog.DEFINITIONS.getOrNull(TOGGLE_SLOTS.indexOf(slot)) ?: return
                val value = !config().getBoolean(definition.key, definition.defaultValue)
                save(player, session, definition.key, value, "${definition.label}${if (value) "打开" else "关上"}了～")
            }
            in NUMBER_SLOTS -> {
                val setting = NUMBERS.getOrNull(NUMBER_SLOTS.indexOf(slot)) ?: return
                val step = when (click) {
                    MenuClick.LEFT -> setting.step
                    MenuClick.RIGHT -> -setting.step
                    MenuClick.SHIFT_LEFT -> setting.step * 5
                    MenuClick.SHIFT_RIGHT -> -setting.step * 5
                    MenuClick.OTHER -> return
                }
                val current = number(config(), setting)
                val value = (current + step).coerceIn(setting.min, setting.max)
                if (value == current) return
                save(player, session, setting.key, value, "${setting.label}改成 $value${setting.unit}～")
            }
        }
    }

    /**
     * Consumes the chat line of a player who was asked for the server address, so it is never
     * broadcast or bridged to QQ. Returns false for ordinary chat.
     */
    fun interceptChat(player: GamePlayer, message: String): Boolean {
        val deadline = addressPrompts.remove(player.uuid) ?: return false
        if (System.nanoTime() > deadline) return false
        val input = message.trim()
        if (input == "取消" || input.equals("cancel", ignoreCase = true)) {
            player.send("&d[Virga]&r 好的，服务器地址没有改。")
            open(player)
            return true
        }
        applyServerAddress(player, input) { if (player.isOnline()) open(player) }
        return true
    }

    private fun save(player: GamePlayer, session: Session, key: String, value: Any, done: String) {
        if (session.pending) return
        session.pending = true
        render(session)
        writer.update("menu.settings", "$key=$value by ${player.name}") { it.set(key, value) }
            .whenComplete { _, error ->
                // PanelConfigWriter completes on the server thread.
                session.pending = false
                if (error != null) {
                    logger.error("游戏内设置菜单保存 $key 失败", error)
                    player.send("&d[Virga]&r 设置没保存成功，看看控制台日志。")
                } else {
                    player.send("&d[Virga]&r $done")
                }
                render(session)
            }
    }

    private fun number(document: YamlConfig, setting: NumberSetting): Int =
        document.getString(setting.key)?.trim()?.toIntOrNull()?.coerceIn(setting.min, setting.max) ?: setting.default

    companion object {
        const val PERMISSION = "virga.admin"
        private const val ROWS = 6
        private const val INFO_SLOT = 4
        private val ADDRESS_PROMPT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        /** Switches in rows 1 and 3; rows 2 and 4 are pane dividers. */
        private val TOGGLE_SLOTS = (10..16) + (28..34)
        private val NUMBER_SLOTS = listOf(46, 47, 48, 49, 50)
        private const val QR_SLOT = 45
        private const val RELOAD_SLOT = 52
        private const val CLOSE_SLOT = 8
        private val FILLER = MenuIcon("minecraft:white_stained_glass_pane", " ")

        /** An item that says what each switch is about; glowing while it is on. */
        private val TOGGLE_ITEMS = mapOf(
            "features.performance.enabled" to "minecraft:compass",
            "features.online-list.enabled" to "minecraft:name_tag",
            "features.inventory.enabled" to "minecraft:chest",
            "features.inventory.ender-chest-enabled" to "minecraft:ender_chest",
            "features.binding.enabled" to "minecraft:lead",
            "features.binding.force-bind" to "minecraft:iron_door",
            "features.remote-commands.enabled" to "minecraft:command_block",
            "features.chat-bridge.game-to-qq" to "minecraft:writable_book",
            "features.chat-bridge.qq-to-game" to "minecraft:paper",
            "features.player-notices.join-enabled" to "minecraft:bell",
            "features.player-notices.quit-enabled" to "minecraft:oak_door",
            "appearance.custom-backgrounds" to "minecraft:painting",
            "panel.qr-connect.enabled" to "minecraft:spyglass"
        )

        val NUMBERS = listOf(
            NumberSetting("features.performance.cooldown-seconds", "状态卡冷却", "minecraft:redstone_torch", " 秒", 0, 300, 5, 1,
                "同一个群两次 /服务器状态 之间至少隔多久"),
            NumberSetting("features.online-list.cooldown-seconds", "在线列表冷却", "minecraft:clock", " 秒", 0, 300, 3, 1,
                "同一个群两次 /在线列表 之间至少隔多久"),
            NumberSetting("features.online-list.page-size", "在线列表每页人数", "minecraft:book", " 人", 1, 60, 27, 3,
                "一张在线列表图片最多画几位玩家"),
            NumberSetting("features.inventory.cooldown-seconds", "背包查询冷却", "minecraft:barrel", " 秒", 0, 300, 5, 1,
                "同一位玩家两次查背包/末影箱之间至少隔多久"),
            NumberSetting("appearance.veil-percent", "自定义底图柔光", "minecraft:white_stained_glass", "%", 0, 80, 15, 5,
                "盖在自定义背景上的奶白色柔光，图片太花时调大一点")
        )

        init {
            check(PanelSettingsCatalog.DEFINITIONS.size <= TOGGLE_SLOTS.size) { "设置菜单放不下全部开关" }
        }

        /** Splits a description into short lore lines. */
        fun wrap(text: String, width: Int = 18): List<String> {
            val lines = mutableListOf<String>()
            var rest = text
            while (rest.length > width) {
                // Break after punctuation (or before an opening bracket) when one is near the end.
                val mark = rest.lastIndexOfAny(charArrayOf('，', '；', '、', ' ', '（'), width - 1)
                val end = when {
                    mark < width / 2 -> width
                    rest[mark] == '（' -> mark
                    else -> mark + 1
                }
                lines += rest.substring(0, end).trim()
                rest = rest.substring(end)
            }
            if (rest.isNotBlank()) lines += rest.trim()
            return lines
        }
    }
}
