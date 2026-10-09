package cn.huohuas001.virga.core.qq

import io.github.kloping.qqbot.utils.LoggerImpl
import io.github.kloping.spt.interfaces.Logger
import cn.huohuas001.virga.core.VirgaLogger

/**
 * Host-owned SDK logging: no raw traffic, per-message transcripts or second log file.
 * The SDK's `waring` method uses level 2 (despite LoggerImpl naming it DEBUG_LEVEL).
 */
internal class QqSdkLogger(private val host: VirgaLogger) : Logger, LoggerImpl.LogSink {
    override fun Log(message: String?, level: Int?) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        when (level) {
            -1 -> host.error(safeDiagnostic(text))
            1 -> if (!isTraffic(text)) host.info(safeDiagnostic(text))
            2 -> host.warning(
                if (text.startsWith("Unknown Pack(")) "QQ SDK 收到未识别的数据包。" else safeDiagnostic(text)
            )
            // Level 0 contains websocket/HTTP bodies, tokens and heartbeat packets.
            // Unknown levels are not promoted to INFO either.
        }
    }

    override fun log(message: String, level: Int) = Log(message, level)

    // SDK initialization may reset its level/path. Neither may re-enable transcripts.
    override fun setLogLevel(level: Int): Int = level
    override fun setOutFile(path: String?) = Unit

    private fun safeDiagnostic(text: String): String {
        if (text.contains("HTTP error fetching URL", ignoreCase = true)) {
            val status = Regex("Status=([0-9]{3})").find(text)?.groupValues?.get(1) ?: "未知"
            return "QQ SDK HTTP 请求失败（HTTP $status），接口地址与响应正文已隐藏。"
        }
        if (Regex("(?i)(authorization|access_token|appsecret|app_secret|secret|password)\\s*[=:]").containsMatchIn(text))
            return "QQ SDK 请求发生异常，敏感诊断内容已隐藏。"
        return text.replace(Regex("https?://[^\\s\\]>)]+", RegexOption.IGNORE_CASE), "<接口地址已隐藏>")
            .replace(Regex("\\b[0-9A-Fa-f]{24,}\\b"), "<身份已隐藏>")
    }

    private fun isTraffic(text: String): Boolean =
        text.startsWith("Bot(") || TRAFFIC_PREFIXES.any { text.startsWith(it, ignoreCase = true) }

    private companion object {
        val TRAFFIC_PREFIXES = listOf(
            "websocket-r:", "wss send:", "webhook-r:", "WebHook服务响应:",
            "ws url:", "Use the ("
        )
    }
}

internal fun installQqSdkLogger(host: VirgaLogger): QqSdkLogger {
    val logger = QqSdkLogger(host)
    // LoggerImpl writes before calling its sink. Disable that file path BEFORE any
    // Starter can initialize; filtering only in the sink would still leak to disk.
    LoggerImpl.INSTANCE.setOutFile(null)
    LoggerImpl.INSTANCE.setLogLevel(1)
    LoggerImpl.setLogSink(logger)
    return logger
}
