package indi.somebottle.potatosack.entities.drivechildren;

/**
 * /drives/{drive-id}/items/{item-id}/children - value - item中的folder字段
 */
public class FolderDetails {

    private long childCount;

    public long getChildCount() {
        return childCount;
    }

    public void setChildCount(long childCount) {
        this.childCount = childCount;
    }
}
