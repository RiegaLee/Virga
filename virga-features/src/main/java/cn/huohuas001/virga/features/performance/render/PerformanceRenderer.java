package cn.huohuas001.virga.features.performance.render;

import cn.huohuas001.virga.features.performance.model.MetricStatus;
import cn.huohuas001.virga.features.performance.model.PerformanceHealth;
import cn.huohuas001.virga.features.performance.model.PerformanceSnapshot;
import cn.huohuas001.virga.features.performance.model.PerformanceThresholds;
import cn.huohuas001.virga.features.render.BackdropLibrary;
import cn.huohuas001.virga.features.render.BundledFonts;
import cn.huohuas001.virga.features.render.Glass;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Draws live server metrics over the layered 1792x1008 backdrop: header with the status badge,
 * TPS/MSPT numbers, a card of rows with bars and the diagnosis bar, all as white text on dark glass
 * (geometry fixed by the brand asset generator).
 */
public final class PerformanceRenderer {
    public static final int WIDTH = 1792;
    public static final int HEIGHT = 1008;


    static final int ROW_PROGRESS_HEIGHT = 18;
    static final String UPDATE_TIME_LABEL = "更新时间 ";

    // White text over the dark glass cards of the layered backdrop.
    private static final Color TEXT_PRIMARY = new Color(255, 255, 255);
    private static final Color TEXT_SECONDARY = new Color(255, 255, 255, 190);
    private static final Color ACCENT = new Color(170, 156, 250);
    private static final Color ACCENT_START = new Color(120, 226, 192);
    private static final Color ACCENT_LIGHT = new Color(214, 206, 255);
    private static final Color GOOD = new Color(150, 240, 200);
    private static final Color WARNING = new Color(255, 190, 110);
    private static final Color WARNING_TEXT = new Color(255, 205, 140);
    private static final Color CRITICAL = new Color(255, 120, 150);
    private static final Color CRITICAL_TEXT = new Color(255, 160, 182);
    private static final Color UNAVAILABLE = new Color(255, 255, 255, 120);

    private static final int BADGE_X = 122;
    private static final int BADGE_Y = 86;
    private static final java.util.Map<MetricStatus, BufferedImage> BADGES = loadBadges();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final BackdropLibrary backdrops;
    private final String fontFamily;
    private final PerformanceThresholds thresholds;
    private final String tpsLabel;

    public PerformanceRenderer(String configuredFont) throws IOException {
        this(configuredFont, PerformanceThresholds.defaults());
    }

    public PerformanceRenderer(String configuredFont, PerformanceThresholds thresholds) throws IOException {
        this(configuredFont, thresholds, "TPS");
    }

    /** @param tpsLabel caption for the TPS metric; blank means "TPS". */
    public PerformanceRenderer(String configuredFont, PerformanceThresholds thresholds, String tpsLabel) throws IOException {
        this(BackdropLibrary.bundled(), configuredFont, thresholds, tpsLabel);
    }

    /** The backdrop (glass cards and tracks) comes from the layered [BackdropLibrary]. */
    public PerformanceRenderer(BackdropLibrary backdrops, String configuredFont, PerformanceThresholds thresholds,
                               String tpsLabel) {
        this.backdrops = backdrops;
        this.fontFamily = chooseFont(configuredFont);
        this.thresholds = thresholds == null ? PerformanceThresholds.defaults() : thresholds;
        this.tpsLabel = tpsLabel == null || tpsLabel.isBlank() ? "TPS" : tpsLabel;
    }

