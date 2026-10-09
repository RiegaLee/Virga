package cn.huohuas001.virga.features.inventory.skin;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** Headless Java2D front-view player preview with base and outer skin layers. */
public final class PlayerSkinRenderer {
    public static final String CACHE_VERSION = "p1";
    public static final int WIDTH = 128;
    public static final int HEIGHT = 256;

    public BufferedImage render(PlayerSkin value) {
        BufferedImage skin = normalizeLegacy(value.getImage());
        BufferedImage result = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(new Color(0, 0, 0, 80));
            graphics.fillOval(23, 237, 82, 12);

            drawPart(graphics, layered(skin, 4, 20, 4, 12, 4, 36), 48, 164, 20, 72, 1.5);
            drawPart(graphics, layered(skin, 20, 52, 4, 12, 4, 52), 63, 164, 20, 72, -1.5);
            drawPart(graphics, layered(skin, 44, 20, 4, 12, 44, 36), 27, 91, value.isSlim() ? 18 : 20, 73, 7.0);
            drawPart(graphics, layered(skin, 36, 52, 4, 12, 52, 52), 81, 91, value.isSlim() ? 18 : 20, 73, -7.0);
            drawPart(graphics, layered(skin, 20, 20, 8, 12, 20, 36), 44, 91, 40, 73, 0.0);
            drawPart(graphics, layered(skin, 8, 8, 8, 8, 40, 8), 36, 24, 56, 56, 0.0);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage layered(
        BufferedImage skin,
        int baseX,
        int baseY,
        int width,
        int height,
        int overlayX,
        int overlayY
    ) {
        BufferedImage part = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = part.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(skin, 0, 0, width, height, baseX, baseY, baseX + width, baseY + height, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.drawImage(skin, 0, 0, width, height, overlayX, overlayY, overlayX + width, overlayY + height, null);
        } finally {
            graphics.dispose();
        }
        return part;
    }

    private static void drawPart(
        Graphics2D destination,
        BufferedImage part,
        int x,
        int y,
        int width,
        int height,
        double angle
    ) {
        AffineTransform before = destination.getTransform();
        destination.rotate(Math.toRadians(angle), x + width / 2.0, y + 3.0);
        destination.drawImage(part, x, y, width, height, null);
        destination.setTransform(before);
    }

    private static BufferedImage normalizeLegacy(BufferedImage input) {
        if (input.getHeight() == 64) return input;
        BufferedImage expanded = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = expanded.createGraphics();
        try {
            graphics.drawImage(input, 0, 0, null);
            copyMirrored(input, graphics, 4, 20, 4, 12, 20, 52);
            copyMirrored(input, graphics, 44, 20, 4, 12, 36, 52);
        } finally {
            graphics.dispose();
        }
        return expanded;
    }

    private static void copyMirrored(
        BufferedImage source,
        Graphics2D destination,
        int sourceX,
        int sourceY,
        int width,
        int height,
        int targetX,
        int targetY
    ) {
        destination.drawImage(
            source, targetX, targetY, targetX + width, targetY + height,
            sourceX + width, sourceY, sourceX, sourceY + height, null
        );
    }
}
