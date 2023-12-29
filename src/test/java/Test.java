import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.utils.ConfigOpts;

public class Test {
    @org.junit.Test
    public void test() {
        String[] test=ConfigOpts.getTestTokens();
        String clientId = test[0];
        String clientSecret = test[1];
        String refreshToken = test[2];
        TokenFetcher fetcher = new TokenFetcher(clientId, clientSecret, refreshToken);
        fetcher.fetch();
        while(true){

        }
    }
}
