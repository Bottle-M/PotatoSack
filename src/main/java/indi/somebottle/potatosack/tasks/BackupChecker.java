package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.utils.*;
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
            // STEP 1 ------------------------- 先检查是不是该进行全量备份了
            String fullBackupCronExp = (String) config.getConfig(Config.KEYS.CRON.FULL_BACKUP);
            CronUtils fullBackupCron = new CronUtils(fullBackupCronExp);
            // 下一次全量备份开始的时间戳
            long nextFullBackupTimestamp = fullBackupCron.nextExecutionTimestamp(backupMaker.getLastFullBackupTime());
            if (Utils.timestamp() >= nextFullBackupTimestamp) {
                // 时间到了，检查是否启用了"无人上线时停止全量备份"功能
                if ((boolean) config.getConfig(Config.KEYS.STOP_FULL_BACKUP_WHEN_NO_PLAYER_JOIN)) {
                    // 检查是否有玩家上线过
                    if (!LocalStatus.getInstance().getFullBackupFlag()) {
                        // 没有玩家上线，跳过本次全量备份
                        return;
                    }
                }
                // 该进行全量备份了
                backupRun = true;
                boolean bkRes = backupMaker.makeFullBackup();
                if (!bkRes)
                    throw new IOException("Failed to make full backup");
                else
                    return; // 执行了全量备份，就不检查增量备份了
            }
            // STEP 2 ------------------------- 检查是不是需要进行增量备份了
            // 如果在线人数为0且配置了【无人时不进行增量备份】，则不进行增量备份检查
            if (Bukkit.getOnlinePlayers().size() < 1 && (boolean) config.getConfig(Config.KEYS.STOP_INCREMENTAL_BACKUP_WHEN_NO_PLAYER))
                return;
            String increBackupCronExp = (String) config.getConfig(Config.KEYS.CRON.INCREMENTAL_BACKUP);
            CronUtils increBackupCron = new CronUtils(increBackupCronExp);
            // 下一次增量备份开始的时间戳
            long nextIncreBackupTimestamp = increBackupCron.nextExecutionTimestamp(backupMaker.getLastIncreBackupTime());
            if (Utils.timestamp() >= nextIncreBackupTimestamp) {
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
