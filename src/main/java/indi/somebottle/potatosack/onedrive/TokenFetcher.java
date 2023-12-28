package indi.somebottle.potatosack.onedrive;


import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

import indi.somebottle.potatosack.utils.Constants;

public class TokenFetcher {
    private final String endPoint = Constants.MS_TOKEN_ENDPOINT; // Microsoft Token更新终结点

    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
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
                .build();
        Request postReq = new Request.Builder()
                .url(endPoint)
                .post(body)
                .build();
        Call call = client.newCall(postReq);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {

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


                } else {
                    System.out.println("Req Failed");
                    ErrorResp respObj = gson.fromJson(rawResp, ErrorResp.class);
                    System.out.println(respObj.errorDesc);
                }
                client.dispatcher().executorService().shutdown(); // 关闭线程
            }
        });

    }

    public void setRefreshToken(String token) {
        refreshToken = token;
    }
}

/**
 * 申请新AccessToken的返回字段
 */
class RefreshResp {
    @SerializedName("access_token")
    public String accessToken;
    @SerializedName("token_type")
    public String tokenType;
    @SerializedName("expires_in")
    public String expiresIn;
    public String scope;
    @SerializedName("refresh_token")
    public String refreshToken;
}

/**
 * 请求错误时返回的字段
 */
class ErrorResp {
    public String error;
    @SerializedName("error_description")
    public String errorDesc;
    @SerializedName("error_codes")
    public String[] errorCodes;
    public String timestamp;
    @SerializedName("error_uri")
    public String errorUri;
}