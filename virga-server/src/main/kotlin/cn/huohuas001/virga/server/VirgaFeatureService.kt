@file:Suppress("DEPRECATION")

package cn.huohuas001.virga.server

import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.features.onlinelist.model.OnlineListPages
import cn.huohuas001.virga.features.onlinelist.model.PlayerSnapshot
import cn.huohuas001.virga.features.onlinelist.model.ServerSnapshot
import cn.huohuas001.virga.features.onlinelist.render.OnlineListRenderer
import cn.huohuas001.virga.features.onlinelist.skin.AvatarCache
import cn.huohuas001.virga.features.performance.model.PerformanceHealth
import cn.huohuas001.virga.features.performance.model.PerformanceSnapshot
import cn.huohuas001.virga.features.performance.model.PerformanceThresholds
import cn.huohuas001.virga.features.performance.monitor.TickRateSampler
import cn.huohuas001.virga.features.performance.render.PerformanceRenderer
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.command.FeatureCommandRouter
import cn.huohuas001.virga.core.config.MessageCatalog
import cn.huohuas001.virga.core.config.VirgaSettings
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import cn.huohuas001.virga.core.runtime.RuntimeTaskHandle
import cn.huohuas001.virga.features.image.FallbackImageSender
import cn.huohuas001.virga.server.platform.VirgaScheduler
import cn.huohuas001.virga.api.TaskHandle
import cn.huohuas001.virga.features.render.BackdropLibrary
import cn.huohuas001.virga.server.game.GameServer
import cn.huohuas001.virga.server.performance.CpuLoadSampler
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Server status card and online list, the two core image features. */
class VirgaFeatureService(
    private val server: GameServer,
    private val julLogger: java.util.logging.Logger,
    private val settings: () -> VirgaSettings,
    private val messages: () -> MessageCatalog,
    private val gateway: MessageGateway,
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val skinProfiles: SkinService,
    private val scheduler: VirgaScheduler,
    private val backdrops: BackdropLibrary = BackdropLibrary.bundled()
) : FeatureCommandRouter, AutoCloseable {
    private val thresholds = PerformanceThresholds.defaults()
    private val tpsLabel = "TPS"
    private val performanceRenderer = PerformanceRenderer(backdrops, settings().performance.fontFamily, thresholds, tpsLabel)
    private val tickSampler = TickRateSampler()
    private val cpuSampler = CpuLoadSampler()
    private val cpuTask = executors.scheduleAtFixedRate("采样 CPU 负载", Duration.ZERO, Duration.ofSeconds(1)) {
        cpuSampler.sample()
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastRequestNanos = ConcurrentHashMap<String, Long>()
    private val tickTask: TaskHandle
    private val onlineRenderer: OnlineListRenderer

    init {
        val online = settings().onlineList
        val avatarCache = AvatarCache(
            julLogger,
            { task -> executors.executeSkin("下载玩家皮肤", task) },
            online.skinConnectTimeoutMs,
            online.skinReadTimeoutMs,
            online.skinCacheEntries,
            online.skinEnabled
        )
        onlineRenderer = OnlineListRenderer(
            backdrops,
            avatarCache,
            online.columns,
            online.fontFamily,
            online.footerText,
            online.fallbackAvatar
        )
        // Samples the server thread every tick; the status card shows its one-minute TPS.
        tickTask = scheduler.globalTimer(1L, 1L) { tickSampler.record(System.nanoTime()) }
    }

    override fun route(message: BotMessage, command: String, arguments: String): Boolean = when (command) {
        "服务器状态" -> {
            if (settings().performance.enabled) requestPerformance(message) else replyText(message, messages().features.performanceDisabled)
            true
        }
        "在线列表" -> {
            if (settings().onlineList.enabled) requestOnlineImage(message, arguments) else replyText(message, messages().features.onlineListDisabled)
            true
        }
        "查在线" -> {
            if (settings().onlineList.enabled) requestOnlineText(message) else replyText(message, messages().features.onlineListDisabled)
            true
        }
        else -> false
    }

    private fun requestPerformance(message: BotMessage) {
        if (!begin(message, settings().performance.cooldownSeconds)) return
        capturePerformance().whenComplete { snapshot, captureError ->
            if (captureError != null || snapshot == null) {
                finishWithText(message, messages().features.performanceSnapshotFailed)
                return@whenComplete
            }
            renderAndReply(
                message,
                "Virga-服务器状态.png",
                { performanceRenderer.render(snapshot) },
                performanceText(snapshot)
            )
        }
    }

    private fun requestOnlineImage(message: BotMessage, arguments: String) {
        if (!begin(message, settings().onlineList.cooldownSeconds)) return
        val requestedPage = arguments.trim().ifEmpty { "1" }.toIntOrNull()
        if (requestedPage == null || requestedPage < 1) {
            finishWithText(message, messages().features.pageInvalid)
            return
        }
        captureOnline().whenComplete { complete, captureError ->
            if (captureError != null || complete == null) {
                finishWithText(message, messages().features.onlineSnapshotFailed)
                return@whenComplete
            }
            val page = try {
                OnlineListPages.page(complete, requestedPage, settings().onlineList.pageSize)
            } catch (_: IllegalArgumentException) {
                finishWithText(
                    message,
                    messages().render(
                        messages().features.pageOutOfRange,
                        mapOf("pages" to OnlineListPages.totalPages(complete.players.size, settings().onlineList.pageSize))
                    )
                )
                return@whenComplete
            }
            renderAndReply(message, "Virga-在线列表-$requestedPage.png", { onlineRenderer.render(page) }, onlineText(page))
        }
    }

    private fun requestOnlineText(message: BotMessage) {
        if (!begin(message, settings().onlineList.cooldownSeconds)) return
        captureOnline().whenComplete { snapshot, error ->
            if (error != null || snapshot == null) finishWithText(message, messages().features.onlineSnapshotFailed)
            else finishWithText(message, onlineText(snapshot))
        }
    }

    private fun renderAndReply(message: BotMessage, fileName: String, render: () -> ByteArray, fallback: String) {
        val completed = AtomicBoolean(false)
        val timeout: RuntimeTaskHandle = try {
            executors.schedule(
                "图片指令超时",
                Duration.ofSeconds(settings().runtime.imageCommandTimeoutSeconds)
            ) {
                if (completed.compareAndSet(false, true)) {
                    logger.warning("图片指令超时，已改用文字回复：$fileName")
                    replyText(message, fallback) { end(message) }
                }
            }
        } catch (error: Throwable) {
            logger.error("无法登记图片指令超时保护", error)
            finishWithText(message, fallback)
            return
        }
        executors.submitRender("渲染 $fileName", render).whenComplete { bytes, renderError ->
            if (completed.get()) return@whenComplete
            if (renderError != null || bytes == null) {
                logger.error("图片渲染失败，已改用文字回复：$fileName", renderError)
            }
            FallbackImageSender.replyRendered(
                gateway,
                message.toReference(),
                bytes,
                renderError,
                fileName,
                messages().decorate(fallback),
                { completed.compareAndSet(false, true) }
            ).whenComplete { result, sendError ->
                if (sendError != null || result == null || !result.isSuccess) {
                    logger.warning("图片与文字回退均未发送成功：${sendError?.message ?: result?.diagnostic ?: "无结果"}")
                    completed.compareAndSet(false, true)
                } else {
                    completed.compareAndSet(false, true)
                }
                timeout.close()
                end(message)
            }
        }
    }

    private fun capturePerformance(): CompletableFuture<PerformanceSnapshot> = scheduler.supplyGlobal {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        PerformanceSnapshot(
            settings().serverName,
            Instant.now(),
            tickSampler.oneMinuteTps(),
            runCatching { server.averageTickMillis() }.getOrDefault(Double.NaN),
            cpuSampler.systemPercent(),
            cpuSampler.processPercent(),
            used,
            runtime.maxMemory(),
            server.onlinePlayers().size,
            server.maxPlayers()
        )
    }

    // Roster, skin profiles and permissions are all read on the server thread.
    private fun captureOnline(): CompletableFuture<ServerSnapshot> =
        scheduler.supplyGlobal { server.onlinePlayers() }.thenCompose { online ->
            val config = settings().onlineList
            val perPlayer = online.map { player ->
                scheduler.supplyForPlayer(player) {
                    PlayerSnapshot(
                        it.name,
                        it.uuid.toString(),
                        skinProfiles.observe(it),
                        config.administratorPermission.isNotBlank() && it.hasPermission(config.administratorPermission)
                    )
                }.handle<PlayerSnapshot?> { snapshot, _ -> snapshot } // players who left meanwhile are skipped
            }
            CompletableFuture.allOf(*perPlayer.toTypedArray()).thenApply {
                ServerSnapshot(
                    settings().serverName,
                    server.maxPlayers(),
                    OnlineListPages.sorted(perPlayer.mapNotNull { it.getNow(null) }, config.administratorsFirst),
                    Instant.now()
                )
            }
        }

    /**
     * Console preview: renders the status card and the first online-list page into [directory]
     * without QQ. Completes with the written files.
     */
    fun renderPreview(directory: java.nio.file.Path): CompletableFuture<List<java.nio.file.Path>> =
        capturePerformance().thenCombine(captureOnline()) { performance, online -> performance to online }
            .thenCompose { (performance, online) ->
                executors.submitRender("渲染预览图") {
                    java.nio.file.Files.createDirectories(directory)
                    val status = directory.resolve("status.png")
                    java.nio.file.Files.write(status, performanceRenderer.render(performance))
                    val list = directory.resolve("online-list.png")
                    val page = OnlineListPages.page(online, 1, settings().onlineList.pageSize.coerceAtLeast(1))
                    java.nio.file.Files.write(list, onlineRenderer.render(page))
                    listOf(status, list)
                }
            }

    private fun performanceText(snapshot: PerformanceSnapshot): String {
        fun metric(value: Double, suffix: String, decimals: Int = 1): String =
            if (value.isNaN()) "采样中" else "%.${decimals}f%s".format(Locale.ROOT, value, suffix)
        val memoryPercent = if (snapshot.maxMemoryBytes <= 0L) Double.NaN
        else snapshot.usedMemoryBytes * 100.0 / snapshot.maxMemoryBytes
        return messages().render(
            messages().features.performanceFallback,
            mapOf(
                "server" to snapshot.serverName,
                "health" to PerformanceHealth.title(PerformanceHealth.overall(snapshot, thresholds)),
                "tps" to metric(snapshot.tps, ""),
                "mspt" to if (snapshot.mspt.isNaN()) "不可用" else metric(snapshot.mspt, " ms"),
                "system_cpu" to metric(snapshot.systemCpuPercent, "%"),
                "process_cpu" to metric(snapshot.processCpuPercent, "%"),
                "memory" to metric(memoryPercent, "%"),
                "online" to snapshot.onlinePlayers,
                "max" to snapshot.maxPlayers,
                "diagnosis" to PerformanceHealth.diagnosis(snapshot, thresholds)
            )
        )
    }

    private fun onlineText(snapshot: ServerSnapshot): String {
        val catalog = messages().features
        val names = snapshot.players.joinToString("、") {
            if (it.isAdministrator) it.name + catalog.administratorSuffix else it.name
        }
        if (names.isEmpty()) return messages().render(catalog.onlineEmpty, mapOf("server" to snapshot.serverName))
        val page = if (snapshot.totalPages > 1) {
            messages().render(catalog.onlinePage, mapOf("current" to snapshot.currentPage, "total" to snapshot.totalPages))
        } else ""
        return messages().render(
            catalog.onlineFallback,
            mapOf(
                "server" to snapshot.serverName,
                "online" to snapshot.onlinePlayers,
                "max" to snapshot.maxPlayers,
                "page" to page,
                "players" to names
            )
        )
    }

    private fun begin(message: BotMessage, cooldownSeconds: Long): Boolean {
        val key = message.groupOpenId
        if (!inFlight.add(key)) {
            replyText(message, messages().features.commandBusy)
            return false
        }
        val now = System.nanoTime()
        val previous = lastRequestNanos[key]
        val cooldownNanos = Duration.ofSeconds(cooldownSeconds).toNanos()
        if (previous != null && now - previous < cooldownNanos) {
            inFlight.remove(key)
            val remaining = ((cooldownNanos - (now - previous)) / 1_000_000_000L).coerceAtLeast(1L)
            replyText(
                message,
                messages().render(messages().features.commandCooldown, mapOf("seconds" to remaining))
            )
            return false
        }
        lastRequestNanos[key] = now
        return true
    }

    private fun finishWithText(message: BotMessage, text: String) = replyText(message, text) { end(message) }

    private fun replyText(message: BotMessage, text: String, after: () -> Unit = {}) {
        gateway.replyText(message.toReference(), messages().decorate(text)).whenComplete { result, error ->
            if (error != null || result == null || !result.isSuccess) {
                logger.warning("核心功能文字回复失败：${error?.message ?: result?.diagnostic ?: "无结果"}")
            }
            after()
        }
    }

    private fun end(message: BotMessage) {
        inFlight.remove(message.groupOpenId)
    }

    override fun close() {
        tickTask.cancel()
        cpuTask.cancel()
        inFlight.clear()
        lastRequestNanos.clear()
    }
}
