package cn.huohuas001.virga.api;

/** Idempotent lifecycle handle returned by API registrations. */
public interface Registration extends AutoCloseable {
    boolean isClosed();

    @Override
    void close();
}
