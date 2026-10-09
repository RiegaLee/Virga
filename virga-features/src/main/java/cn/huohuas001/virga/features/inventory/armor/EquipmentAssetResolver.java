package cn.huohuas001.virga.features.inventory.armor;

import cn.huohuas001.virga.features.inventory.asset.MiniJson;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves 26.1.2 equipment layers and palette-driven trims from the managed runtime pack. */
public final class EquipmentAssetResolver {
    public static final String ASSET_VERSION = "armor-assets-26.1.2-v1";

    private final Path root;
    private final Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();
    private final Map<String, Map<String, Object>> json = new HashMap<String, Map<String, Object>>();
    private final Map<String, BufferedImage> armorTextures = new HashMap<String, BufferedImage>();
    private final Map<String, List<BufferedImage>> armorLayers = new HashMap<String, List<BufferedImage>>();
    private final Map<String, BufferedImage> armorTrims = new HashMap<String, BufferedImage>();

    public EquipmentAssetResolver(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        requireFile("equipment/diamond.json");
        requireFile("textures/entity/equipment/humanoid/diamond.png");
        requireFile("textures/trims/color_palettes/trim_palette.png");
        requireFile("textures/misc/enchanted_glint_armor.png");
    }

    public synchronized BufferedImage resolveArmorTexture(
        ArmorVisualDescriptor descriptor, boolean leggings
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        String layerType = leggings ? "humanoid_leggings" : "humanoid";
        String cacheKey = layerType + '|' + descriptor.visualKey();
        BufferedImage cached = armorTextures.get(cacheKey);
        if (cached != null) return cached;

        BufferedImage result = null;
        for (BufferedImage texture : resolveArmorLayers(descriptor, leggings)) result = overlay(result, texture);
        result = overlay(result, resolveArmorTrimTexture(descriptor, leggings));
        if (result != null) armorTextures.put(cacheKey, result);
        return result;
    }

    /** Client equipment layers in draw order. The glint pass follows the first visible layer. */
    public synchronized List<BufferedImage> resolveArmorLayers(
        ArmorVisualDescriptor descriptor, boolean leggings
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        String layerType = leggings ? "humanoid_leggings" : "humanoid";
        String cacheKey = layerType + '|' + descriptor.visualKey();
        List<BufferedImage> cached = armorLayers.get(cacheKey);
        if (cached != null) return cached;

        String equipmentPath = keyPath(descriptor.getEquipmentModelKey(), "equipment model");
        Map<String, Object> definition = json("equipment/" + equipmentPath + ".json");
        Map<String, Object> layers = MiniJson.object(definition.get("layers"), "equipment.layers");
        Object rawLayers = layers.get(layerType);
        if (rawLayers == null) return Collections.emptyList();
        List<Object> entries = MiniJson.array(rawLayers, "equipment layer " + layerType);
        List<BufferedImage> result = new ArrayList<BufferedImage>();
        for (Object raw : entries) {
            Map<String, Object> layer = MiniJson.object(raw, "equipment layer");
            String textureKey = MiniJson.string(layer, "texture", true);
            BufferedImage texture = image(
                "textures/entity/equipment/" + layerType + "/" + keyPath(textureKey, "equipment texture") + ".png"
            );
            if (layer.containsKey("dyeable")) {
                Map<String, Object> dyeable = MiniJson.object(layer.get("dyeable"), "dyeable");
                int fallback = MiniJson.integer(dyeable, "color_when_undyed", 0xa06540) & 0xffffff;
                int color = descriptor.getLeatherColor() == null ? fallback : descriptor.getLeatherColor().intValue();
                texture = tint(texture, color);
            }
            result.add(texture);
        }
        List<BufferedImage> immutable = Collections.unmodifiableList(result);
        armorLayers.put(cacheKey, immutable);
        return immutable;
    }

    /** Trim is drawn after the client armor glint pass, so it remains independently readable. */
    public synchronized BufferedImage resolveArmorTrimTexture(
        ArmorVisualDescriptor descriptor, boolean leggings
    ) {
        if (descriptor == null || !descriptor.hasTrim()) return null;
        String layerType = leggings ? "humanoid_leggings" : "humanoid";
        String cacheKey = layerType + '|' + descriptor.visualKey();
        BufferedImage cached = armorTrims.get(cacheKey);
        if (cached != null) return cached;
        String pattern = trimPatternAsset(descriptor.getTrimPatternKey());
        BufferedImage trim = image("textures/trims/entity/" + layerType + "/" + pattern + ".png");
        BufferedImage result = applyTrimPalette(trim, paletteAsset(descriptor));
        armorTrims.put(cacheKey, result);
        return result;
    }

    public synchronized BufferedImage resolveArmorGlintTexture() {
        return image("textures/misc/enchanted_glint_armor.png");
    }

    public synchronized BufferedImage resolveItemTrim(ArmorVisualDescriptor descriptor) {
        if (descriptor == null || !descriptor.hasTrim()) return null;
        String slot;
        switch (descriptor.getSlot()) {
            case HEAD: slot = "helmet"; break;
            case CHEST: slot = "chestplate"; break;
            case LEGS: slot = "leggings"; break;
            case FEET: slot = "boots"; break;
            default: throw new IllegalArgumentException("Unsupported armor slot");
        }
        BufferedImage mask = image("textures/trims/items/" + slot + "_trim.png");
        return applyTrimPalette(mask, paletteAsset(descriptor));
    }

