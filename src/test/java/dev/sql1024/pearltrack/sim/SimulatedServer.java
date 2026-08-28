package dev.sql1024.pearltrack.sim;

import dev.sql1024.pearltrack.config.TicketMode;
import dev.sql1024.pearltrack.config.TrackConfig;
import dev.sql1024.pearltrack.log.FlightLogger;
import dev.sql1024.pearltrack.physics.PearlPhysics;
import dev.sql1024.pearltrack.physics.PhysicsOrder;
import dev.sql1024.pearltrack.physics.Step;
import dev.sql1024.pearltrack.physics.Vec3d;
import dev.sql1024.pearltrack.track.ChunkTicketManager;
import dev.sql1024.pearltrack.track.ForceLoadJournal;
import dev.sql1024.pearltrack.track.PearlTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the real tracker against a world that reproduces the one rule the whole
 * design rests on: <b>an entity only ticks while its chunk is entity-ticking</b>,
 * and a chunk is only entity-ticking if something holds a ticket on it and it
 * has finished loading.
 *
 * <p>This is not a Paper server — chunk generation, collisions and the ticket
 * level propagation are all modelled rather than real. What it does exercise is
 * every line of {@code PearlTracker}, {@code ChunkTicketManager} and
 * {@code FlightGate} against a world that freezes the pearl exactly when a real
 * one would.
 */
public final class SimulatedServer {

    public static final double DRAG = 0.99D;
    public static final double GRAVITY = 0.03D;

    private final UUID worldUid = UUID.randomUUID();
    private final World world;
    private final Plugin plugin;
    private final Entity entity;
    private final PersistentDataContainer pdc;

    public final PearlTracker tracker;
    public final ChunkTicketManager tickets;

    // --- pearl -----------------------------------------------------------
    public UUID pearlId = UUID.randomUUID();
    public Vec3d pos;
    public Vec3d motion;
    public int ticksLived;
    public boolean alive = true;
    public boolean noPhysics;

    /** While true the pearl gains momentum each tick without ever ticking. */
    public boolean charging;
    public Vec3d chargeGainPerTick = new Vec3d(5.0D, 0.5D, 0.0D);

    // --- chunk system ----------------------------------------------------
    /** Chunks something holds a ticket on. */
    private final Set<Long> ticketed = new HashSet<>();
    /** When each ticketed chunk was first ticketed, for the load delay. */
    private final Map<Long, Integer> ticketedAt = new HashMap<>();
    /** Chunks that are loaded regardless of tickets, e.g. around spawn or a launcher. */
    public final Set<Long> alwaysLoaded = new HashSet<>();
    /** Chunks that are entity-ticking regardless of tickets. */
    public final Set<Long> alwaysTicking = new HashSet<>();
    /** Ticks a freshly ticketed chunk needs before it is usable, i.e. generation latency. */
    public int loadDelayTicks;

    // --- observations ----------------------------------------------------
    public int tick;
    public int frozenTicks;
    public int maxPinned;
    public final Set<Long> everPinned = new HashSet<>();
    public final Set<Long> visitedChunks = new HashSet<>();

    public SimulatedServer(TrackConfig config, Vec3d startPos, Vec3d startMotion) {
        this.pos = startPos;
        this.motion = startMotion;

        this.pdc = proxy(PersistentDataContainer.class, this::handlePdc);
        this.entity = proxy(EnderPearl.class, this::handleEntity);
        this.world = proxy(World.class, this::handleWorld);
        this.plugin = proxy(Plugin.class, this::handlePlugin);

        installServer(proxy(Server.class, this::handleServer));

        ForceLoadJournal journal = new ForceLoadJournal(Path.of("build", "tmp", "sim.journal"),
                Logger.getLogger("sim"));
        this.tickets = new ChunkTicketManager(plugin, journal, config);
        FlightLogger flightLogger = new FlightLogger(plugin, config);
        this.tracker = new PearlTracker(plugin, tickets, flightLogger, config);
    }

    /**
     * {@code Bukkit.setServer} logs the build info, which is loaded through a
     * ServiceLoader that only a real server provides, so the field is set
     * directly. It is a plain private static on the classpath, not in a module.
     */
    private static void installServer(Server server) {
        if (Bukkit.getServer() != null) {
            return;
        }
        try {
            java.lang.reflect.Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("could not install the simulated server", ex);
        }
    }

    /** One Bukkit static per JVM, so the live world is swapped in per test. */
    private static SimulatedServer current;

    public SimulatedServer install() {
        current = this;
        return this;
    }

    public Entity entity() {
        return entity;
    }

    public World world() {
        return world;
    }

    // -------------------------------------------------------------- the loop

    /** One server tick, in the order a real one runs them. */
    public void runTick() {
        tick++;

        // 1. Scheduler heartbeat — before the worlds tick, which is the whole point.
        tracker.run();

        int pinned = tickets.globalCount();
        maxPinned = Math.max(maxPinned, pinned);

        // 2. Chunk system catches up with the tickets added above.
        //    (Modelled as a fixed latency; a real one queues and generates.)

        // 3. Entity phase.
        if (!alive) {
            return;
        }
        if (charging) {
            // A launcher: loaded but never entity-ticking, so no tick happens and
            // drag is never applied, while explosions keep adding momentum.
            if (!entityTicking(chunkOf(pos))) {
                motion = motion.add(chargeGainPerTick);
                return;
            }
            // Something raised the chunk to entity-ticking: the cannon has fired.
            charging = false;
        }
        if (!entityTicking(chunkOf(pos))) {
            frozenTicks++;
            return;
        }
        Step next = PearlPhysics.advance(new Step(pos, motion), DRAG, GRAVITY, PhysicsOrder.VANILLA);
        pos = next.pos();
        motion = next.motion();
        ticksLived++;
        visitedChunks.add(chunkOf(pos));
    }

