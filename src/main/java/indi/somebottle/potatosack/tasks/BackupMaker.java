package indi.somebottle.potatosack.tasks;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.tasks.entities.BackupRecord;
import indi.somebottle.potatosack.tasks.entities.DirFileRecords;
import indi.somebottle.potatosack.tasks.entities.WorldSaveState;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * 记录文件 .json 均放在插件目录 plugins/PotatoSack/data 下
 */
public class BackupMaker {
    private final Client client;
    private final Plugin plugin;
    // 记录等数据文件存放目录，plugins/PotatoSack/data
    private final String pluginDataPath;
    // 临时压缩包存放目录，plugins/PotatoSack/temp
    private final String pluginTempPath;
    private final Config config;
    private final Gson gson = new Gson();
    @SuppressWarnings("FieldCanBeLocal")
    private final String DIR_FILE_RECORDS_FILE_PREFIX = "_"; // 目录文件哈希记录文件名前缀
    /**
     * 全量备份指数退避计算器 (秒)
     */
    private final ExponentialBackoffCalculator fullBackupBackoffCalculator;
    /**
     * 增量备份指数退避计算器 (秒)
     */
    private final ExponentialBackoffCalculator increBackupBackoffCalculator;

    /**
     * 构造 BackupMaker
     *
     * @param client 文件服务客户端对象
     * @param config 配置对象
     * @throws IOException IO 异常，通常是网络原因
     */
    @SuppressWarnings({"ResultOfMethodCallIgnored", "unchecked"})
    public BackupMaker(Client client, Config config) throws IOException {
        this.client = client;
        this.config = config;
        this.plugin = PotatoSack.getPluginInstance();
        pluginDataPath = plugin.getDataFolder() + File.separator + "data";
        pluginTempPath = plugin.getDataFolder() + File.separator + "temp";
        // 建立必要的目录
        File tempDir = new File(pluginTempPath);
        if (!tempDir.exists())
            tempDir.mkdirs();
        File dataDir = new File(pluginDataPath);
        if (!dataDir.exists())
            dataDir.mkdirs();
        // 初始化本地备份记录文件
        // 先检查 backup.json
        File backupRecordFile = getBackupRecordFile();
        System.out.println("Reading backup record file: " + backupRecordFile.getAbsolutePath());
        if (getBackupRecord() == null) {
            // 这里拉取失败时 getBackupRecord 会自动建立新的 backup 记录文件，创建失败才会返回 null
            throw new IOException("Failed to create local backup record file.");
        }
        List<String> backupConfPaths = (List<String>) config.getConfig(Config.KEYS.PATHS);
        // 先检查配置的备份路径是否都是真实的目录路径
        List<String> relativeBackupConfPaths = new ArrayList<>();
        for (String backupConfPath : backupConfPaths) {
            try {
                // 如果不是服务端根目录的子孙路径就会抛出异常
                relativeBackupConfPaths.add(Utils.pathRelativeToServer(new File(backupConfPath)));
                // 检查路径是否存在且是目录
                File backupConfFile = new File(backupConfPath);
                if (!backupConfFile.exists() || !backupConfFile.isDirectory()) {
                    throw new IOException("does not exist or is not a directory.");
                }
            } catch (IOException e) {
                throw new IOException("Backup path '" + backupConfPath + "' is invalid: " + e.getMessage());
            }
        }
        // 接着检查路径是否有交叠（即是否存在某个路径是另一个路径的父目录或子目录），如果有交叠就抛出异常
        if (Utils.hasOverlappingRelativePaths(relativeBackupConfPaths)) {
            throw new IOException("There are overlapping backup paths in the configuration. Please make sure no backup path is a parent or child of another backup path.");
        }
        // 再检查配置的各个待备份路径的记录文件是否存在
        for (String backupConfPath : backupConfPaths) {
            // 每个待备份路径都有一个目录下文件的哈希记录文件 _备份路径标识.json，存放上一次备份时该路径下所有文件的哈希值
            File dirFileRecordsFile = getDirFileRecordsFile(backupConfPath);
            System.out.println("Reading hash record file: " + dirFileRecordsFile.getAbsolutePath());
            if (getDirFileRecords(backupConfPath) == null) {
                // 这里拉取失败时 getDirFileRecords 会自动建立新的记录文件，创建失败才会返回 null
                throw new IOException("Failed to create local record file for backup path: " + backupConfPath);
            }
        }
        // 初始化指数退避计算器
        fullBackupBackoffCalculator = new ExponentialBackoffCalculator(300);
        increBackupBackoffCalculator = new ExponentialBackoffCalculator(300);
    }

