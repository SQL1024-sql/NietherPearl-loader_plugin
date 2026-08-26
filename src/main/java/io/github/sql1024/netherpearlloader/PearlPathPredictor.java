package io.github.sql1024.netherpearlloader;

import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * 算出「這顆珍珠這一 tick 需要哪些區塊」。
 *
 * <h2>實測到的行為(Paper 26.1.2,地獄 y=200)</h2>
 * <pre>
 * T1  x=     0.5  dx=      0.0  vx=1750.000  chunk=0,0    forced=true
 * T2  x=  1733.0  dx=   1732.5  vx=1732.500  chunk=108,0   ← 一 tick 穿過 108 個未生成區塊
 * T3~T6         dx=0(凍結,vx 完全不變)
 * T7  x=  3448.2  dx=   1715.2  vx=1715.175  chunk=215,0
 * </pre>
 * 三個結論,直接決定這個類別怎麼寫:
 * <ol>
 *   <li><b>沒有速度上限</b>,珍珠一個 tick 真的能位移 1732 格,沿途未載入的區塊不會擋它,
 *       也不會截斷它的 Motion。</li>
 *   <li><b>沿途區塊不需要載入</b>。珍珠只需要「它停下來的那個區塊」是 31 級(entity ticking),
 *       下一 tick 才會被 tick 到、才能繼續飛。</li>
 *   <li><b>drag 只在被 tick 的那一下作用</b>(1750 → 1732.5 → 1715.175,每次剛好 ×0.99),
 *       凍結多久都不消耗動量。所以卡頓只影響「飛多久」,不影響「飛到哪」。</li>
 * </ol>
 *
 * <h2>兩種鋪法</h2>
 * {@link PathMode#CORRIDOR} 沿路線整條鋪,成本與速度成正比(1750 格/tick 要 327 區塊/tick,
 * 不可能);{@link PathMode#LANDING} 只鋪未來每個 tick 的落點,成本 = ticks × (2r+1)²,
 * <b>與速度無關</b>。{@link PathMode#AUTO} 會在走廊塞不進 {@code max-chunks-per-pearl} 時
 * 自動切成落點模式。
 *
 * <h2>簡化的彈道模型</h2>
 * 原版終界珍珠每 tick:先依 Motion 位移,再乘 0.99 空氣阻力,y 再減 0.03 重力。我們只需要
 * chunk 的 X/Z,所以重力(只影響 y)可以忽略,只保留水平阻力。碰撞、水中阻力(0.8)、
 * 傳送門都不模擬——猜錯頂多多鋪或少鋪幾個區塊,下一 tick 的差集運算會修正。
 */
final class PearlPathPredictor {

    /** 原版投擲物每 tick 的水平阻力係數。 */
    private static final double AIR_DRAG = 0.99D;

    /** 走廊模式沿路線取樣的間隔(方塊)。半個區塊寬,確保相鄰取樣點的區塊座標最多差 1。 */
    private static final double SAMPLE_STEP_BLOCKS = 8.0D;

    private PearlPathPredictor() {
    }

    /**
     * @param location 珍珠目前位置
     * @param velocity 珍珠目前的 Motion 向量(方塊/tick)
     * @return 這一 tick 要 forceload 的區塊 key 集合;順序由近而遠,被上限截斷時保留的是
     *         離珍珠最近、最急著要的那一段
     */
    static Set<Long> chunksFor(final Location location, final Vector velocity, final LoaderConfig config) {
        final Set<Long> chunks = new LinkedHashSet<>();
        final int cap = config.maxChunksPerPearl();

        final int chunkX = Math.floorDiv(location.getBlockX(), 16);
        final int chunkZ = Math.floorDiv(location.getBlockZ(), 16);

        // 珍珠當下所在的區塊一定要鋪:它現在很可能正凍在一個 32 級(loaded 但不 tick)的
        // 區塊裡,把它升成 31 級才會重新開始飛。
        if (!addSquare(chunks, chunkX, chunkZ, config.radius(), cap) || !config.predictEnabled()) {
            return chunks;
        }

        if (resolveMode(velocity, config) == PathMode.LANDING) {
            collectLandings(chunks, location, velocity, config, cap);
        } else {
            collectCorridor(chunks, location, velocity, config, cap, chunkX, chunkZ);
        }
        return chunks;
    }

    /** AUTO 時估一下走廊要多少區塊,塞不進 cap 就改鋪落點。 */
    private static PathMode resolveMode(final Vector velocity, final LoaderConfig config) {
        if (config.predictMode() != PathMode.AUTO) {
            return config.predictMode();
        }
        double vx = velocity.getX();
        double vz = velocity.getZ();
        double cells = 0.0D;
        for (int tick = 0; tick < config.predictTicks(); tick++) {
            cells += (Math.abs(vx) + Math.abs(vz)) / 16.0D;
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
        }
        final int side = 2 * config.radius() + 1;
        return (cells + 1) * side > config.maxChunksPerPearl() ? PathMode.LANDING : PathMode.CORRIDOR;
    }

    /**
     * 只鋪未來每個 tick 的落點。這是高速時唯一可行的做法:成本固定 = ticks × (2r+1)²,
     * 珍珠飛 1750 格/tick 還是 17 格/tick 都一樣貴。
     */
    private static void collectLandings(final Set<Long> out, final Location location,
                                        final Vector velocity, final LoaderConfig config, final int cap) {
        double x = location.getX();
        double z = location.getZ();
        double vx = velocity.getX();
        double vz = velocity.getZ();

        for (int tick = 0; tick < config.predictTicks(); tick++) {
            x += vx;
            z += vz;
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
            final int landingX = Math.floorDiv((int) Math.floor(x), 16);
            final int landingZ = Math.floorDiv((int) Math.floor(z), 16);
            if (!addSquare(out, landingX, landingZ, config.radius(), cap)) {
                return;
            }
        }
    }

    /** 沿行進路線整條鋪(低速適用,沿途碰撞判定才會正常運作)。 */
    private static void collectCorridor(final Set<Long> out, final Location location, final Vector velocity,
                                        final LoaderConfig config, final int cap, int chunkX, int chunkZ) {
        double x = location.getX();
        double z = location.getZ();
        double vx = velocity.getX();
        double vz = velocity.getZ();

        for (int tick = 0; tick < config.predictTicks(); tick++) {
            final double distance = Math.hypot(vx, vz);
            if (distance < 1.0E-3D) {
                break; // 幾乎不動(卡住或已落地),沒有路要鋪
            }
            final int steps = (int) Math.ceil(distance / SAMPLE_STEP_BLOCKS);
            for (int step = 1; step <= steps; step++) {
                final double t = (double) step / steps;
                final int sampleX = Math.floorDiv((int) Math.floor(x + vx * t), 16);
                final int sampleZ = Math.floorDiv((int) Math.floor(z + vz * t), 16);
                if (sampleX == chunkX && sampleZ == chunkZ) {
                    continue; // 還在同一個區塊,不必重算
                }
                chunkX = sampleX;
                chunkZ = sampleZ;
                if (!addSquare(out, sampleX, sampleZ, config.radius(), cap)) {
                    return; // 撞到上限,後面的路就不鋪了
                }
            }
            x += vx;
            z += vz;
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
        }
    }

    /**
     * 以 (centerX, centerZ) 為中心加入 (2r+1)^2 個區塊。
     *
     * @return 還有沒有額度可以繼續加(false = 已達上限)
     */
    private static boolean addSquare(final Set<Long> out, final int centerX, final int centerZ,
                                     final int radius, final int cap) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (out.size() >= cap) {
                    return false;
                }
                out.add(ChunkKey.of(centerX + dx, centerZ + dz));
            }
        }
        return out.size() < cap;
    }
}
