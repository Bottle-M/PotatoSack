package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class BackupChecker implements Runnable {
    private final BackupMaker backupMaker;
    private final Config config;

    public BackupChecker(Client odClient, Config config) {
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
        System.out.println("Reading backup record file: " + backupRecordFile.getAbsolutePath());
        // 首先检查插件目录是否有backup.json
        if (!backupRecordFile.exists()) {
            // 从云端抓取文件
            if (!backupMaker.pullRecordsFile("backup")) {
                // 如果抓取失败就直接在本地新建文件
                if (!backupRecordFile.getParentFile().exists()) // 要先把必要的目录给建立了
                    backupRecordFile.getParentFile().mkdirs();
                if (backupRecordFile.createNewFile()) {
                    // 初始化文件JSON内容
                    backupMaker.writeBackupRecord(0, 0, "", "", new ArrayList<>());
                } else {
                    return false;
                }
            }
        }
        // 检查各个世界的记录文件是否存在
        List<String> worlds = (List<String>) config.getConfig("worlds");
        for (String worldName : worlds) {
            File worldRecordFile = backupMaker.getWorldRecordsFile(worldName);
            System.out.println("Reading world record file: " + worldRecordFile.getAbsolutePath());
            if (!worldRecordFile.exists()) {
                // 从云端抓取
                if (!backupMaker.pullRecordsFile("_" + worldName)) {
                    if (!worldRecordFile.getParentFile().exists()) // 要先把必要的目录给建立了
                        worldRecordFile.getParentFile().mkdirs();
                    // 如果抓取失败就直接在本地新建文件
                    if (worldRecordFile.createNewFile()) {
                        backupMaker.writeWorldRecord(worldName, new HashMap<>());
                    } else {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public void run() {
        // 本次运行是否启动了备份任务
        boolean backupRun = false;
        try {
            // 如果已经有备份任务在执行，则不继续本次检查
            if (!Utils.BACKUP_MUTEX.occupy())
                return;
            // 先检查是不是该进行全量备份了
            BackupRecord bkRec = backupMaker.getBackupRecord();
            long fullBackupInterval = Utils.objToLong(config.getConfig("full-backup-interval"));
            // 注意fullBackupInterval单位是分钟
            if (Utils.timeStamp() - bkRec.getLastFullBackupTime() > fullBackupInterval * 60) {
                // 该进行全量备份了
                backupRun = true;
                boolean bkRes = backupMaker.makeFullBackup();
                if (!bkRes)
                    throw new IOException("Failed to make full backup");
                else
                    return; // 执行了全量备份，就不检查增量备份了
            }
            // 检查是不是需要进行增量备份了
            // 如果在线人数为0且配置了【无人时不进行增量备份】，则不进行增量备份检查
            if (Bukkit.getOnlinePlayers().size() < 1 && (boolean) config.getConfig("stop-incremental-backup-when-no-player"))
                return;
            long increBackupInterval = Utils.objToLong(config.getConfig("incremental-backup-check-interval"));
            // 注意increBackupInterval单位是分钟
            if (Utils.timeStamp() - bkRec.getLastIncreBackupTime() > increBackupInterval * 60) {
                // 该进行增量备份了
                backupRun = true;
                boolean bkRes = backupMaker.makeIncreBackup();
                if (!bkRes)
                    throw new IOException("Failed to make incremental backup");
            }
        } catch (Exception e) {
            ConsoleSender.logError("[BackupChecker] " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 最终一定要释放锁
            Utils.BACKUP_MUTEX.release();
            if (backupRun) {
                // 如果运行了备份任务，则在这里进行清理工作
                backupMaker.cleanTempDir(); // 请理临时目录
            }
        }
    }
}
