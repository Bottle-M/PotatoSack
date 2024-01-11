package indi.somebottle.potatosack.entities.backup;

import indi.somebottle.potatosack.utils.Utils;

import java.io.File;

/**
 * 主要用于Utils.ZipSpecificFiles方法，表示 文件绝对路径 -> Zip包内文件路径
 */
public class ZipFilePath {

    public String filePath;
    public String zipFilePath;
    public ZipFilePath(String filePath) {
        this.filePath = filePath;
        // zipFilePath缺省时默认会采用Utils.pathRelativeToServer方法来获得相对路径
        this.zipFilePath = Utils.pathRelativeToServer(new File(filePath));
    }
    public ZipFilePath(String filePath, String zipFilePath) {
        this.filePath = filePath;
        this.zipFilePath = zipFilePath;
    }
}
