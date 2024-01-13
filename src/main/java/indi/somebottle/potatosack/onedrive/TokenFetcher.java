package indi.somebottle.potatosack.onedrive;


import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.RefreshResp;
import indi.somebottle.potatosack.utils.*;
import okhttp3.*;

import java.io.IOException;

public class TokenFetcher {
    private final String endPoint = Constants.MS_TOKEN_ENDPOINT; // Microsoft Token更新终结点
    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
    private long nextRefreshTime = 0;
    private String accessToken;
    private final Gson gson = new Gson();
    private final Config config;

    public TokenFetcher(String clientId, String clientSecret, String refreshToken, Config config) {
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
                .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
                .build();
        RequestBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_secret", clientScrt)
                .build();
        Request postReq = new Request.Builder()
                .url(endPoint)
                .post(body)
                .build();
        ResponseBody responseBody = null;
        try {
            Response response = client.newCall(postReq).execute();
            if (response.isSuccessful()) {
                responseBody = response.body();
                if (responseBody != null) {
                    String rawResp = responseBody.string();
                    RefreshResp respObj = gson.fromJson(rawResp, RefreshResp.class);
                    setRefreshToken(respObj.refreshToken);
                    setAccessToken(respObj.accessToken);
                    // 更新下次更新时间（提前60秒）
                    nextRefreshTime = Utils.timeStamp() + Integer.parseInt(respObj.expiresIn) - 60;
                    System.out.println("Token Req Success");
                    // 更新配置文件中的refreshToken
                    if (config != null)
                        config.setConfig("onedrive.refresh-token", respObj.refreshToken);
                    return true;
                } else {
                    System.out.println("Token Req Failed: Response body is null");
                }
            } else {
                String errMsg = "Token Req Failed, code:" + response.code() + ", msg:" + response.message();
                setRefreshToken("");
                setAccessToken("");
                responseBody = response.body();
                if (responseBody != null)
                    errMsg += ", body:" + responseBody.string();
                Utils.logError(errMsg);
            }
        } catch (IOException e) {
            Utils.logError("Token Req Failed" + e.getMessage());
            setRefreshToken("");
            setAccessToken("");
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

