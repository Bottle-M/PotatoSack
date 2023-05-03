package indi.somebottle.potatosack.onedrive;

import java.util.concurrent.ExecutionException;

public class Test {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        //Client client=new Client("","","");
        TokenFetcher fetcher=new TokenFetcher("","","");
        fetcher.fetch();
        System.out.println("Async test");
    }
}