    /**
     * 请理临时目录中的文件，临时目录只有一层
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void cleanTempDir() {
        File tempDir = new File(pluginTempPath);
        if (tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 根据配置的世界名获取对应的 World 对象，这里涉及到 26.1 之前和之后的区别
     *
     * <p>26.1 之前，直接配置的就是世界名；</p>
     *
     * <p>26.1 及其之后，还支持 Namespaced Key</p>
     *
     * @param worldConfName 配置的世界名或 Namespaced Key 字符串（如 minecraft:overworld）
     * @return World 对象，如果找不到对应的世界则返回 null
     */
    private World resolveWorld(String worldConfName) {
        if (worldConfName == null || worldConfName.equals("")) {
            return null;
        }
        worldConfName = worldConfName.trim();

        // 先检查有没有冒号，如果有冒号就当做 Namespaced Key 来解析
        if (worldConfName.contains(":")) {
            String[] parts = worldConfName.split(":", 2);
            if (parts.length < 2 || parts[0].trim().equals("") || parts[1].trim().equals("")) {
                // 格式不对，fallback
                ConsoleSender.logDebug("Unrecognized namespaced world key '" + worldConfName + "' (with colon but invalid), fallback to normal world name");
                return plugin.getServer().getWorld(worldConfName);
            }
            String namespace = parts[0].trim();
            String key = parts[1].trim();
            return plugin.getServer().getWorld(new NamespacedKey(namespace, key));
        } else {
            // 没有冒号就直接当做世界名
            return plugin.getServer().getWorld(worldConfName);
        }
    }

    /**
     * 把配置的备份路径转换为相对服务端根目录的相对路径，然后转换为可用于文件名的规范化字符串
     *
     * <p><b>如果就是服务端根目录，会返回 "__root"</b></p>
     *
     * @param backupConfPath 配置的备份路径字符串
     * @return 规范化的路径字符串，例如 world -> world, world/region -> world__region
     */
    private String backupConfPathToNormalizedName(String backupConfPath) throws IOException {
        // 先转换为相对于服务端根目录的相对路径
        String relativePath = Utils.pathRelativeToServer(new File(backupConfPath));
        // 先把反斜杠替换成斜杠，再把多个连续的斜杠替换成一个斜杠
        // 然后去掉前导、后缀斜杠
        String normalizedPath = relativePath.replace('\\', '/').replaceAll("/+", "/").replaceAll("^/+", "").replaceAll("/+$", "");
        if (normalizedPath.isBlank()) {
            return "__root";
        }
        // 再把斜杠替换成双下划线，并把其他不合法的字符替换成下划线，得到最终的规范化字符串
        return normalizedPath.replace("/", "__").replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }

    /**
     * 根据规范的备份标识名构建对应的目录文件哈希记录文件名，文件名为 _备份路径标识，没有 .json 后缀
     *
     * @param normalizedFileName 规范的备份标识名字符串，通过 backupConfPathToNormalizedName 方法获得
     * @return 记录文件名字符串
     */
    private String buildDirFileRecordsFilename(String normalizedFileName) {
        return DIR_FILE_RECORDS_FILE_PREFIX + normalizedFileName;
    }

    /**
     * 写入本地的 backup.json
     *
     * @param rec 备份记录 BackupRecord 对象
     * @throws IOException IO异常
     */
    public void writeBackupRecord(BackupRecord rec) throws IOException {
        rec.setFileUpdateTime(Utils.timestamp());
        Files.write(getBackupRecordFile().toPath(), gson.toJson(rec).getBytes());
    }

    /**
     * writeBackupRecord 的重载，写入本地的 backup.json
     *
     * @param lastFullBackupTime  最近一次全量备份时间戳
     * @param lastIncreBackupTime 最近一次增量备份时间戳
     * @param lastFullBackupId    最近一次备份组号
     * @param lastIncreBackupId   最近一次增量备份 ID
     * @param increBackupsHistory 增量备份的历史时间戳信息
     * @throws IOException IO异常
     */
    public void writeBackupRecord(long lastFullBackupTime, long lastIncreBackupTime, String lastFullBackupId, String lastIncreBackupId, List<BackupRecord.IncreBackupHistoryItem> increBackupsHistory) throws IOException {
        BackupRecord rec = new BackupRecord();
        rec.setLastFullBackupTime(lastFullBackupTime);
        rec.setLastIncreBackupTime(lastIncreBackupTime);
        rec.setFileUpdateTime(Utils.timestamp());
        rec.setLastFullBackupId(lastFullBackupId);
        rec.setLastIncreBackupId(lastIncreBackupId);
        rec.setIncreBackupsHistory(increBackupsHistory);
        writeBackupRecord(rec);
    }

