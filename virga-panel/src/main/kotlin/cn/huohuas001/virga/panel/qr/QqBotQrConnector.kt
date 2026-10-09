package cn.huohuas001.virga.panel.qr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unofficial Kotlin implementation of the QQ bot QR connect flow used by the official
 * `@tencent-connect/qqbot-connector` SDK (whose protocol is not publicly documented).
 * The project owner accepted that it may stop working if Tencent changes the protocol;
 * manual AppID/AppSecret entry always remains available.
 */
class QqBotQrConnector(
    private val transport: QrTransport = JdkQrTransport(),
    private val source: String = "Virga",
    private val clock: Clock = Clock.systemUTC(),
    private val pollInterval: Duration = Duration.ofSeconds(2),
    private val sessionTimeout: Duration = Duration.ofMinutes(2),
    private val random: SecureRandom = SecureRandom(),
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "virga-panel-qr").apply { isDaemon = true }
    }
) : AutoCloseable {
    enum class Phase { IDLE, WAITING, COMPLETED, FAILED, CANCELLED }

    /** [startedAtMillis] is epoch millis: java.time types cannot be serialized by Gson on JDK 17+. */
    data class Status(val phase: Phase, val qrUrl: String?, val message: String, val startedAtMillis: Long?)

    data class Credentials(val appId: String, val appSecret: String, val userOpenId: String?)

    private class Session(val key: String, @Volatile var taskId: String, val startedAt: Instant) {
        @Volatile var future: ScheduledFuture<*>? = null
    }

    @Volatile private var session: Session? = null
    @Volatile private var status = Status(Phase.IDLE, null, "尚未开始扫码", null)
    @Volatile private var onCredentials: ((Credentials) -> Unit)? = null

    fun status(): Status = status

    /** Starts (or restarts) a QR session; [onSuccess] receives the decrypted credentials once. */
    @Synchronized
    fun start(onSuccess: (Credentials) -> Unit): Status {
        cancelInternal(Phase.CANCELLED, "已重新开始扫码")
        val key = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
        val taskId = createTask(key)
        val created = Session(key, taskId, clock.instant())
        session = created
        onCredentials = onSuccess
        status = Status(Phase.WAITING, connectUrl(taskId), "请使用手机 QQ 扫描二维码并确认", created.startedAt.toEpochMilli())
        created.future = executor.scheduleWithFixedDelay(
            { poll(created) }, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS
        )
        return status
    }

    @Synchronized
    fun cancel(): Status {
        cancelInternal(Phase.CANCELLED, "已取消扫码")
        return status
    }

    override fun close() {
        cancel()
        executor.shutdownNow()
    }

    private fun poll(current: Session) {
        if (session !== current) return
        try {
            if (Duration.between(current.startedAt, clock.instant()) > sessionTimeout) {
                finish(current, Phase.FAILED, "扫码超时，请重新开始")
                return
            }
            val data = post(POLL_URL, JsonObject().apply { addProperty("task_id", current.taskId) }, "poll_bind_result")
            when (data.intOrZero("status")) {
                STATUS_COMPLETED -> {
                    val appId = data.stringOrEmpty("bot_appid")
                    val secret = decryptSecret(data.stringOrEmpty("bot_encrypt_secret"), current.key)
                    require(appId.isNotBlank() && secret.isNotBlank()) { "扫码结果缺少 AppID 或 Secret" }
                    val callback = onCredentials
                    finish(current, Phase.COMPLETED, "扫码成功，凭据已保存")
                    callback?.invoke(Credentials(appId, secret, data.stringOrEmpty("user_openid").ifBlank { null }))
                }
                STATUS_EXPIRED -> {
                    // Same as the official SDK: an expired code is replaced by a fresh task.
                    val refreshed = createTask(current.key)
                    current.taskId = refreshed
                    updateIf(current, Phase.WAITING, "二维码已过期，已自动刷新", connectUrl(refreshed))
                }
                else -> Unit
            }
        } catch (error: Exception) {
            // Transient network errors are retried on the next tick, like the official SDK.
            if (error is IllegalArgumentException || error is javax.crypto.AEADBadTagException) {
                finish(current, Phase.FAILED, "扫码结果无效：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun createTask(key: String): String {
        val data = post(CREATE_URL, JsonObject().apply { addProperty("key", key) }, "create_bind_task")
        return data.stringOrEmpty("task_id").ifBlank { throw IllegalStateException("create_bind_task 未返回 task_id") }
    }

    private fun post(url: String, body: JsonObject, name: String): JsonObject {
        val response = JsonParser.parseString(transport.post(url, body.toString())).asJsonObject
        val retcode = response.get("retcode")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (retcode != 0) {
            val message = response.get("msg")?.takeIf { it.isJsonPrimitive }?.asString ?: "$name failed"
            throw IllegalStateException("$name: $message")
        }
        return response.getAsJsonObject("data") ?: JsonObject()
    }

    private fun connectUrl(taskId: String): String =
        "https://q.qq.com/qqbot/openclaw/connect.html?task_id=${encode(taskId)}&source=${encode(source)}&_wv=2"

    @Synchronized
    private fun updateIf(current: Session, phase: Phase, message: String, qrUrl: String? = status.qrUrl) {
        if (session === current) status = Status(phase, qrUrl, message, current.startedAt.toEpochMilli())
    }

    @Synchronized
    private fun finish(current: Session, phase: Phase, message: String) {
        if (session !== current) return
        current.future?.cancel(false)
        session = null
        onCredentials = null
        status = Status(phase, null, message, current.startedAt.toEpochMilli())
    }

    private fun cancelInternal(phase: Phase, message: String) {
        val current = session ?: return
        current.future?.cancel(false)
        session = null
        onCredentials = null
        status = Status(phase, null, message, current.startedAt.toEpochMilli())
    }

    companion object {
        const val CREATE_URL = "https://q.qq.com/lite/create_bind_task"
        const val POLL_URL = "https://q.qq.com/lite/poll_bind_result"
        // 0 (none) and 1 (pending) both mean the task is still waiting for the scan and confirmation;
        // the protocol has no separate "scanned" state.
        private const val STATUS_COMPLETED = 2
        private const val STATUS_EXPIRED = 3

        /** `bot_encrypt_secret`: Base64 of IV(12) | ciphertext | GCM tag(16), AES-256-GCM keyed by the session key. */
        fun decryptSecret(encrypted: String, base64Key: String): String {
            val key = Base64.getDecoder().decode(base64Key)
            val payload = Base64.getDecoder().decode(encrypted)
            require(key.size == 32) { "会话密钥长度无效" }
            require(payload.size > 12 + 16) { "加密的 Secret 长度无效" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, payload, 0, 12))
            return String(cipher.doFinal(payload, 12, payload.size - 12), StandardCharsets.UTF_8)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun JsonObject.intOrZero(name: String): Int =
            get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: 0

        private fun JsonObject.stringOrEmpty(name: String): String =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}

/** HTTPS POST of a JSON body, returning the response text (non-200 is an error). */
fun interface QrTransport {
    fun post(url: String, jsonBody: String): String
}

class JdkQrTransport(private val timeout: Duration = Duration.ofSeconds(10)) : QrTransport {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun post(url: String, jsonBody: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()} from $url" }
        return response.body()
    }
}
