package indi.somebottle;


import javax.swing.JFileChooser;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        // 传入命令行参数时完全走命令行模式，不初始化 Swing
        if (args.length > 0) {
            int exitCode = CommandLine.runCommandLine(args);
            System.exit(exitCode);
            return;
        }

        final String executionDir = System.getProperty("user.dir");
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
                backupRecord = Task.readBackupRecord(selectedDir);
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
            List<BackupRecord.IncreBackupHistoryItem> increHistory =
                    Task.discoverAvailableIncrementals(selectedDir, backupRecord.getIncreBackupsHistory());
            if (!Task.validateIncrementalFiles(selectedDir, increHistory)) {
                throw new Utils.ExitException(1);
            }
            int mergeIncreUntil = -1;
            if (increHistory.size() == 0) {
                // 没有增量备份
                System.out.println("No incremental backup exists.");
            } else {
                // 有增量备份的话，让用户选择一直合并到哪份增量备份
                Task.printIncrementals(increHistory);
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
            if (Task.validateOutputFile(zipOutputFile, fullBackupFile, selectedDir, increHistory) != 0) {
                throw new Utils.ExitException(1);
            }
            if (!Task.mergeBackups(selectedDir, fullBackupFile, increHistory,
                    mergeIncreUntil + 1, zipOutputFile, tmpDir)) {
                throw new Utils.ExitException(1);
            }
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

}
