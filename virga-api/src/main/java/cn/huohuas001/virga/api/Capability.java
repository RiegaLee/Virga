package cn.huohuas001.virga.api;

/** Optional capabilities implemented by a Virga host. */
public enum Capability {
    COMMANDS,
    TEXT_MESSAGES,
    BYTE_ARRAY_IMAGES,
    SCHEDULER,
    /** Read-only lookup of the host's group-scoped QQ to Minecraft bindings. */
    BINDING_LOOKUP,
    /** Constrained one-time challenge workflow for verified Minecraft bindings. */
    BINDING_VERIFICATION
}
