package indi.somebottle;

import com.google.gson.Gson;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        final String executionDir = System.getProperty("user.dir");
        final JFrame frame = new JFrame("Potato Sack");
        frame.setAlwaysOnTop(true); // 保持焦点在窗口上
        frame.setVisible(false); // 不显示这个窗口
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // JSON 解析器
        Gson gson = new Gson();
        // 临时目录
        File tmpDir = new File(executionDir, "potato_sack_tmp");
        if (!tmpDir.exists() && !tmpDir.mkdirs()) {
            System.out.println("Failed to create temp directory " + tmpDir.getAbsolutePath());
            System.exit(1);
        }
        try {
            Scanner inputScanner = new Scanner(System.in);
            System.out.println("Welcome to Potato Sack - Backups Merger!\n");
            System.out.println("* Press enter to choose a directory that contains a group of backups. ");
            System.out.println("* Type exit and press enter to exit the program. ");
            String input = inputScanner.nextLine();
            if (input.trim().equals("exit")) {
                System.out.println("Bye!");
                System.exit(0);
            }
            JFileChooser dirChooser = new JFileChooser();
            File selectedDir = null;
            dirChooser.setDialogTitle("Choose a directory that contains a group of backups.");
            dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            dirChooser.setMultiSelectionEnabled(false);
            dirChooser.setCurrentDirectory(new File(executionDir));
            int result = dirChooser.showOpenDialog(frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                selectedDir = dirChooser.getSelectedFile();
                System.out.println("Selected directory: " + selectedDir.getAbsolutePath());
            } else {
                System.out.println("No directory selected, exit.");
                System.exit(1);
            }
            if (!selectedDir.isDirectory()) {
                // 非目录
                System.out.println("You're not choosing a directory, exit.");
                System.exit(1);
            }
            // 用于存放文件解压的临时目录
            File unzipDir = new File(tmpDir, "unzip");
            if (!unzipDir.exists() && !unzipDir.mkdirs()) {
                System.out.println("Failed to create temp directory for unzip: " + unzipDir.getAbsolutePath());
                System.exit(1);
            }
            // 检查是否有 backup.json
            File backupRecordFile = new File(selectedDir, "backup.json");
            if (!backupRecordFile.exists()) {
                // 没有 backup.json
                System.out.println("No backup.json found, exit.");
                System.exit(1);
            }
            // 读出备份记录
            BackupRecord backupRecord = null;
            try {
                String backupJson = Utils.readFile(backupRecordFile);
                backupRecord = gson.fromJson(backupJson, BackupRecord.class);
            } catch (IOException e) {
                System.out.println("Failed to read backup.json.");
                e.printStackTrace();
                System.exit(1);
            }
            // 检查这组备份有没有全量备份
            File fullBackupFile = new File(selectedDir, "full.zip");
            if (!fullBackupFile.exists()) {
                // 没有全量备份
                System.out.println("No full backup exists, exit.");
                System.exit(1);
            }
            // 再扫描有没有缺失增量备份 incre*.zip
            List<BackupRecord.IncreBackupHistoryItem> increHistory = backupRecord.getIncreBackupsHistory();
            for (BackupRecord.IncreBackupHistoryItem item : increHistory) {
                File increBackupFile = new File(selectedDir, "incre" + item.getId() + ".zip");
                if (!increBackupFile.exists()) {
                    // 有增量备份缺失了
                    System.out.println("Incremental backup " + item.getId() + " is missing, unable to continue.");
                    System.exit(1);
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
                System.exit(1);
            }
            System.out.println("Merging incremental backup...");
            // 在全量备份的基础上覆盖解压增量备份
            for (int i = 0; i <= mergeIncreUntil; i++) {
                File increBackupFile = new File(selectedDir, "incre" + increHistory.get(i).getId() + ".zip");
                if (!Utils.unzip(increBackupFile, unzipDir)) {
                    System.out.println("Failed to unzip incremental backup " + increBackupFile.getAbsolutePath());
                    System.exit(1);
                }
                // 解压完后根据 deleted.files 清单删除文件
                File deletedRecordFile = new File(unzipDir, "deleted.files");
                if (deletedRecordFile.exists()) {
                    // 读出被删除的文件，进行删除
                    String[] deletedFilePaths = Utils.readLines(deletedRecordFile);
                    for (String deletedFilePath : deletedFilePaths) {
                        File deletedFile = new File(unzipDir, deletedFilePath);
                        if (deletedFile.exists()) {
                            System.out.println("\tDeleting " + deletedFile.getAbsolutePath());
                            if (!deletedFile.delete()) {
                                System.out.println("!!WARNING!! Failed to delete file " + deletedFile.getAbsolutePath());
                            }
                        }
                    }
                }
                // 最后删掉 deleted.files
                if (!deletedRecordFile.delete()) {
                    System.out.println("!!WARNING!! Failed to delete file " + deletedRecordFile.getAbsolutePath());
                }
            }
            // 让用户选择把压缩包输出到哪里
            System.out.println("Save the merged backup as...");
            dirChooser.setDialogTitle("Save the merged backup as...");
            dirChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            // 预设一个默认的路径
            dirChooser.setSelectedFile(new File(tmpDir.getParentFile(), "merged.zip"));
            dirChooser.setFileFilter(new FileNameExtensionFilter("ZIP files", "zip"));
            File zipOutputFile;
            while (true) {
                if (dirChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    zipOutputFile = dirChooser.getSelectedFile();
                    if (zipOutputFile.exists()) {
                        if (zipOutputFile.isFile())
                            System.out.println("The file " + zipOutputFile.getAbsolutePath() + " already exists! Please retry.");
                        else
                            break;
                    } else {
                        break;
                    }
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
                System.exit(1);
            }
            System.out.println("Done! The merged backup has been saved as " + zipOutputFile.getAbsolutePath());
        } finally {
            // 删除临时目录
            System.out.print("Cleaning up temp files...");
            System.out.flush();
            if (!Utils.rmDir(tmpDir)) {
                System.out.println("Failed to delete temp directory " + tmpDir.getAbsolutePath());
            } else {
                System.out.println("Done");
            }
            // 关闭空窗口，退出进程
            frame.dispose();
            System.gc();
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}