package cn.huohuas001.virga.features.inventory.armor;

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Runtime compositor for dye- and trim-aware armor item icons; the baked cache remains immutable. */
public final class ArmorItemIconRenderer {
    public static final String RENDERER_VERSION = "avi1";

    private final EquipmentAssetResolver assets;
    private final Path cacheRoot;
    private final Map<String, BufferedImage> memory = new HashMap<String, BufferedImage>();

    public ArmorItemIconRenderer(EquipmentAssetResolver assets, Path cacheRoot) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
    }

    public synchronized BufferedImage render(ItemSnapshot item, BufferedImage baseIcon) {
        ArmorVisualDescriptor armor = item == null ? null : item.getArmorVisual();
        if (armor == null || (armor.getLeatherColor() == null && !armor.hasTrim())) return baseIcon;
        String key = sha256(RENDERER_VERSION + '|' + EquipmentAssetResolver.ASSET_VERSION + '|' + armor.visualKey());
        BufferedImage cached = memory.get(key);
        if (cached != null) return cached;
        Path file = cacheRoot.resolve(RENDERER_VERSION + '-' + key + ".png").normalize();
        if (file.startsWith(cacheRoot) && Files.isRegularFile(file)) {
            try {
                BufferedImage disk = ImageIO.read(file.toFile());
                if (disk != null) {
                    memory.put(key, disk);
                    return disk;
                }
            } catch (Exception ignored) {
                // Regenerate the content-addressed icon.
            }
        }

        BufferedImage result = armor.getLeatherColor() == null
            ? copy(baseIcon) : assets.resolveLeatherItem(armor);
        BufferedImage trim = assets.resolveItemTrim(armor);
        if (trim != null) result = overlay(result, trim);
        memory.put(key, result);
        write(file, result, key);
        return result;
    }

    private void write(Path file, BufferedImage image, String key) {
        if (!file.startsWith(cacheRoot)) return;
        try {
            Files.createDirectories(cacheRoot);
            Path temporary = Files.createTempFile(cacheRoot, key + '-', ".tmp");
            try {
                ImageIO.write(image, "png", temporary.toFile());
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception ignored) {
            // The memory result remains authoritative for this process.
        }
    }

    private static BufferedImage overlay(BufferedImage base, BufferedImage layer) {
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

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try { graphics.drawImage(source, 0, 0, null); }
        finally { graphics.dispose(); }
        return result;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte part : hash) output.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff));
            return output.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
