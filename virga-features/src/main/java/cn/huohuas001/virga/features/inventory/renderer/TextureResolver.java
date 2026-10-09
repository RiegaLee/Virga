package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.armor.ArmorItemIconRenderer;
import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider;
import cn.huohuas001.virga.features.inventory.head.PlayerHeadIconCache;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Safe per-theme texture lookup with a mandatory unknown fallback. */
public final class TextureResolver {
    private final Path customOverrideDirectory;
    private final Path overrideDirectory;
    private final Path specialVariantDirectory;
    private final Path textureDirectory;
    private final Path fallbackPath;
    private final BufferedImage fallback;
    private final VanillaImportedAssetProvider vanilla;
    private final Clock clock;
    private final Map<String, BufferedImage> cache = new HashMap<String, BufferedImage>();
    private final Set<String> reportedMissing = new LinkedHashSet<String>();
    private final Set<String> reportedResolutions = new LinkedHashSet<String>();
    private Consumer<String> missingReporter = materialKey -> { };
    private Consumer<ResolutionTrace> resolutionReporter = trace -> { };
    private ArmorItemIconRenderer armorItemRenderer;
    private RuntimeItemIconRenderer runtimeItemRenderer;
    private PlayerHeadIconCache playerHeadIcons;

    TextureResolver(Path textureDirectory, Path fallbackPath) {
        this(
            null,
            textureDirectory,
            null,
            textureDirectory,
            fallbackPath,
            VanillaImportedAssetProvider.disabled(textureDirectory, "Vanilla provider not configured"),
            Clock.systemDefaultZone()
        );
    }

    TextureResolver(
        Path textureDirectory,
        Path fallbackPath,
        VanillaImportedAssetProvider vanilla
    ) {
        this(null, textureDirectory, null, textureDirectory, fallbackPath, vanilla, Clock.systemDefaultZone());
    }

    TextureResolver(
        Path overrideDirectory,
        Path textureDirectory,
        Path fallbackPath,
        VanillaImportedAssetProvider vanilla
    ) {
        this(null, overrideDirectory, null, textureDirectory, fallbackPath, vanilla, Clock.systemDefaultZone());
    }

    TextureResolver(
        Path customOverrideDirectory,
        Path overrideDirectory,
        Path textureDirectory,
        Path fallbackPath,
        VanillaImportedAssetProvider vanilla
    ) {
        this(
            customOverrideDirectory, overrideDirectory, null, textureDirectory, fallbackPath, vanilla,
            Clock.systemDefaultZone()
        );
    }

