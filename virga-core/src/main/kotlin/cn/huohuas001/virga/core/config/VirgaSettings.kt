package cn.huohuas001.virga.core.config

/** Immutable runtime settings. Secrets are deliberately excluded from [toString]. */
class VirgaSettings(
    val configVersion: Int,
    val bot: BotSettings,
    val serverName: String,
    val serverAddress: String,
    val rootAdministrators: Set<String>,
    val trustQqGroupRoles: Boolean,
    val bridge: BridgeSettings,
    val playerNotices: PlayerNoticeSettings,
    val remoteCommands: RemoteCommandSettings,
    val runtime: RuntimeSettings,
    val performance: PerformanceFeatureSettings = PerformanceFeatureSettings(),
    val onlineList: OnlineListFeatureSettings = OnlineListFeatureSettings(),
    val binding: BindingFeatureSettings = BindingFeatureSettings(),
    val inventory: InventoryFeatureSettings = InventoryFeatureSettings(),
    val panel: PanelSettings = PanelSettings(),
    val groupCommands: GroupCommandSettings = GroupCommandSettings(),
    val customCommands: List<cn.huohuas001.virga.core.command.CustomCommand> = emptyList(),
    val appearance: AppearanceSettings = AppearanceSettings()
) {
    init {
        require(configVersion >= 1) { "configVersion must be positive" }
        require(serverName.isNotBlank()) { "serverName must not be blank" }
    }

    fun isAllowedGroup(groupOpenId: String): Boolean = groupOpenId in bot.allowedGroups

    fun isCommandAllowed(group: String, command: String, administratorOnly: Boolean = false): Boolean =
        isAllowedGroup(group) && groupCommands.allows(group, command, administratorOnly)

    fun withBot(replacement: BotSettings): VirgaSettings = VirgaSettings(
        configVersion,
        replacement,
        serverName,
        serverAddress,
        rootAdministrators,
        trustQqGroupRoles,
        bridge,
        playerNotices,
        remoteCommands,
        runtime,
        performance,
        onlineList,
        binding,
        inventory,
        panel,
        groupCommands,
        customCommands,
        appearance
    )

    override fun toString(): String =
        "VirgaSettings(configVersion=$configVersion, bot=$bot, serverName=$serverName, " +
            "serverAddress=$serverAddress, " +
            "rootAdministrators=${rootAdministrators.size}, trustQqGroupRoles=$trustQqGroupRoles, " +
            "bridge=$bridge, playerNotices=$playerNotices, remoteCommands=$remoteCommands, runtime=$runtime, " +
            "performance=$performance, onlineList=$onlineList, binding=$binding, inventory=$inventory, " +
            "appearance=$appearance)"
}

class BotSettings(
    val enabled: Boolean,
    val appId: String,
    val secret: String,
    val displayName: String,
    val allowedGroups: Set<String>,
    val suppressSdkConsoleOutput: Boolean
) {
    init {
        require(displayName.isNotBlank()) { "bot displayName must not be blank" }
    }

    val hasCredentials: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()

    fun withAllowedGroups(replacement: Set<String>): BotSettings = BotSettings(
        enabled,
        appId,
        secret,
        displayName,
        replacement,
        suppressSdkConsoleOutput
    )

    override fun toString(): String =
        "BotSettings(enabled=$enabled, appId=${masked(appId)}, secret=<redacted>, " +
            "displayName=$displayName, allowedGroups=${allowedGroups.size}, " +
            "suppressSdkConsoleOutput=$suppressSdkConsoleOutput)"

    private fun masked(value: String): String = when {
        value.isBlank() -> "<empty>"
        value.length <= 4 -> "****"
        else -> value.take(2) + "***" + value.takeLast(2)
    }
}

data class BridgeSettings(
    val gameToQq: Boolean,
    val qqToGame: Boolean,
    val gamePrefix: String,
    val gameToQqFormat: String,
    val qqToGameFormat: String
)

data class PlayerNoticeSettings(
    val joinEnabled: Boolean,
    val quitEnabled: Boolean,
    val joinFormat: String,
    val quitFormat: String
)

data class RemoteCommandSettings(
    val enabled: Boolean,
    val rootOnly: Boolean
)

