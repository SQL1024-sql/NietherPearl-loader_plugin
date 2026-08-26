package io.github.sql1024.netherpearlloader;

import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * 算出「這顆珍珠這一 tick 需要哪些區塊」。
 *
 * <h2>為什麼需要預測</h2>
 * 純反應式的做法(掃到珍珠 → 載入它腳下的區塊)永遠慢一步:等我們掃到的時候,珍珠已經
 * 到那裡了。珍珠在被拋出後速度可能高達每 tick 數十甚至數百個區塊(珍珠炮),於是實際行為
 * 會變成:<b>珍珠衝進未載入區塊 → 因為該區塊沒被 tick 而卡住 → 下一 tick 我們掃到它 →
 * 補載入 → 珍珠繼續飛</b>,一段一段地跳。
 *
 * <p>這裡的優化是讀珍珠的 Motion 向量(Bukkit 的 {@link org.bukkit.entity.Entity#getVelocity()}),
 * 沿著它下一 tick 的行進<em>路線</em>把沿途區塊先鋪好,而不是只鋪它現在站的地方。能不能
 * 完全消除卡頓取決於速度:
 * <ul>
 *   <li>速度 &lt; {@code max-chunks-per-pearl} 能涵蓋的距離 → 幾乎不會再卡。</li>
 *   <li>速度極高(每 tick 數百個區塊)→ 一 tick 就要求載入上萬個區塊,任何伺服器都撐不住,
 *       所以有 {@code max-chunks-per-pearl} 上限,超過的部分還是會退化成「卡住→補載入→續飛」。
 *       這是這類插件的物理極限,不是實作偷懶:區塊載入本身就比珍珠慢。</li>
 * </ul>
 *
 * <h2>簡化的彈道模型</h2>
 * 原版終界珍珠每 tick:先依 Motion 位移,再乘上 0.99 的空氣阻力,y 再減 0.03 重力。
 * 我們只需要 chunk 的 X/Z,所以重力(只影響 y)可以忽略,只保留水平阻力。碰撞、水中阻力
 * (0.8)、傳送門這些都不模擬——猜錯了頂多多鋪或少鋪幾個區塊,下一 tick 的差集運算會修正。
 */
final class PearlPathPredictor {

    /** 原版投擲物每 tick 的水平阻力係數。 */
    private static final double AIR_DRAG = 0.99D;

    /** 沿路線取樣的間隔(方塊)。取半個區塊寬,確保相鄰取樣點的區塊座標最多差 1,不會跳過區塊。 */
    private static final double SAMPLE_STEP_BLOCKS = 8.0D;

    private PearlPathPredictor() {
    }

    /**
     * @param location 珍珠目前位置
     * @param velocity 珍珠目前的 Motion 向量(方塊/tick)
     * @return 這一 tick 要 forceload 的區塊 key 集合;順序是「由近而遠」,被上限截斷時
     *         保留的是離珍珠最近、最急著要的那一段
     */
    static Set<Long> chunksFor(final Location location, final Vector velocity, final LoaderConfig config) {
        final Set<Long> chunks = new LinkedHashSet<>();
        final int cap = config.maxChunksPerPearl();

        int chunkX = Math.floorDiv(location.getBlockX(), 16);
        int chunkZ = Math.floorDiv(location.getBlockZ(), 16);
        if (!addSquare(chunks, chunkX, chunkZ, config.radius(), cap) || !config.predictEnabled()) {
            return chunks;
        }

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
                if (!addSquare(chunks, sampleX, sampleZ, config.radius(), cap)) {
                    return chunks; // 撞到上限,後面的路就不鋪了
                }
            }
            x += vx;
            z += vz;
            vx *= AIR_DRAG;
            vz *= AIR_DRAG;
        }
        return chunks;
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
