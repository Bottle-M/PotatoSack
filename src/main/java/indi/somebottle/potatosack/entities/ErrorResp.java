package indi.somebottle.potatosack.entities;

import com.google.gson.annotations.SerializedName;

/**
 * 请求错误时返回的字段
 */
public class ErrorResp {
    public String error;
    @SerializedName("error_description")
    public String errorDesc;
    @SerializedName("error_codes")
    public String[] errorCodes;
    public String timestamp;
    @SerializedName("error_uri")
    public String errorUri;
}
