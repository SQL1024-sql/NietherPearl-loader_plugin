package io.github.sql1024.netherpearlloader;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml 的不可變快照。每次 reload 產生一份新的,執行中的程式碼永遠讀到一致的設定值。
 *
 * @param radius            以珍珠所在區塊為中心的方形半徑,載入 (2r+1)^2 個區塊
 * @param intervalTicks     掃描間隔(tick)
 * @param netherOnly        只在 NETHER 維度生效(預設 true,保護主世界的珍珠炮蓄力機制)
 * @param debug             主控台輸出追蹤訊息
 * @param predictEnabled    是否讀取 Motion 向量預先鋪路
 * @param predictTicks      往前預測幾 tick
 * @param maxChunksPerPearl 每顆珍珠的區塊數上限(安全閥)
 * @param recoverOnEnable   啟動時清理上一輪(當機)殘留的 forceload
 * @param journalIntervalTicks 殘留紀錄檔的最小寫入間隔(tick)
 */
record LoaderConfig(int radius,
                    long intervalTicks,
                    boolean netherOnly,
                    boolean debug,
                    boolean predictEnabled,
                    int predictTicks,
                    int maxChunksPerPearl,
                    boolean recoverOnEnable,
                    long journalIntervalTicks) {

    /** 一顆珍珠最多能佔用的區塊數,硬上限;config 再怎麼填都不會超過。 */
    private static final int HARD_CHUNK_CAP = 4096;

    static LoaderConfig from(final FileConfiguration cfg) {
        final int radius = clamp(cfg.getInt("radius", 1), 0, 8);
        final long interval = Math.max(1L, cfg.getLong("interval-ticks", 1L));
        final boolean netherOnly = cfg.getBoolean("nether-only", true);
        final boolean debug = cfg.getBoolean("debug", false);

        final boolean predict = cfg.getBoolean("predict.enabled", true);
        final int predictTicks = clamp(cfg.getInt("predict.ticks", 1), 1, 20);
        final int cap = clamp(cfg.getInt("predict.max-chunks-per-pearl", 256), 1, HARD_CHUNK_CAP);

        final boolean recover = cfg.getBoolean("recover-on-enable", true);
        final long journalInterval = Math.max(20L, cfg.getLong("journal-interval-ticks", 100L));

        return new LoaderConfig(radius, interval, netherOnly, debug,
                predict, predictTicks, cap, recover, journalInterval);
    }

    /** 不預測時,單顆珍珠固定就是 (2r+1)^2 個區塊。 */
    int chunksPerPearlNoPredict() {
        final int side = 2 * radius + 1;
        return side * side;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