    public synchronized BufferedImage resolveLeatherItem(ArmorVisualDescriptor descriptor) {
        if (descriptor == null || descriptor.getLeatherColor() == null) return null;
        String slot;
        switch (descriptor.getSlot()) {
            case HEAD: slot = "helmet"; break;
            case CHEST: slot = "chestplate"; break;
            case LEGS: slot = "leggings"; break;
            case FEET: slot = "boots"; break;
            default: throw new IllegalArgumentException("Unsupported armor slot");
        }
        BufferedImage base = tint(image("textures/item/leather_" + slot + ".png"), descriptor.getLeatherColor());
        BufferedImage overlay = image("textures/item/leather_" + slot + "_overlay.png");
        return overlay(base, overlay);
    }

    private String trimPatternAsset(String patternKey) {
        String path = keyPath(patternKey, "trim pattern");
        Map<String, Object> definition = json("data/trim_pattern/" + path + ".json");
        return keyPath(MiniJson.string(definition, "asset_id", true), "trim pattern asset");
    }

    private String paletteAsset(ArmorVisualDescriptor descriptor) {
        String path = keyPath(descriptor.getTrimMaterialKey(), "trim material");
        Map<String, Object> definition = json("data/trim_material/" + path + ".json");
        String asset = MiniJson.string(definition, "asset_name", true);
        Object overridesValue = definition.get("override_armor_assets");
        if (overridesValue != null) {
            Map<String, Object> overrides = MiniJson.object(overridesValue, "override_armor_assets");
            Object override = overrides.get(descriptor.getEquipmentModelKey());
            if (override instanceof String && !((String) override).trim().isEmpty()) asset = ((String) override).trim();
        }
        if (!asset.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Unsafe trim palette asset " + asset);
        return asset;
    }

    private BufferedImage applyTrimPalette(BufferedImage source, String asset) {
        BufferedImage sourcePalette = image("textures/trims/color_palettes/trim_palette.png");
        BufferedImage targetPalette = image("textures/trims/color_palettes/" + asset + ".png");
        int entries = Math.min(sourcePalette.getWidth() * sourcePalette.getHeight(),
            targetPalette.getWidth() * targetPalette.getHeight());
        Map<Integer, Integer> colors = new HashMap<Integer, Integer>();
        for (int index = 0; index < entries; index++) {
            int sourceColor;
            if (sourcePalette.getRaster().getNumBands() == 1) {
                // The client palette is an 8x1 grayscale PNG. getRGB() applies gray color-space
                // conversion, while trim masks contain the original byte values.
                int sample = sourcePalette.getRaster().getSample(
                    index % sourcePalette.getWidth(), index / sourcePalette.getWidth(), 0
                );
                sourceColor = (sample << 16) | (sample << 8) | sample;
            } else {
                sourceColor = sourcePalette.getRGB(
                    index % sourcePalette.getWidth(), index / sourcePalette.getWidth()
                ) & 0xffffff;
            }
            int targetColor = targetPalette.getRGB(index % targetPalette.getWidth(), index / targetPalette.getWidth()) & 0xffffff;
            colors.put(Integer.valueOf(sourceColor), Integer.valueOf(targetColor));
        }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int argb = source.getRGB(x, y);
            Integer replacement = colors.get(Integer.valueOf(argb & 0xffffff));
            result.setRGB(x, y, (argb & 0xff000000) | (replacement == null ? argb & 0xffffff : replacement.intValue()));
        }
        return result;
    }

    private static BufferedImage tint(BufferedImage source, int color) {
        int tintRed = (color >>> 16) & 0xff;
        int tintGreen = (color >>> 8) & 0xff;
        int tintBlue = color & 0xff;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int argb = source.getRGB(x, y);
            int red = ((argb >>> 16) & 0xff) * tintRed / 255;
            int green = ((argb >>> 8) & 0xff) * tintGreen / 255;
            int blue = (argb & 0xff) * tintBlue / 255;
            result.setRGB(x, y, (argb & 0xff000000) | (red << 16) | (green << 8) | blue);
        }
        return result;
    }

    private static BufferedImage overlay(BufferedImage base, BufferedImage layer) {
        if (layer == null) return base;
        if (base == null) return copy(layer);
        BufferedImage result = copy(base);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(layer, 0, 0, result.getWidth(), result.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.drawImage(image, 0, 0, null); }
        finally { graphics.dispose(); }
        return result;
    }

    private BufferedImage image(String relative) {
        BufferedImage cached = images.get(relative);
        if (cached != null) return cached;
        Path file = safe(relative);
        try {
            BufferedImage loaded = ImageIO.read(file.toFile());
            if (loaded == null) throw new IllegalArgumentException("Unreadable armor image " + file);
            images.put(relative, loaded);
            return loaded;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read armor image " + file, error);
        }
    }

    private Map<String, Object> json(String relative) {
        Map<String, Object> cached = json.get(relative);
        if (cached != null) return cached;
        Path file = safe(relative);
        try {
            Map<String, Object> parsed = MiniJson.object(
                MiniJson.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)), relative
            );
            json.put(relative, parsed);
            return parsed;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read armor JSON " + file, error);
        }
    }

    private void requireFile(String relative) {
        Path file = safe(relative);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing armor asset " + file);
    }

    private Path safe(String relative) {
        if (relative == null || !relative.matches("[a-zA-Z0-9._/-]+") || relative.contains("..") ||
            relative.startsWith("/") || relative.endsWith("/")) {
            throw new IllegalArgumentException("Unsafe armor asset path " + relative);
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Missing armor asset " + file);
        }
        return file;
    }

    private static String keyPath(String key, String label) {
        String normalized = Objects.requireNonNull(key, label).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]+:[a-z0-9._/-]+") || normalized.contains("..") ||
            normalized.contains("//") || normalized.endsWith("/")) {
            throw new IllegalArgumentException("Unsafe " + label + " " + key);
        }
        return normalized.substring(normalized.indexOf(':') + 1);
    }
}
