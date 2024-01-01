import indi.somebottle.potatosack.entities.driveitems.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.utils.ConfigOpts;
import indi.somebottle.potatosack.utils.Utils;

import java.util.List;

public class Test {
    private final String[] test = ConfigOpts.getTestTokens();
    private final String clientId = test[0];
    private final String clientSecret = test[1];
    private final String refreshToken = test[2];
    private final TokenFetcher fetcher = new TokenFetcher(clientId, clientSecret, refreshToken);
    private final Client client = new Client(fetcher);

    @org.junit.Test
    public void getItem() {
        Item item = client.getItem("test");
        System.out.println(item);
        /*
        if(client.createFolder("test"))
            System.out.println("success");
        else
            System.out.println("fail");*/
    }

    @org.junit.Test
    public void listItems() {
        List<Item> items = client.listItems("test");
        System.out.println(items);
    }

    @org.junit.Test
    public void createFolder() {
        if (client.createFolder("test"))
            System.out.println("success");
        else
            System.out.println("fail");
    }

    @org.junit.Test
    public void deleteItem() {
        if (client.deleteItem("test/nichijou.mp4"))
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
    public void uploadTest() {
        if (client.uploadFile("E:\\Projects\\TestArea\\nichijou.mp4", "test/nichijou.mp4"))
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
}
