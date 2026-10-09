package cn.huohuas001.virga.server

import cn.huohuas001.virga.core.bot.QClient
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.SendResult
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData
import io.github.kloping.qqbot.impl.ListenerHost
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Core packaging adapter for the callback-button transport already proven by the local
 * binding and inventory features. Business selection rules remain owned by those features.
 */
class QqCallbackButtonBridge(private val logger: Logger) : AutoCloseable {
    private val routes = ConcurrentHashMap<String, (Interaction) -> Result>()
    private val buttonMessagesByData = ConcurrentHashMap<String, ButtonMessage>()
    private val buttonMessagesByOwner = ConcurrentHashMap<String, ButtonMessage>()
    private val recallExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "virga-qq-button-recall").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)

    @Volatile
    private var starter: Starter? = null

    @Volatile
    private var listener: InteractionListener? = null

    /** Attaches the listener after the asynchronously started QQ session becomes available. */
    fun connect(): Boolean = ensureConnected() != null

    fun register(prefix: String, handler: (Interaction) -> Result): AutoCloseable {
        val normalized = prefix.trim().also { require(it.isNotEmpty()) { "Button data prefix must not be blank" } }
        check(!closed.get()) { "QQ callback button bridge is closed" }
        require(routes.keys.none { normalized.startsWith(it) || it.startsWith(normalized) }) {
            "Conflicting QQ callback button prefix: $normalized"
        }
        check(routes.putIfAbsent(normalized, handler) == null) { "Duplicate QQ callback button prefix: $normalized" }
        return AutoCloseable { routes.remove(normalized, handler) }
    }

    fun replySelection(
        reference: MessageReference,
        markdown: String,
        buttons: List<Button>,
        ownerMention: String? = null
    ): CompletionStage<SendResult> {
        if (closed.get()) return failed("QQ callback button bridge is closed")
        if (markdown.isBlank() || buttons.isEmpty()) return failed("Invalid QQ callback button reply")
        return try {
            val current = ensureConnected() ?: return failed("QQ client is not initialized yet")
            val content = ownerMention?.takeIf(String::isNotBlank)?.let { "$it，$markdown" } ?: markdown
            val payload = V2MsgData()
                .setMsg_type(2)
                .setMarkdown(Markdown().setContent(content))
                .setKeyboard(keyboard(buttons))
                .setMsg_id(reference.messageId)
                .setMsg_seq(reference.messageSequence)
            send(current, reference.groupOpenId, payload, buttons)
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Virga QQ 回调按钮发送失败：${concise(error)}")
            failed("QQ custom keyboard request failed: ${concise(error)}")
        }
    }

    /** Sends the next step of a callback workflow without exposing a command or private token. */
    fun sendSelection(
        groupOpenId: String,
        markdown: String,
        buttons: List<Button>
    ): CompletionStage<SendResult> {
        if (closed.get()) return failed("QQ callback button bridge is closed")
        if (groupOpenId.isBlank() || markdown.isBlank() || buttons.isEmpty()) {
            return failed("Invalid QQ callback button message")
        }
        return try {
            val current = ensureConnected() ?: return failed("QQ client is not initialized yet")
            val payload = V2MsgData()
                .setMsg_type(2)
                .setMarkdown(Markdown().setContent(markdown))
                .setKeyboard(keyboard(buttons))
            send(current, groupOpenId, payload, buttons)
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Virga QQ 回调按钮发送失败：${concise(error)}")
            failed("QQ custom keyboard request failed: ${concise(error)}")
        }
    }

    private fun send(
        current: Starter,
        groupOpenId: String,
        payload: V2MsgData,
        buttons: List<Button>
    ): CompletionStage<SendResult> {
        val response = current.bot.groupBaseV2.send(
            groupOpenId,
            payload.toString(),
            Channel.SEND_MESSAGE_HEADERS
        )
        val messageId = response?.id
        if (messageId.isNullOrBlank()) return failed("QQ did not return a message id for the custom keyboard")
        rememberButtonMessage(groupOpenId, messageId, buttons)
        return CompletableFuture.completedFuture(SendResult.success())
    }

    private fun rememberButtonMessage(groupOpenId: String, messageId: String, buttons: List<Button>) {
        val data = buttons.map(Button::data).toSet()
        val owners = buttons.map(Button::allowedUserOpenId).toSet()
        val message = ButtonMessage(groupOpenId, messageId, data, owners)
        data.forEach { buttonMessagesByData[it] = message }
        owners.forEach { owner ->
            buttonMessagesByOwner.put(ownerKey(groupOpenId, owner), message)?.let(::queueRecall)
        }
        recallExecutor.schedule({ queueRecall(message) }, BUTTON_LIFETIME.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun ensureConnected(): Starter? {
        val active = QClient.currentStarterForCoreFeatures() ?: return null
        if (active === starter && listener != null) return active
        synchronized(this) {
            if (closed.get()) return null
            val refreshed = QClient.currentStarterForCoreFeatures() ?: return null
            if (refreshed === starter && listener != null) return refreshed
            detachListener()
            val newListener = InteractionListener(this)
            refreshed.registerListenerHost(newListener)
            starter = refreshed
            listener = newListener
            logger.info("Virga 已接入绑定与背包功能的 QQ 按钮回调。")
            return refreshed
        }
    }

    private fun onInteraction(event: InterActionEvent) {
        val raw = event.interAction ?: return
        if (raw.type != 11 || raw.chat_type != 1) return
        val data = raw.data?.resolved?.button_data ?: return
        val handler = routes.entries.firstOrNull { data.startsWith(it.key) }?.value ?: return
        val result = try {
            handler(Interaction(raw.id, raw.group_openid, raw.group_member_openid, data))
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Virga QQ 按钮回调失败：${concise(error)}", error)
            Result.FAILED
        }
        if (result == Result.NOT_HANDLED) return
        val buttonMessage = buttonMessagesByData[data]
        if (buttonMessage != null && raw.group_member_openid in buttonMessage.allowedUserOpenIds &&
            result != Result.FORBIDDEN
        ) {
            queueRecall(buttonMessage)
        }
        try {
            acknowledge(raw.id, result.platformCode)
        } catch (error: Throwable) {
            logger.log(Level.WARNING, "Virga QQ 按钮确认失败：${concise(error)}")
        }
    }

    private fun acknowledge(interactionId: String, code: Int) {
        val connected = starter ?: throw IOException("QQ client is not connected")
        val start = authenticationContext(connected)
            ?: throw IOException("QQ authentication context is unavailable")
        val base = connected.net?.takeIf(String::isNotBlank)
            ?: throw IOException("QQ API base URL is blank")
        val encodedId = URLEncoder.encode(interactionId, StandardCharsets.UTF_8).replace("+", "%20")
        val target = URI.create(if (base.endsWith('/')) base else "$base/")
            .resolve("interactions/$encodedId").toURL()
        val body = "{\"code\":$code}".toByteArray(StandardCharsets.UTF_8)
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.doOutput = true
            connection.useCaches = false
            start.headers.orEmpty().forEach { (name, value) ->
                if (name != null && value != null) connection.setRequestProperty(name, value)
            }
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("QQ interaction ACK returned HTTP $status${readResponse(connection, status)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun queueRecall(message: ButtonMessage) {
        if (!message.recallQueued.compareAndSet(false, true)) return
        message.buttonData.forEach { buttonMessagesByData.remove(it, message) }
        message.allowedUserOpenIds.forEach { owner ->
            buttonMessagesByOwner.remove(ownerKey(message.groupOpenId, owner), message)
        }
        runCatching { recallExecutor.execute { recall(message) } }
            .onFailure {
                if (!closed.get()) logger.log(Level.WARNING, "提交 QQ 按钮消息撤回失败：${concise(it)}")
            }
    }

    private fun recall(message: ButtonMessage) {
        if (closed.get()) return
        val connected = ensureConnected() ?: return
        try {
            recall(connected, message.groupOpenId, message.messageId)
        } catch (error: Throwable) {
            if (!closed.get()) {
                logger.log(Level.WARNING, "Virga 撤回已结束的 QQ 按钮消息失败：${concise(error)}")
            }
        }
    }

    private fun recall(connected: Starter, groupOpenId: String, messageId: String) {
        val start = authenticationContext(connected)
            ?: throw IOException("QQ authentication context is unavailable")
        val base = connected.net?.takeIf(String::isNotBlank)
            ?: throw IOException("QQ API base URL is blank")
        val target = URI.create(if (base.endsWith('/')) base else "$base/")
            .resolve(recallPath(groupOpenId, messageId)).toURL()
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            start.headers.orEmpty().forEach { (name, value) ->
                if (name != null && value != null) connection.setRequestProperty(name, value)
            }
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("QQ message recall returned HTTP $status${readResponse(connection, status)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun authenticationContext(connected: Starter): Start0? {
        val field = Starter::class.java.getDeclaredField("contextManager").apply { isAccessible = true }
        val context = field.get(connected) ?: return null
        return context.javaClass.getMethod("getContextEntity", Class::class.java)
            .invoke(context, Start0::class.java) as? Start0
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val input = if (status >= 400) connection.errorStream else connection.inputStream
        if (input == null) return ""
        return input.use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(512)
                var remaining = 4_096
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.toString(StandardCharsets.UTF_8).trim().let { if (it.isEmpty()) "" else ": $it" }
            }
        }
    }

    internal fun keyboard(buttons: List<Button>): Keyboard {
        require(buttons.isNotEmpty()) { "A QQ keyboard needs at least one button" }
        val builder = Keyboard.KeyboardBuilder.create()
        buttons.forEach { button ->
            builder.addRow().addButton()
                .setLabel(button.label)
                .setVisitedLabel(button.visitedLabel)
                .setStyle(button.style)
                .setActionType(1)
                .setActionData(button.data)
                .setPermission(Keyboard.Permission(emptyArray(), arrayOf(button.allowedUserOpenId), 0))
                .setUnSupportTips("当前 QQ 客户端版本过低")
                .build().build()
        }
        return builder.build()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        routes.clear()
        buttonMessagesByData.clear()
        buttonMessagesByOwner.clear()
        recallExecutor.shutdownNow()
        synchronized(this) { detachListener() }
    }

    private fun detachListener() {
        val connected = starter
        val connectedListener = listener
        if (connected != null && connectedListener != null) {
            runCatching { connected.config.listenerHosts.remove(connectedListener) }
                .onFailure { logger.log(Level.WARNING, "注销 Virga QQ 按钮监听器失败：${concise(it)}") }
        }
        starter = null
        listener = null
    }

    data class Button(
        val label: String,
        val visitedLabel: String = "已选择",
        val data: String,
        val allowedUserOpenId: String,
        val style: Int = 1
    ) {
        init {
            require(label.isNotBlank() && label.codePointCount(0, label.length) <= 18)
            require(visitedLabel.isNotBlank() && data.isNotBlank() && allowedUserOpenId.isNotBlank())
        }
    }

    data class Interaction(
        val interactionId: String,
        val groupOpenId: String,
        val userOpenId: String,
        val data: String
    )

    enum class Result(val platformCode: Int) {
        SUCCESS(0), EXPIRED(1), FAILED(1), TOO_FREQUENT(2), DUPLICATE(3), FORBIDDEN(4), NOT_HANDLED(-1)
    }

    private data class ButtonMessage(
        val groupOpenId: String,
        val messageId: String,
        val buttonData: Set<String>,
        val allowedUserOpenIds: Set<String>,
        val recallQueued: AtomicBoolean = AtomicBoolean(false)
    )

    private class InteractionListener(private val owner: QqCallbackButtonBridge) : ListenerHost() {
        @ListenerHost.EventReceiver
        fun onInteraction(event: InterActionEvent) = owner.onInteraction(event)
    }

    companion object {
        private val QQ_OPEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{6,128}")
        private val BUTTON_LIFETIME: Duration = Duration.ofSeconds(60)

        private fun ownerKey(groupOpenId: String, userOpenId: String): String = "$groupOpenId\n$userOpenId"

        internal fun recallPath(groupOpenId: String, messageId: String): String =
            "v2/groups/${encodePath(groupOpenId)}/messages/${encodePath(messageId)}"

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun failed(diagnostic: String): CompletionStage<SendResult> = CompletableFuture.completedFuture(
            SendResult.of(SendResult.Status.FAILED, diagnostic)
        )

        private fun concise(error: Throwable): String {
            var current = error
            while (current.cause != null && current.cause !== current) current = current.cause!!
            return current.javaClass.simpleName + (current.message?.let { ": $it" } ?: "")
        }
    }
}
