package indi.somebottle.potatosack;

import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.tasks.BackupChecker;
import indi.somebottle.potatosack.tasks.TokenChecker;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class PotatoSack extends JavaPlugin {
    public static Plugin plugin = null;
    private final ConsoleSender sender = new ConsoleSender();
    private final Config config = new Config(); // 配置文件对象
    private TokenFetcher tokenFetcher; // TokenFetcher对象

    @Override
    public void onEnable() {
        plugin = this; // 暴露插件对象
        // 开始初始化插件
        sender.toConsole("Potato Sack Initializing...");
        // 初始化配置
        String clientId = (String) config.getConfig("onedrive.client-id");
        String clientScrt = (String) config.getConfig("onedrive.client-secret");
        String refreshToken = (String) config.getConfig("onedrive.refresh-token");
        // 初始化TokenFetcher
        tokenFetcher = new TokenFetcher(clientId, clientScrt, refreshToken, config);
        // 初次获取token，最多重试3次
        if (!tokenFetcher.refresh(3)) {
            sender.toConsole("Potato Sack Failed to Initialize!");
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // 初始化异步任务定时器
        // 每30秒检查一次AccessToken是否过期
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new TokenChecker(tokenFetcher), 0, 20 * 30);
        // 每60秒检查一次备份
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new BackupChecker(tokenFetcher), 0, 20 * 60);

        sender.toConsole("Potato Sack Successfully initialized! Savor using it!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
