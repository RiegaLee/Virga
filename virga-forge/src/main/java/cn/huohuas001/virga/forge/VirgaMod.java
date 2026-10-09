package cn.huohuas001.virga.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import cn.huohuas001.virga.api.VirgaService;
import cn.huohuas001.virga.core.VirgaLogger;
import cn.huohuas001.virga.server.VirgaRuntime;
import cn.huohuas001.virga.server.game.GameCommandSource;
import cn.huohuas001.virga.server.game.GamePlayer;
import cn.huohuas001.virga.server.game.GameText;
import cn.huohuas001.virga.server.platform.TickScheduler;
import kotlin.Unit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Virga — the Forge / NeoForge dedicated-server mod. Everything that is not Minecraft
 * glue lives in {@code virga-server}; this class only forwards lifecycle, tick, chat,
 * connection and command events to {@link VirgaRuntime}.
 */
@Mod(VirgaMod.MOD_ID)
public final class VirgaMod {
    public static final String MOD_ID = "virga";

    private static volatile VirgaRuntime runtime;
    private static volatile ForgeGameServer gameServer;
    private static volatile TickScheduler scheduler;

    /** Public addon API for other server mods; empty until the server has started. */
    public static VirgaService service() {
        VirgaRuntime current = runtime;
        return current == null ? null : current.getService();
    }

