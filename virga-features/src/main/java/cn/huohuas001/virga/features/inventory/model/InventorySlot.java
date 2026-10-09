package cn.huohuas001.virga.features.inventory.model;

import java.util.Objects;

/** One logical slot. A null item represents an empty slot. */
public final class InventorySlot {
    private final SlotType slotType;
    private final int index;
    private final ItemSnapshot item;

    public InventorySlot(SlotType slotType, int index, ItemSnapshot item) {
        this.slotType = Objects.requireNonNull(slotType, "slotType");
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        this.index = index;
        this.item = item;
    }

    public static InventorySlot empty(SlotType slotType, int index) {
        return new InventorySlot(slotType, index, null);
    }

    public static InventorySlot of(SlotType slotType, int index, ItemSnapshot item) {
        return new InventorySlot(slotType, index, Objects.requireNonNull(item, "item"));
    }

    public SlotType getSlotType() { return slotType; }
    public int getIndex() { return index; }
    public ItemSnapshot getItem() { return item; }
    public boolean isEmpty() { return item == null; }
}
