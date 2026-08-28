package dev.sql1024.pearltrack.sim;

import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.physics.PhysicsOrder;
import dev.sql1024.pearltrack.physics.Step;
import dev.sql1024.pearltrack.physics.Vec3d;
import dev.sql1024.pearltrack.track.TrackedPearl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pearl cannon charging by weak loading: the pearl sits in a chunk that is
 * loaded but not entity-ticking, so it never ticks, never moves and never loses
 * momentum to drag, while explosions keep adding to its Motion. Raising that
 * chunk to entity-ticking is the shot — which is exactly what pinning it does.
 */
class CannonSimulationTest {

    private static final Vec3d CANNON = new Vec3d(0.5D, 100.0D, 0.5D);

    private static SimulatedServer cannon(boolean chunkVisible) {
        SimulatedServer sim = new SimulatedServer(SimulatedServer.config(5, 0, true),
                CANNON, new Vec3d(1000.0D, 100.0D, 1.0D)).install();
        sim.charging = true;
        if (chunkVisible) {
            // Weakly loaded: entities can be looked up, but nothing ticks.
            sim.alwaysLoaded.add(sim.chunkOf(CANNON));
        }
        return sim;
    }

    @Test
    void aChargingPearlIsNeverPinnedAndNeverFires() {
        SimulatedServer sim = cannon(true);
        sim.tracker.beginTracking(sim.entity(), "cannon");

        sim.runTicks(400);

        assertEquals(0, sim.ticksLived, "the pearl never ticked, so the cannon never fired");
        assertEquals(CANNON, sim.pos, "and it never moved");
        assertFalse(sim.everPinned.contains(sim.chunkOf(CANNON)),
                "the launcher chunk was never pinned");
        assertEquals(0, sim.maxPinned, "in fact nothing was pinned at all");

        // 400 ticks x 5.0 blocks/tick of accumulated knockback on top of the initial 1000.
        assertEquals(3000.0D, sim.motion.x(), 1.0E-6D);

        TrackedPearl pearl = sim.tracker.get(sim.pearlId);
        assertNotNull(pearl, "still tracked, just not touched");
        assertTrue(pearl.holding());
        assertTrue(pearl.holdTicks() >= 395, "held for " + pearl.holdTicks() + " ticks");
        assertEquals(0, pearl.tick(), "held ticks do not count against max-ticks");
    }

    /**
     * A launcher whose chunk unloads outright, rather than staying weakly loaded,
     * is indistinguishable from a shot: the pearl simply stops being reachable.
     * The tracker assumes the shot, which is the right call for a real cannon —
     * a fully unloaded chunk cannot run one, because the TNT would not tick
     * either. What matters is that the mistake stays cheap and bounded: the
     * launcher is never pinned, and the chase gives up on max-blind-ticks.
     */
    @Test
    void anUnloadedLauncherIsAssumedFiredButNeverPinned() {
        SimulatedServer sim = cannon(false);
        sim.tracker.beginTracking(sim.entity(), "cannon");

        sim.runTicks(400);

        assertEquals(0, sim.ticksLived, "the pearl itself never moved");
        assertFalse(sim.everPinned.contains(sim.chunkOf(CANNON)),
                "and the launcher chunk was never pinned");
        assertTrue(sim.tracker.tracked().isEmpty(), "the chase gave up");
        assertEquals(0, sim.tickets.globalCount(), "and released everything");
        assertTrue(sim.everPinned.size() < 260,
                "bounded by max-blind-ticks, not max-ticks: " + sim.everPinned.size());
    }

    @Test
    void theTrackerTakesOverTheTickTheCannonFires() {
        SimulatedServer sim = cannon(true);
        sim.tracker.beginTracking(sim.entity(), "cannon");
        sim.runTicks(300);

        double launchSpeed = sim.motion.horizontalLength();
        assertTrue(launchSpeed > 2400.0D, "charged to " + launchSpeed + " b/t");

        // The shot: the launcher raises its own chunk to entity-ticking.
        sim.alwaysTicking.add(sim.chunkOf(CANNON));
        sim.runTicks(400);

        TrackedPearl pearl = sim.tracker.get(sim.pearlId);
        assertNotNull(pearl, "still tracked after the shot");
        assertFalse(pearl.holding(), "and now under way");
        assertTrue(sim.ticksLived > 390,
                "kept ticking through unloaded terrain: " + sim.ticksLived
                        + " lived, " + sim.frozenTicks + " frozen");
        assertTrue(sim.frozenTicks <= 2, "at most the tick of the shot itself: " + sim.frozenTicks);
        assertTrue(sim.pos.x() > 200_000.0D, "flew " + sim.pos.x() + " blocks");
    }

    /**
     * A frozen pearl keeps its momentum — that is the same property the cannon
     * runs on — so the stall at the shot delays the flight without bending it.
     */
    @Test
    void theStallAtTheShotCostsTicksNotTrajectory() {
        SimulatedServer sim = cannon(true);
        sim.tracker.beginTracking(sim.entity(), "cannon");
        sim.runTicks(300);

        Step atTheShot = new Step(sim.pos, sim.motion);
        sim.alwaysTicking.add(sim.chunkOf(CANNON));
        sim.runTicks(300);

        // Pure physics, advanced only by the ticks the pearl actually lived.
        Step expected = atTheShot;
        for (int i = 0; i < sim.ticksLived; i++) {
            expected = PearlPhysics.advance(expected, SimulatedServer.DRAG, SimulatedServer.GRAVITY,
                    PhysicsOrder.VANILLA);
        }
        assertEquals(0.0D, expected.pos().distanceTo(sim.pos), 1.0E-6D,
                "identical trajectory, " + sim.frozenTicks + " ticks later");
    }
}
