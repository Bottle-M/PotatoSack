package indi.somebottle.potatosack.clients.base.entities;

public interface FileItem {
    /**
     * 判断这一项是否是目录
     *
     * @return 是否是目录
     */
    boolean isFolder();

    /**
     * 获得这一项的下载 URL
     *
     * @return 下载 URL
     */
    String getDownloadUrl();

    /**
     * 获得这一项的名称
     *
     * @return 名称
     */
    String getName();

    /**
     * 获得这一项的大小（字节数）
     *
     * @return 大小（字节数）
     */
    long getSize();
}
