package indi.somebottle.potatosack.clients.dropbox;

import com.google.gson.Gson;
import indi.somebottle.potatosack.clients.dropbox.entities.DropboxRefreshResp;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.ExponentialBackoffCalculator;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Dropbox 访问令牌获取器
 * <p>
 * 负责管理和刷新 Dropbox OAuth2 访问令牌。
 * 使用刷新令牌（refresh token）向 Dropbox OAuth2 端点请求新的访问令牌。
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>使用刷新令牌获取访问令牌</li>
 *   <li>管理令牌的过期时间和刷新时机</li>
 *   <li>自动更新配置文件中的刷新令牌（若 Dropbox 返回新的刷新令牌）</li>
 * </ul>
 * </p>
 *
 * @see DropboxClient
 * @see DropboxTokenUpdater
 */
public class DropboxTokenFetcher {
    /**
     * Dropbox OAuth2 令牌端点
     */
    private static final String DROPBOX_TOKEN_ENDPOINT = "https://api.dropboxapi.com/oauth2/token";

    /**
     * Dropbox 应用密钥
     */
    private final String appKey;

    /**
     * Dropbox 应用密钥
     */
    private final String appSecret;

    /**
     * 配置对象，用于保存新的刷新令牌
     */
    private final Config config;

    private final Gson gson = new Gson();

    /**
     * 刷新令牌
     */
    private String refreshToken;

    /**
     * 当前访问令牌
     */
    private String accessToken;

    /**
     * 下次需要刷新令牌的时间戳（秒）
     */
    private long nextRefreshTime = 0;
    private final ExponentialBackoffCalculator backoffCalculator = new ExponentialBackoffCalculator(30);

    /**
     * 构造令牌获取器
     *
     * @param appKey Dropbox 应用密钥
     * @param appSecret Dropbox 应用密钥
     * @param refreshToken 刷新令牌
     * @param config 配置对象，用于保存新的刷新令牌
     */
    public DropboxTokenFetcher(String appKey, String appSecret, String refreshToken, Config config) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.refreshToken = refreshToken;
        this.accessToken = "";
        this.config = config;
    }

    /**
     * 获取访问令牌
     * <p>
     * 使用刷新令牌向 Dropbox OAuth2 端点请求新的访问令牌。
     * 若 Dropbox 返回新的刷新令牌，会自动更新到配置文件。
     * 令牌有效期会提前 60 秒标记为需要刷新，以避免过期。
     * </p>
     *
     * @return {@code true} 表示获取成功，{@code false} 表示获取失败
     */
    public boolean fetch() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Constants.OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(Constants.OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(Constants.OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
                .callTimeout(Constants.OKHTTP_CALL_TIMEOUT, TimeUnit.SECONDS)
                .addInterceptor(new HttpRetryInterceptor())
                .build();
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", appKey)
                .add("client_secret", appSecret)
                .build();
        Request req = new Request.Builder()
                .url(DROPBOX_TOKEN_ENDPOINT)
                .post(body)
                .build();
        try (Response response = client.newCall(req).execute()) {
            ResponseBody responseBody = response.body();
            if (response.isSuccessful() && responseBody != null) {
                DropboxRefreshResp respObj = gson.fromJson(responseBody.string(), DropboxRefreshResp.class);
                setAccessToken(respObj.accessToken);
                if (respObj.refreshToken != null && !respObj.refreshToken.isEmpty()) {
                    setRefreshToken(respObj.refreshToken);
                    if (config != null) {
                        config.setConfig(Config.KEYS.CLIENT.DROPBOX.REFRESH_TOKEN, respObj.refreshToken);
                    }
                }
                long expiresIn = respObj.expiresIn > 60 ? respObj.expiresIn - 60 : respObj.expiresIn;
                nextRefreshTime = Utils.timestamp() + expiresIn;
                backoffCalculator.reset();
                System.out.println("Dropbox token refreshed");
                return true;
            }
            String errMsg = "Dropbox token req failed, code:" + response.code() + ", msg:" + response.message();
            if (responseBody != null) {
                errMsg += ", body:" + responseBody.string();
            }
            ConsoleSender.logError(errMsg);
            backoffCalculator.backoff();
            setNextRefreshTime(Utils.timestamp() + backoffCalculator.getNextBackoffTime(600));
        } catch (IOException e) {
            ConsoleSender.logError("Dropbox token req failed: " + e.getMessage());
            backoffCalculator.backoff();
            setNextRefreshTime(Utils.timestamp() + backoffCalculator.getNextBackoffTime(600));
        } finally {
            client.dispatcher().executorService().shutdown();
        }
        setAccessToken("");
        return false;
    }

    public String getAccessToken() {
        if (accessToken.equals("")) {
            fetch();
        }
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public long getNextRefreshTime() {
        return nextRefreshTime;
    }

    public void setNextRefreshTime(long nextRefreshTime) {
        this.nextRefreshTime = nextRefreshTime;
    }
}
