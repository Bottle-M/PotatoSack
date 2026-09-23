package indi.somebottle.potatosack.clients.s3;

import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S3 multipart upload 共用实现
 * <p>
 * 本地大文件上传器（{@link S3FileUploader}）和流式 ZIP 上传器（{@link S3StreamedZipUploader}）
 * 共用本类，以保证分片大小、part number 规则、重试、ETag 收集和 abort 清理逻辑完全一致，
 * 不会随着时间推移出现两套分叉的行为。
 * </p>
 * <p>
 * 分片规则：
 * <ul>
 *   <li>part number 从 1 开始连续递增；</li>
 *   <li>part 数量上限为 S3 的 10000；</li>
 *   <li>除最后一个 part 外，每个 part 不得小于 5 MiB，因此分片大小取 16 MiB；</li>
 *   <li>上传成功后按 part number 顺序收集 ETag，供 CompleteMultipartUpload 使用。</li>
 * </ul>
 * </p>
 * <p>
 * 重试分两层：SDK 自身的标准重试策略负责单次请求的瞬时错误；
 * 应用层额外保留 {@link Constants#MAX_STREAMED_CHUNK_UPLOAD_RETRY} 次完整重试，
 * 重试时使用相同的 part number 和相同的字节范围，因此缓冲区在该 part 成功前不会被复用。
 * </p>
 * <p>
 * 注意：uploadPart(int, byte[], int) 只对 IO 类失败进行重试，
 * part 数量超过上限属于确定性错误，不会重试而是直接失败。
 * </p>
 */
public class S3MultipartUploader {
    /**
     * S3 单个 part 的最小大小（非最后一个 part），5 MiB
     */
    public static final long S3_MIN_PART_SIZE = 5L * 1024 * 1024;

    /**
     * S3 multipart upload 的 part 数量上限
     */
    public static final int S3_MAX_PART_COUNT = 10000;

    /**
     * 分片大小，16 MiB
     */
    public static final int PART_SIZE = 16 * 1024 * 1024;

    /**
     * 应用层 part 上传重试的等待时间（毫秒）
     * <p>
     * 可通过系统属性 {@code potatosack.s3.part-retry-wait-ms} 覆盖，默认 10 秒。
     * 该属性主要用于单元测试（设为 0 可避免测试等待），正常运行时无需设置。
     * </p>
     */
    private static volatile long partRetryWaitMs = resolvePartRetryWaitMs();

    /**
     * 读取 part 重试等待时间，非法值回退到默认 10 秒
     *
     * @return 等待毫秒数
     */
    private static long resolvePartRetryWaitMs() {
        String configured = System.getProperty("potatosack.s3.part-retry-wait-ms");
        if (configured != null) {
            try {
                long value = Long.parseLong(configured.trim());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 忽略非法配置，使用默认值
            }
        }
        return 10000L;
    }

    /**
     * 设置 part 重试等待时间（供测试使用）
     *
     * @param waitMs 等待毫秒数
     */
    static void setPartRetryWaitMs(long waitMs) {
        partRetryWaitMs = waitMs;
    }

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
     * multipart upload 的 uploadId
     */
    private final String uploadId;

    /**
     * 已成功上传的 part，按 part number 升序
     */
    private final List<CompletedPart> completedParts = new ArrayList<>();

    /**
     * 是否已经进入终态（已完成或已 abort），用于确保 complete / abort 只发生一次
     */
    private boolean terminated = false;

    /**
     * 本次 multipart upload 已上传的总字节数
     */
    private long uploadedBytes = 0;

    /**
     * 发起一次 multipart upload
     *
     * @param sdkClient 底层 SDK 客户端
     * @param bucket    目标 bucket
     * @param key       目标 object key
     * @param partCount 预计的 part 数量，未知时传 {@code -1}
     * @throws IOException             发起失败时抛出
     * @throws TooManyPartsException   预计的 part 数量超过 {@link #S3_MAX_PART_COUNT} 时抛出
     */
    public S3MultipartUploader(software.amazon.awssdk.services.s3.S3Client sdkClient,
                               String bucket, String key, long partCount) throws IOException, TooManyPartsException {
        this.sdkClient = sdkClient;
        this.bucket = bucket;
        this.key = key;
        checkPartCount(partCount);
        // S3 multipart upload 不要求在发起时知道最终对象大小，因此这里不传 contentLength
        CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/octet-stream")
                .build();
        CreateMultipartUploadResponse response = sdkClient.createMultipartUpload(request);
        this.uploadId = response.uploadId();
        ConsoleSender.toConsole("S3 multipart upload started. Key: " + key + ", uploadId: " + uploadId);
    }

    /**
     * 校验预计的 part 数量是否超过 S3 上限
     *
     * @param partCount 预计的 part 数量，未知时传 {@code -1}
     * @throws TooManyPartsException 超过上限时抛出
     */
    private static void checkPartCount(long partCount) throws TooManyPartsException {
        if (partCount > S3_MAX_PART_COUNT) {
            throw new TooManyPartsException("File is split into " + partCount + " parts, which exceeds the S3 limit of "
                    + S3_MAX_PART_COUNT + " parts. Please increase the part size or split the file.");
        }
    }

    /**
     * 校验某个 part number 是否超过 S3 上限
     *
     * @param partNumber part number（从 1 开始）
     * @throws TooManyPartsException 超过上限时抛出
     */
    private static void checkPartNumber(int partNumber) throws TooManyPartsException {
        if (partNumber > S3_MAX_PART_COUNT) {
            throw new TooManyPartsException("Part number " + partNumber + " exceeds the S3 limit of "
                    + S3_MAX_PART_COUNT + " parts. Please increase the part size or split the file.");
        }
    }

    /**
     * 上传一个 part，并在失败时进行有限次数的应用层重试
     * <p>
     * 通过 {@code bodySupplier} 为每一次尝试（含应用层重试与 SDK 自身重试）构造全新的输入流，
     * 保证请求体可以被完整重放，同时避免把整个 part 复制到堆内存中。
     * 重试始终使用相同的 part number 和相同的字节范围，成功后只记录一个 ETag。
     * </p>
     *
     * @param partNumber   part number，从 1 开始连续递增
     * @param length       本次上传的字节数，必须大于 0
     * @param bodySupplier 每次尝试时构造请求体输入流的工厂
     * @throws IOException           所有重试都失败时抛出
     * @throws TooManyPartsException part number 超过 S3 上限时抛出（确定性错误，不重试）
     */
    public void uploadPart(int partNumber, long length, PartBodySupplier bodySupplier)
            throws IOException, TooManyPartsException {
        if (length <= 0) {
            return;
        }
        checkPartNumber(partNumber);
        IOException lastError = null;
        for (int retry = 0; retry <= Constants.MAX_STREAMED_CHUNK_UPLOAD_RETRY; retry++) {
            try {
                UploadPartRequest request = UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(length)
                        .build();
                UploadPartResponse response = sdkClient.uploadPart(request,
                        RequestBody.fromContentProvider(bodySupplier, length, "application/octet-stream"));
                String eTag = response.eTag();
                completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(eTag).build());
                uploadedBytes += length;
                ConsoleSender.toConsole("S3 upload part " + partNumber + " succeeded. Chunk: " + length
                        + " byte(s), uploaded total: " + uploadedBytes + " byte(s)");
                return;
            } catch (Exception e) {
                lastError = new IOException("Failed to upload part " + partNumber + " of " + key + ": " + e.getMessage(), e);
                if (retry < Constants.MAX_STREAMED_CHUNK_UPLOAD_RETRY) {
                    ConsoleSender.logWarn("S3 part " + partNumber + " upload failed (" + e.getMessage()
                            + "), retrying with the same byte range...(" + (retry + 1) + "/"
                            + Constants.MAX_STREAMED_CHUNK_UPLOAD_RETRY + ")");
                    waitBeforePartRetry();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Interrupted while retrying part " + partNumber + " of " + key, lastError);
                    }
                }
            }
        }
        throw lastError == null ? new IOException("Failed to upload part " + partNumber + " of " + key) : lastError;
    }

    /**
     * 完成 multipart upload
     *
     * @throws IOException 完成请求失败，或上传过程中没有产生任何 part 时抛出
     */
    public void completeUpload() throws IOException {
        if (terminated) {
            return;
        }
        if (completedParts.isEmpty()) {
            terminated = true;
            abortQuietly();
            throw new IOException("No part was uploaded for " + key + ", multipart upload aborted.");
        }
        // 按 part number 升序提交，SDK 要求 parts 有序
        List<CompletedPart> sortedParts = new ArrayList<>(completedParts);
        sortedParts.sort((a, b) -> a.partNumber().compareTo(b.partNumber()));
        try {
            CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(sortedParts).build())
                    .build();
            CompleteMultipartUploadResponse response = sdkClient.completeMultipartUpload(request);
            terminated = true;
            ConsoleSender.toConsole("S3 multipart upload completed. Key: " + key + ", parts: " + sortedParts.size()
                    + ", total: " + uploadedBytes + " byte(s), location: " + response.location());
        } catch (Exception e) {
            // 完成请求失败：先释放 multipart 状态，再抛出原始错误
            terminated = true;
            abortQuietly();
            throw new IOException("Failed to complete multipart upload of " + key + ": " + e.getMessage(), e);
        }
    }

    /**
     * 放弃本次 multipart upload
     * <p>
     * 允许重复调用；abort 自身的失败只记录警告，不会覆盖调用方的原始异常。
     * </p>
     */
    public void abort() {
        if (terminated) {
            return;
        }
        terminated = true;
        abortQuietly();
    }

    /**
     * 实际发起 abort 请求，忽略并记录失败
     */
    private void abortQuietly() {
        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build();
            sdkClient.abortMultipartUpload(request);
            ConsoleSender.logWarn("S3 multipart upload aborted. Key: " + key + ", uploadId: " + uploadId);
        } catch (Exception e) {
            // abort 失败不能覆盖原始异常，只记录
            ConsoleSender.logWarn("Failed to abort S3 multipart upload. Key: " + key + ", uploadId: " + uploadId
                    + ", reason: " + e.getMessage());
        }
    }

    /**
     * 获得已成功上传的 part 数量
     *
     * @return part 数量
     */
    public int getUploadedPartCount() {
        return completedParts.size();
    }

    /**
     * 获得已上传的总字节数
     *
     * @return 已上传字节数
     */
    public long getUploadedBytes() {
        return uploadedBytes;
    }

    /**
     * 获得已完成 part 的只读视图，供测试检查 ETag 收集结果
     *
     * @return 已完成的 part 列表（按 part number 升序）
     */
    public List<CompletedPart> getCompletedParts() {
        List<CompletedPart> parts = new ArrayList<>(completedParts);
        parts.sort((a, b) -> a.partNumber().compareTo(b.partNumber()));
        return Collections.unmodifiableList(parts);
    }

    /**
     * 判断本次 multipart upload 是否已经进入终态
     *
     * @return 已完成或已 abort 时返回 true
     */
    public boolean isTerminated() {
        return terminated;
    }

    /**
     * 获得本次 multipart upload 的 uploadId
     *
     * @return uploadId
     */
    public String getUploadId() {
        return uploadId;
    }

    /**
     * part 重试前的等待
     */
    private void waitBeforePartRetry() {
        long waitMs = partRetryWaitMs;
        if (waitMs <= 0) {
            return;
        }
        try {
            ConsoleSender.toConsole("Failed to upload S3 part, retrying in " + (waitMs / 1000) + " seconds...");
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ConsoleSender.logWarn("Interrupted while waiting before an S3 part retry.");
        }
    }

    /**
     * part 数量超过 S3 上限时抛出的异常
     * <p>
     * 属于确定性错误，调用方不应进行重试。
     * </p>
     */
    public static class TooManyPartsException extends Exception {
        public TooManyPartsException(String message) {
            super(message);
        }
    }

    /**
     * part 请求体输入流工厂
     * <p>
     * SDK 在应用层重试和内部重试时都可能重新读取请求体，因此每次都要返回一个全新的输入流，
     * 且要能从相同的起始位置重新读取相同的字节范围。
     * </p>
     */
    @FunctionalInterface
    public interface PartBodySupplier extends software.amazon.awssdk.http.ContentStreamProvider {
    }
}
