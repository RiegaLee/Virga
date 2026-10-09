package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
import cn.huohuas001.virga.features.inventory.head.PlayerHeadVisualDescriptor;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.potion.PotionVisualDescriptor;
import cn.huohuas001.virga.server.inventory.ItemVariantTextureHints;
import cn.huohuas001.virga.server.inventory.MojangHeadTextureResolver;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts live ItemStacks into immutable, renderer-neutral item snapshots on the server thread.
 * Minecraft 1.20.1 keeps item data in NBT tags.
 */
final class ForgeItemMapper {
    private static final Set<String> SUPPORTED_ARMOR_MODELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "minecraft:leather", "minecraft:chainmail", "minecraft:iron", "minecraft:gold",
        "minecraft:diamond", "minecraft:netherite", "minecraft:turtle_scute"
    )));

    private ForgeItemMapper() {}

    static ItemSnapshot map(ItemStack stack, RegistryAccess registries) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return null;
        String materialKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int maxDamage = Math.max(0, stack.getMaxDamage());
        int damage = maxDamage == 0 ? 0 : Math.max(0, Math.min(stack.getDamageValue(), maxDamage));
        String displayName = stack.hasCustomHoverName() ? stack.getHoverName().getString() : null;
        CompoundTag tag = stack.getTag();
        Integer customModelData = tag != null && tag.contains("CustomModelData", Tag.TAG_ANY_NUMERIC)
            ? Integer.valueOf(tag.getInt("CustomModelData")) : null;
        boolean glint = stack.hasFoil();

        return new ItemSnapshot(
            materialKey,
            stack.getCount(),
            damage,
            maxDamage,
            displayName,
            customModelData,
            glint,
            textureHint(stack, materialKey),
            armor(stack, materialKey, glint, registries),
            potion(stack, materialKey, glint),
            playerHead(stack, materialKey)
        );
    }

    private static ArmorVisualDescriptor armor(ItemStack stack, String materialKey, boolean glint, RegistryAccess registries) {
        ArmorVisualDescriptor.Slot slot = null;
        String model = null;
        if (stack.getItem() instanceof ArmorItem armor) {
            slot = slot(armor.getEquipmentSlot());
            model = modelFromMaterial(armor.getMaterial().getName());
        }
        if (slot == null) slot = slotFromName(materialKey);
        if (model == null) model = modelFromName(materialKey);
        if (slot == null || model == null || !SUPPORTED_ARMOR_MODELS.contains(model)) return null;

        String trimPattern = null;
        String trimMaterial = null;
        Optional<ArmorTrim> trim = ArmorTrim.getTrim(registries, stack);
        if (trim.isPresent()) {
            trimPattern = key(trim.get().pattern());
            trimMaterial = key(trim.get().material());
            if (trimPattern == null || trimMaterial == null) {
                trimPattern = null;
                trimMaterial = null;
            }
        }
        Integer leatherColor = null;
        if (materialKey.startsWith("minecraft:leather_") && stack.getItem() instanceof DyeableLeatherItem dyeable) {
            // Undyed leather reports vanilla's default brown.
            leatherColor = dyeable.getColor(stack) & 0xffffff;
        }
        return new ArmorVisualDescriptor(slot, materialKey, model, trimPattern, trimMaterial, leatherColor, glint);
    }

    private static ArmorVisualDescriptor.Slot slot(EquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> ArmorVisualDescriptor.Slot.HEAD;
            case CHEST -> ArmorVisualDescriptor.Slot.CHEST;
            case LEGS -> ArmorVisualDescriptor.Slot.LEGS;
            case FEET -> ArmorVisualDescriptor.Slot.FEET;
            default -> null;
        };
    }

    /** Armor material names: vanilla ones are bare ("iron", "turtle"), modded ones namespaced. */
    private static String modelFromMaterial(String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.equals("turtle")) return "minecraft:turtle_scute";
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    private static ArmorVisualDescriptor.Slot slotFromName(String materialKey) {
        if (materialKey.endsWith("_helmet")) return ArmorVisualDescriptor.Slot.HEAD;
        if (materialKey.endsWith("_chestplate")) return ArmorVisualDescriptor.Slot.CHEST;
        if (materialKey.endsWith("_leggings")) return ArmorVisualDescriptor.Slot.LEGS;
        if (materialKey.endsWith("_boots")) return ArmorVisualDescriptor.Slot.FEET;
        return null;
    }

    private static String modelFromName(String materialKey) {
        String path = materialKey.substring(materialKey.indexOf(':') + 1);
        if (path.startsWith("golden_")) return "minecraft:gold";
        if (path.equals("turtle_helmet")) return "minecraft:turtle_scute";
        for (String family : Arrays.asList("leather", "chainmail", "iron", "diamond", "netherite")) {
            if (path.startsWith(family + "_")) return "minecraft:" + family;
        }
        return null;
    }

    private static PotionVisualDescriptor potion(ItemStack stack, String materialKey, boolean glint) {
        if (!PotionVisualDescriptor.supports(materialKey)) return null;
        CompoundTag tag = stack.getTag();
        Potion potion = PotionUtils.getPotion(stack);
        boolean customColor = tag != null && tag.contains("CustomPotionColor", Tag.TAG_ANY_NUMERIC);
        boolean customEffects = tag != null && tag.contains("CustomPotionEffects", Tag.TAG_LIST);
        if (potion == Potions.EMPTY && !customColor && !customEffects) {
            return new PotionVisualDescriptor(materialKey, null, PotionVisualDescriptor.CLIENT_DEFAULT_TINT, false, glint);
        }
        String base = potion == Potions.EMPTY ? null : BuiltInRegistries.POTION.getKey(potion).toString();
        return new PotionVisualDescriptor(materialKey, base, PotionUtils.getColor(stack) & 0xffffff, customColor, glint);
    }

    private static PlayerHeadVisualDescriptor playerHead(ItemStack stack, String materialKey) {
        if (!"minecraft:player_head".equals(materialKey)) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("SkullOwner", Tag.TAG_COMPOUND)) return null;
        try {
            GameProfile profile = NbtUtils.readGameProfile(tag.getCompound("SkullOwner"));
            if (profile == null) return null;
            for (Property property : profile.getProperties().get("textures")) {
                String hash = MojangHeadTextureResolver.Companion.textureHash(property.getValue());
                if (hash != null) return new PlayerHeadVisualDescriptor(hash);
            }
            // Owner-only heads (UUID + name) are resolved off-thread later.
            UUID id = profile.getId();
            String name = profile.getName();
            if (id != null && name != null && name.matches("[A-Za-z0-9_]{1,16}") &&
                !id.equals(new UUID(0L, 0L))) {
                return new PlayerHeadVisualDescriptor(id, name);
            }
        } catch (RuntimeException ignored) {
            // Malformed profile data: render the generic head.
        }
        return null;
    }

    private static String textureHint(ItemStack stack, String materialKey) {
        if ("minecraft:enchanted_book".equals(materialKey)) {
            Map<Enchantment, Integer> stored = EnchantmentHelper.getEnchantments(stack);
            if (stored.size() != 1) return null;
            Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
            ResourceLocation key = BuiltInRegistries.ENCHANTMENT.getKey(entry.getKey());
            return key == null ? null : ItemVariantTextureHints.enchantedBook(key.toString(), entry.getValue());
        }
        if ("minecraft:suspicious_stew".equals(materialKey)) {
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains("Effects", Tag.TAG_LIST)) return null;
            ListTag effects = tag.getList("Effects", Tag.TAG_COMPOUND);
            if (effects.size() != 1) return null;
            CompoundTag effect = effects.getCompound(0);
            MobEffect type = MobEffect.byId(effect.getInt("EffectId"));
            ResourceLocation key = type == null ? null : BuiltInRegistries.MOB_EFFECT.getKey(type);
            if (key == null) return null;
            // Vanilla's default when the duration is missing: 8 seconds.
            int duration = effect.contains("EffectDuration", Tag.TAG_ANY_NUMERIC) ? effect.getInt("EffectDuration") : 160;
            return ItemVariantTextureHints.suspiciousStew(key.toString(), duration, 0);
        }
        return null;
    }

    private static String key(Holder<?> holder) {
        if (holder == null) return null;
        Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
        return key.map(resourceKey -> resourceKey.location().toString()).orElse(null);
    }
}
