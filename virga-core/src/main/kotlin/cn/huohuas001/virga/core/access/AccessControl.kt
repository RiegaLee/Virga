package cn.huohuas001.virga.core.access

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.PrincipalRole
import cn.huohuas001.virga.core.config.VirgaSettings
import java.util.Locale

class AccessControl(
    private val settings: () -> VirgaSettings,
    private val administrators: AdministratorRepository,
    private val rootIdentity: (String, Set<String>) -> Boolean = { _, _ -> false }
) {
    fun senderOpenId(message: BotMessage): String =
        message.sender.openId?.trim().takeUnless { it.isNullOrEmpty() }
            ?: message.sender.id?.trim().orEmpty()

    fun isRoot(userOpenId: String): Boolean = userOpenId.isNotBlank() &&
        (userOpenId in settings().rootAdministrators || rootIdentity(userOpenId, settings().rootAdministrators))

    fun isAdministrator(message: BotMessage): Boolean {
        val user = senderOpenId(message)
        if (isRoot(user) || administrators.contains(message.groupOpenId, user)) return true
        if (!settings().trustQqGroupRoles) return false
        return normalizeRole(message.sender.role) in QQ_ADMIN_ROLES
    }

    fun role(message: BotMessage): PrincipalRole = when {
        isRoot(senderOpenId(message)) -> PrincipalRole.OWNER
        isAdministrator(message) -> PrincipalRole.ADMIN
        normalizeRole(message.sender.role) in QQ_MEMBER_ROLES -> PrincipalRole.MEMBER
        else -> PrincipalRole.UNKNOWN
    }

    fun canMutateAdministrators(message: BotMessage): Boolean = isAdministrator(message)

    fun canRemove(targetOpenId: String): Boolean = !isRoot(targetOpenId)

    private fun normalizeRole(value: String?): String = value.orEmpty().trim().uppercase(Locale.ROOT)

    companion object {
        private val QQ_ADMIN_ROLES = setOf("OWNER", "ADMIN", "ADMINISTRATOR", "群主", "管理员")
        private val QQ_MEMBER_ROLES = setOf("MEMBER", "成员")
    }
}
