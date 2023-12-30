package indi.somebottle.potatosack.onedrive;


import com.google.gson.Gson;
import indi.somebottle.potatosack.entities.ErrorResp;
import indi.somebottle.potatosack.entities.RefreshResp;
import indi.somebottle.potatosack.utils.Utils;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import indi.somebottle.potatosack.utils.Constants;

public class TokenFetcher {
    private final String endPoint = Constants.MS_TOKEN_ENDPOINT; // Microsoft Token更新终结点
    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
    private long nextRefreshTime = 0;
    private String accessToken;
    private final Gson gson = new Gson();

    public TokenFetcher(String clientId, String clientSecret, String refreshToken) {
        this.clientId = clientId;
        this.clientScrt = clientSecret;
        this.refreshToken = refreshToken;
        this.accessToken = "";
    }

    public void fetch() {
        OkHttpClient client = new OkHttpClient();
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
        Call call = client.newCall(postReq);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                System.out.println("Req Failed");
                setRefreshToken("");
                setAccessToken("");
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                // 判断是否请求成功
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    System.out.println("Req Failed");
                    return;
                }
                String rawResp = responseBody.string();
                System.out.println(rawResp);
                if (response.isSuccessful()) { // 请求成功执行
                    RefreshResp respObj = gson.fromJson(rawResp, RefreshResp.class);
                    // 更新RefreshToken和AccessToken
                    setRefreshToken(respObj.refreshToken);
                    setAccessToken(respObj.accessToken);
                    // 计算下次刷新AccessToken和RefreshToken的时间（提前60秒）
                    nextRefreshTime = Utils.timeStamp() + Integer.parseInt(respObj.expiresIn) - 60;
                    System.out.println("Req Success");
                } else {
                    System.out.println("Req Failed");
                    setRefreshToken("");
                    setAccessToken("");
                    ErrorResp respObj = gson.fromJson(rawResp, ErrorResp.class);
                    System.out.println(respObj.errorDesc);
                }
                client.dispatcher().executorService().shutdown(); // 关闭线程
            }
        });

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

    public String getAccessToken() {
        return accessToken;
    }

}