    TextureResolver(
        Path customOverrideDirectory,
        Path overrideDirectory,
        Path specialVariantDirectory,
        Path textureDirectory,
        Path fallbackPath,
        VanillaImportedAssetProvider vanilla,
        Clock clock
    ) {
        this.customOverrideDirectory = customOverrideDirectory == null ? null :
            customOverrideDirectory.toAbsolutePath().normalize();
        this.overrideDirectory = Objects.requireNonNull(overrideDirectory, "overrideDirectory")
            .toAbsolutePath().normalize();
        this.specialVariantDirectory = specialVariantDirectory == null ? null :
            specialVariantDirectory.toAbsolutePath().normalize();
        this.textureDirectory = Objects.requireNonNull(textureDirectory, "textureDirectory").toAbsolutePath().normalize();
        this.fallbackPath = Objects.requireNonNull(fallbackPath, "fallbackPath").toAbsolutePath().normalize();
        this.fallback = readRequired(this.fallbackPath, "fallback texture");
        this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized ResolvedTexture resolve(ItemSnapshot item) {
        Objects.requireNonNull(item, "item");
        ResolvedTexture base = resolveBase(item);
        if (armorItemRenderer == null || item.getArmorVisual() == null) return base;
        BufferedImage composite = armorItemRenderer.render(item, base.image);
        return composite == base.image ? base : new ResolvedTexture(composite, Source.RUNTIME_COMPOSITE, null);
    }

    private ResolvedTexture resolveBase(ItemSnapshot item) {
        ResolvedTexture custom = customOverrideDirectory == null ? null :
            resolveTheme(item, customOverrideDirectory, Source.CUSTOM_OVERRIDE);
        if (custom != null) {
            reportResolution(item, custom.source, "CUSTOM", "CUSTOM_OVERRIDE", "USER", custom.file);
            return custom;
        }
        if (playerHeadIcons != null) {
            Optional<BufferedImage> playerHead = playerHeadIcons.find(item);
            if (playerHead.isPresent()) {
                reportResolution(item, Source.PLAYER_HEAD, "DYNAMIC", "PLAYER_HEAD_CACHE", "MOJANG_TEXTURE", null);
                return new ResolvedTexture(playerHead.get(), Source.PLAYER_HEAD, null);
            }
        }
        ResolvedTexture specialVariant = specialVariantDirectory == null ? null :
            resolveTheme(item, specialVariantDirectory, Source.GENERATED_SPECIAL_STATIC);
        if (specialVariant != null) {
            reportResolution(
                item, specialVariant.source, "SPECIAL", "GENERATED_SPECIAL_STATIC",
                "THEME_SEASONAL_VARIANT", specialVariant.file
            );
            return specialVariant;
        }
        ResolvedTexture override = resolveTheme(item, overrideDirectory, Source.EXPLICIT_OVERRIDE);
        if (override != null) {
            reportResolution(item, override.source, "SPECIAL", "EXPLICIT_OVERRIDE", "THEME", override.file);
            return override;
        }
        if (runtimeItemRenderer != null) {
            RuntimeItemIconRenderer.Result runtime = runtimeItemRenderer.render(item);
            if (runtime != null) {
                reportResolution(
                    item, runtime.source,
                    runtime.source == Source.GUI_MODEL ? "GENERATED" : "DYNAMIC",
                    runtime.renderPath,
                    "RESOURCE_PACK_RUNTIME_LAYERS",
                    runtime.file
                );
                return new ResolvedTexture(runtime.image, runtime.source, runtime.file);
            }
        }
        Optional<BufferedImage> imported = vanilla.resolve(item.getMaterialKey());
        if (imported.isPresent()) {
            String renderPath = vanilla.getRenderPath(item.getMaterialKey()).orElse("VANILLA_STATIC");
            Path file = vanilla.getIconPath(item.getMaterialKey()).orElse(null);
            reportResolution(
                item, Source.GENERATED_CACHE, classification(renderPath), renderPath,
                vanilla.getTextureSource(), file
            );
            return new ResolvedTexture(imported.get(), Source.GENERATED_CACHE, file);
        }
        ResolvedTexture legacy = resolveTheme(item, textureDirectory, Source.LEGACY_STATIC);
        if (legacy != null) {
            reportResolution(item, legacy.source, "LEGACY", "LEGACY_STATIC", "THEME", legacy.file);
            return legacy;
        }
        Source fallbackSource = isUnsupportedSpecial(item.getMaterialKey()) ?
            Source.SPECIAL_UNSUPPORTED : Source.UNKNOWN;
        String fallbackPathName = fallbackSource == Source.SPECIAL_UNSUPPORTED ? "SPECIAL_UNSUPPORTED" : "UNKNOWN";
        reportResolution(
            item, fallbackSource,
            fallbackSource == Source.SPECIAL_UNSUPPORTED ? "SPECIAL" : "UNKNOWN",
            fallbackPathName, "NONE", fallbackPath
        );
        reportMissing(item);
        return new ResolvedTexture(fallback, fallbackSource, fallbackPath);
    }

    private ResolvedTexture resolveTheme(ItemSnapshot item, Path directory, Source source) {
        for (String name : candidateNames(item)) {
            String cacheKey = source.name() + ":" + name;
            BufferedImage cached = cache.get(cacheKey);
            Path candidate = directory.resolve(name + ".png").normalize();
            if (cached != null) return new ResolvedTexture(cached, source, candidate);
            if (!candidate.startsWith(directory) || !Files.isRegularFile(candidate)) continue;
            try {
                BufferedImage image = ImageIO.read(candidate.toFile());
                if (image == null) continue;
                cache.put(cacheKey, image);
                return new ResolvedTexture(image, source, candidate);
            } catch (IOException ignored) {
                // Continue to the next provider.
            }
        }
        return null;
    }

    /** Reports each safe missing material key at most once for this plugin start. */
    public synchronized void setMissingReporter(Consumer<String> reporter) {
        this.missingReporter = Objects.requireNonNull(reporter, "reporter");
    }

    /** Reports the final provider decision for each material key once per plugin start. */
    public synchronized void setResolutionReporter(Consumer<ResolutionTrace> reporter) {
        this.resolutionReporter = Objects.requireNonNull(reporter, "reporter");
    }

    public synchronized void setArmorItemRenderer(ArmorItemIconRenderer renderer) {
        this.armorItemRenderer = Objects.requireNonNull(renderer, "renderer");
    }

    public synchronized void setPlayerHeadIcons(PlayerHeadIconCache cache) {
        this.playerHeadIcons = Objects.requireNonNull(cache, "cache");
    }

    synchronized void setRuntimeItemRenderer(RuntimeItemIconRenderer renderer) {
        this.runtimeItemRenderer = Objects.requireNonNull(renderer, "renderer");
    }

    public synchronized Coverage coverage(Collection<String> materialKeys) {
        Objects.requireNonNull(materialKeys, "materialKeys");
        int explicit = 0;
        int vanillaCount = 0;
        int legacy = 0;
        int runtime = 0;
        int specialUnsupported = 0;
        List<String> unknown = new ArrayList<String>();
        List<String> paths = new ArrayList<String>();
        Set<String> unique = new LinkedHashSet<String>();
        for (String raw : materialKeys) {
            String material = normalizeMaterialKey(raw);
            if (material == null || !unique.add(material)) continue;
            ItemSnapshot item = ItemSnapshot.basic(material, 1);
            if (customOverrideDirectory != null && hasThemeTexture(item, customOverrideDirectory)) {
                explicit++;
                paths.add(material + "\tCUSTOM_OVERRIDE");
            } else if (hasThemeTexture(item, overrideDirectory)) {
                explicit++;
                paths.add(material + "\tEXPLICIT_OVERRIDE");
            } else if (runtimeItemRenderer != null && runtimeItemRenderer.supports(material)) {
                runtime++;
                paths.add(material + "\t" + (
                    "minecraft:trident".equals(material) ? "GENERATED_2D_GUI_MODEL" : "POTION_TINT"
                ));
            } else if (vanilla.contains(material)) {
                vanillaCount++;
                paths.add(material + "\t" + vanilla.getRenderPath(material).orElse("VANILLA_STATIC"));
            } else if (hasThemeTexture(item, textureDirectory)) {
                legacy++;
                paths.add(material + "\tLEGACY_STATIC");
            } else if (isUnsupportedSpecial(material)) {
                specialUnsupported++;
                paths.add(material + "\tSPECIAL_UNSUPPORTED");
            } else {
                unknown.add(material);
                paths.add(material + "\tUNKNOWN");
            }
        }
        Collections.sort(unknown);
        Collections.sort(paths);
        return new Coverage(unique.size(), explicit, vanillaCount, runtime, legacy, specialUnsupported, unknown, paths);
    }

    private boolean hasThemeTexture(ItemSnapshot item, Path directory) {
        for (String name : candidateNames(item)) {
            Path candidate = directory.resolve(name + ".png").normalize();
            if (candidate.startsWith(directory) && Files.isRegularFile(candidate)) return true;
        }
        return false;
    }

    private boolean isUnsupportedSpecial(String materialKey) {
        return "SPECIAL_RENDERER".equals(vanilla.getUnresolvedReason(materialKey).orElse(null));
    }

    private void reportResolution(
        ItemSnapshot item,
        Source source,
        String classification,
        String renderPath,
        String textureSource,
        Path file
    ) {
        String material = normalizeMaterialKey(item.getMaterialKey());
        if (material != null && reportedResolutions.add(material)) {
            resolutionReporter.accept(new ResolutionTrace(
                material, source, classification, renderPath, textureSource,
                file == null ? "unavailable" : file.toAbsolutePath().normalize().toString()
            ));
        }
    }

    private void reportMissing(ItemSnapshot item) {
        String materialKey = normalizeMaterialKey(item.getMaterialKey());
        if (materialKey == null) return;
        if (reportedMissing.add(materialKey)) missingReporter.accept(materialKey);
    }

    private static String normalizeMaterialKey(String value) {
        String materialKey = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!materialKey.matches("[a-z0-9._-]+:[a-z0-9._/-]+") ||
            materialKey.contains("..") || materialKey.contains("//") || materialKey.endsWith("/")) return null;
        return materialKey;
    }

