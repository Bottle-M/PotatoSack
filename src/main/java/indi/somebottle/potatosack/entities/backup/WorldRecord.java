package indi.somebottle.potatosack.entities.backup;

import java.util.List;
import java.util.Map;

/**
 * 用于解析 世界名.json
 */
public class WorldRecord {
    private long fileUpdateTime; // 世界名.json文件更新时间戳
    private Map<String, String[]> lastModifyTimes; // 存放世界数据目录中所有文件的最后修改时间

    public long getFileUpdateTime() {
        return fileUpdateTime;
    }

    public void setFileUpdateTime(long fileUpdateTime) {
        this.fileUpdateTime = fileUpdateTime;
    }

    public Map<String, String[]> getLastModifyTimes() {
        return lastModifyTimes;
    }

    public void setLastModifyTimes(Map<String, String[]> lastModifyTimes) {
        this.lastModifyTimes = lastModifyTimes;
    }
}
