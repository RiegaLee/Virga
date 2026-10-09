package cn.huohuas001.virga.panel.http

import java.net.URI

/**
 * Loopback-only request checks. The panel is reached through an SSH tunnel, so the browser
 * talks to a loopback address on an arbitrary local port; anything else is DNS rebinding or a
 * cross-site request and is rejected.
 */
object RequestGuard {
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /** The Host header must name a loopback host (any port, since tunnels may remap it). */
    fun isLoopbackHost(hostHeader: String?): Boolean {
        val host = hostHeader?.trim()?.lowercase() ?: return false
        if (host.isEmpty() || host.length > 255) return false
        val name = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        val port = host.removePrefix(name).removePrefix(":")
        if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return false
        return name in LOOPBACK_HOSTS
    }

    /**
     * State-changing requests must come from the panel page itself: the Origin (when the
     * browser sends one) has to be http on the same loopback host and port as the Host header.
     */
    fun isSameOrigin(originHeader: String?, hostHeader: String?): Boolean {
        if (!isLoopbackHost(hostHeader)) return false
        val origin = originHeader?.trim() ?: return true // non-browser clients (curl) send none
        if (origin == "null") return false
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        if (uri.scheme != "http" || uri.rawPath?.isNotEmpty() == true) return false
        val authority = uri.rawAuthority?.lowercase() ?: return false
        return authority == hostHeader!!.trim().lowercase()
    }

    const val MAX_BODY_BYTES = 64 * 1024

    val SECURITY_HEADERS: Map<String, String> = linkedMapOf(
        "Content-Security-Policy" to
            "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; " +
            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
        "Cross-Origin-Opener-Policy" to "same-origin",
        "Cross-Origin-Resource-Policy" to "same-origin"
    )
}
