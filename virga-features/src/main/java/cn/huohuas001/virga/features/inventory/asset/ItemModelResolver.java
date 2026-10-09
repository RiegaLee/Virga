package cn.huohuas001.virga.features.inventory.asset;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Resolves modern item definitions, bakes generated items, and rasterizes ordinary block models. */
final class ItemModelResolver {
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXTURE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int OUTPUT_SIZE = 32;

    private final ZipFile jar;
    private final ZipFile texturePack;
    private final Map<String, BufferedImage> textureCache = new LinkedHashMap<String, BufferedImage>();

    ItemModelResolver(ZipFile jar) { this(jar, null); }
    ItemModelResolver(ZipFile jar, ZipFile texturePack) {
        this.jar = jar;
        this.texturePack = texturePack;
    }

    Resolution resolve(String itemDefinitionPath) {
        try {
            Map<String, Object> definition = readObject(itemDefinitionPath);
            ModelChoice choice = selectModel(definition.get("model"), 0);
            if (!choice.supported) return Resolution.unresolved(choice.reason);
            ModelData model = loadModel(choice.model, new HashSet<String>(), 0);

            if (!model.elements.isEmpty()) {
                BufferedImage icon = new BlockModelRenderer().render(
                    new BlockModelRenderer.Model(model.textures, model.elements, model.gui, choice.tints), this::loadTexture
                );
                if (hasVisiblePixel(icon)) return Resolution.resolved(icon, choice.model, null, RenderKind.BLOCK_MODEL);
                return Resolution.unresolved("BLOCK_MODEL_RENDER_EMPTY");
            }

            List<String> layers = generatedLayers(model.textures);
            if (!layers.isEmpty()) {
                BufferedImage icon = renderGenerated(layers);
                if (icon != null) return Resolution.resolved(icon, choice.model, layers.get(0), RenderKind.GENERATED_2D);
            }

            String texture = chooseTexture(model.textures);
            if (texture == null) return Resolution.unresolved("MODEL_HAS_NO_STATIC_TEXTURE");
            BufferedImage source = loadTexture(texture);
            if (source == null) return Resolution.unresolved("MISSING_OR_UNREADABLE_TEXTURE");
            return Resolution.resolved(normalize(source), choice.model, texture, RenderKind.STATIC_FALLBACK);
        } catch (IllegalArgumentException error) {
            return Resolution.unresolved("INVALID_JSON_OR_RESOURCE");
        } catch (IOException error) {
            return Resolution.unresolved("ASSET_READ_ERROR");
        }
    }

