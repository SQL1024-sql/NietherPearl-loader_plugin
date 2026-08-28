package dev.sql1024.pearltrack.compat;

import org.bukkit.World;

import java.util.Optional;

/**
 * Reads the one server setting that decides whether any of this is necessary.
 *
 * <p>Since 1.21.2 a thrown ender pearl is saved with its thrower, and Paper's
 * chunk system backs that by keeping an {@code ENDER_PEARL_TICKER} ticket at
 * entity-ticking level on whichever chunk the pearl is in — moved with it on
 * every section change, for every {@code ThrownEnderpearl} in the world. Two
 * consequences:
 *
 * <ul>
 *   <li>a pearl can no longer be parked in a chunk that is loaded but not
 *       entity-ticking, so a launcher that charges by weak loading cannot
 *       charge at all; and</li>
 *   <li>a pearl already keeps its own chunk ticking, so it does not stall the
 *       way it did before — it just waits on each chunk being generated,
 *       because the ticket is only placed once the pearl has arrived.</li>
 * </ul>
 *
 * <p>{@code misc.legacy-ender-pearl-behavior: true} in paper-world-defaults.yml
 * turns that ticket off and restores the pre-1.21.2 behaviour.
 *
 * <p>There is no API for it, so this reads it reflectively and reports nothing
 * rather than failing if the internals move.
 */
public final class PaperCompat {

    private PaperCompat() {
    }

    /**
     * @return true when the world runs the pre-1.21.2 behaviour and pearls do not
     *         hold their own chunk ticket; empty when it could not be read.
     */
    public static Optional<Boolean> legacyEnderPearlBehavior(World world) {
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Object config = level.getClass().getMethod("paperConfig").invoke(level);
            Object misc = config.getClass().getField("misc").get(config);
            Object value = misc.getClass().getField("legacyEnderPearlBehavior").get(misc);
            return value instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** A short phrase for command output. */
    public static String describe(World world) {
        return legacyEnderPearlBehavior(world)
                .map(legacy -> legacy
                        ? "legacy (no self-ticket; this plugin does the loading)"
                        : "self-ticketing (pearls hold their own entity-ticking chunk)")
                .orElse("unknown");
    }
}
