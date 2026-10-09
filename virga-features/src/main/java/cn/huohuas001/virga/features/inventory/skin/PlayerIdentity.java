package cn.huohuas001.virga.features.inventory.skin;

import java.util.Objects;
import java.util.UUID;

/** Platform-neutral identity used only for locating the player's current visual skin. */
public final class PlayerIdentity {
    private final UUID uuid;
    private final String name;

    public PlayerIdentity(UUID uuid, String name) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = Objects.requireNonNull(name, "name").trim();
        if (!this.name.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("name must be one Minecraft player name");
        }
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
}