    BlockDiagnostic diagnoseBlock(String itemDefinitionPath) {
        try {
            Map<String, Object> definition = readObject(itemDefinitionPath);
            ModelChoice choice = selectModel(definition.get("model"), 0);
            if (!choice.supported) throw new IllegalArgumentException("Unsupported diagnostic item: " + choice.reason);
            ModelData model = loadModel(choice.model, new HashSet<String>(), 0);
            if (model.elements.isEmpty()) throw new IllegalArgumentException("Diagnostic item is not a block model");
            String texture = chooseTexture(model.textures);
            if (texture == null) throw new IllegalArgumentException("Diagnostic block has no source texture");
            BufferedImage source = loadTexture(texture);
            if (source == null) throw new IllegalArgumentException("Diagnostic source texture is unavailable");
            BlockModelRenderer.Model renderModel = new BlockModelRenderer.Model(
                model.textures, model.elements, model.gui, choice.tints
            );
            BlockModelRenderer renderer = new BlockModelRenderer();
            return new BlockDiagnostic(
                texture,
                copy(source),
                renderer.render(renderModel, this::loadTexture, false),
                renderer.render(renderModel, this::loadTexture, true)
            );
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read diagnostic block model", error);
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private ModelChoice selectModel(Object value, int depth) {
        if (depth > MAX_DEPTH || !(value instanceof Map)) return ModelChoice.unsupported("UNSUPPORTED_ITEM_DEFINITION");
        Map<String, Object> node = MiniJson.object(value, "item model");
        String type = MiniJson.string(node, "type", true);
        if ("minecraft:model".equals(type)) {
            List<Integer> tints = resolveStaticTints(node.get("tints"));
            if (tints == null) return ModelChoice.unsupported("DYNAMIC_TINT");
            return ModelChoice.supported(
                ResourceLocation.parse(MiniJson.string(node, "model", true), "minecraft"), tints
            );
        }
        if ("minecraft:select".equals(type) &&
            "minecraft:display_context".equals(MiniJson.string(node, "property", false))) {
            Object cases = node.get("cases");
            if (cases instanceof List) {
                for (Object rawCase : MiniJson.array(cases, "display context cases")) {
                    Map<String, Object> itemCase = MiniJson.object(rawCase, "display context case");
                    if (containsGui(itemCase.get("when"))) return selectModel(itemCase.get("model"), depth + 1);
                }
            }
            return selectModel(node.get("fallback"), depth + 1);
        }
        if ("minecraft:select".equals(type) || "minecraft:range_dispatch".equals(type)) {
            return selectModel(node.get("fallback"), depth + 1);
        }
        if ("minecraft:condition".equals(type)) return selectModel(node.get("on_false"), depth + 1);
        if ("minecraft:composite".equals(type)) {
            Object models = node.get("models");
            if (!(models instanceof List) || ((List<?>) models).isEmpty()) return ModelChoice.unsupported("EMPTY_COMPOSITE");
            return selectModel(((List<?>) models).get(0), depth + 1);
        }
        if ("minecraft:special".equals(type)) return ModelChoice.unsupported("SPECIAL_RENDERER");
        if ("minecraft:empty".equals(type)) return ModelChoice.unsupported("EMPTY_MODEL");
        return ModelChoice.unsupported("UNSUPPORTED_DEFINITION_TYPE");
    }

    private static boolean containsGui(Object when) {
        if (when instanceof String) return "gui".equals(when);
        if (!(when instanceof List)) return false;
        for (Object value : (List<?>) when) if ("gui".equals(value)) return true;
        return false;
    }

    private ModelData loadModel(ResourceLocation location, Set<String> visiting, int depth) throws IOException {
        if (depth > MAX_DEPTH || !visiting.add(location.toString())) {
            throw new IllegalArgumentException("Cyclic or too-deep model parent chain");
        }
        String path = "assets/" + location.namespace + "/models/" + location.path + ".json";
        Map<String, Object> object = readObject(path);
        ModelData inherited = ModelData.empty();
        String parent = MiniJson.string(object, "parent", false);
        boolean generated = false;
        if (parent != null) {
            if ("builtin/generated".equals(parent)) {
                generated = true;
            } else if (!parent.startsWith("builtin/")) {
                ResourceLocation parentLocation = ResourceLocation.parse(parent, location.namespace);
                String parentEntry = "assets/" + parentLocation.namespace + "/models/" + parentLocation.path + ".json";
                if (jar.getEntry(parentEntry) != null) inherited = loadModel(parentLocation, visiting, depth + 1);
            }
        }

        LinkedHashMap<String, String> textures = new LinkedHashMap<String, String>(inherited.textures);
        Object rawTextures = object.get("textures");
        if (rawTextures instanceof Map) {
            for (Map.Entry<String, Object> texture : MiniJson.object(rawTextures, "model textures").entrySet()) {
                if (texture.getValue() instanceof String) {
                    textures.put(texture.getKey(), (String) texture.getValue());
                } else if (texture.getValue() instanceof Map) {
                    String sprite = MiniJson.string(
                        MiniJson.object(texture.getValue(), "model texture descriptor"), "sprite", false
                    );
                    if (sprite != null) textures.put(texture.getKey(), sprite);
                }
            }
        }
        List<BlockModelRenderer.Element> elements = inherited.elements;
        if (object.containsKey("elements")) elements = parseElements(object.get("elements"));
        BlockModelRenderer.Transform gui = inherited.gui;
        Object rawDisplay = object.get("display");
        if (rawDisplay instanceof Map) {
            Object rawGui = MiniJson.object(rawDisplay, "model display").get("gui");
            if (rawGui instanceof Map) gui = parseTransform(MiniJson.object(rawGui, "display.gui"));
        }
        visiting.remove(location.toString());
        return new ModelData(textures, elements, gui, generated || inherited.generated);
    }

    private static List<BlockModelRenderer.Element> parseElements(Object raw) {
        List<BlockModelRenderer.Element> result = new ArrayList<BlockModelRenderer.Element>();
        for (Object value : MiniJson.array(raw, "model elements")) {
            Map<String, Object> element = MiniJson.object(value, "model element");
            BlockModelRenderer.Vec3 from = vector(element.get("from"), "element.from", null);
            BlockModelRenderer.Vec3 to = vector(element.get("to"), "element.to", null);
            Map<String, BlockModelRenderer.Face> faces = new LinkedHashMap<String, BlockModelRenderer.Face>();
            for (Map.Entry<String, Object> entry : MiniJson.object(element.get("faces"), "element.faces").entrySet()) {
                Map<String, Object> face = MiniJson.object(entry.getValue(), "element face");
                String texture = MiniJson.string(face, "texture", true);
                double[] uv = face.containsKey("uv") ? numbers(face.get("uv"), "face.uv", 4) : null;
                int rotation = MiniJson.integer(face, "rotation", 0);
                int tintIndex = MiniJson.integer(face, "tintindex", -1);
                if (rotation % 90 != 0) throw new IllegalArgumentException("Face rotation must be a multiple of 90");
                faces.put(entry.getKey(), new BlockModelRenderer.Face(texture, uv, rotation, tintIndex));
            }
            BlockModelRenderer.Rotation rotation = null;
            Object rawRotation = element.get("rotation");
            if (rawRotation instanceof Map) {
                Map<String, Object> parsed = MiniJson.object(rawRotation, "element.rotation");
                BlockModelRenderer.Vec3 origin = vector(parsed.get("origin"), "rotation.origin", null);
                String axis = MiniJson.string(parsed, "axis", true);
                if (!"x".equals(axis) && !"y".equals(axis) && !"z".equals(axis)) {
                    throw new IllegalArgumentException("Unsupported element rotation axis");
                }
                Object angle = parsed.get("angle");
                if (!(angle instanceof Number)) throw new IllegalArgumentException("rotation.angle must be numeric");
                rotation = new BlockModelRenderer.Rotation(origin, axis, ((Number) angle).doubleValue());
            }
            boolean shade = !(element.get("shade") instanceof Boolean) || ((Boolean) element.get("shade")).booleanValue();
            result.add(new BlockModelRenderer.Element(from, to, faces, rotation, shade));
        }
        return result;
    }

    private static BlockModelRenderer.Transform parseTransform(Map<String, Object> object) {
        return new BlockModelRenderer.Transform(
            vector(object.get("rotation"), "display.rotation", new BlockModelRenderer.Vec3(0, 0, 0)),
            vector(object.get("translation"), "display.translation", new BlockModelRenderer.Vec3(0, 0, 0)),
            vector(object.get("scale"), "display.scale", new BlockModelRenderer.Vec3(1, 1, 1))
        );
    }

    private List<Integer> resolveStaticTints(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (!(raw instanceof List)) return null;
        List<Integer> result = new ArrayList<Integer>();
        for (Object entry : MiniJson.array(raw, "item tints")) {
            Map<String, Object> tint = MiniJson.object(entry, "item tint");
            String type = MiniJson.string(tint, "type", true);
            if ("minecraft:constant".equals(type)) {
                Object value = tint.get("value");
                if (!(value instanceof Number)) return null;
                result.add(((Number) value).intValue() & 0xffffff);
                continue;
            }
            if ("minecraft:grass".equals(type) || "minecraft:foliage".equals(type)) {
                Object temperatureValue = tint.get("temperature");
                Object downfallValue = tint.get("downfall");
                if (!(temperatureValue instanceof Number) || !(downfallValue instanceof Number)) return null;
                double temperature = clamp01(((Number) temperatureValue).doubleValue());
                double downfall = clamp01(((Number) downfallValue).doubleValue()) * temperature;
                BufferedImage map = loadTexture(
                    "minecraft:colormap/" + ("minecraft:grass".equals(type) ? "grass" : "foliage")
                );
                if (map == null) return null;
                int x = Math.min(map.getWidth() - 1, (int) ((1.0 - temperature) * map.getWidth()));
                int y = Math.min(map.getHeight() - 1, (int) ((1.0 - downfall) * map.getHeight()));
                result.add(map.getRGB(x, y) & 0xffffff);
                continue;
            }
            return null;
        }
        return result;
    }

    private static double clamp01(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    private static BlockModelRenderer.Vec3 vector(Object raw, String label, BlockModelRenderer.Vec3 fallback) {
        if (raw == null && fallback != null) return fallback;
        double[] values = numbers(raw, label, 3);
        return new BlockModelRenderer.Vec3(values[0], values[1], values[2]);
    }

    private static double[] numbers(Object raw, String label, int size) {
        List<Object> values = MiniJson.array(raw, label);
        if (values.size() != size) throw new IllegalArgumentException(label + " must contain " + size + " numbers");
        double[] result = new double[size];
        for (int index = 0; index < size; index++) {
            if (!(values.get(index) instanceof Number)) throw new IllegalArgumentException(label + " must be numeric");
            result[index] = ((Number) values.get(index)).doubleValue();
        }
        return result;
    }

    private static List<String> generatedLayers(Map<String, String> textures) {
        List<String> result = new ArrayList<String>();
        for (int index = 0; index < 16; index++) {
            String resolved = resolveTextureReference(textures.get("layer" + index), textures);
            if (resolved != null) result.add(resolved);
        }
        return result;
    }

    private BufferedImage renderGenerated(List<String> layers) {
        BufferedImage result = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        boolean rendered = false;
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            for (String layer : layers) {
                BufferedImage texture = loadTexture(layer);
                if (texture == null) continue;
                graphics.drawImage(normalize(texture), 0, 0, null);
                rendered = true;
            }
        } finally {
            graphics.dispose();
        }
        return rendered ? result : null;
    }

    private BufferedImage loadTexture(String texture) {
        try {
            ResourceLocation location = ResourceLocation.parse(texture, "minecraft");
            String entryName = "assets/" + location.namespace + "/textures/" + location.path + ".png";
            if (textureCache.containsKey(entryName)) return textureCache.get(entryName);
            BufferedImage image = readTexture(texturePack, entryName);
            if (image == null) image = readTexture(jar, entryName);
            image = normalizeColorModel(firstAnimationFrame(image));
            textureCache.put(entryName, image);
            return image;
        } catch (IllegalArgumentException | IOException error) {
            return null;
        }
    }

    private static BufferedImage readTexture(ZipFile source, String entryName) throws IOException {
        if (source == null) return null;
        ZipEntry entry = source.getEntry(entryName);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_TEXTURE_BYTES) return null;
        try (InputStream input = source.getInputStream(entry)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1 ||
                image.getWidth() > 4096 || image.getHeight() > 4096) return null;
            return image;
        }
    }

