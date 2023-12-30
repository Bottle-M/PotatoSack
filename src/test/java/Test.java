import indi.somebottle.potatosack.entities.drivechildren.Item;
import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.utils.ConfigOpts;

import java.util.List;

public class Test {
    @org.junit.Test
    public void test() {
        String[] test = ConfigOpts.getTestTokens();
        String clientId = test[0];
        String clientSecret = test[1];
        String refreshToken = test[2];
        TokenFetcher fetcher = new TokenFetcher(clientId, clientSecret, refreshToken);
        Client client = new Client(fetcher);
        List<Item> list = client.listItems();
        System.out.println(list);
    }
}
