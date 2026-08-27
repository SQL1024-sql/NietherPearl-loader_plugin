package io.github.sql1024.netherpearlloader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * 定時掃描器:每 {@code interval-ticks} 跑一次,把所有(地獄的)飛行中珍珠的佔用區塊同步一次。
 *
 * <p>掃描而不是用事件驅動,是因為珍珠飛行途中沒有「每 tick 移動」的 Bukkit 事件可以掛;
 * 珍珠消失(命中方塊/實體、傳送玩家、飛出世界、被 /kill)也一律由「這次掃描沒看到它」
 * 這件事來判定,不用逐一處理各種消失原因。
 *
 * <h2>蓄力珍珠 vs 飛行珍珠</h2>
 * 這兩種珍珠對區塊等級的需求是相反的(32 級凍住蓄力 vs 31 級 entity ticking 飛行),
 * 而且從 API 看長得一模一樣:都有巨大 Motion,都靜止不動(飛行中的珍珠在等區塊載入時
 * 同樣是靜止的)。所以用兩層保護把它們分開:
 * <ol>
 *   <li><b>炮膛區域</b>({@code protected-chunks}):列出的區塊永遠不 forceload,裡面的
 *       珍珠永遠不追蹤。確定性、不受 reload 影響,是主要手段。</li>
 *   <li><b>靜止偵測</b>({@code skip-idle-pearls}):珍珠要「動過一次」才會被接管。蓄力中的
 *       珍珠因為不被 tick 所以永遠不動,自然不會被碰;你放行它的那一刻它動了第一格,
 *       插件才開始鋪路。零設定,但插件 reload 會清掉這份記憶(見 {@link #flights})。</li>
 * </ol>
 */
final class PearlScanTask extends BukkitRunnable {

    /** 超過這個位移(方塊)才算「動了」,吸收浮點誤差。 */
    private static final double MOVEMENT_EPSILON = 1.0E-6D;

    private final NetherPearlLoaderPlugin plugin;
    private final ForceLoadManager manager;
    private final LoaderConfig config;

    /**
     * 每顆珍珠上次看到的位置,以及它是否已經確定在飛。
     *
     * <p>「在飛」是黏著的:一旦動過就永遠算在飛,直到珍珠消失。不能用「最近 N tick 有沒有動」
     * 來判斷,因為飛行中的珍珠等區塊時就是不動的,那樣會把它自己丟掉。
     *
     * <p>這份記憶活在 task 上,插件 reload 會重來。代價是:reload 當下正卡在半路等區塊的
     * 珍珠會被當成陌生珍珠,要等它再動一格才會被重新接管。真的在意就用 protected-chunks。
     */
    private final Map<UUID, Flight> flights = new HashMap<>();

    PearlScanTask(final NetherPearlLoaderPlugin plugin, final ForceLoadManager manager, final LoaderConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
    }

    @Override
    public void run() {
        final Set<UUID> alive = new HashSet<>();

        for (final World world : this.plugin.getServer().getWorlds()) {
            // ★ 硬性要求:nether-only 開著時,非地獄世界(主世界、終界)連看都不看。
            //   主世界的珍珠炮蓄力靠「珍珠停在 32 級弱載入區塊上不被 tick」來累積動量,
            //   只要在主世界對珍珠 forceload 就會讓它繼續飛,整個機制就毀了。
            if (this.config.netherOnly() && world.getEnvironment() != World.Environment.NETHER) {
                continue;
            }

            for (final EnderPearl pearl : world.getEntitiesByClass(EnderPearl.class)) {
                if (pearl.isDead() || !pearl.isValid()) {
                    continue; // 這一 tick 內已經失效,交給下面的 retainOnly 收尾
                }
                final UUID pearlId = pearl.getUniqueId();
                final Location location = pearl.getLocation();
                final int chunkX = Math.floorDiv(location.getBlockX(), 16);
                final int chunkZ = Math.floorDiv(location.getBlockZ(), 16);

                // 1. 炮膛裡的珍珠:完全不碰(連 alive 都不加,原有的佔用會被 retainOnly 解除)
                if (this.config.isProtected(world.getName(), chunkX, chunkZ)) {
                    this.flights.remove(pearlId);
                    continue;
                }

                // 2. 還沒動過的珍珠:可能是蓄力中,不介入
                if (!isInFlight(pearlId, location)) {
                    alive.add(pearlId); // 留著它的記錄,但不佔用任何區塊
                    continue;
                }

                alive.add(pearlId);

                final Vector velocity = pearl.getVelocity();
                final Set<Long> desired = withoutProtected(
                        PearlPathPredictor.chunksFor(location, velocity, this.config), world);
                final ForceLoadManager.Delta delta = this.manager.sync(pearlId, world, desired);

                if (this.config.debug() && delta.changed()) {
                    this.plugin.getLogger().info(() -> String.format(
                            "[%s] pearl %s @ %d,%d,%d chunk %s motion=(%.2f, %.2f, %.2f) |%.1f| 區塊 %d (+%d/-%d)",
                            world.getName(), shortId(pearlId),
                            location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                            ChunkKey.toString(ChunkKey.of(chunkX, chunkZ)),
                            velocity.getX(), velocity.getY(), velocity.getZ(), velocity.length(),
                            desired.size(), delta.added(), delta.removed()));
                }
            }
        }

        // 沒出現在這次掃描裡的珍珠 = 已經消失(或飛出我們負責的世界),整批解除它的佔用
        final int released = this.manager.retainOnly(alive);
        this.flights.keySet().retainAll(alive);
        if (this.config.debug() && released > 0) {
            this.plugin.getLogger().info(released + " 顆珍珠消失,已解除其佔用的區塊;"
                    + "目前追蹤 " + this.manager.trackedPearls() + " 顆 / "
                    + this.manager.ownedChunks() + " 個區塊");
        }

        this.manager.tickJournal();
    }

    /**
     * 這顆珍珠是不是已經確定在飛。第一次看到的珍珠只記位置、不介入;之後只要位置變過一次
     * 就永久標記為飛行中(黏著,見 {@link #flights})。
     */
    private boolean isInFlight(final UUID pearlId, final Location location) {
        if (!this.config.skipIdlePearls()) {
            return true; // 關掉偵測 = 一律接管
        }
        final Flight known = this.flights.get(pearlId);
        if (known == null) {
            this.flights.put(pearlId, new Flight(location.getX(), location.getY(), location.getZ()));
            return false;
        }
        if (known.inFlight) {
            return true;
        }
        if (known.movedFrom(location)) {
            known.inFlight = true;
            if (this.config.debug()) {
                this.plugin.getLogger().info("pearl " + shortId(pearlId) + " 動了,開始接管("
                        + String.format("%.1f, %.1f, %.1f", location.getX(), location.getY(), location.getZ()) + ")");
            }
            return true;
        }
        return false;
    }

    /** 把落在炮膛範圍裡的區塊從要 forceload 的清單剔除。 */
    private Set<Long> withoutProtected(final Set<Long> chunks, final World world) {
        if (this.config.protectedRegions().isEmpty()) {
            return chunks;
        }
        final Set<Long> filtered = new LinkedHashSet<>(chunks.size());
        for (final Iterator<Long> it = chunks.iterator(); it.hasNext(); ) {
            final long key = it.next();
            if (!this.config.isProtected(world.getName(), ChunkKey.x(key), ChunkKey.z(key))) {
                filtered.add(key);
            }
        }
        return filtered;
    }

    private static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }

    /** 單顆珍珠的移動記錄。 */
    private static final class Flight {
        private double x;
        private double y;
        private double z;
        private boolean inFlight;

        private Flight(final double x, final double y, final double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private boolean movedFrom(final Location location) {
            final boolean moved = Math.abs(location.getX() - this.x) > MOVEMENT_EPSILON
                    || Math.abs(location.getY() - this.y) > MOVEMENT_EPSILON
                    || Math.abs(location.getZ() - this.z) > MOVEMENT_EPSILON;
            this.x = location.getX();
            this.y = location.getY();
            this.z = location.getZ();
            return moved;
        }
    }
}
