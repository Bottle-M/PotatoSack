package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class BackupChecker implements Runnable {
    private final BackupMaker backupMaker;
    private final Client odClient;
    private final Config config;
    private final Gson gson = new Gson();

    public BackupChecker(Client odClient, Config config) {
        this.odClient = odClient; // 初始化OneDrive客户端
        this.backupMaker = new BackupMaker(odClient, config); // 初始化备份核心模块
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
                    backupMaker.writeBackupRecord(0, 0, "","");
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
                        backupMaker.writeWorldRecord(worldName, new HashMap<>());
                    } else {
                        return false;
                    }
                }
        }
        return true;
    }

    @Override
    public void run() {
        try {
            // 先检查是不是该进行全量备份了
            BackupRecord bkRec = backupMaker.getBackupRecord();
            long fullBackupInterval = (long) config.getConfig("full-backup-interval");
            if (Utils.timeStamp() - bkRec.getLastFullBackup() > fullBackupInterval) {
                // 该进行全量备份了
                if (!backupMaker.makeFullBackup())
                    throw new IOException("Failed to make full backup");
                else
                    return; // 执行了全量备份，就不检查增量备份了
            }
            // 检查是不是需要进行增量备份了
            long increBackupInterval = (long) config.getConfig("incremental-backup-check-interval");
            if (Utils.timeStamp() - bkRec.getLastIncreBackup() > increBackupInterval) {
                // 该进行增量备份了
                if (!backupMaker.makeIncreBackup())
                    throw new IOException("Failed to make incremental backup");
            }
        } catch (IOException e) {
            Utils.logError(e.getMessage());
        }
    }
}
