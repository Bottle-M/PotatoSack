package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件配置管理类
 * <p>
 * <strong>注意：此类不能做成单例。</strong>
 * 构造函数中依赖 {@code PotatoSack.getPluginInstance()} 获取插件实例，
 * 而该实例只有在 {@code onEnable()} 中才会初始化。
 * 如果做成单例，在 {@code onEnable()} 之前调用 {@code getInstance()} 会导致空指针异常。
 * </p>
 * <p>
 * 负责加载、读取、修改和保存插件的配置文件 {@code configs.yml}。
 * 提供了配置项的完整性检查机制，确保所有必需的配置项都存在并具有默认值。
 * </p>
 * <p>
 * 配置文件位于插件数据目录下（{@code plugins/PotatoSack/configs.yml}），
 * 首次运行时会自动从插件资源中复制默认配置文件。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * Config config = new Config();
 * String clientType = (String) config.getConfig(Config.KEYS.CLIENT.USE);
 * config.setConfig(Config.KEYS.CLIENT.BASE_DIR, "backups/minecraft");
 * }</pre>
 * </p>
 *
 * @see Config.KEYS
 */
public class Config {
    /**
     * Bukkit 插件实例
     */
    private final Plugin plugin;

    /**
     * 配置文件 {@code configs.yml} 的 File 对象
     */
    private File configFile;

    /**
     * YAML 配置内容对象，用于读取和修改配置项
     */
    private YamlConfiguration config;

    /**
     * 配置项键名常量接口
     * <p>
     * 定义了所有配置项的键名常量，避免硬编码字符串。
     * 使用嵌套接口组织不同类别的配置项，提高代码可读性和可维护性。
     * </p>
     * <p>
     * 使用示例：
     * <pre>{@code
     * String clientType = (String) config.getConfig(Config.KEYS.CLIENT.USE);
     * String baseDir = (String) config.getConfig(Config.KEYS.CLIENT.BASE_DIR);
     * }</pre>
     * </p>
     */
    public interface KEYS {
        /**
         * 云存储客户端相关配置
         */
        interface CLIENT {
            /**
             * 使用的云存储类型（如 "onedrive" 或 "dropbox"）
             */
            String USE = "client.use";

            /**
             * 云端数据存储的基础目录路径
             * <p>
             * 所有文件操作的相对路径都会以此目录为前缀。
             * 例如设置为 "backups/minecraft" 后，上传 "world.zip" 实际会上传到 "backups/minecraft/world.zip"。
             * </p>
             */
            String BASE_DIR = "client.base-dir";

            /**
             * OneDrive 客户端配置
             */
            interface ONEDRIVE {
                /**
                 * 是否使用 OneDrive 应用文件夹模式
                 */
                String USE_APP_FOLDER = "client.onedrive.use-app-folder";

                /**
                 * OneDrive 应用客户端 ID
                 */
                String CLIENT_ID = "client.onedrive.client-id";

                /**
                 * OneDrive 应用客户端密钥
                 */
                String CLIENT_SECRET = "client.onedrive.client-secret";

                /**
                 * OneDrive OAuth2 刷新令牌
                 */
                String REFRESH_TOKEN = "client.onedrive.refresh-token";
            }

            /**
             * Dropbox 客户端配置
             */
            interface DROPBOX {
                /**
                 * Dropbox 应用密钥
                 */
                String APP_KEY = "client.dropbox.app-key";

                /**
                 * Dropbox 应用密钥
                 */
                String APP_SECRET = "client.dropbox.app-secret";

                /**
                 * Dropbox OAuth2 刷新令牌
                 */
                String REFRESH_TOKEN = "client.dropbox.refresh-token";
            }
        }

        /**
         * 配置文件中的版本号（用于判断配置文件是否过旧，是否需要补充新配置项）
         */
        String VERSION = "version";

        /**
         * 保留的最大全量备份数量
         */
        String MAX_FULL_BACKUPS_RETAINED = "max-full-backups-retained";

