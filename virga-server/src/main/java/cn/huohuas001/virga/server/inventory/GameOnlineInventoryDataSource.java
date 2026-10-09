package cn.huohuas001.virga.server.inventory;

import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSourceException;

import cn.huohuas001.virga.features.inventory.datasource.InventoryDataSource;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
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

/** Online-player source that never exposes live Minecraft objects to the renderer. */
public final class GameOnlineInventoryDataSource implements InventoryDataSource {
    private final OnlinePlayerAccess access;
    private final String sourceServer;
    private final Clock clock;

    public GameOnlineInventoryDataSource(OnlinePlayerAccess access, String sourceServer) {
        this(access, sourceServer, Clock.systemUTC());
    }

    GameOnlineInventoryDataSource(
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
                "Could not schedule player inventory capture",
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
            throw new IllegalStateException("Player inventory capture must run on the thread that owns the player");
        }
        return captureOwnedPlayer(player, true);
    }

    /**
     * Captures a player who is disconnecting, on the server thread. The player object keeps its
     * inventory after removal, so the online check is skipped.
     */
    public InventorySnapshot captureDeparting(GamePlayer player) {
        Objects.requireNonNull(player, "player");
        if (!access.isGlobalThread()) {
            throw new IllegalStateException("Departing player capture must run on the server thread");
        }
        return captureOwnedPlayer(player, false);
    }

    private InventorySnapshot captureOwnedPlayer(GamePlayer player, boolean requireOnline) {
        UUID playerUuid = player.getUuid();
        String canonicalName = player.getName();
        if (playerUuid == null || (requireOnline && !player.isOnline())) {
            throw playerStateChanged(canonicalName);
        }
        CapturedInventory inventory = player.captureInventory();
        List<ItemSnapshot> storageContents = inventory.getStorage();

        List<InventorySlot> hotbar = new ArrayList<InventorySlot>(9);
        for (int index = 0; index < 9; index++) {
            hotbar.add(slot(SlotType.HOTBAR, index, storageContents.get(index)));
        }
        List<InventorySlot> storage = new ArrayList<InventorySlot>(27);
        for (int index = 0; index < 27; index++) {
            storage.add(slot(SlotType.STORAGE, index, storageContents.get(index + 9)));
        }

        List<InventorySlot> armor = Arrays.asList(
            slot(SlotType.ARMOR_HEAD, 0, inventory.getHelmet()),
            slot(SlotType.ARMOR_CHEST, 0, inventory.getChestplate()),
            slot(SlotType.ARMOR_LEGS, 0, inventory.getLeggings()),
            slot(SlotType.ARMOR_FEET, 0, inventory.getBoots())
        );
        InventorySlot offhand = slot(SlotType.OFFHAND, 0, inventory.getOffhand());

        if (requireOnline && !player.isOnline()) throw playerStateChanged(canonicalName);
        Instant capturedAt = clock.instant();
        String revision = contentRevision(storage, hotbar, armor, offhand);
        return new InventorySnapshot(
            InventorySnapshot.CURRENT_SCHEMA_VERSION,
            playerUuid,
            canonicalName,
            capturedAt,
            sourceServer,
            revision,
            storage,
            hotbar,
            armor,
            offhand
        );
    }

    private static InventorySlot slot(SlotType type, int index, ItemSnapshot item) {
        return item == null ? InventorySlot.empty(type, index) : InventorySlot.of(type, index, item);
    }

    private static InventoryDataSourceException playerStateChanged(String playerName) {
        return new InventoryDataSourceException(
            InventoryDataSourceException.Reason.PLAYER_STATE_CHANGED,
            "Player state changed while capturing inventory: " + playerName
        );
    }

    static String contentRevision(
        List<InventorySlot> storage,
        List<InventorySlot> hotbar,
        List<InventorySlot> armor,
        InventorySlot offhand
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (InventorySlot slot : combined(storage, hotbar, armor, offhand)) {
                update(digest, slot.getSlotType().name());
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
                    ArmorVisualDescriptor armorVisual = item.getArmorVisual();
                    update(digest, armorVisual == null ? "" : armorVisual.visualKey());
                    PotionVisualDescriptor potionVisual = item.getPotionVisual();
                    update(digest, potionVisual == null ? "" : potionVisual.visualKey());
                    PlayerHeadVisualDescriptor playerHead = item.getPlayerHeadVisual();
                    update(digest, playerHead == null ? "" : playerHead.visualKey());
                }
            }
            byte[] hash = digest.digest();
            StringBuilder value = new StringBuilder("online-inventory-");
            for (int index = 0; index < 12; index++) {
                value.append(String.format(Locale.ROOT, "%02x", hash[index] & 0xff));
            }
            return value.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static List<InventorySlot> combined(
        List<InventorySlot> storage,
        List<InventorySlot> hotbar,
        List<InventorySlot> armor,
        InventorySlot offhand
    ) {
        List<InventorySlot> all = new ArrayList<InventorySlot>(41);
        all.addAll(storage);
        all.addAll(hotbar);
        all.addAll(armor);
        all.add(offhand);
        return all;
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
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