    /**
     * 将下次全量备份延迟至当前时间的一段时间后（指数退避，退避时间上界为直至下一次备份启动的间隔时间）
     *
     * @param rec BackupRecord 备份记录文件对象
     * @throws IOException 文件读写异常
     */
    public void putOffFullBackup(BackupRecord rec) throws IOException {
        String fullBackupCronExp = (String) config.getConfig(Config.KEYS.CRON.FULL_BACKUP);
        CronUtils fullBackupCron = new CronUtils(fullBackupCronExp);
        long nextTimestampFromNow = fullBackupCron.nextExecutionTimestamp(Utils.timestamp());
        // 计算现在到下一次执行的时间间隔
        long durationToNext = (nextTimestampFromNow - Utils.timestamp());
        // 计算自上次执行至今的时间间隔
        long durationFromLast = fullBackupCron.timeFromLastExecution(Utils.timestamp());
        // 退避时间不可超过 <自上一次执行到下一次执行的时间间隔>
        long durationFromLastToNext = durationToNext + durationFromLast;
        long backoffSec = fullBackupBackoffCalculator.getNextBackoffTime(durationFromLastToNext);
        fullBackupBackoffCalculator.backoff();
        rec.setLastFullBackupTime(Utils.timestamp() - durationFromLast + backoffSec);
        // 更新 backup.json
        writeBackupRecord(rec);
    }

    /**
     * 将下次增量备份延迟到当前时间的一段时间后（指数退避）
     *
     * @param rec BackupRecord 备份记录文件对象
     * @throws IOException 文件读写异常
     */
    public void putOffIncreBackup(BackupRecord rec) throws IOException {
        String increBackupCronExp = (String) config.getConfig(Config.KEYS.CRON.INCREMENTAL_BACKUP);
        CronUtils increBackupCron = new CronUtils(increBackupCronExp);
        long nextTimestampFromNow = increBackupCron.nextExecutionTimestamp(Utils.timestamp());
        // 计算现在到下一次执行的时间间隔
        long durationToNext = (nextTimestampFromNow - Utils.timestamp());
        // 计算自上次执行至今的时间间隔
        long durationFromLast = increBackupCron.timeFromLastExecution(Utils.timestamp());
        // 退避时间不可超过 <自上一次执行到下一次执行的时间间隔>
        long durationFromLastToNext = durationToNext + durationFromLast;
        long backoffSec = increBackupBackoffCalculator.getNextBackoffTime(durationFromLastToNext);
        increBackupBackoffCalculator.backoff();
        rec.setLastIncreBackupTime(Utils.timestamp() - durationFromLast + backoffSec);
        // 更新 backup.json
        writeBackupRecord(rec);
    }

    /**
     * 写入本地的 _备份路径标识.json
     *
     * @param backupConfPath 配置的备份路径字符串
     * @param rec           目录文件记录 DirFileRecords 对象
     * @throws IOException IO异常
     */
    public void writeDirFileRecords(String backupConfPath, DirFileRecords rec) throws IOException {
        rec.setFileUpdateTime(Utils.timestamp());
        Files.write(getDirFileRecordsFile(backupConfPath).toPath(), gson.toJson(rec).getBytes());
    }

    /**
     * 写入本地的 _备份路径标识.json
     *
     * @param backupConfPath 配置的备份路径字符串
     * @param recList       Map<文件相对服务端根目录的路径, 文件哈希>
     * @throws IOException IO异常
     */
    public void writeDirFileRecords(String backupConfPath, Map<String, String> recList) throws IOException {
        DirFileRecords rec = new DirFileRecords();
        rec.setFileUpdateTime(Utils.timestamp());
        rec.setLastFileHashes(recList);
        writeDirFileRecords(backupConfPath, rec);
    }

