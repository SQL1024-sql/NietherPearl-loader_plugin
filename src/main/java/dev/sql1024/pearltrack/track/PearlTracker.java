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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The per-tick loop: decide whether the pearl is actually under way, correct
 * against reality, and pin the chunks it is about to reach.
 *
 * <p>Bukkit runs the scheduler heartbeat earlier in the server tick than the
 * world/entity tick, so a chunk pinned here is pinned <em>before</em> the pearl
 * moves into it this same tick. That ordering is what makes the whole approach
 * work; do not move this onto an async task.
 *
 * <p><b>Held pearls must never be pinned.</b> A pearl sitting in a chunk that is
 * loaded but not entity-ticking does not move and does not lose momentum to
 * drag, which is exactly what a pearl cannon relies on while explosions charge
 * it up. Pinning that chunk raises it to entity-ticking and fires the cannon on
 * the spot, so the pin is gated on the entity actually having ticked, not on it
 * merely carrying a large Motion.
 */
public final class PearlTracker implements Runnable {

    private final Plugin plugin;
    private final ChunkTicketManager tickets;
    private final FlightLogger flightLogger;
    private final NamespacedKey trackedKey;
    private final NamespacedKey launchTickKey;

    private final Map<UUID, TrackedPearl> tracked = new LinkedHashMap<>();
    /** Players who ran {@code /pearltrack next} and have not thrown a pearl yet. */
    private final Set<UUID> pendingManual = new HashSet<>();
    /** Pearls that were too slow at launch and are being re-measured for a few ticks. */
    private final Map<UUID, Candidate> candidates = new LinkedHashMap<>();

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
    private record Candidate(UUID world, String shooter, int ticksLived, long expiresAtTick) {
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

