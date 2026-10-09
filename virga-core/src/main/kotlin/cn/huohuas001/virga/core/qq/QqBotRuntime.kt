package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.api.AttachmentSnapshot
import cn.huohuas001.virga.api.BotMessage
import cn.huohuas001.virga.api.MentionSnapshot
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.SendResult
import cn.huohuas001.virga.api.SenderSnapshot
import cn.huohuas001.virga.core.VirgaLogger
import cn.huohuas001.virga.core.config.BotSettings
import cn.huohuas001.virga.core.runtime.RuntimeExecutors
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData
import io.github.kloping.qqbot.http.data.PanelDefinition
import io.github.kloping.qqbot.http.data.PanelItem
import io.github.kloping.common.Public
import io.github.kloping.spt.interfaces.component.ContextManager
import io.github.kloping.qqbot.network.WssWorker
import io.github.kloping.qqbot.network.WebSocketListener
import io.github.kloping.qqbot.utils.LoggerImpl
import org.java_websocket.client.WebSocketClient
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class QqBotRuntime(
    private val executors: RuntimeExecutors,
    private val logger: VirgaLogger,
    private val messageHandler: cn.huohuas001.virga.core.bot.events.GroupMessageHandler,
    private val groupMembershipTracker: GroupMembershipTracker,
    panelStateFile: Path,
    private val commandPanel: () -> QqPanelSnapshot = { QqPanelSnapshot(emptyList(), emptyList()) }
) : QqTransport {
    private val closed = AtomicBoolean(false)
    private val panelSyncQueued = AtomicBoolean(false)
    private val panelSyncDirty = AtomicBoolean(false)
    private val panelStatus = AtomicReference(QqPanelSyncStatus())
    fun commandPanelStatus(): QqPanelSyncStatus = panelStatus.get().let {
        if (isAccepting()) it else it.copy(state = "disconnected", message = "QQ 尚未连接，连接成功后会自动同步")
    }
    private val generation = AtomicLong(0)
    private val starter = AtomicReference<Starter?>()
    /** True between the gateway's READY/RESUMED and the next WebSocket close. */
    private val sessionReady = AtomicBoolean(false)
    /** Completed by the next READY/RESUMED; replaced on every start or credential switch. */
    private val pendingReady = AtomicReference(CompletableFuture<Boolean>())
    /** Set once any Starter was created: the SDK's static executors cannot be restarted afterwards. */
    private val starterCreated = AtomicBoolean(false)
    private val panelStateStore = QqPanelStateStore(panelStateFile)
    private val lastLoggedPanels = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)

    /**
     * True while this process has never created a QQ SDK Starter (e.g. it booted without
     * credentials), so a first connection can still start in-process without a server restart.
     */
    fun canStartWithoutRestart(): Boolean = !closed.get() && !starterCreated.get()

    /** Internal compatibility hook for the proven Addon button bridges. */
    fun currentStarterForCoreFeatures(): Starter? = starter.get()

    /**
     * Starts the SDK and completes with true once the QQ gateway session is READY, or false when
     * the client could not start or no session came up within [READY_TIMEOUT_SECONDS] (the SDK
     * keeps reconnecting in the background; [isAccepting] turns true whenever it succeeds).
     * [logFilePattern] is kept for callers; SDK file logging is intentionally disabled.
     */
    @Suppress("UNUSED_PARAMETER")
    fun start(settings: BotSettings, logFilePattern: String?): CompletionStage<Boolean> {
        if (closed.get()) return CompletableFuture.completedFuture(false)

        val token = generation.incrementAndGet()
        val ready = CompletableFuture<Boolean>().also(pendingReady::set)
        return executors.submit("启动 QQ 客户端") {
            stopCurrent()
            if (closed.get() || token != generation.get()) return@submit false
            if (!settings.enabled) {
                logger.info("QQ 机器人已按配置关闭。")
                return@submit false
            }
            if (!settings.hasCredentials) {
                logger.warning("未配置 QQ AppID 或 Secret，QQ 客户端暂不启动。")
                return@submit false
            }
            starterCreated.set(true)
            val sdkLogger = installQqSdkLogger(logger)
            val created = Starter(settings.appId, "", settings.secret)
            created.APPLICATION.logger = sdkLogger
            created.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_MEMBER_EVENT, Intents.GROUP_INTENTS, Intents.INTERACTION)
            sessionReady.set(false)
            created.config.webSocketListener = SessionWatcher(token)
            // The SDK's own package scan cannot read mod-loader file systems; hand it the class list.
            created.APPLICATION.PRE_SCAN_RUNNABLE.add {
                if (SdkComponentIndex.prefill(created.APPLICATION.INSTANCE.packageScanner) == 0) {
                    logger.warning("没能列出 QQ SDK 的组件类，改用 SDK 自带的扫描。")
                }
            }
            created.run()
            if (closed.get() || token != generation.get()) {
                created.shutdown()
                return@submit false
            }
            created.registerListenerHost(messageHandler)
            created.registerListenerHost(groupMembershipTracker)
            registerMessageSwitchEvents(created)
            created.APPLICATION.logger.setLogLevel(1)
            created.APPLICATION.logger.setOutFile(null)
            starter.set(created)
            // READY may already have arrived on the SDK's WebSocket thread.
            if (sessionReady.get()) syncCommandPanel()
            else logger.info("QQ 客户端已启动，正在等待 QQ 网关建立会话……")
            true
        }.whenComplete { _, error ->
            if (error != null && !closed.get()) logger.error("QQ 客户端启动失败", unwrap(error))
        }.thenCompose { started ->
            if (!started) CompletableFuture.completedFuture(false) else awaitReady(ready, token)
        }
    }

    /**
     * Switches the running SDK to new credentials without a restart. Scanning the QR code again
     * makes QQ issue a new Secret and drop the session that used the old one; the SDK would keep
     * reconnecting with the old Secret. The new AppID/Secret go into the SDK's context (where its
     * own 4004 recovery reads them), a token is fetched right away (rejects a bad Secret), and the
     * gateway connection is rebuilt. Completes like [start]: true once the new session is READY.
     * False when no SDK is running (use [start]) or the switch could not be applied.
     */
    fun switchCredentials(settings: BotSettings): CompletionStage<Boolean> {
        val current = starter.get()
        if (closed.get() || current == null || !settings.enabled || !settings.hasCredentials) {
            return CompletableFuture.completedFuture(false)
        }
        val token = generation.get()
        val ready = CompletableFuture<Boolean>().also(pendingReady::set)
        return executors.submit("切换 QQ 机器人凭据") {
            if (closed.get() || starter.get() !== current) return@submit false
            val context = current.APPLICATION.INSTANCE.contextManager
            current.config.appid = settings.appId
            current.config.secret = settings.secret
            context.append(settings.appId, Starter.APPID_ID)
            context.append(settings.secret, Starter.SECRET_ID)
            context.append("Bot ${settings.appId}.${current.config.token.orEmpty()}", Starter.AUTH_ID)
            // Fails here, with QQ's reason in the log, when the new Secret is not accepted.
            context.getContextEntity(Start0::class.java).updateToken()
            sessionReady.set(false)
            logger.info("已换用新凭据，正在重新连接 QQ 网关……")
            // Confirming the scan made QQ drop the old session (1011), and the SDK queued its own
            // reconnect a few seconds out; left alone it would close the new session right after it
            // came up. Pause SDK reconnects, connect once with the new token, then hand reconnecting
            // back to the SDK (retrying at once if the new session is not up by then).
            val worker = context.getContextEntity(WssWorker::class.java)
            current.config.reconnect = false
            reconnectGateway(context, worker)
            CompletableFuture.delayedExecutor(SDK_RECONNECT_PAUSE_SECONDS, TimeUnit.SECONDS).execute {
                if (closed.get() || starter.get() !== current) return@execute
                current.config.reconnect = true
                if (!sessionReady.get()) reconnectGateway(context, worker)
            }
            true
        }.whenComplete { _, error ->
            if (error != null && !closed.get()) logger.error("切换 QQ 机器人凭据失败", unwrap(error))
        }.thenCompose { switched ->
            if (!switched) CompletableFuture.completedFuture(false) else awaitReady(ready, token)
        }
    }

    /** Rebuilds the gateway connection (the SDK's own identifyConnect assumes a socket exists). */
    private fun reconnectGateway(context: ContextManager, worker: WssWorker) {
        context.getContextEntity(Future::class.java, Starter.MAIN_FUTURE_ID)?.cancel(true)
        worker.webSocket?.takeIf { !it.isClosed }?.close()
        context.append(Public.EXECUTOR_SERVICE1.submit(worker), Starter.MAIN_FUTURE_ID)
    }

    private fun awaitReady(ready: CompletableFuture<Boolean>, token: Long): CompletionStage<Boolean> =
        ready.orTimeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionally {
            if (!closed.get() && token == generation.get()) {
                logger.warning("$READY_TIMEOUT_SECONDS 秒内没等到 QQ 网关建立会话……SDK 会在后台继续重连，原因请看上面的日志。")
            }
            false
        }

    /** Connected means a live gateway session, not merely a started SDK. */
    override fun isAccepting(): Boolean = !closed.get() && starter.get() != null && sessionReady.get()

    /** Tracks the gateway session of the Starter created for [token]. */
    private inner class SessionWatcher(private val token: Long) : WebSocketListener {
        override fun onMessage(client: WebSocketClient, msg: String): Boolean {
            if (token != generation.get() || !msg.contains("\"t\"")) return true
            val type = runCatching { JSON.parseObject(msg).getString("t") }.getOrNull()
            if (type == "READY" || type == "RESUMED") {
                if (!sessionReady.getAndSet(true)) {
                    logger.info(if (type == "READY") "QQ 已连接（网关会话就绪）。" else "QQ 连接已恢复。")
                    syncCommandPanel()
                }
                pendingReady.get().complete(true)
            }
            return true
        }

        override fun onClose(client: WebSocketClient, code: Int, reason: String?, remote: Boolean): Boolean {
            if (token == generation.get() && sessionReady.getAndSet(false) && !closed.get()) {
                logger.warning("QQ 连接断开了（$code），SDK 会自动重连。")
            }
            return true
        }
    }


    /**
     * The SDK only models GROUP_ADD_ROBOT / GROUP_DEL_ROBOT; the message on/off switch that group
     * administrators flip on the bot profile arrives as GROUP_MSG_REJECT / GROUP_MSG_RECEIVE.
     */
    private fun registerMessageSwitchEvents(created: Starter) {
        val events = created.APPLICATION.INSTANCE.contextManager
            .getContextEntity(io.github.kloping.qqbot.network.Events::class.java)
        val register = io.github.kloping.qqbot.network.Events.EventRegister { type, data, _ ->
            messageHandler.onGroupMessageSwitch(data.getString("group_openid").orEmpty(), type == GROUP_MSG_RECEIVE)
            null // handled here; nothing for SDK listeners
        }
        events.register(GROUP_MSG_REJECT, register).register(GROUP_MSG_RECEIVE, register)
    }

    /**
     * `GET /v2/groups/{group_openid}/info` with the SDK's current access-token headers, on the
     * network pool. Completes with null when no QQ session is running. 30 QPM: callers throttle.
     */
    fun fetchGroupInfo(groupOpenId: String): CompletableFuture<QqGroupInfo?> {
        val current = starter.get() ?: return CompletableFuture.completedFuture(null)
        return executors.submit("获取 QQ 群信息") {
            val headers = current.APPLICATION.INSTANCE.contextManager
                .getContextEntity(io.github.kloping.qqbot.Start0::class.java)
                .getHeaders() // refreshes the access token when expired; the raw field does not
            val base = if (current.config.isSandbox) Starter.SANDBOX_NET_MAIN else Starter.NET_MAIN
            val url = java.net.URI(base.trimEnd('/') + "/v2/groups/" +
                java.net.URLEncoder.encode(groupOpenId, Charsets.UTF_8) + "/info").toURL()
            val connection = url.openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                check(status in 200..299) { "QQ group info HTTP $status: ${body.take(200)}" }
                val json = JSON.parseObject(body)
                QqGroupInfo(
                    groupOpenId = json.getString("group_openid") ?: groupOpenId,
                    name = json.getString("group_name")?.trim().orEmpty(),
                    memberCount = json.getInteger("group_member_num")
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Member OpenID remains the routing ID; UnionOpenID is used only in the local identity index. */
    fun fetchMemberIdentity(group: String, member: String): CompletableFuture<QqMemberIdentity> {
        val current = starter.get() ?: return CompletableFuture.failedFuture(IllegalStateException("QQ disconnected"))
        return executors.submit("获取 QQ 成员统一身份") {
            val headers = current.APPLICATION.INSTANCE.contextManager
                .getContextEntity(io.github.kloping.qqbot.Start0::class.java).getHeaders()
            val base = if (current.config.isSandbox) Starter.SANDBOX_NET_MAIN else "https://api.bot.qq.com/"
            val url = java.net.URI(base.trimEnd('/') + "/v2/groups/" +
                java.net.URLEncoder.encode(group, Charsets.UTF_8) + "/members/" +
                java.net.URLEncoder.encode(member, Charsets.UTF_8)).toURL()
            val connection = url.openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readNBytes(65_536).toString(Charsets.UTF_8) }.orEmpty()
                val json = runCatching { JSON.parseObject(body) }.getOrNull()
                parseQqMemberIdentityResponse(status, json, member)
            } finally { connection.disconnect() }
        }
    }

    override fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult> = send("回复 QQ 文本") {
        val payload = V2MsgData()
            .setContent(text)
            .setMsg_id(reference.messageId)
            .setMsg_seq(reference.messageSequence)
        sendPayload(reference.groupOpenId, payload, reference.messageId)
    }



    override fun replyMarkdown(
        reference: MessageReference,
        markdown: String,
        keyboard: Keyboard?
    ): CompletionStage<SendResult> = send("回复 QQ Markdown") {
        sendMarkdownPayload(reference.groupOpenId, markdown, keyboard, reference)
    }

    override fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult> = send("发送 QQ 文本") {
        sendPayload(groupOpenId, V2MsgData().setContent(text))
    }

    override fun sendMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard?
    ): CompletionStage<SendResult> = send("发送 QQ Markdown") {
        sendMarkdownPayload(groupOpenId, markdown, keyboard, null)
    }

    private fun sendMarkdownPayload(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard?,
        reference: MessageReference?
    ): SendResult {
        val markdownEntity = Markdown().setContent(markdown)
        val payload = V2MsgData().setContent(markdown).setMsg_type(2).setMarkdown(markdownEntity)
        reference?.let {
            payload.setMsg_id(it.messageId)
            payload.setMsg_seq(it.messageSequence)
        }
        if (keyboard != null) {
            markdownEntity.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }
        return sendPayload(groupOpenId, payload, reference?.messageId)
    }

    override fun replyImage(
        reference: MessageReference,
        bytes: ByteArray,
        optionalText: String?
    ): CompletionStage<SendResult> = send("回复 QQ 图片") {
        sendImagePayload(reference.groupOpenId, bytes, optionalText, reference)
    }

    override fun sendImage(
        groupOpenId: String,
        bytes: ByteArray,
        optionalText: String?
    ): CompletionStage<SendResult> = send("发送 QQ 图片") {
        sendImagePayload(groupOpenId, bytes, optionalText, null)
    }

    override fun replyNetworkImage(
        event: GroupMessageEvent,
        text: String,
        imageUrl: String
    ): CompletionStage<SendResult> = send("回复 QQ 网络图片") {
        event.sendMessage(MessageChain().text(text.ifBlank { "[图片]" }).image(imageUrl))
        SendResult.success()
    }

    override fun syncCommandPanel() {
        if (!isAccepting()) {
            panelStatus.set(QqPanelSyncStatus("disconnected", "QQ 尚未连接，连接成功后会自动同步", System.currentTimeMillis()))
            return
        }
        panelSyncDirty.set(true)
        panelStatus.set(QqPanelSyncStatus("pending", "正在同步 QQ 指令面板", System.currentTimeMillis()))
        if (!panelSyncQueued.compareAndSet(false, true)) return
        val accepted = executors.execute("同步 QQ 指令面板") {
            try {
                do {
                    panelSyncDirty.set(false)
                    syncCommandPanelNow()
                } while (panelSyncDirty.get() && isAccepting())
                panelStatus.set(QqPanelSyncStatus("success", "各群指令面板已同步", System.currentTimeMillis()))
            } catch (error: Throwable) {
                panelStatus.set(QqPanelSyncStatus("failed", "QQ 同步失败，配置已生效；请稍后点击重新同步", System.currentTimeMillis()))
                logger.error("QQ 指令面板同步失败", error)
            } finally {
                panelSyncQueued.set(false)
                if (panelSyncDirty.get()) syncCommandPanel()
            }
        }
        if (!accepted) {
            panelSyncQueued.set(false)
            panelStatus.set(QqPanelSyncStatus("failed", "后台队列繁忙，请稍后重新同步", System.currentTimeMillis()))
        }
    }

    private fun syncCommandPanelNow() {
        val current = starter.get() ?: return
        val snapshot = commandPanel()
        val groups = snapshot.groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct()
        val definitions = groups.associateWith { group ->
            val commands = (snapshot.groupCommands[group] ?: snapshot.commands)
            .filter { it.name.isNotBlank() && it.description.isNotBlank() }
            .distinctBy { it.name.trim().lowercase() }
            val normalized = commands.mapNotNull { command ->
            QqPanelFields.normalize(command).also { result ->
                if (result == null) {
                    logger.warning("QQ 指令面板跳过了名称超限或说明为空的命令：${command.name.trim()}")
                } else if (result.description != command.description.trim()) {
                    logger.info("QQ 指令面板已缩短命令说明以符合平台宽度限制：${result.name}")
                }
            }
        }
            require(normalized.size <= MAX_PANEL_COMMANDS) { "QQ 指令面板超过 $MAX_PANEL_COMMANDS 项，请在网页中减少面板入口。" }
            PanelDefinition(QqPanelPublisher.PANEL_REMARK, normalized.map {
                PanelItem(it.name.trim(), it.description.trim(), it.administratorOnly)
            })
        }
        val panelApi = current.bot.panelBase ?: error("QQ SDK did not initialize PanelBase")
        QqGroupPanelPublisher(panelApi, panelStateStore).publish(definitions.filterValues { it.items.isNotEmpty() })
        // Syncs run on every connect and group change; only log when the published panels changed.
        if (lastLoggedPanels.getAndSet(definitions.hashCode()) != definitions.hashCode()) {
            logger.info("QQ 指令面板已按群同步，共 ${definitions.size} 个群。")
        }
    }

    private fun send(taskName: String, action: () -> SendResult): CompletionStage<SendResult> {
        if (!isAccepting()) return CompletableFuture.completedFuture(notConnected())
        return executors.submit(taskName) {
            if (!isAccepting()) notConnected() else action()
        }.exceptionally { error ->
            logger.error("$taskName 失败", unwrap(error))
            SendResult.of(SendResult.Status.FAILED, unwrap(error).message)
        }
    }

    private fun sendPayload(
        groupOpenId: String,
        payload: V2MsgData,
        quotedMessageId: String? = payload.msg_id
    ): SendResult {
        val current = starter.get() ?: return notConnected()
        current.bot.groupBaseV2.send(
            groupOpenId,
            serializeGroupPayload(payload, quotedMessageId),
            Channel.SEND_MESSAGE_HEADERS
        )
        return SendResult.success()
    }

    private fun sendImagePayload(
        groupOpenId: String,
        bytes: ByteArray,
        optionalText: String?,
        reference: MessageReference?
    ): SendResult {
        val current = starter.get() ?: return notConnected()
        val uploadBody = JSON.toJSONString(
            mapOf(
                "file_type" to 1,
                "file_data" to Base64.getEncoder().encodeToString(bytes),
                "srv_send_msg" to false
            )
        )
        val uploaded = current.bot.groupBaseV2.sendFile(groupOpenId, uploadBody, Channel.SEND_MESSAGE_HEADERS)
        val fileInfo = uploaded.file_info
        if (fileInfo.isNullOrBlank()) {
            return SendResult.of(SendResult.Status.FAILED, "QQ API did not return file_info")
        }
        val payload = V2MsgData()
            .setContent(optionalText.orEmpty())
            .setMsg_type(7)
            .setMedia(V2MsgData.Media(fileInfo))
        reference?.let {
            payload.setMsg_id(it.messageId)
            payload.setMsg_seq(it.messageSequence)
        }
        // Passive reply quota is retained in msg_id, but image messages must not quote the command.
        return sendPayload(groupOpenId, payload, quotedMessageId = null)
    }

    private fun stopCurrent() {
        sessionReady.set(false)
        starter.getAndSet(null)?.let { current ->
            runCatching { current.shutdown() }.onFailure { logger.error("关闭 QQ 客户端失败", it) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        stopCurrent()
        LoggerImpl.clearLogSink()
    }

    private fun notConnected(): SendResult =
        SendResult.of(SendResult.Status.NOT_CONNECTED, "QQ bot is not connected")

    private fun unwrap(value: Throwable): Throwable {
        var current = value
        while (current.cause != null && current.cause !== current &&
            (current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException)
        ) current = current.cause!!
        return current
    }

    private companion object {
        const val MAX_PANEL_COMMANDS = 20
        const val MAX_PANEL_TARGETS = 20
        const val GROUP_MSG_REJECT = "GROUP_MSG_REJECT"
        const val GROUP_MSG_RECEIVE = "GROUP_MSG_RECEIVE"
        const val READY_TIMEOUT_SECONDS = 30L
        /** Longer than the SDK's own 3-second delayed reconnect after a dropped session. */
        const val SDK_RECONNECT_PAUSE_SECONDS = 5L
    }
}


internal fun serializeGroupPayload(payload: V2MsgData, quotedMessageId: String? = payload.msg_id): String {
    // msg_id grants passive reply quota; message_reference controls the visible quote.
    val body = JSON.parseObject(JSON.toJSONString(payload))
    // Images are standalone: no quoted command, placeholder caption or synthesized mention.
    if (payload.msg_type == 7) {
        body.remove("message_reference")
        if (body.getString("content").isNullOrBlank()) body.remove("content")
        return JSON.toJSONString(body)
    }
    quotedMessageId?.trim()?.takeIf(String::isNotEmpty)?.let { messageId ->
        body["message_reference"] = mapOf("message_id" to messageId)
    }
    return JSON.toJSONString(body)
}

internal fun parseQqMemberIdentityResponse(status: Int, json: com.alibaba.fastjson.JSONObject?, member: String): QqMemberIdentity {
    val code = json?.getInteger("err_code") ?: json?.getInteger("code")
    // Both responses were observed against the official member API. Do not classify other errors as permissions.
    if (code == 11253 || code == 40012010) return QqMemberIdentity(null, permissionDenied = true)
    // Never include response bodies or member identities in exceptions/logs.
    check(status in 200..299 && (code == null || code == 0)) { "QQ member lookup HTTP $status (code=${code ?: "none"})" }
    check(json != null && json.getString("member_openid") == member) { "QQ member response does not match requested member" }
    return QqMemberIdentity(json.getString("union_openid")?.trim()?.takeIf(String::isNotEmpty))
}

fun GroupMessageEvent.toBotMessage(messageSequence: Int): BotMessage {
    val source = rawMessage
    val sourceSender = sender
    return BotMessage(
        source.id.orEmpty(),
        groupOpenId ?: groupId,
        groupId,
        SenderSnapshot(sourceSender?.id, sourceSender?.openid, sourceSender?.username ?: "unknown", sourceSender?.role,
            sourceSender?.meta?.getString("union_openid")),
        source.content.orEmpty(),
        source.toString0(),
        source.timestamp,
        messageSequence,
        mentions.orEmpty().map {
            MentionSnapshot(
                it.id,
                it.openid,
                it.username ?: "unknown",
                it.role,
                it.meta?.getBooleanValue("bot") == true,
                it.meta?.getBooleanValue("is_you") == true
            )
        },
        source.attachments.orEmpty().map {
            AttachmentSnapshot(it.id, it.filename, it.url, it.content_type, it.size, it.width, it.height, it.asr_refer_text)
        }
    )
}

/** Basic group information from `GET /v2/groups/{group_openid}/info`. */
data class QqGroupInfo(val groupOpenId: String, val name: String, val memberCount: Int?)
