package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.onedrive.TokenFetcher;
import indi.somebottle.potatosack.utils.Utils;

public class TokenChecker implements Runnable {
    private final TokenFetcher tokenFetcher;

    public TokenChecker(TokenFetcher tokenFetcher) {
        this.tokenFetcher = tokenFetcher;
    }

    @Override
    public void run() {
        // 检查发现到了下次要更新token的时候了
        if (Utils.timeStamp() > tokenFetcher.getNextRefreshTime()) {
            // 尝试更新token
            if (!tokenFetcher.refresh(3)) {
                // 更新失败
                Utils.logError("Fatal: Token Refresh Failed!");
            }
        }
    }
}
