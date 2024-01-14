import indi.somebottle.potatosack.entities.backup.ZipFilePath;
import indi.somebottle.potatosack.entities.driveitems.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.utils.ConfigOpts;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

public class Test {
    private final String[] test = ConfigOpts.getTestTokens();
    private final String clientId = test[0];
    private final String clientSecret = test[1];
    private final String refreshToken = test[2];
    private final TokenFetcher fetcher = new TokenFetcher(clientId, clientSecret, refreshToken, null);
    private final Client client = new Client(fetcher);

    @org.junit.Test
    public void getNextRefreshTime() {
        System.out.println(fetcher.getNextRefreshTime());
        fetcher.getAccessToken();
        System.out.println(fetcher.getNextRefreshTime());
    }

    @org.junit.Test
    public void getItem() throws IOException {
        Item item = client.getItem("test/nichijou.mp4");
        System.out.println(item);
        /*
        if(client.createFolder("test"))
            System.out.println("success");
        else
            System.out.println("fail");*/
    }

    @org.junit.Test
    public void listItems() throws IOException {
        List<Item> items = client.listItems("test");
        System.out.println(items);
    }

    @org.junit.Test
    public void createFolder() throws IOException {
        if (client.createFolder("test"))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void deleteItem() throws IOException {
        if (client.deleteItem("test"))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void splitTest() {
        String test = "20-30";
        System.out.println(test.split("-").length);
    }

    @org.junit.Test
    public void smallFileUploadTest() throws IOException {
        if (client.uploadFile("E:\\Projects\\TestArea\\1.19.json", "test/test.json"))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void largeFileUploadTest() throws IOException {
        if (client.uploadLargeFile("E:\\Projects\\TestArea\\nichijou.mp4", "test/nichijou.mp4"))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void zipTest() {
        if (Utils.Zip("E:\\Projects\\TestArea\\incre_test\\root-minecraft-world", "E:\\Projects\\TestArea\\compress_test.zip", false, false)) {
            System.out.println("success");
        } else {
            System.out.println("fail");
        }
    }

    @org.junit.Test
    public void zipSpecificDirTest() {
        String[] testInput = {
                "E:\\Projects\\TestArea\\test\\config",
                "E:\\Projects\\TestArea\\test\\logs",
                "E:\\Projects\\TestArea\\test\\plugins"
        };
        if (Utils.Zip(testInput, "E:\\Projects\\TestArea\\compress_test.zip", false)) {
            System.out.println("success");
        } else {
            System.out.println("fail");
        }
    }

    @org.junit.Test
    public void downloadTest() throws IOException {
        File file = new File("E:\\Projects\\TestArea\\testNewFolder\\test2\\download.mp4");
        if (client.downloadFile("test/nichijou.mp4", file))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void okHttp2Test() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new HttpRetryInterceptor())
                .build();
        Request req = new Request.Builder()
                .url("http://unreachable.com")
                .header("Authorization", "Bearer " + fetcher.getAccessToken())
                .build();
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                System.out.println("success");
            } else {
                System.out.println("not accessible");
            }
        } catch (IOException e) {
            System.out.println("fail due to " + e.getMessage());
        }
    }

    @org.junit.Test
    public void dateTest() {
        System.out.println(Utils.getDateStr());
    }

    @org.junit.Test
    public void getLastModifyTimesTest() {
        File testF = new File("E:\\Projects\\TestArea\\test\\plugins");
        Map<String, String[]> lastModifyTimes = Utils.getLastFileHashes(testF, null);
        System.out.println("Parent dir: " + testF.getParentFile().getAbsolutePath() + File.separator);
        for (String key : lastModifyTimes.keySet())
            System.out.println(lastModifyTimes.get(key)[0] + " - " + lastModifyTimes.get(key)[1]);
    }

    @org.junit.Test
    public void zipSpecificFileTest() {
        ZipFilePath[] testInput = {
                new ZipFilePath("E:\\Projects\\TestArea\\1.19.json", "test/test.json"),
                new ZipFilePath("E:\\Projects\\TestArea\\nichijou.mp4", "nichijou.mp4")
        };
        if (Utils.ZipSpecificFiles(testInput, "E:\\Projects\\TestArea\\compress_test.zip", false)) {
            System.out.println("success");
        } else {
            System.out.println("fail");
        }
    }

    @org.junit.Test
    public void md5Test() throws NoSuchAlgorithmException {
        long startTime = System.currentTimeMillis();
        File file1 = new File("C:\\Users\\58379\\Desktop\\incre000001\\world\\region\\r.0.0.mca");
        File file2 = new File("C:\\Users\\58379\\Desktop\\incre000002\\world\\region\\r.0.0.mca");
        System.out.println("file1 size: " + file1.length());
        System.out.println("file2 size: " + file2.length());
        System.out.println(Utils.fileMD5(
                file1
        ));
        System.out.println(Utils.fileMD5(
                file2
        ));
        long endTime = System.currentTimeMillis();
        System.out.println(endTime - startTime + "ms");
    }

    @org.junit.Test
    public void getAppFolderTest() throws IOException {
        System.out.println(client.getAppFolderUrl());
    }

    @org.junit.Test
    public void anvilFileTest() throws IOException {
        File file1 = new File("C:\\Users\\58379\\Desktop\\test\\incre000001\\world\\region\\r.0.0.mca");
        File file2 = new File("C:\\Users\\58379\\Desktop\\test\\incre000002\\world\\region\\r.0.0.mca");
        System.out.println("file1 size: " + file1.length());
        System.out.println("file2 size: " + file2.length());
        try(RandomAccessFile ras=new RandomAccessFile(file1,"r")){
            MessageDigest md = MessageDigest.getInstance("MD5");
            ras.seek(0);
            byte[] b = new byte[4096];
            int readLen=ras.read(b);
            md.update(b,0,readLen);
            System.out.println(new BigInteger(1,md.digest()).toString(16));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        try(RandomAccessFile ras=new RandomAccessFile(file2,"r")){
            MessageDigest md = MessageDigest.getInstance("MD5");
            ras.seek(0);
            byte[] b = new byte[4096];
            int readLen=ras.read(b);
            md.update(b,0,readLen);
            System.out.println(new BigInteger(1,md.digest()).toString(16));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}


