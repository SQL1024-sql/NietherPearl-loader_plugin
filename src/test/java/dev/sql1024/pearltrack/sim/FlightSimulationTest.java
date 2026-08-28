package dev.sql1024.pearltrack.sim;

import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.physics.Vec3d;
import dev.sql1024.pearltrack.track.TrackedPearl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end runs of the real tracker against {@link SimulatedServer}, whose
 * pearl only moves while its chunk is entity-ticking.
 */
class FlightSimulationTest {

    private static SimulatedServer launch(TrackConfig config, Vec3d motion, int loadDelay) {
        Vec3d start = new Vec3d(0.5D, 200.0D, 0.5D);
        SimulatedServer sim = new SimulatedServer(config, start, motion).install();
        sim.loadDelayTicks = loadDelay;
        // The launcher sits in the ordinary ticking area around spawn.
        sim.alwaysTicking.add(sim.chunkOf(start));
        sim.tracker.beginTracking(sim.entity(), "test");
        return sim;
    }

    /**
     * The whole premise: without the plugin a pearl this fast leaves the ticking
     * area on its first hop and never moves again.
     */
    @Test
    void withoutTheTrackerTheSecondHopIsTheLastOne() {
        SimulatedServer sim = new SimulatedServer(
                SimulatedServer.config(5, 0, true), new Vec3d(0.5D, 200.0D, 0.5D),
                new Vec3d(1000.0D, 0.0D, 0.0D)).install();
        sim.alwaysTicking.add(sim.chunkOf(sim.pos));

        for (int i = 0; i < 200; i++) {
            sim.runTick();  // tracker has nothing tracked, so it pins nothing
        }
        assertEquals(1, sim.ticksLived, "one hop out of the ticking chunk, then frozen");
        assertTrue(sim.pos.x() < 1001.0D, "it got exactly one tick of movement");
        assertEquals(199, sim.frozenTicks);
    }

    @Test
    void aTrackedPearlKeepsTickingForTheWholeFlight() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 0);
        sim.runTicks(600);

        assertEquals(0, sim.frozenTicks, "the pearl never stopped ticking");
        assertEquals(600, sim.ticksLived);

        // Closed form: 1000 blocks/tick at 0.99 drag covers 1000/(1-0.99) = 100,000.
        double covered = PearlPhysics.remainingHorizontalDistance(1000.0D, SimulatedServer.DRAG)
                - PearlPhysics.remainingHorizontalDistance(sim.motion.horizontalLength(), SimulatedServer.DRAG);
        assertEquals(covered, sim.pos.x() - 0.5D, 1.0D);
        assertTrue(sim.pos.x() > 98_000.0D, "flew " + sim.pos.x() + " blocks");
    }

    @Test
    void theModelStaysGluedToTheEntityWhileItIsVisible() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 0);
        sim.runTicks(300);

        TrackedPearl pearl = sim.tracker.get(sim.pearlId);
        assertNotNull(pearl);
        assertTrue(pearl.lastWasReal(), "every tick was a real observation, not a prediction");
        assertEquals(0.0D, pearl.maxDrift(), 1.0E-6D,
                "the prediction matched reality every tick, so drift stayed at zero");
    }

    @Test
    void aFewPinnedChunksCarryTheWholeFlight() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 0);
        sim.runTicks(400);  // stop just short of convergence, where the 5x5 window takes over

        assertTrue(sim.maxPinned <= 8, "at most 8 chunks pinned at once, saw " + sim.maxPinned);
        assertTrue(sim.everPinned.size() > 300, "but hundreds over the flight: " + sim.everPinned.size());
        assertTrue(sim.everPinned.size() < 700);
    }

    @Test
    void itConvergesAndReleasesEverything() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 0);

        int convergedAt = -1;
        for (int i = 0; i < 1400 && convergedAt < 0; i++) {
            sim.runTick();
            TrackedPearl pearl = sim.tracker.get(sim.pearlId);
            if (pearl != null && pearl.converged()) {
                convergedAt = pearl.tick();
            }
        }
        // 1000 -> below 16 blocks/tick takes 412 ticks of drag.
        assertEquals(412, convergedAt, 2);

        // keep-ticks-after-convergence is 600, so it lets go 600 ticks after that.
        sim.runTicks(650);
        assertTrue(sim.tracker.tracked().isEmpty(), "tracking finished");
        assertEquals(0, sim.tickets.globalCount(), "every chunk released");
    }

    /**
     * Chunk generation cannot always keep up. The pearl then freezes where it
     * landed and the model runs on, which is what the recovery pins are for.
     */
    @Test
    void itRecoversWhenChunkLoadingFallsBehind() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 3);
        sim.runTicks(600);

        assertTrue(sim.frozenTicks > 0, "the load delay did stall it at least once");
        assertTrue(sim.ticksLived > 500,
                "but it kept going: " + sim.ticksLived + " ticks lived, " + sim.frozenTicks + " frozen");
        assertTrue(sim.pos.x() > 90_000.0D, "and still covered the distance: " + sim.pos.x());
    }

    /**
     * Drag takes the pearl below tracking.only-if-speed-above (4.0) long before it
     * lands. That threshold decides what to pick up, and must not be re-applied to
     * a pearl already deep in unloaded terrain.
     */
    @Test
    void slowingBelowThePickUpThresholdDoesNotStrandThePearl() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 0);
        sim.runTicks(600);

        assertEquals(0, sim.frozenTicks,
                "still ticking at " + sim.motion.horizontalLength() + " b/t");
        assertTrue(sim.motion.horizontalLength() < 4.0D, "and it really is under the threshold");
        assertTrue(sim.tickets.globalCount() > 0, "its chunks are still held");
    }

    @Test
    void aLoadDelayLongerThanTheLookaheadStillDoesNotLoseThePearl() {
        SimulatedServer sim = launch(SimulatedServer.config(5, 0, true),
                new Vec3d(1000.0D, 0.0D, 0.0D), 12);
        sim.runTicks(1200);

        assertTrue(sim.ticksLived > 60, "still moving after " + sim.ticksLived + " lived ticks");
        assertNotNull(sim.tracker.get(sim.pearlId), "still tracked, not abandoned");
    }
}