    private List<String> candidateNames(ItemSnapshot item) {
        Set<String> names = new LinkedHashSet<String>();
        if (item.getTextureHint() != null) {
            String hint = safeTexturePath(item.getTextureHint());
            if (hint != null) names.add(hint);
            return new ArrayList<String>(names);
        }
        String materialKey = item.getMaterialKey().trim().toLowerCase(Locale.ROOT);
        int colon = materialKey.indexOf(':');
        if (colon != materialKey.lastIndexOf(':')) return new ArrayList<String>();
        String namespace = colon >= 0 ? materialKey.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? materialKey.substring(colon + 1) : materialKey;
        if (!namespace.matches("[a-z0-9._-]+")) return new ArrayList<String>();
        path = safeTexturePath(path);
        if (path == null) return new ArrayList<String>();
        if ("minecraft".equals(namespace) && "trapped_chest".equals(path) && isChristmas(clock)) {
            names.add("minecraft/trapped_chest_christmas");
            names.add("trapped_chest_christmas");
        }
        names.add(namespace + "/" + path);
        if ("minecraft".equals(namespace)) names.add(path);
        return new ArrayList<String>(names);
    }

    private static String safeTexturePath(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._/-]+") ||
            normalized.startsWith("/") ||
            normalized.endsWith("/") ||
            normalized.contains("..") ||
            normalized.contains("//")) {
            return null;
        }
        return normalized;
    }

    static String seasonalVariant(Clock clock) {
        return isChristmas(Objects.requireNonNull(clock, "clock")) ? "CHRISTMAS" : "NORMAL";
    }

    private static boolean isChristmas(Clock clock) {
        LocalDate date = LocalDate.now(clock);
        return date.getMonthValue() == 12 && date.getDayOfMonth() >= 24 && date.getDayOfMonth() <= 26;
    }

    private static BufferedImage readRequired(Path path, String label) {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing " + label + ": " + path);
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IllegalArgumentException("Unreadable " + label + ": " + path);
            return image;
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read " + label + ": " + path, error);
        }
    }

    private static String classification(String renderPath) {
        if ("BLOCK_MODEL".equals(renderPath)) return "BLOCK";
        if ("GENERATED_2D".equals(renderPath)) return "GENERATED";
        if ("STATIC_FALLBACK".equals(renderPath) || "VANILLA_STATIC".equals(renderPath)) return "STATIC";
        return "UNKNOWN";
    }

    public static final class ResolvedTexture {
        private final BufferedImage image;
        private final Source source;
        private final Path file;

        private ResolvedTexture(BufferedImage image, Source source, Path file) {
            this.image = image;
            this.source = source;
            this.file = file;
        }

        BufferedImage getImage() { return image; }
        public boolean isFallback() { return source == Source.UNKNOWN || source == Source.SPECIAL_UNSUPPORTED; }
        public Source getSource() { return source; }
    }

    public enum Source {
        CUSTOM_OVERRIDE, EXPLICIT_OVERRIDE, GENERATED_SPECIAL_STATIC, GENERATED_CACHE, GUI_MODEL, RUNTIME_COMPOSITE,
        PLAYER_HEAD,
        LEGACY_STATIC, SPECIAL_UNSUPPORTED, UNKNOWN
    }

    public static final class ResolutionTrace {
        private final String materialKey;
        private final Source source;
        private final String classification;
        private final String renderPath;
        private final String textureSource;
        private final String finalFile;

        private ResolutionTrace(
            String materialKey,
            Source source,
            String classification,
            String renderPath,
            String textureSource,
            String finalFile
        ) {
            this.materialKey = materialKey;
            this.source = source;
            this.classification = classification;
            this.renderPath = renderPath;
            this.textureSource = textureSource;
            this.finalFile = finalFile;
        }

        public String getMaterialKey() { return materialKey; }
        public Source getSource() { return source; }
        public String getClassification() { return classification; }
        public String getRenderPath() { return renderPath; }
        public String getTextureSource() { return textureSource; }
        public String getFinalFile() { return finalFile; }

        public String toLogMessage() {
            return materialKey + ": Classification=" + classification +
                " RenderPath=" + renderPath + " TextureSource=" + textureSource +
                " FinalSource=" + source + " FinalFile=" + finalFile;
        }
    }

    public static final class Coverage {
        private final int total;
        private final int explicitOverrides;
        private final int vanilla;
        private final int runtimeComposite;
        private final int legacyStatic;
        private final int specialUnsupported;
        private final List<String> unknown;
        private final List<String> resolutionPaths;

        private Coverage(
            int total,
            int explicitOverrides,
            int vanilla,
            int runtimeComposite,
            int legacyStatic,
            int specialUnsupported,
            List<String> unknown,
            List<String> resolutionPaths
        ) {
            this.total = total;
            this.explicitOverrides = explicitOverrides;
            this.vanilla = vanilla;
            this.runtimeComposite = runtimeComposite;
            this.legacyStatic = legacyStatic;
            this.specialUnsupported = specialUnsupported;
            this.unknown = Collections.unmodifiableList(new ArrayList<String>(unknown));
            this.resolutionPaths = Collections.unmodifiableList(new ArrayList<String>(resolutionPaths));
        }

        public int getTotal() { return total; }
        public int getTheme() { return explicitOverrides; }
        public int getExplicitOverrides() { return explicitOverrides; }
        public int getVanilla() { return vanilla; }
        public int getRuntimeComposite() { return runtimeComposite; }
        public int getLegacyStatic() { return legacyStatic; }
        public int getSpecialUnsupported() { return specialUnsupported; }
        public int getUnknownCount() { return unknown.size(); }
        public List<String> getUnknown() { return unknown; }
        public List<String> getResolutionPaths() { return resolutionPaths; }
    }
}
