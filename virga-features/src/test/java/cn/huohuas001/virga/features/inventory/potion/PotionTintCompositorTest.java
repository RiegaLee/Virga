package cn.huohuas001.virga.features.inventory.potion;

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PotionTintCompositorTest {
    private static final Path ROOT = Paths.get(
        "build", "resources", "main", "bundled-assets", "pack", "themes", "virga", "runtime-composites", "items", "minecraft"
    );

    @Test
    void sameItemDifferentTintUsesDistinctVisualKeyAndCacheEntry() throws Exception {
        PotionTintCompositor compositor = new PotionTintCompositor(ROOT);
        ItemSnapshot red = item("minecraft:potion", 0xff2020, "minecraft:healing", false);
        ItemSnapshot green = item("minecraft:potion", 0x20ff40, "minecraft:poison", false);

        BufferedImage redIcon = compositor.render(red);
        BufferedImage greenIcon = compositor.render(green);
        assertNotEquals(hash(redIcon), hash(greenIcon));
        assertEquals(2, compositor.cacheSize());
        assertTrue(redIcon == compositor.render(red), "identical visual key must reuse the runtime image");
        assertEquals(2, compositor.cacheSize());
    }

    @Test
    void composesAllModelsAt32x32AndKeepsUntintedBottlePixels() throws Exception {
        PotionTintCompositor compositor = new PotionTintCompositor(ROOT);
        for (String material : new String[] {
            "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:tipped_arrow"
        }) {
            BufferedImage icon = compositor.render(item(material, 0x315ac8, "minecraft:water", false));
            assertEquals(32, icon.getWidth());
            assertEquals(32, icon.getHeight());
            assertTrue(alphaPixels(icon) > 20, material);
        }

        BufferedImage overlay = ImageIO.read(ROOT.resolve("potion_overlay.png").toFile());
        BufferedImage bottle = ImageIO.read(ROOT.resolve("potion.png").toFile());
        BufferedImage result = compositor.render(item("minecraft:potion", 0xff0000, "minecraft:healing", false));
        boolean foundUntinted = false;
        for (int y = 0; y < 32 && !foundUntinted; y++) for (int x = 0; x < 32; x++) {
            if ((overlay.getRGB(x,y) >>> 24) == 0 && (bottle.getRGB(x,y) >>> 24) != 0) {
                assertEquals(bottle.getRGB(x,y), result.getRGB(x,y));
                foundUntinted = true;
                break;
            }
        }
        assertTrue(foundUntinted, "test fixture must contain an untinted glass/outline pixel");
    }

    private static ItemSnapshot item(String material, int tint, String base, boolean custom) {
        return new ItemSnapshot(
            material, 1, 0, 0, null, null, false, null, null,
            new PotionVisualDescriptor(material, base, tint, custom, false)
        );
    }

    private static int alphaPixels(BufferedImage image) {
        int count=0;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) if((image.getRGB(x,y)>>>24)!=0) count++;
        return count;
    }

    private static String hash(BufferedImage image) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int pixel=image.getRGB(x,y);
            digest.update((byte)(pixel>>>24));digest.update((byte)(pixel>>>16));
            digest.update((byte)(pixel>>>8));digest.update((byte)pixel);
        }
        StringBuilder value=new StringBuilder();
        for(byte b:digest.digest()) value.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
        return value.toString();
    }
}
