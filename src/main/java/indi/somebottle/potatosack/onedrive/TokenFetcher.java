package indi.somebottle.potatosack.onedrive;



public class TokenFetcher {
    private final String endPoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
    private String accessToken;

    public TokenFetcher(String clientId, String clientSecret, String refreshToken) {
        this.clientId = clientId;
        this.clientScrt = clientSecret;
        this.refreshToken = refreshToken;
        this.accessToken = "";
    }

    public void fetch() {

    }

    public void setRefreshToken(String token) {
        refreshToken = token;
    }
}
