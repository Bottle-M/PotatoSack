package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.entities.backup.WorldRecord;
import indi.somebottle.potatosack.entities.driveitems.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.Utils;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记录文件.json均放在插件目录plugins/PotatoSack/data下
 */
public class BackupMaker {
    private final Client odClient;
    // 记录等数据文件存放目录，plugins/PotatoSack/data
    private final String pluginDataPath;
    // 临时压缩包存放目录，plugins/PotatoSack/temp
    private final String pluginTempPath;
    private final Config config;
    private final Gson gson = new Gson();

    public BackupMaker(Client odClient, Config config) {
        this.odClient = odClient;
        this.config = config;
        pluginTempPath = PotatoSack.plugin.getDataFolder() + File.separator + "temp" + File.separator;
        pluginDataPath = PotatoSack.plugin.getDataFolder() + File.separator + "data" + File.separator;
    }

    /**
     * 写入本地的backup.json
     *
     * @param rec 备份记录BackupRecord对象
     * @throws IOException IO异常
     */
    public void writeBackupRecord(BackupRecord rec) throws IOException {
        rec.setFileUpdateTime(Utils.timeStamp());
        Files.write(getBackupRecordFile().toPath(), gson.toJson(rec).getBytes());
    }

    /**
     * 写入本地的backup.json
     *
     * @param lastFullBackupTime  最近一次全量备份时间戳
     * @param lastIncreBackupTime 最近一次增量备份时间戳
     * @param lastBackupId        最近一次备份组号
     * @throws IOException IO异常
     */
    public void writeBackupRecord(long lastFullBackupTime, long lastIncreBackupTime, String lastBackupId) throws IOException {
        BackupRecord rec = new BackupRecord();
        rec.setLastFullBackup(lastFullBackupTime);
        rec.setLastIncreBackup(lastIncreBackupTime);
        rec.setFileUpdateTime(Utils.timeStamp());
        rec.setLastBackupId(lastBackupId);
        writeBackupRecord(rec);
    }

    /**
     * 写入本地的 世界名.json
     *
     * @param worldName 世界名
     * @param rec       世界记录WorldRecord
     * @throws IOException IO异常
     */
    public void writeWorldRecord(String worldName, WorldRecord rec) throws IOException {
        rec.setFileUpdateTime(Utils.timeStamp());
        Files.write(getWorldRecordsFile(worldName).toPath(), gson.toJson(rec).getBytes());
    }

    /**
     * 写入本地的 世界名.json
     *
     * @param worldName 世界名
     * @param recList   世界记录WorldRecord
     * @throws IOException IO异常
     */
    public void writeWorldRecord(String worldName, List<WorldRecord.PathAndTime> recList) throws IOException {
        WorldRecord rec = new WorldRecord();
        rec.setFileUpdateTime(Utils.timeStamp());
        rec.setLastModifyTimes(recList);
        writeWorldRecord(worldName, rec);
    }

    /**
     * 取得本地的backup.json对象
     *
     * @return BackupRecord对象
     * @apiNote 如果backup.json不存在本地，会从云端拉取，拉取失败会返回null
     */
    public BackupRecord getBackupRecord() throws IOException {
        File backupRecordFile = getBackupRecordFile();
        if (!backupRecordFile.exists())
            if (!pullRecordsFile("backup"))
                return null; // 获取失败
        // 读入backup.json
        String backupJson = Arrays.toString(Files.readAllBytes(backupRecordFile.toPath()));
        // 解析成配置对象
        return gson.fromJson(backupJson, BackupRecord.class);
    }

    /**
     * 获得世界数据记录（世界名.json）
     *
     * @param worldName 世界名
     * @return WorldRecord对象
     * @throws IOException IO异常
     */
    public WorldRecord getWorldRecord(String worldName) throws IOException {
        File worldRecordFile = getWorldRecordsFile(worldName);
        if (!worldRecordFile.exists())
            if (!pullRecordsFile(worldName))
                return null; // 获取失败
        // 读入backup.json
        String worldJson = Arrays.toString(Files.readAllBytes(worldRecordFile.toPath()));
        // 解析成配置对象
        return gson.fromJson(worldJson, WorldRecord.class);
    }

    /**
     * 获得本地的backup.json文件对象
     *
     * @return File对象
     */
    public File getBackupRecordFile() {
        return new File(pluginDataPath + "backup.json");
    }

    /**
     * 根据世界名获取 世界名.json 记录文件
     *
     * @param worldName 世界名
     * @return File对象
     * @apiNote 世界名.json中存放世界数据目录中所有文件的最后修改时间
     */
    public File getWorldRecordsFile(String worldName) {
        return new File(pluginDataPath + ".json");
    }

    /**
     * 从云端拉取数据目录中的json文件 PotatoSack/备份组号/*.json
     *
     * @param fileName 文件名
     * @return 是否拉取成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 文件名不包含.json后缀
     */
    public boolean pullRecordsFile(String fileName) throws IOException {
        return pullRecordsFile(new String[]{fileName});
    }

