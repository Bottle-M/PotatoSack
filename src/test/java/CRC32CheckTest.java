import indi.somebottle.potatosack.utils.Utils;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

public class CRC32CheckTest {
    public static void main(String[] args) {
        String filePath = "C:\\Users\\58379\\Desktop\\test.txt";
        AtomicBoolean stopWrite = new AtomicBoolean(false);

        Thread thread1 = new Thread(new MyReaderTask(filePath, stopWrite));
        Thread thread2 = new Thread(new MyWriterTask(filePath, stopWrite));
        Thread thread3 = new Thread(new BytesAppender(filePath, stopWrite));


        thread1.start();
        thread2.start();
        thread3.start();
    }
}

class MyReaderTask implements Runnable {
    private final String filePath;
    private final AtomicBoolean stopWrite;

    public MyReaderTask(String filePath, AtomicBoolean stopWrite) {
        this.filePath = filePath;
        this.stopWrite = stopWrite;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(2000);
        } catch (Exception e) {
            e.printStackTrace();
        }
        CRC32 crc32 = new CRC32();
        File mFile=new File(filePath);
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            file.seek(0);
            byte[] buffer = new byte[5];
            file.read(buffer);
            crc32.update(buffer);
            System.out.print(new String(buffer));
            System.out.println("File length before: " + mFile.length());
            Thread.sleep(500);
            buffer = new byte[5];
            file.read(buffer);
            crc32.update(buffer);
            System.out.println("File length after: " + mFile.length());
            stopWrite.set(true);
            System.out.print(new String(buffer));
            long previousCrc32 = crc32.getValue();
            System.out.println("\nCRC32 DURING READ: " + previousCrc32);
            // 再重新计算一次
            long newCrc32 = Utils.fileCRC32(new File(filePath));
            System.out.println("CRC32 AFTER READ: " + newCrc32);
            if (newCrc32 != previousCrc32)
                System.out.println("CRC32 does not match");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class MyWriterTask implements Runnable {
    private final String filePath;
    private final AtomicBoolean stopWrite;

    public MyWriterTask(String filePath, AtomicBoolean stopWrite) {
        this.filePath = filePath;
        this.stopWrite = stopWrite;
    }

    @Override
    public void run() {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
            do {
                file.seek(0);
                file.write(RandomStringGenerator.generateRandomString(10).getBytes());
            } while (!stopWrite.get());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class BytesAppender implements Runnable {
    private final String filePath;
    private final AtomicBoolean stopWrite;

    public BytesAppender(String filePath, AtomicBoolean stopWrite) {
        this.filePath = filePath;
        this.stopWrite = stopWrite;
    }

    @Override
    public void run() {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
            do {
                file.seek(file.length());
                file.write(RandomStringGenerator.generateRandomString(10).getBytes());
                Thread.sleep(300);
            } while (!stopWrite.get());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class RandomStringGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateRandomString(int length) {
        if (length < 1) throw new IllegalArgumentException("Length must be greater than 0");

        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int randomIndex = RANDOM.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(randomIndex));
        }
        return sb.toString();
    }
}