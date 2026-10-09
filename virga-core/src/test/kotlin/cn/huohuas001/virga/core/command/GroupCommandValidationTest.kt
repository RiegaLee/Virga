package cn.huohuas001.virga.core.command

import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.config.GroupPurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupCommandValidationTest {
    private val catalog = listOf(
        CommandPresentation("常用", "帮助", "帮助"),
        CommandPresentation("常用", "在线列表", "在线"),
        CommandPresentation("账号", "绑定", "绑定"),
        CommandPresentation("管理", "执行命令", "执行", true, rootOnly = true)
    )

    @Test fun `management commands open in a player group only with the high-risk confirmation`() {
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("执行命令")), catalog)
        }
        assertEquals(listOf("执行命令"),
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("执行命令")), catalog, confirmHighRisk = true))
        // Already open before this save: no second confirmation.
        assertEquals(emptyList(),
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("执行命令")), catalog, previouslyAllowed = setOf("执行命令")))
        // Binding in a management group and management commands there are ordinary settings.
        GroupCommandValidation.validate(GroupPurpose.MANAGEMENT, listOf(rule("绑定")), catalog)
        GroupCommandValidation.validate(GroupPurpose.MANAGEMENT, listOf(rule("执行命令", "执行")), catalog)
    }

    @Test fun `renaming cannot hijack original commands or retired aliases and unicode width is checked`() {
        listOf("帮助", "菜单", "查在线", "inv", "virga", "查信息", "太长太长太长太长", "<@target>").forEach { label ->
            assertFailsWith<IllegalArgumentException>(label) {
                GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("在线列表", label)), catalog)
            }
        }
        GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("在线列表", "看看谁在线")), catalog)
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER,
                listOf(rule("在线列表").copy(description = "超过十五个汉字的指令说明会被明确拒绝")), catalog)
        }
    }

    @Test fun `unknown duplicate and excess panel entries are rejected before persistence`() {
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("未知")), catalog)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("帮助"), rule("帮助")), catalog)
        }
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER, listOf(rule("帮助").copy(allowed = false)), catalog)
        }
        val many = (1..21).map { CommandPresentation("扩展", "cmd$it", "测试") }
        assertFailsWith<IllegalArgumentException> {
            GroupCommandValidation.validate(GroupPurpose.PLAYER, many.map { rule(it.name) }, many)
        }
    }
    private fun rule(command: String, label: String = command) = GroupCommandRule(command, true, true, label, "测试")
}
