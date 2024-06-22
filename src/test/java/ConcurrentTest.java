import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;

public class ConcurrentTest {

    public static void main(String[] args) {
        CountDownLatch latch = new CountDownLatch(1);
        String filePath = "C:\\Users\\58379\\Desktop\\test.txt";

        Thread thread1 = new Thread(new ReaderTask(filePath, latch));
        Thread thread2 = new Thread(new WriterTask(filePath, latch));

        thread1.start();
        thread2.start();
    }

}

class ReaderTask implements Runnable {
    private final String filePath;
    private final CountDownLatch latch;

    public ReaderTask(String filePath, CountDownLatch latch) {
        this.filePath = filePath;
        this.latch = latch;
    }

    @Override
    public void run() {
        File readingFile=new File(filePath);
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            // Read the first 5 bytes
            byte[] buffer = new byte[5];
            file.read(buffer);
            System.out.println("Thread 1 - First 5 bytes: " + new String(buffer));
            readingFile.length();
            // Wait for thread 2 to finish writing
            latch.await();

            // Continue reading the remaining bytes
            int remainingBytes = (int) (file.length() - 5);
            buffer = new byte[remainingBytes];
            file.read(buffer);
            System.out.println("Thread 1 - Remaining bytes: " + new String(buffer));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class WriterTask implements Runnable {
    private final String filePath;
    private final CountDownLatch latch;

    public WriterTask(String filePath, CountDownLatch latch) {
        this.filePath = filePath;
        this.latch = latch;
    }

    @Override
    public void run() {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
            // Move the file pointer to the end minus 5 bytes
            file.seek(file.length() - 5);

            // Write new data
            byte[] newData = "ABCDE".getBytes();
            file.write(newData);
            System.out.println("Thread 2 - Written last 5 bytes: " + new String(newData));

            // Signal thread 1 to continue
            latch.countDown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}