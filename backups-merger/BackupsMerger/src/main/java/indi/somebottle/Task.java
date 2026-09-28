package indi.somebottle;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 备份合并任务会用到的方法
 */
public final class Task {
    private Task() {
    }

    /**
     * 读取备份组目录下的 backup.json 文件，解析成 BackupRecord 对象
     */
    static BackupRecord readBackupRecord(File groupDir) throws IOException {
        File backupRecordFile = new File(groupDir, "backup.json");
        if (!backupRecordFile.isFile())
            throw new IOException("no backup.json found in " + groupDir.getAbsolutePath());
        final BackupRecord backupRecord;
        try {
            backupRecord = new Gson().fromJson(Utils.readFile(backupRecordFile), BackupRecord.class);
        } catch (JsonParseException e) {
            throw new IOException("failed to parse " + backupRecordFile.getAbsolutePath() + ": "
                    + e.getMessage(), e);
        }
        if (backupRecord == null)
            throw new IOException("backup.json is empty: " + backupRecordFile.getAbsolutePath());
        return backupRecord;
    }

    /** 打印增量备份列表 */
    static void printIncrementals(List<BackupRecord.IncreBackupHistoryItem> increHistory) {
        if (increHistory.isEmpty()) {
            System.out.println("No incremental backup exists.");
            return;
        }
        System.out.println("Incremental backups:");
        for (int i = 0; i < increHistory.size(); i++) {
            BackupRecord.IncreBackupHistoryItem item = increHistory.get(i);
            String time = item.getTime() == 0 ? " (not listed in backup.json)" :
                    " - Time: " + Utils.timestampToDate(item.getTime());
            System.out.println("\t" + (i + 1) + ". incre" + item.getId() + time);
        }
    }

    /**
     * 寻找可用的增量备份。backup.json 上传失败时，已完成上传的下一份增量 ZIP
     * 可能未存入历史记录
     */
    static List<BackupRecord.IncreBackupHistoryItem> discoverAvailableIncrementals(
            File groupDir, List<BackupRecord.IncreBackupHistoryItem> declared) {
        List<BackupRecord.IncreBackupHistoryItem> available =
                declared == null ? new ArrayList<>() : new ArrayList<>(declared);
        long next = 1;
        if (!available.isEmpty()) {
            try {
                next = Long.parseLong(available.get(available.size() - 1).getId()) + 1;
            } catch (NumberFormatException e) {
                return available;
            }
        }
        while (next > 0) {
            String id = String.format("%06d", next);
            if (!new File(groupDir, "incre" + id + ".zip").isFile())
                break;
            BackupRecord.IncreBackupHistoryItem item = new BackupRecord.IncreBackupHistoryItem();
            item.setId(id);
            available.add(item);
            next++;
        }
        return available;
    }

