package indi.somebottle.potatosack.clients.onedrive.entities;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * children 返回的字段
 */
public class OneDriveChildrenResp {
    @SerializedName("value")
    private List<OneDriveItem> value; // 返回的目录子项
    @SerializedName("@odata.nextLink")
    private String nextLink; // 下一页链接

    public List<OneDriveItem> getValue() {
        return value;
    }

    public void setValue(List<OneDriveItem> value) {
        this.value = value;
    }

    public String getNextLink() {
        return nextLink;
    }

    public void setNextLink(String nextLink) {
        this.nextLink = nextLink;
    }
}
