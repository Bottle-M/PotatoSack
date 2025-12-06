package indi.somebottle.potatosack.clients.onedrive.entities;

import com.google.gson.annotations.SerializedName;

/**
 * children - 请求建立目录
 */
public class OneDriveFolderRequest {
    private String name;
    private OneDriveFolderDetails folder;
    @SerializedName("@microsoft.graph.conflictBehavior")
    private String behavior;

    public OneDriveFolderRequest(String name) {
        this.name = name;
        this.folder = new OneDriveFolderDetails();
        this.behavior = "rename";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OneDriveFolderDetails getFolder() {
        return folder;
    }

    public void setFolder(OneDriveFolderDetails folder) {
        this.folder = folder;
    }

    public String getBehavior() {
        return behavior;
    }

    public void setBehavior(String behavior) {
        this.behavior = behavior;
    }
}
