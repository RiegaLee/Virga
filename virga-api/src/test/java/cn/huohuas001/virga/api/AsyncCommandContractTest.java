package cn.huohuas001.virga.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AsyncCommandContractTest {
    @Test
    void handlerCanCompleteAfterReturning() throws Exception {
        CompletableFuture<CommandResult> future = new CompletableFuture<CommandResult>();
        CommandHandler handler = context -> future;

        CompletionStage<CommandResult> returned = handler.handle(sampleContext());
        assertFalse(returned.toCompletableFuture().isDone());

        future.complete(CommandResult.handled());
        assertEquals(CommandResult.Status.HANDLED, returned.toCompletableFuture().join().getStatus());
    }

    private static CommandContext sampleContext() {
        SenderSnapshot sender = new SenderSnapshot("1", "open-1", "tester", "member");
        BotMessage message = new BotMessage(
            "message-1",
            "group-1",
            "legacy-group-1",
            sender,
            "/hello",
            "/hello",
            null,
            1,
            Collections.<MentionSnapshot>emptyList(),
            Collections.<AttachmentSnapshot>emptyList()
        );
        return new CommandContext(
            message,
            new CommandInvocation("hello", ""),
            new Principal("1", "open-1", "tester", PrincipalRole.MEMBER),
            new NoopMessages(),
            new NoopScheduler()
        );
    }

    private static final class NoopMessages implements MessageGateway {
        @Override
        public CompletionStage<SendResult> replyText(MessageReference reference, String text) {
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
            return CompletableFuture.completedFuture(SendResult.success());
        }

        @Override
        public CompletionStage<SendResult> sendText(String groupOpenId, String text) {
            return CompletableFuture.completedFuture(SendResult.success());
        }

        @Override
        public CompletionStage<SendResult> sendImage(
            String groupOpenId,
            byte[] bytes,
            String mimeType,
            String fileName,
            String optionalText
        ) {
            return CompletableFuture.completedFuture(SendResult.success());
        }
    }

    private static final class NoopScheduler implements TaskScheduler {
        @Override public TaskHandle runSync(Runnable task) { return handle(task); }
        @Override public TaskHandle runAsync(Runnable task) { return handle(task); }
        @Override public TaskHandle runLater(Duration delay, Runnable task) { return handle(task); }
        @Override public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) { return handle(task); }

        private TaskHandle handle(Runnable task) {
            return new TaskHandle() {
                private boolean closed;
                @Override public boolean cancel() { close(); return true; }
                @Override public boolean isClosed() { return closed; }
                @Override public void close() { closed = true; }
            };
        }
    }
}
