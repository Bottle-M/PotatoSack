package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;

import java.io.*;
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
        System.out.println(msg);
        if (PotatoSack.plugin != null) {
            // 记录到服务端日志
            PotatoSack.plugin.getLogger().severe(msg);
        }
    }
}