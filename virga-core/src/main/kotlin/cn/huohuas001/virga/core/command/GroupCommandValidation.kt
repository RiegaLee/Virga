package cn.huohuas001.virga.core.command

import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.config.GroupCommandSettings
import cn.huohuas001.virga.core.config.GroupPurpose
import cn.huohuas001.virga.core.qq.QqPanelFields
import java.util.Locale

/** Validate the full draft before changing any live configuration. UI permission flags are not trusted. */
object GroupCommandValidation {
    /**
     * [previouslyAllowed] holds the commands this group already allowed. Newly opening a high-risk
     * command (see [GroupCommandSettings.highRisk]) is refused unless [confirmHighRisk] is set.
     * Returns the high-risk commands this draft newly opens.
     */
    fun validate(
        purpose: GroupPurpose,
        rules: List<GroupCommandRule>,
        catalog: List<CommandPresentation>,
        previouslyAllowed: Set<String> = emptySet(),
        confirmHighRisk: Boolean = false
    ): List<String> {
        val definitions = catalog.associateBy { GroupCommandSettings.canonical(it.name) }
        require(rules.map { it.command }.distinct().size == rules.size) { "不能重复配置同一条指令" }
        val reserved = catalog.flatMap { listOf(it.name) + it.aliases }.map { it.lowercase(Locale.ROOT) }.toSet() +
            GroupCommandSettings.ALIASES.keys + CoreCommandRouter.reservedTokens()
        val labels = HashSet<String>()
        val newlyHighRisk = ArrayList<String>()
        rules.forEach { c ->
            val d = definitions[c.command] ?: throw IllegalArgumentException("未知指令：${c.command}")
            if (c.allowed && c.command !in previouslyAllowed &&
                GroupCommandSettings().highRisk(purpose, c.command, d.administratorOnly || d.rootOnly)) {
                newlyHighRisk += c.command
            }
            require(!c.panel || c.allowed) { "请先允许使用 ${c.command}，再显示面板入口" }
            val key = c.label.lowercase(Locale.ROOT)
            require(c.label.isNotBlank() && c.label.length <= 128 && c.label.all {
                it.isLetterOrDigit() || it == '_' || it == '-'
            }) { "展示名称请使用中文、字母、数字、下划线或短横线" }
            require(labels.add(key)) { "展示名称不能重复" }
            require(key == d.name.lowercase(Locale.ROOT) || key in d.aliases ||
                GroupCommandSettings.ALIASES[key] == c.command || key !in reserved) { "展示名称与其他指令或别名冲突：${c.label}" }
            require(d.category != "旧版功能" || c.label == d.name) { "旧版扩展指令暂不支持改名" }
            require(c.description.length <= 200 && c.description.none { it.isISOControl() }) { "指令说明过长或包含控制字符" }
            if (c.panel) {
                require(QqPanelFields.displayWidth(c.label) <= QqPanelFields.MAX_NAME_WIDTH) { "${c.label} 名称过长（最多 7 个汉字或 14 个英文字符）" }
                require(c.description.isNotBlank() && QqPanelFields.displayWidth(c.description) <= QqPanelFields.MAX_DESCRIPTION_WIDTH) {
                    "${c.label} 说明最多 15 个汉字或 30 个英文字符，且不能为空"
                }
            }
        }
        require(rules.count { it.panel } <= 20) { "每群指令面板最多 20 项，请减少入口" }
        require(newlyHighRisk.isEmpty() || confirmHighRisk) {
            "在玩家群开放管理指令（${newlyHighRisk.joinToString("、") { "/$it" }}）属于高危设置，请确认后再保存"
        }
        return newlyHighRisk
    }
}
