package indi.somebottle.potatosack.clients.onedrive;


import com.google.gson.Gson;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveRefreshResp;
import indi.somebottle.potatosack.utils.*;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class OneDriveTokenFetcher {
    @SuppressWarnings("FieldCanBeLocal")
    private final String MS_TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"; // Microsoft Token 更新终结点
    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
    private long nextRefreshTime = 0;
    private String accessToken;
    private final Gson gson = new Gson();
    private final Config config;
    private final ExponentialBackoffCalculator backoffCalculator = new ExponentialBackoffCalculator(30);

    public OneDriveTokenFetcher(String clientId, String clientSecret, String refreshToken, Config config) {
        this.clientId = clientId;
        this.clientScrt = clientSecret;
        this.refreshToken = refreshToken;
        this.accessToken = "";
        this.config = config;
    }

    /**
     * 获取token
     *
     * @return 是否刷新成功
     * @apiNote 此方法会造成阻塞
     */
    public boolean fetch() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Constants.OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(Constants.OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(Constants.OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
                .callTimeout(Constants.OKHTTP_CALL_TIMEOUT, TimeUnit.SECONDS)
                .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
                .build();
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_secret", clientScrt)
                .build();
        Request postReq = new Request.Builder()
                .url(MS_TOKEN_ENDPOINT)
                .post(body)
                .build();
        ResponseBody responseBody = null;
        try {
            Response response = client.newCall(postReq).execute();
            responseBody = response.body();
            if (response.isSuccessful()) {
                if (responseBody != null) {
                    String rawResp = responseBody.string();
                    OneDriveRefreshResp respObj = gson.fromJson(rawResp, OneDriveRefreshResp.class);
                    setRefreshToken(respObj.refreshToken);
                    setAccessToken(respObj.accessToken);
                    backoffCalculator.reset();
                    // 更新下次更新时间（提前60秒）
                    nextRefreshTime = Utils.timestamp() + Integer.parseInt(respObj.expiresIn) - 60;
                    System.out.println("Onedrive token refreshed");
                    // 更新配置文件中的refreshToken
                    if (config != null)
                        config.setConfig(Config.KEYS.CLIENT.ONEDRIVE.REFRESH_TOKEN, respObj.refreshToken);
                    return true;
                } else {
                    System.out.println("OneDrive Token Req Failed: Response body is null");
                }
            } else {
                String errMsg = "Token Req Failed, code:" + response.code() + ", msg:" + response.message();
                setAccessToken("");
                backoffCalculator.backoff();
                setNextRefreshTime(Utils.timestamp() + backoffCalculator.getNextBackoffTime(600));
                if (responseBody != null)
                    errMsg += ", body:" + responseBody.string();
                ConsoleSender.logError(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError("Token Req Failed" + e.getMessage());
            setAccessToken("");
            backoffCalculator.backoff();
            setNextRefreshTime(Utils.timestamp() + backoffCalculator.getNextBackoffTime(600));
        } finally {
            if (responseBody != null)
                responseBody.close();
            client.dispatcher().executorService().shutdown(); // 关闭线程
        }
        return false;
    }


    public void setNextRefreshTime(long nextRefreshTime) {
        this.nextRefreshTime = nextRefreshTime;
    }

    public long getNextRefreshTime() {
        return nextRefreshTime;
    }

    public void setRefreshToken(String token) {
        refreshToken = token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setAccessToken(String token) {
        accessToken = token;
    }

    /**
     * 获得AccessToken（如果没有准备好会阻塞）
     *
     * @return AccessToken
     * @apiNote 请在线程内调用此方法，可能会阻塞
     */
    public String getAccessToken() {
        if (accessToken.equals(""))
            fetch();
        return accessToken;
    }

}

