package cn.huohuas001.virga.api;

/** Lifecycle state of an in-memory one-time binding challenge. */
public enum BindingChallengeState {
    ACTIVE,
    CONSUMED,
    EXPIRED,
    ATTEMPTS_EXHAUSTED,
    SUPERSEDED
}
