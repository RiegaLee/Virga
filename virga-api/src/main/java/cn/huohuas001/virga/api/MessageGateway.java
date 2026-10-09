package cn.huohuas001.virga.api;

import java.util.concurrent.CompletionStage;

/** SDK-neutral text and in-memory image sending surface. */
public interface MessageGateway {
    CompletionStage<SendResult> replyText(MessageReference reference, String text);

    CompletionStage<SendResult> replyImage(
        MessageReference reference,
        byte[] bytes,
        String mimeType,
        String fileName,
        String optionalText
    );

    CompletionStage<SendResult> sendText(String groupOpenId, String text);

    CompletionStage<SendResult> sendImage(
        String groupOpenId,
        byte[] bytes,
        String mimeType,
        String fileName,
        String optionalText
    );
}
