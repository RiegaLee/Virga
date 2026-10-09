package cn.huohuas001.virga.features.performance.render;

import cn.huohuas001.virga.features.render.BackdropLibrary;
import cn.huohuas001.virga.features.performance.model.PerformanceSnapshot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceRendererTest {
    @Test
    void rendersFixedSizePng() throws Exception {
        byte[] png = new PerformanceRenderer("").render(snapshot(19.9, 12.6, 34, 4.2, 8));
        assertTrue(png.length > 100_000);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(1792, image.getWidth());
        assertEquals(1008, image.getHeight());
    }

    @Test
    void rendersUnavailableAndCriticalMetricsWithoutFailure() throws Exception {
        PerformanceRenderer renderer = new PerformanceRenderer("");
        assertNotNull(renderer.renderImage(snapshot(Double.NaN, Double.NaN, Double.NaN, 0, 0)));
        assertNotNull(renderer.renderImage(snapshot(16.5, 62.0, 96, 7.7, 8)));
    }

    @Test
    void sameMeasuredMsptRendersIdenticallyRegardlessOfTps() throws Exception {
        PerformanceRenderer renderer = new PerformanceRenderer("");
        BufferedImage fullTps = renderer.renderImage(snapshot(20, 5.0, 34, 4.2, 8));
        BufferedImage lowTps = renderer.renderImage(snapshot(10, 5.0, 34, 4.2, 8));
        BufferedImage missingTps = renderer.renderImage(snapshot(Double.NaN, 5.0, 34, 4.2, 8));
        assertArrayEquals(msptPixels(fullTps), msptPixels(lowTps));
        assertArrayEquals(msptPixels(fullTps), msptPixels(missingTps));
    }

    @Test
    void measuredMsptChangesNumberEvenWhenTpsIsTwenty() throws Exception {
        PerformanceRenderer renderer = new PerformanceRenderer("");
        BufferedImage lightLoad = renderer.renderImage(snapshot(20, 5.0, 34, 4.2, 8));
        BufferedImage heavierLoad = renderer.renderImage(snapshot(20, 35.0, 34, 4.2, 8));
        assertFalse(Arrays.equals(
            lightLoad.getRGB(950, 320, 400, 100, null, 0, 400),
            heavierLoad.getRGB(950, 320, 400, 100, null, 0, 400)
        ));
    }

    @Test
    void unavailableMsptDoesNotUseTps() throws Exception {
        PerformanceRenderer renderer = new PerformanceRenderer("");
        BufferedImage unavailable = renderer.renderImage(snapshot(20, Double.NaN, 34, 4.2, 8));
        assertArrayEquals(msptPixels(unavailable), msptPixels(
            renderer.renderImage(snapshot(10, Double.NaN, 34, 4.2, 8))
        ));
    }

    @Test
    void invalidMsptValuesRenderAsUnavailableWhileZeroRemainsMeasured() throws Exception {
        PerformanceRenderer renderer = new PerformanceRenderer("");
        int[] unavailable = msptPixels(renderer.renderImage(snapshot(20, Double.NaN, 34, 4.2, 8)));
        for (double invalid : new double[] {-1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertArrayEquals(unavailable, msptPixels(renderer.renderImage(snapshot(20, invalid, 34, 4.2, 8))));
        }
        assertFalse(Arrays.equals(unavailable, msptPixels(renderer.renderImage(snapshot(20, 0.0, 34, 4.2, 8)))));
    }

    private static int[] msptPixels(BufferedImage image) {
        // Only the MSPT card (value, unit and rail); excludes TPS and the overall health text.
        return image.getRGB(930, 240, 740, 230, null, 0, 740);
    }

    @Test
    void usesAuroraThemeAndRoundedProgressBars() throws Exception {
        BufferedImage rendered = new PerformanceRenderer("").renderImage(snapshot(19.9, 12.6, 34, 4.2, 8));
        BufferedImage background = BackdropLibrary.bundled().get(BackdropLibrary.Surface.STATUS);

        Color corner = new Color(background.getRGB(60, 40));
        // Aurora canvas under the global veil: still a mint tone, muted for white text.
        assertTrue(corner.getGreen() > 120 && corner.getGreen() > corner.getRed() + 30);
        assertTrue(corner.getBlue() > 110);
        assertEquals(background.getRGB(134, 90), rendered.getRGB(134, 90));
        assertEquals(background.getRGB(128, 880), rendered.getRGB(128, 880));
        // The players row is drawn over the layered backdrop (the status badge is drawn by the renderer).
        assertFalse(Arrays.equals(
            background.getRGB(150, 750, 540, 50, null, 0, 540),
            rendered.getRGB(150, 750, 540, 50, null, 0, 540)
        ));
        assertEquals(18, PerformanceRenderer.ROW_PROGRESS_HEIGHT);
        assertEquals("更新时间 ", PerformanceRenderer.UPDATE_TIME_LABEL);
    }

    private static PerformanceSnapshot snapshot(
        double tps,
        double mspt,
        double cpu,
        double usedGiB,
        double maxGiB
    ) {
        long gib = 1024L * 1024L * 1024L;
        return new PerformanceSnapshot(
            "main", Instant.parse("2026-09-04T06:36:10Z"), tps, mspt, cpu, cpu / 2.0,
            (long) (usedGiB * gib), (long) (maxGiB * gib), 17, 50
        );
    }
}
