package cn.huohuas001.virga.features.inventory.datasource;

import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockInventoryDataSourceTest {
    @Test
    void createsStableCompleteSnapshotWithExpectedCoverage() {
        InventorySnapshot snapshot = new MockInventoryDataSource("test-server")
            .getInventory("MockPlayer")
            .toCompletableFuture()
            .join();

        assertEquals(InventorySnapshot.CURRENT_SCHEMA_VERSION, snapshot.getSchemaVersion());
        assertEquals("MockPlayer", snapshot.getPlayerName());
        assertEquals("test-server", snapshot.getSourceServer());
        assertEquals("mock-inventory-v2", snapshot.getContentRevision());
        assertNotNull(snapshot.getPlayerUuid());
        assertEquals(27, snapshot.getStorage().size());
        assertEquals(9, snapshot.getHotbar().size());
        assertEquals(4, snapshot.getArmor().size());
        assertEquals(41, snapshot.getAllSlots().size());

        assertEquals("minecraft:stone", snapshot.getStorage().get(0).getItem().getMaterialKey());
        assertEquals(64, snapshot.getStorage().get(0).getItem().getAmount());
        assertEquals("minecraft:diamond", snapshot.getStorage().get(1).getItem().getMaterialKey());
        assertEquals(12, snapshot.getStorage().get(1).getItem().getAmount());
        assertEquals("minecraft:totem_of_undying", snapshot.getStorage().get(5).getItem().getMaterialKey());
        assertTrue(snapshot.getStorage().get(20).isEmpty());
        assertNull(snapshot.getStorage().get(20).getItem());
        assertEquals("minecraft:diamond_sword", snapshot.getHotbar().get(0).getItem().getMaterialKey());
        assertEquals(SlotType.ARMOR_HEAD, snapshot.getArmor().get(0).getSlotType());
        assertEquals(SlotType.OFFHAND, snapshot.getOffhand().getSlotType());
        assertFalse(snapshot.getOffhand().isEmpty());
    }

    @Test
    void mockIsRepeatable() {
        MockInventoryDataSource source = new MockInventoryDataSource("test-server");
        InventorySnapshot first = source.createSnapshot("MockPlayer");
        InventorySnapshot second = source.createSnapshot("MockPlayer");
        assertEquals(first.getContentRevision(), second.getContentRevision());
        assertEquals(first.getCapturedAt(), second.getCapturedAt());
        assertEquals(first.getAllSlots().size(), second.getAllSlots().size());
    }
}