    /**
     * 取得本地的 backup.json 对象
     *
     * @return BackupRecord对象
     * @apiNote 如果 backup.json 不存在本地，会从云端拉取，拉取不成功会自动建立新的 backup.json，创建失败会返回 null
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public BackupRecord getBackupRecord() throws IOException {
        File backupRecordFile = getBackupRecordFile();
        if (!backupRecordFile.exists()) {
            if (!pullRecordsFile("backup")) {
                ConsoleSender.logInfo("Local backup record file not found, and failed to pull from cloud. Creating new local backup record file...");
                if (!backupRecordFile.getParentFile().exists()) // 要先把必要的目录给建立了
                    backupRecordFile.getParentFile().mkdirs();
                if (backupRecordFile.createNewFile()) {
                    // 初始化文件JSON内容
                    writeBackupRecord(0, 0, "", "", new ArrayList<>());
                } else {
                    return null; // 获取失败
                }
            }
        }
        // 读入backup.json
        String backupJson = new String(Files.readAllBytes(backupRecordFile.toPath()));
        // 解析成配置对象
        return gson.fromJson(backupJson, BackupRecord.class);
    }

    /**
     * 获得备份路径对应的目录下数据哈希记录（_备份路径标识.json）
     *
     * @param backupConfPath 配置的备份路径字符串
     * @return DirFileRecords 对象
     * @throws IOException IO异常
     * @apiNote 如果不存在本地，会从云端拉取，拉取不成功会自动建立新的 _备份路径标识.json。如果连文件都没法新建就会返回 null。
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public DirFileRecords getDirFileRecords(String backupConfPath) throws IOException {
        File dirFileRecordsFile = getDirFileRecordsFile(backupConfPath);
        if (!dirFileRecordsFile.exists()) {
            // 如果本地没有则尝试从云端拉取
            if (!pullRecordsFile(buildDirFileRecordsFilename(backupConfPathToNormalizedName(backupConfPath)))) {
                ConsoleSender.logInfo("Local record file for directory " + backupConfPath + " not found, and failed to pull from cloud. Creating new local record file...");
                // 如果云端也没有则创建新文件
                if (!dirFileRecordsFile.getParentFile().exists()) // 要先把必要的目录给建立了
                    dirFileRecordsFile.getParentFile().mkdirs();
                // 在本地新建文件
                if (dirFileRecordsFile.createNewFile()) {
                    writeDirFileRecords(backupConfPath, new HashMap<>());
                } else {
                    // 若文件都没法新建，返回 null
                    return null;
                }
            }
        }
        // 读入路径文件哈希记录文件 json
        String dirFileRecordsJson = new String(Files.readAllBytes(dirFileRecordsFile.toPath()));
        // 解析成配置对象
        return gson.fromJson(dirFileRecordsJson, DirFileRecords.class);
    }

    /**
     * 获得本地的backup.json文件对象
     *
     * @return File对象
     */
    public File getBackupRecordFile() {
        return new File(pluginDataPath + File.separator + "backup.json");
    }

    /**
     * 根据配置的备份路径字符串获取 _备份路径标识.json 的文件（哈希）记录文件 File 对象
     *
     * @param backupConfPath 配置的备份路径字符串
     * @return File 对象
     * @apiNote _备份路径标识.json 中存放配置的目录中所有文件的最后哈希值
     */
    public File getDirFileRecordsFile(String backupConfPath) throws IOException {
        String normalizedPathName = backupConfPathToNormalizedName(backupConfPath);
        return new File(pluginDataPath + File.separator + buildDirFileRecordsFilename(normalizedPathName) + ".json");
    }

    /**
     * 从云端拉取数据目录中的json文件 PotatoSack/备份组号/[fileName].json
     *
     * @param fileName 文件名
     * @return 是否拉取成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 文件名不包含.json后缀
     */
    public boolean pullRecordsFile(String fileName) throws IOException {
        return pullRecordsFile(new String[]{fileName});
    }

    /**
     * 从云端拉取最新一组备份中的json文件 PotatoSack/备份组号/*.json
     *
     * @param fileNames 文件名数组String[]，指定要下载的一组json文件
     * @return 是否拉取成功
     * @throws IOException 发生网络问题(比如timeout)时会抛出此错误
     * @apiNote 文件名不包含.json后缀
     */
    @SuppressWarnings("StringEqualsEmptyString")
    public boolean pullRecordsFile(String[] fileNames) throws IOException {
        // 先对OneDrive下的插件数据目录进行列表
        List<FileItem> itemsRes;
        String latestFolderName = ""; // 找出字典序上最大的一个子目录名，这里的目录名格式形如 020240104000001
        itemsRes = client.listItems(Constants.APP_DATA_FOLDER);
        for (FileItem item : itemsRes) {
            if (item.isFolder() && item.getName().compareTo(latestFolderName) > 0)
                latestFolderName = item.getName();
        }
        if (latestFolderName.equals(""))
            return false;
        // 从云端拉取backup.json
        boolean success = true;
        for (String name : fileNames) {
            String recordFilePath = pluginDataPath + File.separator + name + ".json";
            success = client.downloadFile(Constants.APP_DATA_FOLDER + "/" + latestFolderName + "/" + name + ".json", recordFilePath) && success;
        }
        return success;
    }

    /**
     * 获取当前的全量备份组号
     *
     * @param rec 备份记录对象
     * @return 下一个全量备份组号
     */
    private static @NotNull String getNextFullBackupId(BackupRecord rec) {
        String currFullBackupId; // 备份组号
        String currDate = Utils.getDateStr(); // 当前日期，形如 20240104
        String lastFullBackupId = rec.getLastFullBackupId(); // 获得前一次的备份组号
        if (lastFullBackupId.equals("") || !lastFullBackupId.substring(1, 9).equals(currDate)) {
            // 1. 尚无备份组 或 2. 不是同一天，从序号 1 重新开始
            currFullBackupId = "0" + currDate + "000001";
        } else {
            // 否则直接递增序号即可
            int serialNum = Integer.parseInt(lastFullBackupId.substring(9)) + 1;
            currFullBackupId = "0" + currDate + String.format("%06d", serialNum);
        }
        return currFullBackupId;
    }

