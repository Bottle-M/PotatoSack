package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.entities.backup.WorldRecord;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
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
import java.util.*;
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
        // 建立必要的目录
        File tempDir = new File(pluginTempPath);
        if (!tempDir.exists())
            tempDir.mkdirs();
        File dataDir = new File(pluginDataPath);
        if (!dataDir.exists())
            dataDir.mkdirs();
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
     * @param lastFullBackupId    最近一次备份组号
     * @throws IOException IO异常
     */
    public void writeBackupRecord(long lastFullBackupTime, long lastIncreBackupTime, String lastFullBackupId, String lastIncreBackupId) throws IOException {
        BackupRecord rec = new BackupRecord();
        rec.setLastFullBackup(lastFullBackupTime);
        rec.setLastIncreBackup(lastIncreBackupTime);
        rec.setFileUpdateTime(Utils.timeStamp());
        rec.setLastFullBackupId(lastFullBackupId);
        rec.setLastIncreBackupId(lastIncreBackupId);
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
    public void writeWorldRecord(String worldName, Map<String, String[]> recList) throws IOException {
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
        String backupJson = new String(Files.readAllBytes(backupRecordFile.toPath()));
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
        String worldJson = new String(Files.readAllBytes(worldRecordFile.toPath()));
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
        return new File(pluginDataPath + worldName + ".json");
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
        // 获得世界名
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
            Map<String, String[]> lastModifyTimes = Utils.getLastModifyTimes(new File(worldAbsPath), null);
            // 世界名.json中存放世界数据目录中所有文件的最后修改时间
            writeWorldRecord(worldName, lastModifyTimes);
        }
        // 2. 开始压缩
        // 临时文件路径
        ConsoleSender.toConsole("Compressing...");
        String tempFilePath = pluginTempPath + "full" + Utils.timeStamp() + ".zip";
        // 压缩
        if (!Utils.Zip(worldPaths.toArray(new String[0]), tempFilePath, true))
            return false;
        // 3. 上传压缩好的文件
        String lastFullBackupId; // 备份组号
        if (rec.getLastFullBackupId().equals("")) {
            // 尚无备份组，从序号1开始
            lastFullBackupId = "0" + Utils.getDateStr() + "000001";
        } else {
            // 末尾序号
            int serial = Integer.parseInt(rec.getLastFullBackupId().substring(9)) + 1;
            lastFullBackupId = "0" + Utils.getDateStr() + String.format("%06d", serial);
        }
        ConsoleSender.toConsole("Uploading Full Backup...");
        if (!odClient.uploadLargeFile(tempFilePath, Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/full.zip"))
            return false;
        // 4. 更新备份记录
        // 写入backup.json
        rec.setLastFullBackupId(lastFullBackupId);
        rec.setLastFullBackup(Utils.timeStamp());
        writeBackupRecord(rec);
        // 5. 上传备份记录
        ConsoleSender.toConsole("Uploading Record Files...");
        if (!odClient.uploadFile(pluginDataPath + "backup.json", Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/backup.json"))
            return false;
        for (String worldName : worlds) {
            if (!odClient.uploadFile(pluginDataPath + worldName + ".json", Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/" + worldName + ".json"))
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
        ConsoleSender.toConsole("Successfully made backup: " + lastFullBackupId);
        return true;
    }

    /**
     * 建立一个增量备份
     *
     * @return 是否成功
     */
    public boolean makeIncreBackup() throws IOException {
        ConsoleSender.toConsole("Making incremental backup...");
        // 获得备份记录文件
        BackupRecord rec = getBackupRecord();
        String lastFullBackupId = rec.getLastFullBackupId();
        if (lastFullBackupId.equals("")) {
            // 如果还没有全量备份则进行全量备份
            Utils.logError("No full backup found, trying to make full backup first.");
            return makeFullBackup();
        }
        // 获得世界名
        List<String> worlds = (List<String>) config.getConfig("worlds");
        // 各个世界的目录路径（绝对路径）
        List<String> worldPaths = new ArrayList<>();
        // 记录相比上次增量备份时，被删除的文件的路径（相对路径）
        List<String> deletedPaths = new ArrayList<>();
        // 所有有变动文件的 【绝对路径】（方便Zip打包）
        List<ZipFilePath> increFilePaths = new ArrayList<>();
        // 1. 扫描每个世界目录找到有差异的文件
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
            Map<String, String[]> lastModifyTimes = Utils.getLastModifyTimes(new File(worldAbsPath), null);
            // 获得上一次增量备份时的文件修改时间
            WorldRecord prevWorldRec = getWorldRecord(worldName);
            Map<String, String[]> prevLastModifyTimes = prevWorldRec.getLastModifyTimes();
            // 找到被删除的文件的路径
            deletedPaths.addAll(
                    Utils.getDeletedFilePaths(prevLastModifyTimes, lastModifyTimes)
            );
            // 找到发生变动的文件的绝对路径
            for (String key : lastModifyTimes.keySet())
                // 新记录中新出现的文件 or 新记录中的文件最后修改时间相比旧记录有变动
                if (!prevLastModifyTimes.containsKey(key) || !prevLastModifyTimes.get(key)[1].equals(lastModifyTimes.get(key)[1]))
                    increFilePaths.add( // 添加到增量文件列表
                            new ZipFilePath(Utils.pathAbsToServer( // 获得文件绝对路径以便Zip打包
                                    lastModifyTimes.get(key)[0]
                            ))
                    );
            // 更新 世界名.json 中存放世界数据目录中所有文件的最后修改时间
            writeWorldRecord(worldName, lastModifyTimes);
        }
        // 2. 压缩
        // 写入deleted.files文件
        String deletedRecordPath = pluginTempPath + "deleted.files";
        String deletedFileContent = String.join("\n", deletedPaths) + "\n";
        Files.write(new File(deletedRecordPath).toPath(), deletedFileContent.getBytes());
        // 把deleted.files文件也加入压缩包
        increFilePaths.add(new ZipFilePath(deletedRecordPath, "deleted.files"));
        ConsoleSender.toConsole("Compressing...");
        // 压缩
        // 增量备份序号
        String increBackupId = rec.getLastIncreBackupId();
        if (increBackupId.equals("")) {
            // 若还没有序号记载则从1开始
            increBackupId = "000001";
        } else {
            // 否则在上一次的基础上递增
            long increBackupIdNum = Long.parseLong(increBackupId);
            increBackupId = String.format("%06d", increBackupIdNum + 1);
        }
        // 输出文件路径
        String outputPath = pluginTempPath + "incre" + increBackupId + ".zip";
        // 执行压缩
        if (!Utils.ZipSpecificFiles(increFilePaths.toArray(new ZipFilePath[0]), outputPath, true))
            return false;
        // 3. 上传
        ConsoleSender.toConsole("Uploading Incremental Backup...");
        if (!odClient.uploadFile(outputPath, Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/incre" + increBackupId + ".zip"))
            return false;
        // 4. 更新备份记录
        rec.setLastIncreBackupId(increBackupId);
        rec.setLastIncreBackup(Utils.timeStamp());
        writeBackupRecord(rec);
        // 5. 上传备份记录
        ConsoleSender.toConsole("Uploading Record Files...");
        if (!odClient.uploadFile(pluginDataPath + "backup.json", Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/backup.json"))
            return false;
        for (String worldName : worlds) {
            if (!odClient.uploadFile(pluginDataPath + worldName + ".json", Constants.OD_APP_DATA_FOLDER + "/" + lastFullBackupId + "/" + worldName + ".json"))
                return false;
        }
        return true;
    }
}
