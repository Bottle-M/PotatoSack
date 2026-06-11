package indi.somebottle.potatosack.clients.dropbox;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Utils;

/**
 * Dropbox 令牌自动更新任务
 * <p>
 * 后台定时任务，定期检查访问令牌是否需要刷新。
 * 若当前时间超过 {@link DropboxTokenFetcher#getNextRefreshTime()}，
 * 则调用 {@link DropboxTokenFetcher#fetch()} 刷新令牌。
 * </p>
 * <p>
 * 通常在 {@link DropboxClient} 初始化时创建，每 30 秒运行一次。
 * </p>
 *
 * @see DropboxTokenFetcher
 * @see DropboxClient
 */
public class DropboxTokenUpdater implements Runnable {
    /**
     * 令牌获取器
     */
    private final DropboxTokenFetcher tokenFetcher;

    /**
     * 构造令牌更新任务
     *
     * @param tokenFetcher 令牌获取器
     */
    public DropboxTokenUpdater(DropboxTokenFetcher tokenFetcher) {
        this.tokenFetcher = tokenFetcher;
    }

    @Override
    public void run() {
        if (Utils.timestamp() > tokenFetcher.getNextRefreshTime()) {
            if (!tokenFetcher.fetch()) {
                ConsoleSender.logError("Fatal: Dropbox token refresh failed!");
            }
        }
    }
}
