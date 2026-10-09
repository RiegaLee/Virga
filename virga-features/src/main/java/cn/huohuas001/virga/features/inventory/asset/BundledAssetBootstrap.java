package cn.huohuas001.virga.features.inventory.asset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Installs and repairs the immutable runtime asset pack embedded in the addon JAR. */
public final class BundledAssetBootstrap {
    public static final String MANIFEST_RESOURCE = "bundled-assets/asset-manifest.json";
    private static final String PACK_RESOURCE_ROOT = "bundled-assets/pack/";

    private BundledAssetBootstrap() {}

    public static Installation install(Path dataDirectory, ResourceSource resources) throws IOException {
        return install(dataDirectory, resources, MANIFEST_RESOURCE);
    }

    /**
     * Older Virga versions extracted their packs next to the current one; they are never read
     * again, so they are deleted once the current pack is in place. Custom assets live elsewhere.
     */
    private static void removeStalePacks(Path bundledRoot, Path packRoot) throws IOException {
        List<Path> stale;
        try (Stream<Path> children = Files.list(bundledRoot)) {
            stale = children.filter(Files::isDirectory).filter(dir -> !dir.equals(packRoot)).collect(Collectors.toList());
        }
        for (Path pack : stale) {
            requireChild(bundledRoot, pack.normalize(), "stale asset pack");
            try (Stream<Path> walk = Files.walk(pack)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    static Installation install(Path dataDirectory, ResourceSource resources, String manifestResource)
        throws IOException {
        Path dataRoot = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        Objects.requireNonNull(resources, "resources");
        Manifest manifest;
        try (InputStream input = requireResource(resources, manifestResource)) {
            manifest = Manifest.parse(readAll(input));
        }

        Path assetsRoot = dataRoot.resolve("assets").normalize();
        Path bundledRoot = assetsRoot.resolve("bundled").normalize();
        Path customRoot = assetsRoot.resolve("custom").normalize();
        Path packRoot = bundledRoot.resolve(manifest.packId).normalize();
        requireChild(dataRoot, assetsRoot, "assets root");
        requireChild(bundledRoot, packRoot, "asset pack root");
        Files.createDirectories(packRoot);
        Files.createDirectories(customRoot.resolve("overrides/items"));
        Files.createDirectories(customRoot.resolve("themes"));

        int installed = 0;
        int reused = 0;
        for (ManifestFile file : manifest.files) {
            Path destination = packRoot.resolve(file.path).normalize();
            requireChild(packRoot, destination, "manifest destination");
            if (matches(destination, file)) {
                reused++;
                continue;
            }
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".asset-", ".tmp");
            boolean moved = false;
            try (InputStream input = requireResource(resources, PACK_RESOURCE_ROOT + file.path)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!matches(temporary, file)) {
                    throw new IOException("Bundled asset failed integrity verification: " + file.path);
                }
                moveReplace(temporary, destination);
                moved = true;
                installed++;
            } finally {
                if (!moved) Files.deleteIfExists(temporary);
            }
        }
        verifyPackDigest(manifest);
        removeStalePacks(bundledRoot, packRoot);
        writeVersionRecord(dataRoot.resolve("assets-version.json"), manifest, installed, reused);
        return new Installation(
            manifest.packId,
            manifest.assetPackVersion,
            manifest.packSha256,
            packRoot,
            packRoot.resolve("vanilla"),
            packRoot.resolve("themes"),
            customRoot,
            manifest.generatedIcons,
            manifest.totalDefinitions,
            installed,
            reused
        );
    }

    private static void verifyPackDigest(Manifest manifest) throws IOException {
        MessageDigest digest = sha256Digest();
        for (ManifestFile file : manifest.files) {
            digest.update(file.path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.sha256.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(Long.toString(file.size).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        String actual = hex(digest.digest());
        if (!actual.equals(manifest.packSha256)) {
            throw new IOException("Bundled asset manifest pack digest mismatch");
        }
    }

    private static void writeVersionRecord(Path destination, Manifest manifest, int installed, int reused)
        throws IOException {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("schemaVersion", Integer.valueOf(1));
        record.put("activePackId", manifest.packId);
        record.put("assetPackVersion", Integer.valueOf(manifest.assetPackVersion));
        record.put("compatibleInventoryVersion", manifest.compatibleInventoryVersion);
        record.put("packSha256", manifest.packSha256);
        record.put("managedFiles", Integer.valueOf(manifest.files.size()));
        record.put("installedOrRepaired", Integer.valueOf(installed));
        record.put("reused", Integer.valueOf(reused));
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".assets-version-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, MiniJson.stringify(record).getBytes(StandardCharsets.UTF_8));
            moveReplace(temporary, destination);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean matches(Path path, ManifestFile expected) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) == expected.size &&
            expected.sha256.equals(sha256(path));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte part : bytes) value.append(String.format(Locale.ROOT, "%02X", part & 0xff));
        return value.toString();
    }

    private static InputStream requireResource(ResourceSource resources, String path) throws IOException {
        InputStream input = resources.open(path);
        if (input == null) throw new IOException("Missing bundled asset resource " + path);
        return input;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void requireChild(Path root, Path child, String label) {
        if (!child.startsWith(root) || child.equals(root)) {
            throw new IllegalArgumentException(label + " escapes its managed directory");
        }
    }

    @FunctionalInterface
    public interface ResourceSource {
        InputStream open(String path) throws IOException;
    }

    public static final class Installation {
        private final String packId;
        private final int assetPackVersion;
        private final String packSha256;
        private final Path packRoot;
        private final Path vanillaRoot;
        private final Path themesRoot;
        private final Path customRoot;
        private final int generatedIcons;
        private final int totalDefinitions;
        private final int installedFiles;
        private final int reusedFiles;

        private Installation(
            String packId,
            int assetPackVersion,
            String packSha256,
            Path packRoot,
            Path vanillaRoot,
            Path themesRoot,
            Path customRoot,
            int generatedIcons,
            int totalDefinitions,
            int installedFiles,
            int reusedFiles
        ) {
            this.packId = packId;
            this.assetPackVersion = assetPackVersion;
            this.packSha256 = packSha256;
            this.packRoot = packRoot;
            this.vanillaRoot = vanillaRoot;
            this.themesRoot = themesRoot;
            this.customRoot = customRoot;
            this.generatedIcons = generatedIcons;
            this.totalDefinitions = totalDefinitions;
            this.installedFiles = installedFiles;
            this.reusedFiles = reusedFiles;
        }

        public String getPackId() { return packId; }
        public int getAssetPackVersion() { return assetPackVersion; }
        public String getPackSha256() { return packSha256; }
        public Path getPackRoot() { return packRoot; }
        public Path getVanillaRoot() { return vanillaRoot; }
        public Path getThemesRoot() { return themesRoot; }
        public Path getCustomRoot() { return customRoot; }
        public int getGeneratedIcons() { return generatedIcons; }
        public int getTotalDefinitions() { return totalDefinitions; }
        public int getInstalledFiles() { return installedFiles; }
        public int getReusedFiles() { return reusedFiles; }
    }

    private static final class Manifest {
        private final int assetPackVersion;
        private final String packId;
        private final String compatibleInventoryVersion;
        private final String packSha256;
        private final int generatedIcons;
        private final int totalDefinitions;
        private final List<ManifestFile> files;

        private Manifest(
            int assetPackVersion,
            String packId,
            String compatibleInventoryVersion,
            String packSha256,
            int generatedIcons,
            int totalDefinitions,
            List<ManifestFile> files
        ) {
            this.assetPackVersion = assetPackVersion;
            this.packId = packId;
            this.compatibleInventoryVersion = compatibleInventoryVersion;
            this.packSha256 = packSha256;
            this.generatedIcons = generatedIcons;
            this.totalDefinitions = totalDefinitions;
            this.files = files;
        }

        private static Manifest parse(String json) {
            Map<String, Object> object = MiniJson.object(MiniJson.parse(json), "asset manifest");
            if (MiniJson.integer(object, "schemaVersion", 0) != 1) {
                throw new IllegalArgumentException("Unsupported bundled asset manifest schema");
            }
            int assetPackVersion = MiniJson.integer(object, "assetPackVersion", 0);
            String packId = MiniJson.string(object, "packId", true);
            String compatibleVersion = MiniJson.string(object, "compatibleInventoryVersion", true);
            String packSha256 = MiniJson.string(object, "packSha256", true).toUpperCase(Locale.ROOT);
            if (assetPackVersion < 1 || !packId.matches("[A-Za-z0-9._-]+") ||
                !packSha256.matches("[0-9A-F]{64}")) {
                throw new IllegalArgumentException("Invalid bundled asset manifest identity");
            }
            List<Object> rawFiles = MiniJson.array(object.get("files"), "files");
            if (rawFiles.isEmpty()) throw new IllegalArgumentException("Bundled asset manifest is empty");
            List<ManifestFile> files = new ArrayList<ManifestFile>();
            String previous = null;
            for (Object raw : rawFiles) {
                Map<String, Object> entry = MiniJson.object(raw, "file entry");
                String path = MiniJson.string(entry, "path", true);
                String sha256 = MiniJson.string(entry, "sha256", true).toUpperCase(Locale.ROOT);
                Object sizeValue = entry.get("size");
                long size = sizeValue instanceof Number ? ((Number) sizeValue).longValue() : -1;
                if (!safeRelative(path) || !sha256.matches("[0-9A-F]{64}") || size < 0) {
                    throw new IllegalArgumentException("Unsafe bundled asset manifest file " + path);
                }
                if (previous != null && previous.compareTo(path) >= 0) {
                    throw new IllegalArgumentException("Bundled asset manifest files are not uniquely sorted");
                }
                files.add(new ManifestFile(path, sha256, size));
                previous = path;
            }
            return new Manifest(
                assetPackVersion,
                packId,
                compatibleVersion,
                packSha256,
                MiniJson.integer(object, "generatedIcons", 0),
                MiniJson.integer(object, "totalDefinitions", 0),
                files
            );
        }

        private static boolean safeRelative(String path) {
            return !path.isEmpty() && !path.startsWith("/") && !path.endsWith("/") &&
                !path.contains("\\") && !path.contains("//") && !path.contains("../") &&
                !path.equals("..") && !path.contains("/../") && !path.matches("^[A-Za-z]:.*");
        }
    }

    private static final class ManifestFile {
        private final String path;
        private final String sha256;
        private final long size;

        private ManifestFile(String path, String sha256, long size) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
        }
    }
}
