package indi.somebottle.potatosack.clients.s3.entities;

import indi.somebottle.potatosack.clients.base.entities.FileItem;

import java.time.Instant;

/**
 * S3 项目实体
 * <p>
 * 表示 S3 bucket 中的一个 object（文件）或一个虚拟目录（common prefix）。
 * 由于 S3 没有真正的目录，虚拟目录只由 key prefix 表示，其大小为 0。
 * </p>
 */
public class S3Item implements FileItem {
    /**
     * 当前目录下的 basename，不携带前缀，也不带结尾 {@code /}
     */
    private final String name;

    /**
     * 完整 object key；虚拟目录则为其 prefix（不带结尾 {@code /}）
     */
    private final String key;

    /**
     * 是否为目录（文件为 false，虚拟目录为 true）
     */
    private final boolean folder;

    /**
     * 大小（字节数）；虚拟目录固定为 0
     */
    private final long size;

    /**
     * object 的 ETag，仅用于诊断，可能为 null
     */
    private final String eTag;

    /**
     * 最后修改时间，仅用于诊断，可能为 null
     */
    private final Instant lastModified;

    /**
     * 构造 S3 项目
     *
     * @param name         basename（不带前缀和结尾 {@code /}）
     * @param key          完整 object key 或虚拟目录 prefix
     * @param folder       是否为目录
     * @param size         大小（字节数）
     * @param eTag         ETag，可为 null
     * @param lastModified 最后修改时间，可为 null
     */
    public S3Item(String name, String key, boolean folder, long size, String eTag, Instant lastModified) {
        this.name = name;
        this.key = key;
        this.folder = folder;
        this.size = size;
        this.eTag = eTag;
        this.lastModified = lastModified;
    }

    /**
     * 构造一个文件项
     *
     * @param name         basename
     * @param key          完整 object key
     * @param size         大小（字节数）
     * @param eTag         ETag，可为 null
     * @param lastModified 最后修改时间，可为 null
     * @return 文件类型的 S3Item
     */
    public static S3Item file(String name, String key, long size, String eTag, Instant lastModified) {
        return new S3Item(name, key, false, size, eTag, lastModified);
    }

    /**
     * 构造一个虚拟目录项
     * <p>
     * 使用静态工厂而不是先构造再改字段，可以避免漏设 {@code folder} 标志。
     * </p>
     *
     * @param name   basename（不带结尾 {@code /}）
     * @param prefix 虚拟目录对应的 key prefix（不带结尾 {@code /}）
     * @return 目录类型的 S3Item，大小为 0
     */
    public static S3Item folder(String name, String prefix) {
        return new S3Item(name, prefix, true, 0L, null, null);
    }

    @Override
    public boolean isFolder() {
        return folder;
    }

    /**
     * 获得下载 URL
     * <p>
     * 对象通常是私有的，且本插件的下载通过 {@code sdkClient.getObject} 完成，
     * 因此这里按约定返回空字符串，不把未经签名的不可用 URL 暴露给上层。
     * </p>
     *
     * @return 恒为空字符串
     */
    @Override
    public String getDownloadUrl() {
        return "";
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return size;
    }

    /**
     * 获得完整 object key（或虚拟目录的 prefix）
     *
     * @return key
     */
    public String getKey() {
        return key;
    }

    /**
     * 获得 object 的 ETag
     *
     * @return ETag，虚拟目录或未知时为 null
     */
    public String getETag() {
        return eTag;
    }

    /**
     * 获得最后修改时间
     *
     * @return 最后修改时间，虚拟目录或未知时为 null
     */
    public Instant getLastModified() {
        return lastModified;
    }

    @Override
    public String toString() {
        return "S3Item{name='" + name + "', key='" + key + "', folder=" + folder + ", size=" + size + "}";
    }
}
