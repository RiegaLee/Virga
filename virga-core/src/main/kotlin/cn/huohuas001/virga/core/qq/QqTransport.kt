package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.SendResult
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.util.concurrent.CompletionStage

interface QqTransport : AutoCloseable {
    fun isAccepting(): Boolean
    fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult>
    fun replyMarkdown(reference: MessageReference, markdown: String, keyboard: Keyboard?): CompletionStage<SendResult>
    fun replyImage(reference: MessageReference, bytes: ByteArray, optionalText: String?): CompletionStage<SendResult>
    fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult>
    fun sendMarkdown(groupOpenId: String, markdown: String, keyboard: Keyboard?): CompletionStage<SendResult>
    fun sendImage(groupOpenId: String, bytes: ByteArray, optionalText: String?): CompletionStage<SendResult>
    fun replyNetworkImage(event: GroupMessageEvent, text: String, imageUrl: String): CompletionStage<SendResult>
    fun syncCommandPanel()
}

data class QqPanelCommand(
    val name: String,
    val description: String,
    val administratorOnly: Boolean = false
)

data class QqPanelSnapshot(
    val groupOpenIds: List<String>,
    val commands: List<QqPanelCommand>,
    val groupCommands: Map<String, List<QqPanelCommand>> = emptyMap()
)

data class QqPanelSyncStatus(val state: String = "idle", val message: String = "尚未同步", val updatedAt: Long = 0)

/** QQ 面板字段按显示宽度计数：ASCII 计 1，其余 Unicode 码点计 2。 */
object QqPanelFields {
    const val MAX_NAME_WIDTH = 14
    const val MAX_DESCRIPTION_WIDTH = 30

    fun normalize(command: QqPanelCommand): QqPanelCommand? {
        val name = collapseWhitespace(command.name)
        if (name.isEmpty() || displayWidth(name) > MAX_NAME_WIDTH) return null

        val description = truncateToWidth(
            collapseWhitespace(command.description),
            MAX_DESCRIPTION_WIDTH
        )
        if (description.isEmpty()) return null
        return command.copy(name = name, description = description)
    }

    fun displayWidth(value: String): Int = value.codePoints().map { codePoint ->
        if (codePoint <= 0x7f) 1 else 2
    }.sum()

    fun truncateToWidth(value: String, maximumWidth: Int): String {
        require(maximumWidth >= 0) { "maximumWidth must not be negative" }
        val result = StringBuilder()
        var width = 0
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val codePoint = iterator.nextInt()
            val codePointWidth = if (codePoint <= 0x7f) 1 else 2
            if (width + codePointWidth > maximumWidth) break
            result.appendCodePoint(codePoint)
            width += codePointWidth
        }
        return result.toString()
    }

    private fun collapseWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