    /**
     * 建立一个全量备份
     *
     * @return 是否成功
     * @throws IOException          IO 异常
     * @throws InterruptedException 中断异常
     * @throws NullPointerException 空指针异常（按理来说不应该出现，和记录文件读取有关）
     * @apiNote 本方法会进行: <p>1. 压缩相应文件</p><p>2. 上传压缩后的文件</p><p>3. 检查是否有需要删除的备份组（只保留几组）</p>
     */
    @SuppressWarnings({"StringEqualsEmptyString", "unchecked"})
    public boolean makeFullBackup() throws IOException, InterruptedException, NullPointerException {
        ConsoleSender.toConsole("Making full backup...");
        // 获得备份记录文件
        BackupRecord rec = getBackupRecord();
        // 获得配置的备份路径列表
        List<String> backupConfPaths = (List<String>) config.getConfig(Config.KEYS.PATHS);
        // 1. 扫描世界目录生成包含每个文件最后哈希值的 _备份路径标识.json
        long scanStartTime = System.currentTimeMillis();
        for (String backupConfPath : backupConfPaths) {
            // 扫描世界目录下的所有文件，计算文件哈希（为增量备份做准备）
            Map<String, String> currentFileHashes = Utils.getCurrentFileHashes(new File(backupConfPath), null);
            // _备份路径标识.json 中存放待备份数据目录中所有文件的最后哈希值
            writeDirFileRecords(backupConfPath, currentFileHashes);
        }
        // 统计扫描和计算哈希所需的时间 T
        long scanDuration = (System.currentTimeMillis() - scanStartTime) / 1000;
        String currFullBackupId = getNextFullBackupId(rec);
        // 全量备份文件在云端的路径
        String remotePath = Constants.APP_DATA_FOLDER + "/" + currFullBackupId + "/full.zip";
        // 扫描 backupConfPaths 对应目录的所有文件，转换为 ZipFilePath 对象数组
        ZipFilePath[] backupZipFilePaths = Utils.scanPeerDirsToZipPaths(backupConfPaths.toArray(new String[0]));
        if ((boolean) config.getConfig(Config.KEYS.USE_STREAMING_COMPRESSION_UPLOAD)) {
            // ################### 采用压缩时上传方式（内存中操作，节省硬盘空间）
            ConsoleSender.toConsole("------>[ Using Streaming Compression Upload ]<------");
            // 这里需要临时禁止自动保存
            // 因为这种上传方式需要先计算一遍ZipOutputStream输出的文件大小，再上传
            // 要保证这个期间世界数据不会变动，否则会上传失败
            // 储存停止自动保存的世界
            // TODO: 这里世界暂停保存有点难办，需要结合之后的 .potatosackignore 判断世界目录在不在备份路径里来判断需不需要关闭自动保存，目前先全部关闭
            List<WorldSaveState> worldSaveStates = Utils.disableWorldsSave(plugin, null);
            ConsoleSender.toConsole("Temporarily disabled world auto-save...");
            try {
                // 等待残余的异步保存完成
                waitForAsyncSavesComplete(backupConfPaths, scanDuration);
                if (!client.streamCompressAndUpload(backupZipFilePaths, remotePath, true)) {
                    // 如果流式压缩上传失败了就指数退避
                    putOffFullBackup(rec);
                    return false;
                }
            } finally {
                // 恢复世界自动保存
                Utils.enableWorldsSave(plugin, worldSaveStates);
                ConsoleSender.toConsole("Enabled world auto-save...");
            }
        } else {
            // ################### 采用先把压缩后的zip文件全写入硬盘，再把硬盘中的文件上传的方式
            ConsoleSender.toConsole("------>[ Using Traditional Upload (Fully write zip file to local temp folder first) ]<------");
            ConsoleSender.toConsole("Compressing...");
            // 临时文件路径
            String tempOutputFilePath = pluginTempPath + File.separator + "full" + Utils.timestamp() + ".zip";
            // 2. 开始压缩
            if (!Utils.zipSpecificFiles(backupZipFilePaths, tempOutputFilePath, true))
                return false;
            // 3. 上传压缩好的文件
            ConsoleSender.toConsole("Uploading Full Backup...");
            if (!client.uploadLargeFile(tempOutputFilePath, remotePath)) {
                putOffFullBackup(rec);
                return false;
            }
        }
        // 如果备份成功了就重置指数退避计算器
        fullBackupBackoffCalculator.reset();
        // 4. 更新备份记录
        // 写入backup.json
        rec.setLastFullBackupId(currFullBackupId);
        rec.setLastFullBackupTime(Utils.timestamp());
        // 全量备份后也要修改增量备份时间记录
        rec.setLastIncreBackupTime(Utils.timestamp());
        rec.setLastIncreBackupId(""); // 同时重置增量备份ID，让其从000001重新开始
        // 全量备份后重置增量备份历史
        rec.clearIncreBackupsHistory();
        writeBackupRecord(rec);
        // 全量备份完成，根据在线人数决定是否重置标记位
        try {
            if (Bukkit.getOnlinePlayers().size() < 1) {
                // 无人在线，重置标记位
                LocalStatus.getInstance().setFullBackupFlag(false);
            }
            // 如果有人在线，保持 true
        } catch (IOException e) {
            ConsoleSender.logError("[LocalStatus] Failed to reset full backup flag: " + e.getMessage());
            e.printStackTrace();
        }
        // 5. 上传备份记录
        ConsoleSender.toConsole("Uploading Record Files...");
        if (!client.uploadFile(pluginDataPath + File.separator + "backup.json", Constants.APP_DATA_FOLDER + "/" + currFullBackupId + "/backup.json"))
            return false;
        for (String backupConfPath : backupConfPaths) {
            String normalizedDirFileRecordsFileName = backupConfPathToNormalizedName(backupConfPath);
            if (!client.uploadFile(pluginDataPath + File.separator + buildDirFileRecordsFilename(normalizedDirFileRecordsFileName) + ".json", Constants.APP_DATA_FOLDER + "/" + currFullBackupId + "/" + buildDirFileRecordsFilename(normalizedDirFileRecordsFileName) + ".json"))
                return false;
        }
        // 6. 删除过时备份
        // 列出云端目录中所有目录
        List<FileItem> itemsRes = client.listItems(Constants.APP_DATA_FOLDER);
        // 筛出目录
        // 按字典升序排序，把老备份放在前面
        List<FileItem> folderItems = itemsRes.stream()
                .filter(FileItem::isFolder)
                .sorted(Comparator.comparing(FileItem::getName))
                .toList();
        int deleteCnt = folderItems.size() - (int) config.getConfig(Config.KEYS.MAX_FULL_BACKUPS_RETAINED);
        if (deleteCnt > 0) {
            ConsoleSender.toConsole("Deleting Old Backups...");
            for (int i = 0; i < deleteCnt; i++) {
                // 移除多余备份
                String deleteName = folderItems.get(i).getName();
                if (!client.deleteItem(Constants.APP_DATA_FOLDER + "/" + deleteName))
                    return false;
            }
        }
        ConsoleSender.toConsole("Successfully made backup: " + currFullBackupId);
        return true;
    }

