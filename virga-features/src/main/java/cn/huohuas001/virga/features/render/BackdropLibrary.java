package cn.huohuas001.virga.features.render;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds every image backdrop from independent transparent layers, bottom to top:
 *
 * <ol>
 *   <li><b>background</b> – the bundled scene, or the admin's own picture from
 *       {@code config/virga/backgrounds/<surface>.png|jpg}; scaled to cover the canvas and cut by
 *       the surface mask so it only shows where the scene belongs;</li>
 *   <li><b>glass</b> – optional: where its alpha is opaque the background is frosted (blurred) under
 *       the translucent slots, so items stay readable on any picture;</li>
 *   <li><b>veil</b> – an optional soft cream layer over a custom picture so text stays readable;</li>
 *   <li><b>ui</b> – fixed panels, slots and cards (transparent elsewhere).</li>
 * </ol>
 *
 * Layers are only scaled and alpha-composited: nothing is cut out of a finished picture.
 * Only the composited result is cached, until the custom file or the options change.
 */
public final class BackdropLibrary {
    /** Rendered surfaces and their fixed canvas sizes (renderers draw text at fixed coordinates). */
    public enum Surface {
        ONLINE_LIST("online-list", 1792, 1008),
        STATUS("status", 1792, 1008),
        INVENTORY("inventory", 1359, 1017),
        ENDER_CHEST("ender-chest", 1620, 694);

        private final String id;
        private final int width;
        private final int height;

        Surface(String id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }

        public String id() { return id; }
        public int width() { return width; }
        public int height() { return height; }
    }

    /** Admin options; veil is 0..80 percent. */
    public record Options(boolean customBackgrounds, int veilPercent) {
        public Options {
            veilPercent = Math.max(0, Math.min(80, veilPercent));
        }

        public static Options defaults() {
            return new Options(true, 15);
        }
    }

    private static final String[] CUSTOM_EXTENSIONS = {".png", ".jpg", ".jpeg"};
    private static final long MAX_CUSTOM_BYTES = 32L * 1024 * 1024;
    private static final int MAX_CUSTOM_EDGE = 8192;
    private static final Color VEIL = new Color(255, 248, 250);

    private final Path customDirectory;
    private final Supplier<Options> options;
    private final Logger logger;
    private final Map<Surface, Cached> cache = new EnumMap<>(Surface.class);
    private final Map<Surface, Cached> backgroundCache = new EnumMap<>(Surface.class);
    private final Map<String, String> reportedFailures = new java.util.HashMap<>();

