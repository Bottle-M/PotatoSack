package indi.somebottle.potatosack.entities.drivechildren;

/**
 * /drives/{drive-id}/items/{item-id}/children - value - item中的file字段
 */
public class FileDetails {
    private String mimeType;

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

}
