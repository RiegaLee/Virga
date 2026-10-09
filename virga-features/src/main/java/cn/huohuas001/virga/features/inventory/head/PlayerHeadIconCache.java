package cn.huohuas001.virga.features.inventory.head;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking player-head icon cache. Request/render threads only touch the bounded in-memory LRU;
 * disk reads, HTTPS downloads, PNG decode/crop and atomic writes run on the supplied bounded executor.
 */
public final class PlayerHeadIconCache implements AutoCloseable {
    static final int ICON_SIZE = 64;
    static final int MAX_SKIN_BYTES = 1024 * 1024;

    private final Object lock = new Object();
    private final Path root;
    private final Logger logger;
    private final Executor executor;
    private final TextureLoader loader;
    private final OwnerTextureResolver ownerResolver;
    private final Clock clock;
    private final long negativeTtlMillis;
    private final int queueCapacity;
    private final int maxConcurrent;
    private final LinkedHashMap<String, BufferedImage> memory;
    private final LinkedHashMap<String, Long> negative;
    private final Set<String> scheduled = new HashSet<String>();
    private final Map<String, CompletableFuture<Void>> completions = new HashMap<String, CompletableFuture<Void>>();
    private final ArrayDeque<PlayerHeadVisualDescriptor> pending = new ArrayDeque<PlayerHeadVisualDescriptor>();
    private int active;
    private boolean closed;

    public PlayerHeadIconCache(
        Path root,
        Logger logger,
        Executor executor,
        int memoryEntries,
        int queueCapacity,
        int maxConcurrent,
        Duration negativeTtl,
        int connectTimeoutMs,
        int readTimeoutMs
    ) {
        this(
            root, logger, executor, memoryEntries, queueCapacity, maxConcurrent, negativeTtl,
            connectTimeoutMs, readTimeoutMs, (uuid, name) -> null
        );
    }

    public PlayerHeadIconCache(
        Path root,
        Logger logger,
        Executor executor,
        int memoryEntries,
        int queueCapacity,
        int maxConcurrent,
        Duration negativeTtl,
        int connectTimeoutMs,
        int readTimeoutMs,
        OwnerTextureResolver ownerResolver
    ) {
        this(
            root, logger, executor, memoryEntries, queueCapacity, maxConcurrent, negativeTtl,
            Clock.systemUTC(), hash -> download(hash, connectTimeoutMs, readTimeoutMs), ownerResolver
        );
    }

    PlayerHeadIconCache(
        Path root,
        Logger logger,
        Executor executor,
        int memoryEntries,
        int queueCapacity,
        int maxConcurrent,
        Duration negativeTtl,
        Clock clock,
        TextureLoader loader
    ) {
        this(
            root, logger, executor, memoryEntries, queueCapacity, maxConcurrent, negativeTtl,
            clock, loader, (uuid, name) -> null
        );
    }

