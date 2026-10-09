package cn.huohuas001.virga.features.inventory.potion;

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Client-equivalent generated-item layer composition for potion tint items. */
public final class PotionTintCompositor {
    private final Path root;
    private final Map<String, BufferedImage> layers = new HashMap<String, BufferedImage>();
    private final Map<String, BufferedImage> cache = new HashMap<String, BufferedImage>();

    public PotionTintCompositor(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        load("potion_overlay");
        load("potion");
        load("splash_potion");
        load("lingering_potion");
        load("tipped_arrow_head");
        load("tipped_arrow_base");
    }

    public synchronized BufferedImage render(ItemSnapshot item) {
        Objects.requireNonNull(item, "item");
        PotionVisualDescriptor descriptor = item.getPotionVisual();
        if (descriptor == null) descriptor = PotionVisualDescriptor.defaultFor(item.getMaterialKey());
        if (!item.getMaterialKey().equals(descriptor.getItemTypeKey())) {
            throw new IllegalArgumentException("potion descriptor item type does not match ItemSnapshot");
        }
        String key = descriptor.visualKey();
        BufferedImage existing = cache.get(key);
        if (existing != null) return existing;

        String material = descriptor.getItemTypeKey();
        String layer0 = "minecraft:tipped_arrow".equals(material) ? "tipped_arrow_head" : "potion_overlay";
        String layer1;
        if ("minecraft:potion".equals(material)) layer1 = "potion";
        else if ("minecraft:splash_potion".equals(material)) layer1 = "splash_potion";
        else if ("minecraft:lingering_potion".equals(material)) layer1 = "lingering_potion";
        else if ("minecraft:tipped_arrow".equals(material)) layer1 = "tipped_arrow_base";
        else throw new IllegalArgumentException("unsupported potion material " + material);

        BufferedImage tinted = tint(layers.get(layer0), descriptor.getResolvedTintRgb());
        BufferedImage base = layers.get(layer1);
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(tinted, 0, 0, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(base, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        cache.put(key, result);
        return result;
    }

    public synchronized int cacheSize() { return cache.size(); }
    public Path getRoot() { return root; }

    private BufferedImage load(String name) {
        Path file = root.resolve(name + ".png").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Missing potion composition layer " + file);
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null || image.getWidth() != 32 || image.getHeight() != 32) {
                throw new IllegalArgumentException("Potion layer must be a readable 32x32 PNG: " + file);
            }
            layers.put(name, image);
            return image;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read potion composition layer " + file, error);
        }
    }

    static BufferedImage tint(BufferedImage source, int tintRgb) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int tr = (tintRgb >>> 16) & 0xff;
        int tg = (tintRgb >>> 8) & 0xff;
        int tb = tintRgb & 0xff;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int pixel = source.getRGB(x, y);
                int alpha = pixel >>> 24;
                int red = ((pixel >>> 16) & 0xff) * tr / 255;
                int green = ((pixel >>> 8) & 0xff) * tg / 255;
                int blue = (pixel & 0xff) * tb / 255;
                result.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return result;
    }
}
