package dev.sql1024.pearltrack.track;

import dev.sql1024.pearltrack.track.FlightGate.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FlightGateTest {

    private static final double GATE = 4.0D;
    private static final int GRACE = 3;

    private static Decision decide(boolean observed, boolean ticking, boolean moved,
                                   double hSpeed, int notTickingStreak) {
        return FlightGate.decide(observed, ticking, moved, hSpeed, GATE, notTickingStreak, GRACE);
    }

    /**
     * Two consecutive observations of a pearl charging in a cannon:
     * Motion (1000, 100, 1) then (1500, 150, 2), Pos (0, 0, 0) both times.
     * Momentum is accumulating, the pearl is going nowhere, and nothing may be
     * pinned no matter which way getTicksLived() reads.
     */
    @Test
    void aStationaryPearlIsNeverFlownWhateverItsMotion() {
        // Not ticking at all: the chunk is loaded but below entity-ticking.
        assertEquals(Decision.HOLD_KEEP_PINS, decide(true, false, false, 1500.0D, 1));
        assertEquals(Decision.HOLD_RELEASE, decide(true, false, false, 1500.0D, GRACE + 1));

        // Ticking, yet pinned in place by something else: still not ours to touch.
        assertEquals(Decision.HOLD_RELEASE, decide(true, true, false, 1500.0D, 0));
    }

    @Test
    void chargingIsNeverConfusedForFlightAtAnySpeed() {
        for (double speed : new double[]{0.0D, 4.0D, 1000.0D, 3559.18D, 1.0E6D}) {
            assertNotEquals(Decision.FLYING, decide(true, false, false, speed, 1),
                    "not ticking at " + speed + " b/t");
            assertNotEquals(Decision.FLYING, decide(true, true, false, speed, 0),
                    "ticking but stationary at " + speed + " b/t");
        }
    }

    @Test
    void aFastPearlThatIsTickingAndMovingIsUnderWay() {
        assertEquals(Decision.FLYING, decide(true, true, true, 1500.0D, 0));
    }

    @Test
    void anOrdinaryPearlIsIgnored() {
        assertEquals(Decision.HOLD_RELEASE, decide(true, true, true, 1.5D, 0));
    }

    @Test
    void leavingTheLoadedAreaCountsAsFlightOnlyWhenFast() {
        assertEquals(Decision.FLYING, decide(false, false, false, 1500.0D, 0));
        assertEquals(Decision.HOLD_RELEASE, decide(false, false, false, 1.5D, 0));
    }

    @Test
    void pinsSurviveTheGraceWindowThenGo() {
        for (int streak = 1; streak <= GRACE; streak++) {
            assertEquals(Decision.HOLD_KEEP_PINS, decide(true, false, true, 1500.0D, streak),
                    "streak " + streak);
        }
        assertEquals(Decision.HOLD_RELEASE, decide(true, false, true, 1500.0D, GRACE + 1));
    }

    @Test
    void aZeroGraceConfigReleasesImmediately() {
        assertEquals(Decision.HOLD_RELEASE,
                FlightGate.decide(true, false, false, 1500.0D, GATE, 1, 0));
    }
}