    /**
     * 建立一个增量备份
     *
     * @return 是否成功
     * @throws IOException          IO 异常
     * @throws InterruptedException 中断异常
     * @throws NullPointerException 空指针异常（按理来说不应该出现，和记录文件读取有关）
     */
    @SuppressWarnings({"StringEqualsEmptyString", "unchecked"})
    public boolean makeIncreBackup() throws IOException, InterruptedException, NullPointerException {
        ConsoleSender.toConsole("Making incremental backup...");
        // 获得备份记录文件
        BackupRecord rec = getBackupRecord();
        String lastFullBackupId = rec.getLastFullBackupId();
        if (lastFullBackupId.equals("")) {
            // 如果还没有全量备份则进行全量备份
            ConsoleSender.logWarn("No full backup found, trying to make full backup first.");
            return makeFullBackup();
        }
        // 获得世界名
        List<String> backupConfPaths = (List<String>) config.getConfig(Config.KEYS.PATHS);
        // 记录相比上次增量备份时，被删除的文件的路径（相对路径）
        List<String> deletedPaths = new ArrayList<>();
        // 所有有变动文件的 【绝对路径】（方便Zip打包）
        List<ZipFilePath> increFilePaths = new ArrayList<>();
        // 1. 扫描每个世界目录找到有差异的文件
        long scanStartTime = System.currentTimeMillis();
        for (String backupConfPath : backupConfPaths) {
            // 扫描指定目录下的所有文件，计算哈希值（为增量备份做准备）
            Map<String, String> currentFileHashes = Utils.getCurrentFileHashes(new File(backupConfPath), null);
            // 获得上一次增量备份时的文件哈希值
            DirFileRecords prevDirFileRec = getDirFileRecords(backupConfPath);
            Map<String, String> prevLastFileHashes = prevDirFileRec.getLastFileHashes();
            // 找到被删除的文件的路径
            deletedPaths.addAll(
                    Utils.getDeletedFilePaths(prevLastFileHashes, currentFileHashes)
            );
            // 找到发生变动的文件的绝对路径
            for (String key : currentFileHashes.keySet()) {
                // 新记录中新出现的文件 or 新记录中的文件哈希相比旧记录有变动
                // key 其实是文件的相对路径
                if (!prevLastFileHashes.containsKey(key) || !prevLastFileHashes.get(key).equals(currentFileHashes.get(key)))
                    increFilePaths.add( // 添加到增量文件列表
                            new ZipFilePath(
                                    // 获得文件绝对路径以便Zip打包, key 就是文件相对于服务端根目录的相对路径
                                    Utils.pathAbsToServer(key),
                                    key
                            )
                    );
            }
            // 更新 _备份路径标识.json 中存放世界数据目录中所有文件的最后哈希值
            writeDirFileRecords(backupConfPath, currentFileHashes);
        }
        long scanDuration = (System.currentTimeMillis() - scanStartTime) / 1000;
        // 若没有文件变更则不进行本次增量备份
        if (increFilePaths.size() == 0 && deletedPaths.size() == 0) {
            ConsoleSender.toConsole("No new files found, skip this incremental backup.");
            return true;
        }
        // 2. 压缩
        // 写入deleted.files文件
        String deletedRecordPath = pluginTempPath + File.separator + "deleted.files";
        String deletedFileContent = String.join("\n", deletedPaths) + "\n";
        Files.write(new File(deletedRecordPath).toPath(), deletedFileContent.getBytes());
        // 把deleted.files文件也加入压缩包
        increFilePaths.add(new ZipFilePath(deletedRecordPath, "deleted.files"));
        // 增量备份序号
        String increBackupId = rec.getLastIncreBackupId();
        if (increBackupId.equals("")) {
            // 若还没有序号记载则从1开始
            increBackupId = "000001";
        } else {
            // 否则在上一次的基础上递增
            long increBackupIdNum = Long.parseLong(increBackupId);
            increBackupId = String.format("%06d", increBackupIdNum + 1);
        }
        // 压缩包在云端的路径
        String remotePath = Constants.APP_DATA_FOLDER + "/" + lastFullBackupId + "/incre" + increBackupId + ".zip";
        if ((boolean) config.getConfig(Config.KEYS.USE_STREAMING_COMPRESSION_UPLOAD)) {
            // ################### 采用压缩时上传方式（内存中操作，节省硬盘空间）
            ConsoleSender.toConsole("------>[ Using Streaming Upload ]<------");
            // 这里需要临时禁止自动保存
            // 因为这种上传方式需要先计算一遍ZipOutputStream输出的文件大小，再上传
            // 要保证这个期间世界数据不会变动，否则会上传失败
            // 储存停止自动保存的世界
            // TODO: 同上
            List<WorldSaveState> worldSaveStates = Utils.disableWorldsSave(plugin, null);
            ConsoleSender.toConsole("Temporarily stopped world auto-save...");
            try {
                // 等待残余的异步保存完成
                waitForAsyncSavesComplete(backupConfPaths, scanDuration);
                if (!client.streamCompressAndUpload(increFilePaths.toArray(new ZipFilePath[0]), remotePath, true)) {
                    // 流式压缩上传如果失败就指数退避
                    putOffIncreBackup(rec);
                    return false;
                }
            } finally {
                // 恢复世界自动保存
                Utils.enableWorldsSave(plugin, worldSaveStates);
                ConsoleSender.toConsole("Restarted world auto-save...");
            }
        } else {
            // ################### 采用先把压缩后的zip文件全写入硬盘，再把硬盘中的文件上传的方式
            ConsoleSender.toConsole("------>[ Using Traditional Upload (Fully write zip file to local temp folder first) ]<------");
            ConsoleSender.toConsole("Compressing...");
            // 输出文件路径
            String tempOutputFilePath = pluginTempPath + File.separator + "incre" + increBackupId + ".zip";
            // 执行压缩
            if (!Utils.zipSpecificFiles(increFilePaths.toArray(new ZipFilePath[0]), tempOutputFilePath, true))
                return false;
            // 3. 上传
            ConsoleSender.toConsole("Uploading Incremental Backup...");
            if (!client.uploadFile(tempOutputFilePath, remotePath)) {
                putOffIncreBackup(rec);
                return false;
            }
        }
        // 如果成功了就重置指数退避计算器
        increBackupBackoffCalculator.reset();
        // 4. 更新备份记录
        rec.setLastIncreBackupId(increBackupId);
        long currentTimestamp = Utils.timestamp();
        rec.setLastIncreBackupTime(currentTimestamp);
        // 把增量备份记录加入历史
        rec.addIncreBackupHistoryItem(increBackupId, currentTimestamp);
        writeBackupRecord(rec);
        // 增量备份完成，根据在线人数决定是否重置标记位
        try {
            if (Bukkit.getOnlinePlayers().size() < 1) {
                // 无人在线，重置标记位
                LocalStatus.getInstance().setIncreBackupFlag(false);
            }
            // 如果有人在线，保持 true
        } catch (IOException e) {
            ConsoleSender.logError("[LocalStatus] Failed to reset incremental backup flag: " + e.getMessage());
            e.printStackTrace();
        }
        // 5. 上传备份记录
        ConsoleSender.toConsole("Uploading Record Files...");
        if (!client.uploadFile(pluginDataPath + File.separator + "backup.json", Constants.APP_DATA_FOLDER + "/" + lastFullBackupId + "/backup.json"))
            return false;
        for (String backupConfPath : backupConfPaths) {
            String normalizedDirFileRecordsFileName = backupConfPathToNormalizedName(backupConfPath);
            if (!client.uploadFile(pluginDataPath + File.separator + buildDirFileRecordsFilename(normalizedDirFileRecordsFileName) + ".json", Constants.APP_DATA_FOLDER + "/" + lastFullBackupId + "/" + buildDirFileRecordsFilename(normalizedDirFileRecordsFileName) + ".json"))
                return false;
        }
        ConsoleSender.toConsole("Successfully made incremental backup: " + increBackupId + " in backup group " + lastFullBackupId);
        return true;
    }

