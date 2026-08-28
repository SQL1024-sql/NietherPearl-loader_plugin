package dev.sql1024.pearltrack;

import dev.sql1024.pearltrack.command.PearlTrackCommand;
import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.listener.PearlLaunchListener;
import dev.sql1024.pearltrack.log.FlightLogger;
import dev.sql1024.pearltrack.track.ChunkTicketManager;
import dev.sql1024.pearltrack.track.EndReason;
import dev.sql1024.pearltrack.track.ForceLoadJournal;
import dev.sql1024.pearltrack.track.PearlTracker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Keeps an ender pearl that is moving hundreds of blocks per tick inside
 * ticking chunks, by predicting where it will be next tick and pinning that
 * chunk before the entity moves.
 */
public final class PearlTrackPlugin extends JavaPlugin {

    private TrackConfig config;
    private ForceLoadJournal journal;
    private ChunkTicketManager tickets;
    private FlightLogger flightLogger;
    private PearlTracker tracker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TrackConfig.load(getConfig(), getLogger());

        this.journal = new ForceLoadJournal(
                getDataFolder().toPath().resolve("forced-chunks.journal"), getLogger());
        this.journal.recoverPreviousRun();

        this.tickets = new ChunkTicketManager(this, journal, config);
        this.flightLogger = new FlightLogger(this, config);
        this.flightLogger.start();
        this.tracker = new PearlTracker(this, tickets, flightLogger, config);

        getServer().getPluginManager().registerEvents(
                new PearlLaunchListener(tracker, this::config), this);

        // Period 1: Bukkit's scheduler heartbeat runs before the worlds tick, so
        // each run pins chunks for movement that happens later in the same tick.
        getServer().getScheduler().runTaskTimer(this, tracker, 1L, 1L);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        "pearltrack",
                        "Track a high-speed ender pearl through unloaded chunks",
                        List.of("pt"),
                        new PearlTrackCommand(tracker, tickets, flightLogger, this::config, this::reload)));

        getLogger().info("Ready: drag=" + config.drag() + " gravity=" + config.gravity()
                + " order=" + config.order() + " mode=" + config.ticketMode()
                + " budget=" + config.maxForcedChunks() + " chunks");
    }

    @Override
    public void onDisable() {
        if (tracker != null) {
            tracker.stopAll(EndReason.PLUGIN_DISABLED);
        }
        if (tickets != null) {
            tickets.releaseEverything();
        }
        if (flightLogger != null) {
            flightLogger.shutdown();
        }
    }

    public TrackConfig config() {
        return config;
    }

    /** Re-reads config.yml and hands the new snapshot to every component. */
    public void reload() {
        reloadConfig();
        this.config = TrackConfig.load(getConfig(), getLogger());
        tickets.setConfig(config);
        flightLogger.setConfig(config);
        tracker.setConfig(config);
    }
}
