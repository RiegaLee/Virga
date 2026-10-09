package io.github.kloping.qqbot.utils;

import io.github.kloping.spt.interfaces.Logger;

import java.awt.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author github.kloping
 */
public class LoggerImpl implements Logger {
    public static final LoggerImpl INSTANCE = new LoggerImpl();

    /**
     * Optional host-application logger. The Maven submodule remains standalone
     * when no sink is configured, while HuHoBot clients can route SDK logs to
     * their native logger implementation.
     */
    private static volatile LogSink logSink;

    public interface LogSink {
        int ERROR_LEVEL = -1;
        int DEBUG_LEVEL = 2;

        void log(String message, int level);
    }

    public static void setLogSink(LogSink sink) {
        logSink = sink;
    }

    public static void clearLogSink() {
        logSink = null;
    }

    /**
     * Adds the same true-color ANSI sequence that Jansi used to generate,
     * without loading Jansi from the host application's class loader.
     *
     * Server platforms commonly bundle an older Jansi version. Calling a
     * method introduced by Jansi 2.x from a plugin then causes
     * NoSuchMethodError during startup or shutdown. Keeping this tiny formatter
     * in the SDK avoids that classpath conflict while preserving standalone
     * console colors. When a host logger is configured, return plain text so
     * ANSI escape sequences are not passed into the host logger.
     */
    public static String colorize(Object value, Color color) {
        String text = String.valueOf(value);
        if (logSink != null || color == null) return text;
        return String.format("\u001B[38;2;%d;%d;%dm%s\u001B[m",
                color.getRed(), color.getGreen(), color.getBlue(), text);
    }

    public static final Color NORMAL_LOW_COLOR = new Color(116, 117, 116, 224);
    public static final Color NORMAL_COLOR = new Color(202, 206, 199, 247);
    public static final Color INFO_COLOR = new Color(24, 220, 85, 247);
    public static final Color DEBUG_COLOR = new Color(234, 213, 103, 247);
    public static final Color ERROR_COLOR = new Color(224, 17, 106, 247);

    private int logLevel = 0;

    private SimpleDateFormat df = new SimpleDateFormat("MM/dd-HH:mm:ss:SSS");
    private String prefix = "[github.kloping.ST]";

    /**
     * 区分每日 已达到隔日切换日志文件的效果
     */
    public DateFormat difference = new SimpleDateFormat("dd");
    /**
     * 日志文件格式
     */
    public DateFormat dfn = new SimpleDateFormat("/yyyy-MM-dd");
    /**
     * 日志文件路径
     */
    public String logFileDir = "./logs/%s.log";

    private File file;

    private String updd = null;

    public synchronized File getFile() {
        String dd = difference.format(new Date());
        if (updd == null) updd = dd;
        else if (!updd.equals(dd)) {
            step0();
            setWriter(file);
        }
        if (file == null) step0();
        updd = dd;
        return file;
    }

    private void step0() {
        file = new File(String.format(logFileDir, dfn.format(new Date())));
    }

    /**
     * 必须设置为 %s 以替换 日期的字符串格式 默认 "./logs/%s.log"
     * 设置为 null 时不输出日志文件
     *
     * @param path
     */
    @Override
    public void setOutFile(String path) {
        this.logFileDir = path;
    }

    @Override
    public void setFormat(SimpleDateFormat format) {
        df = format;
    }

    @Override
    public void Log(String mess, Integer level) {
        String log = null;
        String out = null;
        try {
            log = "[" + df.format(new Date()) + "]" + "=>" + mess;
            switch (level) {
                case 0:
                    log = "[Normal]" + log;
                    break;
                case 1:
                    log = "[Info]  " + log;
                    break;
                case 2:
                    log = "[Debug] " + log;
                    break;
                case -1:
                    log = "[Error] " + log;
                    break;
                default:
            }
            log = prefix + log;
            out = null;
            if (level == 0) {
                out = colorize(log, NORMAL_COLOR);
            } else if (level == 1) {
                out = colorize(log, INFO_COLOR);
            } else if (level == 2) {
                out = colorize(log, DEBUG_COLOR);
            } else if (level == -1) {
                out = colorize(log, ERROR_COLOR);
            }
        } catch (Exception e) {
            if (level != -1 && level < logLevel) {
            } else e.printStackTrace();
        }
        if (logFileDir != null) {
            try {
                BufferedWriter writer = getWriter();
                if (writer != null) {
                    try {
                        log = log.replaceAll("\\\u001B\\[38\\;2\\;[0-9]+\\;[0-9]+\\;[0-9]+m", "")
                                .replaceAll("\\\u001B\\[m", "");
                    } catch (Exception e) {
                    }
                    writer.write(log);
                    writer.newLine();
                    writer.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (level != -1 && level < logLevel) return;
        LogSink sink = logSink;
        if (sink != null) {
            // Let the host logger add its own timestamp/prefix. The formatted
            // SDK line above is still retained in the log file.
            sink.log(mess, level);
        } else {
            System.out.println(out);
        }
    }

    private BufferedWriter writer = null;

    private BufferedWriter getWriter() {
        File f0 = getFile();
        if (f0 != null && writer == null) {
            setWriter(f0);
        }
        return writer;
    }

    private void setWriter(File f0) {
        try {
            if (writer != null) writer.close();
            if (!f0.exists()) f0.getParentFile().mkdirs();
            writer = new BufferedWriter(new FileWriter(f0, true));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int setLogLevel(int level) {
        return logLevel = level;
    }

    @Override
    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}
