package indi.somebottle;

import com.google.gson.Gson;

import javax.swing.JFileChooser;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        final String executionDir = System.getProperty("user.dir");
        // JSON 解析器
        Gson gson = new Gson();
        // 临时目录
        File tmpDir = new File(executionDir, "potato_sack_tmp");
        if (tmpDir.exists()) {
            // 如果存在临时目录，就删除它
            Utils.rmDir(tmpDir);
        }
        if (!tmpDir.mkdirs()) {
            // 创建临时目录
            System.out.println("Failed to create temp directory " + tmpDir.getAbsolutePath());
            System.exit(1);
        }
        int exitCode = 0;
        try {
            Scanner inputScanner = new Scanner(System.in);
            System.out.println("Welcome to Potato Sack - Backups Merger!\n");
            System.out.println("* Press enter to choose a directory that contains a group of backups. ");
            System.out.println("* Type exit and press enter to exit the program. ");
            String input = inputScanner.nextLine();
            if (input.trim().equals("exit")) {
                System.out.println("Bye!");
                throw new Utils.ExitException(0);
            }

            /*
             * Swing 组件应该在 Event Dispatch Thread (EDT) 上创建和显示。
             * 这里不再创建一个“不可见 + alwaysOnTop”的 JFrame 当 owner：Windows（尤其从 Git Bash/mintty
             * 启动时）首次初始化 AWT/Swing 时，这种 owner 组合更容易出现异常的窗口生命周期/返回结果。
             * 同时直接把 executionDir 传给 JFileChooser 构造器，避免先扫描 Windows 默认目录，再立即切目录。
             */
            File initialDirectory = new File(executionDir);
            FileChooserUtils.Result directoryChoice = FileChooserUtils.showBackupDirectoryChooser(initialDirectory);
            File selectedDir;
            if (directoryChoice.returnCode() == JFileChooser.APPROVE_OPTION) {
                selectedDir = directoryChoice.selectedFile();
                if (selectedDir == null) {
                    System.err.println("[FileChooser] APPROVE_OPTION was returned, but selected file is null.");
                    FileChooserUtils.printDiagnostics("backup directory chooser", initialDirectory,
                            directoryChoice.returnCode());
                    throw new Utils.ExitException(1);
                }
                System.out.println("Selected directory: " + selectedDir.getAbsolutePath());
            } else if (directoryChoice.returnCode() == JFileChooser.CANCEL_OPTION) {
                System.out.println("Directory selection canceled, exit.");
                throw new Utils.ExitException(1);
            } else {
                // ERROR_OPTION (-1) 或其它非预期返回值，需要把环境信息打出来，便于定位 Windows/Git Bash 问题。
                System.err.println("[FileChooser] Failed to choose a directory: "
                        + FileChooserUtils.describeResult(directoryChoice.returnCode()));
                FileChooserUtils.printDiagnostics("backup directory chooser", initialDirectory,
                        directoryChoice.returnCode());
                throw new Utils.ExitException(1);
            }
            if (!selectedDir.isDirectory()) {
                // 非目录
                System.out.println("You're not choosing a directory, exit.");
                throw new Utils.ExitException(1);
            }
            // 用于存放文件解压的临时目录
            File unzipDir = new File(tmpDir, "unzip");
            if (!unzipDir.exists() && !unzipDir.mkdirs()) {
                System.out.println("Failed to create temp directory for unzip: " + unzipDir.getAbsolutePath());
                throw new Utils.ExitException(1);
            }
            // 检查是否有 backup.json
            File backupRecordFile = new File(selectedDir, "backup.json");
            if (!backupRecordFile.exists()) {
                // 没有 backup.json
                System.out.println("No backup.json found, exit.");
                throw new Utils.ExitException(1);
            }
            // 读出备份记录
            BackupRecord backupRecord;
            try {
                String backupJson = Utils.readFile(backupRecordFile);
                backupRecord = gson.fromJson(backupJson, BackupRecord.class);
            } catch (IOException e) {
                System.out.println("Failed to read backup.json.");
                e.printStackTrace();
                throw new Utils.ExitException(1);
            }
            // 检查这组备份有没有全量备份
            File fullBackupFile = new File(selectedDir, "full.zip");
            if (!fullBackupFile.exists()) {
                // 没有全量备份
                System.out.println("No full backup exists, exit.");
                throw new Utils.ExitException(1);
            }
            // 再扫描有没有缺失增量备份 incre*.zip
            List<BackupRecord.IncreBackupHistoryItem> increHistory = backupRecord.getIncreBackupsHistory();
            for (BackupRecord.IncreBackupHistoryItem item : increHistory) {
                File increBackupFile = new File(selectedDir, "incre" + item.getId() + ".zip");
                if (!increBackupFile.exists()) {
                    // 有增量备份缺失了
                    System.out.println("Incremental backup " + item.getId() + " is missing, unable to continue.");
                    throw new Utils.ExitException(1);
                }
            }
            int mergeIncreUntil = -1;
            if (increHistory.size() == 0) {
                // 没有增量备份
                System.out.println("No incremental backup exists.");
            } else {
                // 有增量备份的话，让用户选择一直合并到哪份增量备份
                System.out.println("Incremental backups: ");
                for (int i = 0; i < increHistory.size(); i++) {
                    System.out.println("\t" + (i + 1) + ". incre" + increHistory.get(i).getId() + " - Time: " + Utils.timestampToDate(increHistory.get(i).getTime()));
                }
                System.out.println("Up to which incremental backup do you want to merge? Type the number before the option and press enter: ");
                int selected;
                while (true) {
                    selected = inputScanner.nextInt();
                    if (selected > 0 && selected <= increHistory.size()) {
                        break;
                    }
                    System.out.println("Please enter the number before the option: ");
                }
                // 合并直至下标 mergeIncreUntil
                mergeIncreUntil = selected - 1;
            }
            System.out.println("Unzipping and merging backups...");
            System.out.println("Extracting full backup...");
            // 解压全量备份
            if (!Utils.unzip(fullBackupFile, unzipDir)) {
                System.out.println("Failed to unzip full backup.");
                throw new Utils.ExitException(1);
            }
            System.out.println("Merging incremental backup...");
            // 在全量备份的基础上应用增量备份:
            // - 增量 zip 里的 .mca 可能是原样存储的完整区域文件，也可能是 PSMCA delta，由 Utils 分流处理
            // - 每个增量里所有的 zip 条目处理完之后，再按 deleted.files 清单删除文件
            for (int i = 0; i <= mergeIncreUntil; i++) {
                File increBackupFile = new File(selectedDir, "incre" + increHistory.get(i).getId() + ".zip");
                if (!Utils.mergeIncrementalZip(increBackupFile, unzipDir)) {
                    System.out.println("Failed to merge incremental backup " + increBackupFile.getAbsolutePath());
                    throw new Utils.ExitException(1);
                }
                // 解压完后根据 deleted.files 清单删除文件
                applyDeletedFilesSafely(unzipDir, new File(unzipDir, "deleted.files"));
            }
            // 让用户选择把压缩包输出到哪里
            System.out.println("Save the merged backup as...");
            File suggestedOutputFile = new File(tmpDir.getParentFile(), "merged.zip");
            File zipOutputFile;
            while (true) {
                FileChooserUtils.Result saveChoice = FileChooserUtils.showSaveFileChooser(suggestedOutputFile);
                if (saveChoice.returnCode() == JFileChooser.APPROVE_OPTION) {
                    zipOutputFile = saveChoice.selectedFile();
                    if (zipOutputFile == null) {
                        System.err.println("[FileChooser] APPROVE_OPTION was returned, but selected file is null.");
                        FileChooserUtils.printDiagnostics("save file chooser", suggestedOutputFile,
                                saveChoice.returnCode());
                        throw new Utils.ExitException(1);
                    }
                    if (zipOutputFile.exists()) {
                        if (zipOutputFile.isFile()) {
                            System.out.println("The file " + zipOutputFile.getAbsolutePath()
                                    + " already exists! Please retry.");
                            // 下次仍从刚才用户选择的位置打开，方便直接改文件名。
                            suggestedOutputFile = zipOutputFile;
                            continue;
                        }
                    }
                    break;
                } else if (saveChoice.returnCode() == JFileChooser.CANCEL_OPTION) {
                    // 在 Cancel 时明确退出，避免无法结束程序
                    System.out.println("Save canceled, exit.");
                    throw new Utils.ExitException(1);
                } else {
                    System.err.println("[FileChooser] Failed to choose output path: "
                            + FileChooserUtils.describeResult(saveChoice.returnCode()));
                    FileChooserUtils.printDiagnostics("save file chooser", suggestedOutputFile,
                            saveChoice.returnCode());
                    throw new Utils.ExitException(1);
                }
            }
            if (zipOutputFile.isDirectory()) {
                // 如果用户选择的是一个目录，在末尾加上文件名
                zipOutputFile = new File(zipOutputFile, "merged.zip");
            }
            System.out.println("\n> Output file path: " + zipOutputFile.getAbsolutePath() + "\n");
            // 合并备份后再打包成一个压缩包
            System.out.println("Zipping merged backups...");
            if (!Utils.zip(unzipDir.listFiles(), zipOutputFile, unzipDir)) {
                System.out.println("Failed to zip merged backups.");
                throw new Utils.ExitException(1);
            }
            System.out.println("Done! The merged backup has been saved as " + zipOutputFile.getAbsolutePath());
        } catch (Utils.ExitException e) {
            exitCode = e.getExitCode();
        } catch (Throwable e) {
            // 打印运行环境和完整堆栈，便于现场定位问题
            exitCode = 1;
            System.err.println("\nUnexpected error: " + e.getClass().getName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            FileChooserUtils.printRuntimeDiagnostics();
            e.printStackTrace(System.err);
            System.err.flush();
        } finally {
            // 删除临时目录
            System.out.print("Cleaning up temp files...");
            System.out.flush();
            if (!Utils.rmDir(tmpDir)) {
                System.out.println("Failed to delete temp directory " + tmpDir.getAbsolutePath());
            } else {
                System.out.println("Done");
            }
            System.gc();
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }
            // 最后按 exitCode 退出
            System.exit(exitCode);
        }
    }

    /**
     * 按 deleted.files 清单删除文件，然后删掉清单本身
     *
     * <p>清单里每行是一个相对于服务端根目录的路径。这里会对路径做规范化，拒绝绝对路径、`..` 穿越
     * 以及指向恢复目录本身的条目，避免清单被写坏时误删恢复目录之外的文件。</p>
     *
     * @param unzipDir          恢复目录
     * @param deletedRecordFile deleted.files 文件
     */
    static void applyDeletedFilesSafely(File unzipDir, File deletedRecordFile) {
        if (!deletedRecordFile.isFile())
            return;
        Path root = unzipDir.getAbsoluteFile().toPath().normalize();
        // 读出被删除的文件，进行删除
        String[] deletedFilePaths = Utils.readLines(deletedRecordFile);
        for (String deletedFilePath : deletedFilePaths) {
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
            }
        }
        // 最后删掉 deleted.files（它在该增量中不是被删除的文件，只是清单本身）
        try {
            Files.deleteIfExists(deletedRecordFile.toPath());
        } catch (IOException e) {
            System.out.println("!!WARNING!! Failed to delete file " + deletedRecordFile.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }
}
