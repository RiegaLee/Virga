package cn.huohuas001.virga.core.config

import java.util.Locale

enum class GroupPurpose { PLAYER, MANAGEMENT }

/** List order is the saved order; commands registered later are appended by the router. */
data class GroupCommandRule(
    val command: String,
    val allowed: Boolean,
    val panel: Boolean,
    val label: String = command,
    val description: String = ""
)

data class GroupCommandProfile(
    val purpose: GroupPurpose = GroupPurpose.PLAYER,
    val commands: List<GroupCommandRule> = emptyList()
)

data class GroupCommandSettings(val groups: Map<String, GroupCommandProfile> = emptyMap()) {
    fun profile(group: String): GroupCommandProfile = groups[group] ?: GroupCommandProfile()

    /**
     * Whether [command] may be used in [group]. The group's purpose only decides the defaults: any
     * command can be opened in any group, and user permissions (administrator, ROOT) still apply.
     */
    fun allows(group: String, command: String, administratorOnly: Boolean = false): Boolean {
        val profile = profile(group)
        val key = canonical(command)
        return profile.commands.firstOrNull { canonical(it.command) == key }?.allowed
            ?: defaultAllowed(profile.purpose, key, administratorOnly)
    }

    fun defaultAllowed(purpose: GroupPurpose, command: String, administratorOnly: Boolean): Boolean =
        if (purpose == GroupPurpose.PLAYER) !administratorOnly && canonical(command) !in MANAGEMENT_COMMANDS
        else administratorOnly || canonical(command) in MANAGEMENT_COMMANDS || canonical(command) in SHARED_COMMANDS

    /**
     * High-risk setting: a management command opened in a player group. Group administrators (or
     * ROOT) can then use it where every player reads along, so the panel asks for confirmation.
     */
    fun highRisk(purpose: GroupPurpose, command: String, administratorOnly: Boolean): Boolean =
        purpose == GroupPurpose.PLAYER && (administratorOnly || canonical(command) in MANAGEMENT_COMMANDS)

    companion object {
        val MANAGEMENT_COMMANDS = setOf("查管理", "加管理", "删管理", "全量", "执行命令", "强制解绑")
        private val SHARED_COMMANDS = setOf("帮助", "服务器状态", "在线列表", "查在线", "服务器地址")
        val ALIASES = mapOf(
            "菜单" to "帮助", "执行" to "执行命令", "管理员执行" to "执行命令",
            "我的绑定" to "查绑", "inventory" to "我的背包", "inv" to "我的背包",
            "enderchest" to "我的末影箱", "ec" to "我的末影箱"
        )
        fun canonical(command: String): String = command.trim().lowercase(Locale.ROOT).let { ALIASES[it] ?: it }
    }
}
