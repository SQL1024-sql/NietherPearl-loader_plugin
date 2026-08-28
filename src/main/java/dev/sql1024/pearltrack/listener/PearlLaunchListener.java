package dev.sql1024.pearltrack.listener;

import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.track.EndReason;
import dev.sql1024.pearltrack.track.PearlTracker;
import dev.sql1024.pearltrack.track.TrackedPearl;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.function.Supplier;

/** Picks the pearls worth tracking, and notices when one finally lands. */
public final class PearlLaunchListener implements Listener {

    private final PearlTracker tracker;
    private final Supplier<TrackConfig> config;

    public PearlLaunchListener(PearlTracker tracker, Supplier<TrackConfig> config) {
        this.tracker = tracker;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        TrackConfig cfg = config.get();

        ProjectileSource source = pearl.getShooter();
        Player shooter = source instanceof Player player ? player : null;
        boolean manual = shooter != null && tracker.hasManualRequest(shooter.getUniqueId());

        Vector velocity = pearl.getVelocity();
        double hSpeed = Math.hypot(velocity.getX(), velocity.getZ());

        String name = shooter != null ? shooter.getName() : describe(source);

        if (!manual && (!cfg.autoTrackAll() || hSpeed < cfg.onlyIfSpeedAbove())) {
            if (cfg.autoTrackAll()) {
                // Its motion may still be overwritten later this tick.
                tracker.watchForLateSpeed(pearl, name);
            }
            return;
        }
        if (tracker.isFull()) {
            if (shooter != null) {
                shooter.sendMessage(Component.text(
                        "[PearlTrack] Already tracking " + cfg.maxConcurrent() + " pearls; this one is not tracked.",
                        NamedTextColor.RED));
            }
            return;
        }
        if (manual) {
            tracker.consumeManualRequest(shooter.getUniqueId());
        }

        TrackedPearl tracked = tracker.beginTracking(pearl, name);

        if (shooter != null) {
            shooter.sendMessage(Component.text(String.format(Locale.ROOT,
                    "[PearlTrack] Tracking %s — %.1f blocks/tick, ~%.0f blocks of flight ahead.",
                    tracked.uuid().toString().substring(0, 8), hSpeed,
                    PearlPhysics.remainingHorizontalDistance(hSpeed, cfg.drag())),
                    NamedTextColor.AQUA));
        }
    }

    /**
     * A pearl that hits something is removed straight away, which from the
     * tracker's point of view is indistinguishable from flying into an unloaded
     * chunk. This event is what tells the two apart.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile instanceof EnderPearl)) {
            return;
        }
        TrackedPearl tracked = tracker.get(projectile.getUniqueId());
        if (tracked != null) {
            tracker.stop(tracked, EndReason.LANDED_OR_HIT);
        }
    }

    private String describe(ProjectileSource source) {
        if (source == null) {
            return "unknown";
        }
        if (source instanceof org.bukkit.entity.Entity entity) {
            return entity.getType().name();
        }
        return source.getClass().getSimpleName();
    }
}
