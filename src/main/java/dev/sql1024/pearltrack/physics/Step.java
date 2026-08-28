package dev.sql1024.pearltrack.physics;

/** A position paired with the delta movement that will be applied to it next tick. */
public record Step(Vec3d pos, Vec3d motion) {
}
