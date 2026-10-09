package cn.huohuas001.virga.features.inventory.skin;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerModelRendererTest {
    @Test
    void compactArmorGeometryTracksThePlayerArmModelAndInvalidatesOldPreviews() {
        assertEquals("pv14", PlayerModelRenderer.CACHE_VERSION);
        assertEquals(0.5, PlayerModelRenderer.OUTER_ARMOR_EXPANSION);
        assertEquals(0.25, PlayerModelRenderer.INNER_ARMOR_EXPANSION);
        assertEquals(4, PlayerModelRenderer.armorArmWidth(false));
        assertEquals(3, PlayerModelRenderer.armorArmWidth(true));
        assertEquals(4, PlayerModelRenderer.supersampleScale(128, 256));
        assertEquals(2, PlayerModelRenderer.supersampleScale(272, 373));
        assertEquals(1, PlayerModelRenderer.outlineRadius(373));
        assertEquals(-1, Integer.signum(PlayerModelRenderer.comparePaintOrder(1.0, 3, 2.0, 0)),
            "a farther armor face must be painted before a nearer head face");
        assertEquals(-1, Integer.signum(PlayerModelRenderer.comparePaintOrder(2.0, 0, 2.0, 3)),
            "render layers only break ties for coincident skin and armor faces");
    }

    @Test
    void enchantedArmorOutlineIsThinAndDoesNotRecolorTheArmorSurface() {
        BufferedImage model = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
        model.setRGB(2, 2, 0xff30343a);
        BufferedImage mask = new BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB);
        mask.setRGB(2, 2, 0xffffffff);

        BufferedImage outlined = PlayerModelRenderer.addEnchantedArmorOutline(model, mask, 1);

        assertEquals(0xff30343a, outlined.getRGB(2, 2),
            "the armor surface is reserved for the glint texture, not the contour pass");
        assertEquals(0, outlined.getRGB(0, 0));
        assertEquals(PlayerModelRenderer.ARMOR_OUTLINE_ALPHA, outlined.getRGB(2, 1) >>> 24);
        assertEquals(PlayerModelRenderer.ARMOR_OUTLINE_RGB, outlined.getRGB(2, 1) & 0xffffff);
        assertTrue((outlined.getRGB(1, 1) >>> 24) > 0, "diagonal neighbors form one continuous pixel rim");
    }
}
