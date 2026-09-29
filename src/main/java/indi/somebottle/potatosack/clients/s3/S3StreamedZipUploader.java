package indi.somebottle.potatosack.clients.s3;

import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipOutputStream;

/**
 * S3 流式压缩上传器
 * <p>
 * 边压缩边上传，无需先生成本地临时压缩文件，也无需预先计算压缩后的总长度：
 * S3 multipart upload 在 {@code CreateMultipartUpload} 时并不要求知道最终对象大小，
 * 这是它与 OneDrive 上传会话实现的关键差异，因此这里不做 OneDrive 那种
 * “先压缩一遍算总长度”的额外遍历。
 * </p>
 * <p>
 * 工作原理：
 * <ol>
 *   <li>发起 multipart upload，得到 uploadId；</li>
 *   <li>创建 {@link UploadOutputStream}，内部只有一个 {@link S3MultipartUploader#PART_SIZE} 大小的缓冲区；</li>
 *   <li>用 {@link ZipOutputStream} 包裹该输出流，调用 {@link Utils#zipSpecificFilesUtil}；</li>
 *   <li>缓冲区写满时同步上传一个 part，成功后清空缓冲区继续；</li>
 *   <li>ZIP 正常关闭后上传最后一个非空 part（允许小于 5 MiB）；</li>
 *   <li>CompleteMultipartUpload 成功后才返回 true。</li>
 * </ol>
 * </p>
 * <p>
 * 压缩冲突、网络错误、线程中断或 close 过程异常都会 abort，不会遗留未完成的 multipart upload。
 * 只有源文件读写冲突会触发整份 ZIP 重做；每次重做都会新建 uploadId。网络请求级 retry 由 AWS SDK 负责。
 * </p>
 *
 * @see S3MultipartUploader
 */
public class S3StreamedZipUploader {
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
     * 构造流式压缩上传器
     *
     * @param sdkClient 底层 SDK 客户端
     * @param bucket    目标 bucket
     * @param key       目标 object key
     */
    public S3StreamedZipUploader(software.amazon.awssdk.services.s3.S3Client sdkClient, String bucket, String key) {
        this.sdkClient = sdkClient;
        this.bucket = bucket;
        this.key = key;
    }

    /**
     * 压缩并上传指定文件
     *
     * @param entries 要打包进 zip 的条目
     * @param quiet   是否静默打包（不显示 Adding... 信息）
     * @return {@code true} 表示压缩上传成功，{@code false} 表示失败
     */
    public boolean zipSpecifiedAndUpload(ZipEntryInfo[] entries, boolean quiet) {
        ConsoleSender.toConsole("Compressing and uploading to S3... Key: " + key);
        for (int zipRetryCnt = 0; zipRetryCnt <= Constants.ZIP_MAX_RETRY_COUNT; zipRetryCnt++) {
            // 每次整体重试都必须使用新的 uploadId，不能复用已经失败或已 abort 的 upload
            S3MultipartUploader uploader = null;
            UploadOutputStream uploadStream = null;
            try {
                uploader = new S3MultipartUploader(sdkClient, bucket, key, -1);
                uploadStream = new UploadOutputStream(uploader);
                try (UploadOutputStream stream = uploadStream;
                     ZipOutputStream zout = new ZipOutputStream(stream)) {
                    try {
                        Utils.zipSpecificFilesUtil(zout, entries, quiet);
                    } catch (Utils.ZipRWConflictException e) {
                        // 冲突后禁止 close() 时 flush 最后一块；multipart 由 finally abort。
                        stream.terminate();
                        ConsoleSender.logWarn(e.getMessage());
                        throw e;
                    }
                }
                // ZIP 已正常关闭，此时最后一个 part 已经上传完毕
                uploader.completeUpload();
                ConsoleSender.toConsole("S3 compression / upload success. Key: " + key + ", total size: "
                        + uploader.getUploadedBytes() + " Byte(s), parts: " + uploader.getUploadedPartCount());
                return true;
            } catch (Utils.ZipRWConflictException e) {
                if (zipRetryCnt < Constants.ZIP_MAX_RETRY_COUNT) {
                    ConsoleSender.toConsole("Retrying to compress and upload the files anew...("
                            + (zipRetryCnt + 1) + "/" + Constants.ZIP_MAX_RETRY_COUNT + ")");
                    continue;
                }
                ConsoleSender.logError("S3 compression / upload gave up after a read-write conflict: " + e.getMessage());
                return false;
            } catch (S3MultipartUploader.TooManyPartsException e) {
                // 容量上限是确定性错误；重新压缩相同数据不会改变结果。
                ConsoleSender.logError("S3 compression / upload rejected: " + e.getMessage());
                return false;
            } catch (IOException e) {
                // OutputStream 只能抛 IOException；若根因是 part 数量超限，仍按确定性容量错误处理。
                if (e.getCause() instanceof S3MultipartUploader.TooManyPartsException) {
                    ConsoleSender.logError("S3 compression / upload rejected: " + e.getCause().getMessage());
                    return false;
                }
                // 请求级瞬时网络错误由 AWS SDK 自身重试；这里不重做整份 ZIP。
                ConsoleSender.logError("S3 compression / upload failed: " + e.getMessage());
                e.printStackTrace();
                return false;
            } finally {
                // 未成功完成时确保释放 multipart 状态
                if (uploader != null) {
                    uploader.abort();
                }
                if (uploadStream != null) {
                    uploadStream.releaseBuffer();
                }
            }
        }
        return false;
    }

