package dev.sql1024.pearltrack.physics;

/**
 * Immutable double vector. Deliberately free of any Bukkit type so that the
 * whole physics package can be unit tested without a running server.
 */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    public Vec3d add(Vec3d other) {
        return add(other.x, other.y, other.z);
    }

    public Vec3d scale(double factor) {
        return new Vec3d(x * factor, y * factor, z * factor);
    }

    /** Speed in the XZ plane, i.e. blocks travelled horizontally per tick. */
    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distanceTo(Vec3d other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Chunk X of the block this position sits in. */
    public int chunkX() {
        return (int) Math.floor(x) >> 4;
    }

    /** Chunk Z of the block this position sits in. */
    public int chunkZ() {
        return (int) Math.floor(z) >> 4;
    }

    /** True when any component has left the range where chunk maths stays meaningful. */
    public boolean isOutside(double limit) {
        return !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > limit || Math.abs(z) > limit;
    }

    public String format() {
        return String.format("%.2f, %.2f, %.2f", x, y, z);
    }
}