data class RuntimeSettings(
    val workerThreads: Int,
    val queueCapacity: Int,
    val scheduledTaskLimit: Int,
    val addonTimeoutSeconds: Long,
    val imageLimitBytes: Int,
    val renderQueueCapacity: Int = 8,
    val skinThreads: Int = 4,
    val skinQueueCapacity: Int = 64,
    val imageCommandTimeoutSeconds: Long = 15,
    val stateQueueCapacity: Int = 64
) {
    init {
        require(workerThreads in 1..16) { "workerThreads must be between 1 and 16" }
        require(queueCapacity in 8..4096) { "queueCapacity must be between 8 and 4096" }
        require(scheduledTaskLimit in 8..4096) { "scheduledTaskLimit must be between 8 and 4096" }
        require(addonTimeoutSeconds in 1..300) { "addonTimeoutSeconds must be between 1 and 300" }
        require(imageLimitBytes in 1024..32 * 1024 * 1024) { "imageLimitBytes is outside the safe range" }
        require(renderQueueCapacity in 1..128) { "renderQueueCapacity must be between 1 and 128" }
        require(skinThreads in 1..8) { "skinThreads must be between 1 and 8" }
        require(skinQueueCapacity in 8..1024) { "skinQueueCapacity must be between 8 and 1024" }
        require(imageCommandTimeoutSeconds in 1..60) { "imageCommandTimeoutSeconds must be between 1 and 60" }
        require(stateQueueCapacity in 8..1024) { "stateQueueCapacity must be between 8 and 1024" }
    }
}

data class PerformanceFeatureSettings(
    val enabled: Boolean = true,
    val cooldownSeconds: Long = 5,
    val fontFamily: String = ""
)

data class OnlineListFeatureSettings(
    val enabled: Boolean = true,
    val cooldownSeconds: Long = 3,
    val pageSize: Int = 27,
    val administratorsFirst: Boolean = true,
    val administratorPermission: String = "virga.online.priority",
    val columns: Int = 3,
    val fontFamily: String = "",
    val footerText: String = "POWERED BY Virga",
    val skinEnabled: Boolean = true,
    val skinConnectTimeoutMs: Int = 2_000,
    val skinReadTimeoutMs: Int = 3_000,
    val skinCacheEntries: Int = 200,
    val fallbackAvatar: String = "steve"
)

data class BindingFeatureSettings(
    val enabled: Boolean = true,
    val challengeExpireSeconds: Long = 300,
    val confirmationExpireSeconds: Long = 120,
    val challengeCooldownSeconds: Long = 10,
    val maxAttempts: Int = 3,
    val maxAccounts: Int = 2,
    val authMeRequired: Boolean = true,
    val selectionExpireSeconds: Long = 60,
    val forceBind: Boolean = false,
    val forceBindGroups: List<String> = emptyList(),
    val verifyExempt: Set<String> = emptySet()
)

data class InventoryFeatureSettings(
    val enabled: Boolean = true,
    val enderChestEnabled: Boolean = true,
    val cooldownSeconds: Long = 5,
    val periodicSnapshotSeconds: Int = 300,
    val theme: String = "virga",
    val playerHeadTexturesEnabled: Boolean = true,
    val playerHeadMemoryEntries: Int = 4096,
    val playerHeadMaxNewPerRequest: Int = 4,
    val playerHeadMaxOutstanding: Int = 32,
    val playerHeadMaxConcurrent: Int = 2,
    val playerHeadNegativeCacheSeconds: Long = 600,
    val playerHeadConnectTimeoutMs: Int = 2000,
    val playerHeadReadTimeoutMs: Int = 3000
)

/**
 * Image backdrops. Custom pictures live in config/virga/backgrounds/<surface>.png|jpg and replace only the
 * background layer; the cards, slots and the theme foreground stay on top. [veilPercent] lightens custom pictures (0..80).
 */
data class AppearanceSettings(
    val customBackgrounds: Boolean = true,
    val veilPercent: Int = 15
) {
    init {
        require(veilPercent in 0..80) { "appearance.veil-percent must be between 0 and 80" }
    }
}

/** Loopback-only management panel (Web UI). Enabled by default; see docs/PANEL.md. */
data class PanelSettings(
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val qrConnectEnabled: Boolean = true
) {
    init {
        require(port in 1024..65535) { "panel.port must be between 1024 and 65535" }
    }

    companion object {
        const val DEFAULT_PORT = 56789
    }
}
