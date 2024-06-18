package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class Utils {
    // 防止备份任务并发
    public final static BackupMutex BACKUP_MUTEX = new BackupMutex();

    /**
     * 获得当前的秒级时间戳
     *
     * @return 秒级时间戳
     */
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
     * 将指定路径转换为一个可以当作Map Key的字符串，是一个相对于服务端根目录的相对路径
     *
     * @param file 文件File对象
     * @return 转换后的字符串
     * @apiNote 比如<p>/root/server/world/region</p><p>转换为</p><p>world/region</p>
     */
    public static String pathRelativeToServer(File file) {
        // toURI 转换为 绝对路径 构成的标准 URI 形式
        Path serverRootPath = Path.of(PotatoSack.worldContainerDir.toURI());
        Path filePath = Path.of(file.toURI());
        // 获得相对路径
        String relativePathStr = serverRootPath.relativize(filePath).toString();
        // 统一为 Unix 格式
        return relativePathStr.replace('\\', '/');
    }

    /**
     * 和pathRelativeToServer操作相反，将相对路径转换为服务器内的绝对路径
     *
     * @param relativePath 相对路径
     * @return 服务器内的绝对路径
     * @apiNote 比如<p>world/region</p><p>转换为</p><p>/root/server/world/region</p>
     */
    public static String pathAbsToServer(String relativePath) {
        // 把绝对路径和相对路径拼接起来
        File absoluteFile = new File(PotatoSack.worldContainerDir, relativePath);
        // 返回拼接好的路径字符串
        return absoluteFile.getPath();
    }

    /**
     * 遍历获取指定目录下所有文件的最后哈希值
     *
     * @param srcDir 待扫描目录（File对象）
     * @param res    （递归用） 调用时传入null即可
     * @return Map(String - > 文件最后哈希值)对象
     */
    public static Map<String, String> getLastFileHashes(File srcDir, Map<String, String> res) {
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
                        Utils.fileMD5(file) // 计算文件哈希
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
     * @param oldMap 旧记录Map<文件相对服务器根目录的路径, 文件哈希>
     * @param newMap 新记录Map<文件相对服务器根目录的路径, 文件哈希>
     * @return 被删除的文件路径列表 String List
     * @apiNote 用于找出两个增量备份间被删除的文件
     */
    public static List<String> getDeletedFilePaths(Map<String, String> oldMap, Map<String, String> newMap) {
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
     * 将指定的文件加入Zip流
     *
     * @param zos          Zip输出流
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     */
    public static void zipSpecificFilesUtil(ZipOutputStream zos, ZipFilePath[] zipFilePaths, boolean quiet) throws IOException {
        for (ZipFilePath zipFilePath : zipFilePaths) {
            if (!quiet)
                System.out.println("[Verbose] Add file: " + zipFilePath.filePath + " -> " + zipFilePath.zipFilePath);
            zos.putNextEntry(new ZipEntry(zipFilePath.zipFilePath));
            File file = new File(zipFilePath.filePath);
            FileInputStream in = new FileInputStream(file);
            byte[] buffer = new byte[8192]; // 写入文件
            int len;
            while ((len = in.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            in.close();
        }
        zos.closeEntry();
        zos.flush();
    }

    /**
     * 将指定的文件打包成Zip
     *
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param outputPath   输出Zip包的路径
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     * @return 是否打包成功
     */
    public static boolean zipSpecificFiles(ZipFilePath[] zipFilePaths, String outputPath, boolean quiet) {
        System.out.println("Compressing...");
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputPath)))
        ) {
            zipSpecificFilesUtil(zout, zipFilePaths, quiet);
            return true;
        } catch (IOException e) {
            ConsoleSender.logError("Zip specific files failed: " + e.getMessage());
        }
        return false;
    }

    /*
     * 把目录下所有文件打包成zip
     *
     * @param srcDirPath   源目录路径
     * @param zipFilePath  目标zip文件路径
     * @param packAsSrcDir 是否把srcDirPath下的所有文件都放在压缩包的【srcDirPath指向的目录名】的目录下
     * @param quiet        是否静默打包
     * @return 是否打包成功
     * @apiNote 比如srcDirPath='./test/myfolder'，如果packAsSrcDir=true，那么打包后的zip包中根目录下是myfolder，其中是myfolder中的所有文件； 否则根目录下则是myfolder内的所有文件。
     */
    /*public static boolean zip(String srcDirPath, String zipFilePath, boolean packAsSrcDir, boolean quiet) {
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
            ConsoleSender.logError("Compression failed: " + e.getMessage());
        }
        return false;
    }*/

    /**
     * 指定多个目录打包成zip
     *
     * @param srcDirPaths       String[] ，指定要打包的目录绝对路径（注意：需要是同一目录下的子目录）
     * @param targetZipFilePath String 指定打包后的zip文件路径
     * @param quiet             是否静默打包（不显示 Adding... 信息)
     * @return 是否打包成功
     */
    public static boolean zip(String[] srcDirPaths, String targetZipFilePath, boolean quiet) {
        try (
                ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(targetZipFilePath)))
        ) {
            System.out.println("Compressing... ");
            for (String path : srcDirPaths) {
                File srcDir = new File(path);
                // 将指定目录内容加入包中
                addDirFilesToZip(srcDir, srcDir.getName(), zout, quiet);
            }
            zout.closeEntry();
            zout.flush();
            System.out.println("Compress success. File: " + targetZipFilePath);
            return true;
        } catch (Exception e) {
            ConsoleSender.logError("Compression failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * （递归方法） 扫描目录下所有文件并存入Zip Entries
     *
     * @param srcDir        源目录File对象
     * @param parentDirPath 父目录路径
     * @param zout          Zip输出流（Buffered）
     * @param quiet         是否静默打包
     * @throws Exception 打包失败抛出异常
     */
    public static void addDirFilesToZip(File srcDir, String parentDirPath, ZipOutputStream zout, boolean quiet) throws Exception {
        File[] files = srcDir.listFiles();
        if (files == null) {
            throw new Exception("Error: " + srcDir + " is not a directory, this should not happen!");
        }
        for (File file : files) {
            // 如果是目录，这就是当前扫描到的目录路径，否则就是文件的路径
            String currentDirOrFilePath = (parentDirPath.equals("") ? "" : (parentDirPath + "/")) + file.getName();
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                addDirFilesToZip(file, currentDirOrFilePath, zout, quiet);
            } else {
                // 如果是文件就写入Zip
                try (FileInputStream fis = new FileInputStream(file)) {
                    if (!quiet)
                        System.out.println("[Verbose] Add file: " + currentDirOrFilePath);
                    // 将条目（文件）加入zip包
                    ZipEntry zipEntry = new ZipEntry(currentDirOrFilePath);
                    zout.putNextEntry(zipEntry);
                    // 写入文件
                    int len;
                    byte[] buf = new byte[8192];
                    while ((len = fis.read(buf)) != -1) {
                        zout.write(buf, 0, len);
                    }
                }
            }
        }
    }

    /**
     * （递归方法） 扫描目录下所有文件，转换为 ZipFilePath
     *
     * @param srcDir        源目录 File 对象
     * @param parentDirPath 父目录路径
     * @return List<ZipFilePath>
     */
    public static List<ZipFilePath> dirFilesToZipFilePaths(File srcDir, String parentDirPath) throws Exception {
        List<ZipFilePath> resPaths = new ArrayList<>();
        // 列出 srcDir 目录下的文件
        File[] files = srcDir.listFiles();
        if (files == null) {
            throw new Exception("Error: " + srcDir + " is not a directory, this should not happen!");
        }
        for (File file : files) {
            // 如果是目录，这就是当前扫描到的目录路径，否则就是文件的路径
            String currentDirOrFilePath = (parentDirPath.equals("") ? "" : (parentDirPath + "/")) + file.getName();
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                resPaths.addAll(dirFilesToZipFilePaths(file, currentDirOrFilePath));
            } else {
                // 如果是文件就转换为 ZipFilePath
                resPaths.add(new ZipFilePath(
                        Utils.pathAbsToServer(currentDirOrFilePath),
                        currentDirOrFilePath
                ));
            }
        }
        return resPaths;
    }


    /**
     * 指定多个目录，扫描这些目录下的所有文件，组成 ZipFilePath[]
     *
     * @param srcDirPaths String[] ，指定要打包的目录绝对路径（注意：是同一目录下的子目录）
     * @return ZipFilePath[]
     */
    public static ZipFilePath[] worldPathsToZipPaths(String[] srcDirPaths) {
        List<ZipFilePath> res = new ArrayList<>();
        try {
            // 遍历每个目录
            for (String path : srcDirPaths) {
                File srcDir = new File(path);
                // 扫描这个目录内的文件，形成 ZipFilePath 列表
                res.addAll(dirFilesToZipFilePaths(srcDir, srcDir.getName()));
            }
        } catch (Exception e) {
            ConsoleSender.logError("Transformation of world file paths to zip file paths failed: " + e.getMessage());
        }
        return res.toArray(new ZipFilePath[0]);
    }


    /**
     * 设置**所有**世界：是否启动自动保存
     *
     * @param value 是否停止
     * @return 被影响的世界字符串List
     */
    public static List<String> setWorldsSave(boolean value) {
        return setWorldsSave(null, value);
    }

    /**
     * 设置指定世界：是否启动自动保存
     *
     * @param worlds 世界名列表
     * @param value  是否停止
     * @return 被影响的世界字符串List
     */
    public static List<String> setWorldsSave(List<String> worlds, boolean value) {
        // 这样写是因为，有的插件（比如一些需要地图还原的小游戏插件）依赖于将 world 设置为非自动保存
        // 这里我会将受到影响的世界返回，下次设置时可以作为 worlds 参数传入，以免影响到一些世界原有的状态
        List<String> affectedWorlds = new ArrayList<>(); // 受影响的世界
        // 等待主线程任务完成的 CountdownLatch
        CountDownLatch cdl = new CountDownLatch(1);
        if (PotatoSack.plugin != null) {
            List<World> worldList;
            if (worlds == null) {
                // 如果没有指定世界，则默认为全部
                worldList = Bukkit.getWorlds();
            } else {
                worldList = new ArrayList<>();
                for (String worldName : worlds) {
                    World world = Bukkit.getWorld(worldName);
                    if (world != null)
                        worldList.add(world);
                }
            }
            // TODO: 待测试: 等待主线程关闭世界保存任务完成
            // 在主线程中，设置全部世界的保存情况
            Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
                // 停止世界自动保存
                try {
                    worldList.forEach(world -> {
                        if (world.isAutoSave() != value) // 和要设定的值不一样，说明有变更
                            affectedWorlds.add(world.getName());
                        world.setAutoSave(value);
                    });
                } finally {
                    // 主线程任务完成，通知调用线程
                    cdl.countDown();
                }
            });
            try {
                cdl.await();
            } catch (InterruptedException e) {
                System.out.println("Waiting for world auto save stop interrupted:" + e);
            }
        }
        System.out.println("[DEBUG] Auto-save stopped, affected Worlds:" + String.join(",", affectedWorlds));
        return affectedWorlds;
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