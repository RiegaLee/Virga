package cn.huohuas001.virga.features.inventory.skin;

import cn.huohuas001.virga.features.inventory.armor.ArmorEquipmentSet;
import cn.huohuas001.virga.features.inventory.armor.EquipmentAssetResolver;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Optional visual enhancement that always falls back locally and never fails an inventory query. */
public final class PlayerPreviewService {
    private final boolean enabled;
    private final PlayerSkinProvider primary;
    private final DefaultPlayerSkinProvider fallback;
    private final boolean render3d;
    private final PlayerSkinRenderer legacyRenderer;
    private final PlayerModelRenderer modelRenderer;
    private final Path previewCache;
    private final EquipmentAssetResolver equipmentAssets;
    private final Logger logger;
    private final Set<String> warnedPlayers = ConcurrentHashMap.newKeySet();

    public PlayerPreviewService(
        boolean enabled,
        PlayerSkinProvider primary,
        Path cacheRoot,
        Logger logger
    ) {
        this(enabled, primary, cacheRoot, logger, "3d", null);
    }

    public PlayerPreviewService(
        boolean enabled,
        PlayerSkinProvider primary,
        Path cacheRoot,
        Logger logger,
        String mode
    ) {
        this(enabled, primary, cacheRoot, logger, mode, null);
    }

    public PlayerPreviewService(
        boolean enabled,
        PlayerSkinProvider primary,
        Path cacheRoot,
        Logger logger,
        String mode,
        EquipmentAssetResolver equipmentAssets
    ) {
        this(
            enabled, primary, cacheRoot, logger, mode, equipmentAssets,
            PlayerModelRenderer.WIDTH, PlayerModelRenderer.HEIGHT
        );
    }

    public PlayerPreviewService(
        boolean enabled,
        PlayerSkinProvider primary,
        Path cacheRoot,
        Logger logger,
        String mode,
        EquipmentAssetResolver equipmentAssets,
        int previewWidth,
        int previewHeight
    ) {
        this.enabled = enabled;
        this.primary = primary;
        this.fallback = new DefaultPlayerSkinProvider();
        this.render3d = "3d".equalsIgnoreCase(mode);
        this.legacyRenderer = new PlayerSkinRenderer();
        this.modelRenderer = new PlayerModelRenderer(previewWidth, previewHeight);
        this.previewCache = cacheRoot.toAbsolutePath().normalize().resolve("previews");
        this.logger = logger;
        this.equipmentAssets = equipmentAssets;
    }

    public BufferedImage preview(InventorySnapshot snapshot) {
        if (!enabled) return null;
        PlayerSkin skin = fallback.getFallback();
        if (primary != null) {
            try {
                Optional<PlayerSkin> found = primary.findSkin(new PlayerIdentity(snapshot.getPlayerUuid(), snapshot.getPlayerName()));
                if (found.isPresent()) skin = found.get();
            } catch (Throwable error) {
                if (warnedPlayers.add(snapshot.getPlayerName().toLowerCase(java.util.Locale.ROOT))) {
                    logger.log(Level.WARNING, "Could not resolve player skin for " + snapshot.getPlayerName() +
                        "; using local default preview", error);
                }
            }
        }
        ArmorEquipmentSet equipment = render3d ? ArmorEquipmentSet.from(snapshot) : ArmorEquipmentSet.empty();
        return cachedPreview(skin, equipment);
    }

    private BufferedImage cachedPreview(PlayerSkin skin, ArmorEquipmentSet equipment) {
        String version = render3d
            ? PlayerModelRenderer.CACHE_VERSION + "-" + modelRenderer.getWidth() + "x" + modelRenderer.getHeight()
            : PlayerSkinRenderer.CACHE_VERSION;
        String shape = skin.isSlim() ? "slim" : "classic";
        String equipmentKey = render3d ? equipment.fingerprint() : "2d";
        Path file = previewCache.resolve(
            version + "-" + shape + "-" + skin.getCacheKey() + "-" + equipmentKey + ".png"
        ).normalize();
        if (!file.startsWith(previewCache)) return render(skin, equipment);
        if (Files.isRegularFile(file)) {
            try {
                BufferedImage cached = ImageIO.read(file.toFile());
                int expectedWidth = render3d ? modelRenderer.getWidth() : PlayerSkinRenderer.WIDTH;
                int expectedHeight = render3d ? modelRenderer.getHeight() : PlayerSkinRenderer.HEIGHT;
                if (cached != null && cached.getWidth() == expectedWidth &&
                    cached.getHeight() == expectedHeight) return cached;
            } catch (Exception ignored) {
                // Regenerate the content-addressed preview below.
            }
        }
        BufferedImage rendered = render(skin, equipment);
        try {
            Files.createDirectories(previewCache);
            Path temporary = Files.createTempFile(previewCache, skin.getCacheKey() + "-", ".tmp");
            try {
                ImageIO.write(rendered, "png", temporary.toFile());
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception error) {
            logger.log(Level.FINE, "Could not cache player preview " + file, error);
        }
        return rendered;
    }

    private BufferedImage render(PlayerSkin skin, ArmorEquipmentSet equipment) {
        return render3d ? modelRenderer.render(skin, equipment, equipmentAssets) : legacyRenderer.render(skin);
    }
}
