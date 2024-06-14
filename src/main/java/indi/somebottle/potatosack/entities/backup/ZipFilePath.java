package indi.somebottle.potatosack.entities.backup;

import indi.somebottle.potatosack.utils.Utils;

import java.io.File;

/**
 * 主要用于Utils.ZipSpecificFiles方法，表示 文件绝对路径 -> Zip包内文件路径
 */
public class ZipFilePath {

    public String filePath; // 文件绝对路径
    public String zipFilePath; // 文件相对于服务端根目录的相对路径（同时也是在 zip 包内的路径）

    public ZipFilePath(String filePath) {
        this.filePath = filePath;
        // zipFilePath缺省时默认会采用Utils.pathRelativeToServer方法来获得相对路径
        this.zipFilePath = Utils.pathRelativeToServer(new File(filePath));
    }

    public ZipFilePath(String filePath, String zipFilePath) {
        this.filePath = filePath;
        this.zipFilePath = zipFilePath;
    }

    @Override
    public String toString() {
        return "[" + filePath + " -> " + zipFilePath + "]";
    }
}
