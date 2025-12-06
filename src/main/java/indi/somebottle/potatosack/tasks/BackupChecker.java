package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.utils.BackupMutex;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;
import org.bukkit.Bukkit;

import java.io.IOException;

public class BackupChecker implements Runnable {
    private final static BackupMutex BACKUP_MUTEX = new BackupMutex();
    private final BackupMaker backupMaker;
    private final Config config;

    public BackupChecker(Client client, Config config) throws IOException {
        this.backupMaker = new BackupMaker(client, config); // 初始化备份核心模块
        this.config = config;
    }

    @Override
    public void run() {
        // 本次运行是否启动了备份任务
        boolean backupRun = false;
        try {
            // 如果已经有备份任务在执行，则不继续本次检查
            if (!BACKUP_MUTEX.occupy())
                return;
            // 先检查是不是该进行全量备份了
            long fullBackupInterval = Utils.objToLong(config.getConfig("full-backup-interval"));
            // 注意fullBackupInterval单位是分钟
            if (Utils.timeStamp() - backupMaker.getLastFullBackupTime() > fullBackupInterval * 60) {
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
            // 注意 increBackupInterval 单位是分钟
            if (Utils.timeStamp() - backupMaker.getLastIncreBackupTime() > increBackupInterval * 60) {
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
            BACKUP_MUTEX.release();
            if (backupRun) {
                // 如果运行了备份任务，则在这里进行清理工作
                backupMaker.cleanTempDir(); // 请理临时目录
            }
        }
    }
}
