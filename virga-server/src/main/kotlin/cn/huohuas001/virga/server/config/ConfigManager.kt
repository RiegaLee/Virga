package cn.huohuas001.virga.server.config

import cn.huohuas001.virga.core.config.AppearanceSettings
import cn.huohuas001.virga.core.config.BotSettings
import cn.huohuas001.virga.core.config.GroupCommandSettings
import cn.huohuas001.virga.core.config.GroupCommandProfile
import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.config.GroupPurpose
import cn.huohuas001.virga.core.config.BridgeSettings
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.FeatureMessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.config.PanelSettings
import cn.huohuas001.virga.core.config.PlayerNoticeSettings
import cn.huohuas001.virga.core.config.PerformanceFeatureSettings
import cn.huohuas001.virga.core.config.RemoteCommandSettings
import cn.huohuas001.virga.core.config.RuntimeSettings
import cn.huohuas001.virga.core.config.OnlineListFeatureSettings
import cn.huohuas001.virga.core.config.BindingFeatureSettings
import cn.huohuas001.virga.core.config.InventoryFeatureSettings
import cn.huohuas001.virga.core.config.BindingMessageCatalog
import cn.huohuas001.virga.core.config.InventoryMessageCatalog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads `config.yml` and `messages.yml` from the Virga data directory, copying the bundled
 * defaults on first start. [config] is the live document the panel and enrollment write back.
 */