    /**
     * 检查合并输出路径是否合法
     *
     * @return 0 表示合法，1 表示路径或文件系统错误，2 表示会覆盖备份源文件
     */
    public static int validateOutputFile(File outputFile, File fullBackupFile, File groupDir,
                                         List<BackupRecord.IncreBackupHistoryItem> increHistory) {
        if (outputFile == null) {
            System.err.println("Error: output-file is required");
            return 1;
        }
        File absoluteOutputFile = outputFile.getAbsoluteFile();
        if (absoluteOutputFile.exists() && absoluteOutputFile.isDirectory()) {
            System.err.println("Error: output-file is a directory: "
                    + absoluteOutputFile.getAbsolutePath());
            return 1;
        }
        File outputParent = absoluteOutputFile.getParentFile();
        if (outputParent == null || !outputParent.isDirectory()) {
            System.err.println("Error: output directory does not exist: "
                    + (outputParent == null ? "<unknown>" : outputParent.getAbsolutePath()));
            return 1;
        }

        try {
            if (fullBackupFile != null && FileChooserUtils.isSameOutputFile(absoluteOutputFile, fullBackupFile)) {
                System.err.println("Error: output file must not overwrite full.zip");
                return 2;
            }
            if (groupDir != null && increHistory != null) {
                for (BackupRecord.IncreBackupHistoryItem item : increHistory) {
                    if (item == null || item.getId() == null || item.getId().isBlank())
                        continue;
                    File increFile = new File(groupDir, "incre" + item.getId() + ".zip");
                    if (FileChooserUtils.isSameOutputFile(absoluteOutputFile, increFile)) {
                        System.err.println("Error: output file must not overwrite an incremental backup");
                        return 2;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error: failed to check output path: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    /**
     * 验证增量备份记录对应的 zip 文件是否存在
     *
     * @param groupDir 备份组目录
     * @param increHistory 增量备份历史
     * @return 如果所有增量备份文件都存在，返回 true
     */
    public static boolean validateIncrementalFiles(File groupDir,
                                                   List<BackupRecord.IncreBackupHistoryItem> increHistory) {
        if (groupDir == null || increHistory == null)
            return false;
        for (BackupRecord.IncreBackupHistoryItem item : increHistory) {
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                System.err.println("Error: backup.json contains an incremental backup without an id");
                return false;
            }
            File increBackupFile = new File(groupDir, "incre" + item.getId() + ".zip");
            if (!increBackupFile.isFile()) {
                System.err.println("Error: incremental backup " + item.getId() + " is missing: "
                        + increBackupFile.getAbsolutePath());
                return false;
            }
        }
        return true;
    }

    /**
     * 把一份增量备份合并到已经恢复出来的目录中
     *
     * <p>普通文件和原样存储的 `.mca` 会覆盖目标文件；以 {@code PSMCA\0} 开头的 `.mca`
     * 会基于目标目录中的完整区域文件应用 delta。</p>
     *
     * @param zipFile   增量备份 Zip 文件
     * @param targetDir 已经解压好全量备份（以及更早的增量）的目录
     * @return 是否合并成功
     */
    public static boolean mergeIncrementalZip(File zipFile, File targetDir) {
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zipIn.getNextEntry();
            while (zipEntry != null) {
                String fileName = zipEntry.getName();
                File currFile;
                try {
                    currFile = Utils.resolveZipEntryTarget(targetDir, fileName);
                } catch (IOException e) {
                    System.out.println("Refusing zip entry: " + e.getMessage());
                    return false;
                }
                if (Utils.isDirectoryEntry(zipEntry)) {
                    if (!currFile.exists() && !currFile.mkdirs()) {
                        System.out.println("Failed to create directory " + currFile.getAbsolutePath());
                        return false;
                    }
                } else {
                    System.out.println("\tMerging: " + fileName);
                    File tmpFile = null;
                    File mergedFile = null;
                    try {
                        // 先落盘，才能判断条目到底是 delta 还是原样存储的 .mca。
                        tmpFile = Utils.spoolEntry(zipIn, currFile);
                        if (isMcaPath(fileName) && McaDeltaMerger.hasMagic(tmpFile)) {
                            if (!currFile.isFile()) {
                                System.out.println("Incremental region entry " + fileName
                                        + " is a PSMCA delta, but there is no baseline region file at "
                                        + currFile.getAbsolutePath());
                                return false;
                            }
                            if (McaDeltaMerger.hasMagic(currFile)) {
                                System.out.println("Baseline region file " + currFile.getAbsolutePath()
                                        + " is itself a PSMCA delta, cannot apply " + fileName);
                                return false;
                            }
                            mergedFile = Utils.createSiblingTempFile(currFile);
                            McaDeltaMerger.apply(currFile, tmpFile, mergedFile);
                            Utils.moveAtomically(mergedFile, currFile);
                            mergedFile = null;
                        } else {
                            Utils.moveAtomically(tmpFile, currFile);
                            tmpFile = null;
                        }
                    } catch (IOException e) {
                        System.out.println("Failed to merge " + fileName + " into "
                                + currFile.getAbsolutePath() + ": " + e.getMessage());
                        return false;
                    } finally {
                        Utils.deleteQuietly(tmpFile);
                        Utils.deleteQuietly(mergedFile);
                    }
                }
                zipIn.closeEntry();
                zipEntry = zipIn.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 按 deleted.files 清单删除文件，然后删掉清单本身
     *
     * <p>清单路径必须位于恢复目录内，绝对路径、`..` 穿越以及恢复目录本身都会被拒绝。</p>
     */
    static boolean applyDeletedFilesSafely(File unzipDir, File deletedRecordFile) {
        if (!deletedRecordFile.isFile())
            return true;
        Path root = unzipDir.getAbsoluteFile().toPath().normalize();
        for (String deletedFilePath : Utils.readLines(deletedRecordFile)) {
            Path resolved;
            try {
                resolved = root.resolve(deletedFilePath).normalize();
            } catch (InvalidPathException e) {
                System.out.println("!!WARNING!! Ignoring invalid path in deleted.files: " + deletedFilePath);
                continue;
            }
            if (resolved.equals(root) || !resolved.startsWith(root)) {
                System.out.println("!!WARNING!! Ignoring unsafe path in deleted.files: " + deletedFilePath);
                continue;
            }
            File deletedFile = resolved.toFile();
            if (!deletedFile.exists())
                continue;
            System.out.println("\tDeleting " + deletedFile.getAbsolutePath());
            try {
                Files.deleteIfExists(deletedFile.toPath());
            } catch (IOException e) {
                System.out.println("!!WARNING!! Failed to delete file " + deletedFile.getAbsolutePath()
                        + ": " + e.getMessage());
                return false;
            }
        }
        try {
            Files.deleteIfExists(deletedRecordFile.toPath());
        } catch (IOException e) {
            System.out.println("!!WARNING!! Failed to delete file " + deletedRecordFile.getAbsolutePath()
                    + ": " + e.getMessage());
            return false;
        }
        return true;
    }

    private static boolean isMcaPath(String entryName) {
        return entryName != null && entryName.toLowerCase(Locale.ROOT).endsWith(".mca");
    }

    /**
     * 将全量备份和前 {@code mergeCount} 份增量备份合并成一个 zip 文件
     *
     * @param groupDir       备份组目录
     * @param fullBackupFile 全量备份 zip 文件
     * @param increHistory   可用增量备份，顺序与列表中的序号一致
     * @param mergeCount     增量备份序号，表示要合并到哪一份增量备份
     * @param outputFile     合并后的输出文件
     * @param tmpDir         本次任务使用的临时目录，由调用方负责在任务结束后删除
     * @return 是否成功
     */
    public static boolean mergeBackups(File groupDir, File fullBackupFile,
                                       List<BackupRecord.IncreBackupHistoryItem> increHistory,
                                       int mergeCount, File outputFile, File tmpDir) {
        if (groupDir == null || fullBackupFile == null || increHistory == null
                || outputFile == null || tmpDir == null) {
            System.err.println("Error: merge task received a null argument");
            return false;
        }
        if (mergeCount < 0 || mergeCount > increHistory.size()) {
            System.err.println("Error: invalid number of incremental backups to merge: " + mergeCount);
            return false;
        }
        File absoluteOutputFile = outputFile.getAbsoluteFile();
        if (validateOutputFile(absoluteOutputFile, fullBackupFile, groupDir, increHistory) != 0)
            return false;

        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            System.err.println("Error: failed to create temp directory: " + tmpDir.getAbsolutePath());
            return false;
        }
        File unzipDir = new File(tmpDir, "unzip");
        if (unzipDir.exists() && !Utils.rmDir(unzipDir)) {
            System.err.println("Error: failed to clear temp directory for unzip: "
                    + unzipDir.getAbsolutePath());
            return false;
        }
        if (!unzipDir.mkdirs()) {
            System.err.println("Error: failed to create temp directory for unzip: "
                    + unzipDir.getAbsolutePath());
            return false;
        }

        System.out.println("Unzipping and merging backups...");
        System.out.println("Extracting full backup...");
        if (!Utils.unzip(fullBackupFile, unzipDir)) {
            System.err.println("Error: failed to unzip full backup.");
            return false;
        }

        System.out.println("Merging incremental backups...");
        for (int i = 0; i < mergeCount; i++) {
            BackupRecord.IncreBackupHistoryItem item = increHistory.get(i);
            if (item == null || item.getId() == null || item.getId().isBlank()) {
                System.err.println("Error: incremental backup has no id at index " + i);
                return false;
            }
            File increBackupFile = new File(groupDir, "incre" + item.getId() + ".zip");
            if (!mergeIncrementalZip(increBackupFile, unzipDir)) {
                System.err.println("Error: failed to merge incremental backup "
                        + increBackupFile.getAbsolutePath());
                return false;
            }
            if (!applyDeletedFilesSafely(unzipDir, new File(unzipDir, "deleted.files"))) {
                System.err.println("Error: failed to apply deleted.files for "
                        + increBackupFile.getAbsolutePath());
                return false;
            }
        }

        File outputParent = absoluteOutputFile.getParentFile();
        File[] mergedFiles = unzipDir.listFiles();
        if (mergedFiles == null) {
            System.err.println("Error: failed to read merged backup files");
            return false;
        }

        System.out.println("\n> Output file path: " + absoluteOutputFile.getAbsolutePath() + "\n");
        System.out.println("Zipping merged backups...");
        Path temporaryOutput = null;
        try {
            // 先在目标文件所在目录生成临时 zip，再原子替换目标，避免留下半成品
            temporaryOutput = Files.createTempFile(outputParent.toPath(),
                    ".backups-merger-", ".zip");
            if (!Utils.zip(mergedFiles, temporaryOutput.toFile(), unzipDir)) {
                System.err.println("Error: failed to create merged backup "
                        + absoluteOutputFile.getAbsolutePath());
                return false;
            }
            Utils.moveAtomically(temporaryOutput, absoluteOutputFile.toPath());
            temporaryOutput = null;
        } catch (IOException e) {
            System.err.println("Error: failed to save merged backup "
                    + absoluteOutputFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        } finally {
            if (temporaryOutput != null) {
                try {
                    Files.deleteIfExists(temporaryOutput);
                } catch (IOException e) {
                    System.err.println("Warning: failed to delete temporary output " + temporaryOutput);
                }
            }
        }

        System.out.println("Done! The merged backup has been saved as "
                + absoluteOutputFile.getAbsolutePath());
        return true;
    }
}
