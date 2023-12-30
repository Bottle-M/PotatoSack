package indi.somebottle.potatosack.onedrive;

import indi.somebottle.potatosack.entities.drivechildren.Resp;

public class Client {
    private final TokenFetcher fetcher;

    public Client(TokenFetcher fetcher) {
        this.fetcher = fetcher;
    }

}
