package cn.huohuas001.virga.features.image;

import cn.huohuas001.virga.api.MessageGateway;
import cn.huohuas001.virga.api.MessageReference;
import cn.huohuas001.virga.api.SendResult;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Sends an image first and uses the supplied text only when that send fails. */
public final class FallbackImageSender {
    private FallbackImageSender() {
    }

    public static CompletionStage<SendResult> reply(
        MessageGateway gateway,
        MessageReference reference,
        byte[] png,
        String fileName,
        String fallbackText
    ) {
        return reply(gateway, reference, png, fileName, fallbackText, () -> true);
    }

    public static CompletionStage<SendResult> reply(
        MessageGateway gateway,
        MessageReference reference,
        byte[] png,
        String fileName,
        String fallbackText,
        BooleanSupplier fallbackAllowed
    ) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(reference, "reference");
        CompletableFuture<SendResult> result = new CompletableFuture<SendResult>();
        try {
            gateway.replyImage(reference, png, "image/png", fileName, "").whenComplete((imageResult, imageError) -> {
                if (imageError == null && imageResult != null && imageResult.isSuccess()) {
                    result.complete(imageResult);
                    return;
                }
                if (fallbackAllowed.getAsBoolean()) replyText(gateway, reference, fallbackText, result);
                else result.complete(imageResult == null
                    ? SendResult.of(SendResult.Status.FAILED, "image send failed")
                    : imageResult);
            });
        } catch (Throwable imageError) {
            if (fallbackAllowed.getAsBoolean()) replyText(gateway, reference, fallbackText, result);
            else result.completeExceptionally(imageError);
        }
        return result;
    }

    /** Applies the same text fallback when the render itself did not produce an image. */
    public static CompletionStage<SendResult> replyRendered(
        MessageGateway gateway,
        MessageReference reference,
        byte[] png,
        Throwable renderError,
        String fileName,
        String fallbackText,
        BooleanSupplier fallbackAllowed
    ) {
        if (renderError == null && png != null) {
            return reply(gateway, reference, png, fileName, fallbackText, fallbackAllowed);
        }
        if (!fallbackAllowed.getAsBoolean()) {
            return CompletableFuture.completedFuture(
                SendResult.of(SendResult.Status.FAILED, "render failed after request completion")
            );
        }
        CompletableFuture<SendResult> result = new CompletableFuture<SendResult>();
        replyText(gateway, reference, fallbackText, result);
        return result;
    }

    private static void replyText(
        MessageGateway gateway,
        MessageReference reference,
        String fallbackText,
        CompletableFuture<SendResult> result
    ) {
        try {
            gateway.replyText(reference, fallbackText).whenComplete((textResult, textError) -> {
                if (textError != null) result.completeExceptionally(textError);
                else result.complete(textResult);
            });
        } catch (Throwable textError) {
            result.completeExceptionally(textError);
        }
    }
}
