package dev.sql1024.pearltrack.track;

/** Why tracking stopped. Recorded in the CSV and printed to the console. */
public enum EndReason {
    LANDED_OR_HIT,
    CONVERGED_DONE,
    TIMEOUT,
    HOLD_TIMEOUT,
    OUT_OF_BOUNDS,
    WORLD_UNLOADED,
    STOPPED_BY_COMMAND,
    PLUGIN_DISABLED,
    INTERNAL_ERROR
}
