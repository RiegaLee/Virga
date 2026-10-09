package cn.huohuas001.virga.features.image;

import cn.huohuas001.virga.api.MessageGateway;
import cn.huohuas001.virga.api.MessageReference;
import cn.huohuas001.virga.api.SendResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackImageSenderTest {
    @Test
    void fallsBackToTextWhenImageSendFails() {
        FakeGateway gateway = new FakeGateway(false);
        SendResult result = FallbackImageSender.reply(
            gateway,
            new MessageReference("message", "group", 1),
            new byte[]{1},
            "status.png",
            "文字状态"
        ).toCompletableFuture().join();

        assertTrue(result.isSuccess());
        assertEquals(1, gateway.imageCalls.get());
        assertEquals(1, gateway.textCalls.get());
        assertEquals("文字状态", gateway.lastText);
    }

    @Test
    void doesNotSendTextAfterSuccessfulImage() {
        FakeGateway gateway = new FakeGateway(true);
        FallbackImageSender.reply(
            gateway,
            new MessageReference("message", "group", 1),
            new byte[]{1},
            "status.png",
            "文字状态"
        ).toCompletableFuture().join();

        assertEquals(1, gateway.imageCalls.get());
        assertEquals(0, gateway.textCalls.get());
    }

    @Test
    void fallsBackToTextWhenRenderingFails() {
        FakeGateway gateway = new FakeGateway(true);
        SendResult result = FallbackImageSender.replyRendered(
            gateway,
            new MessageReference("message", "group", 1),
            null,
            new IllegalStateException("forced render failure"),
            "status.png",
            "同一份快照的文字状态",
            () -> true
        ).toCompletableFuture().join();

        assertTrue(result.isSuccess());
        assertEquals(0, gateway.imageCalls.get());
        assertEquals(1, gateway.textCalls.get());
        assertEquals("同一份快照的文字状态", gateway.lastText);
    }

    private static final class FakeGateway implements MessageGateway {
        private final boolean imageSucceeds;
        private final AtomicInteger imageCalls = new AtomicInteger();
        private final AtomicInteger textCalls = new AtomicInteger();
        private String lastText;

        private FakeGateway(boolean imageSucceeds) {
            this.imageSucceeds = imageSucceeds;
        }

        @Override
        public CompletionStage<SendResult> replyText(MessageReference reference, String text) {
            textCalls.incrementAndGet();
            lastText = text;
            return CompletableFuture.completedFuture(SendResult.success());
        }

        @Override
        public CompletionStage<SendResult> replyImage(
            MessageReference reference,
            byte[] bytes,
            String mimeType,
            String fileName,
            String optionalText
        ) {
            imageCalls.incrementAndGet();
            SendResult value = imageSucceeds
                ? SendResult.success()
                : SendResult.of(SendResult.Status.FAILED, "forced failure");
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletionStage<SendResult> sendText(String groupOpenId, String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<SendResult> sendImage(
            String groupOpenId,
            byte[] bytes,
            String mimeType,
            String fileName,
            String optionalText
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
