package indi.somebottle.potatosack;

import indi.somebottle.potatosack.command.PotatoSackExecutor;
import indi.somebottle.potatosack.command.PotatoSackTabCompleter;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.tasks.BackupChecker;
import indi.somebottle.potatosack.tasks.TokenChecker;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.OkHttpClient;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PotatoSack extends JavaPlugin {
    public static Plugin plugin = null; // 插件对象
    public static File worldContainerDir = null; // 服务端根目录 File 对象
    private final Config config = new Config(); // 配置文件对象
    private TokenFetcher tokenFetcher; // TokenFetcher对象
    private Client odClient; // OneDrive客户端

    private final BukkitTask[] checkTasks = new BukkitTask[2]; // 定时检查任务数组

    /**
     * 插件启动时进行的操作
     *
     * @apiNote 此处输出主要是System.out的方法实现，因为主线程会被阻塞
     */
    @Override
    public void onEnable() {
        plugin = this; // 暴露插件对象
        // 20240613 配置服务端根目录
        worldContainerDir = this.getServer().getWorldContainer();
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE); // 设置OkHttpClient日志级别
        System.out.println("[DEBUG]" +
                "Server root dir: " + new File(System.getProperty("user.dir")));
        BackupChecker backupChecker;
        // 开始初始化插件
        System.out.println("Potato Sack Initializing...");
        // 初始化配置
        String clientId = (String) config.getConfig("onedrive.client-id");
        String clientScrt = (String) config.getConfig("onedrive.client-secret");
        String refreshToken = (String) config.getConfig("onedrive.refresh-token");
        // 初始化TokenFetcher
        tokenFetcher = new TokenFetcher(clientId, clientScrt, refreshToken, config);
        // 初始化获取token
        if (!tokenFetcher.fetch()) {
            System.out.println("Potato Sack Failed to Initialize! Please check configs.yml");
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // 初始化OneDrive客户端
        odClient = new Client(tokenFetcher);
        try {
            // 输出Onedrive AppFolder
            System.out.println("Onedrive AppFolder URL: " + odClient.getAppFolderUrl());
            // 检查OneDrive上插件数据目录是否建立
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
            // 初始化异步任务定时器
            // 每30秒检查一次AccessToken是否过期
            checkTasks[0] = Bukkit.getScheduler().runTaskTimerAsynchronously(this, new TokenChecker(tokenFetcher), 0, 20 * 30);
            // 每60秒检查一次备份（首次执行前等待60秒)
            checkTasks[1] = Bukkit.getScheduler().runTaskTimerAsynchronously(this, backupChecker, 20 * 60, 20 * 60);
            // 注册重载配置指令
            PluginCommand mainCommand = getCommand("potatosack");
            if (mainCommand == null)
                throw new NullPointerException("Unable to get command, this should not happen.");
            // 设置指令执行者
            mainCommand.setExecutor(new PotatoSackExecutor(config));
            // 注册TAB补全
            mainCommand.setTabCompleter(new PotatoSackTabCompleter());
        } catch (Exception e) {
            // 因为网络原因(比如连接超时)导致目录建立失败
            Utils.logError(e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        ConsoleSender.toConsole("PotatoSack successfully initialized! Savor using it!");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        // 取消定时任务
        for (BukkitTask task : checkTasks)
            if (task != null)
                task.cancel();
        ConsoleSender.toConsoleSync("PotatoSack Shutting Down...See you next time~");
    }
}
