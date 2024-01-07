package indi.somebottle.potatosack.utils;

public class Constants {
    public static String MS_TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    public static String MS_GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    public static String OD_API_ROOT_PATH = "/drive/special/approot"; // OneDrive API请求中的根目录
    // private final String OD_API_ROOT_PATH = "/me/drive/root";
    public static int CHUNK_SIZE = 1024 * 320 * 50; // 15.625MiB一块（320KiB的整数倍）
    public static int MAX_SMALL_FILE_SIZE = 1024 * 1024 * 4; // 小文件最大4MiB
    public static String PLUGIN_PREFIX = "PotatoSack"; // 插件前缀（用于控制台/聊天信息）
    public static String OD_APP_DATA_FOLDER = "PotatoSack"; // 本插件上传的数据存储在OneDrive目录下的哪个目录中

}
