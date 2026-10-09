package cn.huohuas001.virga.server.inventory;


import java.util.Locale;

/** Selects Faithful add-on textures from immutable item values read on the server thread. */
public final class ItemVariantTextureHints {
    private static final String BOOK_ROOT = "variants/minecraft/enchanted_book/";
    private static final String STEW_ROOT = "variants/minecraft/suspicious_stew/";

    private ItemVariantTextureHints() {}


    public static String copperGolemStatue(String rawMaterialKey, String rawPose) {
        String materialKey = normalizeKey(rawMaterialKey);
        if (!materialKey.matches("minecraft:(waxed_)?(exposed_|weathered_|oxidized_)?copper_golem_statue")) {
            return null;
        }
        String pose = rawPose == null ? "" : rawPose.trim().toLowerCase(Locale.ROOT);
        if (!pose.equals("standing") && !pose.equals("sitting") &&
            !pose.equals("running") && !pose.equals("star")) return null;
        return materialKey.replace(':', '/') + "/" + pose;
    }


    /** Mirrors the add-on's component selector: only one exact stored enchantment is a match. */
    public static String enchantedBook(String rawKey, int level) {
        String key = normalizeKey(rawKey);
        int maxLevel = bookMaxLevel(key);
        if (level < 1 || level > maxLevel) return null;
        String fileName = switch (key) {
            case "minecraft:binding_curse" -> "curse_of_binding";
            case "minecraft:vanishing_curse" -> "curse_of_vanishing";
            default -> key.substring("minecraft:".length());
        };
        if (maxLevel > 1) fileName += level;
        return BOOK_ROOT + fileName;
    }

    /** Mirrors the add-on's exact suspicious_stew_effects component values. */
    public static String suspiciousStew(String rawKey, int duration, int amplifier) {
        if (amplifier != 0) return null;
        String fileName = switch (normalizeKey(rawKey)) {
            case "minecraft:saturation" -> duration == 7 ? "saturation" : null;
            case "minecraft:night_vision" -> duration == 100 ? "night_vision" : null;
            case "minecraft:blindness" -> duration == 220 ? "blindness" : null;
            case "minecraft:nausea" -> duration == 140 ? "nausea" : null;
            case "minecraft:fire_resistance" -> duration == 60 ? "fire_resistance" : null;
            case "minecraft:regeneration" -> duration == 140 ? "regeneration" : null;
            case "minecraft:jump_boost" -> duration == 100 ? "jump_boost" : null;
            case "minecraft:poison" -> duration == 220 ? "poison" : null;
            case "minecraft:weakness" -> duration == 140 ? "weakness" : null;
            case "minecraft:wither" -> duration == 140 ? "wither" : null;
            default -> null;
        };
        return fileName == null ? null : STEW_ROOT + "suspicious_stew_" + fileName;
    }

    private static int bookMaxLevel(String key) {
        return switch (key) {
            case "minecraft:aqua_affinity", "minecraft:binding_curse", "minecraft:channeling",
                 "minecraft:flame", "minecraft:infinity", "minecraft:mending", "minecraft:multishot",
                 "minecraft:silk_touch", "minecraft:vanishing_curse" -> 1;
            case "minecraft:fire_aspect", "minecraft:frost_walker", "minecraft:knockback",
                 "minecraft:punch" -> 2;
            case "minecraft:depth_strider", "minecraft:fortune", "minecraft:looting", "minecraft:loyalty",
                 "minecraft:luck_of_the_sea", "minecraft:lunge", "minecraft:lure", "minecraft:quick_charge",
                 "minecraft:respiration", "minecraft:riptide", "minecraft:soul_speed",
                 "minecraft:sweeping_edge", "minecraft:swift_sneak", "minecraft:thorns",
                 "minecraft:unbreaking", "minecraft:wind_burst" -> 3;
            case "minecraft:blast_protection", "minecraft:breach", "minecraft:feather_falling",
                 "minecraft:fire_protection", "minecraft:piercing", "minecraft:projectile_protection",
                 "minecraft:protection" -> 4;
            case "minecraft:bane_of_arthropods", "minecraft:density", "minecraft:efficiency",
                 "minecraft:impaling", "minecraft:power", "minecraft:sharpness", "minecraft:smite" -> 5;
            default -> 0;
        };
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
