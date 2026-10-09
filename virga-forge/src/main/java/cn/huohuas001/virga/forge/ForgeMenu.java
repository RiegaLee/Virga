package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.server.game.GameMenu;
import cn.huohuas001.virga.server.game.GameText;
import cn.huohuas001.virga.server.game.MenuClick;
import cn.huohuas001.virga.server.game.MenuIcon;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A chest menu that only shows Virga's icons; clicks are routed back to {@code virga-server}. */
final class ForgeMenu implements GameMenu {
    private static final Logger LOGGER = Logger.getLogger("Virga");

    private final ServerPlayer player;
    private final SimpleContainer container;
    private final int size;
    private AbstractContainerMenu menu;
    private boolean closed;

    private ForgeMenu(ServerPlayer player, int rows) {
        this.player = player;
        this.size = rows * 9;
        this.container = new SimpleContainer(size);
    }

    static ForgeMenu open(
        ServerPlayer player,
        String title,
        int rows,
        Function2<? super Integer, ? super MenuClick, Unit> onClick,
        Function0<Unit> onClose
    ) {
        int safeRows = Math.max(1, Math.min(6, rows));
        ForgeMenu result = new ForgeMenu(player, safeRows);
        MenuType<?> type = switch (safeRows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
        player.openMenu(new SimpleMenuProvider(
            (syncId, inventory, ignored) -> result.menu = result.new LockedChestMenu(type, syncId, inventory, safeRows, onClick, onClose),
            ForgeText.toComponent(GameText.Companion.legacy(title))
        ));
        if (result.menu == null) result.closed = true;
        return result;
    }

    @Override
    public void show(int slot, MenuIcon icon) {
        if (slot < 0 || slot >= size) return;
        container.setItem(slot, icon == null ? ItemStack.EMPTY : stack(icon, player));
    }

    @Override
    public void close() {
        if (isOpen()) player.closeContainer();
    }

    @Override
    public boolean isOpen() {
        return !closed && menu != null && player.containerMenu == menu;
    }

    /**
     * A chest menu whose slots never move: every click is reported and the client is
     * resynchronised, so nothing can be taken out or put in.
     */
    private final class LockedChestMenu extends ChestMenu {
        private final Function2<? super Integer, ? super MenuClick, Unit> onClick;
        private final Function0<Unit> onClose;

        LockedChestMenu(MenuType<?> type, int syncId, net.minecraft.world.entity.player.Inventory inventory, int rows,
                        Function2<? super Integer, ? super MenuClick, Unit> onClick, Function0<Unit> onClose) {
            super(type, syncId, inventory, container, rows);
            this.onClick = onClick;
            this.onClose = onClose;
        }

        @Override
        public void clicked(int slot, int button, ClickType input, Player clicker) {
            if (slot >= 0 && slot < size) {
                boolean quickMove = input == ClickType.QUICK_MOVE;
                boolean pickup = quickMove || input == ClickType.PICKUP;
                MenuClick click = !pickup ? MenuClick.OTHER
                    : button == 0 ? (quickMove ? MenuClick.SHIFT_LEFT : MenuClick.LEFT)
                    : button == 1 ? (quickMove ? MenuClick.SHIFT_RIGHT : MenuClick.RIGHT)
                    : MenuClick.OTHER;
                try {
                    onClick.invoke(slot, click);
                } catch (RuntimeException error) {
                    LOGGER.log(Level.WARNING, "处理 Virga 菜单点击失败", error);
                }
            }
            setCarried(ItemStack.EMPTY);
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player clicker, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player clicker) {
            return true;
        }

        @Override
        public void removed(Player clicker) {
            super.removed(clicker);
            if (closed) return;
            closed = true;
            try {
                onClose.invoke();
            } catch (RuntimeException error) {
                LOGGER.log(Level.WARNING, "关闭 Virga 菜单失败", error);
            }
        }
    }

    static ItemStack stack(MenuIcon icon, ServerPlayer viewer) {
        ResourceLocation id = ResourceLocation.tryParse(icon.getItem());
        Item item = icon.getViewerHead() ? Items.PLAYER_HEAD
            : id == null || !BuiltInRegistries.ITEM.containsKey(id) ? Items.BARRIER : BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) item = Items.BARRIER;
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(item.getMaxStackSize(), icon.getCount())));
        decorate(stack, icon.getName(), icon.getLore());
        if (icon.getGlowing()) {
            // An enchantment list with an empty entry makes the client draw the glint without
            // listing any enchantment in the tooltip.
            ListTag glint = new ListTag();
            glint.add(new CompoundTag());
            stack.getOrCreateTag().put("Enchantments", glint);
        }
        // The live profile carries the skin textures, so the head needs no lookup.
        if (icon.getViewerHead()) {
            stack.getOrCreateTag().put("SkullOwner", NbtUtils.writeGameProfile(new CompoundTag(), viewer.getGameProfile()));
        }
        return stack;
    }

    /** Sets a non-italic name and lore; texts use {@code &} color codes. */
    static void decorate(ItemStack stack, String name, List<String> lore) {
        stack.setHoverName(plain(name));
        if (lore.isEmpty()) return;
        ListTag lines = new ListTag();
        for (String line : lore) lines.add(StringTag.valueOf(Component.Serializer.toJson(plain(line))));
        stack.getOrCreateTagElement("display").put("Lore", lines);
    }

    /** Item names and lore are italic by default; Virga's are not. */
    static Component plain(String legacy) {
        return Component.empty().withStyle(style -> style.withItalic(false))
            .append(ForgeText.toComponent(GameText.Companion.legacy(legacy)));
    }
}
