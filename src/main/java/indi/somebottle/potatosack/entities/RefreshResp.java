package indi.somebottle.potatosack.entities;

import com.google.gson.annotations.SerializedName;

/**
 * 申请新AccessToken的返回字段
 */
public class RefreshResp {
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
