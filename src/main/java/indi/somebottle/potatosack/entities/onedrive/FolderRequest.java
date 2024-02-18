package indi.somebottle.potatosack.entities.onedrive;

import com.google.gson.annotations.SerializedName;

/**
 * children - 请求建立目录
 */
public class FolderRequest {
    private String name;
    private FolderDetails folder;
    @SerializedName("@microsoft.graph.conflictBehavior")
    private String behavior;

    public FolderRequest(String name) {
        this.name = name;
        this.folder = new FolderDetails();
        this.behavior = "rename";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FolderDetails getFolder() {
        return folder;
    }

    public void setFolder(FolderDetails folder) {
        this.folder = folder;
    }

    public String getBehavior() {
        return behavior;
    }

    public void setBehavior(String behavior) {
        this.behavior = behavior;
    }
}