    /**
     * @param customDirectory folder with custom background pictures, or null to only use the bundled scenes
     */
    public BackdropLibrary(Path customDirectory, Supplier<Options> options, Logger logger) {
        this.customDirectory = customDirectory;
        this.options = Objects.requireNonNull(options, "options");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Bundled layers only, default options. */
    public static BackdropLibrary bundled() {
        return new BackdropLibrary(null, Options::defaults, Logger.getLogger(BackdropLibrary.class.getName()));
    }

    /** The composited backdrop; never null, falls back to the bundled scene on any custom-file problem. */
    public synchronized BufferedImage get(Surface surface) {
        Options current = options.get();
        Path custom = current.customBackgrounds() ? findCustom(surface, "", CUSTOM_EXTENSIONS) : null;
        String key = stamp(custom) + "|" + current.veilPercent();
        Cached cached = cache.get(surface);
        if (cached != null && cached.key.equals(key)) return cached.image;
        BufferedImage image = compose(surface, custom, current);
        cache.put(surface, new Cached(key, image));
        return image;
    }

    /** Where admins put their own pictures, e.g. {@code online-list.png}. */
    public Path customDirectory() {
        return customDirectory;
    }

    /**
     * Only the background picture (the admin's own, or the bundled one) scaled to cover
     * [width]x[height], with the veil over custom pictures. For renderers that lay out their own
     * glass panels at a dynamic size, such as the online list.
     */
    public synchronized BufferedImage background(Surface surface, int width, int height) {
        Options current = options.get();
        Path custom = current.customBackgrounds() ? findCustom(surface, "", CUSTOM_EXTENSIONS) : null;
        String key = width + "x" + height + "|" + stamp(custom) + "|" + current.veilPercent();
        Cached cached = backgroundCache.get(surface);
        if (cached != null && cached.key.equals(key)) return cached.image;
        BufferedImage customImage = custom == null ? null : readCustom(custom);
        BufferedImage source = customImage != null ? customImage
            : read("/backdrops/" + surface.id() + "/background.png", surface);
        BufferedImage image = cover(source, width, height);
        if (customImage != null && current.veilPercent() > 0) {
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.SrcOver.derive(current.veilPercent() / 100f));
                graphics.setColor(VEIL);
                graphics.fillRect(0, 0, width, height);
            } finally {
                graphics.dispose();
            }
        }
        backgroundCache.put(surface, new Cached(key, image));
        return image;
    }

    private BufferedImage compose(Surface surface, Path custom, Options current) {
        // Layers are read only while composing (rare: first use, a new picture or new options);
        // keeping them resident would hold ~30 MB for nothing.
        Layers bundled = loadLayers(surface);
        BufferedImage customImage = custom == null ? null : readCustom(custom);
        BufferedImage background = customImage == null
            ? bundled.background
            : cover(customImage, surface.width(), surface.height());

        BufferedImage result = new BufferedImage(surface.width(), surface.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            BufferedImage visible = masked(background, bundled.mask);
            graphics.drawImage(visible, 0, 0, null);
            if (bundled.glass != null) graphics.drawImage(masked(frost(visible), bundled.glass), 0, 0, null);
            if (customImage != null && current.veilPercent() > 0) {
                BufferedImage veil = new BufferedImage(surface.width(), surface.height(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D veilGraphics = veil.createGraphics();
                try {
                    veilGraphics.setColor(VEIL);
                    veilGraphics.fillRect(0, 0, surface.width(), surface.height());
                } finally {
                    veilGraphics.dispose();
                }
                graphics.setComposite(AlphaComposite.SrcOver.derive(current.veilPercent() / 100f));
                graphics.drawImage(masked(veil, bundled.mask), 0, 0, null);
                graphics.setComposite(AlphaComposite.SrcOver);
            }
            graphics.drawImage(bundled.ui, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private Path findCustom(Surface surface, String suffix, String[] extensions) {
        if (customDirectory == null) return null;
        for (String extension : extensions) {
            Path candidate = customDirectory.resolve(surface.id() + suffix + extension);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static String stamp(Path file) {
        if (file == null) return "bundled";
        try {
            return file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + Files.size(file);
        } catch (IOException error) {
            return file.toAbsolutePath() + "|unreadable";
        }
    }

    private BufferedImage readCustom(Path file) {
        try {
            if (Files.size(file) > MAX_CUSTOM_BYTES) throw new IOException("图片超过 32 MiB");
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) throw new IOException("不是可识别的 PNG/JPG 图片");
            if (image.getWidth() > MAX_CUSTOM_EDGE || image.getHeight() > MAX_CUSTOM_EDGE) {
                throw new IOException("边长不能超过 " + MAX_CUSTOM_EDGE + " 像素");
            }
            reportedFailures.remove(file.getFileName().toString());
            return image;
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (!message.equals(reportedFailures.put(file.getFileName().toString(), message))) {
                logger.log(Level.WARNING, "自定义底图 " + file.getFileName() + " 没能使用（" + message + "），先用 Virga 自带的底图。");
            }
            return null;
        }
    }

    /** Scales [image] to fill the canvas while keeping its aspect ratio, cropping the centre. */
    static BufferedImage cover(BufferedImage image, int width, int height) {
        double scale = Math.max(width / (double) image.getWidth(), height / (double) image.getHeight());
        int drawWidth = (int) Math.ceil(image.getWidth() * scale);
        int drawHeight = (int) Math.ceil(image.getHeight() * scale);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** Multiplies the layer's alpha by the mask's alpha (white, opaque mask pixels keep the layer). */
    static BufferedImage masked(BufferedImage layer, BufferedImage mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = layer.getRGB(0, 0, width, height, null, 0, width);
        int[] maskPixels = mask.getRGB(0, 0, width, height, null, 0, width);
        for (int index = 0; index < pixels.length; index++) {
            int alpha = (pixels[index] >>> 24) * (maskPixels[index] >>> 24) / 255;
            pixels[index] = (alpha << 24) | (pixels[index] & 0xFFFFFF);
        }
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    /**
     * A strong frosted-glass blur: shrink to 1/8 (bilinear halvings), run two separable box blurs
     * there (each ~40 px wide at full size) and scale back up. Close to the generator's Gaussian
     * blur and cheap enough to run once per custom picture.
     */
    public static BufferedImage frost(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage small = image;
        for (int i = 0; i < 3; i++) {
            small = resize(small, Math.max(1, small.getWidth() / 2), Math.max(1, small.getHeight() / 2));
        }
        int w = small.getWidth();
        int h = small.getHeight();
        int[] pixels = small.getRGB(0, 0, w, h, null, 0, w);
        for (int pass = 0; pass < 2; pass++) {
            pixels = boxBlur(pixels, w, h, FROST_RADIUS, true);
            pixels = boxBlur(pixels, w, h, FROST_RADIUS, false);
        }
        small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        small.setRGB(0, 0, w, h, pixels, 0, w);
        BufferedImage current = small;
        for (int i = 0; i < 2; i++) current = resize(current, current.getWidth() * 2, current.getHeight() * 2);
        return resize(current, width, height);
    }

    private static final int FROST_RADIUS = 2;

    /** One box-blur pass along rows or columns; edges clamp, channels are averaged independently. */
    private static int[] boxBlur(int[] source, int width, int height, int radius, boolean horizontal) {
        int[] result = new int[source.length];
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int span = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int j = Math.max(0, Math.min(length - 1, i + k));
                    int argb = source[horizontal ? line * width + j : j * width + line];
                    a += argb >>> 24;
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                }
                result[horizontal ? line * width + i : i * width + line] =
                    ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
            }
        }
        return result;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static Layers loadLayers(Surface surface) {
        String root = "/backdrops/" + surface.id().toLowerCase(Locale.ROOT) + "/";
        return new Layers(
            read(root + "background.png", surface),
            read(root + "mask.png", surface),
            read(root + "ui.png", surface),
            readOptional(root + "glass.png", surface)
        );
    }

    private static BufferedImage read(String resource, Surface surface) {
        try (InputStream input = BackdropLibrary.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("缺少底图图层 " + resource);
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() != surface.width() || image.getHeight() != surface.height()) {
                throw new IllegalStateException("底图图层尺寸不对：" + resource);
            }
            return image;
        } catch (IOException error) {
            throw new IllegalStateException("读取底图图层失败 " + resource, error);
        }
    }

    private static BufferedImage readOptional(String resource, Surface surface) {
        if (BackdropLibrary.class.getResource(resource) == null) return null;
        return read(resource, surface);
    }

    private record Layers(BufferedImage background, BufferedImage mask, BufferedImage ui, BufferedImage glass) {}

    private record Cached(String key, BufferedImage image) {}
}
