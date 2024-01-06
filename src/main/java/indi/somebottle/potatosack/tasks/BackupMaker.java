package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.driveitems.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.utils.Constants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录文件.json均放在插件目录plugins/PotatoSack/data下
 */
public class BackupMaker {
    private final Client odClient;

    public BackupMaker(Client odClient) {
        this.odClient = odClient;
    }

    public File getBackupRecordsFile() {
        return new File(PotatoSack.plugin.getDataFolder() + File.separator + "data" + File.separator + "backups.json");
    }

    /**
     * 根据世界名获取 世界名.json 记录文件
     *
     * @param worldName 世界名
     * @return File对象
     * @apiNote 世界名.json中存放世界数据目录中所有文件的最后修改时间
     */
    public File getWorldRecordsFile(String worldName) {
        return new File(PotatoSack.plugin.getDataFolder() + File.separator + "data" + File.separator + worldName + ".json");
    }

    /**
     * 从云端拉取backups.json
     *
     * @return 是否拉取成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     */
    public boolean pullBackupRecords() throws IOException {
        // 先对OneDrive下的插件数据目录进行列表
        List<Item> itemsRes;
        String latestFolderName=""; // 找出字典序上最大的一个子目录名，这里的目录名格式形如020240104000001
        itemsRes = odClient.listItems(Constants.OD_APP_DATA_FOLDER);
        for (Item item : itemsRes) {
            if (item.getName().compareTo(latestFolderName) > 0)
                latestFolderName = item.getName();
        }
        if(latestFolderName.equals(""))
            return false;
        // 从云端拉取backups.json

        return true;
    }


}