    public byte[] render(PerformanceSnapshot snapshot) throws IOException {
        BufferedImage image = renderImage(snapshot);
        ByteArrayOutputStream output = new ByteArrayOutputStream(1024 * 1024);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer is unavailable");
        }
        return output.toByteArray();
    }

    public BufferedImage renderImage(PerformanceSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");

        BufferedImage result = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            configure(graphics);
            graphics.drawImage(backdrops.get(BackdropLibrary.Surface.STATUS), 0, 0, null);
            drawBadge(graphics, snapshot);
            drawHeader(graphics, snapshot);
            drawOverallHealth(graphics, snapshot);
            drawWorldSmoothness(graphics, snapshot);
            drawCpuCard(graphics, snapshot);
            drawMemoryCard(graphics, snapshot);
            drawPlayersCard(graphics, snapshot);
            drawDiagnosis(graphics, snapshot);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** The aurora badge's ring follows the server: green, amber, rose, or grey while sampling. */
    private void drawBadge(Graphics2D graphics, PerformanceSnapshot snapshot) {
        BufferedImage badge = BADGES.get(PerformanceHealth.overall(snapshot, thresholds));
        if (badge != null) graphics.drawImage(badge, BADGE_X, BADGE_Y, null);
    }

    private static java.util.Map<MetricStatus, BufferedImage> loadBadges() {
        java.util.Map<MetricStatus, BufferedImage> badges = new java.util.EnumMap<>(MetricStatus.class);
        String[][] moods = {{"NORMAL", "normal"}, {"WARNING", "warning"}, {"CRITICAL", "critical"}, {"UNAVAILABLE", "unavailable"}};
        for (String[] mood : moods) {
            String resource = "/backdrops/status/badge-" + mood[1] + ".png";
            try (java.io.InputStream input = PerformanceRenderer.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("缺少表情徽章 " + resource);
                badges.put(MetricStatus.valueOf(mood[0]), ImageIO.read(input));
            } catch (IOException error) {
                throw new IllegalStateException("读取表情徽章失败 " + resource, error);
            }
        }
        return badges;
    }

    private void drawHeader(Graphics2D graphics, PerformanceSnapshot snapshot) {
        graphics.setFont(font(Font.BOLD, 40));
        graphics.setColor(TEXT_PRIMARY);
        Glass.text(graphics, "服务器状态", 244, 132);
        graphics.setFont(font(Font.PLAIN, 24));
        graphics.setColor(TEXT_SECONDARY);
        Glass.text(graphics, UPDATE_TIME_LABEL + TIME_FORMAT.format(snapshot.getCapturedAt()), 246, 174);
    }

    private void drawOverallHealth(Graphics2D graphics, PerformanceSnapshot snapshot) {
        MetricStatus overall = PerformanceHealth.overall(snapshot, thresholds);
        graphics.setFont(font(Font.BOLD, 38));
        drawRight(graphics, statusTitle(overall), 1656, 132, statusTextColor(overall));

        int normalCount = normalMetricCount(snapshot);
        String countText = normalCount == 0 ? "指标正在采样" : normalCount + " 项指标正常";
        graphics.setFont(font(Font.PLAIN, 24));
        drawRight(graphics, countText, 1656, 174, TEXT_SECONDARY);
    }

    private void drawWorldSmoothness(Graphics2D graphics, PerformanceSnapshot snapshot) {
        MetricStatus tpsStatus = PerformanceHealth.tps(snapshot, thresholds);
        MetricStatus msptStatus = PerformanceHealth.mspt(snapshot, thresholds);
        // Tick interval (1000 / TPS) is not processing time; never replace measured MSPT.
        double displayedMspt = snapshot.getMspt();
        boolean msptUnavailable = Double.isNaN(displayedMspt);

        drawPrimaryMetric(graphics, tpsLabel, decimal(snapshot.getTps(), 1), "", 140, valueColor(tpsStatus));
        drawPrimaryMetric(graphics, "MSPT", msptUnavailable ? "不可用" : decimal(displayedMspt, 1),
            msptUnavailable ? "" : "ms", 956, valueColor(msptStatus));
    }

    private void drawCpuCard(Graphics2D graphics, PerformanceSnapshot snapshot) {
        MetricStatus systemStatus = PerformanceHealth.systemCpu(snapshot, thresholds);
        MetricStatus processStatus = PerformanceHealth.processCpu(snapshot, thresholds);

        drawMetricRow(graphics, "系统 CPU", percent(snapshot.getSystemCpuPercent()), 584,
            percentRatio(snapshot.getSystemCpuPercent()), statusColor(systemStatus));
        drawMetricRow(graphics, "进程 CPU", percent(snapshot.getProcessCpuPercent()), 654,
            percentRatio(snapshot.getProcessCpuPercent()), statusColor(processStatus));
    }

    private void drawMemoryCard(Graphics2D graphics, PerformanceSnapshot snapshot) {
        MetricStatus status = PerformanceHealth.memory(snapshot, thresholds);
        double ratio = memoryRatio(snapshot);
        drawMetricRow(graphics, "运行内存", Double.isNaN(ratio) ? "--" : Math.round(ratio * 100.0) + "%", 724,
            ratio, statusColor(status));
    }

    private void drawPlayersCard(Graphics2D graphics, PerformanceSnapshot snapshot) {
        graphics.setFont(font(Font.PLAIN, 30));
        graphics.setColor(TEXT_PRIMARY);
        Glass.text(graphics, "在线玩家", 140, 796);

        String onlineText = Integer.toString(snapshot.getOnlinePlayers());
        graphics.setFont(font(Font.BOLD, 44));
        graphics.setColor(snapshot.getMaxPlayers() > 0 ? ACCENT_LIGHT : UNAVAILABLE);
        Glass.text(graphics, onlineText, 420, 800);
        int countWidth = Glass.width(graphics, onlineText);
        graphics.setFont(font(Font.BOLD, 26));
        graphics.setColor(TEXT_SECONDARY);
        String maximum = snapshot.getMaxPlayers() > 0 ? Integer.toString(snapshot.getMaxPlayers()) : "--";
        Glass.text(graphics, " / " + maximum + " 人", 420 + countWidth + 6, 798);
    }

    private void drawDiagnosis(Graphics2D graphics, PerformanceSnapshot snapshot) {
        MetricStatus overall = PerformanceHealth.overall(snapshot, thresholds);
        graphics.setFont(font(Font.BOLD, 24));
        graphics.setColor(TEXT_PRIMARY);
        drawCentered(graphics, "运行诊断", 197, 909);

        graphics.setFont(font(Font.PLAIN, 28));
        graphics.setColor(TEXT_PRIMARY);
        Glass.text(graphics, diagnosis(snapshot, overall), 300, 911);
    }

    /** One big card: label and a large value (+ unit); the value's color carries the state. */
    private void drawPrimaryMetric(Graphics2D graphics, String label, String value, String unit,
                                   int textX, Color stateColor) {
        graphics.setFont(font(Font.BOLD, 30));
        graphics.setColor(TEXT_SECONDARY);
        Glass.text(graphics, label, textX, 300);

        graphics.setFont(font(Font.BOLD, 112));
        graphics.setColor(stateColor);
        Glass.text(graphics, value, textX - 4, 438);
        if (!unit.isEmpty()) {
            int valueWidth = Glass.width(graphics, value);
            graphics.setFont(font(Font.BOLD, 34));
            graphics.setColor(TEXT_SECONDARY);
            Glass.text(graphics, unit, textX + valueWidth + 14, 434);
        }
    }

    private void drawMetricRow(Graphics2D graphics, String label, String value, int baseline,
                               double ratio, Color accent) {
        graphics.setFont(font(Font.PLAIN, 30));
        graphics.setColor(TEXT_PRIMARY);
        Glass.text(graphics, label, 140, baseline);
        graphics.setFont(font(Font.BOLD, 32));
        drawRight(graphics, value, 1652, baseline + 2, value.equals("--") ? UNAVAILABLE : TEXT_PRIMARY);
        drawProgress(graphics, 420, baseline - 20, 1060, ROW_PROGRESS_HEIGHT, ratio, accent);
    }

    private void drawProgress(Graphics2D graphics, int x, int y, int width, int height, double ratio, Color accent) {
        if (Double.isNaN(ratio)) return;
        double safeRatio = Math.max(0.0, Math.min(1.0, ratio));
        int filled = Math.max(height, (int) Math.round(width * safeRatio));
        // Normal bars carry the aurora gradient (mint to lavender); warnings stay a flat signal color.
        graphics.setPaint(accent == ACCENT
            ? new java.awt.GradientPaint(x, y, ACCENT_START, x + width, y, ACCENT)
            : accent);
        graphics.fillRoundRect(x, y, filled, height, height, height);
    }

    private int normalMetricCount(PerformanceSnapshot snapshot) {
        MetricStatus[] statuses = {
            PerformanceHealth.tps(snapshot, thresholds),
            PerformanceHealth.mspt(snapshot, thresholds),
            PerformanceHealth.systemCpu(snapshot, thresholds),
            PerformanceHealth.processCpu(snapshot, thresholds),
            PerformanceHealth.memory(snapshot, thresholds),
            snapshot.getMaxPlayers() > 0 ? MetricStatus.NORMAL : MetricStatus.UNAVAILABLE
        };
        int count = 0;
        for (MetricStatus status : statuses) if (status == MetricStatus.NORMAL) count++;
        return count;
    }

    /** The configured system font when set, otherwise the bundled MiSans. */
    private Font font(int style, int size) {
        return fontFamily == null ? BundledFonts.font(style, size) : new Font(fontFamily, style, size);
    }

    private static String statusTitle(MetricStatus status) {
        switch (status) {
            case WARNING: return "负载偏高";
            case CRITICAL: return "负载过高";
            case UNAVAILABLE: return "采样中";
            default: return "运行良好";
        }
    }

    private String diagnosis(PerformanceSnapshot snapshot, MetricStatus status) {
        if (status != MetricStatus.NORMAL) return PerformanceHealth.diagnosis(snapshot, thresholds);
        if (Double.isNaN(snapshot.getMspt())) return "运行平稳；当前环境无法读取 MSPT，仅按 TPS 判断";
        return "运行平稳";
    }

    private static Color statusColor(MetricStatus status) {
        switch (status) {
            case WARNING: return WARNING;
            case CRITICAL: return CRITICAL;
            case UNAVAILABLE: return UNAVAILABLE;
            default: return ACCENT;
        }
    }

    /** Big numbers stay white while healthy and take the warning colors otherwise. */
    private static Color valueColor(MetricStatus status) {
        switch (status) {
            case WARNING: return WARNING_TEXT;
            case CRITICAL: return CRITICAL_TEXT;
            case UNAVAILABLE: return TEXT_SECONDARY;
            default: return TEXT_PRIMARY;
        }
    }

    private static Color statusTextColor(MetricStatus status) {
        switch (status) {
            case WARNING: return WARNING_TEXT;
            case CRITICAL: return CRITICAL_TEXT;
            case UNAVAILABLE: return TEXT_SECONDARY;
            default: return GOOD;
        }
    }

    private static String decimal(double value, int decimals) {
        if (Double.isNaN(value)) return "--";
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "--" : Math.round(value) + "%";
    }

    private static double percentRatio(double value) {
        return Double.isNaN(value) ? Double.NaN : value / 100.0;
    }

    private static double memoryRatio(PerformanceSnapshot snapshot) {
        if (snapshot.getMaxMemoryBytes() <= 0L) return Double.NaN;
        return (double) snapshot.getUsedMemoryBytes() / snapshot.getMaxMemoryBytes();
    }


    private static void drawCentered(Graphics2D graphics, String text, int centerX, int baseline) {
        Glass.text(graphics, text, centerX - Glass.width(graphics, text) / 2, baseline);
    }

    private static void drawRight(Graphics2D graphics, String text, int right, int baseline, Color color) {
        graphics.setColor(color);
        Glass.text(graphics, text, right - Glass.width(graphics, text), baseline);
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static String chooseFont(String configuredFont) {
        Set<String> available = new HashSet<String>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()
        ));
        if (configuredFont != null && !configuredFont.trim().isEmpty() && available.contains(configuredFont.trim())) {
            return configuredFont.trim();
        }
        // No font configured: the bundled MiSans (null), the same on every system.
        if (BundledFonts.available()) return null;
        String[] candidates = {
            "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "Source Han Sans SC", "SansSerif"
        };
        for (String candidate : candidates) {
            if (available.contains(candidate)) return candidate;
        }
        return Font.SANS_SERIF;
    }
}
