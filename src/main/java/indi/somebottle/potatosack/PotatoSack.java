package indi.somebottle.potatosack;

import indi.somebottle.potatosack.clients.ClientFactory;
import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.command.PotatoSackExecutor;
import indi.somebottle.potatosack.command.PotatoSackTabCompleter;
import indi.somebottle.potatosack.listeners.PlayerEventListener;
import indi.somebottle.potatosack.tasks.BackupChecker;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.IgnoreMatcher;
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
    private Config config; // 插件配置对象

    /**
     * 插件启动时进行的操作
     */
    @Override
    public void onEnable() {
        pluginInstance = this;
        // 配置服务端根目录
        worldContainerDir = this.getServer().getWorldContainer();
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
        System.out.println("Server root dir URI: " + worldContainerDir.toURI());
        // 测试 pathRelativeToServer 是否正常运作
        try {
            Utils.testRelativePathToServer();
        } catch (IOException e) {
            ConsoleSender.logError("Method pathRelativeToServer worked improperly: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        System.out.println("Potato Sack Initializing...");
        // 初始化配置
        config = new Config();
        // 初始化备份核心链（Client → BackupChecker → 定时任务）
        String err = initBackupChain();
        if (err != null) {
            ConsoleSender.logError(err);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 注册事件、指令等（一次性）
        try {
            LocalStatus.getInstance();
            getServer().getPluginManager().registerEvents(new PlayerEventListener(), this);
            PluginCommand mainCommand = getCommand("potatosack");
            if (mainCommand == null)
                throw new NullPointerException("Unable to get command, this should not happen.");
            mainCommand.setExecutor(new PotatoSackExecutor());
            mainCommand.setTabCompleter(new PotatoSackTabCompleter());
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ConsoleSender.toConsole("PotatoSack successfully initialized! Savor using it!");
    }

    /**
     * 热重载配置和客户端<br>
     * 配置变更通过共享 Config 引用即时生效；新 Client 初始化成功后才切换，失败则保留旧 Client。
     *
     * @return 重载结果消息
     */
    public String reloadAll() {
        if (!config.reload()) {
            return "Failed to reload: configs.yml could not be loaded";
        }
        String err = initBackupChain();
        if (err != null) {
            ConsoleSender.logWarn(err);
            return "Failed to reload: " + err;
        }
        return "Reloaded successfully.";
    }

    /**
     * 初始化/重建备份核心链：校验配置版本 → 创建 Client → 创建 BackupChecker → 启动定时任务。<br>
     * 成功时关闭旧 Client 并替换；失败时不做任何状态变更。
     *
     * @return null 表示成功，否则返回错误描述
     */
    private String initBackupChain() {
        // 重新检查 .potatosackignore 是否能正常解析，如果不能解析就会抛出异常
        try {
            IgnoreMatcher.loadDefault();
        } catch (IOException e) {
            return "Failed to parse .potatosackignore: " + e.getMessage();
        }
        // 配置版本检查
        String configVersion = (String) config.getConfig(Config.KEYS.VERSION);
        if (configVersion == null || configVersion.equals("legacy")) {
            return "Config file is outdated, please update to the latest version.";
        }
        // 创建云存储客户端
        String clientUsed = (String) config.getConfig(Config.KEYS.CLIENT.USE);
        Client newClient;
        try {
            newClient = ClientFactory.getClient(clientUsed, config);
        } catch (Exception e) {
            return "Failed to initialize client: " + e.getMessage();
        }
        // 创建 BackupChecker 并启动定时任务
        BackupChecker newBackupChecker;
        try {
            newBackupChecker = new BackupChecker(newClient, config);
        } catch (IOException e) {
            newClient.shutdown();
            return "Failed to create BackupChecker: " + e.getMessage();
        }
        // 切换：关旧 → 换新 → 重调度
        if (fileClient != null)
            fileClient.shutdown();
        fileClient = newClient;
        if (backupCheckTask != null)
            backupCheckTask.cancel();
        backupCheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, newBackupChecker, 20 * 60, 20 * 60);
        return null;
    }

    /**
     * 获取插件主类实例
     *
     * @return 插件主类实例，可能为 null（尚未初始化时）
     */
    public static PotatoSack getPluginInstance() {
        return pluginInstance;
    }

    @Override
    public void onDisable() {
        if (fileClient != null)
            fileClient.shutdown();
        if (backupCheckTask != null)
            backupCheckTask.cancel();
        ConsoleSender.toConsoleSync("PotatoSack shutting down...See you next time~ (∠・ω< )⌒★");
    }
}
