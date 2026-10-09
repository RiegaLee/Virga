@file:Suppress("unused")

package cn.huohuas001.virga.core.bot.provider

import cn.huohuas001.virga.core.bot.tools.Cancelable
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.io.File
import java.util.concurrent.CompletableFuture

interface LoggerProvider {
    fun log_info(msg: String)
    fun log_warning(msg: String)
    fun log_error(msg: String)
    fun log_debug(msg: String) = log_info(msg)
}

class ChatFormat(
    val fromGame: String,
    val fromGroup: String,
    val postChat: Boolean,
    val startWith: String
)

class PlayerEventFormat(
    val joinEnabled: Boolean,
    val joinFormat: String,
    val quitEnabled: Boolean,
    val quitFormat: String,
    val alwaysForward: Boolean = false
)

/** Compatibility DTO only. Virga does not provide a MOTD feature. */
@Deprecated("Virga does not provide MOTD queries")
class Motd(
    val serverIP: String,
    val serverPort: Int,
    val api: String,
    val text: String,
    val postImg: Boolean,
    val useMarkdown: Boolean
)

class WhiteList(val addCommand: String, val delCommand: String)

class CustomCommandDetail(
    val key: String,
    val command: String,
    val permission: Int,
    val pushMenu: Boolean = true
)

enum class AdminMode(val value: String) {
    QQ("qq"),
    CONFIG("config"),
    BOTH("both");

    companion object {
        fun from(value: String?): AdminMode? = entries.firstOrNull { it.value.equals(value, true) }
    }
}

interface ConfigProvider {
    fun shouldSuppressQqBotConsoleOutput(): Boolean = true
    fun getAuditBaseUrl(): String? = null
    fun getAuditApiKey(): String? = null
    fun getAuditModel(): String? = null
    fun getSensitiveWords(): List<String> = emptyList()
    fun isAuthenticationEnabled(): Boolean = false
    fun getChatFormat(): ChatFormat
    fun getPlayerEventFormat(): PlayerEventFormat = PlayerEventFormat(false, "", false, "", false)
    fun getMotd(): Motd = Motd("", 0, "", "", false, false)
    fun isMotdQueryEnabled(): Boolean = false
    fun getMotdQueryApi(): String = ""
    fun getMotdDefaultImageUrl(): String = ""
    fun getWhiteList(): WhiteList = WhiteList("whitelist add {name}", "whitelist remove {name}")
    fun getConfigFile(): File? = null
    fun getFilterRegexList(): List<String> = emptyList()
    fun filterText(text: String): String = getFilterRegexList().fold(text) { value, pattern ->
        runCatching { Regex(pattern).replace(value, "***") }.getOrDefault(value)
    }
    fun formatGroupMessage(name: String, message: String): String = getChatFormat().fromGroup
        .replace("{name}", name).replace("{nick}", name)
        .replace("{message}", filterText(message)).replace("{msg}", filterText(message))
    fun formatGameMessage(name: String, message: String): String = getChatFormat().fromGame
        .replace("{name}", name).replace("{message}", filterText(message)).replace("{msg}", filterText(message))
    fun formatPlayerJoinMessage(name: String): String = formatPlayerEventMessage(getPlayerEventFormat().joinFormat, name)
    fun formatPlayerQuitMessage(name: String): String = formatPlayerEventMessage(getPlayerEventFormat().quitFormat, name)
    fun formatPlayerEventMessage(format: String, name: String): String = format
        .replace("{name}", name).replace("{player}", name)
        .replace("{server}", getServerName()).replace("{platform}", getPlatform())
    fun getMarkdownFiles(): Map<String, String> = emptyMap()
    fun getMarkdown(key: String): String? = null
    fun getAdminMode(): AdminMode = AdminMode.BOTH
    fun getAdminList(): List<String> = emptyList()
    fun getGroupOpenIdList(): List<String> = emptyList()
    fun getFullAmount(): Boolean = false
    fun getCommandList(): Map<String, Boolean> = emptyMap()
    fun getCommandMenuList(): Map<String, Boolean> = emptyMap()
    fun getBotName(): String
    fun getServerName(): String = getBotName()
    fun getPlatform(): String
    fun getPluginVersion(): String
    fun getCustomCommands(): List<CustomCommandDetail> = emptyList()
}

interface HExecution {
    fun getRawString(): String
    fun execute(command: String): CompletableFuture<HExecution>
}

interface CommandProvider {
    fun dispatchCommand(command: String): CompletableFuture<HExecution>
}

interface SchedulerProvider {
    fun submit(task: Runnable): Cancelable
    fun submitAsync(task: Runnable): Cancelable
    fun submitLater(delay: Long, task: Runnable): Cancelable
    fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable
}

interface MessageProvider {
    fun broadcastMessage(msg: String)
    fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean = false
    fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean = false
    fun sendText(text: String)
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)
    fun replyText(event: GroupMessageEvent, text: String): Boolean
    fun replyMarkdown(event: GroupMessageEvent, markdownContent: String, keyboard: Keyboard? = null): Boolean
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean
}
