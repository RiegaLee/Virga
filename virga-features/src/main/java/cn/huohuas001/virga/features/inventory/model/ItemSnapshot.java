package cn.huohuas001.virga.features.inventory.model;

import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
import cn.huohuas001.virga.features.inventory.head.PlayerHeadVisualDescriptor;
import cn.huohuas001.virga.features.inventory.potion.PotionVisualDescriptor;
import java.util.Objects;

/** Immutable, platform-neutral item data safe for renderer threads. */
public final class ItemSnapshot {
    private final String materialKey;
    private final int amount;
    private final int damage;
    private final int maxDamage;
    private final String displayName;
    private final Integer customModelData;
    private final boolean enchantmentGlint;
    private final String textureHint;
    private final ArmorVisualDescriptor armorVisual;
    private final PotionVisualDescriptor potionVisual;
    private final PlayerHeadVisualDescriptor playerHeadVisual;

    public ItemSnapshot(
        String materialKey,
        int amount,
        int damage,
        int maxDamage,
        String displayName,
        Integer customModelData,
        boolean enchantmentGlint,
        String textureHint
    ) {
        this(
            materialKey, amount, damage, maxDamage, displayName, customModelData,
            enchantmentGlint, textureHint, null, null, null
        );
    }

    public ItemSnapshot(
        String materialKey,
        int amount,
        int damage,
        int maxDamage,
        String displayName,
        Integer customModelData,
        boolean enchantmentGlint,
        String textureHint,
        ArmorVisualDescriptor armorVisual
    ) {
        this(
            materialKey, amount, damage, maxDamage, displayName, customModelData,
            enchantmentGlint, textureHint, armorVisual, null, null
        );
    }

    public ItemSnapshot(
        String materialKey,
        int amount,
        int damage,
        int maxDamage,
        String displayName,
        Integer customModelData,
        boolean enchantmentGlint,
        String textureHint,
        ArmorVisualDescriptor armorVisual,
        PotionVisualDescriptor potionVisual
    ) {
        this(
            materialKey, amount, damage, maxDamage, displayName, customModelData,
            enchantmentGlint, textureHint, armorVisual, potionVisual, null
        );
    }

    public ItemSnapshot(
        String materialKey,
        int amount,
        int damage,
        int maxDamage,
        String displayName,
        Integer customModelData,
        boolean enchantmentGlint,
        String textureHint,
        ArmorVisualDescriptor armorVisual,
        PotionVisualDescriptor potionVisual,
        PlayerHeadVisualDescriptor playerHeadVisual
    ) {
        this.materialKey = requireText(materialKey, "materialKey");
        if (amount < 1) throw new IllegalArgumentException("amount must be at least 1");
        if (damage < 0) throw new IllegalArgumentException("damage must not be negative");
        if (maxDamage < 0 || (maxDamage == 0 && damage != 0) || damage > maxDamage) {
            throw new IllegalArgumentException("damage must be within maxDamage");
        }
        this.amount = amount;
        this.damage = damage;
        this.maxDamage = maxDamage;
        this.displayName = normalizeOptional(displayName);
        this.customModelData = customModelData;
        this.enchantmentGlint = enchantmentGlint;
        this.textureHint = normalizeOptional(textureHint);
        if (armorVisual != null && !this.materialKey.equals(armorVisual.getBaseMaterialKey())) {
            throw new IllegalArgumentException("armorVisual base material must match materialKey");
        }
        this.armorVisual = armorVisual;
        if (potionVisual != null && !this.materialKey.equals(potionVisual.getItemTypeKey())) {
            throw new IllegalArgumentException("potionVisual item type must match materialKey");
        }
        this.potionVisual = potionVisual;
        if (playerHeadVisual != null && !"minecraft:player_head".equals(this.materialKey)) {
            throw new IllegalArgumentException("playerHeadVisual requires minecraft:player_head");
        }
        this.playerHeadVisual = playerHeadVisual;
    }

    public static ItemSnapshot basic(String materialKey, int amount) {
        return new ItemSnapshot(materialKey, amount, 0, 0, null, null, false, null);
    }

    public static ItemSnapshot durable(String materialKey, int damage, int maxDamage) {
        return new ItemSnapshot(materialKey, 1, damage, maxDamage, null, null, false, null);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public String getMaterialKey() { return materialKey; }
    public int getAmount() { return amount; }
    public int getDamage() { return damage; }
    public int getMaxDamage() { return maxDamage; }
    public String getDisplayName() { return displayName; }
    public Integer getCustomModelData() { return customModelData; }
    public boolean hasEnchantmentGlint() { return enchantmentGlint; }
    public String getTextureHint() { return textureHint; }
    public ArmorVisualDescriptor getArmorVisual() { return armorVisual; }
    public PotionVisualDescriptor getPotionVisual() { return potionVisual; }
    public PlayerHeadVisualDescriptor getPlayerHeadVisual() { return playerHeadVisual; }
}
