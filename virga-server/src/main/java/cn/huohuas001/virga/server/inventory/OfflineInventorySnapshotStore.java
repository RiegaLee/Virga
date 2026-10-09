package cn.huohuas001.virga.server.inventory;

import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import cn.huohuas001.virga.features.inventory.potion.PotionVisualDescriptor;
import cn.huohuas001.virga.features.inventory.head.PlayerHeadVisualDescriptor;
import org.yaml.snakeyaml.Yaml;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Versioned, atomic, Bukkit-free persistence for one inventory data type. */
public final class OfflineInventorySnapshotStore implements AutoCloseable {
    public static final int SCHEMA_VERSION = 5;
    private final Path root;
    private final Logger logger;
    private final boolean debugEnabled;
    private final Executor writer;

    public OfflineInventorySnapshotStore(Path root, Logger logger) {
        this(root, logger, false, Runnable::run);
    }

    public OfflineInventorySnapshotStore(Path root, Logger logger, boolean debugEnabled) {
        this(root, logger, debugEnabled, Runnable::run);
    }

    public OfflineInventorySnapshotStore(Path root, Logger logger, boolean debugEnabled, Executor writer) {
        this.root = root.toAbsolutePath().normalize();
        this.logger = logger;
        this.debugEnabled = debugEnabled;
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
    }

