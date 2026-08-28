package dev.sql1024.pearltrack.track;

/** Packs a chunk coordinate pair into a single long so it can live in a HashSet cheaply. */
public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static long of(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
