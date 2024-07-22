import org.junit.Test;

import java.io.*;
import java.nio.file.*;


public class ReadDeleteTest {
    public static String filePath = "C:\\Users\\58379\\Desktop\\test.txt";

    @Test
    public void testRD() throws InterruptedException {
        // 启动线程A读取文件
        Thread threadA = new Thread(new FileReaderTask());
        threadA.start();

        // 启动线程B删除文件
        Thread threadB = new Thread(new FileDeleterTask());
        threadB.start();

        threadA.join();
    }
}

class FileReaderTask implements Runnable {
    @Override
    public void run() {
        File file = new File(ReadDeleteTest.filePath);
        try (FileInputStream fis = new FileInputStream(file)) {
            int byteRead;
            while ((byteRead = fis.read()) != -1) {
                System.out.println("Read byte: " + byteRead);
                Thread.sleep(1000); // 每秒读取一个字节
            }
        } catch (FileNotFoundException e) {
            System.out.println("File not found: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO Exception: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Thread interrupted: " + e.getMessage());
        }
    }
}

class FileDeleterTask implements Runnable {
    @Override
    public void run() {
        try {
            Thread.sleep(3000); // 等待3秒
            Path path = Paths.get(ReadDeleteTest.filePath);
            Files.delete(path);
            System.out.println("File test.txt deleted.");
        } catch (InterruptedException e) {
            System.out.println("Thread interrupted: " + e.getMessage());
        } catch (NoSuchFileException e) {
            System.out.println("No such file: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO Exception: " + e.getMessage());
        }
    }
}