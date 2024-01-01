package indi.somebottle.potatosack.entities.driveitems;

import com.google.gson.annotations.SerializedName;

/**
 * 请求上传时需要的字段
 */
public class UploadRequest {
    private ItemRequest item;

    public UploadRequest(String name) {
        item = new ItemRequest();
        item.setName(name);
        item.setConflictBehavior("rename");
    }

    public UploadRequest(String name, String conflictBehavior) {
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
