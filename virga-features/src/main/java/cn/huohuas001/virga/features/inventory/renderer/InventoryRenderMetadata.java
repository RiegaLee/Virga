package cn.huohuas001.virga.features.inventory.renderer;

import java.time.Instant;
import java.util.Objects;

/** Internal composition metadata; inventory contents remain the same immutable snapshot model. */
public final class InventoryRenderMetadata {
    public enum Freshness { REALTIME, OFFLINE_SNAPSHOT }

    private final Freshness freshness;
    private final Instant capturedAt;

    private InventoryRenderMetadata(Freshness freshness, Instant capturedAt) {
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public static InventoryRenderMetadata realtime(Instant capturedAt) {
        return new InventoryRenderMetadata(Freshness.REALTIME, capturedAt);
    }

    public static InventoryRenderMetadata offline(Instant capturedAt) {
        return new InventoryRenderMetadata(Freshness.OFFLINE_SNAPSHOT, capturedAt);
    }

    public Freshness getFreshness() { return freshness; }
    public Instant getCapturedAt() { return capturedAt; }
}
