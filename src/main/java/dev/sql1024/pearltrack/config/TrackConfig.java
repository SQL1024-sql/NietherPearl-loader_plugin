package dev.sql1024.pearltrack.config;

import dev.sql1024.pearltrack.physics.PhysicsOrder;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Immutable snapshot of config.yml. Nothing in the tracking code reads the
 * FileConfiguration directly, so {@code /pearltrack reload} is just a matter of
 * swapping one of these in.
 */
public record TrackConfig(
        double drag,
        double gravity,
        PhysicsOrder order,

        boolean autoTrackAll,
        double onlyIfSpeedAbove,
        int maxConcurrent,
        int maxTicks,
        int lookaheadTicks,
        int forceloadRadius,
        double convergenceThreshold,
        int keepTicksAfterConvergence,
        int keepRadiusAfterConvergence,
        boolean disableCollision,
        double coordinateLimit,

        TicketMode ticketMode,
        int maxForcedChunks,
        int warnThrottleTicks,
        boolean urgentLoad,

        boolean logEnabled,
        boolean logToConsole,
        String csvDirectory,
        boolean logPredictedTicks,
        int flushIntervalTicks
) {

    public static TrackConfig load(FileConfiguration cfg, Logger logger) {
        double drag = clamp(cfg.getDouble("physics.drag", 0.99D), 1.0E-6D, 0.999999D, "physics.drag", logger);
        double gravity = Math.max(0.0D, cfg.getDouble("physics.gravity", 0.03D));
        PhysicsOrder order = parseOrder(cfg.getString("physics.order", "VANILLA"), logger);

        boolean autoTrackAll = cfg.getBoolean("tracking.auto-track-all-pearls", true);
        double speedGate = Math.max(0.0D, cfg.getDouble("tracking.only-if-speed-above", 4.0D));
        int maxConcurrent = Math.max(1, cfg.getInt("tracking.max-concurrent", 4));
        int maxTicks = Math.max(1, cfg.getInt("tracking.max-ticks", 6000));
        int lookahead = Math.max(1, cfg.getInt("tracking.lookahead-ticks", 5));
        int radius = Math.max(0, cfg.getInt("tracking.forceload-radius", 0));
        double convergence = Math.max(0.0D, cfg.getDouble("tracking.convergence-threshold", 16.0D));
        int keepTicks = Math.max(0, cfg.getInt("tracking.keep-ticks-after-convergence", 100));
        int keepRadius = Math.max(0, cfg.getInt("tracking.keep-radius-after-convergence", 2));
        boolean noCollision = cfg.getBoolean("tracking.disable-collision", false);
        double coordLimit = Math.max(1.0D, cfg.getDouble("tracking.coordinate-limit", 2.9999984E7D));

        TicketMode mode = parseMode(cfg.getString("chunks.mode", "PLUGIN_TICKET"), logger);
        int maxForced = Math.max(1, cfg.getInt("chunks.max-forced-chunks", 256));
        int warnThrottle = Math.max(1, cfg.getInt("chunks.warn-throttle-ticks", 100));
        boolean urgent = cfg.getBoolean("chunks.urgent-load", true);

        boolean logEnabled = cfg.getBoolean("logging.enabled", true);
        boolean toConsole = cfg.getBoolean("logging.to-console", true);
        String dir = cfg.getString("logging.csv-directory", "flights");
        boolean logPredicted = cfg.getBoolean("logging.log-predicted-ticks", true);
        int flush = Math.max(1, cfg.getInt("logging.flush-interval-ticks", 20));

        return new TrackConfig(drag, gravity, order,
                autoTrackAll, speedGate, maxConcurrent, maxTicks, lookahead, radius,
                convergence, keepTicks, keepRadius, noCollision, coordLimit,
                mode, maxForced, warnThrottle, urgent,
                logEnabled, toConsole, dir == null ? "flights" : dir, logPredicted, flush);
    }

    private static double clamp(double value, double min, double max, String path, Logger logger) {
        if (value < min || value > max) {
            double clamped = Math.min(max, Math.max(min, value));
            logger.warning(path + " = " + value + " is out of range, clamped to " + clamped);
            return clamped;
        }
        return value;
    }

    private static PhysicsOrder parseOrder(String raw, Logger logger) {
        try {
            return PhysicsOrder.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            logger.warning("Unknown physics.order '" + raw + "', falling back to VANILLA");
            return PhysicsOrder.VANILLA;
        }
    }

    private static TicketMode parseMode(String raw, Logger logger) {
        try {
            return TicketMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException ex) {
            logger.warning("Unknown chunks.mode '" + raw + "', falling back to PLUGIN_TICKET");
            return TicketMode.PLUGIN_TICKET;
        }
    }
}
