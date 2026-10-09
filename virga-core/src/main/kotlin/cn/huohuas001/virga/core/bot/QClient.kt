@file:Suppress("unused")

package cn.huohuas001.virga.core.bot

import cn.huohuas001.virga.core.bot.addon.Addon
import cn.huohuas001.virga.core.bot.addon.AddonManager
import cn.huohuas001.virga.core.bot.events.GroupMessageHandler
import cn.huohuas001.virga.core.bot.events.commands.BaseCommand
import cn.huohuas001.virga.core.bot.provider.BotShared
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.core.qq.QqTransport
import cn.huohuas001.virga.core.qq.QqBotRuntime
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.concurrent.atomic.AtomicReference

/** Mainline and Agent compatible facade over Virga's bounded QQ transport. */
object QClient {
    private val transport = AtomicReference<QqTransport?>()
    private lateinit var groupMessageHandler: GroupMessageHandler

    fun install(client: QqTransport, handler: GroupMessageHandler) {
        transport.getAndSet(client)?.close()
        groupMessageHandler = handler
    }

    fun currentTransport(): QqTransport? = transport.get()

    /** Exposes the active SDK session only to bundled, source-migrated core features. */
    fun currentStarterForCoreFeatures(): Starter? =
        (transport.get() as? QqBotRuntime)?.currentStarterForCoreFeatures()

    fun registerCommand(command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) { "QQ client has not been launched" }
        groupMessageHandler.registerCommand(command)
        syncGroupPanels()
    }

    fun registerCommand(addon: Addon, command: BaseCommand) {
        AddonManager.register(addon)
        registerCommand(command)
        command.registeredCommands().forEach { AddonManager.addCommand(addon.name, it) }
    }

    fun syncGroupPanels() {
        transport.get()?.syncCommandPanel()
    }

    fun sendText(text: String) {
        val groups = runCatching { BotShared.getPlugin().getGroupOpenIdList() }.getOrDefault(emptyList())
        groups.forEach { sendText(it, text) }
    }

    fun sendText(groupOpenId: String, text: String): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || groupOpenId.isBlank() || text.isBlank()) return false
        current.sendText(groupOpenId, text)
        return true
    }

    @JvmOverloads
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null) {
        val groups = runCatching { BotShared.getPlugin().getGroupOpenIdList() }.getOrDefault(emptyList())
        groups.forEach { sendMarkdown(it, markdownContent, keyboard) }
    }

    @JvmOverloads
    fun sendMarkdown(groupOpenId: String, markdownContent: String, keyboard: Keyboard? = null): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || groupOpenId.isBlank() || markdownContent.isBlank()) return false
        current.sendMarkdown(groupOpenId, markdownContent, keyboard)
        return true
    }

    fun replyText(event: GroupMessageEvent, text: String): Boolean = replyText(
        event.groupOpenId ?: event.groupId,
        event.rawMessage.id.orEmpty(),
        event.msgSeq,
        text
    )

    fun replyText(groupOpenId: String, messageId: String, messageSequence: Int, text: String): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || text.isBlank()) return false
        current.replyText(MessageReference(messageId, groupOpenId, messageSequence), text)
        return true
    }

    @JvmOverloads
    fun replyMarkdown(event: GroupMessageEvent, markdownContent: String, keyboard: Keyboard? = null): Boolean =
        replyMarkdown(
            event.groupOpenId ?: event.groupId,
            event.rawMessage.id.orEmpty(),
            event.msgSeq,
            markdownContent,
            keyboard
        )

    @JvmOverloads
    fun replyMarkdown(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || groupOpenId.isBlank() || markdownContent.isBlank()) return false
        current.replyMarkdown(
            MessageReference(messageId, groupOpenId, messageSequence),
            markdownContent,
            keyboard
        )
        return true
    }

    fun replyImage(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        bytes: ByteArray,
        optionalText: String?
    ): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || bytes.isEmpty()) return false
        current.replyImage(MessageReference(messageId, groupOpenId, messageSequence), bytes.copyOf(), optionalText)
        return true
    }

    fun sendImage(groupOpenId: String, bytes: ByteArray, optionalText: String?): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || bytes.isEmpty()) return false
        current.sendImage(groupOpenId, bytes.copyOf(), optionalText)
        return true
    }

    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean {
        val current = transport.get() ?: return false
        if (!current.isAccepting() || imgUrl.isBlank()) return false
        current.replyNetworkImage(event, text, imgUrl)
        return true
    }

    fun broadcastGameMessage(playerName: String, message: String) {
        val plugin = runCatching { BotShared.getPlugin() }.getOrNull() ?: return
        val format = plugin.getChatFormat()
        if (!format.postChat || !message.startsWith(format.startWith)) return
        val content = plugin.formatGameMessage(playerName, message.removePrefix(format.startWith))
        plugin.getGroupOpenIdList().forEach { sendText(it, content) }
    }

    fun broadcastPlayerJoin(playerName: String) {
        val plugin = runCatching { BotShared.getPlugin() }.getOrNull() ?: return
        if (!plugin.getPlayerEventFormat().joinEnabled) return
        plugin.getGroupOpenIdList().forEach { sendText(it, plugin.formatPlayerJoinMessage(playerName)) }
    }

    fun broadcastPlayerQuit(playerName: String) {
        val plugin = runCatching { BotShared.getPlugin() }.getOrNull() ?: return
        if (!plugin.getPlayerEventFormat().quitEnabled) return
        plugin.getGroupOpenIdList().forEach { sendText(it, plugin.formatPlayerQuitMessage(playerName)) }
    }

    fun shutdown() {
        transport.getAndSet(null)?.close()
        AddonManager.clear()
    }
}
