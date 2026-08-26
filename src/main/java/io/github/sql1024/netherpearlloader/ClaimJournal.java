package io.github.sql1024.netherpearlloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;

/**
 * 殘留 forceload 的紀錄檔。
 *
 * <p>正常關服時 {@code onDisable()} 會把插件加的 forceload 全部解除;但如果伺服器是被
 * {@code kill}、斷電或崩潰收掉的,{@code onDisable()} 不會跑,forceload 旗標就會留在
 * level.dat 裡把區塊永遠釘住。這個 journal 把「目前由本插件佔用的區塊」寫到檔案,
 * 下次啟動時照著清一遍,存檔就不會被永久釘住。
 *
 * <p>寫入是節流的(預設每 5 秒最多一次)而且丟到 async scheduler,不會卡主執行緒。
 * 檔案先寫 .tmp 再 move,避免當機時留下半截檔案。
 */
final class ClaimJournal {

    private static final String FILE_NAME = "forceloaded.txt";

    private final Plugin plugin;
    private final Path file;

    ClaimJournal(final Plugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve(FILE_NAME);
    }

    /** 讀出上一輪殘留的紀錄;檔案不存在或壞掉就當成空的。 */
    List<Entry> read() {
        final List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(this.file)) {
            return entries;
        }
        try {
            for (final String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                final String[] parts = trimmed.split(",");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    entries.add(new Entry(UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (final IllegalArgumentException ignored) {
                    // 單行壞掉不影響其他行
                }
            }
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "讀取 " + FILE_NAME + " 失敗", e);
        }
        return entries;
    }

    /** 非同步覆寫紀錄檔。呼叫端負責傳入「當下的快照」(不可再被修改)。 */
    void writeAsync(final List<Entry> snapshot) {
        this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, task -> write(snapshot));
    }

    /** 同步覆寫,給 onDisable 用(此時已經不能再排 async 任務)。 */
    void write(final List<Entry> snapshot) {
        try {
            Files.createDirectories(this.file.getParent());
            if (snapshot.isEmpty()) {
                Files.deleteIfExists(this.file);
                return;
            }
            final StringBuilder sb = new StringBuilder(snapshot.size() * 48);
            sb.append("# NetherPearlLoader: 目前由本插件 forceload 的區塊,格式 worldUuid,chunkX,chunkZ\n");
            for (final Entry entry : snapshot) {
                sb.append(entry.worldId()).append(',')
                        .append(entry.chunkX()).append(',')
                        .append(entry.chunkZ()).append('\n');
            }
            final Path tmp = this.file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "寫入 " + FILE_NAME + " 失敗", e);
        }
    }

    /** 清空紀錄(關服清乾淨之後呼叫)。 */
    void clear() {
        write(List.of());
    }

    /** 一筆「某世界的某個區塊由本插件 forceload 中」。 */
    record Entry(UUID worldId, int chunkX, int chunkZ) {
    }
}
