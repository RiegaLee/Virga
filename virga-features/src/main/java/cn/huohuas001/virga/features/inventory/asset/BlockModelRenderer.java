package cn.huohuas001.virga.features.inventory.asset;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free orthographic rasterizer for Minecraft GUI block/item models. */
final class BlockModelRenderer {
    private static final int OUTPUT_SIZE = 64;
    private static final int WORK_SIZE = 64;
    private static final int MAX_ICON_EXTENT = 56;
    private static final double PIXELS_PER_UNIT = WORK_SIZE / 16.0;
    private static final Transform DEFAULT_GUI = new Transform(
        new Vec3(30, 225, 0), new Vec3(0, 0, 0), new Vec3(0.625, 0.625, 0.625)
    );

    interface TextureLoader {
        BufferedImage load(String texture);
    }

    BufferedImage render(Model model, TextureLoader textures) {
        return render(model, textures, true);
    }

    BufferedImage render(Model model, TextureLoader textures, boolean shadingEnabled) {
        @SuppressWarnings("unchecked")
        List<Fragment>[] fragments = (List<Fragment>[]) new List<?>[WORK_SIZE * WORK_SIZE];

        for (Element element : model.elements) {
            for (Map.Entry<String, Face> faceEntry : element.faces.entrySet()) {
                String textureId = resolveTexture(faceEntry.getValue().texture, model.textures);
                if (textureId == null) continue;
                BufferedImage texture = textures.load(textureId);
                if (texture == null) continue;
                drawFace(
                    fragments, element, faceEntry.getKey(), faceEntry.getValue(), texture,
                    model.gui, model.tints, shadingEnabled
                );
            }
        }

        int[] pixels = composite(fragments);
        BufferedImage working = new BufferedImage(WORK_SIZE, WORK_SIZE, BufferedImage.TYPE_INT_ARGB);
        working.setRGB(0, 0, WORK_SIZE, WORK_SIZE, pixels, 0, WORK_SIZE);
        // Keep the model baker at the final Faithful slot resolution. MB6 threw away half of this
        // raster here (64 -> 32), after which the inventory compositor enlarged it back to 64.
        // Returning the native raster preserves model edges and texture samples without changing
        // the 32x source pack or introducing filtered/painterly pixels.
        return fitAndCenter(working);
    }

