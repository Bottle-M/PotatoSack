package indi.somebottle.potatosack.entities.drivechildren;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * me/drive/root/children 返回的字段
 */
public class Resp {
    @SerializedName("value")
    private List<Item> value; // 返回的目录子项
    @SerializedName("@odata.nextLink")
    private String nextLink; // 下一页链接

    public List<Item> getValue() {
        return value;
    }

    public void setValue(List<Item> value) {
        this.value = value;
    }

    public String getNextLink() {
        return nextLink;
    }

    public void setNextLink(String nextLink) {
        this.nextLink = nextLink;
    }
}
