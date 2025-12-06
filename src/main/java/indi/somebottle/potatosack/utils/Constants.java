package indi.somebottle.potatosack.utils;

public class Constants {
    public static int MAX_STREAMED_CHUNK_UPLOAD_RETRY = 1; // 流式压缩上传时，每个分片最大上传重试次数
    public static int MAX_SMALL_FILE_SIZE = 1024 * 1024 * 4; // 小文件最大 4 MiB
    public static String PLUGIN_PREFIX = "PotatoSack"; // 插件前缀（用于控制台/聊天信息）
    public static String APP_DATA_FOLDER = "PotatoSack"; // 本插件上传的数据存储在存储服务目录下的哪个目录中
    public static int ZIP_MAX_RETRY_COUNT = 5; // 压缩出问题时最大重试压缩的次数
    // 以下是 OKHttpClient 的超时设置
    public static long OKHTTP_CONNECT_TIMEOUT = 20L; // in seconds
    public static long OKHTTP_WRITE_TIMEOUT = 30L; // in seconds
    public static long OKHTTP_READ_TIMEOUT = 10L; // in seconds
    public static long OKHTTP_CALL_TIMEOUT = 70L; // in seconds
}
