package cn.huohuas001.virga.features.inventory.asset;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only runtime view of a locally imported Vanilla icon cache. */
public final class VanillaImportedAssetProvider {
    private final boolean available;
    private final Path cacheRoot;
    private final Path iconRoot;
    private final String minecraftVersion;
    private final int generatedIcons;
    private final int totalDefinitions;
    private final String diagnostic;
    private final Map<String, String> renderPaths;
    private final Map<String, String> unresolvedReasons;
    private final String textureSource;
    private final Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();
    private final Set<String> misses = new HashSet<String>();

    private VanillaImportedAssetProvider(
        boolean available,
        Path cacheRoot,
        Path iconRoot,
        String minecraftVersion,
        int generatedIcons,
        int totalDefinitions,
        String diagnostic,
        Map<String, String> renderPaths,
        Map<String, String> unresolvedReasons,
        String textureSource
    ) {
        this.available = available;
        this.cacheRoot = cacheRoot;
        this.iconRoot = iconRoot;
        this.minecraftVersion = minecraftVersion;
        this.generatedIcons = generatedIcons;
        this.totalDefinitions = totalDefinitions;
        this.diagnostic = diagnostic;
        this.renderPaths = Collections.unmodifiableMap(new HashMap<String, String>(renderPaths));
        this.unresolvedReasons = Collections.unmodifiableMap(new HashMap<String, String>(unresolvedReasons));
        this.textureSource = textureSource;
    }

    public static VanillaImportedAssetProvider disabled(Path cacheRoot, String diagnostic) {
        Path root = cacheRoot.toAbsolutePath().normalize();
        return new VanillaImportedAssetProvider(
            false, root, null, null, 0, 0, diagnostic,
            Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "NONE"
        );
    }

    public static VanillaImportedAssetProvider open(Path cacheRoot) {
        Path root = cacheRoot.toAbsolutePath().normalize();
        Path pointer = root.resolve("current-version.txt").normalize();
        if (!pointer.startsWith(root) || !Files.isRegularFile(pointer)) {
            return disabled(root, "current-version.txt is missing; run VanillaAssetImporter locally");
        }
        try {
            String cacheKey = new String(Files.readAllBytes(pointer), StandardCharsets.UTF_8).trim();
            if (!cacheKey.matches("[A-Za-z0-9._-]+")) return disabled(root, "current cache key is unsafe");
            Path versionRoot = root.resolve(cacheKey).normalize();
            if (!versionRoot.startsWith(root)) return disabled(root, "current cache path escapes root");
            Path metadataPath = versionRoot.resolve("metadata.json");
            Path icons = versionRoot.resolve("generated-icons").normalize();
            if (!Files.isRegularFile(metadataPath) || !Files.isDirectory(icons)) {
                return disabled(root, "current cache is incomplete: " + cacheKey);
            }
            Map<String, Object> metadata = MiniJson.object(
                MiniJson.parse(new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8)),
                "metadata.json"
            );
            Map<String, String> renderPaths = readRenderPaths(versionRoot.resolve("render-paths.tsv"));
            Map<String, String> unresolvedReasons = readUnresolvedReasons(versionRoot.resolve("unresolved.txt"));
            String resourcePack = MiniJson.string(metadata, "textureResourcePack", false);
            return new VanillaImportedAssetProvider(
                true,
                root,
                icons,
                MiniJson.string(metadata, "minecraftVersion", true),
                MiniJson.integer(metadata, "generatedIcons", 0),
                MiniJson.integer(metadata, "totalDefinitions", 0),
                "ready",
                renderPaths,
                unresolvedReasons,
                resourcePack == null ? "VANILLA_CLIENT" : "RESOURCE_PACK:" + resourcePack
            );
        } catch (IOException | IllegalArgumentException error) {
            return disabled(root, "could not read current cache: " + error.getMessage());
        }
    }

    public synchronized Optional<BufferedImage> resolve(String materialKey) {
        String safe = safeMaterialPath(materialKey);
        if (!available || safe == null || misses.contains(safe)) return Optional.empty();
        BufferedImage cached = images.get(safe);
        if (cached != null) return Optional.of(cached);
        Path candidate = iconRoot.resolve(safe + ".png").normalize();
        if (!candidate.startsWith(iconRoot) || !Files.isRegularFile(candidate)) {
            misses.add(safe);
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(candidate.toFile());
            if (image == null) {
                misses.add(safe);
                return Optional.empty();
            }
            images.put(safe, image);
            return Optional.of(image);
        } catch (IOException error) {
            misses.add(safe);
            return Optional.empty();
        }
    }

    public boolean contains(String materialKey) {
        String safe = safeMaterialPath(materialKey);
        if (!available || safe == null) return false;
        Path candidate = iconRoot.resolve(safe + ".png").normalize();
        return candidate.startsWith(iconRoot) && Files.isRegularFile(candidate);
    }

    public Optional<String> getRenderPath(String materialKey) {
        String safe = safeMaterialKey(materialKey);
        return safe == null ? Optional.<String>empty() : Optional.ofNullable(renderPaths.get(safe));
    }

    public Optional<String> getUnresolvedReason(String materialKey) {
        String safe = safeMaterialKey(materialKey);
        return safe == null ? Optional.<String>empty() : Optional.ofNullable(unresolvedReasons.get(safe));
    }

    public Optional<Path> getIconPath(String materialKey) {
        String safe = safeMaterialPath(materialKey);
        if (!available || safe == null) return Optional.empty();
        Path candidate = iconRoot.resolve(safe + ".png").normalize();
        return candidate.startsWith(iconRoot) && Files.isRegularFile(candidate) ?
            Optional.of(candidate) : Optional.<Path>empty();
    }

    private static Map<String, String> readRenderPaths(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Collections.emptyMap();
        Map<String, String> result = new HashMap<String, String>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\\t", -1);
            if (columns.length != 2 || safeMaterialKey(columns[0]) == null ||
                !columns[1].matches("[A-Z0-9_]+")) continue;
            result.put(columns[0], columns[1]);
        }
        return result;
    }

    private static Map<String, String> readUnresolvedReasons(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Collections.emptyMap();
        Map<String, String> result = new HashMap<String, String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            String key = columns.length == 2 ? safeMaterialKey(columns[0]) : null;
            if (key == null || !columns[1].matches("[A-Z0-9_]+")) continue;
            result.put(key, columns[1]);
        }
        return result;
    }

    private static String safeMaterialPath(String value) {
        String normalized = safeMaterialKey(value);
        if (normalized == null) return null;
        int colon = normalized.indexOf(':');
        String namespace = normalized.substring(0, colon);
        String path = normalized.substring(colon + 1);
        return namespace + "/" + path;
    }

    private static String safeMaterialKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon < 1 || colon != normalized.lastIndexOf(':')) return null;
        String namespace = normalized.substring(0, colon);
        String path = normalized.substring(colon + 1);
        if (!namespace.matches("[a-z0-9._-]+") || !path.matches("[a-z0-9._/-]+") ||
            path.startsWith("/") || path.endsWith("/") || path.contains("..") || path.contains("//")) return null;
        return normalized;
    }

    public boolean isAvailable() { return available; }
    public Path getCacheRoot() { return cacheRoot; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public int getGeneratedIcons() { return generatedIcons; }
    public int getTotalDefinitions() { return totalDefinitions; }
    public String getDiagnostic() { return diagnostic; }
    public String getTextureSource() { return textureSource; }
}
