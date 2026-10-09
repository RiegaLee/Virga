package cn.huohuas001.virga.features.inventory.armor;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Four-slot immutable view extracted from an InventorySnapshot. */
public final class ArmorEquipmentSet {
    private final Map<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor> equipment;

    private ArmorEquipmentSet(Map<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor> equipment) {
        this.equipment = new EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor>(equipment);
    }

    public static ArmorEquipmentSet empty() {
        return new ArmorEquipmentSet(new EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor>(
            ArmorVisualDescriptor.Slot.class
        ));
    }

    public static ArmorEquipmentSet of(ArmorVisualDescriptor... descriptors) {
        EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor> result =
            new EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor>(ArmorVisualDescriptor.Slot.class);
        if (descriptors != null) for (ArmorVisualDescriptor descriptor : descriptors) {
            if (descriptor != null && result.put(descriptor.getSlot(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate armor slot " + descriptor.getSlot());
            }
        }
        return new ArmorEquipmentSet(result);
    }

    public static ArmorEquipmentSet from(InventorySnapshot snapshot) {
        EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor> result =
            new EnumMap<ArmorVisualDescriptor.Slot, ArmorVisualDescriptor>(ArmorVisualDescriptor.Slot.class);
        for (InventorySlot slot : snapshot.getArmor()) {
            ItemSnapshot item = slot.getItem();
            ArmorVisualDescriptor armor = item == null ? null : item.getArmorVisual();
            if (armor != null) result.put(armor.getSlot(), armor);
        }
        return new ArmorEquipmentSet(result);
    }

    public ArmorVisualDescriptor get(ArmorVisualDescriptor.Slot slot) { return equipment.get(slot); }
    public boolean isEmpty() { return equipment.isEmpty(); }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ArmorVisualDescriptor.Slot slot : ArmorVisualDescriptor.Slot.values()) {
                ArmorVisualDescriptor descriptor = equipment.get(slot);
                String value = slot.name() + '=' + (descriptor == null ? "" : descriptor.visualKey());
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                result.append(String.format(Locale.ROOT, "%02x", hash[index] & 0xff));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
