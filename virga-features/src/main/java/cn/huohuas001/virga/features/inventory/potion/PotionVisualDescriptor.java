package cn.huohuas001.virga.features.inventory.potion;

import java.util.Locale;
import java.util.Objects;

/** Immutable platform-neutral potion appearance used by online and persisted snapshots. */
public final class PotionVisualDescriptor {
    public static final int CLIENT_DEFAULT_TINT = 0x385dc6;

    private final String itemTypeKey;
    private final String basePotionKey;
    private final int resolvedTintRgb;
    private final boolean customColor;
    private final boolean glint;

    public PotionVisualDescriptor(
        String itemTypeKey,
        String basePotionKey,
        int resolvedTintRgb,
        boolean customColor,
        boolean glint
    ) {
        this.itemTypeKey = requirePotionItem(itemTypeKey);
        this.basePotionKey = normalizeOptional(basePotionKey);
        if ((resolvedTintRgb & 0xff000000) != 0) {
            throw new IllegalArgumentException("resolvedTintRgb must be a 24-bit RGB value");
        }
        this.resolvedTintRgb = resolvedTintRgb;
        this.customColor = customColor;
        this.glint = glint;
    }

    public static PotionVisualDescriptor defaultFor(String itemTypeKey) {
        return new PotionVisualDescriptor(itemTypeKey, null, CLIENT_DEFAULT_TINT, false, false);
    }

    public static boolean supports(String materialKey) {
        String key = normalize(materialKey);
        return "minecraft:potion".equals(key) ||
            "minecraft:splash_potion".equals(key) ||
            "minecraft:lingering_potion".equals(key) ||
            "minecraft:tipped_arrow".equals(key);
    }

    public String visualKey() {
        return itemTypeKey + "|" + (basePotionKey == null ? "none" : basePotionKey) + "|" +
            String.format(Locale.ROOT, "%06x", resolvedTintRgb) + "|custom=" + customColor + "|glint=" + glint;
    }

    private static String requirePotionItem(String value) {
        String normalized = normalize(Objects.requireNonNull(value, "itemTypeKey"));
        if (!supports(normalized)) throw new IllegalArgumentException("unsupported potion item " + value);
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public String getItemTypeKey() { return itemTypeKey; }
    public String getBasePotionKey() { return basePotionKey; }
    public int getResolvedTintRgb() { return resolvedTintRgb; }
    public boolean hasCustomColor() { return customColor; }
    public boolean hasGlint() { return glint; }
}
