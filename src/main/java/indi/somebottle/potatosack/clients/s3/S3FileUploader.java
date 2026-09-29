package indi.somebottle.potatosack.clients.s3;

import indi.somebottle.potatosack.utils.ConsoleSender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * S3 本地文件上传器
 * <p>
 * 将本地文件通过 multipart upload 分片上传到 S3。适用于超过
 * {@link indi.somebottle.potatosack.utils.Constants#MAX_SMALL_FILE_SIZE} 的文件。
 * </p>
 * <p>
 * 上传流程：
 * <ol>
 *   <li>按固定分片大小计算 part 数量，超过 S3 的 10000 上限时提前失败；</li>
 *   <li>发起 multipart upload；</li>
 *   <li>顺序读取本地文件，每个 part 使用固定大小缓冲区，part number 从 1 开始递增；</li>
 *   <li>所有 part 成功后完成 multipart upload；</li>
 *   <li>任何异常都会 abort，不留下未完成的 multipart upload。</li>
 * </ol>
 * </p>
 * <p>
 * 分片大小为 {@link S3MultipartUploader#PART_SIZE}（32 MiB），大于 S3 对非最后一个 part
 * 的 5 MiB 最小限制；最后一个 part 允许小于该值。
 * </p>
 *
 * @see S3MultipartUploader
 */
public class S3FileUploader {
    /**
     * 底层 SDK 客户端
     */
    private final software.amazon.awssdk.services.s3.S3Client sdkClient;

    /**
     * 目标 bucket
     */
    private final String bucket;

    /**
     * 目标 object key
     */
    private final String key;

    /**
     * 本地文件
     */
    private final File localFile;

    /**
     * 文件大小（字节）
     */
    private final long fileSize;

    /**
     * 构造本地文件上传器
     *
     * @param sdkClient  底层 SDK 客户端
     * @param bucket     目标 bucket
     * @param key        目标 object key
     * @param localFile  本地文件
     */
    public S3FileUploader(software.amazon.awssdk.services.s3.S3Client sdkClient,
                          String bucket, String key, File localFile) {
        this.sdkClient = sdkClient;
        this.bucket = bucket;
        this.key = key;
        this.localFile = localFile;
        this.fileSize = localFile.length();
        ConsoleSender.toConsole("S3 file upload task: " + localFile.getName() + " (key: " + key + ")");
    }

    /**
     * 计算给定文件长度需要的 part 数量
     *
     * @param fileSize 文件大小（字节）
     * @return part 数量，空文件返回 0
     */
    public static long partCountOf(long fileSize) {
        if (fileSize <= 0) {
            return 0;
        }
        return fileSize / S3MultipartUploader.PART_SIZE
                + (fileSize % S3MultipartUploader.PART_SIZE == 0 ? 0 : 1);
    }

    /**
     * 执行上传
     * <p>
     * 每个 part 通过有界文件输入流直接从磁盘流式读取，不在内存中缓存整块分片数据，
     * 也不预读整个文件。
     * </p>
     *
     * @return {@code true} 表示上传成功，{@code false} 表示失败
     */
    public boolean upload() {
        S3MultipartUploader uploader = null;
        try {
            long partCount = partCountOf(fileSize);
            // 超过 S3 的 part 数量上限时会直接失败，不发起 multipart upload
            uploader = new S3MultipartUploader(sdkClient, bucket, key, partCount);
            long offset = 0;
            int partNumber = 1;
            while (offset < fileSize) {
                final long partOffset = offset;
                final long length = Math.min(S3MultipartUploader.PART_SIZE, fileSize - partOffset);
                uploader.uploadPart(partNumber, length, () -> new BoundedFileInputStream(localFile, partOffset, length));
                offset += length;
                partNumber++;
            }
            uploader.completeUpload();
            ConsoleSender.toConsole("S3 file upload success! Key: " + key + ", size: " + fileSize
                    + " byte(s), parts: " + uploader.getUploadedPartCount());
            return true;
        } catch (S3MultipartUploader.TooManyPartsException e) {
            ConsoleSender.logError("S3 file upload rejected: " + e.getMessage());
            return false;
        } catch (IOException e) {
            ConsoleSender.logError("S3 file upload failed: " + e.getMessage());
            return false;
        } finally {
            // 未成功完成时确保释放 multipart 状态
            if (uploader != null) {
                uploader.abort();
            }
        }
    }

    /**
     * 有界文件输入流
     * <p>
     * 从本地文件的指定偏移开始，最多读取指定长度。每次调用 {@link #create()} 都会打开一个新的
     * {@link RandomAccessFile}，因此 AWS SDK 内部 retry 时请求体可以从相同范围完整重放。
     * </p>
     */
    private static class BoundedFileInputStream extends BoundedInputStream {
        private final File file;
        private final long startOffset;

        BoundedFileInputStream(File file, long startOffset, long length) {
            super(length);
            this.file = file;
            this.startOffset = startOffset;
        }

        @Override
        protected InputStream create() throws IOException {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.seek(startOffset);
                return new RandomAccessFileInputStream(raf);
            } catch (IOException e) {
                raf.close();
                throw e;
            }
        }
    }

    /**
     * 基于 {@link RandomAccessFile} 的只读输入流
     */
    private static class RandomAccessFileInputStream extends InputStream {
        private final RandomAccessFile raf;

        RandomAccessFileInputStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public int read() throws IOException {
            return raf.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return raf.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    /**
     * 有界输入流基类
     * <p>
     * 子类实现 {@link #create()} 提供底层流，本类保证最多只向上游暴露 {@code limit} 个字节，
     * 避免 SDK 把 part 之外的数据一并上传。
     * </p>
     */
    private abstract static class BoundedInputStream extends InputStream {
        private final long limit;
        private InputStream delegate;
        private long remaining;

        BoundedInputStream(long limit) {
            this.limit = limit;
            this.remaining = limit;
        }

        /**
         * 创建底层输入流，每次调用都应返回一个新的、位于正确起始位置的流
         *
         * @return 底层输入流
         * @throws IOException 创建失败时抛出
         */
        protected abstract InputStream create() throws IOException;

        private InputStream delegate() throws IOException {
            if (delegate == null) {
                delegate = create();
            }
            return delegate;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int result = delegate().read();
            if (result >= 0) {
                remaining--;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min((long) len, remaining);
            int read = delegate().read(b, off, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            if (remaining <= 0) {
                return 0L;
            }
            long toSkip = Math.min(n, remaining);
            long skipped = delegate().skip(toSkip);
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min((long) delegate().available(), remaining);
        }

        @Override
        public void close() throws IOException {
            if (delegate != null) {
                delegate.close();
                delegate = null;
            }
            remaining = 0;
        }

        @Override
        public String toString() {
            return "BoundedInputStream{limit=" + limit + "}";
        }
    }
}
