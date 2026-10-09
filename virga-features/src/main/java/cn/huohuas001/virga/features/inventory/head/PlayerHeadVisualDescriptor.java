package cn.huohuas001.virga.features.inventory.head;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Platform-neutral, persistence-safe identity for one custom player-head skin. */
public final class PlayerHeadVisualDescriptor {
    private final String textureHash;
    private final UUID ownerUuid;
    private final String ownerName;

    public PlayerHeadVisualDescriptor(String textureHash) {
        String normalized = Objects.requireNonNull(textureHash, "textureHash").trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{32,128}")) {
            throw new IllegalArgumentException("textureHash must be 32..128 lowercase hexadecimal characters");
        }
        this.textureHash = normalized;
        this.ownerUuid = null;
        this.ownerName = null;
    }

    public PlayerHeadVisualDescriptor(UUID ownerUuid, String ownerName) {
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName").trim();
        if (!this.ownerName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("ownerName must be one Minecraft player name");
        }
        this.textureHash = null;
    }

    public boolean hasTextureHash() { return textureHash != null; }
    public String getTextureHash() { return textureHash; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }

    public String visualKey() {
        return textureHash != null
            ? "player-head:texture:" + textureHash
            : "player-head:owner:" + ownerUuid + ":" + ownerName.toLowerCase(Locale.ROOT);
    }
}
