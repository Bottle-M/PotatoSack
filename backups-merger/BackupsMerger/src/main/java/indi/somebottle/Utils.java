package indi.somebottle;


import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 一些工具方法
 */
public class Utils {
    /**
     * 将整个文件按字符串读入
     *
     * @param file File 对象
     * @return 字符串
     * @throws IOException IO 异常
     */
    public static String readFile(File file) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] bytes = new byte[bis.available()];
            if (bis.read(bytes) == -1) {
                return "";
            }
            return new String(bytes);
        }
    }

    /**
     * 将 zip 解压到特定目录。zip 内的文件会覆盖现有的同名文件
     *
     * @param zipFile   Zip 文件 File 对象
     * @param targetDir 解压后存放的目录 File 对象
     * @return 是否解压成功
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean unzip(File zipFile, File targetDir) {
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
                    System.out.println("\tExtracting: " + fileName);
                    // 如果是文件则尝试解压
                    File parent = currFile.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) {
                        System.out.println("Failed to create directory " + parent.getAbsolutePath());
                        return false;
                    }
                    if (currFile.exists() && !truncateFile(currFile)) {
                        // 如果文件已经存在，则先截断再覆盖写入
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
                        // 冲刷缓冲区，写入文件（如果不冲刷，关闭流后文件可能仍然被占用）
                        out.flush();
                    }
                }
                // 关闭当前的条目
                zipIn.closeEntry();
                // 取出下一个条目
                zipEntry = zipIn.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 扫描一个目录下的所有文件，写入 Zip 流
     *
     * @param srcDir  待扫描目录
     * @param rootDir 一个根目录，zip 包内所有文件的路径都是相对于这个根路径生成的相对路径
     * @param zos     Zip 流
     * @return 是否成功
     */
    private static boolean zipDir(File srcDir, File rootDir, ZipOutputStream zos) {
        boolean success = true;
        try {
            File[] files = srcDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        success = success && zipDir(file, rootDir, zos);
                    } else {
                        Path rootPath = Path.of(rootDir.toURI());
                        Path filePath = Path.of(file.toURI());
                        Path relativePath = rootPath.relativize(filePath);
                        System.out.println("\tZipping: " + relativePath);
                        zos.putNextEntry(new ZipEntry(relativePath.toString()));
                        try (FileInputStream fis = new FileInputStream(file)) {
                            byte[] buffer = new byte[16384];
                            int len;
                            while ((len = fis.read(buffer)) > 0) {
                                zos.write(buffer, 0, len);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return success;
    }

    /**
     * 将 srcDirs 中每个目录加入压缩包
     *
     * @param srcFiles      目录 File 对象数组
     * @param outputZipFile 压缩包输出文件
     * @param rootDir       一个根目录，zip 包内所有文件的路径都是相对于这个根路径生成的相对路径
     * @return 是否成功
     */
    public static boolean zip(File[] srcFiles, File outputZipFile, File rootDir) {
        // 如果输出文件已经存在则移除
        if (outputZipFile.exists() && !outputZipFile.delete()) {
            System.out.println("Failed to remove existing file " + outputZipFile.getAbsolutePath());
            return false;
        }
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZipFile))) {
            for (File srcFile : srcFiles) {
                if (srcFile.isDirectory()) {
                    if (!zipDir(srcFile, rootDir, zos))
                        return false;
                } else {
                    // 如果是文件则存入压缩包
                    // 因为是放在压缩包根目录下，文件名就是路径
                    System.out.println("\tZipping: " + srcFile.getName());
                    zos.putNextEntry(new ZipEntry(srcFile.getName()));
                    try (FileInputStream fis = new FileInputStream(srcFile)) {
                        byte[] buffer = new byte[16384];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                }
            }
            // 冲刷缓冲区，写入文件（如果不冲刷，关闭流后文件可能仍然被占用）
            zos.flush();
            zos.closeEntry();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 逐行读取文件并返回（忽略空行）
     *
     * @param file File 对象
     * @return String[]
     */
    public static String[] readLines(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty())
                    lines.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines.toArray(new String[0]);
    }

    /**
     * 彻底删除一个目录及其之中的文件
     *
     * @param dir 目录 File 对象
     * @return 是否成功
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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

    /**
     * 将秒级时间戳转换为 日期字符串 yyyy-MM-dd HH:mm:ss
     *
     * @param ts 秒级时间戳
     * @return 日期字符串
     */
    public static String timestampToDate(long ts) {
        Instant instant = Instant.ofEpochSecond(ts);
        // 系统时区
        ZoneId sysZone = ZoneId.systemDefault();
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, sysZone);
        // 日期格式化
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dtf.format(dateTime);
    }
}
