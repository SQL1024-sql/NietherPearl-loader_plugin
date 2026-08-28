package dev.sql1024.pearltrack.command;

import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.log.FlightLogger;
import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.physics.Vec3d;
import dev.sql1024.pearltrack.track.ChunkTicketManager;
import dev.sql1024.pearltrack.track.EndReason;
import dev.sql1024.pearltrack.track.PearlTracker;
import dev.sql1024.pearltrack.track.TrackedPearl;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** {@code /pearltrack status|list|next|stop|reload}. */
public final class PearlTrackCommand implements BasicCommand {

    private static final NamedTextColor LABEL = NamedTextColor.GRAY;
    private static final NamedTextColor VALUE = NamedTextColor.WHITE;
    private static final NamedTextColor ACCENT = NamedTextColor.AQUA;

    private final PearlTracker tracker;
    private final ChunkTicketManager tickets;
    private final FlightLogger flightLogger;
    private final Supplier<TrackConfig> config;
    private final Runnable reloadAction;

    public PearlTrackCommand(PearlTracker tracker, ChunkTicketManager tickets, FlightLogger flightLogger,
                             Supplier<TrackConfig> config, Runnable reloadAction) {
        this.tracker = tracker;
        this.tickets = tickets;
        this.flightLogger = flightLogger;
        this.config = config;
        this.reloadAction = reloadAction;
    }

