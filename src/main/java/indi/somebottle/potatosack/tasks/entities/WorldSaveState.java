package indi.somebottle.potatosack.tasks.entities;

/**
 * 存储一个世界的保存状态，用于恢复自动保存状态
 */
public class WorldSaveState {

    private final String worldName;
    /**
     * 先前的自动保存状态
     */
    private final boolean previousAutoSaveState;
    /**
     * 目前的自动保存状态
     */
    private final boolean currentAutoSaveState;

    public WorldSaveState(String worldName, boolean previousAutoSaveState, boolean currentAutoSaveState) {
        this.worldName = worldName;
        this.previousAutoSaveState = previousAutoSaveState;
        this.currentAutoSaveState = currentAutoSaveState;
    }


    public String getWorldName() {
        return worldName;
    }

    public boolean isPreviousAutoSaveState() {
        return previousAutoSaveState;
    }

    public boolean isCurrentAutoSaveState() {
        return currentAutoSaveState;
    }
}
