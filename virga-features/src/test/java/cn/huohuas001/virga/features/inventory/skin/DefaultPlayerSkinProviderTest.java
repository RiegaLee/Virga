package cn.huohuas001.virga.features.inventory.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultPlayerSkinProviderTest {
    @Test
    void loadsTheVerifiedWideVanillaSteveInsteadOfAGeneratedApproximation() {
        PlayerSkin skin = new DefaultPlayerSkinProvider().getFallback();

        assertEquals(64, skin.getImage().getWidth());
        assertEquals(64, skin.getImage().getHeight());
        assertFalse(skin.isSlim());
        assertEquals("MINECRAFT_CLIENT_26.1.2", skin.getSource());
        assertEquals(DefaultPlayerSkinProvider.EXPECTED_SHA256.toLowerCase(java.util.Locale.ROOT), skin.getCacheKey());
    }
}
