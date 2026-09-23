package indi.somebottle.potatosack.clients.s3.utils;

/**
 * S3 object key 路径工具类
 * <p>
 * S3 是对象存储，key 中的 {@code /} 只是一个普通字符，服务端并不存在真正的目录 inode。
 * 因此本类统一负责所有 key 的规范化工作，避免各个方法各自拼接路径。
 * </p>
 * <p>
 * 规范化规则：
 * <ol>
 *   <li>去除首尾空白，将反斜杠 {@code \} 替换为正斜杠 {@code /}；</li>
 *   <li>合并重复的分隔符，去除首尾的 {@code /}；</li>
 *   <li>空字符串代表 bucket 根目录；</li>
 *   <li>拒绝包含 {@code ..} 路径段的输入，防止用户配置把操作范围带出 base-dir；</li>
 *   <li>不进行任何 URL 编码，AWS SDK 接受原始 key，编码由 SDK 负责。</li>
 * </ol>
 * </p>
 * <p>
 * 目录 prefix 结尾的 {@code /} 只在发起 prefix 查询时通过 {@link #toDirPrefix(String)} 添加，
 * 普通 object key 不会额外带上结尾 {@code /}。
 * </p>
 */
public class S3PathUtils {
    private S3PathUtils() {
    }

    /**
     * 把任意路径规范化为合法的 S3 object key
     * <p>
     * 反斜杠会被转换为正斜杠，重复斜杠会被合并，首尾斜杠会被去除。
     * </p>
     *
     * @param path 原始路径，可为 null
     * @return 规范化后的 key，若输入为空或只有斜杠则返回空字符串（代表 bucket 根）
     * @throws IllegalArgumentException 输入包含 {@code ..} 路径段时抛出
     */
    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        // 合并重复的分隔符
        normalized = normalized.replaceAll("/{2,}", "/");
        // 去除首尾的分隔符
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // 拒绝 ".." 路径段，防止越出 base-dir 指定的操作范围
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path must not contain '..' segments: " + path);
            }
        }
        return normalized;
    }

    /**
     * 把规范化后的 key 转换为目录 prefix（仅在非空时补上结尾 {@code /}）
     *
     * @param normalizedKey 已经过 {@link #normalize(String)} 处理的 key
     * @return 目录 prefix；若 key 为空则返回空字符串（bucket 根）
     */
    public static String toDirPrefix(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return "";
        }
        return normalizedKey + "/";
    }

    /**
     * 校验一个 key 能否安全地作为操作目标
     * <p>
     * 要求规范化后非空，否则调用方可能把操作范围扩大整个 bucket。
     * </p>
     *
     * @param path 原始路径
     * @return 规范化后的 key
     * @throws IllegalArgumentException 规范化后为空（即指向 bucket 根）时抛出
     */
    public static String requireNonRoot(String path) {
        String normalized = normalize(path);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Path must not be empty or point to the bucket root: " + path);
        }
        return normalized;
    }
}
