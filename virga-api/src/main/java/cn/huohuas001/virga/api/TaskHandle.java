package cn.huohuas001.virga.api;

/** Cancellable task owned by a PluginContext. */
public interface TaskHandle extends Registration {
    boolean cancel();
}
