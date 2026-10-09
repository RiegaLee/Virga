package cn.huohuas001.virga.features.inventory.skin;

import java.util.Optional;

/** Resolves already-local skin pixels; implementations own any storage or network boundary. */
public interface PlayerSkinProvider {
    Optional<PlayerSkin> findSkin(PlayerIdentity player) throws Exception;
}
