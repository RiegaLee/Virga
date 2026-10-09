package cn.huohuas001.virga.features.inventory.head;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerHeadIconCacheTest {
    @Test
    void sameTextureIsCoalescedAndOneRequestQueuesAtMostFour(@TempDir Path temp) {
        ManualExecutor executor = new ManualExecutor();
        PlayerHeadIconCache cache = cache(temp, executor, Clock.systemUTC(), hash -> skin(), 32, 2);
        try {
            String same = hash(1);
            List<ItemSnapshot> repeated = new ArrayList<ItemSnapshot>();
            for (int index = 0; index < 27; index++) repeated.add(head(same, 64));
            assertEquals(1, cache.prefetch(snapshot(repeated), 4));
            assertEquals(1, executor.size());
            assertEquals(0, cache.prefetch(snapshot(repeated), 4));

            executor.runAll();
            assertTrue(cache.find(head(same, 1)).isPresent());

            List<ItemSnapshot> distinct = new ArrayList<ItemSnapshot>();
            for (int index = 2; index < 20; index++) distinct.add(head(hash(index), 1));
            assertEquals(4, cache.prefetch(snapshot(distinct), 4));
        } finally {
            cache.close();
        }
    }

    @Test
    void queueAndConcurrencyAreBounded(@TempDir Path temp) {
        ManualExecutor executor = new ManualExecutor();
        PlayerHeadIconCache cache = cache(temp, executor, Clock.systemUTC(), hash -> skin(), 4, 2);
        try {
            List<ItemSnapshot> distinct = new ArrayList<ItemSnapshot>();
            for (int index = 1; index <= 12; index++) distinct.add(head(hash(index), 1));
            assertEquals(4, cache.prefetch(snapshot(distinct), 12));
            assertEquals(2, executor.size(), "only two workers may be active");
            assertEquals(2, cache.pendingSize());
            assertEquals(0, cache.prefetch(snapshot(distinct), 12));
        } finally {
            cache.close();
        }
    }

    @Test
    void negativeCacheSuppressesRetriesUntilExpiry(@TempDir Path temp) {
        MutableClock clock = new MutableClock();
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        PlayerHeadIconCache cache = cache(temp, executor, clock, hash -> {
            attempts.incrementAndGet();
            throw new IOException("offline");
        }, 32, 1);
        InventorySnapshot snapshot = snapshot(List.of(head(hash(1), 1)));
        try {
            assertEquals(1, cache.prefetch(snapshot, 4));
            executor.runAll();
            assertEquals(1, attempts.get());
            assertEquals(0, cache.prefetch(snapshot, 4));
            clock.advance(Duration.ofMinutes(11));
            assertEquals(1, cache.prefetch(snapshot, 4));
            executor.runAll();
            assertEquals(2, attempts.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void finalIconPersistsAndReloadsWithoutNetwork(@TempDir Path temp) {
        String hash = hash(42);
        ManualExecutor firstExecutor = new ManualExecutor();
        PlayerHeadIconCache first = cache(temp, firstExecutor, Clock.systemUTC(), ignored -> skin(), 32, 1);
        first.prefetch(snapshot(List.of(head(hash, 1))), 4);
        firstExecutor.runAll();
        assertTrue(first.find(head(hash, 1)).isPresent());
        first.close();

        AtomicInteger downloads = new AtomicInteger();
        ManualExecutor secondExecutor = new ManualExecutor();
        PlayerHeadIconCache second = cache(temp, secondExecutor, Clock.systemUTC(), ignored -> {
            downloads.incrementAndGet();
            return skin();
        }, 32, 1);
        try {
            assertFalse(second.find(head(hash, 1)).isPresent(), "request path must not synchronously read disk");
            second.prefetch(snapshot(List.of(head(hash, 1))), 4);
            secondExecutor.runAll();
            assertTrue(second.find(head(hash, 1)).isPresent());
            assertEquals(0, downloads.get());
        } finally {
            second.close();
        }
    }

    @Test
    void finalIconMemoryCacheUsesLruBound(@TempDir Path temp) {
        ManualExecutor executor = new ManualExecutor();
        PlayerHeadIconCache cache = new PlayerHeadIconCache(
            temp, Logger.getAnonymousLogger(), executor, 8, 32, 1,
            Duration.ofMinutes(10), Clock.systemUTC(), ignored -> skin()
        );
        try {
            for (int index = 1; index <= 9; index++) {
                cache.prefetch(snapshot(List.of(head(hash(index), 1))), 4);
                executor.runAll();
            }
            assertEquals(8, cache.memorySize());
            assertFalse(cache.find(head(hash(1), 1)).isPresent());
            assertTrue(cache.find(head(hash(9), 1)).isPresent());
        } finally {
            cache.close();
        }
    }

    @Test
    void ownerOnlyHeadResolvesTextureOffThread(@TempDir Path temp) {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger ownerLookups = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        PlayerHeadIconCache cache = new PlayerHeadIconCache(
            temp, Logger.getAnonymousLogger(), executor, 16, 32, 1,
            Duration.ofMinutes(10), Clock.systemUTC(), ignored -> {
                downloads.incrementAndGet();
                return skin();
            }, (uuid, name) -> {
                ownerLookups.incrementAndGet();
                assertEquals("_RiegaLee_", name);
                return hash(99);
            }
        );
        ItemSnapshot ownerHead = ownerHead(UUID.randomUUID(), "_RiegaLee_");
        try {
            assertEquals(1, cache.prefetch(snapshot(List.of(ownerHead)), 4));
            assertFalse(cache.find(ownerHead).isPresent());
            executor.runAll();
            assertTrue(cache.find(ownerHead).isPresent());
            assertEquals(1, ownerLookups.get());
            assertEquals(1, downloads.get());
        } finally {
            cache.close();
        }
    }

    @Test
    void prepareWaitsForTheOwnerTextureUsedByTheSameRender(@TempDir Path temp) {
        ManualExecutor executor = new ManualExecutor();
        PlayerHeadIconCache cache = new PlayerHeadIconCache(
            temp, Logger.getAnonymousLogger(), executor, 16, 32, 1,
            Duration.ofMinutes(10), Clock.systemUTC(), ignored -> skin(),
            (uuid, name) -> hash(101)
        );
        ItemSnapshot ownerHead = ownerHead(UUID.randomUUID(), "ColdMint521");
        try {
            CompletableFuture<Void> preparation = cache.prepare(snapshot(List.of(ownerHead)), 4);
            assertFalse(preparation.isDone(), "the first render must wait for its accepted head texture");
            executor.runAll();
            assertTrue(preparation.isDone());
            assertTrue(cache.find(ownerHead).isPresent());
        } finally {
            cache.close();
        }
    }

    @Test
    void faceAndTransparentHatLayersAreComposed() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) skin.setRGB(x, y, Color.RED.getRGB());
        skin.setRGB(40, 8, Color.BLUE.getRGB());
        BufferedImage icon = PlayerHeadIconCache.renderHeadItem(skin);
        int redPixels = 0;
        int bluePixels = 0;
        long redX = 0;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                int color = icon.getRGB(x, y);
                if (((color >>> 24) & 0xff) == 0) continue;
                if (((color >>> 16) & 0xff) > (color & 0xff)) {
                    redPixels++;
                    redX += x;
                }
                if ((color & 0xff) > ((color >>> 16) & 0xff)) bluePixels++;
            }
        }
        assertTrue(redPixels > 100, "the face must remain visible");
        assertTrue(redX / redPixels > 32, "the visible face belongs on the right, matching Minecraft GUI orientation");
        assertTrue(bluePixels > 0, "the hat layer must be composited");
        assertEquals(0, icon.getRGB(0, 0) >>> 24, "corners stay transparent instead of becoming a flat tile");
        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
    }

    @Test
    void skinUvsAreRotatedWithTheHeadInsteadOfMirrored() {
        BufferedImage side = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            side.setRGB(0, y, Color.RED.getRGB());   // rear edge of the right side
            side.setRGB(7, y, Color.BLUE.getRGB()); // edge adjoining the face
        }
        BufferedImage sideIcon = PlayerHeadIconCache.renderHeadItem(side);
        assertTrue(dominantCentroidX(sideIcon, true) < dominantCentroidX(sideIcon, false),
            "the rear hair edge must stay outside rather than being mirrored onto the face seam");

        BufferedImage face = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            face.setRGB(8, y, Color.RED.getRGB());
            face.setRGB(15, y, Color.BLUE.getRGB());
        }
        BufferedImage faceIcon = PlayerHeadIconCache.renderHeadItem(face);
        assertTrue(dominantCentroidX(faceIcon, true) < dominantCentroidX(faceIcon, false),
            "the face texture must remain left-to-right instead of becoming a mirror image");
    }

    @Test
    void downloaderRejectsRedirectsOversizeAndAbnormalDimensions() throws Exception {
        URI uri = PlayerHeadTextureUrl.secureTextureUri(hash(7));
        assertThrows(IOException.class, () -> PlayerHeadIconCache.download(
            uri, 500, 500, ignored -> new FakeConnection(uri, 302, new byte[0], -1)
        ));
        assertThrows(IOException.class, () -> PlayerHeadIconCache.download(
            uri, 500, 500, ignored -> new FakeConnection(uri, 200, new byte[0], PlayerHeadIconCache.MAX_SKIN_BYTES + 1)
        ));

        byte[] png = png(64, 64);
        png[19] = 65; // IHDR width becomes 65 before ImageIO is allowed to decode it.
        assertThrows(IOException.class, () -> PlayerHeadIconCache.download(
            uri, 500, 500, ignored -> new FakeConnection(uri, 200, png, png.length)
        ));
        byte[] streamedOversize = new byte[PlayerHeadIconCache.MAX_SKIN_BYTES + 1];
        assertThrows(IOException.class, () -> PlayerHeadIconCache.download(
            uri, 500, 500, ignored -> new FakeConnection(uri, 200, streamedOversize, -1)
        ));
    }

    private static PlayerHeadIconCache cache(
        Path temp, Executor executor, Clock clock, PlayerHeadIconCache.TextureLoader loader,
        int outstanding, int concurrent
    ) {
        return new PlayerHeadIconCache(
            temp, Logger.getAnonymousLogger(), executor, 16, outstanding, concurrent,
            Duration.ofMinutes(10), clock, loader
        );
    }

    private static ItemSnapshot head(String hash, int amount) {
        return new ItemSnapshot(
            "minecraft:player_head", amount, 0, 0, null, null, false, null,
            null, null, new PlayerHeadVisualDescriptor(hash)
        );
    }

    private static ItemSnapshot ownerHead(UUID uuid, String name) {
        return new ItemSnapshot(
            "minecraft:player_head", 1, 0, 0, null, null, false, null,
            null, null, new PlayerHeadVisualDescriptor(uuid, name)
        );
    }

    private static InventorySnapshot snapshot(List<ItemSnapshot> items) {
        int next = 0;
        List<InventorySlot> storage = new ArrayList<InventorySlot>();
        for (int index = 0; index < 27; index++) {
            storage.add(next < items.size() ? InventorySlot.of(SlotType.STORAGE, index, items.get(next++))
                : InventorySlot.empty(SlotType.STORAGE, index));
        }
        List<InventorySlot> hotbar = new ArrayList<InventorySlot>();
        for (int index = 0; index < 9; index++) {
            hotbar.add(next < items.size() ? InventorySlot.of(SlotType.HOTBAR, index, items.get(next++))
                : InventorySlot.empty(SlotType.HOTBAR, index));
        }
        List<InventorySlot> armor = List.of(
            InventorySlot.empty(SlotType.ARMOR_HEAD, 0),
            InventorySlot.empty(SlotType.ARMOR_CHEST, 0),
            InventorySlot.empty(SlotType.ARMOR_LEGS, 0),
            InventorySlot.empty(SlotType.ARMOR_FEET, 0)
        );
        return new InventorySnapshot(
            InventorySnapshot.CURRENT_SCHEMA_VERSION, UUID.randomUUID(), "HeadUser", Instant.EPOCH,
            "test", "revision", storage, hotbar, armor, InventorySlot.empty(SlotType.OFFHAND, 0)
        );
    }

    private static BufferedImage skin() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) skin.setRGB(x, y, Color.GREEN.getRGB());
        return skin;
    }

    private static double dominantCentroidX(BufferedImage image, boolean red) {
        long totalX = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y);
                int redValue = (color >>> 16) & 0xff;
                int blueValue = color & 0xff;
                if ((red && redValue > blueValue) || (!red && blueValue > redValue)) {
                    totalX += x;
                    count++;
                }
            }
        }
        assertTrue(count > 0, "test marker must remain visible");
        return (double) totalX / count;
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "PNG", output);
        return output.toByteArray();
    }

    private static String hash(int value) { return String.format("%064x", value); }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<Runnable>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        int size() { return tasks.size(); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private final int length;
        FakeConnection(URI uri, int status, byte[] body, int length) throws IOException {
            super(uri.toURL());
            this.status = status;
            this.body = body;
            this.length = length;
        }
        @Override public int getResponseCode() { return status; }
        @Override public int getContentLength() { return length; }
        @Override public ByteArrayInputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
