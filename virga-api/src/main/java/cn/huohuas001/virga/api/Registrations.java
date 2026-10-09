package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Factories for correctly idempotent registration handles. */
public final class Registrations {
    private Registrations() {
    }

    public static Registration create(Runnable closeAction) {
        Objects.requireNonNull(closeAction, "closeAction");
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean(false);

            @Override
            public boolean isClosed() {
                return closed.get();
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) closeAction.run();
            }
        };
    }
}
