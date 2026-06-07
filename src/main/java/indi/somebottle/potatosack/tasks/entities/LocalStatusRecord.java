package indi.somebottle.potatosack.tasks.entities;

import com.google.gson.annotations.SerializedName;

/**
 * 在本地存储插件的一些状态信息的 JSON DTO 类
 */
public class LocalStatusRecord {


    @SerializedName("full_backup_flag")
    private boolean fullBackupFlag;

    /**
     * 用于标记目前是否需要进行全量备份的状态量，插件可以配置在长时间没有玩家上线时停止全量备份
     * <p>
     * 在玩家上线时该项会被设置为 true，一次全量备份完成后会被设置为 false。如果启用了上述功能，
     * 则在后续没有玩家上线的情况下不会进行全量备份。
     */
    public boolean isFullBackupFlag() {
        return fullBackupFlag;
    }

    public void setFullBackupFlag(boolean fullBackupFlag) {
        this.fullBackupFlag = fullBackupFlag;
    }
}
