package indi.somebottle;


import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 一些工具方法
 */
public class Utils {
    /**
     * 将 zip 解压到特定目录。zip 内的文件会覆盖现有的同名文件
     *
     * @param zipFile   Zip 文件 File 对象
     * @param targetDir 解压后存放的目录 File 对象
     * @return 是否解压成功@
     * @throws IOException IO 异常
     */
    public static boolean unzip(File zipFile, File targetDir) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zipIn.getNextEntry();
            while (zipEntry != null) {
                // 取得一个压缩条目
                String fileName = zipEntry.getName();
                File currFile = new File(targetDir, fileName);
                if (currFile.isDirectory()) {
                    // 如果是目录，且目录不存在，就尝试建立目录
                    if (!currFile.exists() && !currFile.mkdirs()) {
                        System.out.println("Failed to create directory " + currFile.getAbsolutePath());
                        return false;
                    }
                } else {
                    // 如果是文件则尝试解压
                    File parent = currFile.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) {
                        System.out.println("Failed to create directory " + parent.getAbsolutePath());
                        return false;
                    }
                    if (currFile.exists() && !truncateFile(currFile)) {
                        // 如果文件已经存在，则先截断
                        System.out.println("File " + currFile.getAbsolutePath() + " already exists, but failed to truncate it.");
                        return false;
                    }
                    // 写入文件
                    try (FileOutputStream out = new FileOutputStream(currFile)) {
                        byte[] buffer = new byte[16384];
                        int len;
                        while ((len = zipIn.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                // 关闭当前的条目
                zipIn.closeEntry();
                // 取出下一个条目
                zipEntry = zipIn.getNextEntry();
            }
        }
        return true;
    }

    /**
     * 彻底删除一个目录及其之中的文件
     *
     * @param dir 目录 File 对象
     * @return 是否成功
     */
    public static boolean rmDir(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Directory not exists or not a directory.");
            return false;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    if (!rmDir(f))
                        return false;
                } else {
                    if (!f.delete()) {
                        System.out.println("Failed to delete file " + f.getAbsolutePath());
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    /**
     * 截断文件
     *
     * @param file 文件 File 对象
     * @return 是否成功
     */
    public static boolean truncateFile(File file) {
        if (!file.isFile()) // 不是文件则失败
            return false;
        RandomAccessFile ras = null;
        try {
            ras = new RandomAccessFile(file, "rw");
            ras.setLength(0);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (ras != null) {
                try {
                    ras.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
