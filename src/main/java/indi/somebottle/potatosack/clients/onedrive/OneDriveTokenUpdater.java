package indi.somebottle.potatosack.clients.onedrive;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;

/**
 * 更新 OneDrive Token 的异步任务
 */
public class OneDriveTokenUpdater implements Runnable {
    private final OneDriveTokenFetcher oneDriveTokenFetcher;

    public OneDriveTokenUpdater(OneDriveTokenFetcher oneDriveTokenFetcher) {
        this.oneDriveTokenFetcher = oneDriveTokenFetcher;
    }

    @Override
    public void run() {
        // 检查发现到了下次要更新token的时候了
        if (Utils.timestamp() > oneDriveTokenFetcher.getNextRefreshTime()) {
            // 尝试更新token
            if (!oneDriveTokenFetcher.fetch()) {
                // 更新失败
                ConsoleSender.logError("Fatal: Token Refresh Failed!");
            }
        }
    }
}
