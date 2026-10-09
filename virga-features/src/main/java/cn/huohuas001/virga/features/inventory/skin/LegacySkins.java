package cn.huohuas001.virga.features.inventory.skin;

import java.awt.image.BufferedImage;

/**
 * Matches the vanilla client's handling of legacy 64x32 skins: the hat layer of such a skin
 * is treated as transparent when it has no transparent pixel at all (the "Notch transparency
 * hack"); otherwise players like Notch would get an opaque black helmet over the face.
 */
public final class LegacySkins {
    private LegacySkins() {}

    /** Returns a copy with the hack applied when [skin] is a legacy 64x32 (or scaled) atlas. */
    public static BufferedImage normalized(BufferedImage skin) {
        if (skin == null || skin.getWidth() < 64 || skin.getWidth() % 64 != 0) return skin;
        int scale = skin.getWidth() / 64;
        if (skin.getHeight() != 32 * scale) return skin;
        int x0 = 32 * scale;
        int x1 = 64 * scale;
        int hatBottom = 16 * scale;
        // Vanilla inspects x 32..64, y 0..32 of the legacy atlas and clears the hat rows.
        for (int y = 0; y < 32 * scale; y++) {
            for (int x = x0; x < x1; x++) {
                if ((skin.getRGB(x, y) >>> 24) < 128) return skin;
            }
        }
        BufferedImage copy = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(skin, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        for (int y = 0; y < hatBottom; y++) {
            for (int x = x0; x < x1; x++) copy.setRGB(x, y, 0);
        }
        return copy;
    }
}
