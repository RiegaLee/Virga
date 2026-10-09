package cn.huohuas001.virga.panel.settings

/**
 * The only general-purpose config.yml switches the panel may change. Group command drafts
 * have separate validation. Changes apply on reload; ROOT identities and command execution
 * are never exposed as web endpoints.
 */
object PanelSettingsCatalog {
    data class Definition(
        val key: String,
        val label: String,
        val group: String,
        val description: String,
        /** Value shown when config.yml has no such key (older configs). */
        val defaultValue: Boolean = false,
        /** High-risk switch: turning it on needs an explicit confirmation showing this text. */
        val riskWarning: String? = null
    )

    val DEFINITIONS: List<Definition> = listOf(
        Definition("features.performance.enabled", "服务器状态卡", "查询功能", "QQ 群 /服务器状态"),
        Definition("features.online-list.enabled", "在线列表", "查询功能", "QQ 群 /在线列表 与 /查在线"),
        Definition("features.inventory.enabled", "背包查询", "查询功能", "QQ 群 /我的背包"),
        Definition("features.inventory.ender-chest-enabled", "末影箱查询", "查询功能", "QQ 群 /我的末影箱"),
        Definition("features.binding.enabled", "账号绑定", "账号", "QQ 与游戏账号绑定"),
        Definition("features.binding.force-bind", "入服必须绑定 QQ", "账号", "默认关闭；豁免名单可免绑定入服，机器人不可用时临时放行；豁免名单在配置文件中设置"),
        Definition("features.remote-commands.enabled", "QQ 远程服务器指令", "管理功能", "在开放了 /执行命令 的群里使用；仍按超级管理员／管理员权限检查",
            riskWarning = "打开后，有权限的人可以在 QQ 群里让服务器直接执行控制台命令（如 give、gamemode、ban），" +
                "命令结果会发回群里。账号被盗或误操作都会直接影响服务器。默认只有超级管理员可用。"),
        Definition("features.chat-bridge.game-to-qq", "游戏聊天转发到 QQ", "聊天互通", "游戏内聊天发送到允许的群"),
        Definition("features.chat-bridge.qq-to-game", "QQ 消息转发到游戏", "聊天互通", "允许的群消息广播到游戏"),
        Definition("features.player-notices.join-enabled", "进服通知", "进退服通知", "玩家进服时通知 QQ 群"),
        Definition("features.player-notices.quit-enabled", "退服通知", "进退服通知", "玩家退服时通知 QQ 群"),
        Definition("appearance.custom-backgrounds", "使用自定义底图", "图片外观", "把图片放进 config/virga/backgrounds（online-list / status / inventory / ender-chest .png/.jpg）即可替换背景层", true),
        Definition("panel.qr-connect.enabled", "扫码连接", "管理面板", "机器人连接页的扫码功能")
    )

    private val KEYS = DEFINITIONS.map { it.key }.toSet()

    fun isAllowed(key: String): Boolean = key in KEYS

    /** Keys being switched on that are high-risk and need the panel's confirmation. */
    fun riskyActivations(values: Map<String, Boolean>): List<String> =
        DEFINITIONS.filter { it.riskWarning != null && values[it.key] == true }.map { it.key }
}

data class SettingValue(
    val key: String,
    val label: String,
    val group: String,
    val description: String,
    val value: Boolean,
    val effect: String = "立即生效",
    val riskWarning: String? = null
)
