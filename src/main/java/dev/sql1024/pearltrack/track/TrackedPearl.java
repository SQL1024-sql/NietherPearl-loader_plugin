package dev.sql1024.pearltrack.track;

import dev.sql1024.pearltrack.physics.Step;
import dev.sql1024.pearltrack.physics.Vec3d;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Everything known about one pearl in flight. Mutated only from the main
 * server thread by {@link PearlTracker}.
 */
public final class TrackedPearl {

    private final UUID uuid;
    private final UUID worldUid;
    private final String worldName;
    private final String shooter;
    private final Vec3d launchPos;

    private Step state;
    private Vec3d predictedNext;
    private int tick;

    private int lastRealTick = -1;
    private Vec3d lastRealPos;
    private boolean lastWasReal;
    private int corrections;
    private double lastDrift;
    private double maxDrift;
    private double totalDrift;

    private boolean converged;
    private int convergedAtTick = -1;
    private Vec3d convergencePoint;

    /**
     * Chunks the model has moved through since the last real fix, oldest first.
     *
     * <p>Losing sight of the entity means it stopped ticking, which means it is
     * sitting in whichever of these chunks was not ready in time. The model keeps
     * racing ahead, so without holding these the pin would be released out from
     * under a stranded pearl and it could never come back.
     */
    private final Set<Long> unconfirmedChunks = new LinkedHashSet<>();

    /**
     * {@code getTicksLived()} at the previous observation. Unchanged between two
     * server ticks means the entity did not tick at all, which is the only
     * reliable read on whether its chunk is entity-ticking.
     */
    private int lastTicksLived = -1;
    /** Position at the previous observation, for telling a moving pearl from a pinned-in-place one. */
    private Vec3d lastSeenPos;
    private int notTickingStreak;
    private boolean holding = true;
    private int holdTicks;
    private double previousHoldSpeed;
    private double holdSpeedGain;
    private boolean hasHoldChunk;
    private long holdChunk;
    private int firedAtHoldTick = -1;

    private boolean collisionDisabled;

    public TrackedPearl(UUID uuid, UUID worldUid, String worldName, String shooter,
                        Vec3d pos, Vec3d motion) {
        this.uuid = uuid;
        this.worldUid = worldUid;
        this.worldName = worldName;
        this.shooter = shooter;
        this.launchPos = pos;
        this.state = new Step(pos, motion);
        this.predictedNext = pos;
        this.lastRealPos = pos;
        this.lastSeenPos = pos;
    }

    public UUID uuid() {
        return uuid;
    }

    public UUID worldUid() {
        return worldUid;
    }

    public String worldName() {
        return worldName;
    }

    public String shooter() {
        return shooter;
    }

    public Vec3d launchPos() {
        return launchPos;
    }

    public Step state() {
        return state;
    }

    public Vec3d pos() {
        return state.pos();
    }

    public Vec3d motion() {
        return state.motion();
    }

    public double horizontalSpeed() {
        return state.motion().horizontalLength();
    }

    public void setState(Step state) {
        this.state = state;
    }

    public Vec3d predictedNext() {
        return predictedNext;
    }

    public void setPredictedNext(Vec3d predictedNext) {
        this.predictedNext = predictedNext;
    }

    public int tick() {
        return tick;
    }

    public int advanceTick() {
        return ++tick;
    }

    /**
     * Overwrites the predicted state with what the server actually reports and
     * records how far the prediction had drifted.
     */
    public double applyCorrection(Vec3d realPos, Vec3d realMotion) {
        double drift = state.pos().distanceTo(realPos);
        this.state = new Step(realPos, realMotion);
        this.lastRealPos = realPos;
        this.lastRealTick = tick;
        this.lastWasReal = true;
        this.corrections++;
        this.lastDrift = drift;
        this.unconfirmedChunks.clear();
        this.totalDrift += drift;
        if (drift > maxDrift) {
            this.maxDrift = drift;
        }
        return drift;
    }

    public void markPredicted() {
        this.lastWasReal = false;
    }

    public boolean lastWasReal() {
        return lastWasReal;
    }

    public int lastRealTick() {
        return lastRealTick;
    }

    public Vec3d lastRealPos() {
        return lastRealPos;
    }

    public int corrections() {
        return corrections;
    }

    public double lastDrift() {
        return lastDrift;
    }

    public double maxDrift() {
        return maxDrift;
    }

    public double averageDrift() {
        return corrections == 0 ? 0.0D : totalDrift / corrections;
    }

    public boolean converged() {
        return converged;
    }

    public int convergedAtTick() {
        return convergedAtTick;
    }

    public Vec3d convergencePoint() {
        return convergencePoint;
    }

    public void markConverged(Vec3d point) {
        this.converged = true;
        this.convergedAtTick = tick;
        this.convergencePoint = point;
    }

    public Set<Long> unconfirmedChunks() {
        return unconfirmedChunks;
    }

    /** Oldest entries are the likeliest stranding spots, so a full trail keeps them. */
    public void addUnconfirmedChunk(long key, int cap) {
        if (unconfirmedChunks.size() < cap) {
            unconfirmedChunks.add(key);
        }
    }

    public Vec3d lastSeenPos() {
        return lastSeenPos;
    }

    public void setLastSeenPos(Vec3d lastSeenPos) {
        this.lastSeenPos = lastSeenPos;
    }

    public int notTickingStreak() {
        return notTickingStreak;
    }

    public void setNotTickingStreak(int notTickingStreak) {
        this.notTickingStreak = notTickingStreak;
    }

    /** Refreshes from reality without declaring a hold, used during the grace window. */
    public void observeTransitional(Vec3d pos, Vec3d motion) {
        this.state = new Step(pos, motion);
        this.lastRealPos = pos;
    }

    public int lastTicksLived() {
        return lastTicksLived;
    }

    public void setLastTicksLived(int lastTicksLived) {
        this.lastTicksLived = lastTicksLived;
    }

    public boolean holding() {
        return holding;
    }

    public int holdTicks() {
        return holdTicks;
    }

    /** Blocks/tick of horizontal momentum gained during the last held tick. */
    public double holdSpeedGain() {
        return holdSpeedGain;
    }

    public boolean hasHoldChunk() {
        return hasHoldChunk;
    }

    public long holdChunk() {
        return holdChunk;
    }

    public int firedAtHoldTick() {
        return firedAtHoldTick;
    }

    /** Records a tick where the pearl is loaded but not ticking. */
    public void observeHold(Vec3d pos, Vec3d motion, long chunk) {
        this.state = new Step(pos, motion);
        this.lastRealPos = pos;
        this.holding = true;
        this.holdTicks++;
        double speed = motion.horizontalLength();
        this.holdSpeedGain = speed - previousHoldSpeed;
        this.previousHoldSpeed = speed;
        this.hasHoldChunk = true;
        this.holdChunk = chunk;
        this.unconfirmedChunks.clear();
    }

    /** Records a held tick where the pearl could not be seen at all. */
    public void observeBlindHold() {
        this.holding = true;
        this.holdTicks++;
        this.holdSpeedGain = 0.0D;
    }

    public void beginFlight() {
        this.holding = false;
        this.firedAtHoldTick = holdTicks;
    }

    public boolean collisionDisabled() {
        return collisionDisabled;
    }

    public void setCollisionDisabled(boolean collisionDisabled) {
        this.collisionDisabled = collisionDisabled;
    }

    /** Straight-line distance from where it was thrown. */
    public double travelled() {
        return launchPos.distanceTo(state.pos());
    }
}
