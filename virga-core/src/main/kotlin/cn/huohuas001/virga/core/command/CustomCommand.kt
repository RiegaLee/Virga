package cn.huohuas001.virga.core.command

import java.util.Locale

enum class CustomCommandPermission { MEMBER, ADMIN, ROOT }

/** Templates belong to the authenticated configuration surface, never to public help. */
class CustomCommand(
    val key: String,
    val template: String,
    val description: String = "请 Virga 帮忙完成这件事",
    val permission: CustomCommandPermission = CustomCommandPermission.ROOT,
    val enabled: Boolean = true,
    val panel: Boolean = false,
    val requireBinding: Boolean = true,
    val cooldownSeconds: Int = 5,
    val showFeedback: Boolean = false
) {
    override fun toString() = "CustomCommand(key=$key, permission=$permission, template=<private>)"
    fun presentation() = CommandPresentation("自定义功能", key, description,
        administratorOnly = permission != CustomCommandPermission.MEMBER, publishToPanel = panel,
        rootOnly = permission == CustomCommandPermission.ROOT, available = enabled)

    fun expand(arguments: String, player: String?): String {
        require(arguments.length <= 512 && arguments.none { it.isISOControl() }) { "参数过长或包含控制字符" }
        val tokens = arguments.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        // Public console templates accept atoms, not selectors, JSON, quoted payloads or new commands.
        if (permission == CustomCommandPermission.MEMBER) require(tokens.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) {
            "参数请使用游戏 ID、数字或普通英文词"
        }
        if (requireBinding || template.contains("{player}") || template.contains("{name}")) {
            require(player != null && player.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "要先绑定并验证游戏账号哦。" }
        }
        // One pass: parameter text cannot introduce another placeholder.
        return PLACEHOLDER.replace(template.trim().removePrefix("/")) { match ->
            when (val key = match.groupValues[1]) {
                "player", "name" -> player.orEmpty()
                "params" -> arguments.trim()
                else -> tokens.getOrNull(key.toInt()) ?: throw IllegalArgumentException("还缺参数哦。")
            }
        }.also { require(it.length <= 2048 && !CommandSafety.blocked(it)) { "这条指令不能远程执行。" } }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{(player|name|params|[0-9])}")
        fun validate(commands: List<CustomCommand>, reserved: Set<String>) {
            require(commands.size <= 32) { "自定义指令最多 32 条" }
            val used = reserved.map { it.lowercase(Locale.ROOT) }.toMutableSet()
            for (c in commands) {
                require(c.key.length in 1..14 && c.key.all { it.isLetterOrDigit() || it in "_-" }) { "触发词请使用 1–14 个中文、字母或数字" }
                require(used.add(c.key.lowercase(Locale.ROOT))) { "触发词与已有指令重复" }
                require(c.description.length in 1..30 && c.description.none(Char::isISOControl)) { "说明请填写 1–30 个字符" }
                val template = c.template.trim().removePrefix("/")
                require(template.length in 1..2048 && template.none(Char::isISOControl)) { "命令模板为空、过长或包含控制字符" }
                require(template.substringBefore(' ').matches(Regex("[A-Za-z0-9_:-]+"))) { "命令名称必须固定，不能使用参数" }
                // Other braces are allowed for fixed Minecraft JSON/NBT, but named unknown placeholders are not.
                require(!Regex("\\{[A-Za-z][A-Za-z0-9_-]*}").containsMatchIn(PLACEHOLDER.replace(template, ""))) { "仅支持 {player}、{name}、{params} 和 {0}–{9}" }
                require(!CommandSafety.blocked(template)) { "受保护的命令不能作为远程模板" }
                require(c.cooldownSeconds in 1..300) { "冷却时间为 1–300 秒" }
                if (c.panel) {
                    require(cn.huohuas001.virga.core.qq.QqPanelFields.displayWidth(c.key) <= 14) { "面板触发词最多 7 个汉字或 14 个英文字符" }
                    require(cn.huohuas001.virga.core.qq.QqPanelFields.displayWidth(c.description) <= 30) { "面板说明最多 15 个汉字或 30 个英文字符" }
                }
            }
        }
    }
}

object CommandSafety {
    private val blocked = setOf("stop", "restart", "reload", "op", "deop", "virga", "huhobot", "huohua")
    fun blocked(command: String): Boolean {
        val words = command.trim().removePrefix("/").lowercase(Locale.ROOT).split(Regex("\\s+"))
        return words.firstOrNull()?.substringAfter(':') in blocked ||
            words.zipWithNext().any { (a, b) -> a == "run" && b.substringAfter(':') in blocked }
    }
}
