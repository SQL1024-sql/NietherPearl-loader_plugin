package dev.sql1024.pearltrack.log;

/** The kinds of rows that appear in a flight CSV. */
public enum FlightEvent {
    /** The pearl was thrown or adopted and picked up for tracking. */
    LAUNCH,
    /**
     * The pearl is loaded but not ticking, so its chunk is not entity-ticking.
     * Momentum can be accumulating without ever being spent; nothing may be
     * pinned in this state.
     */
    HOLDING,
    /** The pearl started ticking, or left the loaded area: it is under way. */
    FIRED,
    /** The entity was found in a loaded chunk; position and motion are ground truth. */
    REAL,
    /** The entity was not reachable this tick; position and motion come from the model. */
    PREDICTED,
    /** The entity was real last tick and has now flown into unloaded terrain. */
    LOST,
    /** Horizontal speed dropped below the convergence threshold. */
    CONVERGED,
    /** Tracking stopped. */
    END
}
