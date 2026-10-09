package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** SDK-neutral theme loader. YAML is parsed with SnakeYAML only. */
public final class ThemeLoader {
    private ThemeLoader() {}

    public static Theme load(Path themeDirectory) {
        return load(themeDirectory, VanillaImportedAssetProvider.disabled(themeDirectory, "Vanilla provider not configured"));
    }

    public static Theme load(Path themeDirectory, VanillaImportedAssetProvider vanilla) {
        return load(themeDirectory, vanilla, null);
    }

    public static Theme load(Path themeDirectory, VanillaImportedAssetProvider vanilla, Path customOverrideDirectory) {
        Path root = Objects.requireNonNull(themeDirectory, "themeDirectory").toAbsolutePath().normalize();
        Map<String, Object> descriptor = yaml(safeResolve(root, "theme.yml"));
        requireVersion(integer(descriptor, "format-version", 0), "theme");
        String interpolation = text(descriptor, "render.texture-interpolation", "bilinear");
        if (!"bilinear".equals(interpolation) && !"nearest".equals(interpolation)) {
            throw new IllegalArgumentException("theme.render.texture-interpolation must be bilinear or nearest");
        }
        Path backgroundPath = safeResolve(root, text(descriptor, "background", null));
        Path layoutPath = safeResolve(root, text(descriptor, "layout", null));
        Path textureDirectory = safeResolve(root, text(descriptor, "textures-directory", null));
        Path overrideDirectory = safeResolve(root, text(descriptor, "overrides-directory", "overrides/items"));
        Path specialDirectory = safeResolve(root, text(descriptor, "special-variants-directory", "special-variants"));
        Path compositeDirectory = safeResolve(root, text(descriptor, "runtime-composites-directory", "runtime-composites/items/minecraft"));
        Path fallbackPath = safeResolve(root, text(descriptor, "fallback-texture", null));

        Layout layout = loadLayout(layoutPath);
        BufferedImage background = readImage(backgroundPath, "background");
        if (background.getWidth() != layout.getWidth() || background.getHeight() != layout.getHeight()) {
            throw new IllegalArgumentException("Background dimensions do not match layout canvas");
        }
        TextureResolver textures = new TextureResolver(
            customOverrideDirectory, overrideDirectory, specialDirectory, textureDirectory,
            fallbackPath, Objects.requireNonNull(vanilla, "vanilla"), Clock.systemDefaultZone()
        );
        if (Files.isDirectory(compositeDirectory)) textures.setRuntimeItemRenderer(new RuntimeItemIconRenderer(compositeDirectory));
        return new Theme(
            text(descriptor, "id", null), text(descriptor, "name", null), text(descriptor, "version", null),
            text(descriptor, "minecraft-version", null), text(descriptor, "asset-pack-version", null),
            background, layout, textures,
            bool(descriptor, "render.draw-title", true), bool(descriptor, "render.draw-slot-backgrounds", true),
            bool(descriptor, "render.draw-player-preview-matte", true), "nearest".equals(interpolation),
            loadEnderChestLayout(descriptor)
        );
    }

    static Layout loadLayout(Path path) {
        Map<String, Object> values = yaml(path);
        requireVersion(integer(values, "format-version", 0), "layout");
        Layout.Grid storage = grid(values, "storage");
        Layout.Grid hotbar = grid(values, "hotbar");
        Map<SlotType, Point> equipment = new EnumMap<>(SlotType.class);
        equipment.put(SlotType.ARMOR_HEAD, point(values, "armor.head"));
        equipment.put(SlotType.ARMOR_CHEST, point(values, "armor.chest"));
        equipment.put(SlotType.ARMOR_LEGS, point(values, "armor.legs"));
        equipment.put(SlotType.ARMOR_FEET, point(values, "armor.feet"));
        equipment.put(SlotType.OFFHAND, point(values, "offhand"));
        int slotSize = positive(values, "slot.size");
        Rectangle preview = value(values, "player-preview") instanceof Map ? rectangle(values, "player-preview") : inferLegacyPreview(equipment, slotSize);
        Rectangle freshness = value(values, "freshness") instanceof Map ? rectangle(values, "freshness") : preview;
        return new Layout(
            positive(values, "canvas.width"), positive(values, "canvas.height"), slotSize,
            positive(values, "slot.item-size"), point(values, "title"), storage, hotbar, equipment,
            preview, freshness, new Point(nonNegative(values, "quantity.offset-x"), nonNegative(values, "quantity.offset-y")),
            new Rectangle(nonNegative(values, "durability.offset-x"), nonNegative(values, "durability.offset-y"),
                positive(values, "durability.width"), positive(values, "durability.height"))
        );
    }

