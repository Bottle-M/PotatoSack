package indi.somebottle.potatosack.entities.backup;

import java.util.List;

/**
 * 用于解析 世界名.json
 */
public class WorldRecord {
    private long fileUpdateTime; // 世界名.json文件更新时间戳
    private List<PathAndTime> lastModifyTimes; // 存放世界数据目录中所有文件的最后修改时间

    public static class PathAndTime {
        private String path; // 文件相对路径
        private long time; // 文件最后修改时间戳

        public String getPath() {
            return path;
        }

        public PathAndTime setPath(String path) {
            this.path = path;
            return this;
        }

        public long getTime() {
            return time;
        }

        public PathAndTime setTime(long time) {
            this.time = time;
            return this;
        }
    }

    public long getFileUpdateTime() {
        return fileUpdateTime;
    }

    public void setFileUpdateTime(long fileUpdateTime) {
        this.fileUpdateTime = fileUpdateTime;
    }

    public List<PathAndTime> getLastModifyTimes() {
        return lastModifyTimes;
    }

    public void setLastModifyTimes(List<PathAndTime> lastModifyTimes) {
        this.lastModifyTimes = lastModifyTimes;
    }
}
