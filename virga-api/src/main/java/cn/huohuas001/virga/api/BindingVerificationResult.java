package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.Optional;

/** Result of consuming a Minecraft-side challenge confirmation. */
public final class BindingVerificationResult {
    public enum Status {
        VERIFIED,
        INVALID_CODE,
        PLAYER_MISMATCH,
        EXPIRED,
        ATTEMPTS_EXHAUSTED,
        CONFLICT,
        UNAVAILABLE
    }

    private final Status status;
    private final PlayerBinding binding;
    private final int remainingAttempts;

    private BindingVerificationResult(Status status, PlayerBinding binding, int remainingAttempts) {
        this.status = Objects.requireNonNull(status, "status");
        this.binding = binding;
        this.remainingAttempts = Math.max(0, remainingAttempts);
    }

    public static BindingVerificationResult verified(PlayerBinding binding) {
        return new BindingVerificationResult(Status.VERIFIED, Objects.requireNonNull(binding, "binding"), 0);
    }

    public static BindingVerificationResult rejected(Status status, int remainingAttempts) {
        if (status == Status.VERIFIED) throw new IllegalArgumentException("Use verified() for success");
        return new BindingVerificationResult(status, null, remainingAttempts);
    }

    public Status getStatus() { return status; }
    public Optional<PlayerBinding> getBinding() { return Optional.ofNullable(binding); }
    public int getRemainingAttempts() { return remainingAttempts; }
}
