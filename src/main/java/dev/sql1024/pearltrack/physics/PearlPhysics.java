package dev.sql1024.pearltrack.physics;

/**
 * Pure ballistic model of a thrown ender pearl. No Bukkit types, no state:
 * every method is a function of its arguments so the model can be unit tested
 * and replayed against the CSV flight logs.
 */
public final class PearlPhysics {

    private PearlPhysics() {
    }

    /**
     * Advances one tick.
     *
     * <p>{@link PhysicsOrder#VANILLA} mirrors {@code ThrowableProjectile#tick}:
     * the entity moves by its current delta, the delta is then scaled by drag
     * and finally gravity is subtracted. {@link PhysicsOrder#GRAVITY_FIRST}
     * subtracts gravity before the move instead.
     */
    public static Step advance(Step step, double drag, double gravity, PhysicsOrder order) {
        Vec3d m = step.motion();
        if (order == PhysicsOrder.GRAVITY_FIRST) {
            double vy = m.y() - gravity;
            Vec3d pos = step.pos().add(m.x(), vy, m.z());
            return new Step(pos, new Vec3d(m.x() * drag, vy * drag, m.z() * drag));
        }
        Vec3d pos = step.pos().add(m);
        return new Step(pos, new Vec3d(m.x() * drag, m.y() * drag - gravity, m.z() * drag));
    }

    /**
     * Total horizontal distance still ahead of a pearl moving at {@code hSpeed},
     * i.e. the sum of the geometric series {@code hSpeed * drag^n}. With the
     * vanilla drag of 0.99 this is simply {@code hSpeed * 100}.
     */
    public static double remainingHorizontalDistance(double hSpeed, double drag) {
        if (drag <= 0.0D || drag >= 1.0D) {
            return Double.POSITIVE_INFINITY;
        }
        return hSpeed / (1.0D - drag);
    }

    /**
     * Ticks until horizontal speed decays below {@code threshold}, or 0 when it
     * already is. Returns {@link Integer#MAX_VALUE} if drag can never get there.
     */
    public static int ticksUntilHorizontalSpeedBelow(double hSpeed, double drag, double threshold) {
        if (hSpeed <= threshold) {
            return 0;
        }
        if (drag <= 0.0D || drag >= 1.0D || threshold <= 0.0D) {
            return Integer.MAX_VALUE;
        }
        double ticks = Math.log(threshold / hSpeed) / Math.log(drag);
        if (!Double.isFinite(ticks) || ticks > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(ticks);
    }
}
