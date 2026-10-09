package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Compact 9x3 renderer used only for a player's private Ender Chest contents. */
public final class EnderChestRenderer implements InventoryRenderer {
    static final int ITEM_COUNT_FONT_SIZE = 26;
    public static final int WIDTH = 704;
    public static final int HEIGHT = 308;
    private static final Color COUNT = new Color(250, 250, 250);
    private static final Color SHADOW = new Color(0, 0, 0, 210);

    private final Theme theme;
    private final EnderChestLayout layout;
    private final BufferedImage background;
    private final Supplier<BufferedImage> backdrop;

    public EnderChestRenderer(Theme theme, Path backgroundPath) {
        this(theme, backgroundPath, null);
    }

    /**
     * @param backdrop replaces the theme's Ender Chest background (layered Virga backdrop); ignored when
     *                 null or when its size does not match the layout
     */
    public EnderChestRenderer(Theme theme, Path backgroundPath, Supplier<BufferedImage> backdrop) {
        this(theme, loadBackground(backgroundPath, theme.getEnderChestLayout()), backdrop);
    }

    EnderChestRenderer(Theme theme, BufferedImage background) {
        this(theme, background, null);
    }

    private EnderChestRenderer(Theme theme, BufferedImage background, Supplier<BufferedImage> backdrop) {
        this.theme = Objects.requireNonNull(theme, "theme");
        this.layout = theme.getEnderChestLayout();
        this.background = Objects.requireNonNull(background, "background");
        this.backdrop = backdrop;
        if (background.getWidth() != layout.getWidth() || background.getHeight() != layout.getHeight()) {
            throw new IllegalArgumentException(
                "Ender Chest background must be " + layout.getWidth() + "x" + layout.getHeight()
            );
        }
    }

    @Override
    public RenderResult render(InventorySnapshot snapshot) {
        return render(snapshot, null, InventoryRenderMetadata.realtime(snapshot.getCapturedAt()));
    }

    @Override
    public RenderResult render(
        InventorySnapshot snapshot,
        BufferedImage ignoredPlayerPreview,
        InventoryRenderMetadata metadata
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        BufferedImage canvas = new BufferedImage(
            layout.getWidth(), layout.getHeight(), BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = canvas.createGraphics();
        try {
            configure(graphics);
            BufferedImage replacement = backdrop == null ? null : backdrop.get();
            boolean fits = replacement != null
                && replacement.getWidth() == layout.getWidth() && replacement.getHeight() == layout.getHeight();
            graphics.drawImage(fits ? replacement : background, 0, 0, null);
            // Freshness stays in snapshot metadata, consistent with the inventory renderer.
            for (InventorySlot slot : snapshot.getStorage()) drawSlot(graphics, slot);
        } finally {
            graphics.dispose();
        }
        return encode(canvas);
    }

    private void drawSlot(Graphics2D graphics, InventorySlot slot) {
        ItemSnapshot item = slot.getItem();
        if (item == null) return;
        if (slot.getIndex() < 0 || slot.getIndex() >= 27) return;
        int column = slot.getIndex() % 9;
        int row = slot.getIndex() / 9;
        int slotX = layout.getStartX() + column * layout.getStepX();
        int slotY = layout.getStartY() + row * layout.getStepY();
        int itemX = slotX + (layout.getSlotSize() - layout.getItemSize()) / 2;
        int itemY = slotY + (layout.getSlotSize() - layout.getItemSize()) / 2;
        TextureResolver.ResolvedTexture texture = theme.getTextures().resolve(item);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.drawImage(
            texture.getImage(), itemX, itemY, layout.getItemSize(), layout.getItemSize(), null
        );
        if (item.hasEnchantmentGlint()) {
            graphics.setColor(new Color(130, 95, 255, 150));
            graphics.setStroke(new BasicStroke(3f));
            graphics.drawRoundRect(
                itemX + 1,
                itemY + 1,
                layout.getItemSize() - 2,
                layout.getItemSize() - 2,
                8,
                8
            );
        }
        if (item.getAmount() > 1) drawAmount(graphics, slotX, slotY, item.getAmount());
        if (item.getMaxDamage() > 0) drawDurability(graphics, slotX, slotY, item);
    }

    private void drawAmount(Graphics2D graphics, int slotX, int slotY, int amount) {
        String text = Integer.toString(amount);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, ITEM_COUNT_FONT_SIZE));
        FontMetrics metrics = graphics.getFontMetrics();
        int x = slotX + layout.getSlotSize() - 4 - metrics.stringWidth(text);
        int y = slotY + layout.getSlotSize() - 4;
        graphics.setColor(SHADOW);
        graphics.drawString(text, x + 2, y + 2);
        graphics.setColor(COUNT);
        graphics.drawString(text, x, y);
    }

    private void drawDurability(Graphics2D graphics, int slotX, int slotY, ItemSnapshot item) {
        double remaining = 1.0d - (double) item.getDamage() / (double) item.getMaxDamage();
        remaining = Math.max(0.0d, Math.min(1.0d, remaining));
        int x = slotX + 8;
        int y = slotY + layout.getSlotSize() - 8;
        int width = layout.getSlotSize() - 16;
        graphics.setColor(new Color(18, 18, 18, 230));
        graphics.fillRect(x, y, width, 4);
        graphics.setColor(remaining > 0.5d ? new Color(66, 200, 221) : new Color(238, 177, 47));
        graphics.fillRect(x, y, (int) Math.round(width * remaining), 4);
    }

    private void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            theme.isNearestNeighborTextures()
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR
        );
    }

    private static BufferedImage loadBackground(Path path, EnderChestLayout layout) {
        Objects.requireNonNull(path, "backgroundPath");
        if (Files.isRegularFile(path)) {
            try {
                BufferedImage image = ImageIO.read(path.toFile());
                if (image != null) return image;
            } catch (Exception error) {
                throw new IllegalArgumentException("Could not read Ender Chest background " + path, error);
            }
        }
        return neutralBackground(layout);
    }

    private static BufferedImage neutralBackground(EnderChestLayout layout) {
        BufferedImage image = new BufferedImage(
            layout.getWidth(), layout.getHeight(), BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(194, 194, 194));
            graphics.fillRoundRect(0, 0, layout.getWidth(), layout.getHeight(), 18, 18);
            for (int index = 0; index < 27; index++) {
                int x = layout.getStartX() + index % 9 * layout.getStepX();
                int y = layout.getStartY() + index / 9 * layout.getStepY();
                graphics.setColor(new Color(142, 142, 142));
                graphics.fillRect(x, y, layout.getSlotSize(), layout.getSlotSize());
                graphics.setColor(new Color(71, 71, 71));
                graphics.drawRect(x, y, layout.getSlotSize() - 1, layout.getSlotSize() - 1);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static RenderResult encode(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(96 * 1024);
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("No PNG writer is available");
            return new RenderResult(output.toByteArray(), "image/png", image.getWidth(), image.getHeight());
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode Ender Chest PNG", error);
        }
    }
}
