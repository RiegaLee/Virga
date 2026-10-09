package cn.huohuas001.virga.api;

/** Owner-scoped registry supplied by a PluginContext. */
public interface CommandRegistry {
    Registration register(CommandSpec spec, CommandHandler handler);
}
