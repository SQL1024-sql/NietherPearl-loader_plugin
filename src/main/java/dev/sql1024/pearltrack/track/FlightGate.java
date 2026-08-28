package dev.sql1024.pearltrack.track;

/**
 * Decides, from one tick's observations, whether a pearl may have chunks pinned
 * for it. Pure and Bukkit-free so the truth table can be unit tested.
 *
 * <p>The stakes are asymmetric. Failing to pin costs the pearl a tick of flight
 * and nothing else — a frozen pearl keeps its momentum, so the trajectory is
 * unchanged. Pinning something that should not be pinned raises its chunk to
 * entity-ticking, which fires a charging pearl cannon with partial momentum and
 * cannot be undone. Every ambiguous case therefore resolves to "do not pin".
 */
public final class FlightGate {

    public enum Decision {
        /** Under way: predict and pin. */
        FLYING,
        /** Not under way, and any pins held for this pearl must be dropped. */
        HOLD_RELEASE,
        /**
         * Not ticking yet, but only just: a chunk we pinned may have finished
         * loading without having reached entity-ticking. Releasing here would
         * strand the very pearl the pin was meant to revive, so pins are kept
         * while the ticket takes effect.
         */
        HOLD_KEEP_PINS
    }

    private FlightGate() {
    }

    /**
     * @param holding          the pearl has not been declared under way yet
     * @param observed         the entity was reachable this tick
     * @param ticking          {@code getTicksLived()} changed since the previous observation
     * @param moved            its position changed since the previous observation
     * @param hSpeed           horizontal blocks/tick, observed if possible, else modelled
     * @param speedGate        {@code tracking.only-if-speed-above}
     * @param notTickingStreak consecutive observations with no tick, including this one
     * @param graceTicks       {@code tracking.hold-release-grace-ticks}
     */
    public static Decision decide(boolean holding, boolean observed, boolean ticking, boolean moved,
                                  double hSpeed, double speedGate,
                                  int notTickingStreak, int graceTicks) {
        // The speed gate decides whether a pearl is worth picking up, not whether
        // to keep carrying one. Dropping a pearl mid-flight because drag brought
        // it under the threshold releases its chunks while it is deep in unloaded
        // terrain, which strands it for good. Convergence is what ends a flight.
        boolean interesting = !holding || hSpeed >= speedGate;

        if (!observed) {
            // Out of the loaded area, which it can only reach by moving.
            return interesting ? Decision.FLYING : Decision.HOLD_RELEASE;
        }
        if (!ticking) {
            // Loaded but not ticking: a launcher charging up, or a chunk of ours
            // that has not come up to entity-ticking yet.
            return notTickingStreak <= graceTicks ? Decision.HOLD_KEEP_PINS : Decision.HOLD_RELEASE;
        }
        if (!moved) {
            // Ticking, carrying momentum, and going nowhere: something else is
            // holding it in place. Whatever that is, stay out of its way.
            return Decision.HOLD_RELEASE;
        }
        return interesting ? Decision.FLYING : Decision.HOLD_RELEASE;
    }
}
