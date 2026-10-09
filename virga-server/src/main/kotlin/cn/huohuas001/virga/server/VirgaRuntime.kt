@file:Suppress("DEPRECATION")

package cn.huohuas001.virga.server

import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import cn.huohuas001.virga.api.BindingVerificationState
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.CommandPermission
import cn.huohuas001.virga.api.VirgaService
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.features.render.BackdropLibrary
import cn.huohuas001.virga.server.connect.InGameQrConnect
import cn.huohuas001.virga.server.connect.SettingsMenu
import java.nio.file.Files
import cn.huohuas001.virga.core.access.AccessControl
import cn.huohuas001.virga.core.access.YamlAdministratorRepository
import cn.huohuas001.virga.core.addon.AddonTimeoutHandle
import cn.huohuas001.virga.core.addon.AddonTimeoutScheduler
import cn.huohuas001.virga.core.addon.VirgaServiceImpl
import cn.huohuas001.virga.core.bot.VirgaHost
import cn.huohuas001.virga.core.bot.QClient
import cn.huohuas001.virga.core.bot.events.GroupMessageHandler
import cn.huohuas001.virga.core.bot.events.GroupNotice
import cn.huohuas001.virga.core.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.virga.core.bot.provider.AdminMode
import cn.huohuas001.virga.core.bot.provider.BotShared
import cn.huohuas001.virga.core.bot.provider.ChatFormat
import cn.huohuas001.virga.core.bot.provider.HExecution
import cn.huohuas001.virga.core.bot.provider.Motd
import cn.huohuas001.virga.core.bot.provider.PlayerEventFormat
import cn.huohuas001.virga.core.bot.tools.Cancelable
import cn.huohuas001.virga.core.command.AddonCommandRouter
import cn.huohuas001.virga.core.command.CommandPresentation
import cn.huohuas001.virga.core.command.CoreCommandRouter
import cn.huohuas001.virga.core.command.FeatureCommandRouter
import cn.huohuas001.virga.core.command.RemoteCommandDispatcher
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.PanelSettings
import cn.huohuas001.virga.core.qq.GroupMembershipTracker
import cn.huohuas001.virga.core.qq.QqBotRuntime
import cn.huohuas001.virga.core.qq.QqIdentityResolver
import cn.huohuas001.virga.core.qq.QqIdentityStore
import cn.huohuas001.virga.core.qq.QqMessageGateway
import cn.huohuas001.virga.core.qq.QqPanelCommand
import cn.huohuas001.virga.core.qq.QqPanelSnapshot
import cn.huohuas001.virga.core.qq.shouldAttemptMention
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.panel.BotCredentialRules
import cn.huohuas001.virga.panel.ServerAddressRule
import cn.huohuas001.virga.panel.PanelLog
import cn.huohuas001.virga.panel.PanelServer
import cn.huohuas001.virga.panel.auth.LoginThrottle
import cn.huohuas001.virga.panel.auth.PasswordStore
import cn.huohuas001.virga.panel.auth.SessionManager
import cn.huohuas001.virga.panel.groups.GroupIds
import cn.huohuas001.virga.panel.groups.GroupInfoCache
import cn.huohuas001.virga.panel.groups.GroupNotes
import cn.huohuas001.virga.panel.groups.RecentGroups
import cn.huohuas001.virga.panel.http.StaticAssets
import cn.huohuas001.virga.panel.qr.QqBotQrConnector
import cn.huohuas001.virga.server.binding.BindingFeatureService
import cn.huohuas001.virga.server.config.ConfigManager
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.game.GameText
import cn.huohuas001.virga.server.inventory.InventoryFeatureService
import cn.huohuas001.virga.server.panel.GamePanelBackend
import cn.huohuas001.virga.server.panel.PanelConfigWriter
import cn.huohuas001.virga.server.platform.VirgaScheduler
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Virga composition root. The Forge mod creates it when the server has started, forwards
 * game callbacks to it and closes it when the server stops.
 */
