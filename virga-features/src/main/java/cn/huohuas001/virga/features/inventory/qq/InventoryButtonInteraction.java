package cn.huohuas001.virga.features.inventory.qq;

import java.util.Objects;

/** Inventory-local snapshot of a QQ group button interaction. */
public final class InventoryButtonInteraction {
    private final String interactionId;
    private final String groupOpenId;
    private final String userOpenId;
    private final String data;

    public InventoryButtonInteraction(String interactionId, String groupOpenId, String userOpenId, String data) {
        this.interactionId = requireNonBlank(interactionId, "interactionId");
        this.groupOpenId = requireNonBlank(groupOpenId, "groupOpenId");
        this.userOpenId = requireNonBlank(userOpenId, "userOpenId");
        this.data = requireNonBlank(data, "data");
    }

    public String getInteractionId() { return interactionId; }
    public String getGroupOpenId() { return groupOpenId; }
    public String getUserOpenId() { return userOpenId; }
    public String getData() { return data; }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
