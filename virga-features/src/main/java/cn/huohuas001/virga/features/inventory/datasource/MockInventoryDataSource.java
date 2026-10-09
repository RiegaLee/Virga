package cn.huohuas001.virga.features.inventory.datasource;

import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Stable fixture covering stacks, tools, empty slots, armor, offhand and fallback textures. */
public final class MockInventoryDataSource implements InventoryDataSource {
    private static final UUID MOCK_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final String sourceServer;

    public MockInventoryDataSource(String sourceServer) {
        this.sourceServer = requireText(sourceServer, "sourceServer");
    }

    @Override
    public CompletionStage<InventorySnapshot> getInventory(String playerName) {
        String normalized = requireText(playerName, "playerName");
        return CompletableFuture.completedFuture(createSnapshot(normalized));
    }

    public InventorySnapshot createSnapshot(String playerName) {
        List<InventorySlot> storage = emptyGrid(SlotType.STORAGE, 27);
        storage.set(0, InventorySlot.of(SlotType.STORAGE, 0, ItemSnapshot.basic("minecraft:stone", 64)));
        storage.set(1, InventorySlot.of(SlotType.STORAGE, 1, ItemSnapshot.basic("minecraft:diamond", 12)));
        storage.set(2, InventorySlot.of(SlotType.STORAGE, 2, ItemSnapshot.basic("minecraft:golden_apple", 8)));
        storage.set(3, InventorySlot.of(SlotType.STORAGE, 3, ItemSnapshot.basic("minecraft:bread", 16)));
        storage.set(4, InventorySlot.of(SlotType.STORAGE, 4, ItemSnapshot.basic("minecraft:mystery_relic", 3)));
        storage.set(5, InventorySlot.of(
            SlotType.STORAGE,
            5,
            ItemSnapshot.basic("minecraft:totem_of_undying", 1)
        ));

        List<InventorySlot> hotbar = emptyGrid(SlotType.HOTBAR, 9);
        hotbar.set(0, InventorySlot.of(SlotType.HOTBAR, 0, ItemSnapshot.durable("minecraft:diamond_sword", 212, 1561)));
        hotbar.set(1, InventorySlot.of(SlotType.HOTBAR, 1, ItemSnapshot.durable("minecraft:diamond_pickaxe", 731, 1561)));
        hotbar.set(2, InventorySlot.of(SlotType.HOTBAR, 2, ItemSnapshot.basic("minecraft:firework_rocket", 64)));
        hotbar.set(3, InventorySlot.of(SlotType.HOTBAR, 3, ItemSnapshot.basic("minecraft:cooked_beef", 24)));

        List<InventorySlot> armor = Arrays.asList(
            InventorySlot.of(SlotType.ARMOR_HEAD, 0, ItemSnapshot.durable("minecraft:diamond_helmet", 41, 363)),
            InventorySlot.of(SlotType.ARMOR_CHEST, 0, ItemSnapshot.durable("minecraft:diamond_chestplate", 83, 528)),
            InventorySlot.of(SlotType.ARMOR_LEGS, 0, ItemSnapshot.durable("minecraft:diamond_leggings", 75, 495)),
            InventorySlot.of(SlotType.ARMOR_FEET, 0, ItemSnapshot.durable("minecraft:diamond_boots", 29, 429))
        );
        InventorySlot offhand = InventorySlot.of(
            SlotType.OFFHAND,
            0,
            new ItemSnapshot("minecraft:shield", 1, 68, 336, null, null, true, null)
        );

        return new InventorySnapshot(
            InventorySnapshot.CURRENT_SCHEMA_VERSION,
            MOCK_UUID,
            playerName,
            Instant.parse("2026-08-24T00:00:00Z"),
            sourceServer,
            "mock-inventory-v2",
            storage,
            hotbar,
            armor,
            offhand
        );
    }

    private static List<InventorySlot> emptyGrid(SlotType type, int size) {
        List<InventorySlot> slots = new ArrayList<InventorySlot>(size);
        for (int index = 0; index < size; index++) slots.add(InventorySlot.empty(type, index));
        return slots;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