class VirgaRuntime(
    private val server: GameServer,
    private val logger: VirgaLogger,
    /** java.util.logging bridge for the ported Java services. */
    private val julLogger: java.util.logging.Logger,
    val virgaScheduler: VirgaScheduler
) : VirgaHost {
    private val started = AtomicBoolean(false)
    private val settings = AtomicReference<VirgaSettings>()
    private val messages = AtomicReference<MessageCatalog>()
    private val onlineNames = AtomicReference<List<String>>(emptyList())
    /** Settings changed by reload that only take effect after a full restart (shown in the panel). */
    private val pendingRestartReasons = CopyOnWriteArraySet<String>()
    /** Groups that recently messaged the bot (allowed or not), for the panel group page. */
    private val recentGroups = RecentGroups()
    /** QQ group names for the panel, fetched through the running QQ session with a 30 QPM budget. */
    private val groupInfo = GroupInfoCache(
        fetch = { id ->
            if (!::qqRuntime.isInitialized) CompletableFuture.completedFuture(null)
            else qqRuntime.fetchGroupInfo(id).thenApply { info -> info?.let { GroupInfoCache.Info(it.name, it.memberCount) } }
        },
        onUpdate = { recentGroups.changed() }
    )
    private val dataFolder: File = server.dataDirectory.toFile()
    private val eventBridge = GameEventBridge(settings::get)

    @Volatile
    private var panel: PanelServer? = null
    @Volatile
    private var panelPasswords: PasswordStore? = null
    @Volatile
    private var panelSessions: SessionManager? = null
    private val panelLog = object : PanelLog {
        override fun info(message: String) = logger.info(message)
        override fun warning(message: String) = logger.warning(message)
        override fun error(message: String, error: Throwable?) = logger.error(message, error)
    }

    private lateinit var configManager: ConfigManager
    private lateinit var configWriter: PanelConfigWriter
    private lateinit var executors: RuntimeExecutors
    private lateinit var administrators: YamlAdministratorRepository
    private lateinit var access: AccessControl
    private lateinit var messageGateway: QqMessageGateway
    private lateinit var featureService: VirgaFeatureService
    private lateinit var backdrops: BackdropLibrary
    private lateinit var inGameQr: InGameQrConnect
    private lateinit var settingsMenu: SettingsMenu
    private lateinit var skinService: SkinService
    private lateinit var callbackButtons: QqCallbackButtonBridge
    private lateinit var commandRouter: CoreCommandRouter
    private lateinit var groupMembershipTracker: GroupMembershipTracker
    private lateinit var qqIdentities: QqIdentityResolver
    private lateinit var groupEnrollmentService: GroupEnrollmentService
    private lateinit var bindingFeatureService: BindingFeatureService
    private lateinit var inventoryFeatureService: InventoryFeatureService
    private lateinit var addonService: VirgaServiceImpl
    private lateinit var groupMessageHandler: GroupMessageHandler
    private lateinit var qqRuntime: QqBotRuntime

    /** Local `/virga` command logic; the platform layer only parses arguments. */
    val command = VirgaCommand(this)

    /** Public addon API for other server mods (null until the server has started). */
    val service: VirgaService?
        get() = if (::addonService.isInitialized) addonService else null

    fun start() {
        try {
            if (!dataFolder.isDirectory && !dataFolder.mkdirs()) {
                error("无法创建 Virga 数据目录：${dataFolder.absolutePath}")
            }
            configManager = ConfigManager(server.dataDirectory, server::resource, logger)
            val loaded = configManager.initialize()
            settings.set(loaded.settings)
            messages.set(loaded.messages)
            configWriter = PanelConfigWriter(configManager, server.dataDirectory, virgaScheduler) { reloadPluginConfig() }

            executors = RuntimeExecutors(
                loaded.settings.runtime.workerThreads,
                loaded.settings.runtime.queueCapacity,
                loaded.settings.runtime.scheduledTaskLimit,
                logger,
                loaded.settings.runtime.renderQueueCapacity,
                loaded.settings.runtime.skinThreads,
                loaded.settings.runtime.skinQueueCapacity,
                loaded.settings.runtime.stateQueueCapacity
            )
            administrators = YamlAdministratorRepository(dataFolder.toPath().resolve("state/administrators.yml"))
            val identityScope = java.security.MessageDigest.getInstance("SHA-256")
                .digest(loaded.settings.bot.appId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            qqIdentities = QqIdentityResolver(
                QqIdentityStore(dataFolder.toPath().resolve("state/qq-identities-$identityScope.properties")),
                executors,
                applicationActive = { settings.get().bot.appId == loaded.settings.bot.appId }
            )
            access = AccessControl(settings::get, administrators, qqIdentities::sameRoot)
            messageGateway = QqMessageGateway(QClient::currentTransport) { settings.get().runtime.imageLimitBytes }
            skinService = SkinService(
                executors, logger,
                loaded.settings.onlineList.skinConnectTimeoutMs,
                loaded.settings.onlineList.skinReadTimeoutMs,
                PersistentPlayerSkinStore(dataFolder.toPath().resolve("state/player-skins"), julLogger)
            )
            backdrops = createBackdrops()
            // Compose the four backdrops once in the background so the first image request is quick.
            executors.submitRender("预先合成图片底图") {
                BackdropLibrary.Surface.entries.forEach { surface ->
                    runCatching { backdrops.get(surface) }.onFailure { logger.error("预先合成 ${surface.id()} 底图失败", it) }
                }
            }
            inGameQr = InGameQrConnect(server, virgaScheduler, executors, logger, settings::get, ::saveScannedCredentials)
            settingsMenu = SettingsMenu(
                config = { configManager.config },
                writer = configWriter,
                logger = logger,
                statusLines = ::menuStatusLines,
                reload = ::reloadPluginConfig,
                startQrConnect = { inGameQr.begin(it) },
                applyServerAddress = { player, input, done ->
                    applyServerAddress(input, { player.send(it) }, done)
                }
            )
            featureService = VirgaFeatureService(
                server,
                julLogger,
                settings::get,
                messages::get,
                messageGateway,
                executors,
                logger,
                skinService,
                virgaScheduler,
                backdrops
            )
            callbackButtons = QqCallbackButtonBridge(julLogger)
            groupMembershipTracker = GroupMembershipTracker(
                dataFolder.toPath().resolve("state/qq-group-members.properties"),
                logger
            )
            groupEnrollmentService = GroupEnrollmentService(
                server = server,
                scheduler = virgaScheduler,
                settings = settings::get,
                gateway = messageGateway,
                logger = logger,
                qqConnected = { QClient.currentTransport()?.isAccepting() == true },
                enrollGroup = ::enrollQqGroup
            )
            bindingFeatureService = BindingFeatureService(
                server, julLogger, settings::get, messages::get, messageGateway, messageGateway, access, executors, logger,
                callbackButtons, groupMembershipTracker, virgaScheduler, qqIdentities
            )
            inventoryFeatureService = InventoryFeatureService(
                server, julLogger, settings::get, messages::get, messageGateway,
                bindingFeatureService.bindingService, access, executors, logger, skinService,
                callbackButtons, virgaScheduler, backdrops
            )
            addonService = VirgaServiceImpl(
                messageGateway = messageGateway,
                schedulerFactory = { _, addonLogger -> GameAddonScheduler(virgaScheduler, executors, addonLogger) },
                loggerFactory = { descriptor -> GamePluginLogger(logger, descriptor) },
                timeoutScheduler = AddonTimeoutScheduler { delay, task ->
                    val handle = executors.schedule("Addon 命令超时", delay) { task.run() }
                    AddonTimeoutHandle(handle::cancel)
                },
                reservedCommand = { command ->
                    CoreCommandRouter.isReservedCommand(command)
                },
                configuredAdministrator = access::isAdministrator,
                commandRegistryChanged = QClient::syncGroupPanels,
                commandAllowed = { message, spec ->
                    settings.get().isCommandAllowed(message.groupOpenId, spec.id, spec.permission == CommandPermission.ADMIN)
                },
                bindingService = bindingFeatureService.bindingService,
                bindingVerificationService = bindingFeatureService.verificationService,
                handlerTimeoutMillis = loaded.settings.runtime.addonTimeoutSeconds * 1_000L,
                maxInFlightCommands = minOf(
                    loaded.settings.runtime.queueCapacity,
                    loaded.settings.runtime.scheduledTaskLimit
                )
            )
            val router = CoreCommandRouter(
                settings = settings::get,
                messages = messages::get,
                gateway = messageGateway,
                administrators = administrators,
                access = access,
                addons = AddonCommandRouter(addonService::route),
                remoteCommands = RemoteCommandDispatcher { command ->
                    dispatchCommand(command).thenApply { execution -> execution.getRawString() }
                },
                features = FeatureCommandRouter { message, command, arguments ->
                    bindingFeatureService.route(message, command, arguments) ||
                        inventoryFeatureService.route(message, command, arguments) ||
                        featureService.route(message, command, arguments)
                },
                additionalCommands = ::compatibleCommandPresentations,
                boundPlayer = { message ->
                    bindingFeatureService.bindingService.findBinding(message.groupOpenId, access.senderOpenId(message))
                        .filter { it.verificationState == BindingVerificationState.VERIFIED }
                        .map { it.playerName }.orElse(null)
                },
                shouldAttemptAdministratorMention = { groupOpenId, userOpenId ->
                    groupMembershipTracker.status(groupOpenId, userOpenId).shouldAttemptMention()
                },
                administratorDisplayName = groupMembershipTracker::displayName,
                logger = logger
            )
            commandRouter = router
            groupMessageHandler = GroupMessageHandler(
                plugin = this,
                settings = settings::get,
                router = router,
                presenceObserver = { message ->
                    groupMembershipTracker.observe(message)
                    if (settings.get().isAllowedGroup(message.groupOpenId) && !message.sender.unionOpenId.isNullOrBlank()) {
                        qqIdentities.resolve(message.groupOpenId, access.senderOpenId(message), message.sender.unionOpenId)
                            .whenComplete { _, error -> if (error != null) logger.warning("QQ 消息统一身份记录失败，未变更权限。") }
                    }
                    recentGroups.record(message.groupOpenId, message.sender.username, message.content)
                    groupInfo.request(message.groupOpenId)
                },
                qqToGame = ::forwardQqToGame,
                priorityMessageHandler = { message ->
                    groupEnrollmentService.routeIngress(message) || bindingFeatureService.routeBindingIngress(message)
                },
                backgroundDispatch = executors::execute,
                logger = logger,
                groupJoinObserver = { groupOpenId ->
                    recentGroups.recordJoin(groupOpenId)
                    groupInfo.request(groupOpenId)
                },
                groupNoticeObserver = ::onGroupNotice
            )
            qqRuntime = QqBotRuntime(
                executors,
                logger,
                groupMessageHandler,
                groupMembershipTracker,
                dataFolder.toPath().resolve("state/qq-command-panel.properties")
            ) {
                QqPanelSnapshot(
                    settings.get().bot.allowedGroups.sorted(),
                    emptyList(),
                    settings.get().bot.allowedGroups.associateWith { group ->
                        router.groupPanelPresentations(group)
                            .map { QqPanelCommand(it.name, it.description, it.administratorOnly || it.rootOnly) }
                    }
                )
            }
            QClient.install(qqRuntime, groupMessageHandler)
            BotShared.setInstance(this)

            virgaScheduler.globalTimer(1L, 20L) {
                onlineNames.set(server.onlinePlayers().map { it.name }.sorted())
            }

            started.set(true)
            // Button callbacks only need the started SDK; connect() is a no-op without one.
            qqRuntime.start(loaded.settings.bot, qqLogFilePattern()).whenComplete { _, error ->
                if (error == null) callbackButtons.connect()
            }
            startPanel(loaded.settings.panel)
            startStorageJanitor()
            logger.info(
                "Virga 已启动！${server.platformName} ${server.minecraftVersion} / Java ${Runtime.version()}"
            )
        } catch (error: Throwable) {
            logger.error("Virga 启动失败了，正在把已打开的资源收拾好。", error)
            stop()
            throw error
        }
    }

    fun stop() {
        shutdownComponents()
    }

    private fun shutdownComponents() {
        if (!started.getAndSet(false) && !::executors.isInitialized) return
        // Stop accepting panel requests before the services they read are torn down.
        panel?.let { runCatching { it.close() }.onFailure { e -> logger.error("关闭管理面板失败", e) } }
        panel = null
        runCatching { groupInfo.close() }
        if (::qqIdentities.isInitialized) qqIdentities.close()
        if (::featureService.isInitialized) {
            runCatching { featureService.close() }.onFailure { logger.error("关闭核心功能服务失败", it) }
        }
        if (::inventoryFeatureService.isInitialized) {
            runCatching { inventoryFeatureService.close() }.onFailure { logger.error("关闭玩家数据服务失败", it) }
        }
        if (::bindingFeatureService.isInitialized) {
            runCatching { bindingFeatureService.close() }.onFailure { logger.error("关闭绑定服务失败", it) }
        }
        if (::callbackButtons.isInitialized) {
            runCatching { callbackButtons.close() }.onFailure { logger.error("关闭 QQ 按钮回调失败", it) }
        }
        if (::groupEnrollmentService.isInitialized) groupEnrollmentService.close()
        if (::inGameQr.isInitialized) runCatching { inGameQr.close() }
        if (::addonService.isInitialized) {
            runCatching { addonService.close() }.onFailure { logger.error("关闭 Addon API 失败", it) }
        }
        // Services cancel their own tasks above; this sweeps whatever is still queued.
        runCatching { virgaScheduler.cancelAll() }.onFailure { logger.error("取消调度任务失败", it) }
        runCatching { QClient.shutdown() }.onFailure { logger.error("关闭 QQ 客户端失败", it) }
        BotShared.clear(this)
        CustomCommandRegistry.replace(emptyList())
        if (::executors.isInitialized) {
            runCatching { executors.close() }.onFailure { logger.error("关闭后台执行器失败", it) }
        }
        logger.info("已安全下线。")
    }

    // ---------- Game callbacks (server thread) ----------

    fun onPlayerJoin(player: GamePlayer) {
        if (!started.get()) return
        runCatching { bindingFeatureService.onJoin(player) }.onFailure { logger.error("处理玩家进服绑定提示失败", it) }
        runCatching { skinService.observe(player) }
        eventBridge.onJoin(player.name)
    }

    fun onPlayerQuit(player: GamePlayer) {
        if (!started.get()) return
        runCatching { inventoryFeatureService.onQuit(player) }.onFailure { logger.error("保存 ${player.name} 的离线快照失败", it) }
        eventBridge.onQuit(player.name)
    }

    /** Called from any thread with the plain chat text. */
    fun onChat(playerName: String, message: String) {
        if (!started.get()) return
        eventBridge.onChat(playerName, message)
    }

    fun authcode(player: GamePlayer, args: List<String>) {
        if (!started.get()) return
        bindingFeatureService.authcode(player, args)
    }

    private fun forwardQqToGame(message: BotMessage) {
        if (!settings.get().isAllowedGroup(message.groupOpenId)) return
        val format = settings.get().bridge.qqToGameFormat
            .replace("{name}", qqTextForGame(message.sender.username))
            .replace("{message}", qqTextForGame(message.rawContent))
        virgaScheduler.global { server.broadcast(GameText.legacy(format)) }
    }

    /**
     * QQ text shown in game: mentions arrive as `<@member-openid>` and must not leak OpenIDs, and
     * group members' `&`/`§` must not act as color codes in the server's format.
     */
    private fun qqTextForGame(value: String): String = value
        .replace(QQ_MENTION_IN_TEXT, "@成员")
        .replace('&', '＆')
        .replace('§', '＆')

    /** Cleans the data directory once at startup and then once a day, off the server thread. */
    private fun startStorageJanitor() {
        val janitor = StorageJanitor(dataFolder.toPath(), logger, ::storageSettings)
        val clean = {
            executors.submitState("清理存储") { janitor.run() }
                .whenComplete { _, error -> if (error != null) logger.error("存储清理失败", error) }
            Unit
        }
        clean()
        virgaScheduler.globalTimer(TICKS_PER_DAY, TICKS_PER_DAY) { clean() }
    }

    private fun storageSettings(): StorageJanitor.Settings {
        val config = configManager.config
        fun bounded(path: String, min: Int, max: Int, fallback: Int): Int =
            config.getInt(path, fallback).takeIf { it in min..max } ?: fallback
        return StorageJanitor.Settings(
            cacheDays = bounded("storage.cache-days", 1, 3650, 30),
            snapshotDays = bounded("storage.snapshot-days", 1, 3650, 180),
            auditRotateBytes = bounded("storage.audit-rotate-kb", 16, 1_048_576, 512) * 1024L,
            auditArchives = bounded("storage.audit-archives", 1, 100, 10)
        )
    }

    private fun qqLogFilePattern(): String = dataFolder.resolve("logs/Bot-%s.log").path

    private fun compatibleCommandPresentations(): List<CommandPresentation> = buildList {
        if (::addonService.isInitialized) {
            addonService.registeredCommands().forEach { command ->
                add(
                    CommandPresentation(
                        "扩展指令",
                        command.id,
                        command.description.ifBlank { "由扩展提供的指令" },
                        command.permission == CommandPermission.ADMIN,
                        publishToPanel = command.isPublishToMenu,
                        aliases = command.aliases.toSet()
                    )
                )
            }
        }
        if (::groupMessageHandler.isInitialized) {
            groupMessageHandler.registeredCommands().forEach { command ->
                add(
                    CommandPresentation(
                        "旧版功能",
                        command.command,
                        command.describe.ifBlank { "Virga 保留的旧版功能" },
                        command.onlyAdmin
                    )
                )
            }
        }
    }

    private fun startPanel(config: PanelSettings) {
        if (!config.enabled) {
            logger.info("管理面板已在配置中关闭（panel.enabled: false）。")
            return
        }
        try {
            val passwords = PasswordStore(dataFolder.toPath().resolve("state/panel-credentials.properties"))
            val generated = passwords.initialize()
            if (generated != null) announcePanelPassword(generated)
            else logger.info("管理面板密码已设置过（只在第一次启动时显示）。忘记了就在控制台执行 virga passwd <新密码>，或 virga passwd 重新随机生成。")
            val sessions = SessionManager()
            val backend = GamePanelBackend(
                server,
                configManager,
                virgaScheduler,
                settings::get,
                executors,
                qqConnected = { QClient.currentTransport()?.isAccepting() == true },
                pendingRestart = { pendingRestartReasons.toList() },
                panelPort = { panel?.port ?: config.port },
                configWriter = configWriter,
                botRestartPending = { pendingRestartReasons.any { it.startsWith(BOT_RESTART_REASON) } },
                recentGroups = recentGroups,
                groupNotes = GroupNotes(dataFolder.toPath().resolve("state/panel-group-notes.properties")),
                connectIfIdle = { connectSavedBotIfIdle() },
                groupInfo = groupInfo,
                commandRouter = { commandRouter },
                panelSyncStatus = { qqRuntime.commandPanelStatus() },
                syncCommandPanels = { qqRuntime.syncCommandPanel() },
                seenMembers = { group ->
                    if (::groupMembershipTracker.isInitialized) groupMembershipTracker.recentMembers(group) else emptyList()
                },
                administrators = administrators,
                isRoot = { user -> access.isRoot(user) },
                warn = logger::warning
            )
            val panelServer = PanelServer(
                backend, passwords, sessions, LoginThrottle(), StaticAssets(server.resourceLoader), panelLog,
                QqBotQrConnector(source = "Virga")
            )
            panelPasswords = passwords
            panelSessions = sessions
            panel = panelServer
            panelServer.start(config.port)
        } catch (error: Exception) {
            panel?.let { runCatching { it.close() } }
            panel = null
            logger.error("管理面板启动失败（端口 ${config.port} 可能已被占用），Virga 的其他功能不受影响。", error)
        }
    }

    /**
     * A group that removed the bot or switched its messages off is taken off the allow list
     * right away; switching messages back on only shows up in the panel.
     */
    private fun onGroupNotice(groupOpenId: String, notice: GroupNotice) {
        val display = GroupIds.display(groupOpenId)
        recentGroups.recordNote(groupOpenId, when (notice) {
            GroupNotice.REMOVED -> RecentGroups.REMOVED_NOTE
            GroupNotice.MESSAGES_OFF -> RecentGroups.MESSAGES_OFF_NOTE
            GroupNotice.MESSAGES_ON -> RecentGroups.MESSAGES_ON_NOTE
        })
        if (notice == GroupNotice.MESSAGES_ON) {
            logger.info("QQ 群 $display 的管理员重新开启了机器人消息；如需继续服务，请在面板或游戏内重新允许。")
            return
        }
        if (!settings.get().bot.allowedGroups.contains(groupOpenId)) return
        configWriter.update("groups.auto-deny", "group=$display reason=${notice.name}") { config ->
            config.set("bot.groups", config.getStringList("bot.groups").map(String::trim).filter { it.isNotEmpty() && it != groupOpenId })
        }.whenComplete { _, error ->
            if (error != null) {
                logger.error("QQ 群 $display 已${if (notice == GroupNotice.REMOVED) "移出机器人" else "关闭机器人消息"}，但自动取消允许失败。", error)
            } else {
                logger.info("QQ 群 $display ${if (notice == GroupNotice.REMOVED) "移出了机器人" else "的管理员关闭了机器人消息"}，已自动取消允许。")
            }
            recentGroups.changed()
        }
    }

    /**
     * After the panel or an in-game scan saved credentials (server thread): connect with them now.
     * A process that never created a QQ SDK session starts one; a running session switches to the
     * new credentials in place (a fresh scan makes QQ issue a new Secret and drop the old session,
     * so waiting for a restart would leave the phone on "connecting"). False only when the SDK
     * exists but is not running, which still needs a full restart.
     * [initiator], the player who scanned in game (null for the panel), hears how it ended.
     */
    private fun connectSavedBotIfIdle(initiator: UUID? = null): Boolean {
        val configured = configManager.reload().settings.bot
        if (!configured.enabled || !configured.hasCredentials) return false
        val result = when {
            qqRuntime.canStartWithoutRestart() -> {
                logger.info("正在用刚保存的新凭据连接 QQ（AppID ${BotCredentialRules.mask(configured.appId)}）。")
                qqRuntime.start(configured, qqLogFilePattern())
            }
            qqRuntime.currentStarterForCoreFeatures() != null -> {
                logger.info("正在把运行中的 QQ 客户端切换到新凭据（AppID ${BotCredentialRules.mask(configured.appId)}）。")
                qqRuntime.switchCredentials(configured)
            }
            else -> return false
        }
        settings.set(settings.get().withBot(configured))
        pendingRestartReasons.removeIf { it.startsWith(BOT_RESTART_REASON) }
        result.whenComplete { connected, error ->
            val ok = error == null && connected == true
            if (error == null) callbackButtons.connect()
            // The in-game scan only said "connecting": tell the player who started it how it ended.
            if (initiator != null) {
                val notice = when {
                    ok -> "&d[Virga]&r QQ 机器人连上了～可以用 /virga group add 接入 QQ 群。"
                    error != null -> "&d[Virga]&r QQ 机器人没能启动……原因写在服务器日志里，处理好后请完整重启服务器。"
                    else -> "&d[Virga]&r QQ 机器人 30 秒内没能连上……原因写在服务器日志里；它会在后台继续重试，连上后 /virga info 会显示“已连接”。"
                }
                virgaScheduler.global { server.player(initiator)?.send(notice) }
            }
        }
        return true
    }

    private fun announcePanelPassword(password: String) {
        logger.warning("==================== Virga 管理面板 ====================")
        logger.warning("登录密码（只显示这一次，请马上存好）：$password")
        logger.warning("密码可以在网页「功能设置」里修改。")
        logger.warning("在控制台设置指定密码：virga passwd <新密码>")
        logger.warning("在控制台生成新的随机密码：virga passwd")
        logger.warning("======================================================")
    }

    fun resetPanelPassword() {
        val passwords = panelPasswords
        if (passwords == null) {
            logger.warning("管理面板没有启用或启动失败，暂时不能重置密码。")
            return
        }
        val password = passwords.reset()
        panelSessions?.revokeAll()
        announcePanelPassword(password)
    }

    /** Console only: sets a chosen panel password; the plaintext is never echoed back. */
    fun setPanelPassword(password: CharArray): String {
        val passwords = panelPasswords ?: return "[Virga] 管理面板没有启用或启动失败，暂时不能设置密码。"
        return try {
            when (passwords.set(password)) {
                PasswordStore.ChangeResult.CHANGED -> {
                    panelSessions?.revokeAll()
                    "[Virga] 管理面板密码换好了，已登录的会话都失效了，请用新密码重新登录。"
                }
                else -> "[Virga] 新密码长度要在 ${PasswordStore.MIN_LENGTH}–${PasswordStore.MAX_LENGTH} 个字符之间，密码没有修改。"
            }
        } finally {
            password.fill('\u0000')
        }
    }

    fun panelStatus(): String {
        val port = panel?.port
        return when {
            port != null -> "[Virga] 管理面板运行中：http://127.0.0.1:$port/（只监听本机，请经 SSH 隧道访问）"
            !settings.get().panel.enabled -> "[Virga] 管理面板已在配置中关闭。"
            else -> "[Virga] 管理面板没有运行，请看看启动日志（端口可能被占用）。"
        }
    }

    /** `/virga info`: version, QQ connection, groups and permissions, one short fact per line. */
    fun localStatus(): List<String> {
        val current = settings.get() ?: return listOf("&d[Virga]&r 还没完全醒来，请稍后再问。")
        val qq = if (::qqRuntime.isInitialized && qqRuntime.isAccepting()) "&a● 已连接" else "&c● 未连接"
        val permissions = if (server.permissionModActive()) "权限模组" else "OP 等级"
        val adminCount = current.bot.allowedGroups.sumOf { administrators.administrators(it).size }
        return listOf(
            "&d[Virga] &f${server.modVersion} &7· ${server.platformName} ${server.minecraftVersion}",
            "&7QQ 机器人：$qq",
            "&7允许的群：&f${current.bot.allowedGroups.size} 个 &7· 动态管理员：&f$adminCount 位",
            "&7权限判断：&f$permissions",
            "&7作者：&dRiegaLee"
        )
    }

    /** `/virga menu`: the in-game settings chest (players only). */
    fun openSettingsMenu(player: GamePlayer) {
        if (started.get()) settingsMenu.open(player)
    }

    /** `/virga connect [cancel]`: QR code on a map for connecting the QQ bot. */
    fun qrConnect(player: GamePlayer, cancel: Boolean) {
        if (!started.get()) return
        if (cancel) inGameQr.cancel(player) else inGameQr.begin(player)
    }

    /**
     * `/virga address [地址|clear]`: the address replied to “服务器地址”. Every owner fills in
     * their own; without an argument it shows the current one.
     */
    fun setServerAddress(sender: cn.huohuas001.virga.server.game.GameCommandSource, raw: String) {
        if (!started.get()) return
        if (raw.isBlank()) {
            val current = settings.get().serverAddress
            sender.reply(
                if (current.isEmpty()) "&d[Virga]&r 还没填服务器地址，群里发“服务器地址”时 Virga 不会回复。用 /virga address <地址> 填上。"
                else "&d[Virga]&r 现在的服务器地址：$current（/virga address clear 清空）"
            )
            return
        }
        applyServerAddress(raw, sender::reply) {}
    }

    /** Validates and saves brand.server-address ("clear"/"清空" empties it); replies through [reply]. */
    private fun applyServerAddress(raw: String, reply: (String) -> Unit, done: () -> Unit) {
        val input = raw.trim()
        val address = if (input.lowercase() in setOf("clear", "清空", "none")) ""
            else ServerAddressRule.normalize(input)?.takeIf { it.isNotEmpty() } ?: run {
                reply("&d[Virga]&r 服务器地址不能为空、不能有空格，最多 ${ServerAddressRule.MAX_LENGTH} 个字符。")
                done()
                return
            }
        configWriter.update("brand.server-address", if (address.isEmpty()) "cleared in game" else "set in game") { config ->
            config.set("brand.server-address", address)
        }.whenComplete { _, error ->
            if (error != null) {
                logger.error("保存服务器地址失败", error)
                reply("&d[Virga]&r 服务器地址没保存成功，看看控制台日志。")
            } else {
                reply(
                    if (address.isEmpty()) "&d[Virga]&r 服务器地址清空了，群里问地址时 Virga 就不回复。"
                    else "&d[Virga]&r 服务器地址改成 $address 了，群里发“服务器地址”就能复制～"
                )
            }
            done()
        }
    }

    /** Server thread. True when Virga consumed the chat line (e.g. the address typed after the menu prompt). */
    fun interceptChat(player: GamePlayer, message: String): Boolean =
        started.get() && ::settingsMenu.isInitialized && settingsMenu.interceptChat(player, message)

    private fun menuStatusLines(): List<String> {
        val current = settings.get()
        val qq = when {
            ::qqRuntime.isInitialized && qqRuntime.isAccepting() -> "已连接"
            current.bot.hasCredentials -> "未连接（已填写凭据）"
            else -> "未连接，可以点下面的地图扫码"
        }
        return listOf(
            "版本 ${server.modVersion}｜${server.platformName} ${server.minecraftVersion}",
            "QQ 机器人：$qq",
            "允许的群：${current.bot.allowedGroups.size} 个",
            "服务器地址：" + current.serverAddress.ifEmpty { "未填写（/virga address <地址>）" }
        )
    }

    /** Same as saving from the panel: write the credentials, then connect when this process may. */
    private fun saveScannedCredentials(appId: String, secret: String, initiator: UUID): CompletableFuture<String> =
        configWriter.update("bot.credentials", "appId=${BotCredentialRules.mask(appId)} source=in-game-qr") { config ->
            config.set("bot.app-id", appId)
            config.set("bot.secret", secret)
            config.set("bot.enabled", true)
        }.thenCompose { virgaScheduler.supplyGlobal { connectSavedBotIfIdle(initiator) } }
            .thenApply { connecting ->
                logger.info("已通过游戏内扫码保存 QQ 机器人凭据（AppID ${BotCredentialRules.mask(appId)}）。")
                if (connecting) "凭据保存好了，正在连接 QQ，连上后会告诉你。"
                else "凭据保存好了。QQ 客户端现在没在运行，新凭据要完整重启服务器后才会生效。"
            }

    /** Layered image backdrops; admins drop their own pictures into config/virga/backgrounds. */
    private fun createBackdrops(): BackdropLibrary {
        val directory = server.dataDirectory.resolve("backgrounds")
        try {
            Files.createDirectories(directory)
            val readme = directory.resolve("README.txt")
            if (!Files.exists(readme)) Files.writeString(readme, BACKGROUNDS_README)
        } catch (error: java.io.IOException) {
            logger.warning("没能创建自定义底图目录 $directory：${error.message}")
        }
        return BackdropLibrary(directory, {
            val appearance = settings.get().appearance
            BackdropLibrary.Options(appearance.customBackgrounds, appearance.veilPercent)
        }, julLogger)
    }

    /**
     * `/virga preview [玩家]`: renders the QQ images into config/virga/preview so admins can
     * check them without QQ. Replies are delivered back on the server thread.
     */
    fun preview(sender: cn.huohuas001.virga.server.game.GameCommandSource, playerName: String?) {
        if (!started.get()) return
        val directory = server.dataDirectory.resolve("preview")
        sender.reply("&d[Virga]&r 正在画预览图，稍等一下～")
        val images = featureService.renderPreview(directory).thenCombine(
            if (playerName.isNullOrBlank()) CompletableFuture.completedFuture(emptyList())
            else inventoryFeatureService.renderPreview(playerName, directory)
        ) { first, second -> first + second }
        images.whenComplete { files, error ->
            virgaScheduler.global {
                if (error != null || files == null) {
                    logger.error("渲染预览图失败", error)
                    sender.reply("&d[Virga]&r 预览图没画好，看看控制台日志。")
                } else {
                    val names = files.joinToString("、") { it.fileName.toString() }
                    sender.reply("&d[Virga]&r 画好了：$names（在 ${directory.toAbsolutePath()}）")
                    if (!playerName.isNullOrBlank() && files.none { it.fileName.toString().startsWith("inventory-") }) {
                        sender.reply("&d[Virga]&r 没找到 $playerName 的在线数据或离线快照哦。")
                    }
                }
            }
        }
    }

    fun beginGroupEnrollment(player: GamePlayer) = groupEnrollmentService.begin(player)

    fun cancelGroupEnrollment(player: GamePlayer) = groupEnrollmentService.cancel(player)

    fun confirmGroupEnrollment(player: GamePlayer, requestId: String) =
        groupEnrollmentService.confirm(player, requestId)

    fun rejectGroupEnrollment(player: GamePlayer, requestId: String) =
        groupEnrollmentService.reject(player, requestId)

    private fun enrollQqGroup(groupOpenId: String): Boolean {
        check(virgaScheduler.isGlobalThread()) { "QQ 群接入只能在服务器主线程确认" }
        val normalized = groupOpenId.trim()
        require(normalized.isNotEmpty()) { "QQ群标识为空" }
        val previous = settings.get()
        if (normalized in previous.bot.allowedGroups) return false

        val groups = linkedSetOf<String>().apply {
            addAll(previous.bot.allowedGroups)
            add(normalized)
        }
        val config = configManager.config
        val previousConfiguredGroups = config.getStringList("bot.groups")
        config.set("bot.groups", groups.toList())
        try {
            config.save(configManager.configPath())
        } catch (error: Throwable) {
            config.set("bot.groups", previousConfiguredGroups)
            throw error
        }

        settings.set(previous.withBot(previous.bot.withAllowedGroups(groups)))
        runCatching(QClient::syncGroupPanels)
            .onFailure { logger.error("QQ群已接入，但指令面板同步失败，将在下次启动时重试。", it) }
        logger.info("一个 QQ 群已由游戏内管理员确认接入（群标识不写入日志）。")
        return true
    }

    override fun reloadPluginConfig() {
        if (!virgaScheduler.isGlobalThread()) {
            virgaScheduler.global { reloadPluginConfig() }
            return
        }
        val previous = settings.get()
        val loaded = configManager.reload()
        val configuredBot = loaded.settings.bot
        val sessionSettingsChanged = BotReloadPolicy.requiresFullRestart(previous.bot, configuredBot)
        val effective = loaded.settings.withBot(
            BotReloadPolicy.applyWithoutReconnect(previous.bot, configuredBot)
        )
        if (sessionSettingsChanged) {
            logger.info("检测到 QQ 机器人连接设置变化：扫码或管理面板保存的凭据会马上切换；手动改的启用状态、AppID/Secret 或 SDK 日志设置要完整重启服务器后生效。")
            pendingRestartReasons += "$BOT_RESTART_REASON（启用状态、AppID/Secret 或 SDK 日志）"
        }
        // Port changes from the panel and the QR switch apply live; only panel.enabled needs a restart.
        if (previous.panel.enabled != effective.panel.enabled) {
            logger.warning("panel.* 设置将在下次完整重启后生效。")
            pendingRestartReasons += "管理面板设置（panel.*）"
        }
        settings.set(effective)
        messages.set(loaded.messages)
        if (previous.runtime != effective.runtime) {
            logger.warning("runtime.* 的线程与队列设置将在下次完整重启后生效。")
            pendingRestartReasons += "运行时线程与队列（runtime.*）"
        }
        // Only renderer/skin construction parameters need a restart; enabled, cooldowns, page size
        // and the admin permission are read on every request.
        fun renderParameters(online: cn.huohuas001.virga.core.config.OnlineListFeatureSettings) =
            online.copy(enabled = true, cooldownSeconds = 0, pageSize = 1, administratorsFirst = true, administratorPermission = "")
        if (previous.performance.fontFamily != effective.performance.fontFamily ||
            renderParameters(previous.onlineList) != renderParameters(effective.onlineList)
        ) {
            logger.warning("性能图与在线列表的渲染/皮肤参数将在下次完整重启后生效；enabled 已立即生效。")
            pendingRestartReasons += "性能图与在线列表的渲染/皮肤参数"
        }
        // Never shut the QQ SDK down during reload: it stops process-wide static executors and
        // leaves the bot offline. The live session keeps running with the refreshed settings.
        QClient.syncGroupPanels()
    }

    override fun createCommandExecutor(): HExecution = GameCommandExecution(server, virgaScheduler)
    override fun dispatchCommand(command: String): CompletableFuture<HExecution> = createCommandExecutor().execute(command)
    override fun broadcastMessage(msg: String) {
        if (virgaScheduler.isGlobalThread()) server.broadcast(GameText.legacy(msg))
        else virgaScheduler.global { server.broadcast(GameText.legacy(msg)) }
    }
    override fun submit(task: Runnable): Cancelable = HandleCancelable(virgaScheduler.global { task.run() })
    override fun submitAsync(task: Runnable): Cancelable {
        val future = executors.submit("Mainline 兼容异步任务") { task.run() }
        return FutureCancelable(future)
    }
    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        HandleCancelable(virgaScheduler.globalLater(delay) { task.run() })
    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        HandleCancelable(virgaScheduler.globalTimer(delay, period) { task.run() })
    override fun getOnlineList(): List<String> = onlineNames.get()
    override fun getConfigFile(): File = File(dataFolder, "config.yml")
    override fun getBotAppId(): String = settings.get().bot.appId
    override fun getBotSecret(): String = settings.get().bot.secret
    override fun getChatFormat(): ChatFormat = settings.get().bridge.let {
        ChatFormat(it.gameToQqFormat, it.qqToGameFormat, it.gameToQq, it.gamePrefix)
    }
    override fun getPlayerEventFormat(): PlayerEventFormat = settings.get().playerNotices.let {
        PlayerEventFormat(it.joinEnabled, it.joinFormat, it.quitEnabled, it.quitFormat, false)
    }
    override fun getMotd(): Motd = Motd("", 0, "", "", false, false)
    override fun getAdminMode(): AdminMode = AdminMode.BOTH
    override fun getAdminList(): List<String> = settings.get().rootAdministrators.sorted()
    override fun getGroupOpenIdList(): List<String> = settings.get().bot.allowedGroups.sorted()
    override fun getFullAmount(): Boolean = settings.get().bridge.qqToGame
    override fun getCommandList(): Map<String, Boolean> = REMOVED_COMMANDS.associateWith { false }
    override fun getCommandMenuList(): Map<String, Boolean> = emptyMap()
    override fun getBotName(): String = settings.get().bot.displayName
    override fun getServerName(): String = settings.get().serverName
    override fun getPlatform(): String = server.platformName
    override fun getPluginVersion(): String = server.modVersion
    override fun shouldSuppressQqBotConsoleOutput(): Boolean = settings.get().bot.suppressSdkConsoleOutput
    override fun isAuthenticationEnabled(): Boolean = false
    override fun isMotdQueryEnabled(): Boolean = false
    override fun getAuditBaseUrl(): String? = null
    override fun getAuditApiKey(): String? = null
    override fun getAuditModel(): String? = null
    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.error(msg, null)

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean = false

    companion object {
        private const val BOT_RESTART_REASON = "QQ 机器人连接设置"
        private const val TICKS_PER_DAY = 20L * 60 * 60 * 24
        private val QQ_MENTION_IN_TEXT = Regex("<@!?[^>]+>")
        private val REMOVED_COMMANDS = setOf("motd", "在线服务器", "认证", "解除认证", "blockMotd", "unblockMotd")
        private val BACKGROUNDS_README = """
            |Virga 的自定义底图放这里～
            |
            |按下面的文件名放图片（.png / .jpg / .jpeg 都行），只会替换最底下的背景层，
            |卡片和格子还是会画在上面，文字位置不会乱：
            |
            |  online-list.png   在线列表      建议 1792x1008（16:9）
            |  status.png        服务器状态    建议 1792x1008（16:9）
            |  inventory.png     背包          建议 1359x1017（约 4:3）
            |  ender-chest.png   末影箱        建议 1620x694（约 7:3）
            |
            |尺寸不一样也没关系，Virga 会等比放大并裁掉多出来的边，让图片铺满。
            |图片最大 32 MiB、边长不超过 8192 像素。换图后下一次出图就生效，不用重启。
            |
            |在 config.yml 的 appearance 里（或管理面板“基础设置 → 图片外观”）可以：
            |  custom-backgrounds  关掉后先用 Virga 自带的底图
            |  veil-percent        自定义背景上的奶白柔光（0–80），图片太花时调大一点
            |""".trimMargin()
    }
}

/** Mainline compatibility tasks run on the server thread. */
private class HandleCancelable(private val handle: TaskHandle) : Cancelable {
    override fun cancel() {
        handle.cancel()
    }
}

private class FutureCancelable(private val future: CompletableFuture<*>) : Cancelable {
    override fun cancel() { future.cancel(false) }
}
