package dev.sql1024.pearltrack.track;

import dev.sql1024.pearltrack.physics.Step;
import dev.sql1024.pearltrack.physics.Vec3d;

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
