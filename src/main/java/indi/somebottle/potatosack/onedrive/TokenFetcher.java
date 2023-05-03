package indi.somebottle.potatosack.onedrive;


import com.google.gson.Gson;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.apache.logging.log4j.message.Message;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class TokenFetcher {
    private final String endPoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private final String clientId;
    private final String clientScrt;
    private String refreshToken;
    private String accessToken;
    private final Gson gson=new Gson();

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
                if (response.isSuccessful()) { // 请求成功执行
                    response.body().string();
                } else {
                    System.out.println("Req Failed");
                    System.out.println(response.body().string());

                    refreshToken = "hello world";
                }
                client.dispatcher().executorService().shutdown(); // 关闭线程
            }
        });

    }

    public void setRefreshToken(String token) {
        refreshToken = token;
    }
}
