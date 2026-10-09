package cn.huohuas001.virga.features.inventory.renderer;

import java.util.Arrays;
import java.util.Objects;

/** Immutable encoded image returned by the renderer. */
public final class RenderResult {
    private final byte[] bytes;
    private final String mimeType;
    private final int width;
    private final int height;

    public RenderResult(byte[] bytes, String mimeType, int width, int height) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) throw new IllegalArgumentException("bytes must not be empty");
        if (width < 1 || height < 1) throw new IllegalArgumentException("dimensions must be positive");
        this.bytes = Arrays.copyOf(bytes, bytes.length);
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.width = width;
        this.height = height;
    }

    public byte[] getBytes() { return Arrays.copyOf(bytes, bytes.length); }
    public int getByteSize() { return bytes.length; }
    public String getMimeType() { return mimeType; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
