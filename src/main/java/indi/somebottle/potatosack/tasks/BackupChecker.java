package indi.somebottle.potatosack.tasks;

import indi.somebottle.potatosack.onedrive.Client;
import indi.somebottle.potatosack.onedrive.TokenFetcher;

public class BackupChecker implements Runnable {
    private final TokenFetcher tokenFetcher;
    private final Client odClient;

    public BackupChecker(TokenFetcher tokenFetcher) {
        this.tokenFetcher = tokenFetcher;
        this.odClient = new Client(tokenFetcher); // 初始化OneDrive客户端
    }

    @Override
    public void run() {


    }
}
