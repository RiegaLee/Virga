package cn.huohuas001.virga.server.panel

import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.config.GroupCommandSettings
import cn.huohuas001.virga.core.config.GroupPurpose
import cn.huohuas001.virga.core.command.CoreCommandRouter
import cn.huohuas001.virga.core.command.GroupCommandValidation
import cn.huohuas001.virga.core.config.GroupCommandRule
import cn.huohuas001.virga.core.qq.QqPanelFields
import cn.huohuas001.virga.core.qq.QqPanelSyncStatus
import cn.huohuas001.virga.panel.GroupCommandEditor
import cn.huohuas001.virga.panel.GroupCommandOption
import cn.huohuas001.virga.panel.PanelSyncInfo
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.panel.BotCredentialRules
import cn.huohuas001.virga.panel.BotInfo
import cn.huohuas001.virga.panel.PanelBackend
import cn.huohuas001.virga.panel.PanelOverview
import cn.huohuas001.virga.panel.QueueStatus
import cn.huohuas001.virga.panel.SaveResult
import cn.huohuas001.virga.panel.GroupEntry
import cn.huohuas001.virga.panel.GroupMember
import cn.huohuas001.virga.panel.GroupMembers
import cn.huohuas001.virga.panel.CustomCommandOption
import cn.huohuas001.virga.core.command.CustomCommand
import cn.huohuas001.virga.core.command.CustomCommandPermission
import cn.huohuas001.virga.panel.groups.GroupIds
import cn.huohuas001.virga.panel.groups.GroupInfoCache
import cn.huohuas001.virga.panel.groups.GroupNotes
import cn.huohuas001.virga.panel.groups.RecentGroups
import cn.huohuas001.virga.panel.settings.PanelSettingsCatalog
import cn.huohuas001.virga.panel.settings.SettingValue
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.config.ConfigManager
import cn.huohuas001.virga.server.game.GameServer
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture

