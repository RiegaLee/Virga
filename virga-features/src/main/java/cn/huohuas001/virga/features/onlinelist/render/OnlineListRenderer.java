package cn.huohuas001.virga.features.onlinelist.render;

import cn.huohuas001.virga.features.onlinelist.model.PlayerSnapshot;
import cn.huohuas001.virga.features.onlinelist.model.ServerSnapshot;
import cn.huohuas001.virga.features.onlinelist.skin.AvatarCache;
import cn.huohuas001.virga.features.render.BackdropLibrary;
import cn.huohuas001.virga.features.render.BundledFonts;
import cn.huohuas001.virga.features.render.Glass;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在线列表：背景（内置或服主自定义）按图片高度完整铺满，先整体盖一层遮罩。顶部居中写标题，
 * 下面是一整块毛玻璃面板：第一行左边是服务器名，右边是在线人数与更新时间；其下是玩家卡片网格，
 * 与第一行左右对齐；面板底部是页脚与页码。文字用白色加淡阴影。高度按玩家数向下扩展。
 */
public final class OnlineListRenderer {
    public static final int WIDTH = 1200;
    public static final int HEADER_HEIGHT = 384;
    public static final int BODY_TILE_HEIGHT = 640;
    public static final int FOOTER_HEIGHT = 256;

    /** The panel that holds everything below the title; its inner edge is the content edge. */
    private static final int PANEL_X = 60;
    private static final int PANEL_TOP = 196;
    private static final int PANEL_BOTTOM_MARGIN = 60;
    private static final int PANEL_ARC = 56;
    private static final int PANEL_PADDING = 44;
    private static final int CONTENT_X = PANEL_X + PANEL_PADDING;
    private static final int CONTENT_WIDTH = WIDTH - CONTENT_X * 2;
    private static final int GRID_TOP = PANEL_TOP + 154;

    static final int COMPACT_CARD_ARC = 40;
    static final int REGULAR_CARD_ARC = 48;
    static final int COMPACT_AVATAR_ARC = 24;
    static final int REGULAR_AVATAR_ARC = 30;
    static final int EMPTY_CARD_ARC = 48;

    // White text over dark translucent glass (see Glass).
    private static final Color TEXT_PRIMARY = new Color(255, 255, 255);
    private static final Color TEXT_SECONDARY = new Color(255, 255, 255, 190);
    private static final Color TITLE = new Color(255, 255, 255);
    private static final Color ONLINE_DOT = new Color(111, 226, 180);
    private static final Color ADMIN_TEXT = new Color(214, 206, 255);
    private static final Color AVATAR_BORDER = new Color(255, 255, 255, 150);
    /** Cards sit on the panel's glass: a little white and a light edge, no second frosting. */
    private static final Color INNER_CARD = new Color(255, 255, 255, 24);

    /** Rounded outer corners of the whole image, like the status card. */
    private static final int CORNER_ARC = 60;
    private static final int[][] DEFAULT_STEVE_FACE = {
        {0xFF332411, 0xFF332411, 0xFF3F2A15, 0xFF3F2A15, 0xFF3F2A15, 0xFF3F2A15, 0xFF332411, 0xFF2B1E0D},
        {0xFF241808, 0xFF332411, 0xFF332411, 0xFF3F2A15, 0xFF3F2A15, 0xFF332411, 0xFF3F2A15, 0xFF332411},
        {0xFF2B1E0D, 0xFF9B6349, 0xFFB3795E, 0xFFB7836B, 0xFFB3795E, 0xFFAA7259, 0xFF9B6349, 0xFF342512},
        {0xFF9B6349, 0xFFAA7259, 0xFFB3795E, 0xFFB3795E, 0xFFAA7259, 0xFFAA7259, 0xFFAA7259, 0xFF9B6349},
        {0xFFAA7259, 0xFFFFFFFF, 0xFF523D89, 0xFFAA7259, 0xFF9B6349, 0xFF523D89, 0xFFFFFFFF, 0xFFAA7259},
        {0xFF9B6349, 0xFFAA7259, 0xFFAA7259, 0xFF6A4030, 0xFF6A4030, 0xFFAA7259, 0xFFAA7259, 0xFF9B6349},
        {0xFF90593F, 0xFF8F5E3E, 0xFF492510, 0xFF774235, 0xFF774235, 0xFF421D0A, 0xFF8F5E3E, 0xFF815339},
        {0xFF94603E, 0xFF815339, 0xFF421D0A, 0xFF492510, 0xFF421D0A, 0xFF492510, 0xFF815339, 0xFF8F5E3E}
    };
    private static final BufferedImage DEFAULT_STEVE_HEAD = createDefaultSteveHead();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"));

