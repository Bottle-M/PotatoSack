package indi.somebottle.potatosack.clients.dropbox.entities;

import com.google.gson.annotations.SerializedName;

/**
 * Dropbox OAuth2 令牌刷新响应实体类
 * <p>
 * 表示调用 {@code /oauth2/token} API 刷新令牌的响应。
 * 包含新的访问令牌、过期时间、令牌类型和可选的新刷新令牌。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxTokenFetcher#fetch()
 */
public class DropboxRefreshResp {
    /**
     * 访问令牌
     */
    @SerializedName("access_token")
    public String accessToken;

    /**
     * 令牌有效期（秒）
     */
    @SerializedName("expires_in")
    public long expiresIn;

    /**
     * 令牌类型（通常为 "bearer"）
     */
    @SerializedName("token_type")
    public String tokenType;

    /**
     * 授权范围
     */
    public String scope;

    /**
     * 新的刷新令牌（可选，若 Dropbox 返回则需要更新保存）
     */
    @SerializedName("refresh_token")
    public String refreshToken;
}
