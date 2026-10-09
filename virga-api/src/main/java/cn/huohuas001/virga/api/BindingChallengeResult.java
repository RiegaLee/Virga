package cn.huohuas001.virga.api;

import java.util.Objects;
import java.util.Optional;

/** Result of requesting a constrained binding challenge. */
public final class BindingChallengeResult {
    public enum Status {
        CREATED,
        RATE_LIMITED,
        INVALID_REQUEST,
        USER_ALREADY_VERIFIED,
        USER_BOUND_TO_DIFFERENT_PLAYER,
        ACCOUNT_LIMIT_REACHED,
        PLAYER_BOUND_TO_ANOTHER_USER,
        TARGET_CHALLENGE_ACTIVE,
        UNAVAILABLE
    }

    private final Status status;
    private final BindingChallenge challenge;
    private final long retryAfterSeconds;

    private BindingChallengeResult(Status status, BindingChallenge challenge, long retryAfterSeconds) {
        this.status = Objects.requireNonNull(status, "status");
        this.challenge = challenge;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }

    public static BindingChallengeResult created(BindingChallenge challenge) {
        return new BindingChallengeResult(Status.CREATED, Objects.requireNonNull(challenge, "challenge"), 0L);
    }

    public static BindingChallengeResult of(Status status) {
        return new BindingChallengeResult(status, null, 0L);
    }

    public static BindingChallengeResult rateLimited(long retryAfterSeconds) {
        return new BindingChallengeResult(Status.RATE_LIMITED, null, retryAfterSeconds);
    }

    public Status getStatus() { return status; }
    public Optional<BindingChallenge> getChallenge() { return Optional.ofNullable(challenge); }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