    @Override
    public String permission() {
        return "pearltrack.use";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status" -> status(sender, args.length > 1 ? args[1] : null);
            case "list" -> list(sender);
            case "next" -> next(sender);
            case "stop" -> stop(sender, args.length > 1 ? args[1] : null);
            case "reload" -> {
                reloadAction.run();
                sender.sendMessage(Component.text("[PearlTrack] config.yml reloaded.", NamedTextColor.GREEN));
            }
            default -> usage(sender);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "list", "next", "stop", "reload").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("stop") || args[0].equalsIgnoreCase("status"))) {
            List<String> out = new ArrayList<>();
            if (args[0].equalsIgnoreCase("stop")) {
                out.add("all");
            }
            for (TrackedPearl pearl : tracker.tracked()) {
                out.add(pearl.uuid().toString().substring(0, 8));
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return out.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("/pearltrack status [id]", ACCENT)
                .append(Component.text("  last known / predicted position of a tracked pearl", LABEL)));
        sender.sendMessage(Component.text("/pearltrack list", ACCENT)
                .append(Component.text("  everything currently tracked", LABEL)));
        sender.sendMessage(Component.text("/pearltrack next", ACCENT)
                .append(Component.text("  track the next pearl you throw, whatever its speed", LABEL)));
        sender.sendMessage(Component.text("/pearltrack stop <id|all>", ACCENT)
                .append(Component.text("  stop tracking and release its chunks", LABEL)));
        sender.sendMessage(Component.text("/pearltrack reload", ACCENT)
                .append(Component.text("  re-read config.yml", LABEL)));
    }

    private void next(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[PearlTrack] Only a player can request this.", NamedTextColor.RED));
            return;
        }
        tracker.requestManualTrack(player.getUniqueId());
        sender.sendMessage(Component.text(
                "[PearlTrack] The next ender pearl you throw will be tracked.", NamedTextColor.GREEN));
    }

    private void list(CommandSender sender) {
        Collection<TrackedPearl> all = tracker.tracked();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("[PearlTrack] Nothing is being tracked.", LABEL));
            return;
        }
        sender.sendMessage(Component.text("[PearlTrack] " + all.size() + " tracked, "
                + tickets.globalCount() + "/" + config.get().maxForcedChunks() + " chunks pinned:", ACCENT));
        for (TrackedPearl pearl : all) {
            sender.sendMessage(Component.text(String.format(Locale.ROOT,
                    "  %s  tick %d  (%s)  %.1f b/t  %s",
                    pearl.uuid().toString().substring(0, 8), pearl.tick(), pearl.pos().format(),
                    pearl.horizontalSpeed(), pearl.converged() ? "converged" : "in flight"), VALUE));
        }
    }

    private void stop(CommandSender sender, String id) {
        if (id == null) {
            sender.sendMessage(Component.text("[PearlTrack] Usage: /pearltrack stop <id|all>", NamedTextColor.RED));
            return;
        }
        if (id.equalsIgnoreCase("all")) {
            int count = tracker.stopAll(EndReason.STOPPED_BY_COMMAND);
            sender.sendMessage(Component.text("[PearlTrack] Stopped " + count + " pearl(s).", NamedTextColor.GREEN));
            return;
        }
        TrackedPearl pearl = find(id);
        if (pearl == null) {
            sender.sendMessage(Component.text("[PearlTrack] No tracked pearl matches '" + id + "'.", NamedTextColor.RED));
            return;
        }
        tracker.stop(pearl, EndReason.STOPPED_BY_COMMAND);
        sender.sendMessage(Component.text("[PearlTrack] Stopped " + pearl.uuid() + ".", NamedTextColor.GREEN));
    }

    private void status(CommandSender sender, String id) {
        TrackedPearl pearl;
        if (id != null) {
            pearl = find(id);
            if (pearl == null) {
                sender.sendMessage(Component.text("[PearlTrack] No tracked pearl matches '" + id + "'.", NamedTextColor.RED));
                return;
            }
        } else {
            Collection<TrackedPearl> all = tracker.tracked();
            if (all.isEmpty()) {
                sender.sendMessage(Component.text("[PearlTrack] Nothing is being tracked.", LABEL));
                return;
            }
            if (all.size() > 1) {
                list(sender);
                sender.sendMessage(Component.text("Pass an id for the full report.", LABEL));
                return;
            }
            pearl = all.iterator().next();
        }
        report(sender, pearl);
    }

    private void report(CommandSender sender, TrackedPearl pearl) {
        TrackConfig cfg = config.get();
        Vec3d pos = pearl.pos();
        Vec3d motion = pearl.motion();
        double hSpeed = pearl.horizontalSpeed();

        sender.sendMessage(Component.text("── PearlTrack " + pearl.uuid(), ACCENT));
        row(sender, "world / thrower", pearl.worldName() + " / " + pearl.shooter());
        row(sender, "ticks flown", pearl.tick() + "  (" + String.format(Locale.ROOT, "%.1f", pearl.tick() / 20.0D) + "s)");
        row(sender, "source this tick", pearl.lastWasReal() ? "REAL (entity is loaded)" : "PREDICTED");
        row(sender, "position", "(" + pos.format() + ")  chunk " + pos.chunkX() + "," + pos.chunkZ());
        row(sender, "predicted next tick", "(" + pearl.predictedNext().format() + ")  chunk "
                + pearl.predictedNext().chunkX() + "," + pearl.predictedNext().chunkZ());
        row(sender, "motion", "(" + motion.format() + ")  horizontal "
                + String.format(Locale.ROOT, "%.3f", hSpeed) + " b/t");

        if (pearl.lastRealTick() >= 0) {
            row(sender, "last real fix", "tick " + pearl.lastRealTick() + " ("
                    + (pearl.tick() - pearl.lastRealTick()) + " ticks ago) at (" + pearl.lastRealPos().format() + ")");
        } else {
            row(sender, "last real fix", "none since launch");
        }
        row(sender, "corrections", pearl.corrections() + "   drift last/avg/max "
                + String.format(Locale.ROOT, "%.4f / %.4f / %.4f",
                pearl.lastDrift(), pearl.averageDrift(), pearl.maxDrift()));
        row(sender, "distance flown", String.format(Locale.ROOT, "%.1f blocks from (%s)",
                pearl.travelled(), pearl.launchPos().format()));
        row(sender, "chunks pinned", tickets.countFor(pearl.uuid()) + " for this pearl, "
                + tickets.globalCount() + "/" + cfg.maxForcedChunks() + " total");

        if (pearl.converged()) {
            row(sender, "converged", "tick " + pearl.convergedAtTick() + " at ("
                    + pearl.convergencePoint().format() + ")");
            row(sender, "releasing in", (cfg.keepTicksAfterConvergence()
                    - (pearl.tick() - pearl.convergedAtTick())) + " ticks");
        } else {
            int ticksLeft = PearlPhysics.ticksUntilHorizontalSpeedBelow(
                    hSpeed, cfg.drag(), cfg.convergenceThreshold());
            row(sender, "converges in", ticksLeft == Integer.MAX_VALUE ? "never at this drag"
                    : ticksLeft + " ticks (" + String.format(Locale.ROOT, "%.1f", ticksLeft / 20.0D) + "s)");
            row(sender, "flight left", String.format(Locale.ROOT, "%.0f blocks horizontally",
                    PearlPhysics.remainingHorizontalDistance(hSpeed, cfg.drag())));
        }

        String file = flightLogger.fileNameOf(pearl.uuid());
        if (file != null) {
            row(sender, "log", cfg.csvDirectory() + "/" + file);
        }
    }

    private void row(CommandSender sender, String label, String value) {
        sender.sendMessage(Component.text(String.format("%-20s", label) + " ", LABEL)
                .append(Component.text(value, VALUE)));
    }

    private TrackedPearl find(String id) {
        String needle = id.toLowerCase(Locale.ROOT);
        for (TrackedPearl pearl : tracker.tracked()) {
            if (pearl.uuid().toString().toLowerCase(Locale.ROOT).startsWith(needle)) {
                return pearl;
            }
        }
        return null;
    }
}
