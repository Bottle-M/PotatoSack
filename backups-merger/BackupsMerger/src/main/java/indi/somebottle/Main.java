package indi.somebottle;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner inputScanner=new Scanner(System.in);
        System.out.println("Welcome to Potato Sack - Backups Merger!\n");
        System.out.println("* Press enter to choose a directory that contains a group of backups. ");
        System.out.println("* Type exit and press enter to exit the program. ");
        String input=inputScanner.nextLine();
        if(input.trim().equals("exit")) {
            System.out.println("Bye!");
            System.exit(0);
        }

    }
}