    private static BufferedImage fitAndCenter(BufferedImage source) {
        int minX = source.getWidth();
        int minY = source.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                if ((source.getRGB(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) return source;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        double scale = Math.min(1.0, Math.min((double) MAX_ICON_EXTENT / width, (double) MAX_ICON_EXTENT / height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        int targetX = (OUTPUT_SIZE - targetWidth) / 2;
        int targetY = (OUTPUT_SIZE - targetHeight) / 2;
        BufferedImage result = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(
                source, targetX, targetY, targetX + targetWidth, targetY + targetHeight,
                minX, minY, maxX + 1, maxY + 1, null
            );
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static void drawFace(
        List<Fragment>[] fragments,
        Element element,
        String direction,
        Face face,
        BufferedImage texture,
        Transform gui,
        List<Integer> tints
        , boolean shadingEnabled
    ) {
        Vec3[] points = vertices(element.from, element.to, direction);
        if (points == null) return;
        Vec3 normal = directionNormal(direction);
        normal = rotateNormal(normal, element.rotation, gui);
        if (normal.z <= 0.00001) return;
        for (int index = 0; index < points.length; index++) {
            points[index] = rotateElement(points[index], element.rotation);
            points[index] = applyGui(points[index], gui);
        }

        double[] uv = face.uv == null ? defaultUv(element.from, element.to, direction) : face.uv;
        Uv[] coordinates = rotateUv(new Uv[] {
            new Uv(uv[0], uv[1]), new Uv(uv[2], uv[1]),
            new Uv(uv[2], uv[3]), new Uv(uv[0], uv[3])
        }, face.rotation);

        Screen[] projected = new Screen[4];
        for (int index = 0; index < 4; index++) {
            projected[index] = new Screen(
                WORK_SIZE / 2.0 + points[index].x * PIXELS_PER_UNIT,
                WORK_SIZE / 2.0 - points[index].y * PIXELS_PER_UNIT,
                points[index].z
            );
        }
        double brightness = shadingEnabled && element.shade ? light(normal) : 1.0;
        int tint = face.tintIndex >= 0 && face.tintIndex < tints.size() ? tints.get(face.tintIndex) : 0xffffff;
        triangle(fragments, projected[0], projected[1], projected[2], coordinates[0], coordinates[1], coordinates[2], texture, brightness, tint);
        triangle(fragments, projected[0], projected[2], projected[3], coordinates[0], coordinates[2], coordinates[3], texture, brightness, tint);
    }

    private static void triangle(
        List<Fragment>[] fragments,
        Screen a,
        Screen b,
        Screen c,
        Uv ta,
        Uv tb,
        Uv tc,
        BufferedImage texture,
        double brightness,
        int tint
    ) {
        double area = edge(a.x, a.y, b.x, b.y, c.x, c.y);
        if (Math.abs(area) < 0.000001) return;
        int minX = clamp((int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))), 0, WORK_SIZE - 1);
        int maxX = clamp((int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))), 0, WORK_SIZE - 1);
        int minY = clamp((int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))), 0, WORK_SIZE - 1);
        int maxY = clamp((int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))), 0, WORK_SIZE - 1);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double wa = edge(b.x, b.y, c.x, c.y, px, py) / area;
                double wb = edge(c.x, c.y, a.x, a.y, px, py) / area;
                double wc = 1.0 - wa - wb;
                if (wa < -0.00001 || wb < -0.00001 || wc < -0.00001) continue;
                double z = wa * a.z + wb * b.z + wc * c.z;
                int offset = y * WORK_SIZE + x;
                double u = wa * ta.u + wb * tb.u + wc * tc.u;
                double v = wa * ta.v + wb * tb.v + wc * tc.v;
                int tx = clamp((int) Math.floor(u / 16.0 * texture.getWidth()), 0, texture.getWidth() - 1);
                int ty = clamp((int) Math.floor(v / 16.0 * texture.getHeight()), 0, texture.getHeight() - 1);
                int color = texture.getRGB(tx, ty);
                int alpha = color >>> 24;
                if (alpha == 0) continue;
                color = shade(applyTint(color, tint), brightness);
                List<Fragment> pixel = fragments[offset];
                if (pixel == null) {
                    pixel = new ArrayList<Fragment>(2);
                    fragments[offset] = pixel;
                }
                pixel.add(new Fragment(z, color));
            }
        }
    }

    /**
     * Composites every visible model surface from back to front. Keeping only the nearest
     * fragment made nested translucent models such as honey blocks lose their inner layer,
     * leaving the baked icon at half opacity and washing its color into the slot background.
     */
    private static int[] composite(List<Fragment>[] fragments) {
        int[] pixels = new int[fragments.length];
        Comparator<Fragment> backToFront = Comparator.comparingDouble(fragment -> fragment.depth);
        for (int offset = 0; offset < fragments.length; offset++) {
            List<Fragment> pixel = fragments[offset];
            if (pixel == null) continue;
            pixel.sort(backToFront);
            int color = 0;
            for (Fragment fragment : pixel) color = blend(fragment.color, color);
            pixels[offset] = color;
        }
        return pixels;
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static Vec3[] vertices(Vec3 from, Vec3 to, String direction) {
        if ("north".equals(direction)) return new Vec3[] {
            new Vec3(to.x, to.y, from.z), new Vec3(from.x, to.y, from.z),
            new Vec3(from.x, from.y, from.z), new Vec3(to.x, from.y, from.z)
        };
        if ("south".equals(direction)) return new Vec3[] {
            new Vec3(from.x, to.y, to.z), new Vec3(to.x, to.y, to.z),
            new Vec3(to.x, from.y, to.z), new Vec3(from.x, from.y, to.z)
        };
        if ("west".equals(direction)) return new Vec3[] {
            new Vec3(from.x, to.y, from.z), new Vec3(from.x, to.y, to.z),
            new Vec3(from.x, from.y, to.z), new Vec3(from.x, from.y, from.z)
        };
        if ("east".equals(direction)) return new Vec3[] {
            new Vec3(to.x, to.y, to.z), new Vec3(to.x, to.y, from.z),
            new Vec3(to.x, from.y, from.z), new Vec3(to.x, from.y, to.z)
        };
        if ("up".equals(direction)) return new Vec3[] {
            new Vec3(from.x, to.y, from.z), new Vec3(to.x, to.y, from.z),
            new Vec3(to.x, to.y, to.z), new Vec3(from.x, to.y, to.z)
        };
        if ("down".equals(direction)) return new Vec3[] {
            new Vec3(from.x, from.y, to.z), new Vec3(to.x, from.y, to.z),
            new Vec3(to.x, from.y, from.z), new Vec3(from.x, from.y, from.z)
        };
        return null;
    }

    private static double[] defaultUv(Vec3 from, Vec3 to, String direction) {
        if ("down".equals(direction)) return new double[] {from.x, 16 - to.z, to.x, 16 - from.z};
        if ("up".equals(direction)) return new double[] {from.x, from.z, to.x, to.z};
        if ("north".equals(direction)) return new double[] {16 - to.x, 16 - to.y, 16 - from.x, 16 - from.y};
        if ("south".equals(direction)) return new double[] {from.x, 16 - to.y, to.x, 16 - from.y};
        if ("west".equals(direction)) return new double[] {from.z, 16 - to.y, to.z, 16 - from.y};
        return new double[] {16 - to.z, 16 - to.y, 16 - from.z, 16 - from.y};
    }

    private static Uv[] rotateUv(Uv[] input, int degrees) {
        int steps = ((degrees % 360) + 360) % 360 / 90;
        Uv[] result = input.clone();
        for (int step = 0; step < steps; step++) {
            Uv last = result[3];
            result[3] = result[2];
            result[2] = result[1];
            result[1] = result[0];
            result[0] = last;
        }
        return result;
    }

    private static Vec3 rotateElement(Vec3 point, Rotation rotation) {
        if (rotation == null || rotation.angle == 0) return point;
        Vec3 relative = point.subtract(rotation.origin);
        Vec3 rotated = rotateAxis(relative, rotation.axis, rotation.angle);
        return rotated.add(rotation.origin);
    }

    private static Vec3 applyGui(Vec3 point, Transform transform) {
        Transform value = transform == null ? DEFAULT_GUI : transform;
        Vec3 centered = point.subtract(new Vec3(8, 8, 8));
        centered = new Vec3(centered.x * value.scale.x, centered.y * value.scale.y, centered.z * value.scale.z);
        centered = rotateAxis(centered, "z", value.rotation.z);
        centered = rotateAxis(centered, "y", value.rotation.y);
        centered = rotateAxis(centered, "x", value.rotation.x);
        return centered.add(value.translation);
    }

    private static Vec3 rotateNormal(Vec3 normal, Rotation element, Transform gui) {
        Vec3 result = element == null ? normal : rotateAxis(normal, element.axis, element.angle);
        Transform value = gui == null ? DEFAULT_GUI : gui;
        result = rotateAxis(result, "z", value.rotation.z);
        result = rotateAxis(result, "y", value.rotation.y);
        result = rotateAxis(result, "x", value.rotation.x);
        return result.normalize();
    }

    private static Vec3 directionNormal(String direction) {
        if ("north".equals(direction)) return new Vec3(0, 0, -1);
        if ("south".equals(direction)) return new Vec3(0, 0, 1);
        if ("west".equals(direction)) return new Vec3(-1, 0, 0);
        if ("east".equals(direction)) return new Vec3(1, 0, 0);
        if ("up".equals(direction)) return new Vec3(0, 1, 0);
        return new Vec3(0, -1, 0);
    }

    private static Vec3 rotateAxis(Vec3 point, String axis, double degrees) {
        double angle = Math.toRadians(degrees);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        if ("x".equals(axis)) return new Vec3(point.x, point.y * cos - point.z * sin, point.y * sin + point.z * cos);
        if ("y".equals(axis)) return new Vec3(point.x * cos + point.z * sin, point.y, -point.x * sin + point.z * cos);
        return new Vec3(point.x * cos - point.y * sin, point.x * sin + point.y * cos, point.z);
    }

    private static double light(Vec3 normal) {
        if (normal.y > 0.25) return 1.0;
        return normal.x < 0 ? 0.80 : 0.62;
    }

    private static int applyTint(int color, int tint) {
        int alpha = color >>> 24;
        int red = ((color >>> 16) & 0xff) * ((tint >>> 16) & 0xff) / 255;
        int green = ((color >>> 8) & 0xff) * ((tint >>> 8) & 0xff) / 255;
        int blue = (color & 0xff) * (tint & 0xff) / 255;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int shade(int color, double amount) {
        int alpha = color >>> 24;
        int red = (int) Math.round(((color >>> 16) & 0xff) * amount);
        int green = (int) Math.round(((color >>> 8) & 0xff) * amount);
        int blue = (int) Math.round((color & 0xff) * amount);
        return (alpha << 24) | (clamp(red, 0, 255) << 16) | (clamp(green, 0, 255) << 8) | clamp(blue, 0, 255);
    }

    private static int blend(int source, int destination) {
        int sa = source >>> 24;
        if (sa == 255) return source;
        int da = destination >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int sr = (source >>> 16) & 0xff;
        int sg = (source >>> 8) & 0xff;
        int sb = source & 0xff;
        int dr = (destination >>> 16) & 0xff;
        int dg = (destination >>> 8) & 0xff;
        int db = destination & 0xff;
        int red = (sr * sa + dr * da * (255 - sa) / 255) / outA;
        int green = (sg * sa + dg * da * (255 - sa) / 255) / outA;
        int blue = (sb * sa + db * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (red << 16) | (green << 8) | blue;
    }

    private static String resolveTexture(String value, Map<String, String> textures) {
        java.util.HashSet<String> visited = new java.util.HashSet<String>();
        String current = value;
        if (current != null && !current.startsWith("#") && textures.containsKey(current)) current = "#" + current;
        while (current != null && current.startsWith("#")) {
            String key = current.substring(1);
            if (!visited.add(key)) return null;
            current = textures.get(key);
        }
        return current == null || current.trim().isEmpty() ? null : current.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Model {
        final Map<String, String> textures;
        final List<Element> elements;
        final Transform gui;
        final List<Integer> tints;
        Model(Map<String, String> textures, List<Element> elements, Transform gui) {
            this(textures, elements, gui, java.util.Collections.<Integer>emptyList());
        }
        Model(Map<String, String> textures, List<Element> elements, Transform gui, List<Integer> tints) {
            this.textures = new LinkedHashMap<String, String>(textures);
            this.elements = new ArrayList<Element>(elements);
            this.gui = gui;
            this.tints = new ArrayList<Integer>(tints);
        }
    }

    static final class Element {
        final Vec3 from;
        final Vec3 to;
        final Map<String, Face> faces;
        final Rotation rotation;
        final boolean shade;
        Element(Vec3 from, Vec3 to, Map<String, Face> faces, Rotation rotation, boolean shade) {
            this.from = from;
            this.to = to;
            this.faces = new LinkedHashMap<String, Face>(faces);
            this.rotation = rotation;
            this.shade = shade;
        }
    }

    static final class Face {
        final String texture;
        final double[] uv;
        final int rotation;
        final int tintIndex;
        Face(String texture, double[] uv, int rotation) {
            this(texture, uv, rotation, -1);
        }
        Face(String texture, double[] uv, int rotation, int tintIndex) {
            this.texture = texture;
            this.uv = uv == null ? null : uv.clone();
            this.rotation = rotation;
            this.tintIndex = tintIndex;
        }
    }

    static final class Rotation {
        final Vec3 origin;
        final String axis;
        final double angle;
        Rotation(Vec3 origin, String axis, double angle) {
            this.origin = origin;
            this.axis = axis;
            this.angle = angle;
        }
    }

    static final class Transform {
        static final Transform IDENTITY = new Transform(new Vec3(0, 0, 0), new Vec3(0, 0, 0), new Vec3(1, 1, 1));
        final Vec3 rotation;
        final Vec3 translation;
        final Vec3 scale;
        Transform(Vec3 rotation, Vec3 translation, Vec3 scale) {
            this.rotation = rotation;
            this.translation = translation;
            this.scale = scale;
        }
    }

    static final class Vec3 {
        final double x;
        final double y;
        final double z;
        Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
        Vec3 subtract(Vec3 other) { return new Vec3(x - other.x, y - other.y, z - other.z); }
        Vec3 cross(Vec3 other) { return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
        double dot(Vec3 other) { return x * other.x + y * other.y + z * other.z; }
        Vec3 normalize() {
            double length = Math.sqrt(dot(this));
            return length < 0.000001 ? new Vec3(0, 0, 0) : new Vec3(x / length, y / length, z / length);
        }
    }

    private static final class Screen {
        final double x;
        final double y;
        final double z;
        Screen(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    private static final class Uv {
        final double u;
        final double v;
        Uv(double u, double v) { this.u = u; this.v = v; }
    }

    private static final class Fragment {
        final double depth;
        final int color;
        Fragment(double depth, int color) {
            this.depth = depth;
            this.color = color;
        }
    }
}
