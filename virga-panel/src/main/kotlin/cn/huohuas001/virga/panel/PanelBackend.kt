package cn.huohuas001.virga.panel

import java.util.concurrent.CompletableFuture

/**
 * Everything the panel needs from the running plugin. Implemented in virga-server, where
 * game state is read through VirgaScheduler snapshots; implementations must never block
 * the calling HTTP thread and must never expose secrets.
 */
interface PanelBackend {
    fun overview(): CompletableFuture<PanelOverview>

    fun bot(): CompletableFuture<BotInfo>

    /** Saves credentials (a null secret keeps the stored one); they apply after a full restart. */
    fun saveBotCredentials(appId: String, secret: String?): CompletableFuture<SaveResult>

    /** Read on the HTTP thread; must be cheap and thread-safe. */
    fun qrConnectEnabled(): Boolean

    fun groups(): CompletableFuture<List<GroupEntry>>
    fun refreshGroupInfo(): CompletableFuture<SaveResult> = CompletableFuture.failedFuture(UnsupportedOperationException())

    /** Completes with a new version once the group list changes after [since] (long poll, no timeout). */
    fun groupsChanged(since: Long): CompletableFuture<Long>

    /** [allowed] null leaves the allow list unchanged; [note] null leaves the note unchanged. */
    fun updateGroup(groupOpenId: String, allowed: Boolean?, note: String?): CompletableFuture<SaveResult>

    fun groupCommands(groupOpenId: String): CompletableFuture<GroupCommandEditor> =
        CompletableFuture.failedFuture(UnsupportedOperationException())
    /** [confirmHighRisk]: the admin confirmed opening management commands in a player group. */
    fun saveGroupCommands(
        groupOpenId: String,
        purpose: String,
        commands: List<GroupCommandOption>,
        confirmHighRisk: Boolean = false
    ): CompletableFuture<SaveResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException())
    fun syncGroupCommands(): CompletableFuture<SaveResult> = CompletableFuture.failedFuture(UnsupportedOperationException())

    fun customCommands(): CompletableFuture<List<CustomCommandOption>> = CompletableFuture.failedFuture(UnsupportedOperationException())
    fun saveCustomCommands(commands: List<CustomCommandOption>): CompletableFuture<SaveResult> = CompletableFuture.failedFuture(UnsupportedOperationException())

    fun settings(): CompletableFuture<List<cn.huohuas001.virga.panel.settings.SettingValue>>

    /** Keys are already checked against the settings catalog. */
    fun updateSettings(values: Map<String, Boolean>): CompletableFuture<SaveResult>

    /** Persists panel.port after the server has proven it can bind the new port. */
    fun savePanelPort(port: Int): CompletableFuture<SaveResult>

    /** Members seen in a group, with their ROOT / administrator state. */
    fun groupMembers(groupOpenId: String): CompletableFuture<GroupMembers> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    /** Grants or revokes the group's dynamic administrator role (same as /加管理, /删管理). */
    fun setGroupAdministrator(groupOpenId: String, userOpenId: String, administrator: Boolean): CompletableFuture<SaveResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    /** Adds or removes a ROOT in administrators.roots (config.yml). */
    fun setRoot(userOpenId: String, root: Boolean): CompletableFuture<SaveResult> =
        CompletableFuture.failedFuture(UnsupportedOperationException())

    /** brand.server-address, already checked by [ServerAddressRule]; empty clears it. */
    fun saveServerAddress(address: String): CompletableFuture<SaveResult>
}

/**
 * The address the bot replies with when a group sends “服务器地址”. Each server owner fills in
 * their own; nothing is built in. Empty means the bot stays silent.
 */
object ServerAddressRule {
    const val MAX_LENGTH = 128

    /** The trimmed address, or null when it cannot be an address (spaces, control characters, too long). */
    fun normalize(raw: String): String? {
        val address = raw.trim()
        if (address.length > MAX_LENGTH) return null
        if (address.any { it.isWhitespace() || it.isISOControl() }) return null
        return address
    }
}

/** One person in the members dialog; the OpenID is what QQ gives bots instead of a QQ number. */
data class GroupMember(
    val openId: String,
    val display: String,
    val name: String?,
    val seenAtMillis: Long?,
    val root: Boolean,
    val administrator: Boolean
)

data class GroupMembers(
    val group: String,
    val members: List<GroupMember>,
    /** When true, QQ group owners/admins count as administrators without being listed here. */
    val trustQqGroupRoles: Boolean
)

data class GroupEntry(
    val id: String,
    val display: String,
    val allowed: Boolean,
    val note: String?,
    val lastSeenMillis: Long?,
    /** "nickname：text" preview of the latest message, in memory only; null when unknown. */
    val lastMessage: String? = null,
    /** QQ group name from the group info API; null until fetched. */
    val name: String? = null,
    val memberCount: Int? = null,
    val purpose: String = "PLAYER"
)

data class GroupCommandOption(
    val command: String, val allowed: Boolean, val panel: Boolean, val label: String, val description: String,
    val category: String = "", val permission: String = "群成员", val available: Boolean = true,
    val highRisk: Boolean = false, val defaultPanel: Boolean = true, val management: Boolean = false,
    val playerMutation: Boolean = false
)

data class PanelSyncInfo(val state: String, val message: String, val updatedAt: Long)
data class CustomCommandOption(
    val key: String, val template: String, val description: String, val permission: String,
    val enabled: Boolean, val panel: Boolean, val requireBinding: Boolean, val cooldownSeconds: Int,
    val showFeedback: Boolean
)
data class GroupCommandEditor(val purpose: String, val commands: List<GroupCommandOption>, val sync: PanelSyncInfo)

data class PanelOverview(
    val pluginVersion: String,
    val platform: String,
    val minecraftVersion: String,
    val javaVersion: String,
    val uptimeSeconds: Long,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val qqConnected: Boolean,
    val botConfigured: Boolean,
    val allowedGroups: Int,
    val panelPort: Int,
    val queues: QueueStatus,
    val pendingRestart: List<String>,
    /** Shown only to the logged-in owner so they can edit it. */
    val serverAddress: String = ""
)

data class QueueStatus(
    val worker: Int,
    val render: Int,
    val state: Int,
    val scheduled: Int
)

/** Bot connection page data. Never contains the secret; the AppID is masked. */
data class BotInfo(
    val enabled: Boolean,
    val configured: Boolean,
    val maskedAppId: String?,
    val connected: Boolean,
    val qrConnectEnabled: Boolean,
    val restartRequired: Boolean
)

data class SaveResult(val ok: Boolean, val message: String, val restartRequired: Boolean = false)

object BotCredentialRules {
    private val APP_ID = Regex("[0-9]{5,20}")
    // Printable ASCII without spaces.
    private val SECRET = Regex("[!-~]{8,128}")

    fun validAppId(value: String): Boolean = APP_ID.matches(value)
    fun validSecret(value: String): Boolean = SECRET.matches(value)

    /** e.g. 102****01; short ids are fully masked. */
    fun mask(appId: String): String =
        if (appId.length <= 5) "*".repeat(appId.length) else appId.take(3) + "****" + appId.takeLast(2)
}
