package cn.huohuas001.virga.features.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.text.AttributedString;

/** Translucent glass panes over a picture, and text that stays readable on top of them. */
public final class Glass {
    /**
     * One veil over the whole picture first: it mutes any background evenly, so the light glass on
     * top never looks pasted on and white text reads everywhere.
     */
    public static final Color VEIL = new Color(34, 36, 50, 84);
    /** Light glass: frosted picture, the same veil, then a little white, so a card is only slightly brighter. */
    public static final Color PANE = new Color(255, 255, 255, 34);
    public static final Color CARD = new Color(255, 255, 255, 28);
    public static final Color EDGE = new Color(255, 255, 255, 92);
    private static final Color TEXT_SHADOW = new Color(0, 0, 0, 72);

    private Glass() {}

    /**
     * Draws [shape] as frosted glass: the blurred picture inside the shape, a translucent tint and
     * a thin light edge. [frosted] must be the blurred version of the picture underneath.
     */
    public static void pane(Graphics2D graphics, BufferedImage frosted, Shape shape, Color tint, Color edge, float edgeWidth) {
        Shape clip = graphics.getClip();
        graphics.clip(shape);
        if (frosted != null) graphics.drawImage(frosted, 0, 0, null);
        graphics.setColor(VEIL);
        graphics.fill(shape);
        graphics.setColor(tint);
        graphics.fill(shape);
        graphics.setClip(clip);
        if (edge != null && edgeWidth > 0) {
            graphics.setStroke(new BasicStroke(edgeWidth));
            graphics.setColor(edge);
            graphics.draw(shape);
        }
    }

    /** Text in the current color with a soft shadow underneath. */
    public static void text(Graphics2D graphics, String text, int x, int y) {
        Color color = graphics.getColor();
        graphics.setColor(new Color(0, 0, 0, Math.min(TEXT_SHADOW.getAlpha(), color.getAlpha())));
        draw(graphics, text, x + 1, y + 2);
        graphics.setColor(color);
        draw(graphics, text, x, y);
    }

    /** Advance width of [text] as {@link #text} draws it, fallback characters included. */
    public static int width(Graphics2D graphics, String text) {
        if (text.isEmpty()) return 0;
        Font font = graphics.getFont();
        if (font.canDisplayUpTo(text) < 0) return graphics.getFontMetrics().stringWidth(text);
        return Math.round(new TextLayout(withFallback(text, font).getIterator(), graphics.getFontRenderContext()).getAdvance());
    }

    /**
     * The bundled fonts only hold the characters Virga's own texts use; anything else (a server
     * name, a custom footer) is drawn with the system's sans-serif font instead of empty boxes.
     */
    private static void draw(Graphics2D graphics, String text, int x, int y) {
        Font font = graphics.getFont();
        if (text.isEmpty() || font.canDisplayUpTo(text) < 0) {
            graphics.drawString(text, x, y);
        } else {
            graphics.drawString(withFallback(text, font).getIterator(), x, y);
        }
    }

    private static AttributedString withFallback(String text, Font font) {
        // The bundled Semibold face is a plain-styled font; its fallback should still be bold.
        boolean bold = font.isBold() || font.getFontName().toLowerCase(java.util.Locale.ROOT).contains("semibold");
        Font fallback = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, font.getSize()).deriveFont(font.getSize2D());
        AttributedString attributed = new AttributedString(text);
        int start = 0;
        while (start < text.length()) {
            boolean own = font.canDisplay(text.codePointAt(start));
            int end = start;
            while (end < text.length() && font.canDisplay(text.codePointAt(end)) == own) {
                end += Character.charCount(text.codePointAt(end));
            }
            attributed.addAttribute(TextAttribute.FONT, own ? font : fallback, start, end);
            start = end;
        }
        return attributed;
    }
}