    PlayerHeadIconCache(
        Path root,
        Logger logger,
        Executor executor,
        int memoryEntries,
        int queueCapacity,
        int maxConcurrent,
        Duration negativeTtl,
        Clock clock,
        TextureLoader loader,
        OwnerTextureResolver ownerResolver
    ) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.negativeTtlMillis = Math.max(1L, Objects.requireNonNull(negativeTtl, "negativeTtl").toMillis());
        this.queueCapacity = Math.max(1, queueCapacity);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        final int capacity = Math.max(8, memoryEntries);
        this.negative = new LinkedHashMap<String, Long>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > capacity;
            }
        };
        this.memory = new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Returns immediately; it never reads disk and never waits for background preparation. */
    public Optional<BufferedImage> find(ItemSnapshot item) {
        PlayerHeadVisualDescriptor visual = headVisual(item).orElse(null);
        if (visual == null) return Optional.empty();
        synchronized (lock) {
            return Optional.ofNullable(memory.get(visual.visualKey()));
        }
    }

    /** Queues at most maxNew unique cold textures from this one inventory request. */
    public int prefetch(InventorySnapshot snapshot, int maxNew) {
        if (snapshot == null || maxNew <= 0) return 0;
        Map<String, PlayerHeadVisualDescriptor> unique = new LinkedHashMap<String, PlayerHeadVisualDescriptor>();
        for (InventorySlot slot : snapshot.getAllSlots()) {
            ItemSnapshot item = slot.getItem();
            headVisual(item).ifPresent(visual -> unique.putIfAbsent(visual.visualKey(), visual));
        }
        int accepted = 0;
        synchronized (lock) {
            if (closed) return 0;
            purgeExpiredNegatives();
            for (Map.Entry<String, PlayerHeadVisualDescriptor> entry : unique.entrySet()) {
                if (accepted >= maxNew) break;
                String key = entry.getKey();
                if (memory.containsKey(key) || negative.containsKey(key) || scheduled.contains(key)) continue;
                if (active + pending.size() >= queueCapacity) break;
                scheduled.add(key);
                completions.put(key, new CompletableFuture<Void>());
                pending.addLast(entry.getValue());
                accepted++;
            }
            dispatchLocked();
        }
        return accepted;
    }

    /**
     * Queues cold head textures and completes after every texture accepted for this snapshot is ready
     * (or has safely fallen back). Network and image work remains on the supplied bounded executor.
     */
    public CompletableFuture<Void> prepare(InventorySnapshot snapshot, int maxNew) {
        prefetch(snapshot, maxNew);
        if (snapshot == null) return CompletableFuture.completedFuture(null);
        List<CompletableFuture<Void>> waiting = new ArrayList<CompletableFuture<Void>>();
        synchronized (lock) {
            for (InventorySlot slot : snapshot.getAllSlots()) {
                PlayerHeadVisualDescriptor visual = headVisual(slot.getItem()).orElse(null);
                if (visual == null) continue;
                CompletableFuture<Void> completion = completions.get(visual.visualKey());
                if (completion != null && !waiting.contains(completion)) waiting.add(completion);
            }
        }
        return waiting.isEmpty()
            ? CompletableFuture.completedFuture(null)
            : CompletableFuture.allOf(waiting.toArray(new CompletableFuture<?>[0]));
    }

    private void dispatchLocked() {
        while (!closed && active < maxConcurrent && !pending.isEmpty()) {
            PlayerHeadVisualDescriptor visual = pending.removeFirst();
            String key = visual.visualKey();
            active++;
            try {
                executor.execute(() -> prepareIcon(visual));
            } catch (RuntimeException rejected) {
                active--;
                scheduled.remove(key);
                CompletableFuture<Void> completion = completions.remove(key);
                if (completion != null) completion.complete(null);
                negative.put(key, clock.millis() + negativeTtlMillis);
                logger.fine("玩家头颅后台队列已满，已安全回退通用图标");
            }
        }
    }

    private void prepareIcon(PlayerHeadVisualDescriptor visual) {
        String key = visual.visualKey();
        BufferedImage icon = null;
        try {
            icon = visual.hasTextureHash() ? loadDisk(visual.getTextureHash()) : null;
            if (icon == null) {
                String hash = visual.hasTextureHash()
                    ? visual.getTextureHash()
                    : ownerResolver.resolve(visual.getOwnerUuid(), visual.getOwnerName());
                if (hash == null || hash.trim().isEmpty()) throw new IOException("Player head owner has no skin texture");
                BufferedImage skin = loader.load(hash);
                icon = renderHeadItem(skin);
                if (visual.hasTextureHash()) persist(hash, icon);
            }
        } catch (Throwable error) {
            logger.log(Level.FINE, "玩家头颅纹理准备失败，已进入负缓存: " + key, error);
        } finally {
            CompletableFuture<Void> completion;
            synchronized (lock) {
                if (closed) {
                    scheduled.remove(key);
                    active--;
                } else if (icon != null) {
                    memory.put(key, icon);
                    negative.remove(key);
                } else {
                    negative.put(key, clock.millis() + negativeTtlMillis);
                }
                scheduled.remove(key);
                completion = completions.remove(key);
                active--;
                dispatchLocked();
            }
            if (completion != null) completion.complete(null);
        }
    }

    private BufferedImage loadDisk(String hash) {
        Path file = file(hash);
        if (!Files.isRegularFile(file)) return null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null && image.getWidth() == ICON_SIZE && image.getHeight() == ICON_SIZE) return image;
        } catch (IOException ignored) { }
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        return null;
    }

    private void persist(String hash, BufferedImage icon) throws IOException {
        Files.createDirectories(root);
        Path target = file(hash);
        Path temporary = Files.createTempFile(root, hash + "-", ".tmp");
        try {
            if (!ImageIO.write(icon, "PNG", temporary.toFile())) throw new IOException("PNG writer unavailable");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String hash) {
        Path path = root.resolve(hash + ".png").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Player-head cache path escaped root");
        return path;
    }

    private void purgeExpiredNegatives() {
        long now = clock.millis();
        negative.entrySet().removeIf(entry -> entry.getValue().longValue() <= now);
    }

    static Optional<PlayerHeadVisualDescriptor> headVisual(ItemSnapshot item) {
        if (item == null || !"minecraft:player_head".equalsIgnoreCase(item.getMaterialKey())) {
            return Optional.empty();
        }
        PlayerHeadVisualDescriptor visual = item.getPlayerHeadVisual();
        return Optional.ofNullable(visual);
    }

    /** Renders the skin head as a Minecraft-style inventory object with top, front, side and hat planes. */
    public static BufferedImage renderHeadItem(BufferedImage rawSkin) {
        BufferedImage skin = cn.huohuas001.virga.features.inventory.skin.LegacySkins.normalized(rawSkin);
        if (skin == null || skin.getWidth() < 64 || skin.getWidth() % 64 != 0) {
            throw new IllegalArgumentException("Skin atlas width must be a multiple of 64");
        }
        int textureScale = skin.getWidth() / 64;
        if (skin.getHeight() != 32 * textureScale && skin.getHeight() != 64 * textureScale) {
            throw new IllegalArgumentException("Skin atlas must use the standard 64x32 or 64x64 layout at an integer scale");
        }
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            // Base head, drawn directly in Minecraft's GUI viewing direction. Do not mirror the
            // finished icon: doing so reverses the UVs and moves the rear hair edge to the face seam.
            // Each destination is a parallelogram described by top-left, top-right and bottom-left.
            drawPlane(graphics, skin, 8 * textureScale, 0, 8 * textureScale, 8 * textureScale, 32, 5, 7, 17, 56, 17, 0.00f); // top
            drawPlane(graphics, skin, 0, 8 * textureScale, 8 * textureScale, 8 * textureScale, 7, 17, 31, 29, 7, 45, 0.18f); // right side
            drawPlane(graphics, skin, 8 * textureScale, 8 * textureScale, 8 * textureScale, 8 * textureScale, 31, 29, 56, 17, 31, 57, 0.04f); // face

            // Hat/outer head layer is slightly larger and remains transparent where the skin has no overlay.
            drawPlane(graphics, skin, 40 * textureScale, 0, 8 * textureScale, 8 * textureScale, 32, 2, 4, 16, 59, 16, 0.00f);
            drawPlane(graphics, skin, 32 * textureScale, 8 * textureScale, 8 * textureScale, 8 * textureScale, 4, 16, 31, 30, 4, 47, 0.18f);
            drawPlane(graphics, skin, 40 * textureScale, 8 * textureScale, 8 * textureScale, 8 * textureScale, 31, 30, 59, 16, 31, 61, 0.04f);
        } finally {
            graphics.dispose();
        }
        if (!hasVisiblePixels(icon)) throw new IllegalArgumentException("Player head is fully transparent");
        return icon;
    }

    private static void drawPlane(
        Graphics2D destination,
        BufferedImage source,
        int sourceX,
        int sourceY,
        int sourceWidth,
        int sourceHeight,
        double topLeftX,
        double topLeftY,
        double topRightX,
        double topRightY,
        double bottomLeftX,
        double bottomLeftY,
        float shade
    ) {
        BufferedImage layer = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = layer.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            BufferedImage texture = source.getSubimage(sourceX, sourceY, sourceWidth, sourceHeight);
            AffineTransform transform = new AffineTransform(
                (topRightX - topLeftX) / sourceWidth,
                (topRightY - topLeftY) / sourceWidth,
                (bottomLeftX - topLeftX) / sourceHeight,
                (bottomLeftY - topLeftY) / sourceHeight,
                topLeftX,
                topLeftY
            );
            graphics.drawImage(texture, transform, null);
            if (shade > 0.0f) {
                graphics.setComposite(AlphaComposite.SrcAtop);
                graphics.setColor(new Color(0.0f, 0.0f, 0.0f, shade));
                graphics.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
            }
        } finally {
            graphics.dispose();
        }
        destination.drawImage(layer, 0, 0, null);
    }

    static BufferedImage download(String hash, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        URI uri = PlayerHeadTextureUrl.secureTextureUri(hash);
        return download(uri, connectTimeoutMs, readTimeoutMs, value -> (HttpURLConnection) value.toURL().openConnection());
    }

    static BufferedImage download(
        URI uri,
        int connectTimeoutMs,
        int readTimeoutMs,
        ConnectionFactory connections
    ) throws Exception {
        URI canonical = PlayerHeadTextureUrl.secureTextureUri(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
        if (!canonical.equals(uri)) throw new IOException("Non-canonical player texture URI");
        HttpURLConnection connection = connections.open(canonical);
        try {
            connection.setConnectTimeout(Math.max(250, connectTimeoutMs));
            connection.setReadTimeout(Math.max(250, readTimeoutMs));
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "Virga/1.0 PlayerHeadCache");
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("Texture CDN returned HTTP " + code);
            int declared = connection.getContentLength();
            if (declared > MAX_SKIN_BYTES) throw new IOException("Player skin exceeds 1 MiB");
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = readLimited(input, MAX_SKIN_BYTES);
            }
            validatePngHeader(bytes);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IOException("Texture response is not an image");
            if (image.getWidth() != 64 || (image.getHeight() != 32 && image.getHeight() != 64)) {
                throw new IOException("Unsupported player skin dimensions");
            }
            return image;
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maximum) throw new IOException("Player skin exceeds byte limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static void validatePngHeader(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 24 ||
            (bytes[0] & 0xff) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G' ||
            bytes[4] != 0x0d || bytes[5] != 0x0a || bytes[6] != 0x1a || bytes[7] != 0x0a ||
            bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new IOException("Texture response is not a PNG");
        }
        int width = readInt(bytes, 16);
        int height = readInt(bytes, 20);
        if (width != 64 || (height != 32 && height != 64)) {
            throw new IOException("Unsupported player skin dimensions");
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) |
            ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static boolean hasVisiblePixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }

    int memorySize() { synchronized (lock) { return memory.size(); } }
    int negativeSize() { synchronized (lock) { return negative.size(); } }
    int pendingSize() { synchronized (lock) { return pending.size(); } }
    int scheduledSize() { synchronized (lock) { return scheduled.size(); } }

    @Override public void close() {
        synchronized (lock) {
            closed = true;
            pending.clear();
            scheduled.clear();
            completions.values().forEach(completion -> completion.complete(null));
            completions.clear();
            negative.clear();
            memory.clear();
        }
    }

    @FunctionalInterface
    interface TextureLoader { BufferedImage load(String hash) throws Exception; }

    @FunctionalInterface
    public interface OwnerTextureResolver { String resolve(UUID ownerUuid, String ownerName) throws Exception; }

    @FunctionalInterface
    interface ConnectionFactory { HttpURLConnection open(URI uri) throws IOException; }
}
