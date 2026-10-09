package cn.huohuas001.virga.api;

import java.util.concurrent.CompletionStage;

/** Asynchronous addon command callback. */
@FunctionalInterface
public interface CommandHandler {
    CompletionStage<CommandResult> handle(CommandContext context) throws Exception;
}
