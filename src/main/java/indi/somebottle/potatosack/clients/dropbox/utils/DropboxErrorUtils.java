package indi.somebottle.potatosack.clients.dropbox.utils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import indi.somebottle.potatosack.clients.dropbox.entities.DropboxApiError;

/**
 * Dropbox API 错误解析工具类
 * <p>
 * 提供解析 Dropbox API 错误响应的功能。
 * 主要用于从错误响应中提取特定信息，如上传偏移量不匹配错误中的正确偏移量。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.dropbox.entities.DropboxApiError
 * @see indi.somebottle.potatosack.clients.dropbox.DropboxClient#appendUploadSession(String, long, byte[], int, int)
 */
public class DropboxErrorUtils {
    private static final Gson gson = new Gson();

    private DropboxErrorUtils() {
    }

    /**
     * 解析 Dropbox API 错误响应
     *
     * @param body 响应体 JSON 字符串
     * @return 解析后的错误对象，若解析失败则返回 {@code null}
     */
    public static DropboxApiError parseApiError(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return gson.fromJson(body, DropboxApiError.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * 从错误响应中获取正确的上传偏移量
     * <p>
     * 用于处理上传会话偏移量不匹配错误（409）。
     * 从错误响应中提取服务器记录的正确偏移量，以便客户端调整续传位置。
     * </p>
     *
     * @param body 响应体 JSON 字符串
     * @return 正确的偏移量，若无法获取则返回 {@code null}
     */
    public static Long getCorrectOffset(String body) {
        DropboxApiError apiError = parseApiError(body);
        if (apiError == null) {
            return null;
        }
        return apiError.getCorrectOffset();
    }
}
