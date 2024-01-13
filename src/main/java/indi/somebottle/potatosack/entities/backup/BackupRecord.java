package indi.somebottle.potatosack.entities.backup;

import com.google.gson.annotations.SerializedName;

/**
 * 用于解析backup.json
 */
public class BackupRecord {
    @SerializedName("last_full_backup_time")
    private long lastFullBackupTime; // 上一次全量备份时间戳
    @SerializedName("last_incre_backup_time")
    private long lastIncreBackupTime; // 上一次增量备份时间戳
    @SerializedName("file_update_time")
    private long fileUpdateTime; // backup.json文件更新时间戳
    @SerializedName("last_full_backup_id")
    private String lastFullBackupId; // 最近的一次备份组号

    @SerializedName("last_incre_backup_id")
    private String lastIncreBackupId; // 最近的一次增量备份号

    public long getLastFullBackupTime() {
        return lastFullBackupTime;
    }

    public void setLastFullBackupTime(long lastFullBackupTime) {
        this.lastFullBackupTime = lastFullBackupTime;
    }

    public long getLastIncreBackupTime() {
        return lastIncreBackupTime;
    }

    public void setLastIncreBackupTime(long lastIncreBackupTime) {
        this.lastIncreBackupTime = lastIncreBackupTime;
    }

    public long getFileUpdateTime() {
        return fileUpdateTime;
    }

    public void setFileUpdateTime(long fileUpdateTime) {
        this.fileUpdateTime = fileUpdateTime;
    }

    public String getLastFullBackupId() {
        return lastFullBackupId;
    }

    public void setLastFullBackupId(String lastFullBackupId) {
        this.lastFullBackupId = lastFullBackupId;
    }

    public String getLastIncreBackupId() {
        return lastIncreBackupId;
    }

    public void setLastIncreBackupId(String lastIncreBackupId) {
        this.lastIncreBackupId = lastIncreBackupId;
    }
}
