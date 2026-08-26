package io.github.sql1024.netherpearlloader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * forceload 的帳本:誰(哪顆珍珠)佔用了哪些區塊,以及每個區塊還有幾顆珍珠在用。
 *
 * <p>設計重點:
 * <ul>
 *   <li><b>差集更新</b>:珍珠每 tick 回報一組「這次需要的區塊」,只補新增的、只解除多餘的,
 *       不做「全部解除再全部載入」——後者會讓區塊在同一 tick 內被卸載又載入。</li>
 *   <li><b>引用計數</b>:兩顆珍珠可能同時需要同一個區塊。只有計數歸零才真的解除 forceload,
 *       否則 A 珍珠飛走會把 B 珍珠腳下的區塊抽掉。</li>
 *   <li><b>外來 forceload 保護</b>:如果某個區塊在我們要接手時「已經是 forceloaded」
 *       (玩家用 /forceload 或別的插件設的),就只記帳、不動它,計數歸零時也不解除。
 *       我們只負責解除自己設上去的。</li>
 * </ul>
 */
final class ForceLoadManager {

    private final Plugin plugin;
    private final ClaimJournal journal;

    /** worldUUID -> 該世界的帳本 */
    private final Map<UUID, WorldLedger> ledgers = new HashMap<>();
    /** pearlUUID -> 這顆珍珠目前佔用的東西 */
    private final Map<UUID, PearlClaim> claims = new HashMap<>();

    private LoaderConfig config;
    private boolean journalDirty;
    private long ticksSinceJournalWrite;

    ForceLoadManager(final Plugin plugin, final LoaderConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.journal = new ClaimJournal(plugin);
    }

    void setConfig(final LoaderConfig config) {
        this.config = config;
    }

    // ------------------------------------------------------------------ 對外 API

    /**
     * 把某顆珍珠的佔用區塊更新成 {@code desired}(差集運算)。
     *
     * @return 這次新增/解除的區塊數,只給 debug 訊息用
     */
    Delta sync(final UUID pearlId, final World world, final Set<Long> desired) {
        PearlClaim previous = this.claims.get(pearlId);
        if (previous != null && !previous.worldId().equals(world.getUID())) {
            // 珍珠換世界了(例如穿了地獄門):舊世界那邊的佔用整批放掉,重新來過
            releaseClaim(previous);
            previous = null;
        }
        final Set<Long> before = previous == null ? Set.of() : previous.chunks();

        int added = 0;
        for (final long key : desired) {
            if (!before.contains(key)) {
                claim(world, key);
                added++;
            }
        }
        int removed = 0;
        for (final long key : before) {
            if (!desired.contains(key)) {
                release(world.getUID(), key);
                removed++;
            }
        }

        if (desired.isEmpty()) {
            this.claims.remove(pearlId);
        } else {
            this.claims.put(pearlId, new PearlClaim(world.getUID(), desired));
        }
        if (added != 0 || removed != 0) {
            this.journalDirty = true;
        }
        return new Delta(added, removed);
    }

