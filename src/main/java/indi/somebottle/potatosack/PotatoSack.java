package indi.somebottle.potatosack;

import indi.somebottle.potatosack.clients.ClientFactory;
import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.command.PotatoSackExecutor;
import indi.somebottle.potatosack.command.PotatoSackTabCompleter;
import indi.somebottle.potatosack.listeners.PlayerEventListener;
import indi.somebottle.potatosack.tasks.BackupChecker;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.LocalStatus;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.OkHttpClient;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PotatoSack extends JavaPlugin {
    private static PotatoSack pluginInstance = null; // 插件对象
    public static File worldContainerDir = null; // 服务端根目录 File 对象
    public BukkitTask backupCheckTask = null; // 备份检查定时任务
    private Client fileClient = null; // 云存储客户端

    /**
     * 插件启动时进行的操作
     *
     * @apiNote 此部分输出主要是 System.out 的方法实现
     */
    @Override
    public void onEnable() {
        pluginInstance = this;
        // 20240613 配置服务端根目录
        worldContainerDir = this.getServer().getWorldContainer();
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE); // 设置OkHttpClient日志级别
        // 输出服务端根目录
        System.out.println("Server root dir URI: " + worldContainerDir.toURI());
        // 测试 pathRelativeToServer 是否正常运作
        try {
            Utils.testRelativePathToServer();
        } catch (IOException e) {
            ConsoleSender.logError("Method pathRelativeToServer worked improperly: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // 备份任务定时检查模块
        BackupChecker backupChecker;
        // 开始初始化插件
        System.out.println("Potato Sack Initializing...");
        // 初始化配置
        Config config = new Config();
        // ---------------------------- 配置文件检查 ----------------------------
        // 配置版本检查
        String configVersion = (String) config.getConfig(Config.KEYS.VERSION);
        if (configVersion == null || configVersion.equals("legacy")) {
            // 配置文件需要手动升级
            ConsoleSender.logWarn("Your config file is outdated ( – ⌓ – ). Please update the config file to the latest version CAREFULLY. It is suggested to backup your old backup files before you update.");
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        // -------------------------- 配置文件检查结束 --------------------------
        String clientUsed = (String) config.getConfig(Config.KEYS.CLIENT.USE);
        // 初始化云存储客户端
        try {
            fileClient = ClientFactory.getClient(clientUsed, config);
        } catch (Exception e) {
            ConsoleSender.logError("Failed to initialize client: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        try {
            // 初始化备份核心
            backupChecker = new BackupChecker(fileClient, config);
            // 初始化本地状态管理
            LocalStatus.getInstance();
            // 初始化异步任务定时器
            // 每60秒检查一次备份（首次执行前等待60秒)
            backupCheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, backupChecker, 20 * 60, 20 * 60);
            // 注册玩家事件监听器
            getServer().getPluginManager().registerEvents(new PlayerEventListener(), this);
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
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);  // 中止插件启动
            return;
        }
        ConsoleSender.toConsole("PotatoSack successfully initialized! Savor using it!");
    }

    /**
     * 获取插件主类实例，如果还没有初始化则返回 null
     *
     * @return 插件主类实例，可能是 null
     */
    public static PotatoSack getPluginInstance() {
        return pluginInstance;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (fileClient != null)
            fileClient.shutdown();
        // 取消定时任务
        if (backupCheckTask != null)
            backupCheckTask.cancel();
        ConsoleSender.toConsoleSync("PotatoSack shutting down...See you next time~ (∠・ω< )⌒★");
    }
}
