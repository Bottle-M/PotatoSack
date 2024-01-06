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
import java.util.List;

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
        if (Utils.Zip("E:\\Projects\\TestArea\\incre_test\\root-minecraft-world", "E:\\Projects\\TestArea\\compress_test.zip", false)) {
            System.out.println("success");
        } else {
            System.out.println("fail");
        }
    }

    @org.junit.Test
    public void downloadTest() throws IOException {
        File file = new File("E:\\Projects\\TestArea\\download.mp4");
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
}
