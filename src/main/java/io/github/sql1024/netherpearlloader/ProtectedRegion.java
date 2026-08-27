package io.github.sql1024.netherpearlloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 「絕對不碰」的區塊範圍——珍珠炮的炮膛。
 *
 * <p>蓄力中的珍珠與飛行中的珍珠對區塊等級的需求是相反的:蓄力要 32 級(載入但不 tick,
 * 珍珠才會凍住累積動量),飛行要 31 級(entity ticking,珍珠才會動)。而從插件的角度看
 * 這兩顆珍珠一模一樣——都有巨大 Motion、都靜止不動(飛行中的珍珠在等區塊時也是靜止的)。
 *
 * <p>所以最可靠的區分方式是「你告訴我炮膛在哪」。列在這裡的區塊永遠不會被 forceload,
 * 待在裡面的珍珠也永遠不會被追蹤。這是確定性的保護,不受 reload、不受偵測邏輯影響。
 *
 * @param worldName 世界名稱(區分大小寫,對應 {@code world.getName()})
 * @param minChunkX 區塊座標範圍(含)
 */
record ProtectedRegion(String worldName, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    boolean covers(final String world, final int chunkX, final int chunkZ) {
        return this.worldName.equalsIgnoreCase(world)
                && chunkX >= this.minChunkX && chunkX <= this.maxChunkX
                && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
    }

    /**
     * 從 config 的 {@code stasis-protection.protected-chunks} 讀出範圍清單。
     * 格式:{@code - {world: world_nether, from: [x, z], to: [x, z]}}(區塊座標)。
     */
    static List<ProtectedRegion> from(final FileConfiguration cfg) {
        final List<ProtectedRegion> regions = new ArrayList<>();
        for (final Map<?, ?> raw : cfg.getMapList("stasis-protection.protected-chunks")) {
            final Object world = raw.get("world");
            final List<?> from = raw.get("from") instanceof List<?> list ? list : null;
            final List<?> to = raw.get("to") instanceof List<?> list ? list : null;
            if (world == null || from == null || to == null || from.size() < 2 || to.size() < 2) {
                continue; // 格式不對的整筆略過,不要因為一行設定錯就整個插件掛掉
            }
            final int x1 = toInt(from.get(0));
            final int z1 = toInt(from.get(1));
            final int x2 = toInt(to.get(0));
            final int z2 = toInt(to.get(1));
            regions.add(new ProtectedRegion(world.toString(),
                    Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)));
        }
        return List.copyOf(regions);
    }

    private static int toInt(final Object value) {
        return value instanceof Number number ? number.intValue()
                : Integer.parseInt(value.toString().trim());
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s[%d,%d..%d,%d]",
                this.worldName, this.minChunkX, this.minChunkZ, this.maxChunkX, this.maxChunkZ);
    }
}