    /**
     * 获取上次全量备份时间戳
     *
     * @return 时间戳，单位秒
     * @throws IOException IO 异常
     */
    public long getLastFullBackupTime() throws IOException {
        BackupRecord rec = getBackupRecord();
        return rec.getLastFullBackupTime();
    }

    /**
     * 获取上次增量备份时间戳
     *
     * @return 时间戳，单位秒
     * @throws IOException IO 异常
     */
    public long getLastIncreBackupTime() throws IOException {
        BackupRecord rec = getBackupRecord();
        return rec.getLastIncreBackupTime();
    }

    /**
     * 在流式备份前等待残余的异步保存完成
     * <p>
     * 即使关闭了自动保存，之前可能还有正在异步进行的保存工作。
     * 此方法通过动态等待和文件变动检测，确保所有异步保存完成后再开始备份，
     * 避免因文件变动导致流式上传失败。
     * <p>
     * 等待策略：先等待 max(MIN_WAIT, scanDuration*2) 秒，然后检测文件是否变动，
     * 若有变动则继续等待，直到无变动或达到总等待上限。
     *
     * @param backupConfPaths       配置的备份路径列表，用于检测这些路径下的文件变动
     * @param scanDuration 扫描文件哈希所用时长（秒）
     * @throws InterruptedException 线程中断异常
     */
    @SuppressWarnings("BusyWait")
    private void waitForAsyncSavesComplete(List<String> backupConfPaths, long scanDuration) throws InterruptedException, IOException {
        ConsoleSender.toConsole("Waiting for async saves to complete (scan took " + scanDuration + "s)...");

        // 立即获取第一次文件修改时间快照作为基准
        Map<String, Long> lastSnapshot = Utils.getTimeSnapshotForAllFilesUnderPaths(plugin, backupConfPaths);

        // 计算单次等待时间
        long singleWaitTime = Math.max(Constants.STREAMING_UPLOAD_MIN_WAIT_SECONDS, scanDuration * 2);
        long totalWaitTime = 0;

        while (totalWaitTime < Constants.STREAMING_UPLOAD_MAX_TOTAL_WAIT_SECONDS) {
            ConsoleSender.toConsole("Waiting " + singleWaitTime + "s... (total: " + totalWaitTime + "s)");
            Thread.sleep(singleWaitTime * 1000);
            totalWaitTime += singleWaitTime;

            // 重新扫描文件修改时间
            Map<String, Long> currentSnapshot = Utils.getTimeSnapshotForAllFilesUnderPaths(plugin, backupConfPaths);

            // 比较修改时间快照
            if (currentSnapshot.equals(lastSnapshot)) {
                // 无变动，结束等待
                ConsoleSender.toConsole("No file changes detected, proceeding with backup.");
                return;
            }

            // 有变动，更新快照，继续等待
            ConsoleSender.toConsole("File changes detected, waiting another " + singleWaitTime + "s...");
            lastSnapshot = currentSnapshot;

            // 检查是否即将超时
            if (totalWaitTime + singleWaitTime > Constants.STREAMING_UPLOAD_MAX_TOTAL_WAIT_SECONDS) {
                break;
            }
        }

        // 达到上限
        ConsoleSender.logWarn("Reached maximum wait time (" + Constants.STREAMING_UPLOAD_MAX_TOTAL_WAIT_SECONDS + "s), proceeding with backup anyway.");
    }
}
