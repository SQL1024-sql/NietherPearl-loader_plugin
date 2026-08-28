package dev.sql1024.pearltrack.config;

/** How the plugin pins chunks that the pearl is about to fly into. */
public enum TicketMode {
    /**
     * {@code World#addPluginChunkTicket}. Ticket level 31 (entity ticking), never
     * written to the region files and dropped automatically when the plugin
     * disables. This is the safe default.
     */
    PLUGIN_TICKET,
    /**
     * {@code World#setChunkForceLoaded}. Same ticket level, but the set is saved
     * with the world, so a crash leaves the chunks pinned forever. The plugin
     * keeps a journal to clean those up on the next start.
     */
    FORCE_LOADED
}
