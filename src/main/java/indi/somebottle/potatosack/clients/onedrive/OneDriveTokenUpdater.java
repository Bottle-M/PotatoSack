package indi.somebottle.potatosack.clients.onedrive;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 更新 OneDrive Token 的异步任务
 */
public class OneDriveTokenUpdater implements Runnable {
    private final OneDriveTokenFetcher oneDriveTokenFetcher;
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    public OneDriveTokenUpdater(OneDriveTokenFetcher oneDriveTokenFetcher) {
        this.oneDriveTokenFetcher = oneDriveTokenFetcher;
    }

    @Override
    public void run() {
        // 检查发现到了下次要更新token的时候了
        if (Utils.timestamp() > oneDriveTokenFetcher.getNextRefreshTime()) {
            // 并发防护：已有在途 fetch 则跳过本次调度
            if (!fetching.compareAndSet(false, true)) {
                return;
            }
            try {
                // 尝试更新token
                if (!oneDriveTokenFetcher.fetch()) {
                    // 更新失败
                    ConsoleSender.logError("Fatal: Token Refresh Failed!");
                }
            } finally {
                fetching.set(false);
            }
        }
    }
}
