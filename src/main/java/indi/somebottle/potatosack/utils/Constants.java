package indi.somebottle.potatosack.utils;

public class Constants {
    public static int MAX_STREAMED_CHUNK_UPLOAD_RETRY = 1; // 流式压缩上传时，每个分片最大上传重试次数
    public static int MAX_SMALL_FILE_SIZE = 1024 * 1024 * 4; // 小文件最大 4 MiB
    public static String PLUGIN_PREFIX = "PotatoSack"; // 插件前缀（用于控制台/聊天信息）
    public static String APP_DATA_FOLDER = "PotatoSack"; // 本插件上传的数据存储在存储服务目录下的哪个目录中
    public static int ZIP_MAX_RETRY_COUNT = 5; // 压缩出问题时最大重试压缩的次数
    public static int FILE_READ_MAX_RETRY = 3; // 读取文件遇到锁定时最大重试次数
    public static long FILE_READ_MAX_BACKOFF_MS = 4000; // 读取文件遇到锁定时单次退避上限（毫秒），封顶后固定间隔重试
    public static int STREAMING_UPLOAD_MIN_WAIT_SECONDS = 10; // 流式上传前最小等待时间（秒）
    public static int STREAMING_UPLOAD_MAX_TOTAL_WAIT_SECONDS = 120; // 流式上传前总等待时间上限（秒）
    // 以下是 OKHttpClient 的超时设置
    public static long OKHTTP_CONNECT_TIMEOUT = 20L; // in seconds
    public static long OKHTTP_WRITE_TIMEOUT = 30L; // in seconds
    public static long OKHTTP_READ_TIMEOUT = 10L; // in seconds
    public static long OKHTTP_CALL_TIMEOUT = 70L; // in seconds
}
