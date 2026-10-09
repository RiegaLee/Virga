package cn.huohuas001.virga.features.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * MiSans subsets shipped in the JAR (see fonts/NOTICE.txt), so the status card and
 * the online list look the same on every system. They only hold the characters those images
 * use; {@link Glass#text} draws anything else with a system font.
 *
 * MiSans © Xiaomi, free for commercial use and embedding with attribution.
 */
public final class BundledFonts {
    private static final Font REGULAR = load("fonts/MiSans-Regular.subset.ttf");
    private static final Font SEMIBOLD = load("fonts/MiSans-Semibold.subset.ttf");

    private BundledFonts() {}

    /** False when the font resources could not be read; callers then use system fonts. */
    public static boolean available() {
        return REGULAR != null && SEMIBOLD != null;
    }

    /** Semibold for {@link Font#BOLD}, Regular otherwise. Requires {@link #available()}. */
    public static Font font(int style, float size) {
        Font base = (style & Font.BOLD) != 0 ? SEMIBOLD : REGULAR;
        return base.deriveFont(Font.PLAIN, size);
    }

    private static Font load(String resource) {
        try (InputStream input = BundledFonts.class.getClassLoader().getResourceAsStream(resource)) {
            return input == null ? null : Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (IOException | FontFormatException | RuntimeException error) {
            return null;
        }
    }
}
