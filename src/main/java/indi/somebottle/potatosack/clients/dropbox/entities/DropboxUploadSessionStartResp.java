package indi.somebottle.potatosack.clients.dropbox.entities;

import com.google.gson.annotations.SerializedName;

/**
 * Dropbox 上传会话启动响应实体类
 * <p>
 * 表示调用 {@code /files/upload_session/start} API 的响应。
 * 包含新创建的上传会话 ID，用于后续的分块上传操作。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient#createUploadSession()
 */
public class DropboxUploadSessionStartResp {
    @SerializedName("session_id")
    private String sessionId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
