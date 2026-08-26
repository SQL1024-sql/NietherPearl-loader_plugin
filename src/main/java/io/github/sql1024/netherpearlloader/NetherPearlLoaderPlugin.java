package io.github.sql1024.netherpearlloader;

import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * NetherPearlLoader — 只在地獄對飛行中的終界珍珠強制載入區塊。
 *
 * <p>主世界與終界完全不介入(見 {@code nether-only},預設 true)。
 */
public final class NetherPearlLoaderPlugin extends JavaPlugin {

    private LoaderConfig config;
    private ForceLoadManager manager;
    private PearlScanTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = LoaderConfig.from(getConfig());
        this.manager = new ForceLoadManager(this, this.config);

        if (this.config.recoverOnEnable()) {
            final int cleared = this.manager.recoverFromPreviousSession();
            if (cleared > 0) {
                getLogger().info("清除上一輪殘留的 forceload 區塊 " + cleared + " 個(上次關服不正常?)");
            }
        }

        startTask();

        final PluginCommand command = getCommand("netherpearlloader");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        if (!this.config.netherOnly()) {
            getLogger().warning("nether-only 被關閉了:主世界的珍珠也會被 forceload,"
                    + "靠弱載入卡珍珠蓄力(珍珠炮)的機制會失效!");
        }
        getLogger().info(describe());
    }

    @Override
    public void onDisable() {
        stopTask();
        if (this.manager != null) {
            final int chunks = this.manager.releaseAll();
            this.manager.clearJournal();
            getLogger().info("已解除本插件加上的 forceload,共 " + chunks + " 個區塊佔用紀錄");
        }
    }

    @Override
    public boolean onCommand(final @NotNull CommandSender sender, final @NotNull Command command,
                             final @NotNull String label, final String @NotNull [] args) {
        final String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadPlugin();
                sender.sendMessage(Component.text("NetherPearlLoader 已重新載入設定。", NamedTextColor.GREEN));
                sender.sendMessage(Component.text(describe(), NamedTextColor.GRAY));
            }
            case "status" -> {
                sender.sendMessage(Component.text(describe(), NamedTextColor.GRAY));
                sender.sendMessage(Component.text("追蹤中的珍珠:" + this.manager.trackedPearls()
                        + ",本插件 forceload 的區塊:" + this.manager.ownedChunks(), NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text("用法:/" + label + " <reload|status>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final @NotNull CommandSender sender, final @NotNull Command command,
                                      final @NotNull String label, final String @NotNull [] args) {
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "status").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    /**
     * reload:先把目前的 forceload 全部解除,再用新設定重來一輪。
     * 半徑/維度條件可能整個變了,沿用舊帳本會留下對不上的殘留。
     */
    private void reloadPlugin() {
        stopTask();
        this.manager.releaseAll();
        reloadConfig();
        this.config = LoaderConfig.from(getConfig());
        this.manager.setConfig(this.config);
        startTask();
        if (!this.config.netherOnly()) {
            getLogger().warning("nether-only 被關閉了:主世界的珍珠也會被 forceload!");
        }
    }

    private void startTask() {
        this.task = new PearlScanTask(this, this.manager, this.config);
        this.task.runTaskTimer(this, 1L, this.config.intervalTicks());
    }

    private void stopTask() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private String describe() {
        return String.format(Locale.ROOT,
                "設定:radius=%d (%dx%d,每顆珍珠 %d 區塊)、interval-ticks=%d、nether-only=%s、debug=%s、"
                        + "predict=%s (ticks=%d, cap=%d)",
                this.config.radius(), 2 * this.config.radius() + 1, 2 * this.config.radius() + 1,
                this.config.chunksPerPearlNoPredict(), this.config.intervalTicks(),
                this.config.netherOnly(), this.config.debug(),
                this.config.predictEnabled(), this.config.predictTicks(), this.config.maxChunksPerPearl());
    }
}
