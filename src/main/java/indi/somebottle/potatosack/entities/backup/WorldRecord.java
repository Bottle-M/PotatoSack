package indi.somebottle.potatosack.entities.backup;

import java.util.Map;

/**
 * 用于解析 _世界名.json
 */
public class WorldRecord {
    private long fileUpdateTime; // 世界名.json文件更新时间戳
    private Map<String, String> lastFileHashes; // 存放世界数据目录中所有文件的最后哈希值

    public long getFileUpdateTime() {
        return fileUpdateTime;
    }

    public void setFileUpdateTime(long fileUpdateTime) {
        this.fileUpdateTime = fileUpdateTime;
    }

    /**
     * 获得 _世界名.json 中的 lastFileHashes，即键值对：<相对服务器根目录的路径, 文件哈希>
     * @return Map<文件相对服务器根目录的路径, 文件哈希>
     */
    public Map<String, String> getLastFileHashes() {
        return lastFileHashes;
    }

    public void setLastFileHashes(Map<String, String> lastFileHashes) {
        this.lastFileHashes = lastFileHashes;
    }
}
