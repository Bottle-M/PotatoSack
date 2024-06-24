package indi.somebottle;

import com.google.gson.annotations.SerializedName;

import java.util.List;

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
    @SerializedName("incre_backups_history")
    private List<IncreBackupHistoryItem> increBackupsHistory; // 增量备份历史记录 (备份组号 id, 备份时间戳 time)

    public static class IncreBackupHistoryItem {
        private String id;
        private long time;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }
    }

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

    public List<IncreBackupHistoryItem> getIncreBackupsHistory() {
        return increBackupsHistory;
    }

    public void setIncreBackupsHistory(List<IncreBackupHistoryItem> increBackupsHistory) {
        this.increBackupsHistory = increBackupsHistory;
    }

    /**
     * 添加一条增量备份记录
     *
     * @param id   增量备份 ID
     * @param time 备份时间戳
     */
    public void addIncreBackupHistoryItem(String id, long time) {
        IncreBackupHistoryItem item = new IncreBackupHistoryItem();
        item.setId(id);
        item.setTime(time);
        increBackupsHistory.add(item);
    }

    /**
     * 清空增量备份历史记录
     */
    public void clearIncreBackupsHistory() {
        increBackupsHistory.clear();
    }
}
