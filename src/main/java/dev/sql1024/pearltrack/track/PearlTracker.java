package dev.sql1024.pearltrack.track;

import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.log.FlightEvent;
import dev.sql1024.pearltrack.log.FlightLogger;
import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.physics.Step;
import dev.sql1024.pearltrack.physics.Vec3d;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Locale;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-tick loop: correct against reality, decide whether the flight has
 * converged, predict where the pearl is going and pin those chunks before the
 * entities tick.
 *
 * <p>Bukkit runs the scheduler heartbeat earlier in the server tick than the
 * world/entity tick, so a chunk pinned here is pinned <em>before</em> the pearl
 * moves into it this same tick. That ordering is what makes the whole approach
 * work; do not move this onto an async task.
 */
public final class PearlTracker implements Runnable {

    private final Plugin plugin;
    private final ChunkTicketManager tickets;
    private final FlightLogger flightLogger;
    private final NamespacedKey trackedKey;
    private final NamespacedKey launchTickKey;

    private final Map<UUID, TrackedPearl> tracked = new LinkedHashMap<>();
    /** Pearls that were too slow at launch and are being re-measured for a few ticks. */
    private final Map<UUID, Candidate> candidates = new LinkedHashMap<>();
    /** Players who ran {@code /pearltrack next} and have not thrown a pearl yet. */
    private final Set<UUID> pendingManual = new HashSet<>();

    private TrackConfig config;
    private long serverTick;

    public PearlTracker(Plugin plugin, ChunkTicketManager tickets, FlightLogger flightLogger,
                        TrackConfig config) {
        this.plugin = plugin;
        this.tickets = tickets;
        this.flightLogger = flightLogger;
        this.config = config;
        this.trackedKey = new NamespacedKey(plugin, "tracked");
        this.launchTickKey = new NamespacedKey(plugin, "launch_tick");
    }

    /** A pearl whose speed may still be raised after {@code ProjectileLaunchEvent} has fired. */
    private record Candidate(UUID world, String shooter, long expiresAtTick) {
    }

    public void setConfig(TrackConfig config) {
        this.config = config;
    }

    public NamespacedKey trackedKey() {
        return trackedKey;
    }

    public long serverTick() {
        return serverTick;
    }

    public Collection<TrackedPearl> tracked() {
        return tracked.values();
    }

    public TrackedPearl get(UUID uuid) {
        return tracked.get(uuid);
    }

    public boolean isFull() {
        return tracked.size() >= config.maxConcurrent();
    }

    public void requestManualTrack(UUID player) {
        pendingManual.add(player);
    }

    public boolean consumeManualRequest(UUID player) {
        return pendingManual.remove(player);
    }

    public boolean hasManualRequest(UUID player) {
        return pendingManual.contains(player);
    }

    /**
     * Re-measures a pearl that was below the speed gate when it was launched.
     *
     * <p>A command block or another plugin can write {@code Motion} after the
     * launch event, so the velocity seen there is not the velocity the pearl
     * flies at. The scheduler runs before entities move, so on the following
     * ticks the pearl is still sitting at its origin with its real motion
     * already set — early enough to pick it up and pin its landing chunk.
     */
    public void watchForLateSpeed(Entity pearl, String shooter) {
        if (config.lateSpeedCheckTicks() <= 0 || tracked.containsKey(pearl.getUniqueId())) {
            return;
        }
        candidates.put(pearl.getUniqueId(), new Candidate(pearl.getWorld().getUID(), shooter,
                serverTick + config.lateSpeedCheckTicks()));
    }

