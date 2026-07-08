package indi.somebottle.potatosack.clients.dropbox.utils;

/**
 * Dropbox 路径工具类
 * <p>
 * 提供路径格式转换功能，将本地路径格式转换为 Dropbox API 要求的路径格式。
 * </p>
 * <p>
 * Dropbox 路径规范：
 * <ul>
 *   <li>必须以 {@code /} 开头（根目录为空字符串 {@code ""}）</li>
 *   <li>不能以 {@code /} 结尾</li>
 *   <li>使用正斜杠 {@code /} 作为路径分隔符</li>
 * </ul>
 * </p>
 */
public class DropboxPathUtils {
    private DropboxPathUtils() {
    }

    /**
     * 将路径转换为 Dropbox API 格式
     * <p>
     * 转换规则：
     * <ol>
     *   <li>去除首尾空格</li>
     *   <li>将反斜杠 {@code \} 替换为正斜杠 {@code /}</li>
     *   <li>去除开头的所有 {@code /}</li>
     *   <li>去除结尾的所有 {@code /}</li>
     *   <li>若结果为空，返回空字符串；否则在开头添加 {@code /}</li>
     * </ol>
     * </p>
     * <p>
     * 示例：
     * <ul>
     *   <li>{@code "folder/file.txt"} → {@code "/folder/file.txt"}</li>
     *   <li>{@code "/folder/"} → {@code "/folder"}</li>
     *   <li>{@code ""} 或 {@code "/"} → {@code ""}</li>
     * </ul>
     * </p>
     *
     * @param path 原始路径
     * @return Dropbox API 格式的路径
     */
    public static String toDropboxPath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        return "/" + normalized;
    }
}
