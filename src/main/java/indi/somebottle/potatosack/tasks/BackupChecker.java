package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.entities.backup.WorldRecord;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class BackupChecker implements Runnable {
    private final BackupMaker backupMaker;
    private final Client odClient;
    private final Config config;
    private final Gson gson = new Gson();

    public BackupChecker(Client odClient, Config config) {
        this.odClient = odClient; // 初始化OneDrive客户端
        this.backupMaker = new BackupMaker(odClient); // 初始化备份核心模块
        this.config = config;
    }

    /**
     * 初始化备份模块
     *
     * @return 是否初始化成功
     */
    public boolean initialize() throws IOException {
        File backupRecordFile = backupMaker.getBackupRecordFile();
        // 首先检查插件目录是否有backup.json
        if (!backupRecordFile.exists()) {
            // 从云端抓取文件
            if (!backupMaker.pullRecordsFile("backup")) {
                // 如果抓取失败就直接在本地新建文件
                if (backupRecordFile.createNewFile()) {
                    // 初始化文件JSON内容
                    BackupRecord rec = new BackupRecord();
                    rec.setLastFullBackup(0L);
                    rec.setLastIncreBackup(0L);
                    rec.setFileUpdateTime(Utils.timeStamp());
                    // 写入文件
                    Files.write(backupRecordFile.toPath(), gson.toJson(rec).getBytes());
                } else {
                    return false;
                }
            }
        }
        // 检查各个世界的记录文件是否存在
        List<String> worlds = (List<String>) config.getConfig("worlds");
        for (String worldName : worlds) {
            File worldRecordFile = backupMaker.getWorldRecordsFile(worldName);
            if (!worldRecordFile.exists())
                // 从云端抓取
                if (!backupMaker.pullRecordsFile(worldName)) {
                    // 如果抓取失败就直接在本地新建文件
                    if (worldRecordFile.createNewFile()) {
                        WorldRecord rec = new WorldRecord();
                        rec.setFileUpdateTime(Utils.timeStamp());
                        rec.setLastModifyTimes(new ArrayList<>());
                        // 写入文件
                        Files.write(worldRecordFile.toPath(), gson.toJson(rec).getBytes());
                    } else {
                        return false;
                    }
                }
        }
        return true;
    }

    @Override
    public void run() {

    }
}
