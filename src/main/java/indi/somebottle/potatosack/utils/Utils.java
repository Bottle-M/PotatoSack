package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.WorldRecord;
import org.bukkit.Bukkit;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class Utils {
    public static long timeStamp() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 从文件中指定位置开始读取指定字节数
     *
     * @param file   文件
     * @param start  开始位置
     * @param length 读取字节数
     * @return 读取的字节数组
     * @throws IOException IO
     */
    public static byte[] readBytesFromFile(File file, long start, int length) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            // 分配一个字节数组来存储读取的数据
            byte[] chunkData = new byte[length];
            // 将文件的特定部分读入字节数组
            randomAccessFile.seek(start); // 将文件指针移动到指定位置
            randomAccessFile.read(chunkData, 0, length); // 读取指定字节数
            return chunkData;
        } catch (IOException e) {
            throw new IOException("Error reading bytes from file", e);
        }
    }

    /**
     * 遍历获取指定目录下所有文件的最后修改时间
     *
     * @param srcDir     待扫描目录（File对象）
     * @param res        （递归用） 调用时传入null即可
     * @param parentPath （递归用），传入null即可。存储父级相对目录，比如/root/server/world/data/test.file, 则parentPath=world/data
     * @return List<WorldRecord.PathAndTime>对象
     */
    public static List<WorldRecord.PathAndTime> getLastModifyTimes(File srcDir, List<WorldRecord.PathAndTime> res, String parentPath) {
        if (res == null)
            res = new ArrayList<>();
        if (parentPath == null)
            parentPath = srcDir.getName();
        File[] files = srcDir.listFiles();
        for (File file : files) {
            if (file.isFile()) {
                // 是文件则加入
                res.add(
                        new WorldRecord.PathAndTime()
                                .setPath(parentPath + "/" + file.getName())
                                .setTime(file.lastModified() / 1000) // 秒级时间戳
                );
            } else if (file.isDirectory()) {
                // 是目录则继续
                getLastModifyTimes(file, res, parentPath + "/" + file.getName());
            }
        }
        return res;
    }

    /**
     * 把目录下所有文件打包成zip
     *
     * @param srcDirPath   源目录路径
     * @param zipFilePath  目标zip文件路径
     * @param packAsSrcDir 是否把srcDirPath下的所有文件都放在压缩包的【srcDirPath指向的目录名】的目录下
     * @return 是否打包成功
     * @apiNote 比如srcDirPath='./test/myfolder'，如果packAsSrcDir=true，那么打包后的zip包中根目录下是myfolder，其中是myfolder中的所有文件； 否则根目录下则是myfolder内的所有文件。
     */
    public static boolean Zip(String srcDirPath, String zipFilePath, boolean packAsSrcDir) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)))
        ) {
            File srcDir = new File(srcDirPath);
            System.out.println("Compressing... ");
            addItemsToZip(srcDir, packAsSrcDir ? srcDir.getName() : "", zout);
            zout.closeEntry();
            zout.flush();
            System.out.println("Compress success. File: " + zipFilePath);
            return true;
        } catch (Exception e) {
            Utils.logError("Compression failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 指定多个目录打包成zip
     *
     * @param srcDirPath  String[] ，指定要打包的目录路径（注意：路径需要是同一目录下的子目录）
     * @param zipFilePath String 指定打包后的zip文件路径
     * @return 是否打包成功
     */
    public static boolean Zip(String[] srcDirPath, String zipFilePath) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)))
        ) {
            System.out.println("Compressing... ");
            for (String path : srcDirPath) {
                File srcDir = new File(path);
                // 将指定目录内容加入包中
                addItemsToZip(srcDir, srcDir.getName(), zout);
            }
            zout.closeEntry();
            zout.flush();
            System.out.println("Compress success. File: " + zipFilePath);
            return true;
        } catch (Exception e) {
            Utils.logError("Compression failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * （递归方法） 扫描目录下所有文件并存入Zip Entries
     *
     * @param srcDir    源目录File对象
     * @param parentDir 父目录路径
     * @param zout      Zip输出流（Buffered）
     * @throws Exception 打包失败抛出异常
     */
    private static void addItemsToZip(File srcDir, String parentDir, ZipOutputStream zout) throws Exception {
        File[] files = srcDir.listFiles();
        if (files == null) {
            throw new Exception("Error: file list is null, this should not happen!");
        }
        for (File file : files) {
            String currentDir = (parentDir.equals("") ? "" : (parentDir + "/")) + file.getName();
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                addItemsToZip(file, currentDir, zout);
            } else {
                // 如果是文件就写入Zip
                try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(file))) {
                    System.out.println("Add file: " + currentDir);
                    // 将条目（文件）加入zip包
                    ZipEntry zipEntry = new ZipEntry(currentDir);
                    zout.putNextEntry(zipEntry);
                    // 写入文件
                    int len;
                    byte[] buf = new byte[8192];
                    while ((len = bin.read(buf)) != -1) {
                        zout.write(buf, 0, len);
                    }
                }
            }
        }
    }

    /**
     * 记录插件出错信息（方便追溯）
     *
     * @param msg 错误信息字符串
     * @apiNote 本方法会将错误信息记入服务端日志，同时打印到控制台
     */
    public static void logError(String msg) {
        String finalMsg = "[" + Constants.PLUGIN_PREFIX + "] " + msg;
        System.out.println(msg);
        if (PotatoSack.plugin != null) {
            // 记录到服务端日志
            // 因为logError可能在异步方法中被调用，这里需要把getLogger.severe通过runTask放回主线程调用
            Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
                PotatoSack.plugin.getLogger().severe(finalMsg);
            });
        }
    }

    /**
     * 获得日期字符串，形如20240104
     *
     * @return 日期字符串
     */
    public static String getDateStr() {
        return new SimpleDateFormat("yyyyMMdd").format(new Date());
    }
}