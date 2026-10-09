package cn.huohuas001.virga.server.inventory;

import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSourceException;

import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSource;

import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import cn.huohuas001.virga.features.inventory.potion.PotionVisualDescriptor;
import cn.huohuas001.virga.features.inventory.head.PlayerHeadVisualDescriptor;

import cn.huohuas001.virga.server.game.CapturedInventory;
import cn.huohuas001.virga.server.game.GamePlayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Source for one online player's private 27-slot Ender Chest. */
public final class GameOnlineEnderChestDataSource implements InventoryDataSource {
    private final OnlinePlayerAccess access;
    private final String sourceServer;
    private final Clock clock;

    public GameOnlineEnderChestDataSource(OnlinePlayerAccess access, String sourceServer) {
        this(access, sourceServer, Clock.systemUTC());
    }

    GameOnlineEnderChestDataSource(
        OnlinePlayerAccess access,
        String sourceServer,
        Clock clock
    ) {
        this.access = Objects.requireNonNull(access, "access");
        this.sourceServer = requireText(sourceServer, "sourceServer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<InventorySnapshot> getInventory(String playerName) {
        String requestedName = requirePlayerName(playerName);
        CompletableFuture<InventorySnapshot> result = new CompletableFuture<InventorySnapshot>();
        // Resolve the name on the global region, then read the player on the region that owns it.
        Runnable lookup = () -> lookupAndCapture(requestedName, result);
        try {
            if (access.isGlobalThread()) lookup.run();
            else if (!access.executeGlobal(lookup)) throw new IllegalStateException("Scheduler is closed");
        } catch (Throwable error) {
            result.completeExceptionally(new InventoryDataSourceException(
                InventoryDataSourceException.Reason.SOURCE_UNAVAILABLE,
                "Could not schedule Ender Chest capture",
                error
            ));
        }
        return result;
    }

    private void lookupAndCapture(String requestedName, CompletableFuture<InventorySnapshot> result) {
        if (result.isDone()) return;
        try {
            GamePlayer player = access.findExactPlayer(requestedName);
            if (player == null || !player.isOnline() || !player.getName().equalsIgnoreCase(requestedName)) {
                throw playerOffline(requestedName);
            }
            if (access.ownsPlayer(player)) {
                result.complete(capturePlayer(player));
                return;
            }
            boolean scheduled = access.executeForPlayer(player, () -> {
                if (result.isDone()) return;
                try {
                    result.complete(capturePlayer(player));
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            }, () -> result.completeExceptionally(playerOffline(requestedName)));
            if (!scheduled) result.completeExceptionally(playerOffline(requestedName));
        } catch (Throwable error) {
            result.completeExceptionally(error);
        }
    }

    /** Captures one live Player immediately; callers must own the player (the server thread). */
    public InventorySnapshot capturePlayer(GamePlayer player) {
        Objects.requireNonNull(player, "player");
        if (!access.ownsPlayer(player)) {
            throw new IllegalStateException("Ender Chest capture must run on the thread that owns the player");
        }
        return captureOwned(player, true);
    }

    /** Captures a disconnecting player on the server thread; the online check is skipped. */
    public InventorySnapshot captureDeparting(GamePlayer player) {
        Objects.requireNonNull(player, "player");
        if (!access.isGlobalThread()) {
            throw new IllegalStateException("Departing player capture must run on the server thread");
        }
        return captureOwned(player, false);
    }

    private InventorySnapshot captureOwned(GamePlayer player, boolean requireOnline) {
        UUID uuid = player.getUuid();
        String name = player.getName();
        if (uuid == null || (requireOnline && !player.isOnline())) throw playerStateChanged(name);
        List<ItemSnapshot> contents = player.captureEnderChest();
        if (contents == null || contents.size() < 27) {
            throw new InventoryDataSourceException(
                InventoryDataSourceException.Reason.SOURCE_UNAVAILABLE,
                "Ender Chest contains fewer than 27 slots"
            );
        }

        List<InventorySlot> storage = new ArrayList<InventorySlot>(27);
        for (int index = 0; index < 27; index++) storage.add(slot(SlotType.STORAGE, index, contents.get(index)));
        List<InventorySlot> hotbar = emptyGrid(SlotType.HOTBAR, 9);
        List<InventorySlot> armor = Arrays.asList(
            InventorySlot.empty(SlotType.ARMOR_HEAD, 0),
            InventorySlot.empty(SlotType.ARMOR_CHEST, 0),
            InventorySlot.empty(SlotType.ARMOR_LEGS, 0),
            InventorySlot.empty(SlotType.ARMOR_FEET, 0)
        );
        InventorySlot offhand = InventorySlot.empty(SlotType.OFFHAND, 0);
        if (requireOnline && !player.isOnline()) throw playerStateChanged(name);
        Instant capturedAt = clock.instant();
        return new InventorySnapshot(
            InventorySnapshot.CURRENT_SCHEMA_VERSION,
            uuid,
            name,
            capturedAt,
            sourceServer,
            contentRevision(storage),
            storage,
            hotbar,
            armor,
            offhand
        );
    }

    private static InventorySlot slot(SlotType type, int index, ItemSnapshot item) {
        return item == null ? InventorySlot.empty(type, index) : InventorySlot.of(type, index, item);
    }

    private static List<InventorySlot> emptyGrid(SlotType type, int size) {
        List<InventorySlot> slots = new ArrayList<InventorySlot>(size);
        for (int index = 0; index < size; index++) slots.add(InventorySlot.empty(type, index));
        return slots;
    }

    static String contentRevision(List<InventorySlot> slots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (InventorySlot slot : slots) {
                update(digest, Integer.toString(slot.getIndex()));
                ItemSnapshot item = slot.getItem();
                if (item == null) {
                    update(digest, "empty");
                } else {
                    update(digest, item.getMaterialKey());
                    update(digest, Integer.toString(item.getAmount()));
                    update(digest, Integer.toString(item.getDamage()));
                    update(digest, Integer.toString(item.getMaxDamage()));
                    update(digest, item.getDisplayName() == null ? "" : item.getDisplayName());
                    update(digest, item.getCustomModelData() == null ? "" : item.getCustomModelData().toString());
                    update(digest, Boolean.toString(item.hasEnchantmentGlint()));
                    update(digest, item.getTextureHint() == null ? "" : item.getTextureHint());
                    ArmorVisualDescriptor armor = item.getArmorVisual();
                    update(digest, armor == null ? "" : armor.visualKey());
                    PotionVisualDescriptor potion = item.getPotionVisual();
                    update(digest, potion == null ? "" : potion.visualKey());
                    PlayerHeadVisualDescriptor playerHead = item.getPlayerHeadVisual();
                    update(digest, playerHead == null ? "" : playerHead.visualKey());
                }
            }
            byte[] hash = digest.digest();
            StringBuilder value = new StringBuilder("online-ender-chest-");
            for (int index = 0; index < 12; index++) {
                value.append(String.format(Locale.ROOT, "%02x", hash[index] & 0xff));
            }
            return value.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static InventoryDataSourceException playerStateChanged(String name) {
        return new InventoryDataSourceException(
            InventoryDataSourceException.Reason.PLAYER_STATE_CHANGED,
            "Player state changed while capturing Ender Chest: " + name
        );
    }

    private static String requirePlayerName(String value) {
        String normalized = requireText(value, "playerName");
        if (!normalized.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("playerName must be one exact Minecraft player name");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static InventoryDataSourceException playerOffline(String playerName) {
        return new InventoryDataSourceException(
            InventoryDataSourceException.Reason.PLAYER_OFFLINE,
            "Player is not online: " + playerName
        );
    }
}
