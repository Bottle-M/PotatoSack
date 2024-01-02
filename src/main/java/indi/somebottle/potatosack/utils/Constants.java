package indi.somebottle.potatosack.utils;

public class Constants {
    public static String MS_TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    public static String MS_GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    public static String ROOT_PATH = "/drive/special/approot";
    // private final String ROOT_PATH = "/me/drive/root";
    public static int CHUNK_SIZE = 1024 * 320 * 50; // 15.625MiB一块（320KiB的整数倍）
    public static String PLUGIN_PREFIX = "PotatoSack";

}
