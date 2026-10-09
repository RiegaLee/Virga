package cn.huohuas001.virga.core.command

/** Fail closed: never publish a partly redacted diagnostic or a command echo. */
object CommandFeedback {
    const val HIDDEN = "服务器已经收到了。"
    const val EMPTY = "服务器已经收到了。"
    private val internal = Regex("(?i)(open.?id|union.?id|app.?secret|app.?id|access.?token|password|passwd|登录密码|验证码|secret|bearer|exception|stacktrace|\\.(?:java|kt|class)(?:\\b|:)|(?:io|com|org|net)\\.[a-z0-9_]+\\.|<qqbot|<@|https?://|[a-z]:[\\\\/]|(?:plugins|config|state)[\\\\/]|/(?:home|root|opt|srv|etc)/|[a-f0-9]{32,}|[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}|[a-z0-9_-]{40,})")
    fun safe(raw: String?, sensitive: Collection<String> = emptyList(), command: String = ""): String {
        val text = raw.orEmpty().take(8192).replace(Regex("§[0-9a-fk-or]|\\u001B\\[[0-9;]*[A-Za-z]"), "")
            .filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()
        if (text.isEmpty()) return EMPTY
        if (internal.containsMatchIn(text) || sensitive.any { it.isNotBlank() && text.contains(it, true) } ||
            (command.isNotBlank() && text.contains(command, true))) return HIDDEN
        return text.lines().take(8).joinToString("\n").take(1000)
    }
}
