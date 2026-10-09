package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.qr.QqBotQrConnector
import cn.huohuas001.virga.panel.qr.QrTransport
import com.google.gson.JsonParser
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QqBotQrConnectorTest {
    /** Mirrors the server side: IV(12) | ciphertext | tag(16), AES-256-GCM with the session key. */
    private fun encrypt(secret: String, base64Key: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(secret.toByteArray()))
    }

    private class ScriptedTransport(private val polls: List<(String) -> String>) : QrTransport {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val key = AtomicReference<String>()
        private var pollIndex = 0
        private var taskCounter = 0

        override fun post(url: String, jsonBody: String): String {
            requests += url to jsonBody
            val body = JsonParser.parseString(jsonBody).asJsonObject
            return if (url == QqBotQrConnector.CREATE_URL) {
                key.set(body["key"].asString)
                """{"retcode":0,"data":{"task_id":"task-${++taskCounter}"}}"""
            } else {
                polls[minOf(pollIndex++, polls.lastIndex)](key.get())
            }
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `decrypts the secret with the session key and rejects tampering`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val encrypted = encrypt("S3cr3tValue", key)
        assertEquals("S3cr3tValue", QqBotQrConnector.decryptSecret(encrypted, key))
        val tampered = Base64.getDecoder().decode(encrypted).also { it[20] = (it[20] + 1).toByte() }
        assertFailsWith<javax.crypto.AEADBadTagException> {
            QqBotQrConnector.decryptSecret(Base64.getEncoder().encodeToString(tampered), key)
        }
    }

    @Test
    fun `full flow waits, refreshes an expired code, then delivers credentials once`() {
        val transport = ScriptedTransport(listOf(
            { _ -> """{"retcode":0,"data":{"status":0}}""" },
            { _ -> """{"retcode":0,"data":{"status":3}}""" },
            { _ -> """{"retcode":0,"data":{"status":1}}""" },
            { key -> """{"retcode":0,"data":{"status":2,"bot_appid":"102000001","bot_encrypt_secret":"${encrypt("AppSecretXYZ", key)}","user_openid":"OPENID"}}""" }
        ))
        val connector = QqBotQrConnector(transport, source = "Virga", pollInterval = Duration.ofMillis(10))
        val received = CopyOnWriteArrayList<QqBotQrConnector.Credentials>()
        try {
            val started = connector.start { received += it }
            assertEquals(QqBotQrConnector.Phase.WAITING, started.phase)
            assertTrue(started.qrUrl!!.startsWith("https://q.qq.com/qqbot/openclaw/connect.html?task_id=task-1&source=Virga"))

            awaitUntil { connector.status().phase == QqBotQrConnector.Phase.COMPLETED }
            assertEquals(listOf(QqBotQrConnector.Credentials("102000001", "AppSecretXYZ", "OPENID")), received.toList())
            assertNull(connector.status().qrUrl, "no QR is shown after completion")
            assertTrue(transport.requests.count { it.first == QqBotQrConnector.CREATE_URL } == 2, "expired code was refreshed")
            val polledTasks = transport.requests.filter { it.first == QqBotQrConnector.POLL_URL }.map { it.second }
            assertTrue(polledTasks.last().contains("task-2"), "polling follows the refreshed task")
            Thread.sleep(50)
            assertEquals(1, received.size, "credentials are delivered exactly once")
        } finally {
            connector.close()
        }
    }

    @Test
    fun `cancel stops polling and restart creates a new session`() {
        val transport = ScriptedTransport(listOf({ _ -> """{"retcode":0,"data":{"status":0}}""" }))
        val connector = QqBotQrConnector(transport, pollInterval = Duration.ofMillis(10))
        try {
            connector.start { }
            awaitUntil { transport.requests.size >= 3 }
            assertEquals(QqBotQrConnector.Phase.CANCELLED, connector.cancel().phase)
            val count = transport.requests.size
            Thread.sleep(60)
            assertTrue(transport.requests.size <= count + 1, "polling stopped after cancel")
            assertNotNull(connector.start { }.qrUrl)
        } finally {
            connector.close()
        }
    }

    @Test
    fun `server errors on create are reported and sessions time out`() {
        val failing = QrTransport { _, _ -> """{"retcode":40001,"msg":"denied"}""" }
        val connector = QqBotQrConnector(failing, pollInterval = Duration.ofMillis(10))
        assertFailsWith<IllegalStateException> { connector.start { } }
        connector.close()

        val waiting = ScriptedTransport(listOf({ _ -> """{"retcode":0,"data":{"status":0}}""" }))
        val shortLived = QqBotQrConnector(waiting, pollInterval = Duration.ofMillis(10), sessionTimeout = Duration.ofMillis(50))
        try {
            shortLived.start { }
            awaitUntil { shortLived.status().phase == QqBotQrConnector.Phase.FAILED }
        } finally {
            shortLived.close()
        }
    }
}
