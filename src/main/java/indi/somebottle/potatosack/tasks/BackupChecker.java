package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.onedrive.Client;

public class BackupChecker implements Runnable {
    private final BackupMaker backupMaker;
    private final Client odClient;

    public BackupChecker(Client odClient) {
        this.odClient = odClient; // 初始化OneDrive客户端
        this.backupMaker = new BackupMaker(odClient); // 初始化备份核心模块
    }

    /**
     * 初始化备份模块
     * @return 是否初始化成功
     */
    public boolean initialize() {
        // 首先检查插件目录是否有backup.json
        if(!backupMaker.getBackupRecordFile().exists()){
            // 从云端抓取getBackupRecordsFile

        }
        return true;
    }

    @Override
    public void run() {

    }
}
