package cn.huohuas001.virga.core.qq

import cn.huohuas001.virga.api.MessageGateway
import cn.huohuas001.virga.api.MessageReference
import cn.huohuas001.virga.api.SendResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface MarkdownMessageGateway {
    fun replyMarkdown(reference: MessageReference, markdown: String): CompletionStage<SendResult>
    fun sendMarkdown(groupOpenId: String, markdown: String): CompletionStage<SendResult>
}

class QqMessageGateway(
    private val transport: () -> QqTransport?,
    private val imageLimitBytes: () -> Int
) : MessageGateway, MarkdownMessageGateway {
    override fun replyText(reference: MessageReference, text: String): CompletionStage<SendResult> {
        if (text.isBlank()) return invalid("Reply text must not be blank")
        return transport()?.replyText(reference, text) ?: notConnected()
    }

    override fun replyMarkdown(reference: MessageReference, markdown: String): CompletionStage<SendResult> {
        if (markdown.isBlank()) return invalid("Reply Markdown must not be blank")
        return transport()?.replyMarkdown(reference, markdown, null) ?: notConnected()
    }

    override fun sendMarkdown(groupOpenId: String, markdown: String): CompletionStage<SendResult> {
        if (groupOpenId.isBlank() || markdown.isBlank()) return invalid("Group and Markdown must not be blank")
        return transport()?.sendMarkdown(groupOpenId, markdown, null) ?: notConnected()
    }

    override fun replyImage(
        reference: MessageReference,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        optionalText: String?
    ): CompletionStage<SendResult> {
        validateImage(bytes, mimeType, fileName)?.let { return invalid(it) }
        return transport()?.replyImage(reference, bytes.copyOf(), optionalText) ?: notConnected()
    }

    override fun sendText(groupOpenId: String, text: String): CompletionStage<SendResult> {
        if (groupOpenId.isBlank() || text.isBlank()) return invalid("Group and text must not be blank")
        return transport()?.sendText(groupOpenId, text) ?: notConnected()
    }

    override fun sendImage(
        groupOpenId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        optionalText: String?
    ): CompletionStage<SendResult> {
        if (groupOpenId.isBlank()) return invalid("Group must not be blank")
        validateImage(bytes, mimeType, fileName)?.let { return invalid(it) }
        return transport()?.sendImage(groupOpenId, bytes.copyOf(), optionalText) ?: notConnected()
    }

    private fun validateImage(bytes: ByteArray, mimeType: String, fileName: String): String? = when {
        bytes.isEmpty() -> "Image bytes must not be empty"
        bytes.size > imageLimitBytes() -> "Image exceeds the host limit"
        !mimeType.lowercase().startsWith("image/") -> "Unsupported image MIME type '$mimeType'"
        fileName.isBlank() -> "Image file name must not be blank"
        else -> null
    }

    private fun invalid(message: String): CompletionStage<SendResult> = CompletableFuture.completedFuture(
        SendResult.of(SendResult.Status.INVALID_REQUEST, message)
    )

    private fun notConnected(): CompletionStage<SendResult> = CompletableFuture.completedFuture(
        SendResult.of(SendResult.Status.NOT_CONNECTED, "QQ bot is not connected")
    )
}
