package indi.somebottle.potatosack.entities.driveitems;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * children 返回的字段
 */
public class ChildrenResp {
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
