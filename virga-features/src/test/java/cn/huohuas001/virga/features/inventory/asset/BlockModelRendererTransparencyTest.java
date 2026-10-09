package cn.huohuas001.virga.features.inventory.asset;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockModelRendererTransparencyTest {
    @Test
    void nestedTranslucentSurfacesBlendBackToFront() {
        Map<String, BlockModelRenderer.Face> faces = new LinkedHashMap<String, BlockModelRenderer.Face>();
        for (String direction : Arrays.asList("down", "up", "north", "south", "west", "east")) {
            faces.put(direction, new BlockModelRenderer.Face("#all", null, 0));
        }
        BlockModelRenderer.Element outer = cube(0, 16, faces);
        BlockModelRenderer.Element inner = cube(1, 15, faces);
        BufferedImage texture = solid(new Color(238, 155, 26, 128));
        BlockModelRenderer renderer = new BlockModelRenderer();
        BlockModelRenderer.Transform gui = new BlockModelRenderer.Transform(
            new BlockModelRenderer.Vec3(30, 225, 0),
            new BlockModelRenderer.Vec3(0, 0, 0),
            new BlockModelRenderer.Vec3(0.625, 0.625, 0.625)
        );
        BufferedImage result = renderer.render(
            new BlockModelRenderer.Model(
                Collections.singletonMap("all", "minecraft:block/translucent"),
                Arrays.asList(outer, inner), gui
            ),
            ignored -> texture
        );

        int layeredPixels = 0;
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                if ((result.getRGB(x, y) >>> 24) > 128) layeredPixels++;
            }
        }
        assertTrue(layeredPixels > 500, "nested translucent surfaces must retain their inner color layer");
    }

    private static BlockModelRenderer.Element cube(
        int from,
        int to,
        Map<String, BlockModelRenderer.Face> faces
    ) {
        return new BlockModelRenderer.Element(
            new BlockModelRenderer.Vec3(from, from, from),
            new BlockModelRenderer.Vec3(to, to, to),
            faces,
            null,
            true
        );
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, color.getRGB());
        }
        return image;
    }
}
