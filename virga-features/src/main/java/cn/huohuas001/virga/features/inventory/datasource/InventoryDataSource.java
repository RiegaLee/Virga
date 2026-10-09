package cn.huohuas001.virga.features.inventory.datasource;

import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;

import java.util.concurrent.CompletionStage;

/** Internal asynchronous source boundary; implementations must return platform-neutral snapshots. */
public interface InventoryDataSource {
    CompletionStage<InventorySnapshot> getInventory(String playerName);
}
