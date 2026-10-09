package cn.huohuas001.virga.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.Map;

/**
 * Permission nodes such as {@code virga.admin}. They are registered with Forge's permission API,
 * so a permission mod (LuckPerms and friends) can grant them; with Forge's default handler, or for
 * nodes Virga does not register, the vanilla operator level decides.
 */
final class ForgePermissions {
    /** Default level for registered nodes: 2 = command blocks / gamemasters. */
    private static final int DEFAULT_LEVEL = 2;

    private static final Map<String, PermissionNode<Boolean>> NODES = Map.of(
        "virga.admin", node("admin"),
        "virga.online.priority", node("online.priority")
    );

    private ForgePermissions() {}

    private static PermissionNode<Boolean> node(String name) {
        return new PermissionNode<>(VirgaMod.MOD_ID, name, PermissionTypes.BOOLEAN,
            (player, uuid, context) -> player != null && player.hasPermissions(DEFAULT_LEVEL));
    }

    static PermissionNode<?>[] nodes() {
        return NODES.values().toArray(new PermissionNode<?>[0]);
    }

    /** True when a permission mod replaced Forge's default handler. */
    static boolean handlerInstalled() {
        try {
            return !"default_handler".equals(PermissionAPI.getActivePermissionHandler().getPath());
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean has(ServerPlayer player, String node, int defaultOpLevel) {
        PermissionNode<Boolean> registered = NODES.get(node);
        if (registered == null || !handlerInstalled()) return player.hasPermissions(clamp(defaultOpLevel));
        try {
            return PermissionAPI.getPermission(player, registered);
        } catch (RuntimeException error) {
            return player.hasPermissions(clamp(defaultOpLevel));
        }
    }

    static boolean has(CommandSourceStack source, String node, int defaultOpLevel) {
        ServerPlayer player = source.getPlayer();
        if (player != null) return has(player, node, defaultOpLevel);
        return source.hasPermission(clamp(defaultOpLevel));
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(4, level));
    }
}
