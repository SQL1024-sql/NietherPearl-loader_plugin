package dev.sql1024.pearltrack.track;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Crash safety net for {@link dev.sql1024.pearltrack.config.TicketMode#FORCE_LOADED}.
 *
 * <p>{@code setChunkForceLoaded} is persisted with the world, so a server that
 * dies mid-flight would leave a trail of permanently pinned chunks behind. Every
 * chunk we pin is appended here first; a clean shutdown deletes the file, and a
 * surviving file on startup means the previous run crashed and its chunks get
 * un-pinned. Unused in PLUGIN_TICKET mode, which cannot leak.
 */
public final class ForceLoadJournal {

    private final Path file;
    private final Logger logger;
    private BufferedWriter writer;

    public ForceLoadJournal(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Un-pins anything a previous, crashed run left behind. Call before tracking starts. */
    public void recoverPreviousRun() {
        if (!Files.exists(file)) {
            return;
        }
        int recovered = 0;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                String[] parts = line.split(",");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    World world = Bukkit.getWorld(UUID.fromString(parts[0]));
                    if (world == null) {
                        continue;
                    }
                    world.setChunkForceLoaded(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), false);
                    recovered++;
                } catch (IllegalArgumentException ignored) {
                    // Malformed line from a truncated write; nothing to undo.
                }
            }
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            logger.warning("Could not replay the force-load journal: " + ex.getMessage());
            return;
        }
        if (recovered > 0) {
            logger.warning("Released " + recovered + " chunk(s) left force-loaded by a previous unclean shutdown");
        }
    }

    public void record(UUID worldUid, int chunkX, int chunkZ) {
        try {
            if (writer == null) {
                Files.createDirectories(file.getParent());
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            writer.write(worldUid + "," + chunkX + "," + chunkZ);
            writer.newLine();
            // flush(), not fsync: one small buffered write per tick, but the data is
            // in the page cache so it survives a JVM crash, which is the case we care about.
            writer.flush();
        } catch (IOException ex) {
            logger.warning("Could not append to the force-load journal: " + ex.getMessage());
        }
    }

    /** Clean shutdown: everything has been released, so the journal is no longer needed. */
    public void discard() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            logger.warning("Could not discard the force-load journal: " + ex.getMessage());
        }
    }
}
