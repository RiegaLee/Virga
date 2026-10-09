package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Immutable, SDK-neutral public view of one Minecraft account binding. */
public final class PlayerBinding {
    private final String bindingId;
    private final int slot;
    private final boolean primary;
    private final String accountKey;
    private final String playerName;
    private final UUID playerUuid;
    private final BindingVerificationState verificationState;
    private final BindingVerificationSource verificationSource;
    private final Instant verifiedAt;
    private final Instant lastSeenAt;

    public PlayerBinding(
        String playerName,
        UUID playerUuid,
        BindingVerificationState verificationState
    ) {
        this(null, 0, true, null, playerName, playerUuid, verificationState, null, null, null);
    }

    public PlayerBinding(
        String playerName,
        UUID playerUuid,
        BindingVerificationState verificationState,
        BindingVerificationSource verificationSource,
        Instant verifiedAt,
        Instant lastSeenAt
    ) {
        this(null, 0, true, null, playerName, playerUuid, verificationState,
            verificationSource, verifiedAt, lastSeenAt);
    }

    /** Creates an API 1.3 multi-account binding snapshot. */
    public PlayerBinding(
        String bindingId,
        int slot,
        boolean primary,
        String accountKey,
        String playerName,
        UUID playerUuid,
        BindingVerificationState verificationState,
        BindingVerificationSource verificationSource,
        Instant verifiedAt,
        Instant lastSeenAt
    ) {
        String normalizedName = Objects.requireNonNull(playerName, "playerName").trim();
        if (normalizedName.isEmpty()) throw new IllegalArgumentException("playerName must not be blank");
        if (slot < 0) throw new IllegalArgumentException("slot must not be negative");
        this.bindingId = normalizeOptional(bindingId);
        this.slot = slot;
        this.primary = primary;
        this.accountKey = normalizeOptional(accountKey);
        this.playerName = normalizedName;
        this.playerUuid = playerUuid;
        this.verificationState = Objects.requireNonNull(verificationState, "verificationState");
        this.verificationSource = verificationSource;
        this.verifiedAt = verifiedAt;
        this.lastSeenAt = lastSeenAt;
    }

    public Optional<String> getBindingId() { return Optional.ofNullable(bindingId); }
    /** Slot zero means that the provider implements the legacy single-account contract. */
    public int getSlot() { return slot; }
    public boolean isPrimary() { return primary; }
    /** Stable login-account key when the provider can expose one without host SDK types. */
    public Optional<String> getAccountKey() { return Optional.ofNullable(accountKey); }
    public String getPlayerName() { return playerName; }
    public String getLastKnownName() { return playerName; }
    public Optional<UUID> getPlayerUuid() { return Optional.ofNullable(playerUuid); }
    public Optional<UUID> getObservedUuid() { return Optional.ofNullable(playerUuid); }
    public BindingVerificationState getVerificationState() { return verificationState; }
    public Optional<BindingVerificationSource> getVerificationSource() {
        return Optional.ofNullable(verificationSource);
    }
    public Optional<Instant> getVerifiedAt() { return Optional.ofNullable(verifiedAt); }
    public Optional<Instant> getLastSeenAt() { return Optional.ofNullable(lastSeenAt); }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
