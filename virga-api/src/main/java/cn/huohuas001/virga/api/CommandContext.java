package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Immutable context passed to an addon command callback. */
public final class CommandContext {
    private final BotMessage message;
    private final CommandInvocation invocation;
    private final Principal principal;
    private final MessageGateway messages;
    private final TaskScheduler scheduler;

    public CommandContext(
        BotMessage message,
        CommandInvocation invocation,
        Principal principal,
        MessageGateway messages,
        TaskScheduler scheduler
    ) {
        this.message = Objects.requireNonNull(message, "message");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public BotMessage getMessage() { return message; }
    public CommandInvocation getInvocation() { return invocation; }
    public Principal getPrincipal() { return principal; }
    public MessageGateway getMessages() { return messages; }
    public TaskScheduler getScheduler() { return scheduler; }

    public CompletionStage<SendResult> replyText(String text) {
        return messages.replyText(message.toReference(), text);
    }

    public CompletionStage<SendResult> replyImage(
        byte[] bytes,
        String mimeType,
        String fileName,
        String optionalText
    ) {
        return messages.replyImage(message.toReference(), bytes, mimeType, fileName, optionalText);
    }
}
