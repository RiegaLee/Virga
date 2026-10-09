package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.SlotType;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully validated, data-loaded pixel layout. */
public final class Layout {
    private final int width;
    private final int height;
    private final int slotSize;
    private final int itemSize;
    private final Point title;
    private final Grid storage;
    private final Grid hotbar;
    private final Map<SlotType, Point> equipment;
    private final Rectangle playerPreview;
    private final Rectangle freshness;
    private final Point quantityOffset;
    private final Rectangle durability;

    public Layout(
        int width,
        int height,
        int slotSize,
        int itemSize,
        Point title,
        Grid storage,
        Grid hotbar,
        Map<SlotType, Point> equipment,
        Rectangle playerPreview,
        Rectangle freshness,
        Point quantityOffset,
        Rectangle durability
    ) {
        if (width < 1 || height < 1 || (long) width * height > 16_000_000L) {
            throw new IllegalArgumentException("canvas dimensions are invalid or too large");
        }
        if (slotSize < 16 || itemSize < 1 || itemSize > slotSize) {
            throw new IllegalArgumentException("slot/item size is invalid");
        }
        this.width = width;
        this.height = height;
        this.slotSize = slotSize;
        this.itemSize = itemSize;
        this.title = copy(Objects.requireNonNull(title, "title"));
        this.storage = Objects.requireNonNull(storage, "storage");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        if (storage.getColumns() != 9 || storage.getRows() != 3) {
            throw new IllegalArgumentException("storage layout must be 9x3");
        }
        if (hotbar.getColumns() != 9 || hotbar.getRows() != 1) {
            throw new IllegalArgumentException("hotbar layout must be 9x1");
        }
        EnumMap<SlotType, Point> points = new EnumMap<SlotType, Point>(SlotType.class);
        points.putAll(Objects.requireNonNull(equipment, "equipment"));
        for (SlotType type : new SlotType[] {
            SlotType.ARMOR_HEAD,
            SlotType.ARMOR_CHEST,
            SlotType.ARMOR_LEGS,
            SlotType.ARMOR_FEET,
            SlotType.OFFHAND
        }) {
            if (!points.containsKey(type)) throw new IllegalArgumentException("missing layout for " + type);
            points.put(type, copy(points.get(type)));
        }
        this.equipment = points;
        this.playerPreview = playerPreview == null ? null : new Rectangle(playerPreview);
        this.freshness = freshness == null ? null : new Rectangle(freshness);
        this.quantityOffset = copy(Objects.requireNonNull(quantityOffset, "quantityOffset"));
        this.durability = new Rectangle(Objects.requireNonNull(durability, "durability"));
        validateAllSlots();
    }

    public Rectangle slotBounds(InventorySlot slot) {
        Objects.requireNonNull(slot, "slot");
        switch (slot.getSlotType()) {
            case STORAGE:
                return storage.bounds(slot.getIndex(), slotSize);
            case HOTBAR:
                return hotbar.bounds(slot.getIndex(), slotSize);
            default:
                Point point = equipment.get(slot.getSlotType());
                if (point == null || slot.getIndex() != 0) {
                    throw new IllegalArgumentException("No equipment layout for " + slot.getSlotType());
                }
                return new Rectangle(point.x, point.y, slotSize, slotSize);
        }
    }

    private void validateAllSlots() {
        Set<String> occupied = new HashSet<String>();
        for (int i = 0; i < 27; i++) validateBounds(storage.bounds(i, slotSize), occupied);
        for (int i = 0; i < 9; i++) validateBounds(hotbar.bounds(i, slotSize), occupied);
        for (Point point : equipment.values()) {
            validateBounds(new Rectangle(point.x, point.y, slotSize, slotSize), occupied);
        }
        if (playerPreview != null) validateBounds(playerPreview, occupied);
        if (freshness != null && (freshness.x < 0 || freshness.y < 0 ||
            freshness.x + freshness.width > width || freshness.y + freshness.height > height)) {
            throw new IllegalArgumentException("freshness area lies outside the canvas: " + freshness);
        }
        if (title.x < 0 || title.x >= width || title.y < 0 || title.y >= height) {
            throw new IllegalArgumentException("title anchor lies outside the canvas");
        }
        if (quantityOffset.x < 0 || quantityOffset.y < 0 ||
            quantityOffset.x > slotSize || quantityOffset.y > slotSize) {
            throw new IllegalArgumentException("quantity anchor lies outside a slot");
        }
        if (durability.x < 0 || durability.y < 0 || durability.width < 1 || durability.height < 1 ||
            durability.x + durability.width > slotSize || durability.y + durability.height > slotSize) {
            throw new IllegalArgumentException("durability bar lies outside a slot");
        }
    }

    private void validateBounds(Rectangle rectangle, Set<String> occupied) {
        if (rectangle.x < 0 || rectangle.y < 0 ||
            rectangle.x + rectangle.width > width || rectangle.y + rectangle.height > height) {
            throw new IllegalArgumentException("slot lies outside the canvas: " + rectangle);
        }
        for (int y = rectangle.y; y < rectangle.y + rectangle.height; y++) {
            for (int x = rectangle.x; x < rectangle.x + rectangle.width; x++) {
                String key = x + ":" + y;
                if (!occupied.add(key)) throw new IllegalArgumentException("slot layouts overlap at " + key);
            }
        }
    }

    private static Point copy(Point point) {
        return new Point(point.x, point.y);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getSlotSize() { return slotSize; }
    public int getItemSize() { return itemSize; }
    public Point getTitle() { return copy(title); }
    public Rectangle getPlayerPreview() { return playerPreview == null ? null : new Rectangle(playerPreview); }
    public Rectangle getFreshness() { return freshness == null ? null : new Rectangle(freshness); }
    public Point getQuantityOffset() { return copy(quantityOffset); }
    public Rectangle getDurability() { return new Rectangle(durability); }

    public static final class Grid {
        private final int startX;
        private final int startY;
        private final int columns;
        private final int rows;
        private final int stepX;
        private final int stepY;

        public Grid(int startX, int startY, int columns, int rows, int stepX, int stepY) {
            if (startX < 0 || startY < 0 || columns < 1 || rows < 1 || stepX < 1 || stepY < 1) {
                throw new IllegalArgumentException("grid values must be positive");
            }
            this.startX = startX;
            this.startY = startY;
            this.columns = columns;
            this.rows = rows;
            this.stepX = stepX;
            this.stepY = stepY;
        }

        Rectangle bounds(int index, int slotSize) {
            if (index < 0 || index >= columns * rows) throw new IllegalArgumentException("grid index is out of range");
            int column = index % columns;
            int row = index / columns;
            return new Rectangle(startX + column * stepX, startY + row * stepY, slotSize, slotSize);
        }

        int getColumns() { return columns; }
        int getRows() { return rows; }
    }
}
