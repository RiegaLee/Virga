package cn.huohuas001.virga.api;

import java.util.Objects;

/** Parsed addon command name and unmodified argument tail. */
public final class CommandInvocation {
    private final String command;
    private final String arguments;

    public CommandInvocation(String command, String arguments) {
        this.command = Objects.requireNonNull(command, "command");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
    }

    public String getCommand() { return command; }
    public String getArguments() { return arguments; }
}
