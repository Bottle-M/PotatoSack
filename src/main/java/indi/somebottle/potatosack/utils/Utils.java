package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.tasks.entities.WorldSaveState;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@SuppressWarnings("BusyWait")
public class Utils {
    /**
     * 获得当前的秒级时间戳
     *
     * @return 秒级时间戳 (UNIX 时间戳)
     */
    public static long timestamp() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 把 UNIX 秒级时间戳转换为当前系统时区下的日期字符串
     *
     * @param timestamp UNIX 秒级时间戳
     * @return 日期字符串，格式为 yyyy-MM-dd HH:mm:ss
     */
    public static String timestampToDateStr(long timestamp) {
        Instant instant = Instant.ofEpochSecond(timestamp);
        ZonedDateTime dateTime = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        LocalDateTime localDateTime = dateTime.toLocalDateTime();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return dateTime.format(formatter);
    }

    /**
     * 计算文件的 MD5 哈希值
     *
     * @param file 文件File对象
     * @return 哈希值（32位十六进制字符串），若无法读取则返回空字符串 ""
     */
    public static String fileMD5(File file) {
        ExponentialBackoffCalculator backoffCalc = new ExponentialBackoffCalculator(1000); // 基础退避 1s
        int retry = 0;
        while (true) {
            try (FileInputStream fis = new FileInputStream(file)) {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                byte[] buffer = new byte[16384]; // 16K的数据缓冲区
                int readLen;
                // 流式读入文件计算哈希
                while ((readLen = fis.read(buffer)) != -1) {
                    md5.update(buffer, 0, readLen);
                }
                // 转换为十六进制字符串返回，确保前导零不会丢失
                byte[] digest = md5.digest();
                StringBuilder sb = new StringBuilder(32);
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (IOException e) {
                // 文件被锁定（Windows 下常见）或其它 IO 错误，进行有限重试
                if (retry < Constants.FILE_READ_MAX_RETRY) {
                    retry++;
                    try {
                        Thread.sleep(backoffCalc.getNextBackoffTime(Constants.FILE_READ_MAX_BACKOFF_MS));
                        backoffCalc.backoff(); // 退避时间翻倍: 1s → 2s → 4s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    ConsoleSender.logWarn("Cannot read file " + file.getAbsolutePath() + ", skipping: " + e.getMessage());
                    return "";
                }
            } catch (Exception e) {
                e.printStackTrace();
                return "";
            }
        }
    }

    /**
     * 计算字符串的 MD5 哈希值
     *
     * @param input 待计算哈希的字符串（按 UTF-8 解码）
     * @return 32 位十六进制哈希字符串
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JCE 标准算法，正常环境下不会缺失
            throw new RuntimeException("MD5 algorithm not available, this should not happen.", e);
        }
    }

    /**
     * 计算文件的 CRC32 校验和
     *
     * @param file 文件
     * @return 校验和
     */
    public static long fileCRC32(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            CRC32 crc32 = new CRC32();
            byte[] buffer = new byte[16384]; // 16K的数据缓冲区
            int readLen;
            // 流式读入文件计算哈希
            while ((readLen = fis.read(buffer)) != -1) {
                crc32.update(buffer, 0, readLen);
            }
            return crc32.getValue();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     * 将代表数值的 Object 对象转换为 long
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
     * 用于在插件启动时检查 pathRelativeToServer 是否能正常运作
     *
     * @throws IOException 如果文件不存在，或者文件的真实位置不在服务端根目录下，或者发生其他 IO 错误
     */
    public static void testRelativePathToServer() throws IOException {
        // 拿 server.properties 测试相对服务端根目录路径
        File serverProperties = new File(System.getProperty("user.dir"), "server.properties");
        System.out.println("Absolute path of server.properties: " + serverProperties.getAbsolutePath());
        System.out.println("server.properties path relative to server root: " + pathRelativeToServer(serverProperties));
    }

    /**
     * 将指定路径转换为一个相对于服务端根目录的规范相对路径（Unix 风格）
     *
     * <p>本方法支持跟随符号链接</p>
     *
     * <p><b>注意，当 file 为服务端根目录时，会返回空字符串</b></p>
     *
     * <p>例如：</p>
     * /root/server/world/region -> world/region
     *
     * <p>要求：</p>
     * <ol>
     *     <li>目标路径必须存在</li>
     *     <li>目标路径的真实位置必须位于服务端根目录内</li>
     *     <li>绝对路径和相对路径如果指向同一文件，会得到同一个 key</li>
     * </ol>
     *
     * @param file 文件File对象
     * @return 转换后的字符串
     * @throws IOException 如果文件不存在，或者文件的真实位置不在服务端根目录下，或者发生其他 IO 错误
     * @apiNote 比如<p>/root/server/world/region</p><p>转换为</p><p>world/region</p>
     */
    public static String pathRelativeToServer(File file) throws IOException {
        // 获取服务端根目录的真实路径，以及输入路径的真实路径
        Path serverRootPath = PotatoSack.worldContainerDir.toPath().toRealPath();
        Path inputPath = file.toPath();
        // 如果输入路径是绝对路径，则直接使用；如果是相对路径，则将它解析为相对于服务端根目录的路径
        Path resolvedInputPath = inputPath.isAbsolute()
                ? inputPath
                : serverRootPath.resolve(inputPath);
        Path realFilePath = resolvedInputPath.toRealPath();
        if (!realFilePath.startsWith(serverRootPath)) {
            // 确保输入路径的真实位置在服务端根目录下，防止路径穿越
            throw new IllegalArgumentException(
                    "Path is outside server root: " + file
            );
        }
        // 获得相对路径
        Path relativePath = serverRootPath.relativize(realFilePath);
        // 统一为 Unix 格式
        return relativePath.toString().replace('\\', '/');
    }

    /**
     * 检查相对于相同根目录的一组 Unix 相对路径中有没有包含和被包含的情况
     *
     * @param relativePaths 相对路径列表
     * @return 如果存在路径 A 包含路径 B 的情况（即 A 是 B 的父目录），则返回 true；否则返回 false
     * @apiNote 例如：如果 relativePaths 中包含 "world" 和 "world/region"，则返回 true，因为 "world" 包含 "world/region"；如果 relativePaths 中包含 "world/region" 和 "world/region/dimension"，则返回 true，因为 "world/region" 包含 "world/region/dimension"
     */
    public static boolean hasOverlappingRelativePaths(List<String> relativePaths) {
        // 排序后检查相邻路径是否有包含关系（在副本上排序，避免修改入参）
        List<String> sorted = new ArrayList<>(relativePaths);
        Collections.sort(sorted);
        for (int i = 0; i < sorted.size() - 1; i++) {
            String currentPath = sorted.get(i);
            String nextPath = sorted.get(i + 1);
            if (nextPath.startsWith(currentPath + "/") || nextPath.equals(currentPath)) {
                // 如果下一个路径以当前路径加斜杠开头，说明当前路径包含下一个路径；或者如果两个路径完全相同，也算包含关系
                return true;
            }
        }
        return false;
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
     * 将配置的备份路径解析为 File 对象，用于统一备份路径对应的实际文件
     * <p>
     * 绝对路径原样使用；相对路径按服务端根目录（worldContainerDir）解析，
     * 与 {@link #pathRelativeToServer(File)} 的解析基准保持一致，
     * 避免相对路径在扫描/打包时按 JVM 工作目录解析而与键值生成基准不一致。
     * </p>
     *
     * @param path 配置的备份路径（绝对或相对）
     * @return 解析后的 File 对象
     */
    public static File resolveBackupConfPath(String path) {
        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(PotatoSack.worldContainerDir, path);
    }

    /**
     * 遍历获取指定目录下所有文件的目前哈希值
     *
     * @param srcDir 待扫描目录（File对象）
     * @param ignorer 用于跳过被忽略的文件/目录（目录命中即剪枝，不递归）
     * @param res    （递归用） 调用时传入null即可
     * @return Map(String - > 文件最后哈希值)对象
     * @throws IOException 如果在访问文件时发生IO错误
     */
    public static Map<String, String> getCurrentFileHashes(File srcDir, IgnoreMatcher ignorer, Map<String, String> res) throws IOException {
        if (res == null)
            res = new HashMap<>();
        File[] files = srcDir.listFiles();
        if (files == null)
            return res;
        for (File file : files) {
            if (file.isFile()) {
                // 获得相对服务器根目录的路径，比如 /root/server/world/region 转换为 world/region
                String relativePath = pathRelativeToServer(file);
                // 命中 ignore 规则的文件跳过（不计入哈希）
                if (ignorer.isIgnored(relativePath, false))
                    continue;
                // 计算文件哈希；若文件被锁定无法读取（如 Windows 下的 session.lock），
                // fileMD5 会打印 WARN 并返回 ""，此时跳过该文件不加入哈希记录
                String hash = Utils.fileMD5(file);
                if (!hash.isEmpty()) {
                    res.put(relativePath, hash);
                }
            } else if (file.isDirectory()) {
                // 仅在存在 ignore 规则时才计算相对路径判断是否剪枝，避免无规则时的额外开销
                if (!ignorer.isEmpty() && ignorer.isIgnored(pathRelativeToServer(file), true))
                    continue; // 命中 ignore 的目录直接剪枝，不递归
                // 是目录则继续
                getCurrentFileHashes(file, ignorer, res);
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
     * 从哈希记录中移除被 ignore 的条目（用于增量备份时清理旧记录，
     * 避免之前备份过、现已忽略的文件被误当作“删除”写入 deleted.files）
     *
     * @param hashes 哈希记录，可为 null
     * @param ignorer IgnoreMatcher
     * @return 过滤后的 Map；若无规则或入参为 null 则原样返回
     */
    public static Map<String, String> filterIgnoredHashes(Map<String, String> hashes, IgnoreMatcher ignorer) {
        if (hashes == null || ignorer.isEmpty()) {
            return hashes;
        }
        Map<String, String> filtered = new HashMap<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            // 用 isIgnored（内置祖先遍历）：既匹配文件自身，也匹配“位于被忽略目录之下”的文件（与扫描期目录剪枝一致），
            // 避免旧记录中此类文件被误当作删除
            if (!ignorer.isIgnored(entry.getKey(), false)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * 获取指定目录下所有文件的修改时间快照
     *
     * @param srcDir 待扫描目录（File 对象）
     * @param ignorer 用于跳过被忽略的文件/目录（目录命中即剪枝）
     * @param res    存储结果的 Map
     */
    public static void updateFilesModificationSnapshot(File srcDir, IgnoreMatcher ignorer, Map<String, Long> res) throws IOException {
        if (res == null)
            res = new HashMap<>();
        File[] files = srcDir.listFiles();
        if (files == null)
            return;
        for (File file : files) {
            if (file.isFile()) {
                String relativePath = pathRelativeToServer(file);
                if (ignorer.isIgnored(relativePath, false))
                    continue;
                res.put(relativePath, file.lastModified());
            } else if (file.isDirectory()) {
                if (!ignorer.isEmpty() && ignorer.isIgnored(pathRelativeToServer(file), true))
                    continue;
                updateFilesModificationSnapshot(file, ignorer, res);
            }
        }
    }

    /**
     * 获取所有指定路径下文件的修改时间快照
     *
     * @param paths  路径列表（绝对或相对服务端根）
     * @param ignorer 用于跳过被忽略的文件/目录
     * @return Map<文件相对路径, 最后修改时间戳>
     */
    public static Map<String, Long> getTimeSnapshotForAllFilesUnderPaths(List<String> paths, IgnoreMatcher ignorer) throws IOException {
        Map<String, Long> snapshot = new HashMap<>();
        for (String path : paths) {
            updateFilesModificationSnapshot(resolveBackupConfPath(path), ignorer, snapshot);
        }
        return snapshot;
    }

    /**
     * 规范化云端基础目录路径
     * <p>
     * 去掉前后的斜杠，返回干净的路径片段
     *
     * @param baseDir 基础目录路径
     * @return 规范化后的路径，如果输入为空或只有斜杠则返回空字符串
     */
    public static String normalizeBaseDir(String baseDir) {
        if (baseDir == null || baseDir.isEmpty()) {
            return "";
        }
        // 去掉前后的斜杠
        baseDir = baseDir.trim();
        while (baseDir.startsWith("/")) {
            baseDir = baseDir.substring(1);
        }
        while (baseDir.endsWith("/")) {
            baseDir = baseDir.substring(0, baseDir.length() - 1);
        }
        return baseDir;
    }

    /**
     * 将指定的文件加入Zip流
     *
     * @param zos          Zip输出流
     * @param zipFilePaths 要打包的文件路径对ZipFilePath[]
     * @param quiet        是否静默打包（不显示 Adding... 信息)
     */
    public static void zipSpecificFilesUtil(ZipOutputStream zos, ZipFilePath[] zipFilePaths, boolean quiet) throws IOException, ZipRWConflictException {
        // CRC 校验和计算器
        CRC32 crc32 = new CRC32();
        for (ZipFilePath zipFilePath : zipFilePaths) {
            // 重置 CRC
            crc32.reset();
            if (!quiet)
                System.out.println("[Verbose] Add file: " + zipFilePath.filePath + " -> " + zipFilePath.zipFilePath);
            File file = new File(zipFilePath.filePath);
            // 如果待压缩文件不存在，则忽略 20240722
            // 可能在文件列表到开始压缩文件这段时间内，这个文件被删除了
            if (!file.exists()) {
                ConsoleSender.logWarn("(Unexpected!) File " + zipFilePath.filePath + " not found while compressing, it may have been deleted, ignored.");
                continue;
            }
            zos.putNextEntry(new ZipEntry(zipFilePath.zipFilePath));
            // 先记录在读取文件前的时间戳，以及文件大小
            long fileModifiedTimeBefore = file.lastModified();
            long fileSizeBefore = file.length();
            // 尝试打开并读取文件，遇到锁定时指数退避重试
            ExponentialBackoffCalculator backoffCalc = new ExponentialBackoffCalculator(1000); // 基础退避 1s
            int retry = 0;
            boolean fileSkipped = false;
            while (retry <= Constants.FILE_READ_MAX_RETRY) {
                try (FileInputStream in = new FileInputStream(file)) {
                    // 1 MiB 大小的读取缓冲区
                    byte[] buffer = new byte[1048576]; // 读出文件
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        // 计算 CRC
                        crc32.update(buffer, 0, len);
                        // 写入
                        zos.write(buffer, 0, len);
                    }
                    break; // 读取成功，跳出重试循环
                } catch (IOException e) {
                    // 文件被锁定（Windows 下常见）或其它 IO 错误
                    if (retry < Constants.FILE_READ_MAX_RETRY) {
                        retry++;
                        try {
                            Thread.sleep(backoffCalc.getNextBackoffTime(Constants.FILE_READ_MAX_BACKOFF_MS));
                            backoffCalc.backoff(); // 退避时间翻倍: 1s → 2s → 4s
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        ConsoleSender.logWarn("Cannot read file " + zipFilePath.filePath + ", skipping: " + e.getMessage());
                        fileSkipped = true;
                        break;
                    }
                }
            }
            if (fileSkipped) {
                continue; // 跳过该文件，处理下一个
            }
            // 文件读取写入完毕后取出这个期间计算的校验和
            long checksumBefore = crc32.getValue();
            // 文件读取，并压缩写入 Zip 后，再次检查文件时间戳、文件大小
            // 同时再读取文件一遍，重新计算校验和，检查校验和是否一致
            if (file.lastModified() != fileModifiedTimeBefore || file.length() != fileSizeBefore || checksumBefore != fileCRC32(file)) {
                // 如果 文件更新时间戳发生变更 或 文件大小发生变化 或 校验和 发生变化，说明在读取过程中此文件同时进行了写入
                // 可能造成数据混乱，因此要抛出异常
                throw new ZipRWConflictException("Conflict: File modified while being added to zip - " + zipFilePath.filePath);
            }
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
        File outputFile = new File(outputPath);
        int zipRetryCnt = 0; // 已经重试的次数
        // 用 <= 是因为首次运行不算重试
        for (; zipRetryCnt <= Constants.ZIP_MAX_RETRY_COUNT; zipRetryCnt++) {
            // 如果文件已经存在，则删除
            if (outputFile.exists() && !outputFile.delete()) {
                ConsoleSender.logError("Delete existing file failed: " + outputPath);
                return false;
            }
            try (
                    ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))
            ) {
                zipSpecificFilesUtil(zout, zipFilePaths, quiet);
                return true;
            } catch (ZipRWConflictException e) {
                // 添加文件时发生冲突，重试压缩
                ConsoleSender.logWarn(e.getMessage());
                if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                    ConsoleSender.toConsole("Retrying to compress files...(" + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                }
            } catch (IOException e) {
                ConsoleSender.logError("Zip specific files failed: " + e.getMessage());
                break;
            }
        }
        return false;
    }


    /**
     * （递归方法） 扫描某个目录下所有文件，转换为 ZipFilePath
     *
     * @param srcDir        源目录 File 对象
     * @param ignorer        用于跳过被忽略的文件/目录（目录命中即剪枝）
     * @param parentDirPath 该目录相对于服务端根的相对路径（作为 zip 内路径前缀，空串表示服务端根）
     * @return List<ZipFilePath>
     */
    private static List<ZipFilePath> dirFilesToZipFilePaths(File srcDir, IgnoreMatcher ignorer, String parentDirPath) throws Exception {
        List<ZipFilePath> resPaths = new ArrayList<>();
        // 列出 srcDir 目录下的文件
        File[] files = srcDir.listFiles();
        if (files == null) {
            throw new Exception("Error: " + srcDir + " is not a directory, this should not happen!");
        }
        for (File file : files) {
            // 如果是目录，这就是当前扫描到的目录路径，否则就是文件的路径
            String currentDirOrFilePath = (parentDirPath.equals("") ? "" : (parentDirPath + "/")) + file.getName();
            // 命中 ignore 规则的文件/目录跳过（目录即剪枝，不递归）
            if (ignorer.isIgnored(currentDirOrFilePath, file.isDirectory()))
                continue;
            if (file.isDirectory()) {
                // 如果是目录就递归扫描文件
                resPaths.addAll(dirFilesToZipFilePaths(file, ignorer, currentDirOrFilePath));
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
     * 指定多个备份目录，扫描这些目录下的所有文件，组成 ZipFilePath[]
     *
     * @param srcDirPaths String[] ，指定要打包的备份目录路径（绝对或相对服务端根）
     * @param ignorer      用于跳过被忽略的文件/目录
     * @return ZipFilePath[]
     */
    public static ZipFilePath[] scanPeerDirsToZipPaths(String[] srcDirPaths, IgnoreMatcher ignorer) {
        List<ZipFilePath> res = new ArrayList<>();
        try {
            // 遍历每个目录
            for (String path : srcDirPaths) {
                File srcDir = resolveBackupConfPath(path);
                // 以该目录相对于服务端根的路径作为 zip 内路径前缀，保证嵌套路径也能正确还原
                res.addAll(dirFilesToZipFilePaths(srcDir, ignorer, pathRelativeToServer(srcDir)));
            }
        } catch (Exception e) {
            ConsoleSender.logError("Transformation of backup path to zip file paths failed: " + e.getMessage());
        }
        return res.toArray(new ZipFilePath[0]);
    }

    /**
     * 找出其世界目录与任一配置的备份路径有交叠（相等、或互为父子目录）、且未被整体忽略的已加载世界名
     * <p>
     * 用于确定流式备份时需要临时关闭自动保存的世界范围：只暂停真正会被备份到数据的世界，
     * 避免无差别关闭所有世界自动保存。
     * 若某世界目录本身被 ignore（整体忽略），则其数据不会被备份，相应不暂停其自动保存。
     * </p>
     *
     * @param backupConfPaths 配置的备份路径列表
     * @param ignorer          IgnoreMatcher，用于判断世界目录是否被整体忽略
     * @return 与备份路径有交叠且未被忽略的世界名列表；若存在世界目录无法解析，返回 null，表示无法判断交叠关系，调用方应关闭所有世界的自动保存
     */
    public static List<String> getWorldNamesOverlappingBackupPaths(List<String> backupConfPaths, IgnoreMatcher ignorer) {
        List<String> res = new ArrayList<>();
        // 预先解析各备份路径的真实路径，便于和世界目录比较
        List<Path> backupRealPaths = new ArrayList<>();
        for (String path : backupConfPaths) {
            try {
                backupRealPaths.add(resolveBackupConfPath(path).toPath().toRealPath());
            } catch (IOException ignored) {
                // 路径无法解析（不存在等）则跳过，构造时已校验过，这里防御性处理
            }
        }
        for (World world : Bukkit.getWorlds()) {
            try {
                Path worldRealPath = world.getWorldFolder().toPath().toRealPath();
                boolean overlaps = false;
                for (Path backupRealPath : backupRealPaths) {
                    if (worldRealPath.startsWith(backupRealPath) || backupRealPath.startsWith(worldRealPath)) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    continue;
                }
                // 与备份路径交叠；但若世界目录整体被 ignore，则其数据不会被备份，相应不暂停其自动保存
                if (!ignorer.isEmpty() && ignorer.isIgnored(pathRelativeToServer(world.getWorldFolder()), true)) {
                    continue;
                }
                res.add(world.getName());
            } catch (IOException e) {
                // 某个世界目录无法解析，无法判断它与备份路径是否交叠，保守起见返回 null，让调用方关闭所有世界的自动保存
                ConsoleSender.logWarn("Failed to resolve world folder for '" + world.getName() + "': " + e.getMessage() + ". Will disable auto-save for all worlds.");
                return null;
            }
        }
        return res;
    }

    /**
     * 启动指定世界的自动保存
     *
     * @param plugin          插件对象，不可为 null
     * @param worldSaveStates 之前记录的世界保存状态列表，不可为 null
     * @apiNote 此方法可在异步线程中调用
     */
    public static void enableWorldsSave(Plugin plugin, List<WorldSaveState> worldSaveStates) {
        if (plugin == null || worldSaveStates == null)
            return;
        // 记录影响到的世界数量
        AtomicInteger numAffectedWorlds = new AtomicInteger();
        List<Pair<World, WorldSaveState>> worldList = new ArrayList<>();
        for (WorldSaveState wss : worldSaveStates) {
            World world = Bukkit.getWorld(wss.getWorldName());
            if (world != null)
                worldList.add(Pair.of(world, wss));
        }
        // 等待主线程任务完成的 CountdownLatch
        CountDownLatch cdl = new CountDownLatch(1);
        // 在主线程中，设置全部世界的保存情况
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 停止世界自动保存
            try {
                worldList.forEach(pair -> {
                    // 世界目前的自动保存状态
                    boolean currentAutoSave = pair.getFirst().isAutoSave();
                    if (currentAutoSave != pair.getSecond().isCurrentAutoSaveState()) {
                        // 虽然之前关闭了自动保存，但现在状态和记录的不一样，说明在这段时间内，世界的自动保存状态被其他插件修改过，就不作改动
                        return;
                    }
                    if (currentAutoSave == pair.getSecond().isPreviousAutoSaveState()) {
                        // 原本的自动保存状态和现在的一样，说明世界本来就关闭了自动保存，就不作改动
                        return;
                    }
                    pair.getFirst().setAutoSave(pair.getSecond().isPreviousAutoSaveState());
                    numAffectedWorlds.getAndIncrement();
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
        System.out.println("Auto-save enabled, affected " + numAffectedWorlds.get() + " worlds.");
    }

    /**
     * 关闭指定世界的自动保存
     *
     * @param plugin 插件对象，不可为 null
     * @param worlds 世界名列表，如果为 null 则表示全部世界
     * @return 所有世界保存状态组成的 List
     * @apiNote 此方法可在异步线程中调用
     */
    public static List<WorldSaveState> disableWorldsSave(Plugin plugin, List<String> worlds) {
        // 这样写是因为，有的插件（比如一些需要地图还原的小游戏插件）依赖于将 world 设置为非自动保存
        // 这里特意记录一下每个世界的保存状态和原始状态
        List<WorldSaveState> worldSaveStates = new ArrayList<>();
        if (plugin == null)
            return worldSaveStates;
        // 记录影响到的世界数量
        AtomicInteger numAffectedWorlds = new AtomicInteger();
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
        // 等待主线程任务完成的 CountdownLatch
        CountDownLatch cdl = new CountDownLatch(1);
        // 在主线程中，设置全部世界的保存情况
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 停止世界自动保存
            try {
                worldList.forEach(world -> {
                    boolean prevAutoSave = world.isAutoSave();
                    if (prevAutoSave) // 之前的自动保存状态是启用的
                        numAffectedWorlds.getAndIncrement();
                    world.setAutoSave(false);
                    // 记录这个世界的保存状态
                    worldSaveStates.add(new WorldSaveState(world.getName(), prevAutoSave, false));
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
        System.out.println("Auto-save disabled, affected " + numAffectedWorlds.get() + " worlds.");
        return worldSaveStates;
    }

    /**
     * 获得日期字符串，形如20240104
     *
     * @return 日期字符串
     */
    public static String getDateStr() {
        return new SimpleDateFormat("yyyyMMdd").format(new Date());
    }

    /**
     * 读写冲突异常。在将文件加入 Zip 压缩包时，可能服务端还在异步写入这个文件，同时读写可能导致读出的数据混乱。
     */
    public static class ZipRWConflictException extends Exception {
        public ZipRWConflictException(String message) {
            super(message);
        }
    }
}