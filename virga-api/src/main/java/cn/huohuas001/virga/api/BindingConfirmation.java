package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.UUID;

/** Evidence supplied by the currently executing Minecraft player. */
public final class BindingConfirmation {
    private final String code;
    private final String observedPlayerName;
    private final UUID observedUuid;

    public BindingConfirmation(String code, String observedPlayerName, UUID observedUuid) {
        this.code = requireText(code, "code");
        this.observedPlayerName = requireText(observedPlayerName, "observedPlayerName");
        this.observedUuid = Objects.requireNonNull(observedUuid, "observedUuid");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public String getCode() { return code; }
    public String getObservedPlayerName() { return observedPlayerName; }
    public UUID getObservedUuid() { return observedUuid; }
}
