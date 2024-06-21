package indi.somebottle;

import javax.swing.*;
import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        // 临时目录
        File tmpDir = new File(System.getProperty("user.dir"), "potato_sack_tmp");
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
            dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            dirChooser.setMultiSelectionEnabled(false);
            int result = dirChooser.showOpenDialog(null);
            File selectedFile = null;
            if (result == JFileChooser.APPROVE_OPTION) {
                selectedFile = dirChooser.getSelectedFile();
                System.out.println("Selected directory: " + selectedFile.getAbsolutePath());
            } else {
                System.out.println("No directory selected, exit.");
                System.exit(1);
            }
            if (!selectedFile.isDirectory()) {
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
            // 检查这组备份有没有全量备份
            File fullBackupFile = new File(selectedFile, "full.zip");
            if (!fullBackupFile.exists()) {
                // 没有全量备份
                System.out.println("No full backup exists, exit.");
                System.exit(1);
            }
            // 再扫描有没有增量备份 incre*.zip
            File[] incrementBackupFiles = selectedFile.listFiles(new IncrementBackupFilter());
            if (incrementBackupFiles == null || incrementBackupFiles.length == 0) {
                // 没有增量备份
                System.out.println("No increment backup found.");
            } else {
                // 有增量备份，将文件名按字典升序排列
                // TODO: 待测试
                Arrays.sort(incrementBackupFiles, Comparator.comparing(File::getName));
                // 询问用户要恢复到哪个备份版本
            }
        } finally {
            // 删除临时目录
            if (!Utils.rmDir(tmpDir)) {
                System.out.println("Failed to delete temp directory " + tmpDir.getAbsolutePath());
            }
        }

    }

    /**
     * 用于筛选出所有的增量备份 incre*.zip
     */
    private static class IncrementBackupFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            return name.startsWith("incre") && name.endsWith(".zip");
        }
    }
}