package indi.somebottle.potatosack.utils;

/**
 * 防止多个备份任务同时出现的互斥锁（可重入）
 */
public class BackupMutex {
    private Thread heldBy = null; // 记录被哪个线程所持有
    private int lockCount = 0; // 记录被同一线程持有的次数

    /**
     * （非阻塞） 尝试获得锁
     *
     * @return 是否获得锁
     */
    public synchronized boolean occupy() {
        Thread currThread = Thread.currentThread();
        // 如果此锁对象未被占用或者已被当前线程占用，返回 true
        if (heldBy == null || heldBy == currThread) {
            heldBy = currThread;
            // 重入，增加计数
            lockCount++;
            return true;
        }
        return false;
    }

    /**
     * 释放锁，只有持有者能释放锁
     */
    public synchronized void release() {
        // 如果是被当前线程持有，则释放
        if (heldBy == Thread.currentThread()) {
            lockCount--;
            if (lockCount == 0) {
                heldBy = null;
            }
        }
    }
}
