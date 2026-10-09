package cn.huohuas001.virga.api;

import java.time.Duration;

/** Platform-neutral scheduler surface. */
public interface TaskScheduler {
    TaskHandle runSync(Runnable task);
    TaskHandle runAsync(Runnable task);
    TaskHandle runLater(Duration delay, Runnable task);
    TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task);
}
