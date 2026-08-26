package io.github.sql1024.netherpearlloader;

/**
 * 把 (chunkX, chunkZ) 打包成一個 long,用來當 Set/Map 的 key。
 *
 * <p>這裡自己打包而不是直接用 {@link org.bukkit.Chunk#getChunkKey(int, int)},是因為我們
 * 也需要「解包」:追蹤結構裡只留 long,解除 forceload 時得把 x/z 還原回來。自己包自己解
 * 可以保證編碼與解碼永遠一致(打包方式其實與 Bukkit 相同:低 32 bit 放 x、高 32 bit 放 z)。
 */
final class ChunkKey {

    private ChunkKey() {
    }

    static long of(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFF_FFFFL) | ((long) chunkZ << 32);
    }

    static int x(final long key) {
        return (int) key;
    }

    static int z(final long key) {
        return (int) (key >> 32);
    }

    static String toString(final long key) {
        return "(" + x(key) + ", " + z(key) + ")";
    }
}
