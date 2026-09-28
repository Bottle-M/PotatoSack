package indi.somebottle.potatosack.utils;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;
import java.util.Objects;

/**
 * 以现有字节数组的一个切片作为底层数据的请求体
 *
 * <p>前提是这块切片的变动和 OkHttp 操作是同步而不是异步的，
 * 这样一来 OkHttp 在调用方复用该数组之前，就已经写出了这个切片，
 * 这样创建请求时不需要再额外分配和复制切片内容到一个大的字节数组。</p>
 */
public final class ByteArraySliceRequestBody extends RequestBody {
    private final byte[] content;
    private final int offset;
    private final int length;
    private final MediaType contentType;

    /**
     * 以现有字节数组的<b>一个切片</b>作为底层数据的请求体，避免额外分配和复制切片内容到一个大的字节数组
     *
     * @param content 数组 byte[]
     * @param offset 切片的起始位置
     * @param length 切片的长度
     * @param contentType 请求体的内容类型
     */
    public ByteArraySliceRequestBody(byte[] content, int offset, int length, MediaType contentType) {
        Objects.requireNonNull(content, "content");
        Objects.checkFromIndexSize(offset, length, content.length);
        this.content = content;
        this.offset = offset;
        this.length = length;
        this.contentType = contentType;
    }

    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        sink.write(content, offset, length);
    }
}
