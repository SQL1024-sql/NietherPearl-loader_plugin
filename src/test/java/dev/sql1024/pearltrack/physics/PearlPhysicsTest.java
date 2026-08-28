package dev.sql1024.pearltrack.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlPhysicsTest {

    private static final double DRAG = 0.99D;
    private static final double GRAVITY = 0.03D;

    @Test
    void vanillaOrderMovesByTheCurrentDeltaThenAppliesDragAndGravity() {
        Step start = new Step(new Vec3d(0, 100, 0), new Vec3d(1000, 5, -20));
        Step next = PearlPhysics.advance(start, DRAG, GRAVITY, PhysicsOrder.VANILLA);

        assertEquals(1000.0D, next.pos().x(), 1.0E-9D);
        assertEquals(105.0D, next.pos().y(), 1.0E-9D);
        assertEquals(-20.0D, next.pos().z(), 1.0E-9D);

        assertEquals(990.0D, next.motion().x(), 1.0E-9D);
        assertEquals(5 * DRAG - GRAVITY, next.motion().y(), 1.0E-9D);
        assertEquals(-19.8D, next.motion().z(), 1.0E-9D);
    }

    @Test
    void gravityFirstOrderSubtractsGravityBeforeTheMove() {
        Step start = new Step(new Vec3d(0, 100, 0), new Vec3d(1000, 5, -20));
        Step next = PearlPhysics.advance(start, DRAG, GRAVITY, PhysicsOrder.GRAVITY_FIRST);

        assertEquals(100 + 5 - GRAVITY, next.pos().y(), 1.0E-9D);
        assertEquals((5 - GRAVITY) * DRAG, next.motion().y(), 1.0E-9D);
        // Horizontal motion is unaffected by the ordering.
        assertEquals(990.0D, next.motion().x(), 1.0E-9D);
        assertEquals(1000.0D, next.pos().x(), 1.0E-9D);
    }

    @Test
    void horizontalSpeedDecaysGeometrically() {
        Step step = new Step(Vec3d.ZERO, new Vec3d(1000, 0, 0));
        for (int i = 0; i < 200; i++) {
            step = PearlPhysics.advance(step, DRAG, GRAVITY, PhysicsOrder.VANILLA);
        }
        assertEquals(1000 * Math.pow(DRAG, 200), step.motion().x(), 1.0E-6D);
    }

    @Test
    void totalHorizontalDistanceMatchesTheClosedForm() {
        Step step = new Step(Vec3d.ZERO, new Vec3d(1000, 0, 0));
        for (int i = 0; i < 20000; i++) {
            step = PearlPhysics.advance(step, DRAG, GRAVITY, PhysicsOrder.VANILLA);
        }
        // sum of 1000 * 0.99^n == 1000 / (1 - 0.99) == 100_000
        assertEquals(100_000.0D, step.pos().x(), 1.0D);
        assertEquals(100_000.0D, PearlPhysics.remainingHorizontalDistance(1000, DRAG), 1.0E-6D);
    }

    @Test
    void convergenceEstimateMatchesSimulation() {
        int predicted = PearlPhysics.ticksUntilHorizontalSpeedBelow(1000, DRAG, 16.0D);
        assertEquals(412, predicted);

        Step step = new Step(Vec3d.ZERO, new Vec3d(1000, 0, 0));
        int actual = 0;
        while (step.motion().horizontalLength() >= 16.0D) {
            step = PearlPhysics.advance(step, DRAG, GRAVITY, PhysicsOrder.VANILLA);
            actual++;
        }
        assertEquals(predicted, actual);
    }

    @Test
    void convergenceEstimateIsZeroForASlowPearl() {
        assertEquals(0, PearlPhysics.ticksUntilHorizontalSpeedBelow(1.5D, DRAG, 16.0D));
    }

    @Test
    void verticalMotionReachesTheExpectedTerminalSpeed() {
        Step vanilla = new Step(Vec3d.ZERO, Vec3d.ZERO);
        Step gravityFirst = new Step(Vec3d.ZERO, Vec3d.ZERO);
        for (int i = 0; i < 5000; i++) {
            vanilla = PearlPhysics.advance(vanilla, DRAG, GRAVITY, PhysicsOrder.VANILLA);
            gravityFirst = PearlPhysics.advance(gravityFirst, DRAG, GRAVITY, PhysicsOrder.GRAVITY_FIRST);
        }
        assertEquals(-3.00D, vanilla.motion().y(), 1.0E-6D);
        assertEquals(-2.97D, gravityFirst.motion().y(), 1.0E-6D);
    }

    @Test
    void chunkCoordinatesFloorTowardsNegativeInfinity() {
        assertEquals(0, new Vec3d(0.5, 0, 0.5).chunkX());
        assertEquals(-1, new Vec3d(-0.5, 0, -0.5).chunkX());
        assertEquals(-1, new Vec3d(-16.0, 0, -16.0).chunkZ());
        assertEquals(62, new Vec3d(1000.0, 0, 1000.0).chunkX());
    }

    @Test
    void positionsBeyondTheCoordinateLimitAreRejected() {
        assertTrue(new Vec3d(3.1E7, 64, 0).isOutside(2.9999984E7D));
        assertTrue(new Vec3d(Double.NaN, 64, 0).isOutside(2.9999984E7D));
        assertTrue(!new Vec3d(1.0E6, 64, 0).isOutside(2.9999984E7D));
    }
}
