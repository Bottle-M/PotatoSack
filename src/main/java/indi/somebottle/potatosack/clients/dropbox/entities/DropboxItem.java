package indi.somebottle.potatosack.clients.dropbox.entities;

import com.google.gson.annotations.SerializedName;
import indi.somebottle.potatosack.clients.base.entities.FileItem;

/**
 * Dropbox 文件或文件夹项实体类
 * <p>
 * 表示 Dropbox 中的一个文件或文件夹，实现了 {@link indi.somebottle.potatosack.clients.base.entities.FileItem} 接口。
 * 包含文件/文件夹的基本信息，如名称、大小、路径、类型等。
 * </p>
 * <p>
 * 通过 {@code .tag} 字段区分文件和文件夹：
 * <ul>
 *   <li>{@code "file"} - 文件</li>
 *   <li>{@code "folder"} - 文件夹</li>
 * </ul>
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.base.entities.FileItem
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
 */
public class DropboxItem implements FileItem {
    @SerializedName(".tag")
    private String tag;

    private String name;
    private String id;
    private long size;

    @SerializedName("path_display")
    private String pathDisplay;

    @SerializedName("path_lower")
    private String pathLower;

    @Override
    public boolean isFolder() {
        return "folder".equals(tag);
    }

    /**
     * 获取下载 URL
     * <p>
     * Dropbox 不提供直接下载链接，文件下载需通过 Content API 进行。
     * 因此此方法始终返回空字符串。
     * </p>
     *
     * @return 空字符串
     * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient
     */
    @Override
    public String getDownloadUrl() {
        return "";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return size;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getPathDisplay() {
        return pathDisplay;
    }

    public void setPathDisplay(String pathDisplay) {
        this.pathDisplay = pathDisplay;
    }

    public String getPathLower() {
        return pathLower;
    }

    public void setPathLower(String pathLower) {
        this.pathLower = pathLower;
    }
}
