@file:Suppress("unused", "DEPRECATION")

package cn.huohuas001.virga.core.bot

import cn.huohuas001.virga.core.bot.provider.CommandProvider
import cn.huohuas001.virga.core.bot.provider.ConfigProvider
import cn.huohuas001.virga.core.bot.provider.HExecution
import cn.huohuas001.virga.core.bot.provider.LoggerProvider
import cn.huohuas001.virga.core.bot.provider.MessageProvider
import cn.huohuas001.virga.core.bot.provider.SchedulerProvider
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.concurrent.CompletableFuture

/** Mainline compatibility surface. Runtime ownership lives in the Virga composition root. */
interface VirgaHost : LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider {
    fun getBotAppId(): String
    fun getBotSecret(): String
    fun createCommandExecutor(): HExecution
    fun reloadPluginConfig()
    fun getAuthenticatedQq(groupOpenId: String, openId: String): String? = null

    override fun sendText(text: String) = QClient.sendText(text)
    override fun sendMarkdown(markdownContent: String, keyboard: Keyboard?) =
        QClient.sendMarkdown(markdownContent, keyboard)
    override fun replyText(event: GroupMessageEvent, text: String): Boolean = QClient.replyText(event, text)
    override fun replyMarkdown(event: GroupMessageEvent, markdownContent: String, keyboard: Keyboard?): Boolean =
        QClient.replyMarkdown(event, markdownContent, keyboard)
    override fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean =
        QClient.replyWithImg(event, text, imgUrl)

    fun initializeRuntime() = Unit
    fun shutdownRuntime() = QClient.shutdown()
    fun reloadRuntimeConfig() = QClient.syncGroupPanels()
    fun initializeMarkdownTemplates() = Unit
    fun launchQqClient() = Unit
    fun auditText(text: String): String = filterText(text)
    fun sendCommand(command: String): CompletableFuture<HExecution> = dispatchCommand(command.removePrefix("/"))
    fun getOnlineList(): List<String>
}