    public CompletableFuture<Void> saveAsync(final InventorySnapshot snapshot) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            writer.execute(() -> {
                try { write(snapshot); result.complete(null); }
                catch (Throwable error) {
                    logger.log(Level.WARNING, "Could not save offline snapshot for " + snapshot.getPlayerName(), error);
                    result.completeExceptionally(error);
                }
            });
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    public Optional<InventorySnapshot> load(UUID playerUuid) {
        Path file = fileFor(playerUuid);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (java.io.InputStream input = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("snapshot root is not a map");
            Map<String, Object> values = cast(loaded);
            int schema = integer(values, "schema-version", 0);
            if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("unsupported schema " + schema);
            UUID storedUuid = UUID.fromString(required(string(values, "player.uuid", null), "player.uuid"));
            if (!storedUuid.equals(playerUuid)) throw new IllegalArgumentException("snapshot UUID does not match file name");
            InventorySnapshot snapshot = new InventorySnapshot(
                InventorySnapshot.CURRENT_SCHEMA_VERSION,
                storedUuid,
                required(string(values, "player.last-known-name", null), "player.last-known-name"),
                Instant.parse(required(string(values, "captured-at", null), "captured-at")),
                required(string(values, "source-server", null), "source-server"),
                required(string(values, "content-revision", null), "content-revision"),
                readGrid(values, "storage", SlotType.STORAGE, 27, schema),
                readGrid(values, "hotbar", SlotType.HOTBAR, 9, schema),
                Arrays.asList(
                    readSlot(values, "armor.head", SlotType.ARMOR_HEAD, 0, schema),
                    readSlot(values, "armor.chest", SlotType.ARMOR_CHEST, 0, schema),
                    readSlot(values, "armor.legs", SlotType.ARMOR_LEGS, 0, schema),
                    readSlot(values, "armor.feet", SlotType.ARMOR_FEET, 0, schema)
                ),
                readSlot(values, "offhand", SlotType.OFFHAND, 0, schema)
            );
            if (debugEnabled) logger.info("[OfflineSnapshotStore] disk load snapshot found uuid=" + playerUuid);
            return Optional.of(snapshot);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "Ignoring corrupt offline snapshot " + file, error);
            return Optional.empty();
        }
    }

    private void write(InventorySnapshot snapshot) throws Exception {
        if (snapshot.getPlayerUuid() == null) throw new IllegalArgumentException("snapshot UUID is required");
        Files.createDirectories(root);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schema-version", SCHEMA_VERSION);
        values.put("player.uuid", snapshot.getPlayerUuid().toString());
        values.put("player.last-known-name", snapshot.getPlayerName());
        values.put("captured-at", snapshot.getCapturedAt().toString());
        values.put("source-server", snapshot.getSourceServer());
        values.put("content-revision", snapshot.getContentRevision());
        writeSlots(values, "storage", snapshot.getStorage());
        writeSlots(values, "hotbar", snapshot.getHotbar());
        for (InventorySlot slot : snapshot.getArmor()) {
            String name;
            switch (slot.getSlotType()) {
                case ARMOR_HEAD: name = "head"; break;
                case ARMOR_CHEST: name = "chest"; break;
                case ARMOR_LEGS: name = "legs"; break;
                case ARMOR_FEET: name = "feet"; break;
                default: throw new IllegalArgumentException("unexpected armor slot");
            }
            writeSlot(values, "armor." + name, slot);
        }
        writeSlot(values, "offhand", snapshot.getOffhand());
        byte[] bytes = new Yaml().dump(values).getBytes(StandardCharsets.UTF_8);
        Path destination = fileFor(snapshot.getPlayerUuid());
        Path temporary = Files.createTempFile(root, snapshot.getPlayerUuid().toString() + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path fileFor(UUID uuid) {
        Path file = root.resolve(uuid.toString().toLowerCase(Locale.ROOT) + ".yml").normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("snapshot path escaped its root");
        return file;
    }

    private static void writeSlots(Map<String, Object> values, String path, List<InventorySlot> slots) {
        for (InventorySlot slot : slots) writeSlot(values, path + "." + slot.getIndex(), slot);
    }

    private static void writeSlot(Map<String, Object> values, String path, InventorySlot slot) {
        ItemSnapshot item = slot.getItem();
        values.put(path + ".empty", item == null);
        if (item == null) return;
        values.put(path + ".material", item.getMaterialKey());
        values.put(path + ".amount", item.getAmount());
        values.put(path + ".damage", item.getDamage());
        values.put(path + ".max-damage", item.getMaxDamage());
        values.put(path + ".display-name", item.getDisplayName());
        values.put(path + ".custom-model-data", item.getCustomModelData());
        values.put(path + ".enchantment-glint", item.hasEnchantmentGlint());
        values.put(path + ".texture-hint", item.getTextureHint());
        ArmorVisualDescriptor armor = item.getArmorVisual();
        values.put(path + ".armor-visual.present", armor != null);
        if (armor != null) {
            values.put(path + ".armor-visual.slot", armor.getSlot().name());
            values.put(path + ".armor-visual.base-material", armor.getBaseMaterialKey());
            values.put(path + ".armor-visual.equipment-model", armor.getEquipmentModelKey());
            values.put(path + ".armor-visual.trim-pattern", armor.getTrimPatternKey());
            values.put(path + ".armor-visual.trim-material", armor.getTrimMaterialKey());
            values.put(path + ".armor-visual.leather-color", armor.getLeatherColor());
            values.put(path + ".armor-visual.glint", armor.hasGlint());
        }
        PotionVisualDescriptor potion = item.getPotionVisual();
        values.put(path + ".potion-visual.present", potion != null);
        if (potion != null) {
            values.put(path + ".potion-visual.item-type", potion.getItemTypeKey());
            values.put(path + ".potion-visual.base-potion", potion.getBasePotionKey());
            values.put(path + ".potion-visual.resolved-tint-rgb", potion.getResolvedTintRgb());
            values.put(path + ".potion-visual.custom-color", potion.hasCustomColor());
            values.put(path + ".potion-visual.glint", potion.hasGlint());
        }
        PlayerHeadVisualDescriptor playerHead = item.getPlayerHeadVisual();
        values.put(path + ".player-head-visual.present", playerHead != null);
        if (playerHead != null) {
            values.put(path + ".player-head-visual.kind", playerHead.hasTextureHash() ? "texture" : "owner");
            values.put(path + ".player-head-visual.texture-hash", playerHead.getTextureHash());
            values.put(path + ".player-head-visual.owner-uuid",
                playerHead.getOwnerUuid() == null ? null : playerHead.getOwnerUuid().toString());
            values.put(path + ".player-head-visual.owner-name", playerHead.getOwnerName());
        }
    }

    private static List<InventorySlot> readGrid(Map<String, Object> values, String path, SlotType type, int size, int schema) {
        List<InventorySlot> result = new ArrayList<InventorySlot>(size);
        for (int index = 0; index < size; index++) result.add(readSlot(values, path + "." + index, type, index, schema));
        return result;
    }

    private static InventorySlot readSlot(Map<String, Object> values, String path, SlotType type, int index, int schema) {
        if (!values.containsKey(path + ".empty")) throw new IllegalArgumentException("missing slot " + path);
        if (bool(values, path + ".empty", false)) return InventorySlot.empty(type, index);
        Integer custom = values.containsKey(path + ".custom-model-data") && values.get(path + ".custom-model-data") != null
            ? Integer.valueOf(integer(values, path + ".custom-model-data", 0)) : null;
        ItemSnapshot item = new ItemSnapshot(
            required(string(values, path + ".material", null), path + ".material"),
            integer(values, path + ".amount", 0), integer(values, path + ".damage", 0),
            integer(values, path + ".max-damage", 0), string(values, path + ".display-name", null),
            custom, bool(values, path + ".enchantment-glint", false), string(values, path + ".texture-hint", null),
            schema >= 2 ? readArmor(values, path) : null,
            schema >= 3 ? readPotion(values, path) : null,
            schema >= 4 ? readPlayerHead(values, path, schema) : null
        );
        return InventorySlot.of(type, index, item);
    }

    private static ArmorVisualDescriptor readArmor(Map<String, Object> values, String path) {
        String base = path + ".armor-visual";
        if (!values.containsKey(base + ".present")) throw new IllegalArgumentException("missing armor marker " + base);
        if (!bool(values, base + ".present", false)) return null;
        Integer leather = values.get(base + ".leather-color") instanceof Number
            ? Integer.valueOf(integer(values, base + ".leather-color", 0)) : null;
        return new ArmorVisualDescriptor(
            ArmorVisualDescriptor.Slot.valueOf(required(string(values, base + ".slot", null), base + ".slot")),
            required(string(values, base + ".base-material", null), base + ".base-material"),
            required(string(values, base + ".equipment-model", null), base + ".equipment-model"),
            string(values, base + ".trim-pattern", null), string(values, base + ".trim-material", null),
            leather, bool(values, base + ".glint", false)
        );
    }

    private static PotionVisualDescriptor readPotion(Map<String, Object> values, String path) {
        String base = path + ".potion-visual";
        if (!values.containsKey(base + ".present")) throw new IllegalArgumentException("missing potion marker " + base);
        if (!bool(values, base + ".present", false)) return null;
        return new PotionVisualDescriptor(
            required(string(values, base + ".item-type", null), base + ".item-type"),
            string(values, base + ".base-potion", null), integer(values, base + ".resolved-tint-rgb", 0),
            bool(values, base + ".custom-color", false), bool(values, base + ".glint", false)
        );
    }

    private static PlayerHeadVisualDescriptor readPlayerHead(Map<String, Object> values, String path, int schema) {
        String base = path + ".player-head-visual";
        if (!values.containsKey(base + ".present")) throw new IllegalArgumentException("missing player-head marker " + base);
        if (!bool(values, base + ".present", false)) return null;
        if (schema < 5 || "texture".equalsIgnoreCase(string(values, base + ".kind", "texture"))) {
            return new PlayerHeadVisualDescriptor(
                required(string(values, base + ".texture-hash", null), base + ".texture-hash")
            );
        }
        return new PlayerHeadVisualDescriptor(
            UUID.fromString(required(string(values, base + ".owner-uuid", null), base + ".owner-uuid")),
            required(string(values, base + ".owner-name", null), base + ".owner-name")
        );
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
    private static String string(Map<String, Object> values, String key, String fallback) { Object value = values.get(key); return value == null ? fallback : value.toString(); }
    private static int integer(Map<String, Object> values, String key, int fallback) { Object value = values.get(key); return value instanceof Number ? ((Number) value).intValue() : fallback; }
    private static boolean bool(Map<String, Object> values, String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback; }
    private static String required(String value, String field) { if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("missing " + field); return value.trim(); }
    @Override public void close() { }
}
