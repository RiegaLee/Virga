package cn.huohuas001.virga.features.inventory.skin;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LegacySkinsTest {
    @Test
    void opaqueLegacyHatLayerBecomesTransparentLikeTheVanillaClient() {
        BufferedImage skin = filled(64, 32, 0xFF000000);
        BufferedImage normalized = LegacySkins.normalized(skin);
        assertEquals(0, normalized.getRGB(40, 8) >>> 24, "hat pixel must be cleared");
        assertEquals(0xFF000000, normalized.getRGB(8, 8), "face stays untouched");
        assertEquals(0xFF000000, normalized.getRGB(44, 20), "arm rows below the hat stay untouched");
    }

    @Test
    void legacySkinWithAnyTransparentPixelKeepsItsHat() {
        BufferedImage skin = filled(64, 32, 0xFF112233);
        skin.setRGB(50, 20, 0x00000000);
        assertSame(skin, LegacySkins.normalized(skin));
    }

    @Test
    void modernSkinsAreNeverChanged() {
        BufferedImage skin = filled(64, 64, 0xFF000000);
        assertSame(skin, LegacySkins.normalized(skin));
    }

    private static BufferedImage filled(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        }
        return image;
    }
}
