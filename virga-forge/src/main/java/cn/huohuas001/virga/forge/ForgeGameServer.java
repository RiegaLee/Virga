package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.server.game.GameMapCanvas;
import cn.huohuas001.virga.server.game.GamePlayer;
import cn.huohuas001.virga.server.game.GameServer;
import cn.huohuas001.virga.server.game.GameText;
import com.mojang.authlib.GameProfile;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraftforge.fml.ModList;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** {@link GameServer} on a Forge / NeoForge dedicated server. Game members run on the server thread. */
final class ForgeGameServer implements GameServer {
    private final MinecraftServer server;
    private final Path dataDirectory;
    private final String modVersion;
    private final String platformName = isNeoForge() ? "NeoForge" : "Forge";
    private final Map<UUID, ForgeGamePlayer> players = new ConcurrentHashMap<>();

    ForgeGameServer(MinecraftServer server, Path dataDirectory, String modVersion) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.modVersion = modVersion;
    }

    /** Stable wrapper per connection, so identity checks survive between calls. */
    ForgeGamePlayer wrap(ServerPlayer player) {
        return players.compute(player.getUUID(), (uuid, existing) ->
            existing != null && existing.handle == player ? existing : new ForgeGamePlayer(server, player));
    }

    void forget(ServerPlayer player) {
        players.computeIfPresent(player.getUUID(), (uuid, existing) -> existing.handle == player ? null : existing);
    }

    @Override
    public String getPlatformName() {
        return platformName;
    }

    /** NeoForge 1.20.1 still reports itself as mod "forge"; its display name tells them apart. */
    private static boolean isNeoForge() {
        return ModList.get().getModContainerById("forge")
            .map(container -> container.getModInfo().getDisplayName().toLowerCase(Locale.ROOT).contains("neoforge"))
            .orElse(false);
    }

    @Override
    public String getMinecraftVersion() {
        return server.getServerVersion();
    }

    @Override
    public String getModVersion() {
        return modVersion;
    }

    @Override
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public List<GamePlayer> onlinePlayers() {
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        List<GamePlayer> result = new ArrayList<>(online.size());
        for (ServerPlayer player : online) result.add(wrap(player));
        return result;
    }

    @Override
    public GamePlayer player(UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player == null ? null : wrap(player);
    }

    @Override
    public GamePlayer playerExact(String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) return wrap(player);
        }
        return null;
    }

    @Override
    public int maxPlayers() {
        return server.getPlayerList().getMaxPlayers();
    }

    @Override
    public double averageTickMillis() {
        long[] samples = server.tickTimes;
        long total = 0;
        int count = 0;
        for (long sample : samples) {
            if (sample <= 0) continue;
            total += sample;
            count++;
        }
        return count == 0 ? Double.NaN : total / (double) count / 1_000_000.0;
    }

    @Override
    public void broadcast(GameText text) {
        server.getPlayerList().broadcastSystemMessage(ForgeText.toComponent(text), false);
    }

    @Override
    public boolean dispatchCommand(String command, Function1<? super String, Unit> feedback) {
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                feedback.invoke(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture);
        server.getCommands().performPrefixedCommand(source, command);
        return true;
    }

    @Override
    public InputStream resource(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return VirgaMod.class.getClassLoader().getResourceAsStream(normalized);
    }

    @Override
    public ClassLoader getResourceLoader() {
        return VirgaMod.class.getClassLoader();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean permissionModActive() {
        return ForgePermissions.handlerInstalled();
    }

    @Override
    public UUID knownPlayerUuid(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) return null;
        try {
            GameProfileCache cache = server.getProfileCache();
            if (cache == null) return null;
            return cache.get(name).map(GameProfile::getId).orElse(null);
        } catch (RuntimeException error) {
            return null;
        }
    }

    @Override
    public GameMapCanvas createMapCanvas() {
        return new ForgeMapCanvas(server);
    }

    @Override
    public String toString() {
        return "ForgeGameServer[" + server.getServerVersion().toLowerCase(Locale.ROOT) + "]";
    }
}
