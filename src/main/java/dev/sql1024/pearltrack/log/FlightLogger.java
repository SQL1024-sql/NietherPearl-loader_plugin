package dev.sql1024.pearltrack.log;

import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.physics.Vec3d;
import dev.sql1024.pearltrack.track.TrackedPearl;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Writes one CSV per flight so the trajectory can be replayed afterwards.
 *
 * <p>The main thread only ever formats a line and drops it on a queue; opening,
 * writing and closing the files all happen on an async task. That queue is the
 * single point where this plugin crosses threads.
 */
public final class FlightLogger {

    private static final String HEADER =
            "tick,event,x,y,z,vx,vy,vz,hSpeed,chunkX,chunkZ,drift,pinnedChunks,travelled,note";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private record Entry(UUID pearl, String fileName, String line, boolean close) {
    }

    private final Plugin plugin;
    private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
    /** Guarded by {@code this}: the flush task and shutdown are the only writers. */
    private final Map<UUID, Writer> writers = new HashMap<>();
    private final Map<UUID, String> fileNames = new HashMap<>();

    private TrackConfig config;
    private BukkitTask flushTask;

    public FlightLogger(Plugin plugin, TrackConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setConfig(TrackConfig config) {
        this.config = config;
    }

    public void start() {
        int interval = config.flushIntervalTicks();
        this.flushTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::drain, interval, interval);
    }

    public void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        drain();
        synchronized (this) {
            for (Writer writer : writers.values()) {
                closeQuietly(writer);
            }
            writers.clear();
        }
        fileNames.clear();
    }

    /** Opens a CSV for a newly tracked pearl. Safe to call when logging is disabled. */
    public void open(TrackedPearl pearl) {
        if (!config.logEnabled()) {
            return;
        }
        String name = LocalDateTime.now().format(FILE_STAMP) + "_"
                + pearl.uuid().toString().substring(0, 8) + ".csv";
        fileNames.put(pearl.uuid(), name);
        queue.add(new Entry(pearl.uuid(), name, HEADER, false));
    }

    public void write(TrackedPearl pearl, FlightEvent event, double drift, int pinnedChunks, String note) {
        if (!config.logEnabled()) {
            return;
        }
        if (config.logToConsole() && event != FlightEvent.PREDICTED) {
            plugin.getLogger().info(console(pearl, event, drift, note));
        }
        if (event == FlightEvent.PREDICTED && !config.logPredictedTicks()) {
            return;
        }
        String name = fileNames.get(pearl.uuid());
        if (name == null) {
            return;
        }
        queue.add(new Entry(pearl.uuid(), name, csv(pearl, event, drift, pinnedChunks, note), false));
    }

    public void close(UUID pearl) {
        String name = fileNames.remove(pearl);
        if (name != null) {
            queue.add(new Entry(pearl, name, null, true));
        }
    }

    /** Absolute path of the CSV for a pearl, for {@code /pearltrack status}. */
    public String fileNameOf(UUID pearl) {
        return fileNames.get(pearl);
    }

    private String csv(TrackedPearl pearl, FlightEvent event, double drift, int pinnedChunks, String note) {
        Vec3d p = pearl.pos();
        Vec3d m = pearl.motion();
        return String.format(Locale.ROOT,
                "%d,%s,%.4f,%.4f,%.4f,%.6f,%.6f,%.6f,%.6f,%d,%d,%.6f,%d,%.2f,%s",
                pearl.tick(), event, p.x(), p.y(), p.z(), m.x(), m.y(), m.z(),
                m.horizontalLength(), p.chunkX(), p.chunkZ(), drift, pinnedChunks,
                pearl.travelled(), note == null ? "" : note.replace(',', ';'));
    }

    private String console(TrackedPearl pearl, FlightEvent event, double drift, String note) {
        Vec3d p = pearl.pos();
        StringBuilder sb = new StringBuilder()
                .append('[').append(pearl.uuid().toString(), 0, 8).append("] tick ").append(pearl.tick())
                .append(' ').append(event)
                .append(" pos=(").append(p.format()).append(')')
                .append(" chunk=(").append(p.chunkX()).append(',').append(p.chunkZ()).append(')')
                .append(String.format(Locale.ROOT, " hSpeed=%.3f", pearl.horizontalSpeed()));
        if (event == FlightEvent.REAL) {
            sb.append(String.format(Locale.ROOT, " drift=%.4f", drift));
        }
        if (note != null && !note.isEmpty()) {
            sb.append(' ').append(note);
        }
        return sb.toString();
    }

    /**
     * Runs on the async flush task, and once more from {@link #shutdown()} on the
     * main thread — synchronized because cancelling a task does not wait for a
     * run that is already in flight.
     */
    private synchronized void drain() {
        Entry entry;
        while ((entry = queue.poll()) != null) {
            if (entry.close()) {
                closeQuietly(writers.remove(entry.pearl()));
                continue;
            }
            Writer writer = writers.get(entry.pearl());
            if (writer == null) {
                writer = openWriter(entry.fileName());
                if (writer == null) {
                    continue;
                }
                writers.put(entry.pearl(), writer);
            }
            try {
                writer.write(entry.line());
                writer.write(System.lineSeparator());
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not write flight log " + entry.fileName() + ": " + ex.getMessage());
            }
        }
        for (Writer writer : writers.values()) {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // Reported on the next failing write.
            }
        }
    }

    private Writer openWriter(String fileName) {
        try {
            Path dir = plugin.getDataFolder().toPath().resolve(config.csvDirectory());
            Files.createDirectories(dir);
            return Files.newBufferedWriter(dir.resolve(fileName), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not open flight log " + fileName + ": " + ex.getMessage());
            return null;
        }
    }

    private void closeQuietly(Writer writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful left to do with a file we are done with.
        }
    }
}