class ConfigManager(
    private val dataDirectory: Path,
    private val defaults: (String) -> java.io.InputStream?,
    private val logger: cn.huohuas001.virga.core.VirgaLogger
) {
    private val configFile: Path = dataDirectory.resolve("config.yml")
    private val messagesFile: Path = dataDirectory.resolve("messages.yml")

    /** The parsed config.yml; only touched on the server thread. */
    var config: YamlConfig = YamlConfig.empty()
        private set

    fun initialize(): LoadedConfiguration {
        Files.createDirectories(dataDirectory)
        copyDefault("config.yml", configFile)
        copyDefault("messages.yml", messagesFile)
        return reload()
    }

    fun reload(): LoadedConfiguration {
        config = YamlConfig.load(configFile)
        return load(messagesFile)
    }

    fun configPath(): Path = configFile

    private fun copyDefault(resource: String, target: Path) {
        if (Files.isRegularFile(target)) return
        val input = defaults(resource) ?: error("模组 JAR 缺少默认 $resource")
        input.use { Files.copy(it, target) }
    }
    private fun loadCustomCommands(config: YamlConfig): List<cn.huohuas001.virga.core.command.CustomCommand> {
        val commands = config.getMapList("custom-commands").map { row ->
            cn.huohuas001.virga.core.command.CustomCommand(
                key = row["key"]?.toString().orEmpty(), template = row["template"]?.toString().orEmpty(),
                description = row["description"]?.toString() ?: "请 Virga 帮忙完成这件事",
                permission = cn.huohuas001.virga.core.command.CustomCommandPermission.valueOf(row["permission"]?.toString() ?: "ROOT"),
                enabled = row["enabled"] as? Boolean ?: true, panel = row["panel"] as? Boolean ?: false,
                requireBinding = row["requireBinding"] as? Boolean ?: true,
                cooldownSeconds = (row["cooldownSeconds"] as? Number)?.toInt() ?: 5,
                showFeedback = row["showFeedback"] as? Boolean ?: false
            )
        }
        cn.huohuas001.virga.core.command.CustomCommand.validate(commands,
            cn.huohuas001.virga.core.command.CoreCommandRouter.reservedTokens())
        return commands
    }

    private fun load(messagesFile: Path): LoadedConfiguration {
        val config = config
        val version = config.getInt("config-version", 1)
        require(version == 1) { "不支持的 config-version: $version" }

        val settings = VirgaSettings(
            configVersion = version,
            bot = BotSettings(
                enabled = config.getBoolean("bot.enabled", false),
                appId = config.getString("bot.app-id").orEmpty().trim(),
                secret = config.getString("bot.secret").orEmpty().trim(),
                displayName = config.getString("brand.bot-name", "Virga")!!.trim().ifEmpty { "Virga" },
                allowedGroups = stringSet(config, "bot.groups"),
                suppressSdkConsoleOutput = config.getBoolean("bot.suppress-sdk-console-output", true)
            ),
            serverName = config.getString("brand.server-name", "MinecraftServer").orEmpty().trim()
                .ifEmpty { "MinecraftServer" },
            serverAddress = config.getString("brand.server-address", "").orEmpty().trim(),
            rootAdministrators = stringSet(config, "administrators.roots"),
            trustQqGroupRoles = config.getBoolean("administrators.trust-qq-group-roles", false),
            bridge = BridgeSettings(
                gameToQq = config.getBoolean("features.chat-bridge.game-to-qq", false),
                qqToGame = config.getBoolean("features.chat-bridge.qq-to-game", false),
                gamePrefix = config.getString("features.chat-bridge.game-prefix", "")!!,
                gameToQqFormat = config.getString(
                    "features.chat-bridge.game-to-qq-format",
                    "[游戏] {name}: {message}"
                )!!,
                qqToGameFormat = config.getString(
                    "features.chat-bridge.qq-to-game-format",
                    "[QQ] {name}: {message}"
                )!!
            ),
            playerNotices = PlayerNoticeSettings(
                joinEnabled = config.getBoolean("features.player-notices.join-enabled", false),
                quitEnabled = config.getBoolean("features.player-notices.quit-enabled", false),
                joinFormat = config.getString(
                    "features.player-notices.join-format",
                    "{name} 进入了 {server}，欢迎～"
                )!!,
                quitFormat = config.getString(
                    "features.player-notices.quit-format",
                    "{name} 离开了 {server}，下次再来玩哦"
                )!!
            ),
            remoteCommands = RemoteCommandSettings(
                enabled = config.getBoolean("features.remote-commands.enabled", false),
                rootOnly = config.getBoolean("features.remote-commands.root-only", true)
            ),
            runtime = RuntimeSettings(
                workerThreads = bounded(config, "runtime.worker-threads", 1, 16, 2),
                queueCapacity = bounded(config, "runtime.queue-capacity", 8, 4096, 128),
                scheduledTaskLimit = bounded(config, "runtime.scheduled-task-limit", 8, 4096, 128),
                addonTimeoutSeconds = bounded(config, "runtime.addon-timeout-seconds", 1, 300, 30).toLong(),
                imageLimitBytes = bounded(
                    config,
                    "runtime.image-limit-bytes",
                    1024,
                    32 * 1024 * 1024,
                    8 * 1024 * 1024
                ),
                renderQueueCapacity = bounded(config, "runtime.render-queue-capacity", 1, 128, 8),
                skinThreads = bounded(config, "runtime.skin-threads", 1, 8, 4),
                skinQueueCapacity = bounded(config, "runtime.skin-queue-capacity", 8, 1024, 64),
                imageCommandTimeoutSeconds = bounded(
                    config,
                    "runtime.image-command-timeout-seconds",
                    1,
                    60,
                    15
                ).toLong(),
                stateQueueCapacity = bounded(config, "runtime.state-queue-capacity", 8, 1024, 64)
            ),
            performance = PerformanceFeatureSettings(
                enabled = config.getBoolean("features.performance.enabled", true),
                cooldownSeconds = bounded(config, "features.performance.cooldown-seconds", 0, 300, 5).toLong(),
                fontFamily = config.getString("features.performance.font-family", "").orEmpty().trim()
            ),
            onlineList = OnlineListFeatureSettings(
                enabled = config.getBoolean("features.online-list.enabled", true),
                cooldownSeconds = bounded(config, "features.online-list.cooldown-seconds", 0, 300, 3).toLong(),
                pageSize = bounded(config, "features.online-list.page-size", 1, 60, 27),
                administratorsFirst = config.getBoolean("features.online-list.administrators-first", true),
                administratorPermission = config.getString(
                    "features.online-list.administrator-permission",
                    "virga.online.priority"
                ).orEmpty().trim(),
                columns = bounded(config, "features.online-list.columns", 1, 3, 3),
                fontFamily = config.getString("features.online-list.font-family", "").orEmpty().trim(),
                footerText = config.getString(
                    "features.online-list.footer-text",
                    "POWERED BY Virga"
                ).orEmpty(),
                skinEnabled = config.getBoolean("features.online-list.skin.enabled", true),
                skinConnectTimeoutMs = bounded(
                    config,
                    "features.online-list.skin.connect-timeout-ms",
                    250,
                    10_000,
                    2_000
                ),
                skinReadTimeoutMs = bounded(
                    config,
                    "features.online-list.skin.read-timeout-ms",
                    250,
                    15_000,
                    3_000
                ),
                skinCacheEntries = bounded(config, "features.online-list.skin.cache-entries", 8, 2000, 200),
                fallbackAvatar = config.getString("features.online-list.skin.fallback-avatar", "steve")
                    .orEmpty().trim().ifEmpty { "steve" }
            ),
            binding = BindingFeatureSettings(
                enabled = config.getBoolean("features.binding.enabled", true),
                challengeExpireSeconds = bounded(config, "features.binding.challenge-expire-seconds", 30, 3600, 300).toLong(),
                confirmationExpireSeconds = bounded(
                    config,
                    "features.binding.confirmation-expire-seconds",
                    30,
                    600,
                    120
                ).toLong(),
                challengeCooldownSeconds = bounded(config, "features.binding.challenge-cooldown-seconds", 0, 300, 10).toLong(),
                maxAttempts = bounded(config, "features.binding.max-attempts", 1, 10, 3),
                maxAccounts = bounded(config, "features.binding.max-accounts", 1, 10, 2),
                // A modded server has no login plugin: the vanilla session check is the authentication.
                authMeRequired = false,
                selectionExpireSeconds = bounded(config, "features.binding.selection-expire-seconds", 10, 300, 60).toLong(),
                forceBind = config.getBoolean("features.binding.force-bind", false),
                forceBindGroups = config.getStringList("features.binding.force-bind-groups")
                    .map(String::trim).filter { it.matches(Regex("[0-9]{5,12}")) }.distinct(),
                verifyExempt = config.getStringList("features.binding.verify-exempt")
                    .map(String::trim).filter { it.matches(Regex("[A-Za-z0-9_]{1,16}")) }.toSet()
            ),
            inventory = InventoryFeatureSettings(
                enabled = config.getBoolean("features.inventory.enabled", true),
                enderChestEnabled = config.getBoolean("features.inventory.ender-chest-enabled", true),
                cooldownSeconds = bounded(config, "features.inventory.cooldown-seconds", 0, 300, 5).toLong(),
                periodicSnapshotSeconds = bounded(config, "features.inventory.periodic-snapshot-seconds", 30, 3600, 300),
                theme = config.getString("features.inventory.theme", "virga").orEmpty().trim().ifEmpty { "virga" },
                playerHeadTexturesEnabled = config.getBoolean("features.inventory.player-head-textures.enabled", true),
                playerHeadMemoryEntries = bounded(config, "features.inventory.player-head-textures.memory-entries", 2000, 5000, 4096),
                playerHeadMaxNewPerRequest = bounded(config, "features.inventory.player-head-textures.max-new-per-request", 1, 16, 4),
                playerHeadMaxOutstanding = bounded(config, "features.inventory.player-head-textures.max-outstanding", 4, 128, 32),
                playerHeadMaxConcurrent = bounded(config, "features.inventory.player-head-textures.max-concurrent", 1, 2, 2),
                playerHeadNegativeCacheSeconds = bounded(config, "features.inventory.player-head-textures.negative-cache-seconds", 60, 3600, 600).toLong(),
                playerHeadConnectTimeoutMs = bounded(config, "features.inventory.player-head-textures.connect-timeout-ms", 250, 15000, 2000),
                playerHeadReadTimeoutMs = bounded(config, "features.inventory.player-head-textures.read-timeout-ms", 250, 15000, 3000)
            ),
            groupCommands = loadGroupCommands(config),
            customCommands = loadCustomCommands(config),
            panel = PanelSettings(
                enabled = config.getBoolean("panel.enabled", true),
                port = bounded(config, "panel.port", 1024, 65535, PanelSettings.DEFAULT_PORT),
                qrConnectEnabled = config.getBoolean("panel.qr-connect.enabled", true)
            ),
            appearance = AppearanceSettings(
                customBackgrounds = config.getBoolean("appearance.custom-backgrounds", true),
                veilPercent = bounded(config, "appearance.veil-percent", 0, 80, 15)
            )
        )

        if (settings.bot.enabled && !settings.bot.hasCredentials) {
            logger.warning("bot.enabled=true，但 AppID 或 Secret 为空；QQ 客户端不会启动。")
        }
        if (settings.bot.enabled && settings.bot.allowedGroups.isEmpty()) {
            logger.warning("bot.groups 为空；QQ群消息都会被拒绝。请在游戏内执行 /virga group add 接入目标群。")
        }

        return LoadedConfiguration(settings, loadMessages(messagesFile))
    }


    private fun loadGroupCommands(config: YamlConfig): GroupCommandSettings {
        val base = "qq-command-panels.groups"
        val groups = config.getKeys(base).takeIf { it.isNotEmpty() } ?: return GroupCommandSettings()
        return GroupCommandSettings(groups.associateWith { group ->
            val purpose = GroupPurpose.valueOf(config.getString("$base.$group.purpose", "PLAYER")!!.uppercase())
            val rules = config.getMapList("$base.$group.commands").map { entry ->
                GroupCommandRule(
                    command = entry["command"]?.toString().orEmpty(),
                    allowed = entry["allowed"] == true,
                    panel = entry["panel"] == true,
                    label = entry["label"]?.toString() ?: entry["command"]?.toString().orEmpty(),
                    description = entry["description"]?.toString().orEmpty()
                )
            }
            GroupCommandProfile(purpose, rules)
        })
    }

    private fun loadMessages(file: Path): MessageCatalog {
        val yaml = YamlConfig.load(file)
        val d = MessageCatalog()
        fun text(path: String, fallback: String): String = yaml.getString(path, fallback) ?: fallback
        return MessageCatalog(
            prefix = text("prefix", d.prefix),
            noPermission = text("no-permission", d.noPermission),
            unavailableGroup = text("unavailable-group", d.unavailableGroup),
            information = text("information", d.information),
            administratorList = text("administrator-list", d.administratorList),
            administratorAdded = text("administrator-added", d.administratorAdded),
            administratorRemoved = text("administrator-removed", d.administratorRemoved),
            administratorAlreadyPresent = text("administrator-already-present", d.administratorAlreadyPresent),
            administratorMissing = text("administrator-missing", d.administratorMissing),
            protectedRoot = text("protected-root", d.protectedRoot),
            targetRequired = text("target-required", d.targetRequired),
            stateWriteFailed = text("state-write-failed", d.stateWriteFailed),
            bridgeDisabled = text("bridge-disabled", d.bridgeDisabled),
            unknownCommand = text("unknown-command", d.unknownCommand),
            addonFailure = text("addon-failure", d.addonFailure),
            commandRequired = text("command-required", d.commandRequired),
            commandBlocked = text("command-blocked", d.commandBlocked),
            commandAccepted = text("command-accepted", d.commandAccepted),
            commandFailed = text("command-failed", d.commandFailed),
            serverAddress = text("server-address", d.serverAddress),
            features = FeatureMessageCatalog(
                performanceDisabled = text("features.performance-disabled", d.features.performanceDisabled),
                onlineListDisabled = text("features.online-list-disabled", d.features.onlineListDisabled),
                performanceSnapshotFailed = text("features.performance-snapshot-failed", d.features.performanceSnapshotFailed),
                onlineSnapshotFailed = text("features.online-snapshot-failed", d.features.onlineSnapshotFailed),
                pageInvalid = text("features.page-invalid", d.features.pageInvalid),
                pageOutOfRange = text("features.page-out-of-range", d.features.pageOutOfRange),
                commandBusy = text("features.command-busy", d.features.commandBusy),
                commandCooldown = text("features.command-cooldown", d.features.commandCooldown),
                performanceFallback = text("features.performance-fallback", d.features.performanceFallback),
                onlineEmpty = text("features.online-empty", d.features.onlineEmpty),
                onlineFallback = text("features.online-fallback", d.features.onlineFallback),
                onlinePage = text("features.online-page", d.features.onlinePage),
                administratorSuffix = text("features.administrator-suffix", d.features.administratorSuffix)
            ),
            binding = BindingMessageCatalog(
                unavailable = text("binding.unavailable", d.binding.unavailable),
                notReady = text("binding.not-ready", d.binding.notReady),
                usageQq = text("binding.usage-qq", d.binding.usageQq),
                usageGame = text("binding.usage-game", d.binding.usageGame),
                loginRequired = text("binding.login-required", d.binding.loginRequired),
                codeCreated = text("binding.code-created", d.binding.codeCreated),
                codeReused = text("binding.code-reused", d.binding.codeReused),
                codeRotatedAfterExposure = text("binding.code-rotated-after-exposure", d.binding.codeRotatedAfterExposure),
                codeRevokedAfterExposure = text("binding.code-revoked-after-exposure", d.binding.codeRevokedAfterExposure),
                confirmationRequiredQq = text("binding.confirmation-required-qq", d.binding.confirmationRequiredQq),
                confirmationPendingGame = text("binding.confirmation-pending-game", d.binding.confirmationPendingGame),
                confirmationRejectedQq = text("binding.confirmation-rejected-qq", d.binding.confirmationRejectedQq),
                confirmationExpiredQq = text("binding.confirmation-expired-qq", d.binding.confirmationExpiredQq),
                confirmationPlayerOfflineQq = text("binding.confirmation-player-offline-qq", d.binding.confirmationPlayerOfflineQq),
                confirmationInvalidGame = text("binding.confirmation-invalid-game", d.binding.confirmationInvalidGame),
                confirmationAlreadyHandledGame = text("binding.confirmation-already-handled-game", d.binding.confirmationAlreadyHandledGame),
                confirmationRejectedGame = text("binding.confirmation-rejected-game", d.binding.confirmationRejectedGame),
                verified = text("binding.verified", d.binding.verified),
                verifiedGame = text("binding.verified-game", d.binding.verifiedGame),
                invalidCode = text("binding.invalid-code", d.binding.invalidCode),
                expired = text("binding.expired", d.binding.expired),
                rateLimited = text("binding.rate-limited", d.binding.rateLimited),
                conflict = text("binding.conflict", d.binding.conflict),
                alreadyBound = text("binding.already-bound", d.binding.alreadyBound),
                accountLimit = text("binding.account-limit", d.binding.accountLimit),
                listEmpty = text("binding.list-empty", d.binding.listEmpty),
                list = text("binding.list", d.binding.list),
                lookupTargetRequired = text("binding.lookup-target-required", d.binding.lookupTargetRequired),
                listOtherEmpty = text("binding.list-other-empty", d.binding.listOtherEmpty),
                listOther = text("binding.list-other", d.binding.listOther),
                unbound = text("binding.unbound", d.binding.unbound),
                forceUnbindUsage = text("binding.force-unbind-usage", d.binding.forceUnbindUsage),
                forceUnbindRootOnly = text("binding.force-unbind-root-only", d.binding.forceUnbindRootOnly),
                forceUnbindTargetEmpty = text("binding.force-unbind-target-empty", d.binding.forceUnbindTargetEmpty),
                forceUnbindSelectTitle = text("binding.force-unbind-select-title", d.binding.forceUnbindSelectTitle),
                forceUnbindConfirmTitle = text("binding.force-unbind-confirm-title", d.binding.forceUnbindConfirmTitle),
                forceUnbound = text("binding.force-unbound", d.binding.forceUnbound),
                ownerLookupUsage = text("binding.owner-lookup-usage", d.binding.ownerLookupUsage),
                ownerLookupEmpty = text("binding.owner-lookup-empty", d.binding.ownerLookupEmpty),
                ownerLookupFound = text("binding.owner-lookup-found", d.binding.ownerLookupFound),
                ownerLookupAbsent = text("binding.owner-lookup-absent", d.binding.ownerLookupAbsent),
                ownerLookupFallback = text("binding.owner-lookup-fallback", d.binding.ownerLookupFallback),
                ownerLookupConflict = text("binding.owner-lookup-conflict", d.binding.ownerLookupConflict),
                accountNotFound = text("binding.account-not-found", d.binding.accountNotFound),
                unbindButtonTitle = text("binding.unbind-button-title", d.binding.unbindButtonTitle),
                unbindConfirmTitle = text("binding.unbind-confirm-title", d.binding.unbindConfirmTitle),
                unbindConfirmButton = text("binding.unbind-confirm-button", d.binding.unbindConfirmButton),
                unbindCancelButton = text("binding.unbind-cancel-button", d.binding.unbindCancelButton),
                unbindCancelled = text("binding.unbind-cancelled", d.binding.unbindCancelled),
                unbindButtonExpired = text("binding.unbind-button-expired", d.binding.unbindButtonExpired),
                unbindSelect = text("binding.unbind-select", d.binding.unbindSelect),
                selectionExpired = text("binding.selection-expired", d.binding.selectionExpired),
                primaryUsage = text("binding.primary-usage", d.binding.primaryUsage),
                primaryButtonTitle = text("binding.primary-button-title", d.binding.primaryButtonTitle),
                primaryButtonExpired = text("binding.primary-button-expired", d.binding.primaryButtonExpired),
                primaryCancelled = text("binding.primary-cancelled", d.binding.primaryCancelled),
                primaryOnlyAccount = text("binding.primary-only-account", d.binding.primaryOnlyAccount),
                primaryChanged = text("binding.primary-changed", d.binding.primaryChanged),
                writeFailed = text("binding.write-failed", d.binding.writeFailed),
                codeFailed = text("binding.code-failed", d.binding.codeFailed)
            ),
            inventory = InventoryMessageCatalog(
                disabled = text("inventory.disabled", d.inventory.disabled),
                enderDisabled = text("inventory.ender-disabled", d.inventory.enderDisabled),
                bindingRequired = text("inventory.binding-required", d.inventory.bindingRequired),
                bindingUnverified = text("inventory.binding-unverified", d.inventory.bindingUnverified),
                usage = text("inventory.usage", d.inventory.usage),
                enderUsage = text("inventory.ender-usage", d.inventory.enderUsage),
                notAuthorized = text("inventory.not-authorized", d.inventory.notAuthorized),
                selectionTitle = text("inventory.selection-title", d.inventory.selectionTitle),
                selectionFallback = text("inventory.selection-fallback", d.inventory.selectionFallback),
                selectionExpired = text("inventory.selection-expired", d.inventory.selectionExpired),
                selectionInvalid = text("inventory.selection-invalid", d.inventory.selectionInvalid),
                noSnapshot = text("inventory.no-snapshot", d.inventory.noSnapshot),
                stateChanged = text("inventory.state-changed", d.inventory.stateChanged),
                failed = text("inventory.failed", d.inventory.failed),
                offlineLabel = text("inventory.offline-label", d.inventory.offlineLabel)
            )
        )
    }

    private fun stringSet(config: YamlConfig, path: String): Set<String> = config
        .getStringList(path)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())

    private fun bounded(config: YamlConfig, path: String, min: Int, max: Int, fallback: Int): Int {
        val value = config.getInt(path, fallback)
        if (value in min..max) return value
        logger.warning("$path=$value 超出安全范围 $min..$max，已使用默认值 $fallback。")
        return fallback
    }
}

data class LoadedConfiguration(
    val settings: VirgaSettings,
    val messages: MessageCatalog
)