    // ------------------------------------------------------------- candidates

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
                pearl.getTicksLived(), serverTick + config.lateSpeedCheckTicks()));
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
            // Both conditions matter: a pearl charging in a cannon carries an
            // enormous Motion while standing perfectly still, and adopting it
            // here would pin its chunk and fire it.
            boolean fast = Math.hypot(velocity.getX(), velocity.getZ()) >= config.onlyIfSpeedAbove();
            boolean ticking = entity.getTicksLived() != candidate.ticksLived();
            if (fast && ticking) {
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
     * Starts tracking a pearl. Nothing is pinned unless the pearl is already
     * moving fast: at this point it may just as easily be sitting in a cannon
     * waiting to be charged, and one pin would launch it.
     */
    public TrackedPearl beginTracking(Entity entity, String shooter) {
        World world = entity.getWorld();
        Location loc = entity.getLocation();
        Vector vel = entity.getVelocity();

        TrackedPearl pearl = new TrackedPearl(entity.getUniqueId(), world.getUID(), world.getName(), shooter,
                new Vec3d(loc.getX(), loc.getY(), loc.getZ()),
                new Vec3d(vel.getX(), vel.getY(), vel.getZ()));
        pearl.setLastTicksLived(entity.getTicksLived());

        entity.getPersistentDataContainer().set(trackedKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(launchTickKey, PersistentDataType.LONG, serverTick);

        tracked.put(pearl.uuid(), pearl);
        flightLogger.open(pearl);
        flightLogger.write(pearl, FlightEvent.LAUNCH, 0.0D, 0,
                String.format(Locale.ROOT, "by %s hSpeed=%.3f", shooter, pearl.horizontalSpeed()));
        return pearl;
    }

    /**
     * Adopts ender pearls already in the world near {@code origin} — the way to
     * pick up a pearl that is sitting in a cannon rather than being thrown.
     *
     * @return how many pearls were adopted
     */
    public int adopt(Location origin, double radius) {
        int adopted = 0;
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, radius, radius, radius)) {
            if (!(entity instanceof org.bukkit.entity.EnderPearl) || tracked.containsKey(entity.getUniqueId())) {
                continue;
            }
            if (isFull()) {
                break;
            }
            beginTracking(entity, "adopted");
            adopted++;
        }
        return adopted;
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

        Entity entity = world.getEntity(pearl.uuid());
        boolean observed = entity != null && entity.isValid();

        boolean ticking = false;
        boolean moved = true;
        Vec3d observedPos = null;
        Vec3d observedMotion = null;
        if (observed) {
            int ticksLived = entity.getTicksLived();
            ticking = pearl.lastTicksLived() >= 0 && ticksLived != pearl.lastTicksLived();
            pearl.setLastTicksLived(ticksLived);
            pearl.setNotTickingStreak(ticking ? 0 : pearl.notTickingStreak() + 1);

            Location loc = entity.getLocation();
            Vector vel = entity.getVelocity();
            observedPos = new Vec3d(loc.getX(), loc.getY(), loc.getZ());
            observedMotion = new Vec3d(vel.getX(), vel.getY(), vel.getZ());
            moved = pearl.lastSeenPos() == null
                    || observedPos.distanceTo(pearl.lastSeenPos()) > config.stationaryEpsilon();
            pearl.setLastSeenPos(observedPos);
        } else {
            pearl.setNotTickingStreak(0);
        }

        double hSpeed = observed ? observedMotion.horizontalLength() : pearl.horizontalSpeed();
        FlightGate.Decision decision = FlightGate.decide(observed, ticking, moved, hSpeed,
                config.onlyIfSpeedAbove(), pearl.notTickingStreak(), config.holdReleaseGraceTicks());

        if (decision != FlightGate.Decision.FLYING) {
            hold(pearl, observed, observedPos, observedMotion, hSpeed,
                    decision == FlightGate.Decision.HOLD_RELEASE);
            return;
        }
        if (pearl.holding()) {
            pearl.beginFlight();
            flightLogger.write(pearl, FlightEvent.FIRED, 0.0D, 0, String.format(Locale.ROOT,
                    "held %d ticks, leaving at %.2f b/t, %.0f blocks of flight ahead",
                    pearl.holdTicks(), hSpeed,
                    PearlPhysics.remainingHorizontalDistance(hSpeed, config.drag())));
        }

        pearl.advanceTick();

        // (A) Correct against reality whenever the entity is reachable, or roll
        //     the model forward one tick when it is not.
        int pinnedBefore = tickets.countFor(pearl.uuid());
        double drift = 0.0D;
        if (observed) {
            drift = pearl.applyCorrection(observedPos, observedMotion);
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
        if (!pearl.converged() && pearl.horizontalSpeed() < config.convergenceThreshold()) {
            pearl.markConverged(pearl.pos());
            flightLogger.write(pearl, FlightEvent.CONVERGED, drift, pinnedBefore,
                    String.format(Locale.ROOT, "hSpeed=%.4f at (%s)",
                            pearl.horizontalSpeed(), pearl.pos().format()));
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
            tickets.apply(world, pearl.uuid(), excludeHoldArea(pearl, keep), serverTick);
            pearl.setPredictedNext(step(pearl.state()).pos());
            return;
        }

        // (D) Predict and pin, nearest future first.
        pearl.setPredictedNext(step(pearl.state()).pos());
        int dropped = tickets.apply(world, pearl.uuid(),
                excludeHoldArea(pearl, desiredChunks(pearl)), serverTick);

        if (!pearl.lastWasReal()) {
            flightLogger.write(pearl, FlightEvent.PREDICTED, 0.0D, tickets.countFor(pearl.uuid()),
                    dropped > 0 ? "budget dropped " + dropped : null);
        }
    }

    /**
     * The pearl is not under way. Pins are dropped, because raising its chunk to
     * entity-ticking is exactly what would set a charging launcher off — except
     * during the grace window described by
     * {@link FlightGate.Decision#HOLD_KEEP_PINS}.
     */
    private void hold(TrackedPearl pearl, boolean observed, Vec3d pos, Vec3d motion, double hSpeed,
                      boolean releasePins) {
        if (!releasePins) {
            // Grace window: a chunk we pinned has loaded but is not entity-ticking
            // yet. Keep the pins and do not record a hold chunk — excluding the
            // chunk the pearl is stranded in is the one thing that would make this
            // unrecoverable.
            if (observed) {
                pearl.observeTransitional(pos, motion);
            }
            return;
        }

        tickets.releaseAll(pearl.uuid());

        if (observed) {
            pearl.observeHold(pos, motion, ChunkKeys.of(pos.chunkX(), pos.chunkZ()));
        } else {
            pearl.observeBlindHold();
        }

        if (pearl.holdTicks() >= config.maxHoldTicks()) {
            finish(pearl, EndReason.HOLD_TIMEOUT, null);
            return;
        }
        if (config.holdLogIntervalTicks() > 0 && pearl.holdTicks() % config.holdLogIntervalTicks() == 0) {
            flightLogger.write(pearl, FlightEvent.HOLDING, 0.0D, 0, String.format(Locale.ROOT,
                    "hold=%d hSpeed=%.2f gain=%+.3f/tick%s",
                    pearl.holdTicks(), hSpeed, pearl.holdSpeedGain(), observed ? "" : " (out of sight)"));
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
        note.append(String.format(Locale.ROOT, " held=%d travelled=%.1f corrections=%d maxDrift=%.4f",
                pearl.holdTicks(), pearl.travelled(), pearl.corrections(), pearl.maxDrift()));
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

    /**
     * Drops the chunk the pearl was last held in, plus a margin, from a pin set.
     *
     * <p>A launcher is a fixed place that has to be able to drop below
     * entity-ticking. Predictions are normally thousands of blocks away so this
     * removes nothing, but it is the one mistake that silently breaks a cannon,
     * and it costs one set lookup to rule out.
     */
    private LinkedHashSet<Long> excludeHoldArea(TrackedPearl pearl, LinkedHashSet<Long> desired) {
        if (!pearl.hasHoldChunk()) {
            return desired;
        }
        int radius = config.holdExclusionRadius();
        int hx = ChunkKeys.x(pearl.holdChunk());
        int hz = ChunkKeys.z(pearl.holdChunk());
        desired.removeIf(key -> Math.abs(ChunkKeys.x(key) - hx) <= radius
                && Math.abs(ChunkKeys.z(key) - hz) <= radius);
        return desired;
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
