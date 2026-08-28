package dev.sql1024.pearltrack.track;

import dev.sql1024.pearltrack.config.TicketMode;
import dev.sql1024.pearltrack.config.TrackConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every chunk this plugin pins.
 *
 * <p>Chunks are reference counted so two pearls crossing the same chunk pin it
 * once and release it once. The total is capped by {@code chunks.max-forced-chunks}:
 * note that this is our own budget, not a server limit — the 256 figure people
 * quote is the maximum area of a single {@code /forceload add} command, and the
 * Bukkit API has no ceiling of its own.
 *
 * <p>All methods must be called from the main server thread.
 */
public final class ChunkTicketManager {

    /** A pinned chunk in a specific world. Chunk coordinates alone are ambiguous across worlds. */
    private record Pin(UUID world, long chunk) {
    }

    private final Plugin plugin;
    private final ForceLoadJournal journal;

    private final Map<Pin, Integer> refCount = new HashMap<>();
    private final Map<UUID, Set<Pin>> owned = new HashMap<>();

    private TrackConfig config;
    private long lastBudgetWarnTick = Long.MIN_VALUE;
    private int droppedSinceWarn;

    public ChunkTicketManager(Plugin plugin, ForceLoadJournal journal, TrackConfig config) {
        this.plugin = plugin;
        this.journal = journal;
        this.config = config;
    }

    public void setConfig(TrackConfig config) {
        this.config = config;
    }

    /**
     * Brings the set of chunks pinned for {@code pearl} in line with {@code desired}.
     * {@code desired} must be ordered nearest-future-first: when the budget is
     * exhausted the tail is dropped, so the chunks the pearl reaches soonest win.
     *
     * @return how many of the requested chunks could not be pinned
     */
    public int apply(World world, UUID pearl, LinkedHashSet<Long> desired, long serverTick) {
        UUID worldUid = world.getUID();
        Set<Pin> current = owned.computeIfAbsent(pearl, k -> new HashSet<>());

        // Release first: chunks the pearl has already flown past free up budget
        // for the ones it is about to reach.
        List<Pin> toRelease = new ArrayList<>();
        for (Pin pin : current) {
            if (!worldUid.equals(pin.world()) || !desired.contains(pin.chunk())) {
                toRelease.add(pin);
            }
        }
        for (Pin pin : toRelease) {
            release(pearl, pin);
        }

        List<Pin> toAcquire = new ArrayList<>();
        for (long chunk : desired) {
            Pin pin = new Pin(worldUid, chunk);
            if (!current.contains(pin)) {
                toAcquire.add(pin);
            }
        }

        int budgetLeft = Math.max(0, config.maxForcedChunks() - refCount.size());
        int dropped = 0;
        if (toAcquire.size() > budgetLeft) {
            dropped = toAcquire.size() - budgetLeft;
            toAcquire = toAcquire.subList(0, budgetLeft);
        }

        for (Pin pin : toAcquire) {
            acquire(world, pearl, pin);
        }

        if (dropped > 0) {
            warnBudget(dropped, serverTick);
        }
        return dropped;
    }

    private void warnBudget(int dropped, long serverTick) {
        droppedSinceWarn = Math.max(droppedSinceWarn, dropped);
        if (serverTick - lastBudgetWarnTick < config.warnThrottleTicks()) {
            return;
        }
        lastBudgetWarnTick = serverTick;
        plugin.getLogger().warning("chunk budget of " + config.maxForcedChunks()
                + " is full; dropped up to " + droppedSinceWarn
                + " of the furthest predicted chunks. Lower tracking.lookahead-ticks or"
                + " tracking.forceload-radius, or raise chunks.max-forced-chunks.");
        droppedSinceWarn = 0;
    }

    private void acquire(World world, UUID pearl, Pin pin) {
        int cx = ChunkKeys.x(pin.chunk());
        int cz = ChunkKeys.z(pin.chunk());
        if (refCount.merge(pin, 1, Integer::sum) == 1) {
            if (config.ticketMode() == TicketMode.PLUGIN_TICKET) {
                world.addPluginChunkTicket(cx, cz, plugin);
            } else {
                journal.record(pin.world(), cx, cz);
                world.setChunkForceLoaded(cx, cz, true);
            }
            if (config.urgentLoad() && !world.isChunkLoaded(cx, cz)) {
                // A ticket alone leaves the chunk in the ordinary load queue, which can
                // take several ticks when the terrain still has to be generated — by
                // which time the pearl has flown past. Ask for it urgently instead.
                world.getChunkAtAsyncUrgently(cx, cz).exceptionally(t -> null);
            }
        }
        owned.computeIfAbsent(pearl, k -> new HashSet<>()).add(pin);
    }

    private void release(UUID pearl, Pin pin) {
        Set<Pin> set = owned.get(pearl);
        if (set == null || !set.remove(pin)) {
            return;
        }
        Integer remaining = refCount.merge(pin, -1, Integer::sum);
        if (remaining != null && remaining <= 0) {
            refCount.remove(pin);
            unpin(pin);
        }
    }

    private void unpin(Pin pin) {
        World world = Bukkit.getWorld(pin.world());
        if (world == null) {
            return;
        }
        int cx = ChunkKeys.x(pin.chunk());
        int cz = ChunkKeys.z(pin.chunk());
        if (config.ticketMode() == TicketMode.PLUGIN_TICKET) {
            world.removePluginChunkTicket(cx, cz, plugin);
        } else {
            world.setChunkForceLoaded(cx, cz, false);
        }
    }

    public void releaseAll(UUID pearl) {
        Set<Pin> set = owned.get(pearl);
        if (set == null) {
            return;
        }
        for (Pin pin : new ArrayList<>(set)) {
            release(pearl, pin);
        }
        owned.remove(pearl);
    }

    /** Drops every ticket this plugin holds. Called on disable. */
    public void releaseEverything() {
        if (config.ticketMode() == TicketMode.PLUGIN_TICKET) {
            for (World world : Bukkit.getWorlds()) {
                world.removePluginChunkTickets(plugin);
            }
        } else {
            for (Pin pin : new ArrayList<>(refCount.keySet())) {
                unpin(pin);
            }
        }
        refCount.clear();
        owned.clear();
        journal.discard();
    }

    public int globalCount() {
        return refCount.size();
    }

    public int countFor(UUID pearl) {
        Set<Pin> set = owned.get(pearl);
        return set == null ? 0 : set.size();
    }
}