        /**
         * Cron 定时任务配置
         */
        interface CRON {
            /**
             * 全量备份的 Cron 表达式
             */
            String FULL_BACKUP = "cron.full-backup";

            /**
             * 增量备份的 Cron 表达式
             */
            String INCREMENTAL_BACKUP = "cron.incremental-backup";
        }

        /**
         * 无玩家在线时是否停止增量备份
         */
        String STOP_INCREMENTAL_BACKUP_WHEN_NO_PLAYER = "stop-incremental-backup-when-no-player";

        /**
         * 无玩家在线时是否停止全量备份
         */
        String STOP_FULL_BACKUP_WHEN_NO_PLAYER = "stop-full-backup-when-no-player";

        /**
         * 是否使用流式压缩上传（边压缩边上传）
         */
        String USE_STREAMING_COMPRESSION_UPLOAD = "use-streaming-compression-upload";

        /**
         * 需要备份的目录路径列表
         */
        String PATHS = "paths";
    }

    /**
     * 构造配置管理对象
     * <p>
     * 初始化时会自动加载配置文件 {@code configs.yml}。
     * 如果配置文件不存在，会从插件资源中复制默认配置。
     * </p>
     *
     * @see #loadConfig()
     */
    public Config() {
        plugin = PotatoSack.getPluginInstance();
        loadConfig();
    }

