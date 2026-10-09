package cn.huohuas001.virga.api;

import java.time.Instant;
import java.util.Objects;

/** Immutable public snapshot of a short-lived, one-time verification challenge. */
public final class BindingChallenge {
    private final String challengeId;
    private final String code;
    private final String groupOpenId;
    private final String qqOpenId;
    private final String targetPlayerName;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final int remainingAttempts;
    private final BindingChallengeState state;

    public BindingChallenge(
        String challengeId,
        String code,
        String groupOpenId,
        String qqOpenId,
        String targetPlayerName,
        Instant createdAt,
        Instant expiresAt,
        int remainingAttempts,
        BindingChallengeState state
    ) {
        this.challengeId = Objects.requireNonNull(challengeId, "challengeId");
        this.code = Objects.requireNonNull(code, "code");
        this.groupOpenId = Objects.requireNonNull(groupOpenId, "groupOpenId");
        this.qqOpenId = Objects.requireNonNull(qqOpenId, "qqOpenId");
        this.targetPlayerName = Objects.requireNonNull(targetPlayerName, "targetPlayerName");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (remainingAttempts < 0) throw new IllegalArgumentException("remainingAttempts must not be negative");
        this.remainingAttempts = remainingAttempts;
        this.state = Objects.requireNonNull(state, "state");
    }

    public String getChallengeId() { return challengeId; }
    public String getCode() { return code; }
    public String getGroupOpenId() { return groupOpenId; }
    public String getQqOpenId() { return qqOpenId; }
    public String getTargetPlayerName() { return targetPlayerName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getRemainingAttempts() { return remainingAttempts; }
    public BindingChallengeState getState() { return state; }
}
