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

    /**
     * @apiNote 此方法会造成阻塞
     */
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

        try {
            Response response = client.newCall(postReq).execute();

            if (response.isSuccessful()) {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    String rawResp = responseBody.string();
                    RefreshResp respObj = gson.fromJson(rawResp, RefreshResp.class);
                    setRefreshToken(respObj.refreshToken);
                    setAccessToken(respObj.accessToken);
                    nextRefreshTime = Utils.timeStamp() + Integer.parseInt(respObj.expiresIn) - 60;
                    System.out.println("Token Req Success");
                } else {
                    System.out.println("Token Req Failed: Response body is null");
                }
            } else {
                System.out.println("Token Req Failed");
                setRefreshToken("");
                setAccessToken("");
                ResponseBody errorBody = response.body();
                if (errorBody != null) {
                    ErrorResp respObj = gson.fromJson(errorBody.string(), ErrorResp.class);
                    System.out.println(respObj.errorDesc);
                } else {
                    System.out.println("Token Req Error: Response body is empty");
                }
            }
        } catch (IOException e) {
            System.out.println("Token Req Failed");
            setRefreshToken("");
            setAccessToken("");
            e.printStackTrace();
        } finally {
            client.dispatcher().executorService().shutdown(); // 关闭线程
        }
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