    private static EnderChestLayout loadEnderChestLayout(Map<String, Object> values) {
        if (!(value(values, "ender-chest") instanceof Map)) return EnderChestLayout.legacy();
        return new EnderChestLayout(
            positive(values, "ender-chest.canvas.width"), positive(values, "ender-chest.canvas.height"),
            nonNegative(values, "ender-chest.storage.start-x"), nonNegative(values, "ender-chest.storage.start-y"),
            positive(values, "ender-chest.storage.step-x"), positive(values, "ender-chest.storage.step-y"),
            positive(values, "ender-chest.slot.size"), positive(values, "ender-chest.slot.item-size"),
            nonNegative(values, "ender-chest.freshness.x"), nonNegative(values, "ender-chest.freshness.y")
        );
    }

    private static Layout.Grid grid(Map<String, Object> values, String path) {
        return new Layout.Grid(nonNegative(values, path + ".start-x"), nonNegative(values, path + ".start-y"),
            positive(values, path + ".columns"), positive(values, path + ".rows"),
            positive(values, path + ".step-x"), positive(values, path + ".step-y"));
    }

    private static Point point(Map<String, Object> values, String path) {
        return new Point(nonNegative(values, path + ".x"), nonNegative(values, path + ".y"));
    }

    private static Rectangle rectangle(Map<String, Object> values, String path) {
        return new Rectangle(nonNegative(values, path + ".x"), nonNegative(values, path + ".y"),
            positive(values, path + ".width"), positive(values, path + ".height"));
    }

    private static Rectangle inferLegacyPreview(Map<SlotType, Point> equipment, int slotSize) {
        Point head = equipment.get(SlotType.ARMOR_HEAD), chest = equipment.get(SlotType.ARMOR_CHEST);
        Point legs = equipment.get(SlotType.ARMOR_LEGS), feet = equipment.get(SlotType.ARMOR_FEET);
        Point offhand = equipment.get(SlotType.OFFHAND);
        if (head.x != chest.x || head.x != legs.x || head.x != feet.x) return null;
        int x = head.x + slotSize + 2, y = head.y + 2, right = offhand.x - 4, bottom = feet.y + slotSize - 3;
        return right > x && bottom > y ? new Rectangle(x, y, right - x, bottom - y) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path path) {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing YAML: " + path);
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("YAML root must be a map: " + path);
            return (Map<String, Object>) loaded;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read YAML: " + path, error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object value(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private static String text(Map<String, Object> root, String path, String fallback) {
        Object raw = value(root, path);
        String result = raw == null ? fallback : raw.toString().trim();
        if (result == null || result.isEmpty()) throw new IllegalArgumentException(path + " must not be blank");
        return result;
    }

    private static int integer(Map<String, Object> root, String path, int fallback) {
        Object raw = value(root, path);
        return raw instanceof Number ? ((Number) raw).intValue() : fallback;
    }

    private static boolean bool(Map<String, Object> root, String path, boolean fallback) {
        Object raw = value(root, path);
        return raw instanceof Boolean ? ((Boolean) raw).booleanValue() : fallback;
    }

    private static int positive(Map<String, Object> root, String path) {
        int result = integer(root, path, Integer.MIN_VALUE);
        if (result < 1) throw new IllegalArgumentException(path + " must be positive");
        return result;
    }

    private static int nonNegative(Map<String, Object> root, String path) {
        int result = integer(root, path, Integer.MIN_VALUE);
        if (result < 0) throw new IllegalArgumentException(path + " must not be negative");
        return result;
    }

    private static void requireVersion(int version, String type) {
        if (version != 1) throw new IllegalArgumentException("Unsupported " + type + " format-version " + version);
    }

    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Theme path escapes its directory: " + relative);
        return resolved;
    }

    private static BufferedImage readImage(Path path, String label) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IllegalArgumentException("Unreadable " + label + ": " + path);
            return image;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read " + label + ": " + path, error);
        }
    }
}
