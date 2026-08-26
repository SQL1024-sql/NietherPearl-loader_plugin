package io.github.sql1024.netherpearlloader;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
 */
final class PearlScanTask extends BukkitRunnable {

    private final NetherPearlLoaderPlugin plugin;
    private final ForceLoadManager manager;
    private final LoaderConfig config;

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
                alive.add(pearlId);

                final Vector velocity = pearl.getVelocity();
                final Set<Long> desired = PearlPathPredictor.chunksFor(pearl.getLocation(), velocity, this.config);
                final ForceLoadManager.Delta delta = this.manager.sync(pearlId, world, desired);

                if (this.config.debug() && delta.changed()) {
                    this.plugin.getLogger().info(() -> String.format(
                            "[%s] pearl %s @ %d,%d,%d chunk %s motion=(%.2f, %.2f, %.2f) |%.1f| 區塊 %d (+%d/-%d)",
                            world.getName(), shortId(pearlId),
                            pearl.getLocation().getBlockX(), pearl.getLocation().getBlockY(),
                            pearl.getLocation().getBlockZ(),
                            ChunkKey.toString(ChunkKey.of(Math.floorDiv(pearl.getLocation().getBlockX(), 16),
                                    Math.floorDiv(pearl.getLocation().getBlockZ(), 16))),
                            velocity.getX(), velocity.getY(), velocity.getZ(), velocity.length(),
                            desired.size(), delta.added(), delta.removed()));
                }
            }
        }

        // 沒出現在這次掃描裡的珍珠 = 已經消失(或飛出我們負責的世界),整批解除它的佔用
        final int released = this.manager.retainOnly(alive);
        if (this.config.debug() && released > 0) {
            this.plugin.getLogger().info(released + " 顆珍珠消失,已解除其佔用的區塊;"
                    + "目前追蹤 " + this.manager.trackedPearls() + " 顆 / "
                    + this.manager.ownedChunks() + " 個區塊");
        }

        this.manager.tickJournal();
    }

    private static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }
}
