package cn.huohuas001.virga.features.inventory.qq;

@FunctionalInterface
public interface InventoryButtonHandler {
    InventoryButtonResult handle(InventoryButtonInteraction interaction);
}
