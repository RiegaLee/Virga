package cn.huohuas001.virga.api;

/** Logger namespaced to the current addon owner. */
public interface PluginLogger {
    void info(String message);
    void warning(String message);
    void error(String message, Throwable error);
}
