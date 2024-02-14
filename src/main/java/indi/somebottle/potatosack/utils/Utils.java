package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import org.bukkit.Bukkit;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class Utils {
    // 防止备份任务并发
    public final static BackupMutex BACKUP_MUTEX = new BackupMutex();

    public static long timeStamp() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 计算文件的MD5哈希值
     *
     * @param file 文件File对象
     * @return 哈希值
     */
    public static String fileMD5(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[16384]; // 16K的数据缓冲区
            int readLen;
            // 流式读入文件计算哈希
            while ((readLen = fis.read(buffer)) != -1) {
                md5.update(buffer, 0, readLen);
            }
            // 转换为十六进制字符串返回
            return new BigInteger(1, md5.digest()).toString(16);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 将代表数值的Object对象转换为long
     *
     * @param obj Object
     * @return long
     */
    public static long objToLong(Object obj) {
        if (obj instanceof Integer) {
            return (long) (Integer) obj;
        } else {
            return (long) obj;
        }
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
     * 将指定路径转换为一个可以当作Map Key的字符串
     *
     * @param file 文件File对象
     * @return 转换后的字符串
     * @apiNote 比如<p>/root/server/world/region</p><p>转换为</p><p>world/region</p>
     */
    public static String pathRelativeToServer(File file) {
        String serverPath = new File(System.getProperty("user.dir")).getAbsolutePath();
        // 这里替换时加上末尾的“/”再替换
        String replaced = file.getAbsolutePath().replace(serverPath + File.separator, "");
        if (replaced.startsWith("./")) // 有的时候替换后是./开头，很奇妙，这里也考虑进去
            replaced = replaced.substring(2);
        return replaced;
    }

    /**
     * 和pathRelativeToServer操作相反，将相对路径转换为服务器内的绝对路径
     *
     * @param relativePath 相对路径
     * @return 服务器内的绝对路径
     * @apiNote 比如<p>world/region</p><p>转换为</p><p>/root/server/world/region</p>
     */
    public static String pathAbsToServer(String relativePath) {
        String serverPath = new File(System.getProperty("user.dir")).getAbsolutePath();
        // 这里替换时加上末尾的“/”
        return serverPath + File.separator + relativePath;
    }

    /**
     * 遍历获取指定目录下所有文件的最后哈希值
     *
     * @param srcDir 待扫描目录（File对象）
     * @param res    （递归用） 调用时传入null即可
     * @return Map(String - > [文件路径, 文件最后哈希值])对象
     */
    public static Map<String, String[]> getLastFileHashes(File srcDir, Map<String, String[]> res) {
        if (res == null)
            res = new HashMap<>();
        File[] files = srcDir.listFiles();
        if (files == null)
            return res;
        for (File file : files) {
            if (file.isFile()) {
                // 获得相对服务器根目录的路径，比如/root/server/world/region转换为world/region
                String relativePath = pathRelativeToServer(file);
                // 是文件则加入
                res.put(
                        relativePath,
                        new String[]{
                                relativePath,
                                Utils.fileMD5(file) // 计算文件哈希
                        }
                );
            } else if (file.isDirectory()) {
                // 是目录则继续
                getLastFileHashes(file, res);
            }
        }
        return res;
    }

    /**
     * 获得两个lastFileHashes Map的差集
     *
     * @param oldMap 旧记录Map
     * @param newMap 新记录Map
     * @return 被删除的文件路径列表String List
     * @apiNote 用于找出两个增量备份间被删除的文件
     */
    public static List<String> getDeletedFilePaths(Map<String, String[]> oldMap, Map<String, String[]> newMap) {
        List<String> res = new ArrayList<>();
        for (String key : oldMap.keySet()) {
            if (!newMap.containsKey(key)) {
                // 旧记录中的某个文件未出现在新记录中，被删除，加入结果中
                res.add(key);
            }
        }
        return res;
    }

    /**
     * 将指定的文件打包成Zip
     *
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param outputPath   输出Zip包的路径
     * @param quiet        是否静默打包
     * @return 是否打包成功
     */
    public static boolean ZipSpecificFiles(ZipFilePath[] zipFilePaths, String outputPath, boolean quiet) {
        System.out.println("Compressing...");
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputPath)))
        ) {
            for (ZipFilePath zipFilePath : zipFilePaths) {
                if (!quiet)
                    System.out.println("Add file: " + zipFilePath.filePath + " -> " + zipFilePath.zipFilePath);
                zout.putNextEntry(new ZipEntry(zipFilePath.zipFilePath));
                File file = new File(zipFilePath.filePath);
                FileInputStream in = new FileInputStream(file);
                byte[] buffer = new byte[8192]; // 写入文件
                int len;
                while ((len = in.read(buffer)) > 0) {
                    zout.write(buffer, 0, len);
                }
                in.close();
            }
            zout.closeEntry();
            zout.flush();
            return true;
        } catch (IOException e) {
            Utils.logError("Zip specific files failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 把目录下所有文件打包成zip
     *
     * @param srcDirPath   源目录路径
     * @param zipFilePath  目标zip文件路径
     * @param packAsSrcDir 是否把srcDirPath下的所有文件都放在压缩包的【srcDirPath指向的目录名】的目录下
     * @param quiet        是否静默打包
     * @return 是否打包成功
     * @apiNote 比如srcDirPath='./test/myfolder'，如果packAsSrcDir=true，那么打包后的zip包中根目录下是myfolder，其中是myfolder中的所有文件； 否则根目录下则是myfolder内的所有文件。
     */
    public static boolean Zip(String srcDirPath, String zipFilePath, boolean packAsSrcDir, boolean quiet) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)))
        ) {
            File srcDir = new File(srcDirPath);
            System.out.println("Compressing... ");
            addItemsToZip(srcDir, packAsSrcDir ? srcDir.getName() : "", zout, quiet);
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
     * @param quiet       是否静默打包
     * @return 是否打包成功
     */
    public static boolean Zip(String[] srcDirPath, String zipFilePath, boolean quiet) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFilePath)))
        ) {
            System.out.println("Compressing... ");
            for (String path : srcDirPath) {
                File srcDir = new File(path);
                // 将指定目录内容加入包中
                addItemsToZip(srcDir, srcDir.getName(), zout, quiet);
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
     * @param quiet     是否静默打包
     * @throws Exception 打包失败抛出异常
     */
    private static void addItemsToZip(File srcDir, String parentDir, ZipOutputStream zout, boolean quiet) throws Exception {
        File[] files = srcDir.listFiles();
        if (files == null) {
            throw new Exception("Error: file list is null, this should not happen!");
        }
        for (File file : files) {
            String currentDir = (parentDir.equals("") ? "" : (parentDir + "/")) + file.getName();
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                addItemsToZip(file, currentDir, zout, quiet);
            } else {
                // 如果是文件就写入Zip
                try (BufferedInputStream bin = new BufferedInputStream(new FileInputStream(file))) {
                    if (!quiet)
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
        String finalMsg = "[" + Constants.PLUGIN_PREFIX + "] Fatal: " + msg;
        System.out.println(finalMsg);
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