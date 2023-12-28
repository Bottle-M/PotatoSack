package indi.somebottle.potatosack.onedrive;

import indi.somebottle.potatosack.utils.ConfigOpts;

public class Tester {
    static String[] tokens = ConfigOpts.getTestTokens();
    static TokenFetcher fetcher = new TokenFetcher(tokens[0], tokens[1], tokens[2]);
    public static void main(String[] args) {

    }
}
