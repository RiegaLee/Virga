package cn.huohuas001.virga.features.inventory.renderer;

/** Theme-owned geometry for the compact 9x3 Ender Chest renderer. */
public final class EnderChestLayout {
    private final int width;
    private final int height;
    private final int startX;
    private final int startY;
    private final int stepX;
    private final int stepY;
    private final int slotSize;
    private final int itemSize;
    private final int freshnessX;
    private final int freshnessY;

    public EnderChestLayout(
        int width,
        int height,
        int startX,
        int startY,
        int stepX,
        int stepY,
        int slotSize,
        int itemSize,
        int freshnessX,
        int freshnessY
    ) {
        this.width = positive(width, "width");
        this.height = positive(height, "height");
        this.startX = nonNegative(startX, "startX");
        this.startY = nonNegative(startY, "startY");
        this.stepX = positive(stepX, "stepX");
        this.stepY = positive(stepY, "stepY");
        this.slotSize = positive(slotSize, "slotSize");
        this.itemSize = positive(itemSize, "itemSize");
        this.freshnessX = nonNegative(freshnessX, "freshnessX");
        this.freshnessY = nonNegative(freshnessY, "freshnessY");
        if (itemSize > slotSize) throw new IllegalArgumentException("itemSize exceeds slotSize");
        if (startX + stepX * 8 + slotSize > width) {
            throw new IllegalArgumentException("Ender Chest columns exceed canvas width");
        }
        if (startY + stepY * 2 + slotSize > height) {
            throw new IllegalArgumentException("Ender Chest rows exceed canvas height");
        }
        if (freshnessX >= width || freshnessY >= height) {
            throw new IllegalArgumentException("Ender Chest freshness label starts outside canvas");
        }
    }

    static EnderChestLayout legacy() {
        return new EnderChestLayout(704, 308, 28, 68, 72, 72, 72, 64, 8, 8);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getStartX() { return startX; }
    public int getStartY() { return startY; }
    public int getStepX() { return stepX; }
    public int getStepY() { return stepY; }
    public int getSlotSize() { return slotSize; }
    public int getItemSize() { return itemSize; }
    public int getFreshnessX() { return freshnessX; }
    public int getFreshnessY() { return freshnessY; }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