    private static BufferedImage firstAnimationFrame(BufferedImage original) {
        if (original == null) return null;
        if (original.getHeight() > original.getWidth() && original.getHeight() % original.getWidth() == 0) {
            return original.getSubimage(0, 0, original.getWidth(), original.getWidth());
        }
        if (original.getWidth() > original.getHeight() && original.getWidth() % original.getHeight() == 0) {
            return original.getSubimage(0, 0, original.getHeight(), original.getHeight());
        }
        return original;
    }

    /**
     * ImageIO may retain an embedded grayscale color model. Direct getRGB sampling from that model
     * produced a second gamma conversion in the block rasterizer. Drawing once into standard ARGB
     * makes the sampled values match the texture's actual on-screen sRGB appearance.
     */
    private static BufferedImage normalizeColorModel(BufferedImage original) {
        if (original == null) return null;
        BufferedImage normalized = new BufferedImage(
            original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(original, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static String chooseTexture(Map<String, String> textures) {
        for (String preferred : new String[] {"layer0", "all", "side", "particle", "top", "end"}) {
            String resolved = resolveTextureReference(textures.get(preferred), textures);
            if (resolved != null) return resolved;
        }
        List<String> names = new ArrayList<String>(textures.keySet());
        Collections.sort(names);
        for (String name : names) {
            String resolved = resolveTextureReference(textures.get(name), textures);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private static String resolveTextureReference(String value, Map<String, String> textures) {
        Set<String> visited = new HashSet<String>();
        String current = value;
        if (current != null && !current.startsWith("#") && textures.containsKey(current)) current = "#" + current;
        while (current != null && current.startsWith("#")) {
            String variable = current.substring(1);
            if (!visited.add(variable)) return null;
            current = textures.get(variable);
        }
        if (current == null || current.trim().isEmpty() || "minecraft:missingno".equals(current)) return null;
        return current.trim();
    }

    private Map<String, Object> readObject(String path) throws IOException {
        ZipEntry entry = jar.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Missing JSON " + path);
        if (entry.getSize() > MAX_JSON_BYTES) throw new IllegalArgumentException("JSON entry too large " + path);
        try (InputStream input = jar.getInputStream(entry)) {
            return MiniJson.object(MiniJson.parse(new String(readLimited(input, MAX_JSON_BYTES), StandardCharsets.UTF_8)), path);
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) throw new IOException("ZIP entry exceeds safety limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static BufferedImage normalize(BufferedImage original) {
        BufferedImage frame = firstAnimationFrame(original);
        BufferedImage target = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale = Math.min((double) OUTPUT_SIZE / frame.getWidth(), (double) OUTPUT_SIZE / frame.getHeight());
            int width = Math.max(1, (int) Math.round(frame.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(frame.getHeight() * scale));
            int x = (OUTPUT_SIZE - width) / 2;
            int y = (OUTPUT_SIZE - height) / 2;
            graphics.drawImage(frame, x, y, x + width, y + height, 0, 0, frame.getWidth(), frame.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static boolean hasVisiblePixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) return true;
        }
        return false;
    }

    enum RenderKind { GENERATED_2D, BLOCK_MODEL, STATIC_FALLBACK }

    static final class BlockDiagnostic {
        private final String texture;
        private final BufferedImage source;
        private final BufferedImage unshaded;
        private final BufferedImage shaded;
        private BlockDiagnostic(String texture, BufferedImage source, BufferedImage unshaded, BufferedImage shaded) {
            this.texture = texture;
            this.source = source;
            this.unshaded = unshaded;
            this.shaded = shaded;
        }
        String getTexture() { return texture; }
        BufferedImage getSource() { return source; }
        BufferedImage getUnshaded() { return unshaded; }
        BufferedImage getShaded() { return shaded; }
    }

    static final class Resolution {
        private final BufferedImage image;
        private final String model;
        private final String texture;
        private final String reason;
        private final RenderKind kind;
        private Resolution(BufferedImage image, String model, String texture, String reason, RenderKind kind) {
            this.image = image; this.model = model; this.texture = texture; this.reason = reason; this.kind = kind;
        }
        static Resolution resolved(BufferedImage image, ResourceLocation model, String texture, RenderKind kind) {
            return new Resolution(image, model.toString(), texture, null, kind);
        }
        static Resolution unresolved(String reason) { return new Resolution(null, null, null, reason, null); }
        boolean isResolved() { return image != null; }
        BufferedImage getImage() { return image; }
        String getModel() { return model; }
        String getTexture() { return texture; }
        String getReason() { return reason; }
        RenderKind getKind() { return kind; }
    }

    private static final class ModelChoice {
        private final boolean supported;
        private final ResourceLocation model;
        private final String reason;
        private final List<Integer> tints;
        private ModelChoice(boolean supported, ResourceLocation model, String reason, List<Integer> tints) {
            this.supported = supported; this.model = model; this.reason = reason;
            this.tints = tints == null ? Collections.<Integer>emptyList() : new ArrayList<Integer>(tints);
        }
        private static ModelChoice supported(ResourceLocation model, List<Integer> tints) {
            return new ModelChoice(true, model, null, tints);
        }
        private static ModelChoice unsupported(String reason) {
            return new ModelChoice(false, null, reason, Collections.<Integer>emptyList());
        }
    }

    private static final class ModelData {
        private final Map<String, String> textures;
        private final List<BlockModelRenderer.Element> elements;
        private final BlockModelRenderer.Transform gui;
        private final boolean generated;
        private ModelData(Map<String, String> textures, List<BlockModelRenderer.Element> elements,
                          BlockModelRenderer.Transform gui, boolean generated) {
            this.textures = new LinkedHashMap<String, String>(textures);
            this.elements = new ArrayList<BlockModelRenderer.Element>(elements);
            this.gui = gui; this.generated = generated;
        }
        private static ModelData empty() {
            return new ModelData(Collections.<String, String>emptyMap(), Collections.<BlockModelRenderer.Element>emptyList(),
                null, false);
        }
    }

    static final class ResourceLocation {
        private final String namespace;
        private final String path;
        private ResourceLocation(String namespace, String path) { this.namespace = namespace; this.path = path; }
        static ResourceLocation parse(String value, String defaultNamespace) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            int colon = normalized.indexOf(':');
            if (colon != normalized.lastIndexOf(':')) throw new IllegalArgumentException("Invalid resource location");
            String namespace = colon >= 0 ? normalized.substring(0, colon) : defaultNamespace;
            String path = colon >= 0 ? normalized.substring(colon + 1) : normalized;
            if (!namespace.matches("[a-z0-9._-]+") || !path.matches("[a-z0-9._/-]+") ||
                path.startsWith("/") || path.endsWith("/") || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("Unsafe resource location " + value);
            }
            return new ResourceLocation(namespace, path);
        }
        @Override public String toString() { return namespace + ":" + path; }
    }
}