    /**
     * 放掉所有「這次掃描沒看到」的珍珠。珍珠命中方塊/實體、傳送玩家後消失、被清除、
     * 或飛出我們會掃描的世界,都會走到這裡,它佔用的區塊會整批解除。
     *
     * @return 被清掉的珍珠數
     */
    int retainOnly(final Set<UUID> alivePearls) {
        int released = 0;
        final Iterator<Map.Entry<UUID, PearlClaim>> it = this.claims.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, PearlClaim> entry = it.next();
            if (alivePearls.contains(entry.getKey())) {
                continue;
            }
            releaseClaim(entry.getValue());
            it.remove();
            released++;
            this.journalDirty = true;
        }
        return released;
    }

    /** 關服/reload 時把本插件加過的 forceload 全部清乾淨,避免存檔被永久釘住。 */
    int releaseAll() {
        int chunks = 0;
        for (final PearlClaim claim : this.claims.values()) {
            chunks += claim.chunks().size();
            releaseClaim(claim);
        }
        this.claims.clear();
        // 理論上 ledger 這時應該已經空了;真的有殘留(引用計數對不上)就強制收尾。
        for (final Map.Entry<UUID, WorldLedger> entry : this.ledgers.entrySet()) {
            final World world = this.plugin.getServer().getWorld(entry.getKey());
            final WorldLedger ledger = entry.getValue();
            if (world != null) {
                for (final long key : ledger.refCounts.keySet()) {
                    if (!ledger.foreign.contains(key) && !ledger.pending.contains(key)) {
                        world.setChunkForceLoaded(ChunkKey.x(key), ChunkKey.z(key), false);
                    }
                }
            }
            ledger.refCounts.clear();
            ledger.foreign.clear();
            ledger.pending.clear();
        }
        this.ledgers.clear();
        this.journalDirty = false;
        return chunks;
    }

    /**
     * 啟動時清掉上一輪(當機/被 kill,沒跑到 onDisable)殘留的 forceload。
     *
     * @return 清掉的區塊數
     */
    int recoverFromPreviousSession() {
        final List<ClaimJournal.Entry> entries = this.journal.read();
        if (entries.isEmpty()) {
            return 0;
        }
        int cleared = 0;
        for (final ClaimJournal.Entry entry : entries) {
            final World world = this.plugin.getServer().getWorld(entry.worldId());
            if (world == null) {
                continue;
            }
            // 保險:nether-only 開著的時候,連清理都不碰非地獄世界。
            if (this.config.netherOnly() && world.getEnvironment() != World.Environment.NETHER) {
                continue;
            }
            if (world.isChunkForceLoaded(entry.chunkX(), entry.chunkZ())) {
                world.setChunkForceLoaded(entry.chunkX(), entry.chunkZ(), false);
                cleared++;
            }
        }
        this.journal.clear();
        return cleared;
    }

    /** 每次掃描結束呼叫一次;有變動且超過節流間隔才會真的寫檔(非同步)。 */
    void tickJournal() {
        if (!this.config.recoverOnEnable()) {
            return;
        }
        this.ticksSinceJournalWrite += this.config.intervalTicks();
        if (!this.journalDirty || this.ticksSinceJournalWrite < this.config.journalIntervalTicks()) {
            return;
        }
        this.journalDirty = false;
        this.ticksSinceJournalWrite = 0L;
        this.journal.writeAsync(snapshot());
    }

    /** 關服時同步寫一次(清空)。 */
    void clearJournal() {
        if (this.config.recoverOnEnable()) {
            this.journal.clear();
        }
    }

    int trackedPearls() {
        return this.claims.size();
    }

    int ownedChunks() {
        int total = 0;
        for (final WorldLedger ledger : this.ledgers.values()) {
            total += ledger.refCounts.size() - ledger.foreign.size() - ledger.pending.size();
        }
        return total;
    }

    // ------------------------------------------------------------------ 內部

    private void claim(final World world, final long key) {
        final WorldLedger ledger = this.ledgers.computeIfAbsent(world.getUID(), id -> new WorldLedger());
        final int count = ledger.refCounts.merge(key, 1, Integer::sum);
        if (count != 1) {
            return; // 已經有別的珍珠佔著,不用再設一次
        }
        final int chunkX = ChunkKey.x(key);
        final int chunkZ = ChunkKey.z(key);
        if (world.isChunkForceLoaded(chunkX, chunkZ)) {
            // 不是我們設的 forceload(玩家 /forceload 或其他插件),只記帳、不接管
            ledger.foreign.add(key);
            return;
        }
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            // 已經載入的區塊:setChunkForced 內部那次 getChunk 直接命中快取,不會卡
            world.setChunkForceLoaded(chunkX, chunkZ, true);
            return;
        }
        //
        // ★ 這裡是這個插件最重要的一行。
        //
        // CraftWorld.setChunkForceLoaded -> ServerLevel.setChunkForced 內部會呼叫
        // getChunk(x, z) ——那是**同步、會現場生成**的載入。直接對一個沒生成過的遠方區塊
        // 下 forceload,等於在主執行緒跑一次地形生成。實測(Paper 26.1.2,1750 格/tick 的
        // 珍珠打進全新地獄)單一 tick 因此卡了 6~8 秒,珍珠反而比不裝插件還慢 10 倍。
        //
        // 所以改成:先用 Paper 的非同步 API 把區塊叫起來(生成跑在 chunk worker 執行緒,
        // 主執行緒照常 20 TPS),等它真的載入完、回到主執行緒之後才掛 forceload——這時
        // 那次內部 getChunk 是快取命中,不會阻塞。
        //
        ledger.pending.add(key);
        world.getChunkAtAsync(chunkX, chunkZ, true, chunk -> onChunkReady(world.getUID(), key));
    }

    /** 非同步載入完成(主執行緒)後才真正掛上 forceload;期間若已被釋放就什麼都不做。 */
    private void onChunkReady(final UUID worldId, final long key) {
        final WorldLedger ledger = this.ledgers.get(worldId);
        if (ledger == null || !ledger.pending.remove(key) || !ledger.refCounts.containsKey(key)) {
            return; // 珍珠早就飛走 / 消失了,這張路不用鋪了
        }
        final World world = this.plugin.getServer().getWorld(worldId);
        if (world == null) {
            return;
        }
        final int chunkX = ChunkKey.x(key);
        final int chunkZ = ChunkKey.z(key);
        if (world.isChunkForceLoaded(chunkX, chunkZ)) {
            ledger.foreign.add(key); // 這段期間被別人 forceload 了,不接管
            return;
        }
        world.setChunkForceLoaded(chunkX, chunkZ, true);
    }

    private void release(final UUID worldId, final long key) {
        final WorldLedger ledger = this.ledgers.get(worldId);
        if (ledger == null) {
            return;
        }
        final Integer count = ledger.refCounts.get(key);
        if (count == null) {
            return;
        }
        if (count > 1) {
            ledger.refCounts.put(key, count - 1);
            return;
        }
        ledger.refCounts.remove(key);
        if (ledger.foreign.remove(key)) {
            return; // 外來的 forceload,原封不動還回去
        }
        if (ledger.pending.remove(key)) {
            return; // 還在非同步載入中,根本還沒 forceload,不用解除
        }
        final World world = this.plugin.getServer().getWorld(worldId);
        if (world != null) {
            world.setChunkForceLoaded(ChunkKey.x(key), ChunkKey.z(key), false);
        }
        if (ledger.refCounts.isEmpty()) {
            this.ledgers.remove(worldId);
        }
    }

    private void releaseClaim(final PearlClaim claim) {
        for (final long key : claim.chunks()) {
            release(claim.worldId(), key);
        }
    }

    private List<ClaimJournal.Entry> snapshot() {
        final List<ClaimJournal.Entry> entries = new ArrayList<>();
        for (final Map.Entry<UUID, WorldLedger> entry : this.ledgers.entrySet()) {
            final WorldLedger ledger = entry.getValue();
            for (final long key : ledger.refCounts.keySet()) {
                if (ledger.foreign.contains(key) || ledger.pending.contains(key)) {
                    continue; // 不是我們設的(或還沒設上去),不要記進去
                }
                entries.add(new ClaimJournal.Entry(entry.getKey(), ChunkKey.x(key), ChunkKey.z(key)));
            }
        }
        return entries;
    }

    /** 單一世界的區塊引用計數 + 外來 forceload 名單。 */
    private static final class WorldLedger {
        private final Map<Long, Integer> refCounts = new HashMap<>();
        private final Set<Long> foreign = new HashSet<>();
        /** 已經送出非同步載入、但還沒真正掛上 forceload 的區塊。 */
        private final Set<Long> pending = new HashSet<>();
    }

    /** 某顆珍珠目前佔用的世界與區塊集合。 */
    private record PearlClaim(UUID worldId, Set<Long> chunks) {
    }

    /** 一次 sync 的增減量,只給 debug 用。 */
    record Delta(int added, int removed) {
        boolean changed() {
            return this.added != 0 || this.removed != 0;
        }
    }
}
