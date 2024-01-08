package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.BackupRecord;
import indi.somebottle.potatosack.entities.driveitems.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Constants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 记录文件.json均放在插件目录plugins/PotatoSack/data下
 */
public class BackupMaker {
    private final Client odClient;
    private final String pluginDataPath;
    private final Gson gson = new Gson();

    public BackupMaker(Client odClient) {
        this.odClient = odClient;
        pluginDataPath = PotatoSack.plugin.getDataFolder() + File.separator + "data" + File.separator;
    }

    /**
     * 取得本地的backup.json对象
     *
     * @return BackupRecord对象
     * @apiNote 如果backup.json不存在本地，会从云端拉取
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
     */
    public boolean pullRecordsFile(String fileName) throws IOException {
        // 先对OneDrive下的插件数据目录进行列表
        List<Item> itemsRes;
        String latestFolderName = ""; // 找出字典序上最大的一个子目录名，这里的目录名格式形如020240104000001
        itemsRes = odClient.listItems(Constants.OD_APP_DATA_FOLDER);
        for (Item item : itemsRes) {
            if (item.getName().compareTo(latestFolderName) > 0)
                latestFolderName = item.getName();
        }
        if (latestFolderName.equals(""))
            return false;
        // 从云端拉取backup.json
        File recordFile = new File(pluginDataPath + fileName + ".json");
        return odClient.downloadFile(Constants.OD_APP_DATA_FOLDER + "/" + latestFolderName + "/" + fileName + ".json", recordFile);
    }


}