    /**
     * 从云端拉取数据目录中的json文件 PotatoSack/备份组号/*.json
     *
     * @param fileName 文件名数组String[]，指定要下载的一组json文件
     * @return 是否拉取成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 文件名不包含.json后缀
     */
    public boolean pullRecordsFile(String[] fileName) throws IOException {
        // 先对OneDrive下的插件数据目录进行列表
        List<Item> itemsRes;
        String latestFolderName = ""; // 找出字典序上最大的一个子目录名，这里的目录名格式形如020240104000001
        itemsRes = odClient.listItems(Constants.OD_APP_DATA_FOLDER);
        for (Item item : itemsRes) {
            if (item.isFolder() && item.getName().compareTo(latestFolderName) > 0)
                latestFolderName = item.getName();
        }
        if (latestFolderName.equals(""))
            return false;
        // 从云端拉取backup.json
        boolean success = true;
        for (String name : fileName) {
            File recordFile = new File(pluginDataPath + name + ".json");
            success = odClient.downloadFile(Constants.OD_APP_DATA_FOLDER + "/" + latestFolderName + "/" + name + ".json", recordFile) && success;
        }
        return success;
    }

    /**
     * 建立一个全量备份
     *
     * @return 是否成功
     * @apiNote 本方法会进行: <p>1. 压缩相应文件</p><p>2. 上传压缩后的文件</p><p>3. 检查是否有需要删除的备份组（只保留几组）</p>
     */
    public boolean makeFullBackup() throws IOException {
        ConsoleSender.toConsole("Making full backup...");
        // 获得备份记录文件
        BackupRecord rec = getBackupRecord();
        // 压缩相应文件
        List<String> worlds = (List<String>) config.getConfig("worlds");
        // 各个世界的目录路径
        List<String> worldPaths = new ArrayList<>();
        // 1. 扫描世界目录生成包含每个文件最后修改时间的 世界名.json
        for (String worldName : worlds) {
            // 获得各个世界的配置文件;
            World world = PotatoSack.plugin.getServer().getWorld(worldName);
            if (world == null) {
                Utils.logError("World " + worldName + " not found, ignored.");
                continue;
            }
            String worldAbsPath = world.getWorldFolder().getAbsolutePath();
            worldPaths.add(worldAbsPath);
            // 扫描世界目录下的所有文件，获得修改时间（为增量备份做准备）
            List<WorldRecord.PathAndTime> lastModifyTimes = Utils.getLastModifyTimes(new File(worldAbsPath), null, null);
            // 世界名.json中存放世界数据目录中所有文件的最后修改时间
            writeWorldRecord(worldName, lastModifyTimes);
        }
        // 2. 开始压缩
        // 临时文件路径
        ConsoleSender.toConsole("Compressing...");
        String tempFilePath = pluginTempPath + "full" + Utils.timeStamp() + ".zip";
        // 压缩
        if (!Utils.Zip(worldPaths.toArray(new String[0]), tempFilePath))
            return false;
        // 3. 上传压缩好的文件
        String backUpId; // 备份组号
        if (rec.getLastBackupId().equals("")) {
            // 尚无备份组，从序号1开始
            backUpId = "0" + Utils.getDateStr() + "000001";
        } else {
            // 末尾序号
            int serial = Integer.parseInt(rec.getLastBackupId().substring(9)) + 1;
            backUpId = "0" + Utils.getDateStr() + String.format("%06d", serial);
        }
        ConsoleSender.toConsole("Uploading Zipped Worlds...");
        if (!odClient.uploadLargeFile(tempFilePath, Constants.OD_APP_DATA_FOLDER + "/" + backUpId + "/full.zip"))
            return false;
        // 4. 更新备份记录
        // 写入backup.json
        rec.setLastBackupId(backUpId);
        rec.setLastFullBackup(Utils.timeStamp());
        writeBackupRecord(rec);
        // 5. 上传备份记录
        ConsoleSender.toConsole("Uploading Record Files...");
        if (!odClient.uploadFile(pluginDataPath + "backup.json", Constants.OD_APP_DATA_FOLDER + "/" + backUpId + "/backup.json"))
            return false;
        for (String worldName : worlds) {
            if (!odClient.uploadFile(pluginDataPath + worldName + ".json", Constants.OD_APP_DATA_FOLDER + "/" + backUpId + "/" + worldName + ".json"))
                return false;
        }
        // 6. 删除过时备份
        // 列出云端目录中所有目录
        List<Item> itemsRes = odClient.listItems(Constants.OD_APP_DATA_FOLDER);
        // 筛出目录
        // 按字典升序排序，把老备份放在前面
        List<Item> folderItems = itemsRes.stream()
                .filter(Item::isFolder)
                .sorted(Comparator.comparing(Item::getName))
                .collect(Collectors.toList());
        int deleteCnt = folderItems.size() - (int) config.getConfig("max-full-backups-retained");
        if (deleteCnt > 0) {
            ConsoleSender.toConsole("Deleting Old Backups...");
            for (int i = 0; i < deleteCnt; i++) {
                // 移除多余备份
                String deleteName = folderItems.get(i).getName();
                if (!odClient.deleteItem(Constants.OD_APP_DATA_FOLDER + "/" + deleteName))
                    return false;
            }
        }
        ConsoleSender.toConsole("Successfully made backup: " + backUpId);
        return true;
    }


}
