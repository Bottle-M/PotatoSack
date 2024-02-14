package indi.somebottle.potatosack.utils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 防止多个备份任务同时出现
 */
public class BackupMutex {
    private final AtomicBoolean onGoing = new AtomicBoolean(false);

    public boolean isOnGoing() {
        return onGoing.get();
    }

    public void setOnGoing(boolean onGoing) {
        this.onGoing.set(onGoing);
    }
}