    /**
     * 自定义输出流，把 ZIP 压缩数据按 part 转发到 S3 multipart upload
     * <p>
     * 内部只维护一个 {@link S3MultipartUploader#PART_SIZE} 大小的缓冲区，
     * 写满即上传一个 part，因此内存占用是常量级的。
     * </p>
     * <p>
     * close 语义：{@link ZipOutputStream#close()} 会先 flush 再调用本流的 {@code close()}，
     * 此时才上传最后一个非空 part 并完成 multipart upload。
     * 若 ZIP 打包过程中失败，调用方应改为调用 {@link #terminate()}，
     * 这样 close 会退化为“只释放缓冲区”，交由上传器的 abort 清理。
     * </p>
     */
    private static class UploadOutputStream extends OutputStream {
        /**
         * 对应的 multipart 上传器
         */
        private final S3MultipartUploader uploader;

        /**
         * 单个 part 的缓冲区
         */
        private byte[] buffer = new byte[S3MultipartUploader.PART_SIZE];

        /**
         * 缓冲区中当前已写入的字节数
         */
        private int writePos = 0;

        /**
         * 下一个待上传的 part number，从 1 开始
         */
        private int nextPartNumber = 1;

        /**
         * 流是否已进入终态。terminate 与正常 close 都会置为 true，后续写入/关闭均为空操作
         */
        private boolean closed = false;

        UploadOutputStream(S3MultipartUploader uploader) {
            this.uploader = uploader;
        }

        /**
         * 把缓冲区中已有的数据上传为一个 part，成功后清空缓冲区
         * <p>
         * 缓冲区在 part 成功前不会被复用：每次尝试都用相同的字节范围重新构造输入流。
         * </p>
         */
        private void flushBufferAsPart() throws IOException, S3MultipartUploader.TooManyPartsException {
            if (writePos <= 0 || buffer == null) {
                return;
            }
            final byte[] partBuffer = buffer;
            final int length = writePos;
            ConsoleSender.toConsole("Compressing + uploading S3 part " + nextPartNumber + ": " + length
                    + " byte(s) (uploaded so far: " + uploader.getUploadedBytes() + " byte(s))");
            uploader.uploadPart(nextPartNumber, length, () -> new ByteArrayInputStream(partBuffer, 0, length));
            nextPartNumber++;
            writePos = 0;
            ConsoleSender.toConsole(" --> S3 part successfully uploaded. Total uploaded: "
                    + uploader.getUploadedBytes() + " byte(s)");
        }

        /**
         * 标记本流作废，后续 close 不会完成 multipart upload
         */
        void terminate() {
            closed = true;
            buffer = null;
        }

        /**
         * 释放缓冲区引用，便于 GC 回收
         */
        void releaseBuffer() {
            buffer = null;
        }

        @Override
        public void write(int b) throws IOException {
            if (closed || buffer == null) {
                return;
            }
            if (writePos >= buffer.length) {
                uploadBufferOrFail();
            }
            if (buffer == null) {
                return;
            }
            buffer[writePos++] = (byte) b;
            if (writePos == buffer.length) {
                uploadBufferOrFail();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed || buffer == null || len <= 0) {
                return;
            }
            int remaining = len;
            int pos = off;
            while (remaining > 0) {
                if (writePos >= buffer.length) {
                    uploadBufferOrFail();
                    if (buffer == null) {
                        return;
                    }
                }
                int copyLen = Math.min(remaining, buffer.length - writePos);
                System.arraycopy(b, pos, buffer, writePos, copyLen);
                writePos += copyLen;
                pos += copyLen;
                remaining -= copyLen;
                if (writePos == buffer.length) {
                    uploadBufferOrFail();
                    if (buffer == null) {
                        return;
                    }
                }
            }
        }

        /**
         * 上传缓冲区，失败时把本流标记为作废并向上抛出，避免 close 时再次上传已失败的数据
         */
        private void uploadBufferOrFail() throws IOException {
            try {
                flushBufferAsPart();
            } catch (S3MultipartUploader.TooManyPartsException e) {
                // part 数量超限属于确定性错误，标记作废并把原因包装为 IOException
                terminate();
                throw new IOException(e.getMessage(), e);
            } catch (IOException e) {
                terminate();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                // 上传最后一个非空 part，即使它小于 5 MiB 也允许（ZIP 可能整体不到一个 part）
                flushBufferAsPart();
            } catch (S3MultipartUploader.TooManyPartsException e) {
                // close() 只能抛出 IOException，这里包装后向上传播
                terminate();
                throw new IOException(e.getMessage(), e);
            } finally {
                buffer = null;
            }
        }
    }
}
