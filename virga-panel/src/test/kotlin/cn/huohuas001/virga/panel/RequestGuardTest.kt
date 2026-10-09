package cn.huohuas001.virga.panel

import cn.huohuas001.virga.panel.http.RequestGuard
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestGuardTest {
    @Test
    fun `only loopback host headers are accepted, on any tunnel port`() {
        listOf("127.0.0.1:56789", "localhost:8080", "LOCALHOST", "[::1]:56789", "127.0.0.1").forEach {
            assertTrue(RequestGuard.isLoopbackHost(it), it)
        }
        listOf(null, "", "evil.example", "127.0.0.1.evil.example:56789", "10.0.0.5:56789", "127.0.0.1:99999", "localhost:abc")
            .forEach { assertFalse(RequestGuard.isLoopbackHost(it), it.toString()) }
    }

    @Test
    fun `origin must match the loopback host exactly`() {
        assertTrue(RequestGuard.isSameOrigin("http://127.0.0.1:56789", "127.0.0.1:56789"))
        assertTrue(RequestGuard.isSameOrigin(null, "127.0.0.1:56789"), "non-browser clients send no Origin")
        assertFalse(RequestGuard.isSameOrigin("http://localhost:56789", "127.0.0.1:56789"))
        assertFalse(RequestGuard.isSameOrigin("https://127.0.0.1:56789", "127.0.0.1:56789"))
        assertFalse(RequestGuard.isSameOrigin("http://127.0.0.1:1234", "127.0.0.1:56789"))
        assertFalse(RequestGuard.isSameOrigin("null", "127.0.0.1:56789"))
        assertFalse(RequestGuard.isSameOrigin("http://127.0.0.1:56789", "evil.example"))
    }
}
