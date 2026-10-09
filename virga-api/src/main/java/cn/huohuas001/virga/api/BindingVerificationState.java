package cn.huohuas001.virga.api;

/** Ownership status attached to a public binding snapshot. */
public enum BindingVerificationState {
    /** The Minecraft account owner completed a trusted verification flow. */
    VERIFIED,
    /** A name-only binding imported from the legacy pre-verification binding store. */
    LEGACY_UNVERIFIED,
    /** A future verification challenge exists but has not completed. */
    PENDING,
    /** The server observed identity metadata inconsistent with the verified snapshot. */
    IDENTITY_CHANGED,
    /** The binding was administratively or explicitly revoked and must not grant access. */
    REVOKED
}
