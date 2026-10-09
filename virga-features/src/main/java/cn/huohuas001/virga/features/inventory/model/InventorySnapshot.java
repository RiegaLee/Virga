package cn.huohuas001.virga.features.inventory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Complete immutable inventory view with no Minecraft or QQ SDK types. */
public final class InventorySnapshot {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final int schemaVersion;
    private final UUID playerUuid;
    private final String playerName;
    private final Instant capturedAt;
    private final String sourceServer;
    private final String contentRevision;
    private final List<InventorySlot> storage;
    private final List<InventorySlot> hotbar;
    private final List<InventorySlot> armor;
    private final InventorySlot offhand;

    public InventorySnapshot(
        int schemaVersion,
        UUID playerUuid,
        String playerName,
        Instant capturedAt,
        String sourceServer,
        String contentRevision,
        List<InventorySlot> storage,
        List<InventorySlot> hotbar,
        List<InventorySlot> armor,
        InventorySlot offhand
    ) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported InventorySnapshot schema " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.playerUuid = playerUuid;
        this.playerName = requireText(playerName, "playerName");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.sourceServer = requireText(sourceServer, "sourceServer");
        this.contentRevision = requireText(contentRevision, "contentRevision");
        this.storage = checkedGrid(storage, SlotType.STORAGE, 27, "storage");
        this.hotbar = checkedGrid(hotbar, SlotType.HOTBAR, 9, "hotbar");
        this.armor = checkedArmor(armor);
        this.offhand = Objects.requireNonNull(offhand, "offhand");
        if (offhand.getSlotType() != SlotType.OFFHAND || offhand.getIndex() != 0) {
            throw new IllegalArgumentException("offhand must use OFFHAND index 0");
        }
    }

    private static List<InventorySlot> checkedGrid(
        List<InventorySlot> source,
        SlotType expected,
        int size,
        String field
    ) {
        Objects.requireNonNull(source, field);
        if (source.size() != size) throw new IllegalArgumentException(field + " must contain " + size + " slots");
        boolean[] seen = new boolean[size];
        List<InventorySlot> copy = new ArrayList<InventorySlot>(source.size());
        for (InventorySlot slot : source) {
            Objects.requireNonNull(slot, field + " slot");
            int index = slot.getIndex();
            if (slot.getSlotType() != expected || index >= size || seen[index]) {
                throw new IllegalArgumentException(field + " contains an invalid or duplicate slot");
            }
            seen[index] = true;
            copy.add(slot);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<InventorySlot> checkedArmor(List<InventorySlot> source) {
        Objects.requireNonNull(source, "armor");
        if (source.size() != 4) throw new IllegalArgumentException("armor must contain four slots");
        Set<SlotType> expected = EnumSet.of(
            SlotType.ARMOR_HEAD,
            SlotType.ARMOR_CHEST,
            SlotType.ARMOR_LEGS,
            SlotType.ARMOR_FEET
        );
        Set<SlotType> seen = new HashSet<SlotType>();
        List<InventorySlot> copy = new ArrayList<InventorySlot>(source.size());
        for (InventorySlot slot : source) {
            Objects.requireNonNull(slot, "armor slot");
            if (!expected.contains(slot.getSlotType()) || slot.getIndex() != 0 || !seen.add(slot.getSlotType())) {
                throw new IllegalArgumentException("armor contains an invalid or duplicate slot");
            }
            copy.add(slot);
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public Instant getCapturedAt() { return capturedAt; }
    public String getSourceServer() { return sourceServer; }
    public String getContentRevision() { return contentRevision; }
    public List<InventorySlot> getStorage() { return storage; }
    public List<InventorySlot> getHotbar() { return hotbar; }
    public List<InventorySlot> getArmor() { return armor; }
    public InventorySlot getOffhand() { return offhand; }

    public List<InventorySlot> getAllSlots() {
        List<InventorySlot> all = new ArrayList<InventorySlot>(41);
        all.addAll(storage);
        all.addAll(hotbar);
        all.addAll(armor);
        all.add(offhand);
        return Collections.unmodifiableList(all);
    }
}
