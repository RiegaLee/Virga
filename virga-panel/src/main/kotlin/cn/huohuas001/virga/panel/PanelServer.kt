package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.auth.LoginThrottle
import cn.huohuas001.virga.panel.auth.PasswordStore
import cn.huohuas001.virga.panel.auth.SessionManager
import cn.huohuas001.virga.panel.http.RequestGuard
import cn.huohuas001.virga.panel.http.StaticAssets
import cn.huohuas001.virga.panel.qr.QqBotQrConnector
import cn.huohuas001.virga.panel.groups.GroupIds
import cn.huohuas001.virga.panel.settings.PanelSettingsCatalog
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface PanelLog {
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String, error: Throwable?)
}

/**
 * Loopback-only management panel HTTP server. Every request is checked by [RequestGuard];
 * API calls other than login need a bearer session token. Backend work completes the
 * exchange asynchronously, so HTTP threads never wait on game threads.
 */
class PanelServer(
    private val backend: PanelBackend,
    private val passwords: PasswordStore,
    private val sessions: SessionManager,
    private val throttle: LoginThrottle,
    private val assets: StaticAssets,
    private val log: PanelLog,
    private val qr: QqBotQrConnector,
    private val requestTimeout: Duration = Duration.ofSeconds(10)
) : AutoCloseable {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val threadIds = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "virga-panel-${threadIds.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    @Volatile
    private var server: HttpServer? = null

    val port: Int? get() = server?.address?.port

    @Synchronized
    fun start(port: Int) {
        require(port in MIN_PORT..MAX_PORT) { "面板端口必须在 $MIN_PORT–$MAX_PORT 之间：$port" }
        stopServer()
        server = bind(port)
        log.info("Virga 管理面板已在 http://127.0.0.1:$port/ 启动（只监听本机，可经 SSH 隧道访问）。")
    }

    private fun bind(port: Int): HttpServer {
        // Always IPv4 127.0.0.1: Forge starts the JVM with preferIPv6Addresses=system, where
        // getLoopbackAddress() is ::1 and the documented http://127.0.0.1 address would not answer.
        val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        val created = HttpServer.create(InetSocketAddress(loopback, port), 32)
        created.executor = executor
        created.createContext("/") { exchange -> safely(exchange) { handle(exchange) } }
        created.start()
        return created
    }

    /**
     * Moves the panel to [newPort]: bind first (so an occupied port changes nothing), persist,
     * answer on the old port, then close the old listener shortly afterwards.
     */
    private fun changePort(exchange: HttpExchange, body: JsonObject) {
        val newPort = body.get("port")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
        if (newPort == null || newPort !in MIN_PORT..MAX_PORT) {
            return respondError(exchange, 400, "bad_port", "端口必须是 $MIN_PORT–$MAX_PORT 之间的整数")
        }
        if (newPort == port) return respondError(exchange, 400, "same_port", "面板已经在使用这个端口")
        val candidate = try {
            bind(newPort)
        } catch (error: Exception) {
            return respondError(exchange, 409, "port_in_use", "端口 $newPort 无法使用（可能已被占用）")
        }
        backend.savePanelPort(newPort).orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { result, failure ->
            safely(exchange) {
                if (failure != null || result?.ok != true) {
                    candidate.stop(0)
                    log.error("保存面板端口失败", failure)
                    respondError(exchange, 500, "internal", "保存端口失败，面板继续使用原端口")
                    return@safely
                }
                val previous = synchronized(this) { server.also { server = candidate } }
                log.info("Virga 管理面板已改用端口 $newPort：http://127.0.0.1:$newPort/")
                respondJson(exchange, 200, mapOf("ok" to true, "port" to newPort, "message" to "面板已改用端口 $newPort，请用新地址（及对应的 SSH 隧道）重新打开"))
                // Give this response time to flush before closing the old listener.
                Thread({ Thread.sleep(1500); previous?.stop(0) }, "virga-panel-port-switch").apply { isDaemon = true }.start()
            }
        }
    }

    @Synchronized
    override fun close() {
        stopServer()
        qr.close()
        executor.shutdownNow()
        sessions.revokeAll()
    }

    @Synchronized
    private fun stopServer() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        val host = exchange.requestHeaders.getFirst("Host")
        if (!RequestGuard.isLoopbackHost(host)) {
            respondError(exchange, 421, "bad_host", "只接受本机地址访问")
            return
        }
        val path = exchange.requestURI.rawPath ?: "/"
        val method = exchange.requestMethod.uppercase()
        if (!path.startsWith("/api/")) {
            if (method != "GET" && method != "HEAD") return respondError(exchange, 405, "method", "不支持的请求方法")
            val asset = assets.resolve(path) ?: return respondError(exchange, 404, "not_found", "页面不存在")
            return respond(exchange, 200, asset.contentType, asset.bytes)
        }
        if (method == "POST") {
            if (!RequestGuard.isSameOrigin(exchange.requestHeaders.getFirst("Origin"), host)) {
                return respondError(exchange, 403, "bad_origin", "请求来源不受信任")
            }
            val type = exchange.requestHeaders.getFirst("Content-Type").orEmpty().lowercase()
            if (!type.startsWith("application/json")) return respondError(exchange, 415, "content_type", "请求必须是 JSON")
        } else if (method != "GET") {
            return respondError(exchange, 405, "method", "不支持的请求方法")
        }
        val body = if (method == "POST") readJson(exchange) ?: return else JsonObject()
        if (path == "/api/login" && method == "POST") return login(exchange, body)

        val token = bearer(exchange)
        if (!sessions.validate(token)) return respondError(exchange, 401, "unauthorized", "请先登录")
        when ("$method $path") {
            "GET /api/session" -> respondJson(exchange, 200, mapOf("ok" to true))
            "POST /api/logout" -> {
                sessions.revoke(token)
                respondJson(exchange, 200, mapOf("ok" to true))
            }
            "POST /api/password" -> changePassword(exchange, body)
            "GET /api/overview" -> respondAsync(exchange, backend.overview())
            "GET /api/bot" -> respondAsync(exchange, backend.bot())
            "POST /api/bot/credentials" -> saveCredentials(exchange, body)
            "POST /api/bot/qr/start" -> startQr(exchange)
            "GET /api/bot/qr/status" -> respondJson(exchange, 200, qr.status())
            "POST /api/bot/qr/cancel" -> respondJson(exchange, 200, qr.cancel())
            "GET /api/groups" -> respondAsync(exchange, backend.groups())
            "POST /api/groups/refresh" -> respondAsync(exchange, backend.refreshGroupInfo())
            "GET /api/groups/changes" -> awaitGroupChange(exchange)
            "POST /api/groups" -> updateGroup(exchange, body)
            "GET /api/groups/commands" -> {
                val id = exchange.requestURI.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("id=") }?.substringAfter('=').orEmpty()
                if (!GroupIds.valid(id)) respondError(exchange, 400, "bad_group", "群标识无效")
                else respondAsync(exchange, backend.groupCommands(id))
            }
            "POST /api/groups/commands" -> updateGroupCommands(exchange, body)
            "POST /api/groups/commands/sync" -> respondAsync(exchange, backend.syncGroupCommands())
            "GET /api/groups/members" -> {
                val id = exchange.requestURI.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.startsWith("id=") }?.substringAfter('=').orEmpty()
                if (!GroupIds.valid(id)) respondError(exchange, 400, "bad_group", "群标识无效")
                else respondAsync(exchange, backend.groupMembers(id))
            }
            "POST /api/groups/admins" -> {
                val group = body.string("group").orEmpty()
                val user = body.string("user").orEmpty()
                val administrator = body.get("administrator")?.takeIf { it.isJsonPrimitive }?.asBoolean
                if (!GroupIds.valid(group) || !GroupIds.valid(user) || administrator == null) {
                    respondError(exchange, 400, "bad_request", "群或成员标识无效")
                } else respondAsync(exchange, backend.setGroupAdministrator(group, user, administrator))
            }
            "POST /api/roots" -> {
                val user = body.string("user").orEmpty()
                val root = body.get("root")?.takeIf { it.isJsonPrimitive }?.asBoolean
                if (!GroupIds.valid(user) || root == null) respondError(exchange, 400, "bad_request", "成员标识无效")
                else respondAsync(exchange, backend.setRoot(user, root))
            }
            "GET /api/settings" -> respondAsync(exchange, backend.settings())
            "GET /api/custom-commands" -> respondAsync(exchange, backend.customCommands())
            "POST /api/custom-commands" -> updateCustomCommands(exchange, body)
            "POST /api/settings" -> updateSettings(exchange, body)
            "POST /api/panel/port" -> changePort(exchange, body)
            "POST /api/server-address" -> {
                val address = ServerAddressRule.normalize(body.string("address").orEmpty())
                    ?: return respondError(exchange, 400, "bad_address", "服务器地址不能有空格，最多 ${ServerAddressRule.MAX_LENGTH} 个字符")
                respondAsync(exchange, backend.saveServerAddress(address))
            }
            else -> respondError(exchange, 404, "not_found", "接口不存在")
        }
    }

    /**
     * Long poll for the group page: answers as soon as the group list changes after `since`
     * (or after [LONG_POLL_SECONDS] with changed=false). The exchange waits on a future, so no
     * HTTP thread is held while nothing happens.
     */
    private fun awaitGroupChange(exchange: HttpExchange) {
        val since = exchange.requestURI.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("since=") }?.substringAfter('=')?.toLongOrNull() ?: -1L
        backend.groupsChanged(since)
            .completeOnTimeout(since, LONG_POLL_SECONDS, TimeUnit.SECONDS)
            .whenComplete { version, failure ->
                safely(exchange) {
                    if (failure != null) respondError(exchange, 500, "internal", "服务器内部错误")
                    else respondJson(exchange, 200, mapOf("version" to version, "changed" to (version != since)))
                }
            }
    }

    private fun updateGroup(exchange: HttpExchange, body: JsonObject) {
        val id = body.string("id")?.trim().orEmpty()
        if (!GroupIds.valid(id)) return respondError(exchange, 400, "bad_group", "群标识无效")
        val allowed = body.get("allowed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        val note = body.string("note")
        if (note != null && note.length > 200) return respondError(exchange, 400, "bad_note", "备注过长")
        if (allowed == null && note == null) return respondError(exchange, 400, "empty", "没有需要修改的内容")
        respondAsync(exchange, backend.updateGroup(id, allowed, note))
    }

    private fun updateGroupCommands(exchange: HttpExchange, body: JsonObject) {
        val id = body.string("id").orEmpty()
        val purpose = body.string("purpose").orEmpty()
        if (!GroupIds.valid(id)) return respondError(exchange, 400, "bad_group", "群标识无效")
        if (purpose !in setOf("PLAYER", "MANAGEMENT")) return respondError(exchange, 400, "bad_purpose", "请选择玩家群或管理群")
        val entries = body.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return respondError(exchange, 400, "bad_commands", "缺少指令列表")
        if (entries.size() > 128) return respondError(exchange, 400, "bad_commands", "指令列表过长")
        val commands = ArrayList<GroupCommandOption>()
        for (entry in entries) {
            val row = entry.takeIf { it.isJsonObject }?.asJsonObject
                ?: return respondError(exchange, 400, "bad_command", "指令格式无效")
            val command = row.string("command") ?: return respondError(exchange, 400, "bad_command", "缺少指令名称")
            val label = row.string("label") ?: return respondError(exchange, 400, "bad_command", "缺少展示名称")
            val description = row.string("description") ?: return respondError(exchange, 400, "bad_command", "缺少说明")
            val allowed = row.get("allowed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            val panel = row.get("panel")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            if (allowed == null || panel == null || command.length > 128 || label.length > 128 || description.length > 200) {
                return respondError(exchange, 400, "bad_command", "指令开关或文字格式无效")
            }
            commands += GroupCommandOption(command, allowed, panel, label, description)
        }
        val confirmHighRisk = body.get("confirmHighRisk")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        respondAsync(exchange, backend.saveGroupCommands(id, purpose, commands, confirmHighRisk))
    }

    private fun updateCustomCommands(exchange: HttpExchange, body: JsonObject) {
        val rows = body.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return respondError(exchange, 400, "bad_commands", "缺少指令列表")
        if (rows.size() > 32) return respondError(exchange, 400, "bad_commands", "自定义指令最多 32 条")
        val commands = ArrayList<CustomCommandOption>()
        for (entry in rows) {
            val row = entry.takeIf { it.isJsonObject }?.asJsonObject
                ?: return respondError(exchange, 400, "bad_command", "指令格式无效")
            val key = row.string("key")
            val template = row.string("template")
            val description = row.string("description")
            val permission = row.string("permission")
            val cooldown = row.get("cooldownSeconds")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
            fun flag(name: String) = row.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            val enabled = flag("enabled"); val panel = flag("panel")
            val binding = flag("requireBinding"); val feedback = flag("showFeedback")
            if (key == null || template == null || description == null || permission !in setOf("MEMBER", "ADMIN", "ROOT") ||
                cooldown == null || cooldown !in 1..300 || enabled == null || panel == null || binding == null || feedback == null ||
                key.length > 14 || template.length > 2048 || description.length > 30) {
                return respondError(exchange, 400, "bad_command", "请检查触发词、权限、模板与冷却时间")
            }
            commands += CustomCommandOption(key, template, description, permission!!, enabled, panel, binding, cooldown, feedback)
        }
        respondAsync(exchange, backend.saveCustomCommands(commands))
    }

    private fun updateSettings(exchange: HttpExchange, body: JsonObject) {
        val values = body.getAsJsonObject("values")?.entrySet()
            ?: return respondError(exchange, 400, "bad_settings", "缺少 values")
        val parsed = LinkedHashMap<String, Boolean>()
        for ((key, value) in values) {
            if (!PanelSettingsCatalog.isAllowed(key)) return respondError(exchange, 400, "unknown_setting", "不允许修改的设置：$key")
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
                return respondError(exchange, 400, "bad_value", "设置 $key 必须是开关值")
            }
            parsed[key] = value.asBoolean
        }
        if (parsed.isEmpty()) return respondError(exchange, 400, "empty", "没有需要修改的设置")
        val confirmed = body.get("confirmHighRisk")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        if (PanelSettingsCatalog.riskyActivations(parsed).isNotEmpty() && !confirmed) {
            return respondError(exchange, 400, "high_risk_unconfirmed", "这是高危设置，请在页面上确认后再打开")
        }
        respondAsync(exchange, backend.updateSettings(parsed))
    }

    private fun saveCredentials(exchange: HttpExchange, body: JsonObject) {
        val appId = body.string("appId")?.trim().orEmpty()
        val secret = body.string("secret")?.trim().orEmpty()
        if (!BotCredentialRules.validAppId(appId)) return respondError(exchange, 400, "bad_app_id", "AppID 应为 5–20 位数字")
        if (secret.isNotEmpty() && !BotCredentialRules.validSecret(secret)) {
            return respondError(exchange, 400, "bad_secret", "AppSecret 格式不正确（8–128 个可见字符，不含空格）")
        }
        // An empty secret keeps the stored one, so the page never needs to read it back.
        respondAsync(exchange, backend.saveBotCredentials(appId, secret.ifEmpty { null }))
    }

    private fun startQr(exchange: HttpExchange) {
        if (!backend.qrConnectEnabled()) return respondError(exchange, 403, "qr_disabled", "扫码连接已在配置中关闭，请手工填写")
        val status = try {
            qr.start { credentials ->
                backend.saveBotCredentials(credentials.appId, credentials.appSecret).whenComplete { result, error ->
                    if (error != null || result?.ok != true) log.error("扫码成功但保存 QQ 凭据失败", error)
                    else log.info("已通过扫码保存 QQ 机器人凭据（AppID ${BotCredentialRules.mask(credentials.appId)}）。")
                }
            }
        } catch (error: Exception) {
            log.warning("扫码连接启动失败：${error.message}")
            return respondError(exchange, 502, "qr_unavailable", "暂时无法获取二维码（${error.message ?: "网络错误"}），请稍后重试或手工填写")
        }
        respondJson(exchange, 200, status)
    }

    private fun login(exchange: HttpExchange, body: JsonObject) {
        val source = exchange.remoteAddress?.address?.hostAddress ?: "unknown"
        val wait = throttle.retryAfter(source)
        if (!wait.isZero) {
            exchange.responseHeaders.add("Retry-After", wait.toSeconds().coerceAtLeast(1).toString())
            return respondError(exchange, 429, "too_many_attempts", "尝试次数过多，请 ${wait.toSeconds().coerceAtLeast(1)} 秒后再试")
        }
        val password = body.string("password")?.toCharArray()
        if (password == null || password.isEmpty() || !passwords.verify(password)) {
            throttle.recordFailure(source)
            log.warning("管理面板登录失败（来源 $source）。")
            return respondError(exchange, 401, "wrong_password", "密码不正确")
        }
        password.fill('\u0000')
        throttle.recordSuccess(source)
        respondJson(exchange, 200, mapOf("token" to sessions.issue()))
    }

    private fun changePassword(exchange: HttpExchange, body: JsonObject) {
        val current = body.string("current")?.toCharArray() ?: CharArray(0)
        val next = body.string("next")?.toCharArray() ?: CharArray(0)
        try {
            when (passwords.change(current, next)) {
                PasswordStore.ChangeResult.CHANGED -> {
                    sessions.revokeAll()
                    log.info("管理面板密码已修改，所有登录会话已失效。")
                    respondJson(exchange, 200, mapOf("ok" to true))
                }
                PasswordStore.ChangeResult.WRONG_PASSWORD -> respondError(exchange, 403, "wrong_password", "当前密码不正确")
                PasswordStore.ChangeResult.INVALID_NEW_PASSWORD -> respondError(
                    exchange, 400, "weak_password",
                    "新密码长度需在 ${PasswordStore.MIN_LENGTH}–${PasswordStore.MAX_LENGTH} 个字符之间"
                )
            }
        } finally {
            current.fill('\u0000')
            next.fill('\u0000')
        }
    }

    private fun respondAsync(exchange: HttpExchange, future: CompletableFuture<*>) {
        val done = AtomicBoolean(false)
        future.orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete { value, failure ->
            if (!done.compareAndSet(false, true)) return@whenComplete
            safely(exchange) {
                if (failure == null) {
                    respondJson(exchange, 200, value)
                } else {
                    val cause = (failure as? CompletionException)?.cause ?: failure
                    if (cause is TimeoutException) {
                        respondError(exchange, 504, "timeout", "服务器繁忙，请稍后重试")
                    } else if (cause is IllegalArgumentException) {
                        respondError(exchange, 400, "invalid", cause.message ?: "配置内容无效")
                    } else {
                        log.error("管理面板请求失败：${exchange.requestURI.rawPath}", cause)
                        respondError(exchange, 500, "internal", "服务器内部错误")
                    }
                }
            }
        }
    }

    private fun readJson(exchange: HttpExchange): JsonObject? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > RequestGuard.MAX_BODY_BYTES) {
                    respondError(exchange, 413, "too_large", "请求内容过大")
                    return null
                }
            }
        }
        val text = buffer.toString(Charsets.UTF_8).ifBlank { "{}" }
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed == null || !parsed.isJsonObject) {
            respondError(exchange, 400, "bad_json", "请求格式不正确")
            return null
        }
        return parsed.asJsonObject
    }

    private fun bearer(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()

    private fun respondJson(exchange: HttpExchange, status: Int, value: Any?) =
        respond(exchange, status, "application/json; charset=utf-8", gson.toJson(value).toByteArray(Charsets.UTF_8))

    private fun respondError(exchange: HttpExchange, status: Int, code: String, message: String) =
        respondJson(exchange, status, mapOf("error" to code, "message" to message))

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
        RequestGuard.SECURITY_HEADERS.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        exchange.responseHeaders.set("Content-Type", contentType)
        val head = exchange.requestMethod.equals("HEAD", ignoreCase = true)
        try {
            exchange.sendResponseHeaders(status, if (head) -1 else bytes.size.toLong().takeIf { it > 0 } ?: -1)
            if (!head && bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        } catch (error: IOException) {
            // The browser may leave/refresh while a long poll is pending. Only response I/O is
            // classified this way; backend disk/network exceptions remain visible as real failures.
            throw ClientDisconnected(error)
        } finally {
            exchange.close()
        }
    }

    private fun safely(exchange: HttpExchange, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            if (error is ClientDisconnected) return
            log.error("管理面板处理请求时出错", error)
            runCatching { respondError(exchange, 500, "internal", "服务器内部错误") }
            runCatching { exchange.close() }
            if (error is VirtualMachineError) throw error
        }
    }

    private class ClientDisconnected(cause: IOException) : IOException(cause)

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val LONG_POLL_SECONDS = 25L
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
