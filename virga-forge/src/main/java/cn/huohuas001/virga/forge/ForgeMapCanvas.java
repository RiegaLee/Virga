package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.server.game.GameMapCanvas;
import cn.huohuas001.virga.server.game.GamePlayer;
import cn.huohuas001.virga.server.game.MapHold;
import cn.huohuas001.virga.server.game.MapTone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;

/**
 * A locked overworld map whose pixels Virga sets directly. Locked maps never redraw from the
 * world; the server still sends pixel changes to whoever holds the map.
 */
final class ForgeMapCanvas implements GameMapCanvas {
    private static final int SIZE = 128;

    private final int id;
    private final MapItemSavedData data;

    ForgeMapCanvas(MinecraftServer server) {
        ServerLevel level = server.overworld();
        this.id = level.getFreeMapId();
        this.data = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, Level.OVERWORLD).locked();
        level.setMapData(MapItem.makeKey(id), data);
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void draw(MapTone[] pixels) {
        if (pixels.length != SIZE * SIZE) throw new IllegalArgumentException("map canvas needs " + SIZE * SIZE + " pixels");
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) data.setColor(x, y, packed(pixels[y * SIZE + x]));
        }
    }

    @Override
    public boolean giveTo(GamePlayer player, String name, List<String> lore) {
        ServerPlayer handle = ((ForgeGamePlayer) player).handle;
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.getOrCreateTag().putInt("map", id);
        ForgeMenu.decorate(stack, name, lore);
        // The map goes straight into the main hand; whatever was held moves to a free slot.
        Inventory inventory = handle.getInventory();
        ItemStack held = handle.getMainHandItem();
        if (!held.isEmpty()) {
            int free = inventory.getFreeSlot();
            if (free < 0) return false;
            inventory.setItem(free, held.copy());
        }
        handle.setItemInHand(InteractionHand.MAIN_HAND, stack);
        handle.containerMenu.broadcastChanges();
        return true;
    }

    @Override
    public void takeBackFrom(GamePlayer player) {
        ServerPlayer handle = ((ForgeGamePlayer) player).handle;
        Inventory inventory = handle.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isThisMap(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
        }
        if (isThisMap(handle.containerMenu.getCarried())) handle.containerMenu.setCarried(ItemStack.EMPTY);
        handle.containerMenu.broadcastChanges();
    }

    @Override
    public MapHold keepInHand(GamePlayer player) {
        ServerPlayer handle = ((ForgeGamePlayer) player).handle;
        if (!handle.isAlive() || handle.hasDisconnected()) return MapHold.LOST;
        Inventory inventory = handle.getInventory();
        int selected = inventory.selected;
        if (isThisMap(inventory.getItem(selected))) {
            removeCopies(inventory, selected);
            return MapHold.HELD;
        }
        // Another slot (hotbar scroll, offhand swap, inventory drag)?
        ItemStack map = ItemStack.EMPTY;
        int from = -1;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isThisMap(inventory.getItem(slot))) {
                map = inventory.getItem(slot);
                from = slot;
                inventory.setItem(slot, ItemStack.EMPTY);
                break;
            }
        }
        // Held on the cursor of an open screen?
        if (map.isEmpty() && isThisMap(handle.containerMenu.getCarried())) {
            map = handle.containerMenu.getCarried();
            handle.containerMenu.setCarried(ItemStack.EMPTY);
        }
        // Just dropped (Q)? Nobody can pick a player's drop up for two seconds, and this runs every tick.
        if (map.isEmpty()) {
            List<ItemEntity> drops = handle.level().getEntitiesOfClass(
                ItemEntity.class, handle.getBoundingBox().inflate(32.0), entity -> isThisMap(entity.getItem()));
            if (!drops.isEmpty()) {
                map = drops.get(0).getItem().copy();
                drops.forEach(ItemEntity::discard);
            }
        }
        if (map.isEmpty()) return MapHold.LOST;
        ItemStack held = inventory.getItem(selected);
        int place = from >= 0 ? from : inventory.getFreeSlot();
        if (!held.isEmpty() && place < 0) return MapHold.LOST;
        inventory.setItem(selected, map);
        if (!held.isEmpty()) inventory.setItem(place, held);
        removeCopies(inventory, selected);
        handle.containerMenu.broadcastChanges();
        return MapHold.RESTORED;
    }

    @Override
    public void clear() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) data.setColor(x, y, packed(MapTone.PAPER));
        }
    }

    /** Only one copy may exist: the one in [keep]. */
    private void removeCopies(Inventory inventory, int keep) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (slot != keep && isThisMap(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
        }
    }

    private boolean isThisMap(ItemStack stack) {
        if (!stack.is(Items.FILLED_MAP)) return false;
        Integer mapId = MapItem.getMapId(stack);
        return mapId != null && mapId == id;
    }

    private static byte packed(MapTone tone) {
        return switch (tone) {
            case PAPER -> MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH);
            case INK -> MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.LOW);
            case ACCENT -> MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.HIGH);
            case ACCENT_DEEP -> MapColor.COLOR_PURPLE.getPackedId(MapColor.Brightness.LOW);
        };
    }
}
