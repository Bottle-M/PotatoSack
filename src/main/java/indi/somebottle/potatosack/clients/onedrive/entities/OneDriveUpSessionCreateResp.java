package indi.somebottle.potatosack.clients.onedrive.entities;

/**
 * createUploadSession 请求后返回的结果字段
 */
public class OneDriveUpSessionCreateResp {
    private String uploadUrl;
    private String expirationDateTime;

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public String getExpirationDateTime() {
        return expirationDateTime;
    }

    public void setExpirationDateTime(String expirationDateTime) {
        this.expirationDateTime = expirationDateTime;
    }
}