    public void runTicks(int count) {
        for (int i = 0; i < count; i++) {
            runTick();
        }
    }

    // ------------------------------------------------------------ chunk model

    public static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public long chunkOf(Vec3d p) {
        return chunkKey(p.chunkX(), p.chunkZ());
    }

    private boolean loaded(long key) {
        if (alwaysLoaded.contains(key) || alwaysTicking.contains(key)) {
            return true;
        }
        Integer since = ticketedAt.get(key);
        return since != null && tick - since >= loadDelayTicks;
    }

    private boolean entityTicking(long key) {
        return alwaysTicking.contains(key) || (ticketed.contains(key) && loaded(key));
    }

    // --------------------------------------------------------------- proxies

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private Object handlePlugin(Object p, Method m, Object[] a) {
        return switch (m.getName()) {
            case "getName" -> "PearlTrack";
            case "namespace" -> "pearltrack";
            case "getLogger" -> quietLogger();
            case "getServer" -> Bukkit.getServer();
            case "isEnabled" -> true;
            case "toString" -> "PearlTrack(sim)";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> defaultValue(m);
        };
    }

    private Object handleServer(Object p, Method m, Object[] a) {
        return switch (m.getName()) {
            case "getWorld" -> current == null ? null : current.world;
            case "getWorlds" -> current == null ? List.of() : List.of(current.world);
            case "getLogger" -> quietLogger();
            case "getName" -> "SimulatedServer";
            case "getVersion", "getBukkitVersion" -> "sim";
            case "isPrimaryThread" -> true;
            case "toString" -> "SimulatedServer";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> defaultValue(m);
        };
    }

    private Object handleWorld(Object p, Method m, Object[] a) {
        switch (m.getName()) {
            case "getUID":
                return worldUid;
            case "getName":
                return "sim";
            case "getEntity":
                // Only reachable while its chunk is loaded, exactly like the real lookup.
                return alive && loaded(chunkOf(pos)) && pearlId.equals(a[0]) ? entity : null;
            case "addPluginChunkTicket": {
                long key = chunkKey((Integer) a[0], (Integer) a[1]);
                if (ticketed.add(key)) {
                    ticketedAt.put(key, tick);
                }
                everPinned.add(key);
                return true;
            }
            case "removePluginChunkTicket": {
                long key = chunkKey((Integer) a[0], (Integer) a[1]);
                ticketed.remove(key);
                ticketedAt.remove(key);
                return true;
            }
            case "removePluginChunkTickets":
                ticketed.clear();
                ticketedAt.clear();
                return null;
            case "setChunkForceLoaded": {
                long key = chunkKey((Integer) a[0], (Integer) a[1]);
                if ((Boolean) a[2]) {
                    if (ticketed.add(key)) {
                        ticketedAt.put(key, tick);
                    }
                    everPinned.add(key);
                } else {
                    ticketed.remove(key);
                    ticketedAt.remove(key);
                }
                return null;
            }
            case "isChunkLoaded":
                return a.length == 2 && loaded(chunkKey((Integer) a[0], (Integer) a[1]));
            case "getChunkAtAsyncUrgently":
            case "getChunkAtAsync":
                return CompletableFuture.completedFuture(null);
            case "getNearbyEntities":
                return alive ? List.of(entity) : List.of();
            case "toString":
                return "sim-world";
            case "hashCode":
                return System.identityHashCode(p);
            case "equals":
                return p == a[0];
            default:
                return defaultValue(m);
        }
    }

    private Object handleEntity(Object p, Method m, Object[] a) {
        switch (m.getName()) {
            case "getUniqueId":
                return pearlId;
            case "isValid":
                return alive;
            case "isDead":
                return !alive;
            case "getTicksLived":
                return ticksLived;
            case "getLocation":
                return new Location(world, pos.x(), pos.y(), pos.z());
            case "getVelocity":
                return new Vector(motion.x(), motion.y(), motion.z());
            case "getPersistentDataContainer":
                return pdc;
            case "hasNoPhysics":
                return noPhysics;
            case "setNoPhysics":
                noPhysics = (Boolean) a[0];
                return null;
            case "getWorld":
                return world;
            case "toString":
                return "sim-pearl";
            case "hashCode":
                return System.identityHashCode(p);
            case "equals":
                return p == a[0];
            default:
                return defaultValue(m);
        }
    }

    private Object handlePdc(Object p, Method m, Object[] a) {
        return switch (m.getName()) {
            case "has" -> false;
            case "isEmpty" -> true;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "sim-pdc";
            default -> defaultValue(m);
        };
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("PearlTrack-sim");
        logger.setLevel(Level.WARNING);
        return logger;
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive()) {
            return null;
        }
        if (r == boolean.class) {
            return false;
        }
        if (r == void.class) {
            return null;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == double.class) {
            return 0.0D;
        }
        if (r == float.class) {
            return 0.0F;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        return (char) 0;
    }

    // ---------------------------------------------------------------- config

    public static TrackConfig config(int lookahead, int forceloadRadius, boolean disableCollision) {
        return new TrackConfig(
                DRAG, GRAVITY, PhysicsOrder.VANILLA,
                true, 4.0D, 3, 4, 60000, 24000, 200, 0, 3, 1.0E-6D,
                lookahead, forceloadRadius, 16.0D, 600, 2, disableCollision, 2.9999984E7D,
                TicketMode.PLUGIN_TICKET, 256, 8, 1, 100, true,
                false, false, "flights", false, 20);
    }
}
