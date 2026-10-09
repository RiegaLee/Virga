package cn.huohuas001.virga.server

import cn.huohuas001.virga.server.game.GameCommandSource

/** `/virga`, registered through Brigadier by the platform layer. */
class VirgaCommand(private val plugin: VirgaRuntime) {
    fun execute(sender: GameCommandSource, label: String, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "info") {
            "info", "状态" -> plugin.localStatus().forEach(sender::reply)
            "reload", "重载" -> {
                if (!sender.hasPermission("virga.admin")) {
                    sender.reply("&d[Virga]&r 你没有重载的权限哦。")
                    return
                }
                runCatching { plugin.reloadPluginConfig() }
                    .onSuccess { sender.reply("&d[Virga]&r 配置和文案重新读好了，QQ 连接一直在线～") }
                    .onFailure { sender.reply("&d[Virga]&r 重载失败了：${it.message}") }
            }
            "group", "群" -> handleGroup(sender, label, args.drop(1))
            "preview", "预览" -> {
                if (!sender.hasPermission("virga.admin")) {
                    sender.reply("&d[Virga]&r 你没有预览图片的权限哦。")
                    return
                }
                plugin.preview(sender, args.getOrNull(1))
            }
            "panel", "面板" -> handlePanel(sender, label, args.drop(1))
            "menu", "设置", "菜单" -> {
                val player = adminPlayer(sender, "设置菜单是箱子界面，要在游戏里打开哦；控制台可以直接改 config.yml 后 reload。") ?: return
                plugin.openSettingsMenu(player)
            }
            "address", "地址" -> {
                if (!sender.hasPermission("virga.admin")) {
                    sender.reply("&d[Virga]&r 你没有修改服务器地址的权限哦。")
                    return
                }
                plugin.setServerAddress(sender, args.drop(1).joinToString(" "))
            }
            "connect", "扫码", "连接" -> {
                val player = adminPlayer(sender, "扫码连接会把二维码画在地图上，要由游戏里的管理员发起哦。") ?: return
                val cancel = args.getOrNull(1)?.lowercase() in setOf("cancel", "取消")
                plugin.qrConnect(player, cancel)
            }
            "passwd", "password", "密码" -> handlePassword(sender, label, args.drop(1))
            else -> sender.reply("&d[Virga]&r 用法：/$label info|reload|menu|address [地址|clear]|connect [cancel]|group <add|cancel>|panel|preview [玩家]|passwd [新密码]")
        }
    }

    private fun adminPlayer(sender: GameCommandSource, consoleHint: String): cn.huohuas001.virga.server.game.GamePlayer? {
        if (!sender.hasPermission("virga.admin")) {
            sender.reply("&d[Virga]&r 你没有修改 Virga 设置的权限哦。")
            return null
        }
        return sender.player ?: run {
            sender.reply("&d[Virga]&r $consoleHint")
            null
        }
    }

    private fun handlePanel(sender: GameCommandSource, label: String, args: List<String>) {
        if (!sender.hasPermission("virga.admin")) {
            sender.reply("&d[Virga]&r 你没有管理面板的权限哦。")
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "status", "状态" -> sender.reply(plugin.panelStatus())
            // reset-password is kept for existing docs and habits; passwd is the short form.
            "reset-password", "重置密码" -> handlePassword(sender, label, emptyList())
            "passwd", "password", "密码" -> handlePassword(sender, label, args.drop(1))
            else -> sender.reply("&d[Virga]&r 用法：/$label panel [status]；改密码用 /$label passwd [新密码]")
        }
    }

    /** `passwd` regenerates a random password, `passwd <新密码>` sets a chosen one. */
    private fun handlePassword(sender: GameCommandSource, label: String, args: List<String>) {
        if (!sender.hasPermission("virga.admin")) {
            sender.reply("&d[Virga]&r 你没有管理面板的权限哦。")
            return
        }
        // Passwords go through the server console only, never into game chat.
        if (!sender.isConsole) {
            sender.reply("&d[Virga]&r 为了不让密码出现在聊天记录里，请在服务器控制台执行 $label passwd [新密码]。")
            return
        }
        when (args.size) {
            0 -> plugin.resetPanelPassword()
            1 -> sender.reply(plugin.setPanelPassword(args[0].toCharArray()))
            else -> sender.reply("&d[Virga]&r 新密码不能有空格，密码没有修改。用法：$label passwd [新密码]")
        }
    }

    private fun handleGroup(sender: GameCommandSource, label: String, args: List<String>) {
        if (!sender.hasPermission("virga.admin")) {
            sender.reply("&d[Virga]&r 你没有管理 QQ 群接入的权限哦。")
            return
        }
        val player = sender.player
        if (player == null) {
            sender.reply("&d[Virga]&r QQ 群接入要由游戏里的管理员发起和确认哦。")
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            "add", "添加", "接入" -> plugin.beginGroupEnrollment(player)
            "cancel", "取消" -> plugin.cancelGroupEnrollment(player)
            "confirm" -> plugin.confirmGroupEnrollment(player, args.getOrNull(1).orEmpty())
            "reject" -> plugin.rejectGroupEnrollment(player, args.getOrNull(1).orEmpty())
            else -> sender.reply("&d[Virga]&r 用法：/$label group <add|cancel>")
        }
    }

    fun complete(args: List<String>): List<String> = when {
        args.size == 1 -> listOf("info", "reload", "menu", "address", "connect", "group", "panel", "preview", "passwd").filter { it.startsWith(args[0], ignoreCase = true) }
        args.size == 2 && args[0].equals("group", ignoreCase = true) ->
            listOf("add", "cancel").filter { it.startsWith(args[1], ignoreCase = true) }
        args.size == 2 && args[0].equals("address", ignoreCase = true) ->
            listOf("clear").filter { it.startsWith(args[1], ignoreCase = true) }
        args.size == 2 && args[0].equals("connect", ignoreCase = true) ->
            listOf("cancel").filter { it.startsWith(args[1], ignoreCase = true) }
        args.size == 2 && args[0].equals("panel", ignoreCase = true) ->
            listOf("status", "passwd", "reset-password").filter { it.startsWith(args[1], ignoreCase = true) }
        else -> emptyList()
    }
}
