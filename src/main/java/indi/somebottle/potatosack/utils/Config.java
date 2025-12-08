package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private final Plugin plugin; // 插件对象
    private File configFile; // configs.yml文件对象
    private YamlConfiguration config; // configs.yml配置内容

    // 配置项键名常量
    public interface KEYS {
        interface CLIENT {
            String USE = "client.use";

            interface ONEDRIVE {
                String USE_APP_FOLDER = "client.onedrive.use-app-folder";
                String CLIENT_ID = "client.onedrive.client-id";
                String CLIENT_SECRET = "client.onedrive.client-secret";
                String REFRESH_TOKEN = "client.onedrive.refresh-token";
            }
        }

        String MAX_FULL_BACKUPS_RETAINED = "max-full-backups-retained";

        interface CRON {
            String FULL_BACKUP = "cron.full-backup";
            String INCREMENTAL_BACKUP = "cron.incremental-backup";
        }

        String STOP_INCREMENTAL_BACKUP_WHEN_NO_PLAYER = "stop-incremental-backup-when-no-player";
        String USE_STREAMING_COMPRESSION_UPLOAD = "use-streaming-compression-upload";
        String WORLDS = "worlds";
    }

    public Config() {
        plugin = PotatoSack.getPluginInstance();
        loadConfig();
    }

    /**
     * 载入配置文件 configs.yml
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void loadConfig() {
        // 插件配置目录
        File dataDir = plugin.getDataFolder();
        if (!dataDir.exists())
            dataDir.mkdirs();
        // 配置文件
        configFile = new File(dataDir, "configs.yml");
        if (!configFile.exists())
            // 创建默认配置文件（resources/configs.yml）
            plugin.saveResource("configs.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        inspectConfig();
    }

    /**
     * 写入配置文件
     */
    private void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            ConsoleSender.logError("Failed to save configs.yml: " + e.getMessage());
        }
    }

    /**
     * 获取配置项
     *
     * @return 配置YamlConfiguration
     */
    public YamlConfiguration getConfig() {
        if (config == null)
            loadConfig();
        return config;
    }

    /**
     * 获取配置项
     *
     * @param path 配置字段路径
     * @return 配置项对象
     */
    public Object getConfig(String path) {
        if (config == null)
            loadConfig();
        return config.get(path, "");
    }

    /**
     * 设置配置项值
     *
     * @param path  配置字段路径
     * @param value 配置项值
     */
    public void setConfig(String path, Object value) {
        if (config == null)
            loadConfig();
        config.set(path, value);
        saveConfig();
    }

    /**
     * 重载配置内容
     *
     * @return 是否重载成功
     */
    public boolean reload() {
        try {
            config.load(configFile);
            inspectConfig(); // 检查配置
            return true;
        } catch (Exception e) {
            ConsoleSender.logError("Failed to load configs.yml: " + e.getMessage());
        }
        return false;
    }

    /**
     * 配置文件configs.yml完整性检查
     *
     * @apiNote 配置中的部分项目可能因为用户配置错误而丢失，这里检查配置项是否完整，对缺失项填充默认值
     */
    private void inspectConfig() {
        if (config.get(KEYS.CLIENT.USE) == null) {
            config.set(KEYS.CLIENT.USE, "onedrive");
        }
        if (config.get(KEYS.CLIENT.ONEDRIVE.USE_APP_FOLDER) == null) {
            config.set(KEYS.CLIENT.ONEDRIVE.USE_APP_FOLDER, true);
        }
        if (config.get(KEYS.CLIENT.ONEDRIVE.CLIENT_ID) == null) {
            config.set(KEYS.CLIENT.ONEDRIVE.CLIENT_ID, "");
        }
        if (config.get(KEYS.CLIENT.ONEDRIVE.CLIENT_SECRET) == null) {
            config.set(KEYS.CLIENT.ONEDRIVE.CLIENT_SECRET, "");
        }
        if (config.get(KEYS.CLIENT.ONEDRIVE.REFRESH_TOKEN) == null) {
            config.set(KEYS.CLIENT.ONEDRIVE.REFRESH_TOKEN, "");
        }
        if (config.get(KEYS.MAX_FULL_BACKUPS_RETAINED) == null) {
            config.set(KEYS.MAX_FULL_BACKUPS_RETAINED, 10);
        }
        if (config.get(KEYS.CRON.FULL_BACKUP) == null) {
            config.set(KEYS.CRON.FULL_BACKUP, "0 2 */1 * *");
        }
        if (config.get(KEYS.CRON.INCREMENTAL_BACKUP) == null) {
            config.set(KEYS.CRON.INCREMENTAL_BACKUP, "*/30 * * * *");
        }
        if (config.get(KEYS.STOP_INCREMENTAL_BACKUP_WHEN_NO_PLAYER) == null) {
            config.set(KEYS.STOP_INCREMENTAL_BACKUP_WHEN_NO_PLAYER, true);
        }
        if (config.get(KEYS.USE_STREAMING_COMPRESSION_UPLOAD) == null) {
            config.set(KEYS.USE_STREAMING_COMPRESSION_UPLOAD, false);
        }
        if (config.get(KEYS.WORLDS) == null) {
            List<String> worlds = new ArrayList<>();
            config.set(KEYS.WORLDS, worlds);
        }
        saveConfig();
    }
}
