package cn.huohuas001.virga.forge;

import cn.huohuas001.virga.core.VirgaLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Routes Virga's own logger and the java.util.logging used by ported services to the server log. */
final class ForgeLogging {
    static final Logger LOGGER = LoggerFactory.getLogger("Virga");

    private ForgeLogging() {}

    static VirgaLogger core() {
        return new VirgaLogger() {
            @Override
            public void info(String message) {
                LOGGER.info(message);
            }

            @Override
            public void warning(String message) {
                LOGGER.warn(message);
            }

            @Override
            public void error(String message, Throwable error) {
                if (error == null) LOGGER.error(message);
                else LOGGER.error(message, error);
            }
        };
    }

    static java.util.logging.Logger jul() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("cn.huohuas001.virga");
        logger.setUseParentHandlers(false);
        for (Handler handler : logger.getHandlers()) logger.removeHandler(handler);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null) return;
                String message = record.getMessage();
                Throwable thrown = record.getThrown();
                int level = record.getLevel().intValue();
                if (level >= Level.SEVERE.intValue()) LOGGER.error(message, thrown);
                else if (level >= Level.WARNING.intValue()) LOGGER.warn(message, thrown);
                else if (level >= Level.INFO.intValue()) LOGGER.info(message, thrown);
                else LOGGER.debug(message, thrown);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return logger;
    }
}
