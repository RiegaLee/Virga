package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.potion.PotionTintCompositor;
import cn.huohuas001.virga.features.inventory.potion.PotionVisualDescriptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Resolves Inventory-only GUI models and lightweight state-aware item composites outside the baked cache. */
final class RuntimeItemIconRenderer {
    private final Path root;
    private final Path tridentFile;
    private final BufferedImage trident;
    private final PotionTintCompositor potions;

    RuntimeItemIconRenderer(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.tridentFile = this.root.resolve("trident.png").normalize();
        this.trident = read32(tridentFile, "Trident GUI layer");
        this.potions = new PotionTintCompositor(this.root);
    }

    boolean supports(String materialKey) {
        String key = normalize(materialKey);
        return "minecraft:trident".equals(key) || PotionVisualDescriptor.supports(key);
    }

    Result render(ItemSnapshot item) {
        String material = normalize(item.getMaterialKey());
        if ("minecraft:trident".equals(material)) {
            return new Result(trident, TextureResolver.Source.GUI_MODEL, "GENERATED_2D_GUI_MODEL", tridentFile);
        }
        if (PotionVisualDescriptor.supports(material)) {
            return new Result(potions.render(item), TextureResolver.Source.RUNTIME_COMPOSITE, "POTION_TINT", root);
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BufferedImage read32(Path file, String label) {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing " + label + ": " + file);
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() != 32 || image.getHeight() != 32) {
                throw new IllegalArgumentException(label + " must be a readable 32x32 PNG: " + file);
            }
            return image;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read " + label + ": " + file, error);
        }
    }

    static final class Result {
        final BufferedImage image;
        final TextureResolver.Source source;
        final String renderPath;
        final Path file;
        Result(BufferedImage image, TextureResolver.Source source, String renderPath, Path file) {
            this.image=image; this.source=source; this.renderPath=renderPath; this.file=file;
        }
    }
}
