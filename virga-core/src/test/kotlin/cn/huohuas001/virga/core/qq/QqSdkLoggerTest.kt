package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.utils.LoggerImpl
import cn.huohuas001.virga.core.VirgaLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class QqSdkLoggerTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun `normal traffic and per-message info never reach host logs`() {
        val host = RecordingLogger()
        val sdk = QqSdkLogger(host)
        sdk.log("websocket-r: synthetic-message-body")
        sdk.log("wss send: synthetic-authentication-body")
        sdk.log("webhook-r: synthetic-body")
        sdk.info("Bot(test-bot) post(synthetic-body) from GroupMessageEvent")
        sdk.info("Bot(test-bot): synthetic-channel <= synthetic-caption")
        sdk.info("websocket-r: synthetic-body")
        sdk.info("Use the (GET) method through the synthetic interface")
        sdk.Log("synthetic-debug-body", 0)
        assertTrue(host.lines.isEmpty())
    }

    @Test
    fun `connection info warnings and errors remain available`() {
        val host = RecordingLogger()
        val sdk = QqSdkLogger(host)
        sdk.info("wss opened")
        sdk.info("Ready!")
        sdk.waring("op 7 Reconnect")
        sdk.error("synthetic request failed\n\tat synthetic.Frame.run(Frame.java:1)")
        assertEquals(listOf(
            "info:wss opened", "info:Ready!", "warning:op 7 Reconnect",
            "error:synthetic request failed\n\tat synthetic.Frame.run(Frame.java:1)"
        ), host.lines)
    }

    @Test
    fun `unrecognized pack warning does not dump message body`() {
        val host = RecordingLogger()
        QqSdkLogger(host).waring("Unknown Pack(synthetic-private-body)")
        assertEquals(listOf("warning:QQ SDK 收到未识别的数据包。"), host.lines)
        assertFalse(host.lines.any { "synthetic-private-body" in it })
    }

    @Test
    fun `sdk level and file resets cannot restore file transcripts`() {
        val host = RecordingLogger()
        val sdk = QqSdkLogger(host)
        val file = directory.resolve("sdk.log")
        sdk.setOutFile(file.toString())
        sdk.setLogLevel(0)
        repeat(10_000) { sdk.log("websocket-r: synthetic-traffic-$it") }
        sdk.info("Bot(test-bot) post(synthetic-message)")
        sdk.info("Ready!")
        assertFalse(Files.exists(file))
        assertEquals(listOf("info:Ready!"), host.lines)
    }

    @Test
    fun `fallback logger file is disabled before sink registration`() {
        val host = RecordingLogger()
        val previousPath = LoggerImpl.INSTANCE.logFileDir
        try {
            val installed = installQqSdkLogger(host)
            assertNull(LoggerImpl.INSTANCE.logFileDir)
            installed.setOutFile(directory.resolve("ignored.log").toString())
            LoggerImpl.INSTANCE.log("websocket-r: synthetic-private-body")
            LoggerImpl.INSTANCE.info("Bot(test-bot) post(synthetic-private-body)")
            LoggerImpl.INSTANCE.waring("op 7 Reconnect")
            LoggerImpl.INSTANCE.error("synthetic failure")
            assertEquals(listOf("warning:op 7 Reconnect", "error:synthetic failure"), host.lines)
            Files.list(directory).use { assertEquals(0L, it.count()) }
        } finally {
            LoggerImpl.clearLogSink()
            LoggerImpl.INSTANCE.setOutFile(previousPath)
        }
    }

    @Test
    fun `empty and unknown levels do not become info`() {
        val host = RecordingLogger()
        val sdk = QqSdkLogger(host)
        sdk.Log(null, 1)
        sdk.Log("  ", -1)
        sdk.Log("synthetic-body", 99)
        assertTrue(host.lines.isEmpty())
    }

    @Test
    fun `HTTP and SDK diagnostics never expose request addresses identities or authentication values`() {
        val host = RecordingLogger()
        val sdk = QqSdkLogger(host)
        val fakeIdentity = "A".repeat(32)
        sdk.error("HTTP error fetching URL. Status=400, URL=[https://api.example.com/v2/users/$fakeIdentity/messages]")
        sdk.waring("request failed https://api.example.com/users/$fakeIdentity")
        sdk.error("RequestException: user=$fakeIdentity")
        sdk.error("Authorization=Bearer synthetic-private-token")
        assertTrue(host.lines.first().contains("HTTP 400"))
        assertFalse(host.lines.any { it.contains(fakeIdentity) || it.contains("https://") || it.contains("synthetic-private-token") })
    }

    private class RecordingLogger : VirgaLogger {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += "info:$message" }
        override fun warning(message: String) { lines += "warning:$message" }
        override fun error(message: String, error: Throwable?) { lines += "error:$message" }
    }
}
