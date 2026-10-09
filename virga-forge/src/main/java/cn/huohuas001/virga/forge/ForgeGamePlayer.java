package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.server.game.CapturedInventory;
import cn.huohuas001.virga.server.game.GameMenu;
import cn.huohuas001.virga.server.game.GamePlayer;
import cn.huohuas001.virga.server.game.GameText;
import cn.huohuas001.virga.server.game.MenuClick;
import com.mojang.authlib.properties.Property;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** One connected player. Holds the exact ServerPlayer so a reconnect is a different object. */
final class ForgeGamePlayer implements GamePlayer {
    private final MinecraftServer server;
    final ServerPlayer handle;
    private final UUID uuid;
    private final String name;

    ForgeGamePlayer(MinecraftServer server, ServerPlayer handle) {
        this.server = server;
        this.handle = handle;
        this.uuid = handle.getUUID();
        this.name = handle.getGameProfile().getName();
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isOnline() {
        return !handle.hasDisconnected() && server.getPlayerList().getPlayer(uuid) == handle;
    }

    @Override
    public boolean hasPermission(String node, int defaultOpLevel) {
        return ForgePermissions.has(handle, node, defaultOpLevel);
    }

    @Override
    public void send(GameText text) {
        handle.sendSystemMessage(ForgeText.toComponent(text));
    }

    @Override
    public void send(String legacy) {
        send(GameText.Companion.legacy(legacy));
    }

    @Override
    public void kick(String reason) {
        handle.connection.disconnect(Component.literal(reason));
    }

    @Override
    public String texturesProperty() {
        Iterator<Property> textures = handle.getGameProfile().getProperties().get("textures").iterator();
        return textures.hasNext() ? textures.next().getValue() : null;
    }

    @Override
    public CapturedInventory captureInventory() {
        RegistryAccess registries = server.registryAccess();
        Inventory inventory = handle.getInventory();
        List<ItemSnapshot> storage = new ArrayList<>(36);
        for (int index = 0; index < 36; index++) storage.add(ForgeItemMapper.map(inventory.getItem(index), registries));
        return new CapturedInventory(
            storage,
            ForgeItemMapper.map(handle.getItemBySlot(EquipmentSlot.HEAD), registries),
            ForgeItemMapper.map(handle.getItemBySlot(EquipmentSlot.CHEST), registries),
            ForgeItemMapper.map(handle.getItemBySlot(EquipmentSlot.LEGS), registries),
            ForgeItemMapper.map(handle.getItemBySlot(EquipmentSlot.FEET), registries),
            ForgeItemMapper.map(handle.getItemBySlot(EquipmentSlot.OFFHAND), registries)
        );
    }

    @Override
    public List<ItemSnapshot> captureEnderChest() {
        RegistryAccess registries = server.registryAccess();
        PlayerEnderChestContainer ender = handle.getEnderChestInventory();
        List<ItemSnapshot> items = new ArrayList<>(27);
        for (int index = 0; index < 27; index++) {
            items.add(index < ender.getContainerSize() ? ForgeItemMapper.map(ender.getItem(index), registries) : null);
        }
        return items;
    }

    @Override
    public GameMenu openMenu(
        String title,
        int rows,
        Function2<? super Integer, ? super MenuClick, Unit> onClick,
        Function0<Unit> onClose
    ) {
        return ForgeMenu.open(handle, title, rows, onClick, onClose);
    }
}