    private final BackdropLibrary backdrops;
    private final AvatarCache avatarCache;
    private final int columns;
    private final int cardGapX;
    private final int cardGapY;
    private final int cardHeight;
    private final int avatarSize;
    private final int contentBottomSafe;
    private final String fontFamily;
    private final String footerText;
    private final boolean useInitialFallback;

    public OnlineListRenderer(AvatarCache avatarCache, int columns, String configuredFont, String footerText) throws IOException {
        this(avatarCache, columns, configuredFont, footerText, "steve");
    }

    public OnlineListRenderer(
        AvatarCache avatarCache,
        int columns,
        String configuredFont,
        String footerText,
        String fallbackAvatar
    ) throws IOException {
        this(BackdropLibrary.bundled(), avatarCache, columns, configuredFont, footerText, fallbackAvatar);
    }

    /** @param backdrops supplies the background picture (bundled or the admin's own) */
    public OnlineListRenderer(
        BackdropLibrary backdrops,
        AvatarCache avatarCache,
        int columns,
        String configuredFont,
        String footerText,
        String fallbackAvatar
    ) {
        this.backdrops = backdrops;
        this.avatarCache = avatarCache;
        this.columns = Math.max(1, Math.min(3, columns));
        boolean compact = this.columns == 3;
        this.cardGapX = compact ? 18 : 28;
        this.cardGapY = compact ? 16 : 22;
        this.cardHeight = compact ? 96 : 118;
        this.avatarSize = compact ? 60 : 76;
        this.contentBottomSafe = compact ? 60 : 86;
        this.fontFamily = chooseFont(configuredFont);
        this.footerText = footerText == null ? "" : footerText.trim();
        this.useInitialFallback = "initial".equalsIgnoreCase(fallbackAvatar == null ? "" : fallbackAvatar.trim());
    }

