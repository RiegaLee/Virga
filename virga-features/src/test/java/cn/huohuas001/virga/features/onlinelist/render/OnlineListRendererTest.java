package cn.huohuas001.virga.features.onlinelist.render;

import cn.huohuas001.virga.features.onlinelist.model.PlayerSnapshot;
import cn.huohuas001.virga.features.onlinelist.model.ServerSnapshot;
import cn.huohuas001.virga.features.onlinelist.skin.AvatarCache;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineListRendererTest {
    @Test
    void rendersEmptyAndMultiRowPngsFromBundledVirgaMaster() throws Exception {
        OnlineListRenderer renderer = renderer();
        for (int count : new int[]{0, 1, 27}) {
            byte[] png = renderer.render(snapshot(count));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(image);
            assertEquals(1200, image.getWidth());
            assertTrue(image.getHeight() >= 1280);
            assertEquals(0, (image.getHeight() - OnlineListRenderer.HEADER_HEIGHT - OnlineListRenderer.FOOTER_HEIGHT)
                % OnlineListRenderer.BODY_TILE_HEIGHT);
        }
    }

    @Test
    void bundledSteveFallbackHasOpaqueFacePixels() {
        BufferedImage steve = OnlineListRenderer.createDefaultSteveHead();
        assertEquals(8, steve.getWidth());
        assertEquals(0xFF523D89, steve.getRGB(2, 4));
    }

    @Test
    void bundledThemeUsesAuroraPaletteAndRoundedCorners() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(renderer().render(snapshot(1))));
        // Outer corners are rounded off; just inside them the aurora canvas shows.
        assertEquals(0, new Color(image.getRGB(0, 0), true).getAlpha());
        Color corner = new Color(image.getRGB(40, 40), true);

        // Aurora canvas under the global veil: still a mint tone, muted for white text.
        assertTrue(corner.getGreen() > 120 && corner.getGreen() > corner.getRed() + 30);
        assertTrue(corner.getBlue() > 110);
        assertEquals(40, OnlineListRenderer.COMPACT_CARD_ARC);
        assertEquals(48, OnlineListRenderer.REGULAR_CARD_ARC);
        assertEquals(24, OnlineListRenderer.COMPACT_AVATAR_ARC);
        assertEquals(30, OnlineListRenderer.REGULAR_AVATAR_ARC);
        assertEquals(48, OnlineListRenderer.EMPTY_CARD_ARC);
    }

    private static OnlineListRenderer renderer() throws Exception {
        AvatarCache avatars = new AvatarCache(Logger.getLogger("test"), Runnable::run, 500, 500, 16, false);
        return new OnlineListRenderer(avatars, 3, "", "POWERED BY Virga");
    }

    private static ServerSnapshot snapshot(int count) {
        List<PlayerSnapshot> players = new ArrayList<PlayerSnapshot>();
        for (int i = 1; i <= count; i++) {
            players.add(new PlayerSnapshot("Player_" + i, "uuid-" + i, null));
        }
        return new ServerSnapshot("Virga 测试服", 100, players, Instant.EPOCH);
    }
}