/** Panel data source: immutable snapshots taken on the server thread. */
class GamePanelBackend(
    private val server: GameServer,
    private val configs: ConfigManager,
    private val scheduler: VirgaScheduler,
    private val currentSettings: () -> VirgaSettings,
    private val executors: RuntimeExecutors,
    private val qqConnected: () -> Boolean,
    private val pendingRestart: () -> List<String>,
    private val panelPort: () -> Int,
    private val configWriter: PanelConfigWriter,
    private val botRestartPending: () -> Boolean,
    private val recentGroups: RecentGroups,
    private val groupNotes: GroupNotes,
    /** Starts QQ with the saved credentials when no SDK session ever ran; true if started. */
    private val connectIfIdle: () -> Boolean,
    private val groupInfo: GroupInfoCache,
    private val commandRouter: () -> CoreCommandRouter,
    private val panelSyncStatus: () -> QqPanelSyncStatus,
    private val syncCommandPanels: () -> Unit,
    /** Members the bot has seen in a group, newest first. */
    private val seenMembers: (String) -> List<cn.huohuas001.virga.core.qq.GroupMembershipTracker.SeenMember> = { emptyList() },
    private val administrators: cn.huohuas001.virga.core.access.AdministratorRepository? = null,
    private val isRoot: (String) -> Boolean = { false },
    /** Records high-risk settings confirmed in the panel. */
    private val warn: (String) -> Unit = {}
) : PanelBackend {
    override fun overview(): CompletableFuture<PanelOverview> = scheduler.supplyGlobal {
        val current = currentSettings()
        PanelOverview(
            pluginVersion = server.modVersion,
            platform = server.platformName,
            minecraftVersion = server.minecraftVersion,
            javaVersion = Runtime.version().toString(),
            uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
            onlinePlayers = server.onlinePlayers().size,
            maxPlayers = server.maxPlayers(),
            qqConnected = qqConnected(),
            botConfigured = current.bot.hasCredentials,
            allowedGroups = current.bot.allowedGroups.size,
            panelPort = panelPort(),
            queues = QueueStatus(
                worker = executors.queuedTaskCount(),
                render = executors.queuedRenderCount(),
                state = executors.queuedStateCount(),
                scheduled = executors.scheduledTaskCount()
            ),
            pendingRestart = pendingRestart(),
            serverAddress = current.serverAddress
        )
    }

    override fun bot(): CompletableFuture<BotInfo> = scheduler.supplyGlobal {
        val bot = currentSettings().bot
        BotInfo(
            enabled = bot.enabled,
            configured = bot.hasCredentials,
            maskedAppId = bot.appId.takeIf { it.isNotBlank() }?.let(BotCredentialRules::mask),
            connected = qqConnected(),
            qrConnectEnabled = qrConnectEnabled(),
            restartRequired = botRestartPending()
        )
    }

    override fun saveBotCredentials(appId: String, secret: String?): CompletableFuture<SaveResult> =
        configWriter.update("bot.credentials", "appId=${BotCredentialRules.mask(appId)} secretChanged=${secret != null}") { config ->
            config.set("bot.app-id", appId)
            if (secret != null) config.set("bot.secret", secret)
            config.set("bot.enabled", true)
        }.thenCompose {
            // Starts the first session, or switches a running one to the new credentials in place.
            scheduler.supplyGlobal { connectIfIdle() }
        }.thenApply { connecting ->
            if (connecting) SaveResult(true, "已保存，正在连接 QQ。")
            else SaveResult(
                true,
                "已保存。QQ 客户端现在没在运行，新凭据需要完整重启服务器后生效；手机 QQ 会一直显示“连接中”，直到重启后连接成功。",
                restartRequired = true
            )
        }

    override fun qrConnectEnabled(): Boolean = currentSettings().panel.qrConnectEnabled

    override fun groups(): CompletableFuture<List<GroupEntry>> = scheduler.supplyGlobal {
        val allowed = currentSettings().bot.allowedGroups
        val seen = recentGroups.snapshot()
        // Groups with a note stay listed after being disallowed, so the note is not silently lost.
        (allowed + seen.keys + groupNotes.ids()).distinct().map { id ->
            groupInfo.request(id)
            val info = groupInfo.get(id)
            GroupEntry(id, GroupIds.display(id), id in allowed, groupNotes.get(id), seen[id]?.lastSeenMillis, seen[id]?.lastMessage, info?.name, info?.memberCount,
                currentSettings().groupCommands.profile(id).purpose.name)
        }.sortedWith(compareByDescending<GroupEntry> { it.allowed }.thenByDescending { it.lastSeenMillis ?: 0L })
    }

    override fun groupsChanged(since: Long): CompletableFuture<Long> = recentGroups.awaitChange(since)

    override fun refreshGroupInfo(): CompletableFuture<SaveResult> = scheduler.supplyGlobal {
        (currentSettings().bot.allowedGroups + recentGroups.snapshot().keys + groupNotes.ids()).distinct()
            .forEach { groupInfo.request(it, force = true) }
        SaveResult(true, "已开始刷新群名和人数。")
    }

    override fun updateGroup(groupOpenId: String, allowed: Boolean?, note: String?): CompletableFuture<SaveResult> =
        saveGroup(groupOpenId, allowed, note).whenComplete { _, _ -> recentGroups.changed() }

    private fun saveGroup(groupOpenId: String, allowed: Boolean?, note: String?): CompletableFuture<SaveResult> {
        if (note != null) groupNotes.set(groupOpenId, note)
        if (allowed == null) return CompletableFuture.completedFuture(SaveResult(true, "备注已保存"))
        val display = GroupIds.display(groupOpenId)
        // The allow list is applied on reload without reconnecting, and the QQ panel is resynced.
        return configWriter.update("groups.${if (allowed) "allow" else "deny"}", "group=$display") { config ->
            val groups = config.getStringList("bot.groups").map(String::trim).filter(String::isNotEmpty).toMutableList()
            if (allowed && groupOpenId !in groups) groups += groupOpenId
            if (!allowed) groups.remove(groupOpenId)
            config.set("bot.groups", groups)
        }.thenApply {
            SaveResult(true, if (allowed) "已允许该群，立即生效" else "已取消允许该群，立即生效")
        }
    }

    override fun groupCommands(groupOpenId: String): CompletableFuture<GroupCommandEditor> = scheduler.supplyGlobal {
        val purpose = currentSettings().groupCommands.profile(groupOpenId).purpose
        val status = panelSyncStatus()
        GroupCommandEditor(purpose.name, commandRouter().groupCommandEntries(groupOpenId, includeDisabled = true).map {
            val d = it.definition
            GroupCommandOption(it.rule.command, it.rule.allowed, it.rule.panel, it.rule.label,
                QqPanelFields.truncateToWidth(it.rule.description, QqPanelFields.MAX_DESCRIPTION_WIDTH),
                d.category, if (d.rootOnly) "超级管理员" else if (d.administratorOnly) "管理员" else "群成员",
                d.available, it.highRisk, d.publishToPanel, d.administratorOnly || d.rootOnly,
                it.rule.command in setOf("绑定", "解绑", "设置主账号"))
        }, PanelSyncInfo(status.state, status.message, status.updatedAt))
    }

    override fun saveGroupCommands(
        groupOpenId: String,
        purpose: String,
        commands: List<GroupCommandOption>,
        confirmHighRisk: Boolean
    ): CompletableFuture<SaveResult> {
        var openedHighRisk = emptyList<String>()
        return configWriter.update(
            "groups.commands",
            "group=${GroupIds.display(groupOpenId)} purpose=$purpose count=${commands.size} confirmHighRisk=$confirmHighRisk"
        ) { config ->
            require(groupOpenId in currentSettings().bot.allowedGroups) { "请先允许该群，再配置指令面板" }
            val role = GroupPurpose.valueOf(purpose)
            val catalog = commandRouter().commandPresentations(includeDisabled = true)
            val definitions = catalog.associateBy { GroupCommandSettings.canonical(it.name) }
            // Commands that were already open for the same purpose need no second confirmation.
            val previouslyAllowed = if (currentSettings().groupCommands.profile(groupOpenId).purpose != role) emptySet()
            else commandRouter().groupCommandEntries(groupOpenId, includeDisabled = true)
                .filter { it.rule.allowed }.map { it.rule.command }.toSet()
            openedHighRisk = GroupCommandValidation.validate(role, commands.map {
                GroupCommandRule(it.command, it.allowed, it.panel, it.label, it.description)
            }, catalog, previouslyAllowed, confirmHighRisk)
            val base = "qq-command-panels.groups.$groupOpenId"
            config.set("$base.purpose", role.name)
            val unknown = currentSettings().groupCommands.profile(groupOpenId).commands.filter { it.command !in definitions }
            config.set("$base.commands", commands.map {
                linkedMapOf("command" to it.command, "allowed" to it.allowed, "panel" to it.panel,
                    "label" to it.label, "description" to it.description)
            } + unknown.map {
                linkedMapOf("command" to it.command, "allowed" to it.allowed, "panel" to it.panel,
                    "label" to it.label, "description" to it.description)
            })
        }.thenApply {
            recentGroups.changed()
            if (openedHighRisk.isNotEmpty()) {
                warn("高危设置：群 ${GroupIds.display(groupOpenId)} 作为玩家群开放了管理指令 " +
                    openedHighRisk.joinToString("、") { "/$it" } + "（已在管理面板确认）。")
            }
            SaveResult(true, "本群规则已保存并立即生效，QQ 面板正在同步")
        }
    }

    override fun syncGroupCommands(): CompletableFuture<SaveResult> {
        syncCommandPanels()
        return CompletableFuture.completedFuture(SaveResult(true, panelSyncStatus().message))
    }

    override fun customCommands(): CompletableFuture<List<CustomCommandOption>> = scheduler.supplyGlobal {
        currentSettings().customCommands.map {
            CustomCommandOption(it.key, it.template, it.description, it.permission.name, it.enabled,
                it.panel, it.requireBinding, it.cooldownSeconds, it.showFeedback)
        }
    }

    override fun saveCustomCommands(commands: List<CustomCommandOption>): CompletableFuture<SaveResult> =
        configWriter.update("custom.commands", "count=${commands.size}") { config ->
            val definitions = commands.map {
                CustomCommand(it.key, it.template, it.description, CustomCommandPermission.valueOf(it.permission),
                    it.enabled, it.panel, it.requireBinding, it.cooldownSeconds, it.showFeedback)
            }
            val oldKeys = currentSettings().customCommands.map { it.key.lowercase() }.toSet()
            val reserved = commandRouter().commandPresentations(includeDisabled = true)
                .filter { it.name.lowercase() !in oldKeys }.flatMap { listOf(it.name) + it.aliases }.toSet() +
                CoreCommandRouter.reservedTokens() + currentSettings().groupCommands.groups.values.flatMap { p ->
                    p.commands.filter { it.command.lowercase() !in oldKeys && it.label.lowercase() != it.command.lowercase() }.map { it.label }
                }
            CustomCommand.validate(definitions, reserved)
            config.set("custom-commands", commands.map {
                linkedMapOf("key" to it.key, "template" to it.template, "description" to it.description,
                    "permission" to it.permission, "enabled" to it.enabled, "panel" to it.panel,
                    "requireBinding" to it.requireBinding, "cooldownSeconds" to it.cooldownSeconds, "showFeedback" to it.showFeedback)
            })
        }.thenApply {
            recentGroups.changed()
            SaveResult(true, "自定义指令已保存并立即生效，QQ 面板正在同步")
        }

    override fun settings(): CompletableFuture<List<SettingValue>> = scheduler.supplyGlobal {
        val config = configs.config
        PanelSettingsCatalog.DEFINITIONS.map { definition ->
            SettingValue(definition.key, definition.label, definition.group, definition.description,
                config.getBoolean(definition.key, definition.defaultValue), riskWarning = definition.riskWarning)
        }
    }

    override fun updateSettings(values: Map<String, Boolean>): CompletableFuture<SaveResult> {
        val changed = values.filterKeys(PanelSettingsCatalog::isAllowed)
        val detail = changed.entries.joinToString(",") { "${it.key}=${it.value}" }
        return configWriter.update("settings", detail) { config ->
            changed.forEach { (key, value) -> config.set(key, value) }
        }.thenApply { SaveResult(true, "设置已保存，立即生效") }
    }

    override fun groupMembers(groupOpenId: String): CompletableFuture<GroupMembers> = scheduler.supplyGlobal {
        val repository = administrators
        val admins = repository?.administrators(groupOpenId).orEmpty()
        val seen = seenMembers(groupOpenId)
        val seenIds = seen.map { it.userOpenId }.toSet()
        // Listed administrators and ROOTs who never spoke here still appear, so they can be removed.
        val extra = (admins + currentSettings().rootAdministrators).filter { it !in seenIds }
        val members = seen.map { GroupMember(it.userOpenId, GroupIds.display(it.userOpenId), it.displayName,
                it.observedAtSeconds * 1000, isRoot(it.userOpenId), it.userOpenId in admins) } +
            extra.map { GroupMember(it, GroupIds.display(it), null, null, isRoot(it), it in admins) }
        GroupMembers(groupOpenId, members, currentSettings().trustQqGroupRoles)
    }

    override fun setGroupAdministrator(groupOpenId: String, userOpenId: String, administrator: Boolean): CompletableFuture<SaveResult> =
        executors.submitState("面板修改群管理员") {
            val repository = administrators ?: return@submitState SaveResult(false, "管理员名单暂时不可用")
            val changed = if (administrator) repository.add(groupOpenId, userOpenId) else repository.remove(groupOpenId, userOpenId)
            val who = GroupIds.display(userOpenId)
            when {
                !changed && administrator -> SaveResult(true, "$who 已经是本群管理员")
                !changed -> SaveResult(true, "$who 本来就不是本群管理员")
                administrator -> SaveResult(true, "已把 $who 设为本群管理员，立即生效")
                else -> SaveResult(true, "已取消 $who 的本群管理员，立即生效")
            }
        }

    override fun setRoot(userOpenId: String, root: Boolean): CompletableFuture<SaveResult> {
        val who = GroupIds.display(userOpenId)
        return configWriter.update("administrators.roots", "${if (root) "add" else "remove"} $who") { config ->
            val roots = config.getStringList("administrators.roots").map(String::trim).filter(String::isNotEmpty).toMutableList()
            if (root) { if (userOpenId !in roots) roots += userOpenId } else roots.remove(userOpenId)
            config.set("administrators.roots", roots)
        }.thenApply {
            SaveResult(true, if (root) "已把 $who 设为超级管理员，立即生效" else "已取消 $who 的超级管理员，立即生效")
        }
    }

    override fun saveServerAddress(address: String): CompletableFuture<SaveResult> =
        configWriter.update("brand.server-address", if (address.isEmpty()) "cleared" else "set") { config ->
            config.set("brand.server-address", address)
        }.thenApply {
            SaveResult(true, if (address.isEmpty()) "已清空服务器地址" else "服务器地址已保存，立即生效")
        }

    override fun savePanelPort(port: Int): CompletableFuture<SaveResult> =
        configWriter.update("panel.port", "port=$port") { config -> config.set("panel.port", port) }
            .thenApply { SaveResult(true, "端口已保存") }
}
