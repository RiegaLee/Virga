package cn.huohuas001.virga.server.inventory

import cn.huohuas001.virga.features.render.BackdropLibrary
import java.util.function.Supplier
import cn.huohuas001.virga.api.BindingService
import cn.huohuas001.virga.api.BindingVerificationState
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.PlayerBinding
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.features.inventory.armor.ArmorItemIconRenderer
import cn.huohuas001.virga.features.inventory.armor.EquipmentAssetResolver
import cn.huohuas001.virga.features.inventory.asset.BundledAssetBootstrap
import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider
import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSource
import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSourceException
import cn.huohuas001.virga.features.inventory.head.PlayerHeadIconCache
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot
import cn.huohuas001.virga.features.inventory.model.InventoryTargetPolicy
import cn.huohuas001.virga.features.inventory.renderer.EnderChestRenderer
import cn.huohuas001.virga.features.inventory.renderer.InventoryRenderMetadata
import cn.huohuas001.virga.features.inventory.renderer.InventoryRenderer
import cn.huohuas001.virga.features.inventory.renderer.Java2DInventoryRenderer
import cn.huohuas001.virga.features.inventory.renderer.RenderResult
import cn.huohuas001.virga.features.inventory.renderer.ThemeLoader
import cn.huohuas001.virga.features.inventory.skin.PlayerModelRenderer
import cn.huohuas001.virga.features.inventory.skin.PlayerPreviewService
import cn.huohuas001.virga.features.inventory.skin.PlayerIdentity
import cn.huohuas001.virga.features.inventory.skin.PlayerSkin
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.access.AccessControl
import cn.huohuas001.virga.core.command.FeatureCommandRouter
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.qq.qqMarkdownMention
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.server.platform.wasRefused
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.server.QqCallbackButtonBridge
import cn.huohuas001.virga.server.SkinService
import cn.huohuas001.virga.server.game.GamePlayer
import cn.huohuas001.virga.server.game.GameServer
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Private inventory and Ender Chest snapshots and their QQ commands. */
class InventoryFeatureService(
    private val server: GameServer,
    private val julLogger: java.util.logging.Logger,
    private val settings: () -> VirgaSettings,
    private val messages: () -> MessageCatalog,
    private val gateway: MessageGateway,
    private val bindings: BindingService,
    private val access: AccessControl,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val skinProfiles: SkinService,
    private val buttonGateway: QqCallbackButtonBridge,
    private val scheduler: VirgaScheduler,
    private val backdrops: BackdropLibrary = BackdropLibrary.bundled()
) : FeatureCommandRouter, AutoCloseable {
    private val inventorySource = GameOnlineInventoryDataSource(SchedulerOnlinePlayerAccess(server, scheduler), settings().serverName)
    private val enderSource = GameOnlineEnderChestDataSource(SchedulerOnlinePlayerAccess(server, scheduler), settings().serverName)
    private val stateExecutor = Executor { task -> executors.executeState("保存玩家数据快照", task) }
    private val inventoryStore = OfflineInventorySnapshotStore(
        server.dataDirectory.resolve("state/player-snapshots/inventory"), julLogger, false, stateExecutor
    )
    private val enderStore = OfflineInventorySnapshotStore(
        server.dataDirectory.resolve("state/player-snapshots/ender-chest"), julLogger, false, stateExecutor
    )
    private val closed = AtomicBoolean(false)
    private val renderers: CompletableFuture<RenderBundle> = executors.submitState("安装并校验私有背包资源") {
        val installation = BundledAssetBootstrap.install(
            server.dataDirectory.resolve("private-inventory"),
            BundledAssetBootstrap.ResourceSource { path -> server.resource(path) }
        )
        val configuredTheme = settings().inventory.theme
        // An unknown theme name (e.g. one carried over from another bot's config) falls back to the
        // bundled theme instead of switching the whole inventory feature off.
        val themeRoot = installation.themesRoot.resolve(configuredTheme).takeIf { java.nio.file.Files.isDirectory(it) }
            ?: installation.themesRoot.resolve(DEFAULT_THEME).also {
                logger.warning("背包主题 $configuredTheme 不存在，已改用内置主题 $DEFAULT_THEME。")
            }
        val theme = ThemeLoader.load(
            themeRoot,
            VanillaImportedAssetProvider.open(installation.vanillaRoot),
            installation.customRoot.resolve("overrides/items")
        )
        val equipmentAssets = EquipmentAssetResolver(installation.packRoot.resolve("armor"))
        theme.textures.setArmorItemRenderer(
            ArmorItemIconRenderer(
                equipmentAssets,
                server.dataDirectory.resolve("cache/armor-items")
            )
        )
        val inventorySettings = settings().inventory
        val playerHeadIcons = if (inventorySettings.playerHeadTexturesEnabled) {
            PlayerHeadIconCache(
                server.dataDirectory.resolve("cache/player-head-icons/v4"),
                julLogger,
                Executor { task -> executors.executeSkin("准备玩家头颅贴图", task) },
                inventorySettings.playerHeadMemoryEntries,
                inventorySettings.playerHeadMaxOutstanding,
                inventorySettings.playerHeadMaxConcurrent,
                Duration.ofSeconds(inventorySettings.playerHeadNegativeCacheSeconds),
                inventorySettings.playerHeadConnectTimeoutMs,
                inventorySettings.playerHeadReadTimeoutMs,
                MojangHeadTextureResolver()
            ).also { theme.textures.setPlayerHeadIcons(it) }
        } else null
        val playerPreviewArea = theme.layout.playerPreview
        val preview = PlayerPreviewService(
            true, skinProfiles, server.dataDirectory.resolve("cache/player-preview"), julLogger,
            "3d", equipmentAssets,
            playerPreviewArea?.width ?: PlayerModelRenderer.WIDTH,
            playerPreviewArea?.height ?: PlayerModelRenderer.HEIGHT
        )
        // Layered backdrops (and custom pictures) only fit the bundled Virga layout.
        val layered = theme.id == "virga"
        RenderBundle(
            Java2DInventoryRenderer(theme, if (layered) Supplier { backdrops.get(BackdropLibrary.Surface.INVENTORY) } else null),
            EnderChestRenderer(
                theme, themeRoot.resolve("ender-chest-background.png"),
                if (layered) Supplier { backdrops.get(BackdropLibrary.Surface.ENDER_CHEST) } else null
            ),
            preview,
            playerHeadIcons
        ).also { if (closed.get()) it.playerHeadIcons?.close() }
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastRequest = ConcurrentHashMap<String, Long>()
    private val inventorySelections = InventorySelectionManager(Duration.ofSeconds(60))
    private val enderSelections = InventorySelectionManager(Duration.ofSeconds(60))
    private val inventoryButtonRegistration = buttonGateway.register(INVENTORY_BUTTON_PREFIX) {
        handleButton(it, false)
    }
    private val enderButtonRegistration = buttonGateway.register(ENDER_BUTTON_PREFIX) {
        handleButton(it, true)
    }
    private val periodic: TaskHandle

    init {
        val ticks = settings().inventory.periodicSnapshotSeconds * 20L
        // Periodic snapshots run on the server thread, a few players per tick (see captureSpread).
        periodic = scheduler.globalTimer(ticks, ticks) { captureSpread() }
        renderers.whenComplete { _, error ->
            if (error != null) logger.error("私有背包资源校验失败，背包图片功能将保持不可用", unwrap(error))
            else logger.info("私有背包与末影箱资源已经通过完整性校验。")
        }
    }

    override fun route(message: BotMessage, command: String, arguments: String): Boolean {
        val ender = command in setOf("我的末影箱", "enderchest", "ec")
        if (!ender && command !in setOf("我的背包", "inventory", "inv")) return false
        if (!settings().inventory.enabled) {
            reply(message, messages().inventory.disabled)
            return true
        }
        if (ender && !settings().inventory.enderChestEnabled) {
            reply(message, messages().inventory.enderDisabled)
            return true
        }
        request(message, arguments, ender)
        return true
    }

    private fun request(message: BotMessage, arguments: String, ender: Boolean) {
        val origin = RequestOrigin(message, false)
        resolveTarget(origin, arguments, ender).whenComplete { target, resolveError ->
            if (resolveError != null || target == null) {
                if (resolveError != null) reply(origin, messages().inventory.failed)
                return@whenComplete
            }
            startResolved(origin, target, ender)
        }
    }

    private fun startResolved(origin: RequestOrigin, target: PlayerBinding, ender: Boolean): Boolean {
        if (!begin(origin)) return false
        val source: InventoryDataSource = if (ender) enderSource else inventorySource
        val store = if (ender) enderStore else inventoryStore
        source.getInventory(target.playerName).whenComplete { online, captureError ->
            if (captureError == null && online != null) {
                store.saveAsync(online)
                render(origin, online, false, ender)
                return@whenComplete
            }
            val cause = unwrap(captureError)
            if (cause is InventoryDataSourceException && cause.reason == InventoryDataSourceException.Reason.PLAYER_STATE_CHANGED) {
                finishText(origin, messages().inventory.stateChanged)
                return@whenComplete
            }
            val uuid = target.playerUuid.orElse(null)
            if (uuid == null) {
                finishText(origin, messages().inventory.noSnapshot)
                return@whenComplete
            }
            executors.submitState("读取离线玩家快照") { store.load(uuid).orElse(null) }
                .whenComplete { offline, loadError ->
                    if (loadError != null) finishText(origin, messages().inventory.failed)
                    else if (offline == null) finishText(origin, messages().inventory.noSnapshot)
                    else render(origin, offline, true, ender)
                }
        }
        return true
    }

    private fun resolveTarget(
        origin: RequestOrigin,
        arguments: String,
        ender: Boolean
    ): CompletableFuture<PlayerBinding?> {
        val message = origin.message
        val own = bindings.findBindings(message.groupOpenId, access.senderOpenId(message))
        val selector = arguments.trim()
        val verified = own.filter { it.verificationState == BindingVerificationState.VERIFIED }
        if (selector.isEmpty()) {
            if (verified.size > 1) {
                sendSelection(origin, verified, ender)
                return CompletableFuture.completedFuture(null)
            }
            if (verified.size == 1) return CompletableFuture.completedFuture(verified.first())
            reply(origin, if (own.isEmpty()) messages().inventory.bindingRequired else messages().inventory.bindingUnverified)
            return CompletableFuture.completedFuture(null)
        }
        if (selector.matches(Regex("[1-9][0-9]*"))) {
            val selected = selectionManager(ender).consumeText(
                message.groupOpenId,
                access.senderOpenId(message),
                selector.toIntOrNull() ?: -1
            )
            if (selected.status == InventorySelectionManager.Status.SELECTED && selected.option != null) {
                val current = verified.firstOrNull(selected.option::matches)
                if (current != null) return CompletableFuture.completedFuture(current)
                reply(origin, messages().inventory.bindingUnverified)
                return CompletableFuture.completedFuture(null)
            }
        }
        val decision = InventoryTargetPolicy.resolve(own, selector, access.isAdministrator(message))
        when (decision.status) {
            InventoryTargetPolicy.Status.OWN_VERIFIED -> return CompletableFuture.completedFuture(decision.binding)
            InventoryTargetPolicy.Status.BINDING_REQUIRED -> reply(origin, messages().inventory.bindingRequired)
            InventoryTargetPolicy.Status.UNVERIFIED -> reply(origin, messages().inventory.bindingUnverified)
            InventoryTargetPolicy.Status.DENIED -> reply(origin, messages().inventory.notAuthorized)
            InventoryTargetPolicy.Status.INVALID_TARGET -> reply(
                origin,
                if (ender) messages().inventory.enderUsage else messages().inventory.usage
            )
            InventoryTargetPolicy.Status.ADMIN_ONLINE_LOOKUP -> Unit
        }
        if (decision.status != InventoryTargetPolicy.Status.ADMIN_ONLINE_LOOKUP) return CompletableFuture.completedFuture(null)
        val result = CompletableFuture<PlayerBinding?>()
        val capture = Runnable {
            val player = server.playerExact(selector)
            if (player == null || !player.isOnline()) {
                reply(origin, messages().inventory.noSnapshot)
                result.complete(null)
            } else {
                result.complete(PlayerBinding(player.name, player.uuid, BindingVerificationState.VERIFIED))
            }
        }
        if (scheduler.isGlobalThread()) {
            capture.run()
        } else if (scheduler.global { capture.run() }.wasRefused()) {
            // Refused during shutdown: the lookup never runs.
            result.complete(null)
        }
        return result
    }

    private fun sendSelection(origin: RequestOrigin, values: List<PlayerBinding>, ender: Boolean) {
        val message = origin.message
        val manager = selectionManager(ender)
        val pending = manager.create(message.groupOpenId, access.senderOpenId(message), values)
        val prefix = if (ender) ENDER_BUTTON_PREFIX else INVENTORY_BUTTON_PREFIX
        val buttons = pending.options.mapIndexed { index, option ->
            QqCallbackButtonBridge.Button(
                label = buttonLabel(index + 1, option.playerName),
                data = "$prefix${pending.nonce}:${index + 1}",
                allowedUserOpenId = access.senderOpenId(message)
            )
        }
        val command = if (ender) "我的末影箱" else "我的背包"
        val commands = values.mapIndexed { index, binding ->
            "/$command ${index + 1}（${binding.playerName}）"
        }.joinToString("\n")
        val fallback = messages().render(messages().inventory.selectionFallback, mapOf("commands" to commands))
        buttonGateway.replySelection(
            message.toReference(),
            messages().decorate(messages().inventory.selectionTitle),
            buttons,
            ownerMention = qqMarkdownMention(access.senderOpenId(message), message.sender.username)
        ).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("玩家账号按钮消息未发送，将回退文字选择：${error?.message ?: result?.diagnostic}")
                reply(origin, fallback)
            }
        }
    }

    private fun handleButton(
        interaction: QqCallbackButtonBridge.Interaction,
        ender: Boolean
    ): QqCallbackButtonBridge.Result {
        if (!settings().isCommandAllowed(interaction.groupOpenId, if (ender) "我的末影箱" else "我的背包")) {
            return QqCallbackButtonBridge.Result.FORBIDDEN
        }
        if (!settings().inventory.enabled || (ender && !settings().inventory.enderChestEnabled)) {
            sendButtonFeedback(
                interaction.groupOpenId,
                if (ender) messages().inventory.enderDisabled else messages().inventory.disabled
            )
            return QqCallbackButtonBridge.Result.FAILED
        }
        val prefix = if (ender) ENDER_BUTTON_PREFIX else INVENTORY_BUTTON_PREFIX
        if (!interaction.data.startsWith(prefix)) return QqCallbackButtonBridge.Result.NOT_HANDLED
        val payload = interaction.data.substring(prefix.length)
        val separator = payload.lastIndexOf(':')
        if (separator <= 0 || separator == payload.lastIndex) return QqCallbackButtonBridge.Result.FAILED
        val nonce = payload.substring(0, separator)
        val selection = payload.substring(separator + 1).toIntOrNull()
            ?: return QqCallbackButtonBridge.Result.FAILED
        val selected = selectionManager(ender).consumeButton(
            interaction.groupOpenId,
            interaction.userOpenId,
            nonce,
            selection
        )
        when (selected.status) {
            InventorySelectionManager.Status.FORBIDDEN -> return QqCallbackButtonBridge.Result.FORBIDDEN
            InventorySelectionManager.Status.DUPLICATE -> return QqCallbackButtonBridge.Result.DUPLICATE
            InventorySelectionManager.Status.EXPIRED -> {
                sendButtonFeedback(
                    interaction.groupOpenId,
                    messages().render(
                        messages().inventory.selectionExpired,
                        mapOf("command" to if (ender) "我的末影箱" else "我的背包")
                    )
                )
                return QqCallbackButtonBridge.Result.EXPIRED
            }
            InventorySelectionManager.Status.INVALID -> return QqCallbackButtonBridge.Result.FAILED
            InventorySelectionManager.Status.SELECTED -> Unit
        }
        val option = selected.option ?: return QqCallbackButtonBridge.Result.FAILED
        val current = bindings.findBindings(interaction.groupOpenId, interaction.userOpenId)
            .firstOrNull { it.verificationState == BindingVerificationState.VERIFIED && option.matches(it) }
        if (current == null) {
            sendButtonFeedback(interaction.groupOpenId, messages().inventory.bindingUnverified)
            return QqCallbackButtonBridge.Result.FAILED
        }
        val synthetic = BotMessage(
            interaction.interactionId,
            interaction.groupOpenId,
            interaction.groupOpenId,
            SenderSnapshot(null, interaction.userOpenId, "button-user", "MEMBER"),
            "",
            "",
            null,
            0,
            emptyList(),
            emptyList()
        )
        return if (startResolved(RequestOrigin(synthetic, true), current, ender)) {
            QqCallbackButtonBridge.Result.SUCCESS
        } else QqCallbackButtonBridge.Result.TOO_FREQUENT
    }

    private fun sendButtonFeedback(groupOpenId: String, text: String) {
        gateway.sendText(groupOpenId, messages().decorate(text)).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("玩家账号按钮反馈发送失败：${error?.message ?: result?.diagnostic}")
            }
        }
    }

    private fun selectionManager(ender: Boolean): InventorySelectionManager =
        if (ender) enderSelections else inventorySelections

    private fun buttonLabel(index: Int, playerName: String): String {
        val value = "$index $playerName"
        val count = value.codePointCount(0, value.length)
        return if (count <= MAX_BUTTON_LABEL_CODE_POINTS) value
        else value.substring(0, value.offsetByCodePoints(0, MAX_BUTTON_LABEL_CODE_POINTS))
    }

    private fun render(origin: RequestOrigin, snapshot: InventorySnapshot, offline: Boolean, ender: Boolean) {
        renderers.whenComplete { bundle, initError ->
            if (initError != null || bundle == null) {
                finishText(origin, messages().inventory.failed)
                return@whenComplete
            }
            val headPreparation = bundle.playerHeadIcons?.prepare(
                snapshot,
                settings().inventory.playerHeadMaxNewPerRequest
            ) ?: CompletableFuture.completedFuture(null)
            val skinPreparation = if (ender) CompletableFuture.completedFuture(null) else prepareSkin(snapshot)
            CompletableFuture.allOf(headPreparation, skinPreparation).whenComplete { _, skinError ->
                if (skinError != null) logger.warning("玩家皮肤准备失败，将使用本地回退：${unwrap(skinError)?.message}")
                executors.submitRender(if (ender) "渲染末影箱" else "渲染背包") {
                    val metadata = if (offline) InventoryRenderMetadata.offline(snapshot.capturedAt) else InventoryRenderMetadata.realtime(snapshot.capturedAt)
                    val renderer: InventoryRenderer = if (ender) bundle.ender else bundle.inventory
                    val preview = if (ender) null else bundle.preview.preview(snapshot)
                    renderer.render(snapshot, preview, metadata)
                }.whenComplete { image, renderError ->
                    if (renderError != null || image == null) {
                        logger.error("玩家数据图片渲染失败", unwrap(renderError))
                        finishText(origin, messages().inventory.failed)
                    } else sendImage(origin, snapshot, image, offline, ender)
                }
            }
        }
    }

    /**
     * Console preview: renders [playerName]'s live inventory and Ender Chest (or the saved
     * offline snapshots) into [directory] without QQ.
     */
    fun renderPreview(playerName: String, directory: java.nio.file.Path): CompletableFuture<List<java.nio.file.Path>> {
        fun snapshot(ender: Boolean): CompletableFuture<Pair<InventorySnapshot, Boolean>?> {
            val source: InventoryDataSource = if (ender) enderSource else inventorySource
            val store = if (ender) enderStore else inventoryStore
            val result = CompletableFuture<Pair<InventorySnapshot, Boolean>?>()
            source.getInventory(playerName).whenComplete { online, error ->
                if (error == null && online != null) {
                    result.complete(online to false)
                    return@whenComplete
                }
                executors.submitState("读取离线快照预览") {
                    server.knownPlayerUuid(playerName)?.let { store.load(it).orElse(null) }
                }.whenComplete { offline, _ -> result.complete(offline?.let { it to true }) }
            }
            return result
        }
        return renderers.thenCompose { bundle ->
            snapshot(false).thenCombine(snapshot(true)) { inventory, ender -> Triple(bundle, inventory, ender) }
        }.thenCompose { (bundle, inventory, ender) ->
            val skin = inventory?.first?.let(::prepareSkin) ?: CompletableFuture.completedFuture(null)
            val heads = inventory?.first?.let { bundle.playerHeadIcons?.prepare(it, settings().inventory.playerHeadMaxNewPerRequest) }
                ?: CompletableFuture.completedFuture(null)
            CompletableFuture.allOf(skin.exceptionally { null }, heads.exceptionally { null }).thenCompose {
                executors.submitRender("渲染背包预览") {
                    java.nio.file.Files.createDirectories(directory)
                    val written = ArrayList<java.nio.file.Path>()
                    inventory?.let { (value, offline) ->
                        val metadata = if (offline) InventoryRenderMetadata.offline(value.capturedAt) else InventoryRenderMetadata.realtime(value.capturedAt)
                        val file = directory.resolve("inventory-${value.playerName}.png")
                        java.nio.file.Files.write(file, bundle.inventory.render(value, bundle.preview.preview(value), metadata).bytes)
                        written.add(file)
                    }
                    ender?.let { (value, offline) ->
                        val metadata = if (offline) InventoryRenderMetadata.offline(value.capturedAt) else InventoryRenderMetadata.realtime(value.capturedAt)
                        val file = directory.resolve("ender-chest-${value.playerName}.png")
                        java.nio.file.Files.write(file, bundle.ender.render(value, null, metadata).bytes)
                        written.add(file)
                    }
                    written.toList()
                }
            }
        }
    }

    private fun prepareSkin(snapshot: InventorySnapshot): CompletableFuture<PlayerSkin?> {
        val identity = PlayerIdentity(snapshot.playerUuid, snapshot.playerName)
        val result = CompletableFuture<PlayerSkin?>()
        val prepare = {
            skinProfiles.prepare(identity).whenComplete { skin, error ->
                if (error == null) result.complete(skin) else result.completeExceptionally(error)
            }
            Unit
        }
        // Refresh the live profile on the server thread, falling back to the stored skin.
        val lookup = Runnable {
            val player = server.player(snapshot.playerUuid)
                ?.takeIf { it.isOnline() }
                ?: server.playerExact(snapshot.playerName)?.takeIf { it.isOnline() }
            if (player != null) skinProfiles.observe(player)
            prepare()
        }
        if (scheduler.isGlobalThread()) lookup.run()
        else if (scheduler.global { lookup.run() }.wasRefused()) prepare()
        return result
    }

    private fun sendImage(origin: RequestOrigin, snapshot: InventorySnapshot, image: RenderResult, offline: Boolean, ender: Boolean) {
        val note = if (offline) messages().render(messages().inventory.offlineLabel, mapOf(
            "time" to DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(snapshot.capturedAt)
        )) else null
        val name = "Virga-${if (ender) "末影箱" else "背包"}-${snapshot.playerName}.png"
        val send = if (origin.proactive) {
            gateway.sendImage(origin.message.groupOpenId, image.bytes, image.mimeType, name, note)
        } else {
            gateway.replyImage(origin.message.toReference(), image.bytes, image.mimeType, name, note)
        }
        send.whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("玩家数据图片发送失败：${error?.message ?: result?.diagnostic}")
                reply(origin, messages().inventory.failed)
            }
            end(origin)
        }
    }

    /** Called on the server thread when a player is leaving; the player may already be removed. */
    fun onQuit(player: GamePlayer) = capture(player, departing = true)

    private fun captureAll() = server.onlinePlayers().forEach(::capture)

    private var spreadTask: TaskHandle? = null

    /**
     * Snapshots every online player, [PLAYERS_PER_TICK] per tick, so a busy server never spends
     * one whole tick copying hundreds of inventories. Players who left meanwhile were already
     * saved by [onQuit] and are skipped.
     */
    private fun captureSpread() {
        if (spreadTask?.isClosed() == false) return
        val queue = ArrayDeque(server.onlinePlayers())
        if (queue.isEmpty()) return
        spreadTask = scheduler.globalTimer(1L, 1L) {
            repeat(PLAYERS_PER_TICK) {
                val player = queue.removeFirstOrNull() ?: return@repeat
                if (player.isOnline()) capture(player)
            }
            if (queue.isEmpty() || closed.get()) spreadTask?.cancel()
        }
    }

    private fun capture(player: GamePlayer, departing: Boolean = false) {
        runCatching {
            // Preserve the live game profile skin whenever player data is snapshotted.
            skinProfiles.observe(player)
            inventoryStore.saveAsync(if (departing) inventorySource.captureDeparting(player) else inventorySource.capturePlayer(player))
            enderStore.saveAsync(if (departing) enderSource.captureDeparting(player) else enderSource.capturePlayer(player))
        }.onFailure { logger.error("保存 ${player.name} 的离线快照失败", it) }
    }

    private fun begin(origin: RequestOrigin): Boolean {
        val message = origin.message
        val key = requestKey(message)
        if (!inFlight.add(key)) {
            reply(origin, messages().features.commandBusy)
            return false
        }
        val now = System.nanoTime()
        val cooldown = Duration.ofSeconds(settings().inventory.cooldownSeconds).toNanos()
        val previous = lastRequest[key]
        if (previous != null && now - previous < cooldown) {
            inFlight.remove(key)
            reply(origin, messages().render(messages().features.commandCooldown, mapOf("seconds" to ((cooldown - (now - previous)) / 1_000_000_000L).coerceAtLeast(1))))
            return false
        }
        lastRequest[key] = now
        return true
    }

    private fun requestKey(message: BotMessage) = message.groupOpenId + "\u0000" + access.senderOpenId(message)
    private fun end(origin: RequestOrigin) = inFlight.remove(requestKey(origin.message))
    private fun finishText(origin: RequestOrigin, text: String) { reply(origin, text); end(origin) }
    private fun reply(message: BotMessage, text: String) {
        reply(RequestOrigin(message, false), text)
    }
    private fun reply(origin: RequestOrigin, text: String) {
        val send = if (origin.proactive) {
            gateway.sendText(origin.message.groupOpenId, messages().decorate(text))
        } else {
            gateway.replyText(origin.message.toReference(), messages().decorate(text))
        }
        send.whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) logger.warning("玩家数据命令回复失败：${error?.message ?: result?.diagnostic}")
        }
    }

    private fun unwrap(error: Throwable?): Throwable? {
        var current = error ?: return null
        while (current is CompletionException && current.cause != null) current = current.cause!!
        return current
    }

    override fun close() {
        closed.set(true)
        inventoryButtonRegistration.close()
        enderButtonRegistration.close()
        periodic.cancel()
        spreadTask?.cancel()
        // Final snapshot while the server thread can still read every player.
        if (scheduler.isGlobalThread()) runCatching { captureAll() }
        inventoryStore.close()
        enderStore.close()
        renderers.getNow(null)?.playerHeadIcons?.close()
        inFlight.clear()
    }

    private data class RenderBundle(
        val inventory: InventoryRenderer,
        val ender: InventoryRenderer,
        val preview: PlayerPreviewService,
        val playerHeadIcons: PlayerHeadIconCache?
    )

    private data class RequestOrigin(val message: BotMessage, val proactive: Boolean)

    private companion object {
        const val INVENTORY_BUTTON_PREFIX = "hbi:i:"
        const val ENDER_BUTTON_PREFIX = "hbi:e:"
        const val MAX_BUTTON_LABEL_CODE_POINTS = 18
        const val PLAYERS_PER_TICK = 4
        const val DEFAULT_THEME = "virga"
    }
}
