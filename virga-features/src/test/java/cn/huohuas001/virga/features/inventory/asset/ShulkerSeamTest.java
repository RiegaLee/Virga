package cn.huohuas001.virga.features.inventory.asset;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShulkerSeamTest {
    @Test
    void allBundledShulkerIconsHaveOpaqueLidSideJoin() throws Exception {
        String[] colors = {
            "", "white_", "orange_", "magenta_", "light_blue_", "yellow_", "lime_", "pink_",
            "gray_", "light_gray_", "cyan_", "purple_", "blue_", "brown_", "green_", "red_", "black_"
        };
        for (String color : colors) {
            String name = color + "shulker_box.png";
            String resource = "bundled-assets/pack/themes/virga/overrides/items/minecraft/" + name;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                BufferedImage image = ImageIO.read(input);
                assertNotNull(image, resource + " must decode");
                for (int y = 17; y <= 28; y++) {
                    int x = 72 - y;
                    assertTrue(((image.getRGB(x, y) >>> 24) & 0xff) > 0,
                        name + " has a transparent lid/side seam at " + x + "," + y);
                }
            }
        }
    }
}
