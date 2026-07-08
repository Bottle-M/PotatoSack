package indi.somebottle.potatosack.exceptions;

import java.io.IOException;

/**
 * 上传文件大小超出约定大小时的异常
 */
public class DataSizeOverflowException extends IOException {
    private final long overflowSize;

    /**
     * 初始化 DataSizeOverflowException
     *
     * @param msg          异常信息
     * @param overflowSize 实际上传大小相较模拟压缩计算的大小溢出的字节数
     */
    public DataSizeOverflowException(String msg, long overflowSize) {
        super(msg);
        // 实际上传大小相较模拟压缩计算的大小溢出了多少字节
        this.overflowSize = overflowSize;
    }

    public long getOverflowSize() {
        return overflowSize;
    }
}