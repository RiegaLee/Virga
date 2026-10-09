package cn.huohuas001.virga.features.inventory.armor;

import java.util.Locale;
import java.util.Objects;

/** Immutable, platform-neutral description shared by item icons, snapshots and the player renderer. */
public final class ArmorVisualDescriptor {
    public enum Slot { HEAD, CHEST, LEGS, FEET }

    private final Slot slot;
    private final String baseMaterialKey;
    private final String equipmentModelKey;
    private final String trimPatternKey;
    private final String trimMaterialKey;
    private final Integer leatherColor;
    private final boolean glint;

    public ArmorVisualDescriptor(
        Slot slot,
        String baseMaterialKey,
        String equipmentModelKey,
        String trimPatternKey,
        String trimMaterialKey,
        Integer leatherColor,
        boolean glint
    ) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.baseMaterialKey = namespaced(baseMaterialKey, "baseMaterialKey");
        this.equipmentModelKey = namespaced(equipmentModelKey, "equipmentModelKey");
        this.trimPatternKey = optionalNamespaced(trimPatternKey, "trimPatternKey");
        this.trimMaterialKey = optionalNamespaced(trimMaterialKey, "trimMaterialKey");
        if ((this.trimPatternKey == null) != (this.trimMaterialKey == null)) {
            throw new IllegalArgumentException("trim pattern and material must either both be present or both be absent");
        }
        if (leatherColor != null && (leatherColor.intValue() < 0 || leatherColor.intValue() > 0xffffff)) {
            throw new IllegalArgumentException("leatherColor must be a 24-bit RGB value");
        }
        this.leatherColor = leatherColor;
        this.glint = glint;
    }

    public Slot getSlot() { return slot; }
    public String getBaseMaterialKey() { return baseMaterialKey; }
    public String getEquipmentModelKey() { return equipmentModelKey; }
    public String getTrimPatternKey() { return trimPatternKey; }
    public String getTrimMaterialKey() { return trimMaterialKey; }
    public Integer getLeatherColor() { return leatherColor; }
    public boolean hasGlint() { return glint; }
    public boolean hasTrim() { return trimPatternKey != null; }

    /** Stable canonical value suitable for content revisions and cache keys. */
    public String visualKey() {
        return slot.name().toLowerCase(Locale.ROOT) + '|' + baseMaterialKey + '|' + equipmentModelKey + '|' +
            value(trimPatternKey) + '|' + value(trimMaterialKey) + '|' +
            (leatherColor == null ? "" : String.format(Locale.ROOT, "%06x", leatherColor.intValue())) + '|' + glint;
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static String optionalNamespaced(String value, String field) {
        return value == null ? null : namespaced(value, field);
    }

    private static String namespaced(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]+:[a-z0-9._/-]+") || normalized.contains("..") ||
            normalized.contains("//") || normalized.endsWith("/")) {
            throw new IllegalArgumentException(field + " must be a safe namespaced key");
        }
        return normalized;
    }
}
