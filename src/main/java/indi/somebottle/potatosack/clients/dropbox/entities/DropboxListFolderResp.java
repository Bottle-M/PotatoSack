package indi.somebottle.potatosack.clients.dropbox.entities;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Dropbox 列出文件夹内容响应实体类
 * <p>
 * 表示调用 {@code /files/list_folder} 和 {@code /files/list_folder/continue} API 的响应。
 * 包含文件夹中的项目列表、游标和分页信息。
 * </p>
 * <p>
 * 若 {@code hasMore} 为 {@code true}，表示还有更多项目，
 * 需要使用 {@code cursor} 调用 {@code /files/list_folder/continue} 继续获取。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public class DropboxListFolderResp {
    /**
     * 文件夹中的项目列表
     */
    private List<DropboxItem> entries;

    /**
     * 游标，用于分页获取后续项目
     */
    private String cursor;

    /**
     * 是否还有更多项目
     */
    @SerializedName("has_more")
    private boolean hasMore;

    public List<DropboxItem> getEntries() {
        return entries;
    }

    public void setEntries(List<DropboxItem> entries) {
        this.entries = entries;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
