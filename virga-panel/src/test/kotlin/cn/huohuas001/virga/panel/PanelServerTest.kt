package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.auth.LoginThrottle
import cn.huohuas001.virga.panel.auth.PasswordHasher
import cn.huohuas001.virga.panel.auth.PasswordStore
import cn.huohuas001.virga.panel.auth.SessionManager
import cn.huohuas001.virga.panel.http.RequestGuard
import cn.huohuas001.virga.panel.http.StaticAssets
import cn.huohuas001.virga.panel.settings.PanelSettingsCatalog
import cn.huohuas001.virga.panel.settings.SettingValue
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelServerTest {
    private data class Response(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json() = JsonParser.parseString(body).asJsonObject
    }

    private val overview = PanelOverview(
        "2.0.0-alpha.2-test.1", "Forge", "1.20.1", "17", 42, 2, 20, true, true, 1, 0,
        QueueStatus(0, 0, 0, 1), emptyList()
    )
    private val saved = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
    @Volatile private var qrEnabled = true
    private val backend = object : PanelBackend {
        override fun overview() = CompletableFuture.completedFuture(overview)
        override fun bot() = CompletableFuture.completedFuture(BotInfo(true, true, BotCredentialRules.mask("102000001"), false, qrEnabled, false))
        override fun saveBotCredentials(appId: String, secret: String?): CompletableFuture<SaveResult> {
            saved += appId to secret
            return CompletableFuture.completedFuture(SaveResult(true, "已保存，完整重启服务器后生效", restartRequired = true))
        }
        override fun qrConnectEnabled() = qrEnabled
        override fun groups() = CompletableFuture.completedFuture(
            listOf(GroupEntry("ABCDEF1234567890", "ABCDEF…7890", true, "主群", null))
        )
        override fun groupsChanged(since: Long): CompletableFuture<Long> = recentGroups.awaitChange(since)
        override fun refreshGroupInfo(): CompletableFuture<SaveResult> {
            metadataRefreshes.incrementAndGet()
            return CompletableFuture.completedFuture(SaveResult(true, "已排队"))
        }
        override fun groupCommands(groupOpenId: String) = CompletableFuture.completedFuture(
            GroupCommandEditor("PLAYER", listOf(GroupCommandOption("帮助", true, true, "帮助", "测试")), PanelSyncInfo("success", "已同步", 1))
        )
        override fun saveGroupCommands(
            groupOpenId: String,
            purpose: String,
            commands: List<GroupCommandOption>,
            confirmHighRisk: Boolean
        ): CompletableFuture<SaveResult> {
            commandUpdates += commands
            return CompletableFuture.completedFuture(SaveResult(true, "已保存"))
        }
        override fun syncGroupCommands() = CompletableFuture.completedFuture(SaveResult(true, "正在同步"))
        override fun customCommands() = CompletableFuture.completedFuture(emptyList<CustomCommandOption>())
        override fun saveCustomCommands(commands: List<CustomCommandOption>): CompletableFuture<SaveResult> {
            customUpdates += commands
            return CompletableFuture.completedFuture(SaveResult(true, "已保存"))
        }
        override fun updateGroup(groupOpenId: String, allowed: Boolean?, note: String?): CompletableFuture<SaveResult> {
            groupUpdates += Triple(groupOpenId, allowed, note)
            return CompletableFuture.completedFuture(SaveResult(true, "已保存"))
        }
        override fun settings() = CompletableFuture.completedFuture(
            PanelSettingsCatalog.DEFINITIONS.map { SettingValue(it.key, it.label, it.group, it.description, true) }
        )
        override fun updateSettings(values: Map<String, Boolean>): CompletableFuture<SaveResult> {
            settingUpdates += values
            return CompletableFuture.completedFuture(SaveResult(true, "已保存"))
        }
        override fun savePanelPort(port: Int): CompletableFuture<SaveResult> {
            savedPorts += port
            return CompletableFuture.completedFuture(SaveResult(true, "已保存"))
        }

        override fun saveServerAddress(address: String): CompletableFuture<SaveResult> =
            CompletableFuture.completedFuture(SaveResult(true, "已保存"))
    }
    private val recentGroups = cn.huohuas001.virga.panel.groups.RecentGroups()
    private val groupUpdates = CopyOnWriteArrayList<Triple<String, Boolean?, String?>>()
    private val settingUpdates = CopyOnWriteArrayList<Map<String, Boolean>>()
    private val savedPorts = CopyOnWriteArrayList<Int>()
    private val commandUpdates = CopyOnWriteArrayList<List<GroupCommandOption>>()
    private val customUpdates = CopyOnWriteArrayList<List<CustomCommandOption>>()
    private val metadataRefreshes = java.util.concurrent.atomic.AtomicInteger()
    private val loggedErrors = CopyOnWriteArrayList<Throwable?>()
    private val log = object : PanelLog {
        override fun info(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, error: Throwable?) { loggedErrors += error }
    }
    private lateinit var password: String
    private lateinit var server: PanelServer
    private var port = 0

    @BeforeTest
    fun start() {
        val store = PasswordStore(Files.createTempDirectory("panel").resolve("c.properties"), PasswordHasher(1_000))
        password = store.initialize()!!
        server = PanelServer(
            backend, store, SessionManager(), LoginThrottle(), StaticAssets(javaClass.classLoader), log,
            cn.huohuas001.virga.panel.qr.QqBotQrConnector(transport = { _, _ -> error("offline test") })
        )
        port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        server.start(port)
    }

    @AfterTest
    fun stop() = server.close()

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        host: String = "127.0.0.1:$port",
        origin: String? = "http://127.0.0.1:$port",
        contentType: String = "application/json"
    ): Response {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
                if (origin != null && method == "POST") append("Origin: $origin\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (method == "POST") append("Content-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val headerEnd = raw.indexOf("\r\n\r\n")
            val lines = raw.substring(0, headerEnd).split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(lines[0].split(' ')[1].toInt(), headers, raw.substring(headerEnd + 4))
        }
    }

    private fun login(): String = request("POST", "/api/login", """{"password":"$password"}""").json()["token"].asString

    @Test fun `custom template API requires auth validates and never executes`() {
        assertEquals(401, request("GET", "/api/custom-commands").status)
        assertEquals(401, request("POST", "/api/custom-commands", """{"commands":[]}""").status)
        val token = login()
        assertEquals(200, request("GET", "/api/custom-commands", token = token).status)
        assertEquals(400, request("POST", "/api/custom-commands", """{"commands":[{"key":"x"}]}""", token).status)
        val row = """{"key":"补给","template":"give {player} bread 1","description":"领一份补给","permission":"ROOT","enabled":true,"panel":false,"requireBinding":true,"cooldownSeconds":5,"showFeedback":false}"""
        assertEquals(400, request("POST", "/api/custom-commands", """{"commands":[$row]}""".replace("\"ROOT\"", "\"OWNER\""), token).status)
        assertEquals(400, request("POST", "/api/custom-commands", """{"commands":[$row]}""".replace("\"cooldownSeconds\":5", "\"cooldownSeconds\":1.5"), token).status)
        assertTrue(customUpdates.isEmpty())
        assertEquals(200, request("POST", "/api/custom-commands", """{"commands":[$row]}""", token).status)
        assertEquals("give {player} bread 1", customUpdates.single().single().template)
    }

    @Test
    fun `the panel only listens on loopback`() {
        val external = InetAddress.getLocalHost()
        if (!external.isLoopbackAddress) {
            val reachable = runCatching { Socket(external, port).close() }.isSuccess
            assertFalse(reachable, "must not accept connections on $external")
        }
    }

    @Test
    fun `api requires login and returns the overview with security headers`() {
        assertEquals(401, request("GET", "/api/overview").status)
        val token = login()
        val response = request("GET", "/api/overview", token = token)
        assertEquals(200, response.status)
        assertEquals("Forge", response.json()["platform"].asString)
        RequestGuard.SECURITY_HEADERS.keys.forEach { assertTrue(it.lowercase() in response.headers, "missing $it") }
        assertEquals(200, request("POST", "/api/logout", "{}", token).status)
        assertEquals(401, request("GET", "/api/overview", token = token).status, "logout revokes the session")
    }

    @Test
    fun `wrong passwords are rejected and throttled`() {
        repeat(3) { assertEquals(401, request("POST", "/api/login", """{"password":"wrong"}""").status) }
        assertEquals(401, request("POST", "/api/login", """{"password":"wrong"}""").status)
        val throttled = request("POST", "/api/login", """{"password":"$password"}""")
        assertEquals(429, throttled.status, "even the right password waits during back-off")
        assertTrue("retry-after" in throttled.headers)
    }

    @Test
    fun `foreign hosts, cross-site origins and non-json posts are refused`() {
        assertEquals(421, request("GET", "/api/overview", host = "evil.example:$port").status)
        assertEquals(403, request("POST", "/api/login", """{"password":"$password"}""", origin = "http://evil.example").status)
        assertEquals(403, request("POST", "/api/login", """{"password":"$password"}""", origin = "null").status)
        assertEquals(415, request("POST", "/api/login", "password=x", contentType = "application/x-www-form-urlencoded").status)
        assertEquals(413, request("POST", "/api/login", "{\"password\":\"" + "a".repeat(70_000) + "\"}").status)
    }

    @Test
    fun `changing the password revokes every session`() {
        val token = login()
        assertEquals(403, request("POST", "/api/password", """{"current":"bad","next":"another-long-password"}""", token).status)
        assertEquals(200, request("POST", "/api/password", """{"current":"$password","next":"another-long-password"}""", token).status)
        assertEquals(401, request("GET", "/api/session", token = token).status)
        assertEquals(200, request("POST", "/api/login", """{"password":"another-long-password"}""").status)
    }

    @Test
    fun `bot credentials are validated, secrets are never echoed and blank keeps the old one`() {
        val token = login()
        val info = request("GET", "/api/bot", token = token)
        assertEquals("102****01", info.json()["maskedAppId"].asString)
        assertFalse("secret" in info.body.lowercase())

        assertEquals(400, request("POST", "/api/bot/credentials", """{"appId":"abc","secret":"whatever-secret"}""", token).status)
        assertEquals(400, request("POST", "/api/bot/credentials", """{"appId":"102000001","secret":"has space x"}""", token).status)
        val ok = request("POST", "/api/bot/credentials", """{"appId":"102000001","secret":"TopSecretValue1"}""", token)
        assertEquals(200, ok.status)
        assertFalse("TopSecretValue1" in ok.body)
        assertTrue(ok.json()["restartRequired"].asBoolean)
        assertEquals(200, request("POST", "/api/bot/credentials", """{"appId":"102000002","secret":""}""", token).status)
        assertEquals(listOf("102000001" to "TopSecretValue1", "102000002" to null), saved.toList())
    }

    @Test
    fun `qr connect honours the switch and reports network failures`() {
        val token = login()
        qrEnabled = false
        assertEquals(403, request("POST", "/api/bot/qr/start", "{}", token).status)
        qrEnabled = true
        val failed = request("POST", "/api/bot/qr/start", "{}", token)
        assertEquals(502, failed.status, "the offline transport cannot create a task")
        val qrStatus = request("GET", "/api/bot/qr/status", token = token)
        assertEquals("IDLE", qrStatus.json()["phase"]?.asString, qrStatus.toString())
        assertEquals(401, request("POST", "/api/bot/qr/start", "{}").status, "login required")
    }

    @Test
    fun `group updates are validated`() {
        val token = login()
        assertEquals("ABCDEF…7890", request("GET", "/api/groups", token = token).body.let { JsonParser.parseString(it).asJsonArray[0].asJsonObject["display"].asString })
        assertEquals(400, request("POST", "/api/groups", """{"id":"../../etc","allowed":true}""", token).status)
        assertEquals(400, request("POST", "/api/groups", """{"id":"ABCDEF1234567890"}""", token).status)
        assertEquals(200, request("POST", "/api/groups", """{"id":"ABCDEF1234567890","allowed":false,"note":"测试群"}""", token).status)
        assertEquals(listOf(Triple("ABCDEF1234567890", false, "测试群")), groupUpdates.toList())
    }

    @Test
    fun `group long poll answers immediately on change without holding threads`() {
        val token = login()
        val initial = request("GET", "/api/groups/changes?since=-1", token = token).json()["version"].asLong
        val started = System.nanoTime()
        val waiting = java.util.concurrent.CompletableFuture.supplyAsync {
            request("GET", "/api/groups/changes?since=$initial", token = token)
        }
        Thread.sleep(300)
        // Both panel worker threads stay free while the poll waits.
        assertEquals(200, request("GET", "/api/session", token = token).status)
        assertEquals(200, request("GET", "/api/session", token = token).status)
        recentGroups.record("NEWGROUP0001", "Lee", "你好")
        val answer = waiting.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(answer.json()["changed"].asBoolean)
        assertTrue(System.nanoTime() - started < java.time.Duration.ofSeconds(3).toNanos(), "answered right after the change")
    }

    @Test
    fun `browser disconnect during long poll is not logged as an internal error`() {
        val token = login()
        val version = request("GET", "/api/groups/changes?since=-1", token = token).json()["version"].asLong
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.setSoLinger(true, 0) // Close with TCP RST rather than waiting for the delayed response.
            socket.getOutputStream().write(("GET /api/groups/changes?since=$version HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n\r\n").toByteArray())
            socket.getOutputStream().flush()
            Thread.sleep(150)
        }
        recentGroups.record("NEWGROUP0001")
        Thread.sleep(150)
        assertTrue(loggedErrors.isEmpty(), "a closed browser is not a backend failure")
        assertEquals(200, request("GET", "/api/session", token = token).status)
    }

    @Test
    fun `group metadata refresh is authenticated and queues a backend lookup`() {
        assertEquals(401, request("POST", "/api/groups/refresh", "{}").status)
        assertEquals(200, request("POST", "/api/groups/refresh", "{}", login()).status)
        assertEquals(1, metadataRefreshes.get())
    }

    @Test
    fun `only catalogued boolean settings can be changed`() {
        val token = login()
        assertEquals(400, request("POST", "/api/settings", """{"values":{"bot.secret":true}}""", token).status)
        assertEquals(400, request("POST", "/api/settings", """{"values":{"features.inventory.enabled":"yes"}}""", token).status)
        assertEquals(200, request("POST", "/api/settings", """{"values":{"features.inventory.enabled":false}}""", token).status)
        assertEquals(listOf(mapOf("features.inventory.enabled" to false)), settingUpdates.toList())
    }

    @Test
    fun `high-risk switches need an explicit confirmation to turn on`() {
        val token = login()
        val key = "features.remote-commands.enabled"
        assertEquals(400, request("POST", "/api/settings", """{"values":{"$key":true}}""", token).status)
        assertTrue(settingUpdates.isEmpty())
        assertEquals(200, request("POST", "/api/settings", """{"values":{"$key":true},"confirmHighRisk":true}""", token).status)
        // Turning it off is never blocked.
        assertEquals(200, request("POST", "/api/settings", """{"values":{"$key":false}}""", token).status)
        assertEquals(listOf(mapOf(key to true), mapOf(key to false)), settingUpdates.toList())
    }

    @Test
    fun `group command editor is authenticated and validates types while preserving submitted order`() {
        assertEquals(401, request("GET", "/api/groups/commands?id=ABCDEF1234567890").status)
        val token = login()
        assertEquals(200, request("GET", "/api/groups/commands?id=ABCDEF1234567890", token = token).status)
        assertEquals(400, request("GET", "/api/groups/commands?id=../../etc", token = token).status)
        val row = """{"command":"在线列表","allowed":true,"panel":false,"label":"在线","description":"测试"}"""
        val draft = """{"id":"ABCDEF1234567890","purpose":"PLAYER","commands":[$row,{"command":"帮助","allowed":true,"panel":true,"label":"帮助","description":"测试"}]}"""
        assertEquals(200, request("POST", "/api/groups/commands", draft, token).status)
        assertEquals(listOf("在线列表", "帮助"), commandUpdates.single().map { it.command })
        assertEquals(400, request("POST", "/api/groups/commands", draft.replace("\"allowed\":true", "\"allowed\":\"true\""), token).status)
        assertEquals(400, request("POST", "/api/groups/commands", draft.replace("PLAYER", "ROOT"), token).status)
        assertEquals(200, request("POST", "/api/groups/commands/sync", "{}", token).status)
    }

    @Test
    fun `port change binds first, refuses busy ports and then moves the listener`() {
        val token = login()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { busy ->
            assertEquals(409, request("POST", "/api/panel/port", """{"port":${busy.localPort}}""", token).status)
        }
        assertEquals(400, request("POST", "/api/panel/port", """{"port":80}""", token).status)
        val newPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val moved = request("POST", "/api/panel/port", """{"port":$newPort}""", token)
        assertEquals(200, moved.status)
        assertEquals(listOf(newPort), savedPorts.toList())
        val oldPort = port
        port = newPort
        assertEquals(200, request("GET", "/api/session", token = token).status, "sessions survive the move")
        Thread.sleep(2000)
        assertFalse(runCatching { Socket(InetAddress.getLoopbackAddress(), oldPort).close() }.isSuccess, "old port closed")
    }

    @Test
    fun `unknown pages fall back to the front end and traversal is blocked`() {
        assertEquals(200, request("GET", "/").status, "the home page always answers")
        assertEquals(200, request("GET", "/login").status)
        assertEquals(404, request("GET", "/../secret.txt").status)
        assertEquals(404, request("GET", "/missing.js").status)
    }
}
