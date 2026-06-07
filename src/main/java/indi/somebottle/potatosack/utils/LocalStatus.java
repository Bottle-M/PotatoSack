package indi.somebottle.potatosack.utils;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.tasks.entities.LocalStatusRecord;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * 用来设置和获取本地状态值的工具类 (单例)
 */
public class LocalStatus {
    private static LocalStatus instance;
    private static LocalStatusRecord localStatusRecord;
    private final String statusFilePath;
    private final Gson gson = new Gson();

    /**
     * 私有构造函数，初始化本地状态管理器
     *
     * @throws IOException 文件读写异常
     */
    private LocalStatus() throws IOException {
        Plugin plugin = PotatoSack.getPluginInstance();
        statusFilePath = plugin.getDataFolder() + File.separator + "local_status.json";
        loadFromFile();
    }

    /**
     * 获取 LocalStatus 单例实例
     *
     * @return LocalStatus 实例
     * @throws IOException 文件读写异常
     */
    public static LocalStatus getInstance() throws IOException {
        if (instance == null) {
            instance = new LocalStatus();
        }
        return instance;
    }

    /**
     * 获取全量备份标记位
     * <p>
     * true 表示需要进行全量备份，false 表示跳过全量备份
     *
     * @return 全量备份标记位
     */
    public boolean getFullBackupFlag() {
        return localStatusRecord.isFullBackupFlag();
    }

    /**
     * 设置全量备份标记位并立即持久化到文件
     *
     * @param flag 全量备份标记位，true 表示需要进行全量备份，false 表示跳过全量备份
     * @throws IOException 文件写入异常
     */
    public void setFullBackupFlag(boolean flag) throws IOException {
        localStatusRecord.setFullBackupFlag(flag);
        saveToFile();
    }

    /**
     * 获取增量备份标记位
     * <p>
     * true 表示需要进行增量备份，false 表示跳过增量备份
     *
     * @return 增量备份标记位
     */
    public boolean getIncreBackupFlag() {
        return localStatusRecord.isIncreBackupFlag();
    }

    /**
     * 设置增量备份标记位并立即持久化到文件
     *
     * @param flag 增量备份标记位，true 表示需要进行增量备份，false 表示跳过增量备份
     * @throws IOException 文件写入异常
     */
    public void setIncreBackupFlag(boolean flag) throws IOException {
        localStatusRecord.setIncreBackupFlag(flag);
        saveToFile();
    }

    /**
     * 从本地文件加载状态
     * <p>
     * 如果文件不存在，则创建新文件并初始化 fullBackupFlag 为 true
     *
     * @throws IOException 文件读写异常
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void loadFromFile() throws IOException {
        File statusFile = new File(statusFilePath);
        if (!statusFile.exists()) {
            // 文件不存在，创建新文件并初始化为 true
            if (!statusFile.getParentFile().exists())
                statusFile.getParentFile().mkdirs();
            localStatusRecord = new LocalStatusRecord();
            localStatusRecord.setFullBackupFlag(true);
            localStatusRecord.setIncreBackupFlag(true);
            saveToFile();
        } else {
            // 文件存在，读取并解析
            String json = new String(Files.readAllBytes(statusFile.toPath()));
            localStatusRecord = gson.fromJson(json, LocalStatusRecord.class);
        }
    }

    /**
     * 将当前状态保存到本地文件
     *
     * @throws IOException 文件写入异常
     */
    private void saveToFile() throws IOException {
        String json = gson.toJson(localStatusRecord);
        Files.write(new File(statusFilePath).toPath(), json.getBytes());
    }
}