    /**
     * 加载配置文件 {@code configs.yml}
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查并创建插件数据目录（{@code plugins/PotatoSack/}）</li>
     *   <li>如果配置文件不存在，从插件 JAR 中的 {@code resources/configs.yml} 复制默认配置</li>
     *   <li>加载配置文件内容到内存</li>
     *   <li>执行配置完整性检查，补充缺失的配置项</li>
     * </ol>
     * </p>
     * <p>
     * 此方法在构造函数中自动调用，通常无需手动调用。
     * </p>
     *
     * @see #inspectConfig()
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
     * 保存配置到文件
     * <p>
     * 将内存中的配置内容写入到 {@code configs.yml} 文件。
     * 如果保存失败（如文件被占用、权限不足等），会在控制台输出错误日志。
     * </p>
     * <p>
     * 此方法在 {@link #setConfig(String, Object)} 和 {@link #inspectConfig()} 中自动调用。
     * </p>
     *
     * @see #setConfig(String, Object)
     */
    private void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            ConsoleSender.logError("Failed to save configs.yml: " + e.getMessage());
        }
    }

    /**
     * 获取完整的配置对象
     * <p>
     * 返回底层的 {@link YamlConfiguration} 对象，可用于访问所有配置项。
     * 如果配置尚未加载，会自动调用 {@link #loadConfig()} 进行加载。
     * </p>
     *
     * @return YAML 配置对象
     * @see YamlConfiguration
     */
    public YamlConfiguration getConfig() {
        if (config == null)
            loadConfig();
        return config;
    }

    /**
     * 获取指定路径的配置项值
     * <p>
     * 根据配置键路径获取对应的值。如果配置尚未加载，会自动加载配置文件。
     * 若指定路径不存在，返回空字符串 {@code ""}。
     * </p>
     * <p>
     * 使用示例：
     * <pre>{@code
     * String clientType = (String) config.getConfig(Config.KEYS.CLIENT.USE);
     * int maxBackups = (Integer) config.getConfig(Config.KEYS.MAX_FULL_BACKUPS_RETAINED);
     * }</pre>
     * </p>
     *
     * @param path 配置字段路径（使用点分隔的层级路径，如 {@code "client.use"}）
     * @return 配置项的值对象，如果不存在则返回空字符串
     * @see Config.KEYS
     */
    public Object getConfig(String path) {
        if (config == null)
            loadConfig();
        return config.get(path, "");
    }

    /**
     * 设置指定路径的配置项值
     * <p>
     * 修改指定配置项的值，并立即保存到配置文件。
     * 如果配置尚未加载，会自动加载配置文件。
     * </p>
     * <p>
     * 使用示例：
     * <pre>{@code
     * config.setConfig(Config.KEYS.CLIENT.USE, "dropbox");
     * config.setConfig(Config.KEYS.CLIENT.BASE_DIR, "backups/minecraft");
     * config.setConfig(Config.KEYS.MAX_FULL_BACKUPS_RETAINED, 20);
     * }</pre>
     * </p>
     *
     * @param path  配置字段路径（使用点分隔的层级路径，如 {@code "client.use"}）
     * @param value 要设置的配置值，可以是字符串、数字、布尔值、列表等 YAML 支持的类型
     * @see Config.KEYS
     * @see #saveConfig()
     */
    public void setConfig(String path, Object value) {
        if (config == null)
            loadConfig();
        config.set(path, value);
        saveConfig();
    }

    /**
     * 重新加载配置文件内容
     * <p>
     * 从磁盘重新读取 {@code configs.yml} 文件，刷新内存中的配置内容。
     * 重新加载后会自动执行配置完整性检查。
     * </p>
     * <p>
     * 此方法常用于：
     * <ul>
     *   <li>手动编辑配置文件后重新加载</li>
     *   <li>插件热重载时刷新配置</li>
     *   <li>命令触发的配置重载操作</li>
     * </ul>
     * </p>
     *
     * @return {@code true} 表示重载成功，{@code false} 表示重载失败（如文件格式错误）
     * @see #inspectConfig()
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
     * 获得插件版本号，读取自 {@code plugin.yml} 中的 version 字段
     *
     * @return 插件版本字符串
     */
    public String getPluginVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * 配置文件 {@code configs.yml} 完整性检查
     * <p>
     * 检查所有必需的配置项是否存在，对于缺失的配置项自动填充默认值。
     * 这样可以确保即使用户配置文件不完整或版本过旧，插件也能正常运行。
     * </p>
     * <p>
     * 检查并补充的配置项包括：
     * <ul>
     *   <li>云存储客户端配置（类型、base-dir、认证信息等）</li>
     *   <li>备份策略配置（保留数量、定时任务、无人上线处理等）</li>
     *   <li>性能配置（流式压缩上传开关）</li>
     *   <li>备份目录路径列表</li>
     * </ul>
     * </p>
     * <p>
     * 此方法在配置加载和重载时自动调用，检查完成后会自动保存配置文件。
     * </p>
     *
     * @see #loadConfig()
     * @see #reload()
     */
    private void inspectConfig() {
        if (config.get(KEYS.VERSION) == null) {
            // 版本号配置项不存在，说明是旧版本的配置文件，设置默认版本号为 "legacy"
            config.set(KEYS.VERSION, "legacy");
        }
        if (config.get(KEYS.CLIENT.USE) == null) {
            config.set(KEYS.CLIENT.USE, "onedrive");
        }
        if (config.get(KEYS.CLIENT.BASE_DIR) == null) {
            config.set(KEYS.CLIENT.BASE_DIR, "");
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
        if (config.get(KEYS.CLIENT.DROPBOX.APP_KEY) == null) {
            config.set(KEYS.CLIENT.DROPBOX.APP_KEY, "");
        }
        if (config.get(KEYS.CLIENT.DROPBOX.APP_SECRET) == null) {
            config.set(KEYS.CLIENT.DROPBOX.APP_SECRET, "");
        }
        if (config.get(KEYS.CLIENT.DROPBOX.REFRESH_TOKEN) == null) {
            config.set(KEYS.CLIENT.DROPBOX.REFRESH_TOKEN, "");
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
        if (config.get(KEYS.STOP_FULL_BACKUP_WHEN_NO_PLAYER) == null) {
            config.set(KEYS.STOP_FULL_BACKUP_WHEN_NO_PLAYER, false);
        }
        if (config.get(KEYS.USE_STREAMING_COMPRESSION_UPLOAD) == null) {
            config.set(KEYS.USE_STREAMING_COMPRESSION_UPLOAD, false);
        }
        if (config.get(KEYS.PATHS) == null) {
            List<String> paths = new ArrayList<>();
            config.set(KEYS.PATHS, paths);
        }
        saveConfig();
    }
}
