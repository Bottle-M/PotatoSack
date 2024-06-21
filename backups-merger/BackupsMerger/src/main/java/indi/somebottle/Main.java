package indi.somebottle;

import javax.swing.*;
import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
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
            System.exit(0);
        }
        if (!selectedFile.isDirectory()) {
            // 非目录
            System.out.println("You're not choosing a directory, exit.");
            System.exit(0);
        }
        // 检查必要的文件是否存在
        String[] necessaryFiles = {"config.json", "data.db", "data.db-shm", "data.db-wal"};

    }
}