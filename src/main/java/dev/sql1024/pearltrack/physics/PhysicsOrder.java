package dev.sql1024.pearltrack.physics;

/**
 * The order in which a throwable projectile applies movement, drag and gravity
 * inside a single tick. Horizontal motion is identical either way; only the
 * vertical term differs (terminal fall speed -3.00 vs -2.97 blocks/tick with
 * the vanilla 0.99 / 0.03 coefficients).
 */
public enum PhysicsOrder {
    /** Vanilla ThrowableProjectile: move by delta, multiply by drag, then subtract gravity. */
    VANILLA,
    /** Subtract gravity first, then move, then multiply by drag. */
    GRAVITY_FIRST
}
