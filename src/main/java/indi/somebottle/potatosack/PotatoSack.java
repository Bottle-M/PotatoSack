package indi.somebottle.potatosack;

import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.tasks.BackupChecker;
import indi.somebottle.potatosack.tasks.TokenChecker;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.Utils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class PotatoSack extends JavaPlugin {
    public static Plugin plugin = null;
    private final Config config = new Config(); // 配置文件对象
    private TokenFetcher tokenFetcher; // TokenFetcher对象
    private Client odClient; // OneDrive客户端

    @Override
    public void onEnable() {
        plugin = this; // 暴露插件对象
        BackupChecker backupChecker;
        // 开始初始化插件
        ConsoleSender.toConsole("Potato Sack Initializing...");
        // 初始化配置
        String clientId = (String) config.getConfig("onedrive.client-id");
        String clientScrt = (String) config.getConfig("onedrive.client-secret");
        String refreshToken = (String) config.getConfig("onedrive.refresh-token");
        // 初始化TokenFetcher
        tokenFetcher = new TokenFetcher(clientId, clientScrt, refreshToken, config);
        // 初始化获取token
        if (!tokenFetcher.fetch()) {
            ConsoleSender.toConsole("Potato Sack Failed to Initialize! Please check configs.yml");
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // 初始化OneDrive客户端
        odClient = new Client(tokenFetcher);
        // 检查OneDrive上插件数据目录是否建立
        try {
            if (odClient.getItem(Constants.OD_APP_DATA_FOLDER) == null) {
                ConsoleSender.toConsole("Creating data folder in OneDrive.");
                // 如果没有建立则建立数据目录
                if (odClient.createFolder(Constants.OD_APP_DATA_FOLDER)) {
                    ConsoleSender.toConsole("Successfully created data folder in OneDrive.");
                } else {
                    throw new IOException("Failed to create data folder in OneDrive.");
                }
            }
            // 初始化备份核心
            backupChecker = new BackupChecker(odClient, config);
            // 初始化备份
            if (!backupChecker.initialize())
                throw new IOException("Failed to initialize backup module.");
        } catch (IOException e) {
            // 因为网络原因(比如连接超时)导致目录建立失败
            Utils.logError(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // 初始化异步任务定时器
        // 每30秒检查一次AccessToken是否过期
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new TokenChecker(tokenFetcher), 0, 20 * 30);
        // 每60秒检查一次备份（首次执行前等待60秒)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, backupChecker, 20 * 60, 20 * 60);

        ConsoleSender.toConsole("Potato Sack Successfully initialized! Savor using it!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