    public VirgaMod() {
        // Virga is a dedicated-server mod: a client that happens to have the JAR stays untouched.
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            ForgeLogging.LOGGER.info("Virga 只在专用服务器上运行，客户端不做任何事。");
            return;
        }
        // The renderers only draw into images; make sure AWT never tries to open a display.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");

        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(this::onServerStarted);
        bus.addListener(this::onServerStopping);
        bus.addListener(this::onServerTick);
        bus.addListener(this::onPlayerLoggedIn);
        bus.addListener(this::onPlayerLoggedOut);
        // Lines Virga asked for (the server address typed after the settings menu) stay private:
        // they are cancelled first, so they are neither broadcast nor bridged to QQ.
        bus.addListener(EventPriority.HIGHEST, false, ServerChatEvent.class, this::interceptChat);
        // Bridge only what other mods let through.
        bus.addListener(EventPriority.LOWEST, false, ServerChatEvent.class, this::relayChat);
        bus.addListener(this::onRegisterCommands);
        bus.addListener(this::onGatherPermissions);
    }

    // ---------- Lifecycle ----------

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        VirgaLogger logger = ForgeLogging.core();
        try {
            String version = ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("dev");
            Path dataDirectory = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
            ForgeGameServer game = new ForgeGameServer(server, dataDirectory, version);
            // server::execute keeps one-shot work flowing even when no tick callback arrives.
            TickScheduler ticks = new TickScheduler(game.getPlatformName(), server::isSameThread,
                (message, error) -> { logger.error(message, error); return Unit.INSTANCE; },
                task -> { server.execute(task); return Unit.INSTANCE; });
            VirgaRuntime created = new VirgaRuntime(game, logger, ForgeLogging.jul(), ticks);
            gameServer = game;
            scheduler = ticks;
            created.start();
            runtime = created;
        } catch (Throwable error) {
            runtime = null;
            scheduler = null;
            logger.error("Virga 没能启动，服务器其余部分照常运行。", error);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        VirgaRuntime current = runtime;
        runtime = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable error) {
                ForgeLogging.LOGGER.error("Virga 关闭时出错", error);
            }
        }
        scheduler = null;
        gameServer = null;
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TickScheduler current = scheduler;
        if (current != null) current.tick();
    }

    // ---------- Players ----------

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        TickScheduler ticks = scheduler;
        if (ticks == null) return;
        // Other mods are still reacting to the login: handle it at the end of the tick.
        ticks.global(() -> {
            VirgaRuntime current = runtime;
            ForgeGameServer game = gameServer;
            if (current != null && game != null && !player.hasDisconnected()) current.onPlayerJoin(game.wrap(player));
            return Unit.INSTANCE;
        });
    }

    /** Fired on the server thread while the player is being removed from the player list. */
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        VirgaRuntime current = runtime;
        ForgeGameServer game = gameServer;
        if (current != null && game != null) {
            current.onPlayerQuit(game.wrap(player));
            game.forget(player);
        }
    }

    private void interceptChat(ServerChatEvent event) {
        VirgaRuntime current = runtime;
        ForgeGameServer game = gameServer;
        if (current == null || game == null) return;
        if (current.interceptChat(game.wrap(event.getPlayer()), event.getRawText())) event.setCanceled(true);
    }

    private void relayChat(ServerChatEvent event) {
        VirgaRuntime current = runtime;
        if (current != null) current.onChat(event.getUsername(), event.getRawText());
    }

    private void onGatherPermissions(PermissionGatherEvent.Nodes event) {
        event.addNodes(ForgePermissions.nodes());
    }

    // ---------- Commands ----------

    private void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        String label = "virga";
        dispatcher.register(Commands.literal(label)
            .executes(context -> runVirga(context, label, ""))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .suggests(VirgaMod::suggestVirga)
                .executes(context -> runVirga(context, label, StringArgumentType.getString(context, "args")))));
        dispatcher.register(Commands.literal("authcode")
            .executes(context -> runAuthcode(context, ""))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .executes(context -> runAuthcode(context, StringArgumentType.getString(context, "args")))));
    }

    private static int runVirga(CommandContext<CommandSourceStack> context, String label, String raw) {
        CommandSourceStack source = context.getSource();
        VirgaRuntime current = runtime;
        ForgeGameServer game = gameServer;
        if (current == null || game == null) {
            source.sendSystemMessage(ForgeText.toComponent(GameText.Companion.legacy("&d[Virga] 还在睡觉，等服务器完全启动再叫我。")));
            return 0;
        }
        current.getCommand().execute(new ForgeCommandSource(source, game), label, split(raw));
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestVirga(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        VirgaRuntime current = runtime;
        if (current == null) return builder.buildFuture();
        String remaining = builder.getRemaining();
        List<String> args = new ArrayList<>(split(remaining));
        if (remaining.isEmpty() || remaining.endsWith(" ")) args.add("");
        String prefix = remaining.substring(0, remaining.length() - args.get(args.size() - 1).length());
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + prefix.length());
        for (String option : current.getCommand().complete(args)) offset.suggest(option);
        return offset.buildFuture();
    }

    private static int runAuthcode(CommandContext<CommandSourceStack> context, String raw) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        VirgaRuntime current = runtime;
        ForgeGameServer game = gameServer;
        if (player == null) {
            source.sendSystemMessage(ForgeText.toComponent(GameText.Companion.legacy("[Virga] 这条指令只能由游戏里的玩家使用。")));
            return 0;
        }
        if (current == null || game == null) {
            source.sendSystemMessage(ForgeText.toComponent(GameText.Companion.legacy("&d[Virga] 还在睡觉，等服务器完全启动再叫我。")));
            return 0;
        }
        current.authcode(game.wrap(player), split(raw));
        return 1;
    }

    private static List<String> split(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    /** `/virga` sender: console or player. */
    private record ForgeCommandSource(CommandSourceStack source, ForgeGameServer game) implements GameCommandSource {
        @Override
        public GamePlayer getPlayer() {
            ServerPlayer player = source.getPlayer();
            return player == null ? null : game.wrap(player);
        }

        @Override
        public boolean isConsole() {
            return source.getEntity() == null && "Server".equals(source.getTextName());
        }

        @Override
        public boolean hasPermission(String node, int defaultOpLevel) {
            return ForgePermissions.has(source, node, defaultOpLevel);
        }

        @Override
        public void reply(GameText text) {
            source.sendSystemMessage(ForgeText.toComponent(text));
        }

        @Override
        public void reply(String legacy) {
            reply(GameText.Companion.legacy(legacy));
        }
    }
}
