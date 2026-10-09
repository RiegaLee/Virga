package cn.huohuas001.virga.features.inventory.skin;

import java.awt.image.BufferedImage;
import java.util.Objects;

/** Validated local skin pixels plus an immutable content-derived cache key. */
public final class PlayerSkin {
    private final BufferedImage image;
    private final String cacheKey;
    private final String source;
    private final boolean slim;

    public PlayerSkin(BufferedImage image, String cacheKey, String source, boolean slim) {
        this.image = LegacySkins.normalized(Objects.requireNonNull(image, "image"));
        if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IllegalArgumentException("Minecraft skin must be 64x64 or legacy 64x32");
        }
        this.cacheKey = safeKey(cacheKey);
        this.source = Objects.requireNonNull(source, "source");
        this.slim = slim;
    }

    private static String safeKey(String value) {
        String normalized = Objects.requireNonNull(value, "cacheKey").trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{1,128}")) throw new IllegalArgumentException("unsafe skin cache key");
        return normalized;
    }

    public BufferedImage getImage() { return image; }
    public String getCacheKey() { return cacheKey; }
    public String getSource() { return source; }
    public boolean isSlim() { return slim; }
}
