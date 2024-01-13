package indi.somebottle.potatosack.entities.driveitems;

import com.google.gson.annotations.SerializedName;

/**
 * children - value - item
 */
public class Item {
    private String createdDateTime;
    private String id;
    private String lastModifiedDateTime;
    private String name;
    private long size;
    private FolderDetails folder;
    private FileDetails file;
    private String webUrl;
    @SerializedName("@microsoft.graph.downloadUrl")
    private String downloadUrl;

    /**
     * 判断是否是目录
     *
     * @return true表示是目录
     */
    public boolean isFolder() {
        return folder != null;
    }

    @Override
    public String toString() {
        return "Item{" +
                "createdDateTime='" + createdDateTime + '\'' +
                ", id='" + id + '\'' +
                ", lastModifiedDateTime='" + lastModifiedDateTime + '\'' +
                ", name='" + name + '\'' +
                ", size=" + size +
                ", folder=" + folder +
                ", file=" + file +
                ", webUrl='" + webUrl + '\'' +
                ", downloadUrl='" + downloadUrl + '\'' +
                '}';
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getCreatedDateTime() {
        return createdDateTime;
    }

    public void setCreatedDateTime(String createdDateTime) {
        this.createdDateTime = createdDateTime;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLastModifiedDateTime() {
        return lastModifiedDateTime;
    }

    public void setLastModifiedDateTime(String lastModifiedDateTime) {
        this.lastModifiedDateTime = lastModifiedDateTime;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public FolderDetails getFolder() {
        return folder;
    }

    public void setFolder(FolderDetails folder) {
        this.folder = folder;
    }

    public FileDetails getFile() {
        return file;
    }

    public void setFile(FileDetails file) {
        this.file = file;
    }

}