    public byte[] render(ServerSnapshot snapshot) throws IOException {
        Map<String, BufferedImage> avatars = avatarCache.loadAll(snapshot.getPlayers());
        BufferedImage image = renderImage(snapshot, avatars);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer is unavailable");
            return output.toByteArray();
        }
    }

    BufferedImage renderImage(ServerSnapshot snapshot, Map<String, BufferedImage> avatars) {
        int displayedPlayers = snapshot.getPlayers().size();
        int rows = displayedPlayers == 0 ? 0 : (displayedPlayers + columns - 1) / columns;
        int gridHeight = rows == 0 ? 0 : rows * cardHeight + (rows - 1) * cardGapY;
        int contentBottom = rows == 0 ? GRID_TOP + 210 : GRID_TOP + gridHeight;
        int bodyTiles = Math.max(1, divideRoundUp(contentBottom + contentBottomSafe - HEADER_HEIGHT, BODY_TILE_HEIGHT));
        if (snapshot.getTotalPages() > 1) bodyTiles = Math.max(2, bodyTiles);
        int footerTop = HEADER_HEIGHT + bodyTiles * BODY_TILE_HEIGHT;
        int height = footerTop + FOOTER_HEIGHT;

        BufferedImage result = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            configure(graphics);
            BufferedImage frosted = drawBackground(graphics, height);
            int panelBottom = height - PANEL_BOTTOM_MARGIN;
            Glass.pane(graphics, frosted,
                new RoundRectangle2D.Float(PANEL_X, PANEL_TOP, WIDTH - PANEL_X * 2, panelBottom - PANEL_TOP, PANEL_ARC, PANEL_ARC),
                Glass.PANE, Glass.EDGE, 1.6f);
            drawHeader(graphics, snapshot);
            if (rows == 0) {
                drawEmptyState(graphics);
            } else {
                drawPlayers(graphics, snapshot, avatars);
            }
            drawFooter(graphics, panelBottom, snapshot);
        } finally {
            graphics.dispose();
        }
        return roundCorners(result);
    }

    /** Cuts the outer corners with an anti-aliased rounded mask (transparent outside). */
    private static BufferedImage roundCorners(BufferedImage image) {
        BufferedImage rounded = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = rounded.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fill(new RoundRectangle2D.Float(0, 0, image.getWidth(), image.getHeight(), CORNER_ARC, CORNER_ARC));
            graphics.setComposite(java.awt.AlphaComposite.SrcIn);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rounded;
    }

    /** Full background under one veil. Returns the frosted (un-veiled) picture used inside the glass. */
    private BufferedImage drawBackground(Graphics2D graphics, int height) {
        BufferedImage background = backdrops.background(BackdropLibrary.Surface.ONLINE_LIST, WIDTH, height);
        BufferedImage frosted = BackdropLibrary.frost(background);
        graphics.drawImage(background, 0, 0, null);
        graphics.setColor(Glass.VEIL);
        graphics.fillRect(0, 0, WIDTH, height);
        return frosted;
    }

    private void drawHeader(Graphics2D graphics, ServerSnapshot snapshot) {
        graphics.setFont(font(Font.BOLD, 72));
        drawCentered(graphics, "在线列表", 136, TITLE);

        // First panel row: server name on the left, counts on the right, both on the grid's edges.
        int right = CONTENT_X + CONTENT_WIDTH;
        String maximum = snapshot.getMaxPlayers() > 0 ? Integer.toString(snapshot.getMaxPlayers()) : "--";
        String count = snapshot.getOnlinePlayers() + " / " + maximum;
        graphics.setFont(font(Font.BOLD, 40));
        int countWidth = Glass.width(graphics, count);
        drawRight(graphics, count, right, PANEL_TOP + 80, TEXT_PRIMARY);
        graphics.setFont(font(Font.BOLD, 34));
        graphics.setColor(TEXT_PRIMARY);
        Glass.text(graphics, truncate(graphics, snapshot.getServerName(), CONTENT_WIDTH - countWidth - 48), CONTENT_X, PANEL_TOP + 80);

        graphics.setFont(font(Font.PLAIN, 22));
        graphics.setColor(TEXT_SECONDARY);
        Glass.text(graphics, "当前在线玩家", CONTENT_X, PANEL_TOP + 118);
        drawRight(graphics, "更新 " + TIME.format(snapshot.getCapturedAt()), right, PANEL_TOP + 118, TEXT_SECONDARY);
    }

    private void drawPlayers(
        Graphics2D graphics,
        ServerSnapshot snapshot,
        Map<String, BufferedImage> avatars
    ) {
        int cardWidth = (CONTENT_WIDTH - (columns - 1) * cardGapX) / columns;
        for (int index = 0; index < snapshot.getPlayers().size(); index++) {
            PlayerSnapshot player = snapshot.getPlayers().get(index);
            int column = index % columns;
            int row = index / columns;
            int x = CONTENT_X + column * (cardWidth + cardGapX);
            int y = GRID_TOP + row * (cardHeight + cardGapY);
            drawPlayerCard(graphics, x, y, cardWidth, player, avatars.get(player.getUuid()));
        }
    }

    private void drawPlayerCard(
        Graphics2D graphics,
        int x,
        int y,
        int width,
        PlayerSnapshot player,
        BufferedImage avatar
    ) {
        int cardArc = columns == 3 ? COMPACT_CARD_ARC : REGULAR_CARD_ARC;
        RoundRectangle2D card = new RoundRectangle2D.Float(x, y, width, cardHeight, cardArc, cardArc);
        innerCard(graphics, card);

        int avatarX = x + (columns == 3 ? 16 : 21);
        int avatarY = y + (cardHeight - avatarSize) / 2;
        int avatarArc = columns == 3 ? COMPACT_AVATAR_ARC : REGULAR_AVATAR_ARC;
        RoundRectangle2D avatarShape = new RoundRectangle2D.Float(avatarX, avatarY, avatarSize, avatarSize, avatarArc, avatarArc);
        Shape previousClip = graphics.getClip();
        graphics.clip(avatarShape);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (avatar == null) {
            drawFallbackAvatar(graphics, avatarX, avatarY, player);
        } else {
            graphics.drawImage(avatar, avatarX, avatarY, avatarSize, avatarSize, null);
        }
        graphics.setClip(previousClip);
        graphics.setColor(AVATAR_BORDER);
        graphics.setStroke(new BasicStroke(2f));
        graphics.draw(avatarShape);

        graphics.setColor(ONLINE_DOT);
        int dotSize = columns == 3 ? 10 : 13;
        graphics.fillOval(avatarX + avatarSize - dotSize, avatarY + avatarSize - dotSize, dotSize, dotSize);

        int textX = avatarX + avatarSize + (columns == 3 ? 13 : 19);
        graphics.setFont(font(Font.BOLD, columns == 3 ? 22 : 28));
        graphics.setColor(TEXT_PRIMARY);
        String name = truncate(graphics, player.getName(), x + width - textX - 18);
        Glass.text(graphics, name, textX, y + (columns == 3 ? 50 : 69));

        graphics.setFont(font(Font.PLAIN, columns == 3 ? 15 : 18));
        graphics.setColor(player.isAdministrator() ? ADMIN_TEXT : TEXT_SECONDARY);
        Glass.text(graphics, player.isAdministrator() ? "ADMIN" : "ONLINE", textX, y + (columns == 3 ? 73 : 94));
    }

    private void drawFallbackAvatar(Graphics2D graphics, int x, int y, PlayerSnapshot player) {
        if (!useInitialFallback) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(DEFAULT_STEVE_HEAD, x, y, x + avatarSize, y + avatarSize, 0, 0, 8, 8, null);
            return;
        }
        int hue = Math.abs(player.getUuid().hashCode()) % 360;
        Color base = Color.getHSBColor(hue / 360f, 0.22f, 0.86f);
        graphics.setColor(base);
        graphics.fillRect(x, y, avatarSize, avatarSize);
        graphics.setColor(new Color(255, 255, 255, 210));
        graphics.setFont(font(Font.BOLD, columns == 3 ? 27 : 34));
        String initial = player.getName().isEmpty() ? "?" : player.getName().substring(0, 1).toUpperCase();
        Glass.text(graphics, initial, x + (avatarSize - Glass.width(graphics, initial)) / 2, y + (columns == 3 ? 40 : 50));
    }

    static BufferedImage createDefaultSteveHead() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < DEFAULT_STEVE_FACE.length; y++) {
            for (int x = 0; x < DEFAULT_STEVE_FACE[y].length; x++) {
                image.setRGB(x, y, DEFAULT_STEVE_FACE[y][x]);
            }
        }
        return image;
    }

    private void drawEmptyState(Graphics2D graphics) {
        int y = GRID_TOP + 25;
        int height = 160;
        innerCard(graphics, new RoundRectangle2D.Float(CONTENT_X, y, CONTENT_WIDTH, height, EMPTY_CARD_ARC, EMPTY_CARD_ARC));

        graphics.setFont(font(Font.BOLD, 30));
        String title = "服务器里空荡荡的";
        // Vertically centred in the card: baseline = middle + half the cap height.
        drawCentered(graphics, title, y + height / 2 + graphics.getFontMetrics().getAscent() * 7 / 20, TEXT_PRIMARY);
    }

    private static void innerCard(Graphics2D graphics, Shape card) {
        graphics.setColor(INNER_CARD);
        graphics.fill(card);
        graphics.setStroke(new BasicStroke(1.4f));
        graphics.setColor(Glass.EDGE);
        graphics.draw(card);
    }

    /** Footer text and the page number, at the bottom of the panel. */
    private void drawFooter(Graphics2D graphics, int panelBottom, ServerSnapshot snapshot) {
        graphics.setFont(font(Font.BOLD, 24));
        drawCentered(graphics, "第 " + snapshot.getCurrentPage() + " / " + snapshot.getTotalPages() + " 页",
            panelBottom - 44, TEXT_PRIMARY);
        if (footerText.isEmpty()) return;
        graphics.setFont(font(Font.PLAIN, 20));
        // A watermark line: always upper case, e.g. POWERED BY VIRGA.
        drawCentered(graphics, footerText.toUpperCase(Locale.ROOT), panelBottom - 100, TEXT_SECONDARY);
    }

    /** The configured system font when set, otherwise the bundled MiSans. */
    private Font font(int style, int size) {
        return fontFamily == null ? BundledFonts.font(style, size) : new Font(fontFamily, style, size);
    }

    private static String truncate(Graphics2D graphics, String text, int maxWidth) {
        if (Glass.width(graphics, text) <= maxWidth) return text;
        int end = text.length();
        while (end > 1 && Glass.width(graphics, text.substring(0, end) + "…") > maxWidth) end--;
        return text.substring(0, end) + "…";
    }

    private static void drawRight(Graphics2D graphics, String text, int right, int baseline, Color color) {
        graphics.setColor(color);
        Glass.text(graphics, text, right - Glass.width(graphics, text), baseline);
    }

    private static void drawCentered(Graphics2D graphics, String text, int baseline, Color color) {
        graphics.setColor(color);
        Glass.text(graphics, text, (WIDTH - Glass.width(graphics, text)) / 2, baseline);
    }

    private static void drawCentered(Graphics2D graphics, String text, int centerX, int baseline, Color color) {
        graphics.setColor(color);
        Glass.text(graphics, text, centerX - Glass.width(graphics, text) / 2, baseline);
    }

    private static int divideRoundUp(int value, int divisor) {
        return Math.max(0, (value + divisor - 1) / divisor);
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static String chooseFont(String configured) {
        Set<String> available = new HashSet<String>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()
        ));
        if (configured != null && !configured.trim().isEmpty() && available.contains(configured.trim())) {
            return configured.trim();
        }
        // No font configured: the bundled MiSans (null), the same on every system.
        if (BundledFonts.available()) return null;
        String[] candidates = {"Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Micro Hei", "SansSerif"};
        for (String candidate : candidates) if (available.contains(candidate)) return candidate;
        return Font.SANS_SERIF;
    }

}
