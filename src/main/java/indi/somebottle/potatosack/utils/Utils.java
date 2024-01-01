package indi.somebottle.potatosack.utils;

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
     * @param srcDirPath  源目录路径
     * @param zipFilePath 目标zip文件路径
     * @return 是否打包成功
     */
    public static boolean Zip(String srcDirPath, String zipFilePath) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)))
        ) {
            File srcDir = new File(srcDirPath);
            System.out.println("Compressing... ");
            addItemsToZip(srcDir, srcDir.getName(), zout);
            zout.closeEntry();
            zout.flush();
            System.out.println("Compress success. File: " + zipFilePath);
            return true;
        } catch (Exception e) {
            System.out.println("Compression failed.");
            e.printStackTrace();
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
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                addItemsToZip(file, parentDir + "/" + file.getName(), zout);
            } else {
                // 如果是文件就写入Zip
                try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(file))) {
                    System.out.println("Add file: " + parentDir + "/" + file.getName());
                    // 将条目（文件）加入zip包
                    ZipEntry zipEntry = new ZipEntry(parentDir + "/" + file.getName());
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
}