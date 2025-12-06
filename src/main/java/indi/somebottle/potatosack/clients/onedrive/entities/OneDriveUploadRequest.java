package indi.somebottle.potatosack.clients.onedrive.entities;

import com.google.gson.annotations.SerializedName;

/**
 * 请求上传时需要的字段
 */
public class OneDriveUploadRequest {
    private ItemRequest item;

    public OneDriveUploadRequest(String name) {
        item = new ItemRequest();
        item.setName(name);
        // 同名冲突时重命名文件
        item.setConflictBehavior("rename");
    }

    public OneDriveUploadRequest(String name, String conflictBehavior) {
        item = new ItemRequest();
        item.setName(name);
        item.setConflictBehavior(conflictBehavior);
    }

    public ItemRequest getItem() {
        return item;
    }

    public void setItem(ItemRequest item) {
        this.item = item;
    }

    private static class ItemRequest {
        @SerializedName("@microsoft.graph.conflictBehavior")
        private String conflictBehavior = "rename";
        private String name;

        public String getConflictBehavior() {
            return conflictBehavior;
        }

        public void setConflictBehavior(String conflictBehavior) {
            this.conflictBehavior = conflictBehavior;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