    private void processCandidates() {
        Iterator<Map.Entry<UUID, Candidate>> it = candidates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Candidate> next = it.next();
            Candidate candidate = next.getValue();
            World world = Bukkit.getWorld(candidate.world());
            Entity entity = world == null ? null : world.getEntity(next.getKey());
            if (entity == null || !entity.isValid()) {
                it.remove();
                continue;
            }
            Vector velocity = entity.getVelocity();
            if (Math.hypot(velocity.getX(), velocity.getZ()) >= config.onlyIfSpeedAbove()) {
                it.remove();
                if (!isFull()) {
                    beginTracking(entity, candidate.shooter());
                }
            } else if (serverTick >= candidate.expiresAtTick()) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts tracking a pearl that was just launched. Pins its first chunks
     * immediately: the pearl moves during this very tick, long before the
     * scheduler next runs, so waiting would lose the first hop.
     */
    public TrackedPearl beginTracking(Entity entity, String shooter) {
        World world = entity.getWorld();
        Location loc = entity.getLocation();
        Vector vel = entity.getVelocity();

        TrackedPearl pearl = new TrackedPearl(entity.getUniqueId(), world.getUID(), world.getName(), shooter,
                new Vec3d(loc.getX(), loc.getY(), loc.getZ()),
                new Vec3d(vel.getX(), vel.getY(), vel.getZ()));

        entity.getPersistentDataContainer().set(trackedKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(launchTickKey, PersistentDataType.LONG, serverTick);

        if (config.disableCollision()) {
            entity.setNoPhysics(true);
            pearl.setCollisionDisabled(true);
        }

        tracked.put(pearl.uuid(), pearl);
        flightLogger.open(pearl);
        tickets.apply(world, pearl.uuid(), desiredChunks(pearl), serverTick);
        flightLogger.write(pearl, FlightEvent.LAUNCH, 0.0D, tickets.countFor(pearl.uuid()),
                "by " + shooter + " hSpeed=" + String.format(Locale.ROOT, "%.3f", pearl.horizontalSpeed()));
        return pearl;
    }

    // ------------------------------------------------------------------- loop

    @Override
    public void run() {
        serverTick++;
        if (!candidates.isEmpty()) {
            processCandidates();
        }
        if (tracked.isEmpty()) {
            return;
        }
        for (TrackedPearl pearl : new ArrayList<>(tracked.values())) {
            try {
                tickOne(pearl);
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Tracking " + pearl.uuid() + " failed: " + ex);
                finish(pearl, EndReason.INTERNAL_ERROR, ex.toString());
            }
        }
    }

    private void tickOne(TrackedPearl pearl) {
        World world = Bukkit.getWorld(pearl.worldUid());
        if (world == null) {
            finish(pearl, EndReason.WORLD_UNLOADED, null);
            return;
        }
        pearl.advanceTick();

        // (A) Correct against reality whenever the entity is reachable again, or
        //     roll the model forward one tick when it is not.
        Entity entity = world.getEntity(pearl.uuid());
        int pinnedBefore = tickets.countFor(pearl.uuid());
        double drift = 0.0D;
        if (entity != null && entity.isValid()) {
            Location loc = entity.getLocation();
            Vector vel = entity.getVelocity();
            drift = pearl.applyCorrection(
                    new Vec3d(loc.getX(), loc.getY(), loc.getZ()),
                    new Vec3d(vel.getX(), vel.getY(), vel.getZ()));
            // noPhysics is not part of the entity's saved data, so it has to be
            // re-applied every time the pearl comes back from an unloaded chunk.
            if (config.disableCollision() && !pearl.converged() && !entity.hasNoPhysics()) {
                entity.setNoPhysics(true);
                pearl.setCollisionDisabled(true);
            }
            flightLogger.write(pearl, FlightEvent.REAL, drift, pinnedBefore, null);
        } else {
            boolean justLost = pearl.lastWasReal();
            pearl.setState(step(pearl.state()));
            pearl.markPredicted();
            pearl.addUnconfirmedChunk(
                    ChunkKeys.of(pearl.pos().chunkX(), pearl.pos().chunkZ()), config.recoveryChunks());
            if (justLost) {
                flightLogger.write(pearl, FlightEvent.LOST, 0.0D, pinnedBefore, "entity left loaded chunks");
            }
        }

        // (B) Stop conditions.
        if (pearl.tick() >= config.maxTicks()) {
            finish(pearl, EndReason.TIMEOUT, null);
            return;
        }
        if (pearl.pos().isOutside(config.coordinateLimit())) {
            finish(pearl, EndReason.OUT_OF_BOUNDS, null);
            return;
        }

        // (C) Convergence: once a single tick no longer skips whole chunks, stop
        //     predicting ahead and just keep a normal-sized loaded area with it.
        double hSpeed = pearl.horizontalSpeed();
        if (!pearl.converged() && hSpeed < config.convergenceThreshold()) {
            pearl.markConverged(pearl.pos());
            flightLogger.write(pearl, FlightEvent.CONVERGED, drift, pinnedBefore,
                    String.format(Locale.ROOT, "hSpeed=%.4f at (%s)",
                            hSpeed, pearl.pos().format()));
        }

        if (pearl.converged()) {
            // Retried every tick: at the moment of convergence the entity is
            // usually still in an unloaded chunk and cannot be touched yet.
            restorePhysics(pearl, entity);
            if (pearl.tick() - pearl.convergedAtTick() > config.keepTicksAfterConvergence()) {
                finish(pearl, EndReason.CONVERGED_DONE, null);
                return;
            }
            // No more look-ahead: a plain radius that travels with the pearl, which
            // is all it needs now that it stays inside a chunk or two per tick.
            LinkedHashSet<Long> keep = new LinkedHashSet<>();
            addChunks(keep, pearl.pos(), config.keepRadiusAfterConvergence());
            tickets.apply(world, pearl.uuid(), keep, serverTick);
            pearl.setPredictedNext(step(pearl.state()).pos());
            return;
        }

        // (D) Predict and pin, nearest future first.
        pearl.setPredictedNext(step(pearl.state()).pos());
        int dropped = tickets.apply(world, pearl.uuid(), desiredChunks(pearl), serverTick);

        if (!pearl.lastWasReal()) {
            flightLogger.write(pearl, FlightEvent.PREDICTED, 0.0D, tickets.countFor(pearl.uuid()),
                    dropped > 0 ? "budget dropped " + dropped : null);
        }
    }

    // ------------------------------------------------------------------- stop

    public void stop(TrackedPearl pearl, EndReason reason) {
        finish(pearl, reason, null);
    }

    public int stopAll(EndReason reason) {
        int count = 0;
        for (TrackedPearl pearl : new ArrayList<>(tracked.values())) {
            finish(pearl, reason, null);
            count++;
        }
        return count;
    }

    private void finish(TrackedPearl pearl, EndReason reason, String extra) {
        tracked.remove(pearl.uuid());

        World world = Bukkit.getWorld(pearl.worldUid());
        if (world != null) {
            restorePhysics(pearl, world.getEntity(pearl.uuid()));
        }

        StringBuilder note = new StringBuilder(reason.name());
        if (pearl.converged() && pearl.convergencePoint() != null) {
            note.append(" convergedAtTick=").append(pearl.convergedAtTick())
                    .append(" convergencePoint=(").append(pearl.convergencePoint().format()).append(')');
        }
        note.append(String.format(Locale.ROOT, " travelled=%.1f corrections=%d maxDrift=%.4f",
                pearl.travelled(), pearl.corrections(), pearl.maxDrift()));
        if (extra != null) {
            note.append(' ').append(extra);
        }

        flightLogger.write(pearl, FlightEvent.END, 0.0D, tickets.countFor(pearl.uuid()), note.toString());
        flightLogger.close(pearl.uuid());
        tickets.releaseAll(pearl.uuid());
    }

    /** Give the pearl its collisions back so it can actually land on something. */
    private void restorePhysics(TrackedPearl pearl, Entity entity) {
        if (!pearl.collisionDisabled()) {
            return;
        }
        if (entity != null && entity.isValid()) {
            entity.setNoPhysics(false);
            pearl.setCollisionDisabled(false);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Step step(Step from) {
        return PearlPhysics.advance(from, config.drag(), config.gravity(), config.order());
    }

    /**
     * Chunks to keep pinned: the one the pearl is in right now (so it is not
     * unpinned out from under itself) plus the landing chunk of each of the next
     * {@code lookahead-ticks} ticks. Iteration order is nearest-future-first so
     * the budget, if it runs out, drops the least urgent chunks.
     *
     * <p>Deliberately <em>not</em> the corridor between the two: at 1000 blocks a
     * tick that is 60+ chunks per hop and would exhaust any budget, and the pearl
     * only needs the chunk it lands in to keep ticking. The trade-off is that it
     * passes through unloaded terrain without colliding with it.
     */
    private LinkedHashSet<Long> desiredChunks(TrackedPearl pearl) {
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        // First, so the budget never trims these: while the entity is out of
        // sight it is stranded in one of them, and releasing that chunk would
        // strand it for good.
        out.addAll(pearl.unconfirmedChunks());
        int radius = config.forceloadRadius();
        Step probe = pearl.state();
        addChunks(out, probe.pos(), radius);
        for (int i = 0; i < config.lookaheadTicks(); i++) {
            probe = step(probe);
            if (probe.pos().isOutside(config.coordinateLimit())) {
                break;
            }
            addChunks(out, probe.pos(), radius);
        }
        return out;
    }

    private void addChunks(Set<Long> out, Vec3d pos, int radius) {
        int cx = pos.chunkX();
        int cz = pos.chunkZ();
        out.add(ChunkKeys.of(cx, cz));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(ChunkKeys.of(cx + dx, cz + dz));
            }
        }
    }
}
