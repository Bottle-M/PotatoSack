package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private File file; // configs.yml文件对象
    private YamlConfiguration config; // configs.yml配置内容

    /**
     * 载入配置文件configs.yml
     */
    private void loadConfig() {
        // 插件配置目录
        File dataDir = PotatoSack.plugin.getDataFolder();
        if (!dataDir.exists())
            dataDir.mkdirs();
        // 配置文件
        file = new File(dataDir, "configs.yml");
        if (!file.exists())
            // 创建默认配置文件（resources/configs.yml）
            PotatoSack.plugin.saveResource("configs.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
        inspectConfig();
    }

    /**
     * 写入配置文件
     */
    private void saveConfig() {
        try {
            config.save(file);
        } catch (IOException e) {
            Utils.logError("Failed to save configs.yml: " + e.getMessage());
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
            config.load(file);
            inspectConfig(); // 检查配置
            return true;
        } catch (Exception e) {
            Utils.logError("Failed to load configs.yml: " + e.getMessage());
        }
        return false;
    }

    /**
     * 配置文件configs.yml完整性检查
     *
     * @apiNote 配置中的部分项目可能因为用户配置错误而丢失，这里检查配置项是否完整，对缺失项填充默认值
     */
    private void inspectConfig() {
        if (config.get("onedrive.client-id") == null) {
            config.set("onedrive.client-id", "");
        }
        if (config.get("onedrive.client-secret") == null) {
            config.set("onedrive.client-secret", "");
        }
        if (config.get("onedrive.refresh-token") == null) {
            config.set("onedrive.refresh-token", "");
        }
        if (config.get("max-full-backups-retained") == null) {
            config.set("max-full-backups-retained", 3);
        }
        if (config.get("full-backup-interval") == null) {
            config.set("full-backup-interval", 1440);
        }
        if (config.get("incremental-backup-check-interval") == null) {
            config.set("incremental-backup-check-interval", 15);
        }
        if (config.get("stop-incremental-backup-when-no-player") == null) {
            config.set("stop-incremental-backup-when-no-player", true);
        }
        if (config.get("use-streaming-compression-upload") == null) {
            config.set("use-streaming-compression-upload", false);
        }
        if (config.get("worlds") == null) {
            List<String> worlds = new ArrayList<>();
            config.set("worlds", worlds);
        }
        saveConfig();
    }
}
