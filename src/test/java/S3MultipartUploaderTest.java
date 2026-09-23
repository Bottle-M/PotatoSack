import indi.somebottle.potatosack.clients.s3.S3FileUploader;
import indi.somebottle.potatosack.clients.s3.S3MultipartUploader;
import org.junit.Test;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * multipart 纯逻辑单元测试
 * <p>
 * 不访问网络：用一个记录调用的假 SDK 客户端（JDK 动态代理）替代真实的
 * {@code software.amazon.awssdk.services.s3.S3Client}，覆盖 part number 规则、
 * ETag 收集、最后小 part、10,000 part 上限与 abort 语义。
 * </p>
 */
public class S3MultipartUploaderTest {
    /**
     * 假的 SDK 客户端，记录调用并可按需注入失败
     */
    private static class FakeS3 {
        final List<Integer> uploadedPartNumbers = new ArrayList<>();
        final List<Long> uploadedPartLengths = new ArrayList<>();
        final List<String> abortedUploadIds = new ArrayList<>();
        final List<List<CompletedPart>> submittedParts = new ArrayList<>();
        final List<Integer> readBodyLengths = new ArrayList<>();
        int failNextUploadParts = 0;
        boolean failComplete = false;
        boolean failAbort = false;
        int createdUploads = 0;

        final InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createMultipartUpload": {
                    CreateMultipartUploadRequest request = (CreateMultipartUploadRequest) args[0];
                    createdUploads++;
                    return CreateMultipartUploadResponse.builder()
                            .bucket(request.bucket())
                            .key(request.key())
                            .uploadId("upload-" + createdUploads)
                            .build();
                }
                case "uploadPart": {
                    UploadPartRequest request = (UploadPartRequest) args[0];
                    // 像真实 SDK 一样消费请求体（失败前也要读，模拟请求已发出后服务端返回错误），
                    // 用于验证 PartBodySupplier 每次都能提供可重放的请求体
                    software.amazon.awssdk.core.sync.RequestBody requestBody =
                            (software.amazon.awssdk.core.sync.RequestBody) args[1];
                    try (InputStream bodyStream = requestBody.contentStreamProvider().newStream()) {
                        readBodyLengths.add(bodyStream.readAllBytes().length);
                    }
                    if (failNextUploadParts > 0) {
                        failNextUploadParts--;
                        throw new RuntimeException("simulated upload part failure");
                    }
                    uploadedPartNumbers.add(request.partNumber());
                    uploadedPartLengths.add(request.contentLength());
                    return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
                }
                case "completeMultipartUpload": {
                    if (failComplete) {
                        throw new RuntimeException("simulated complete failure");
                    }
                    CompleteMultipartUploadRequest request = (CompleteMultipartUploadRequest) args[0];
                    submittedParts.add(new ArrayList<>(request.multipartUpload().parts()));
                    return CompleteMultipartUploadResponse.builder().location("http://example.com/object").build();
                }
                case "abortMultipartUpload": {
                    AbortMultipartUploadRequest request = (AbortMultipartUploadRequest) args[0];
                    abortedUploadIds.add(request.uploadId());
                    if (failAbort) {
                        throw new RuntimeException("simulated abort failure");
                    }
                    return AbortMultipartUploadResponse.builder().build();
                }
                case "close":
                    return null;
                default:
                    throw new UnsupportedOperationException("unexpected SDK call: " + method.getName());
            }
        };

        software.amazon.awssdk.services.s3.S3Client client() {
            return (software.amazon.awssdk.services.s3.S3Client) Proxy.newProxyInstance(
                    S3MultipartUploaderTest.class.getClassLoader(),
                    new Class<?>[]{software.amazon.awssdk.services.s3.S3Client.class},
                    handler);
        }
    }

    /**
     * 构造一个已发起 multipart upload 的上传器
     *
     * @param fake      假客户端
     * @param partCount 预计 part 数量，-1 表示未知
     * @return 上传器，uploadId 为 "upload-1"
     */
    private static S3MultipartUploader newUploader(FakeS3 fake, long partCount) throws Exception {
        // 构造函数会调用 createMultipartUpload，由假客户端处理
        return new S3MultipartUploader(fake.client(), "test-bucket", "PotatoSack/target.zip", partCount);
    }

    private static S3MultipartUploader newUploader(FakeS3 fake) throws Exception {
        return newUploader(fake, -1);
    }

    /**
     * 每次尝试都返回全新输入流，模拟可重放的请求体
     */
    private static S3MultipartUploader.PartBodySupplier body(byte[] data, int length) {
        return () -> new ByteArrayInputStream(data, 0, length);
    }

    // ==================== part 数量上限 ====================

    @Test
    public void testPartCountExceedingLimitIsRejectedBeforeUpload() {
        FakeS3 fake = new FakeS3();
        try {
            newUploader(fake, 10001L);
            fail("预计 10001 个 part 时应当提前失败");
        } catch (Exception e) {
            assertTrue("应抛出 TooManyPartsException，实际为 " + e.getClass(),
                    e instanceof S3MultipartUploader.TooManyPartsException);
            assertTrue(e.getMessage().contains("10000"));
        }
        // 必须在发起 multipart upload 之前就失败，不能留下未完成 upload
        assertEquals(0, fake.createdUploads);
    }

    @Test
    public void testPartCountExactlyAtLimitIsAccepted() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake, 10000L);
        assertNotNull(uploader);
        assertEquals(1, fake.createdUploads);
    }

    @Test
    public void testPartCountIsDerivedFromFileSize() {
        long partSize = S3MultipartUploader.PART_SIZE;
        assertEquals(0L, S3FileUploader.partCountOf(0L));
        assertEquals(1L, S3FileUploader.partCountOf(1L));
        assertEquals(1L, S3FileUploader.partCountOf(partSize));
        assertEquals(2L, S3FileUploader.partCountOf(partSize + 1));
        assertEquals(2L, S3FileUploader.partCountOf(partSize * 2));
        assertEquals(3L, S3FileUploader.partCountOf(partSize * 2 + 1));
        // 10,000 个 part 恰好覆盖 10000 * 16 MiB
        assertEquals(10000L, S3FileUploader.partCountOf(partSize * 10000L));
        assertEquals(10001L, S3FileUploader.partCountOf(partSize * 10000L + 1));
        // 分片必须大于 S3 对非最后 part 的 5 MiB 下限
        assertTrue(S3MultipartUploader.PART_SIZE > S3MultipartUploader.S3_MIN_PART_SIZE);
    }

    @Test
    public void testPartNumberBeyondLimitIsRejected() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[4];
        try {
            uploader.uploadPart(10001, buffer.length, body(buffer, buffer.length));
            fail("part number 10001 应当被拒绝");
        } catch (S3MultipartUploader.TooManyPartsException e) {
            assertTrue(e.getMessage().contains("10001"));
        }
        assertEquals(0, fake.uploadedPartNumbers.size());
    }

    // ==================== part number 与 ETag ====================

    @Test
    public void testPartNumbersStartAtOneAndIncreaseByOne() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[8];
        for (int i = 1; i <= 3; i++) {
            uploader.uploadPart(i, buffer.length, body(buffer, buffer.length));
        }
        assertEquals(Arrays.asList(1, 2, 3), fake.uploadedPartNumbers);
    }

    @Test
    public void testETagsAreCollectedPerPartNumber() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[8];
        uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
        uploader.uploadPart(2, buffer.length, body(buffer, buffer.length));
        uploader.completeUpload();
        // 不再暴露 uploader 内部 completedParts；验证真正提交给 S3 的完成请求。
        List<CompletedPart> parts = fake.submittedParts.get(0);
        assertEquals(2, parts.size());
        assertEquals(Integer.valueOf(1), parts.get(0).partNumber());
        assertEquals("etag-1", parts.get(0).eTag());
        assertEquals(Integer.valueOf(2), parts.get(1).partNumber());
        assertEquals("etag-2", parts.get(1).eTag());
        assertEquals(2, uploader.getUploadedPartCount());
        assertEquals(16L, uploader.getUploadedBytes());
    }

    @Test
    public void testSmallLastPartIsAllowed() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] full = new byte[S3MultipartUploader.PART_SIZE];
        byte[] small = new byte[1024];
        uploader.uploadPart(1, full.length, body(full, full.length));
        // 最后一个 part 只有 1 KiB，远小于 5 MiB 的下限，但必须允许
        uploader.uploadPart(2, small.length, body(small, small.length));
        uploader.completeUpload();
        assertEquals(Arrays.asList((long) S3MultipartUploader.PART_SIZE, 1024L), fake.uploadedPartLengths);
        // complete 后已进入终态；再次 abort 不应发送请求。
        uploader.abort();
        assertTrue(fake.abortedUploadIds.isEmpty());
    }

    @Test
    public void testZeroLengthPartIsIgnored() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        uploader.uploadPart(1, 0, body(new byte[0], 0));
        assertEquals(0, uploader.getUploadedPartCount());
        assertEquals(0, fake.uploadedPartNumbers.size());
    }

    @Test
    public void testUploadedPartsAreSubmittedInAscendingOrder() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[4];
        // 故意乱序上传
        uploader.uploadPart(3, buffer.length, body(buffer, buffer.length));
        uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
        uploader.uploadPart(2, buffer.length, body(buffer, buffer.length));
        uploader.completeUpload();
        List<CompletedPart> submitted = fake.submittedParts.get(0);
        assertEquals(Arrays.asList(1, 2, 3), submitted.stream().map(CompletedPart::partNumber).toList());
    }

    // ==================== 重试 ====================

    @Test
    public void testUploadPartDoesNotApplicationRetryFailure() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[16];
        // Fake SDK 绕过 AWS SDK 自己的 retry；uploader 层不应再次调用 uploadPart。
        fake.failNextUploadParts = 1;
        try {
            uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
            fail("part 上传失败时应当抛出 IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to upload part 1"));
        }
        assertEquals(1, fake.readBodyLengths.size());
        assertTrue(fake.uploadedPartNumbers.isEmpty());
        assertEquals(0, uploader.getUploadedPartCount());
        assertEquals(0L, uploader.getUploadedBytes());
    }

    @Test
    public void testPartBodySupplierCanReplaySameRange() throws Exception {
        byte[] buffer = new byte[16];
        final int[] supplierCalls = {0};
        S3MultipartUploader.PartBodySupplier supplier = () -> {
            supplierCalls[0]++;
            return new ByteArrayInputStream(buffer);
        };

        // AWS SDK retry 时会再次向 ContentStreamProvider 请求新流。
        try (InputStream first = supplier.newStream();
             InputStream second = supplier.newStream()) {
            assertEquals(16, first.readAllBytes().length);
            assertEquals(16, second.readAllBytes().length);
        }
        assertEquals(2, supplierCalls[0]);
    }

    // ==================== abort 语义 ====================

    @Test
    public void testCompleteFailureTriggersAbort() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[4];
        uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
        fake.failComplete = true;
        try {
            uploader.completeUpload();
            fail("complete 失败时应当抛出异常");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to complete multipart upload"));
        }
        // complete 失败后必须 abort，不能遗留未完成的 multipart upload
        assertEquals(Arrays.asList("upload-1"), fake.abortedUploadIds);
        // complete 失败后也已进入终态；再次 abort 不应发送第二次请求。
        uploader.abort();
        assertEquals(1, fake.abortedUploadIds.size());
    }

    @Test
    public void testAbortFailureDoesNotOverrideOriginalException() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[4];
        uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
        fake.failComplete = true;
        fake.failAbort = true;
        try {
            uploader.completeUpload();
            fail("complete 失败时应当抛出异常");
        } catch (IOException e) {
            // abort 自身失败只记录警告，不能覆盖原始异常
            assertTrue(e.getMessage().contains("Failed to complete multipart upload"));
            assertNotNull(e.getCause());
        }
        assertEquals(1, fake.abortedUploadIds.size());
    }

    @Test
    public void testCompleteWithoutAnyPartAborts() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        try {
            uploader.completeUpload();
            fail("没有任何 part 时 complete 应当失败");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("No part was uploaded"));
        }
        assertEquals(1, fake.abortedUploadIds.size());
    }

    @Test
    public void testAbortIsIdempotent() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        uploader.abort();
        uploader.abort();
        // 重复 abort 只应发一次请求
        assertEquals(1, fake.abortedUploadIds.size());
    }

    @Test
    public void testCompleteAfterAbortIsNoOp() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        uploader.abort();
        uploader.completeUpload();
        assertTrue(fake.submittedParts.isEmpty());
    }

    @Test
    public void testSuccessfulCompleteDoesNotAbort() throws Exception {
        FakeS3 fake = new FakeS3();
        S3MultipartUploader uploader = newUploader(fake);
        byte[] buffer = new byte[4];
        uploader.uploadPart(1, buffer.length, body(buffer, buffer.length));
        uploader.completeUpload();
        // 成功完成后不应再调用 abort
        assertTrue(fake.abortedUploadIds.isEmpty());
        // 完成后再次 abort 也不应有请求
        uploader.abort();
        assertTrue(fake.abortedUploadIds.isEmpty());
    }

    // ==================== 本地文件上传器的数据范围 ====================

    @Test
    public void testFileUploaderUploadsFullFileThroughMultipart() throws Exception {
        File tempFile = File.createTempFile("potatosack-s3-test", ".bin");
        tempFile.deleteOnExit();
        long fileSize = S3MultipartUploader.PART_SIZE + 7L;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempFile, "rw")) {
            raf.setLength(fileSize);
        }
        FakeS3 fake = new FakeS3();
        S3FileUploader uploader = new S3FileUploader(fake.client(), "test-bucket", "key", tempFile);
        assertTrue(uploader.upload());
        // 两个 part：16 MiB + 7 字节
        assertEquals(Arrays.asList((long) S3MultipartUploader.PART_SIZE, 7L), fake.uploadedPartLengths);
        assertEquals(Arrays.asList(1, 2), fake.uploadedPartNumbers);
        assertEquals(1, fake.submittedParts.size());
        assertTrue(fake.abortedUploadIds.isEmpty());
        assertEquals(fileSize, tempFile.length());
    }

    @Test
    public void testFileUploaderAbortsWhenPartUploadFails() throws Exception {
        File tempFile = File.createTempFile("potatosack-s3-test", ".bin");
        tempFile.deleteOnExit();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempFile, "rw")) {
            raf.setLength(64L);
        }
        FakeS3 fake = new FakeS3();
        // 模拟 part 上传最终失败；请求级 retry 由真实 AWS SDK 负责，uploader 收到失败后必须 abort
        fake.failNextUploadParts = 100;
        S3FileUploader uploader = new S3FileUploader(fake.client(), "test-bucket", "key", tempFile);
        assertFalse(uploader.upload());
        assertEquals(1, fake.abortedUploadIds.size());
    }

    @Test
    public void testFileUploaderRejectsTooManyPartsBeforeCreatingUpload() throws Exception {
        // 不实际创建 160 GB 文件（部分文件系统不支持稀疏文件）：直接验证
        // 由文件大小推出的 part 数量超过上限时，S3MultipartUploader 会在发起
        // multipart upload 之前就拒绝，从而不会留下未完成的 upload
        long fileSize = (long) S3MultipartUploader.PART_SIZE * (S3MultipartUploader.S3_MAX_PART_COUNT + 1L);
        long partCount = S3FileUploader.partCountOf(fileSize);
        assertEquals(S3MultipartUploader.S3_MAX_PART_COUNT + 1L, partCount);
        FakeS3 fake = new FakeS3();
        try {
            new S3MultipartUploader(fake.client(), "test-bucket", "key", partCount);
            fail("part 数量超过上限时应当提前失败");
        } catch (S3MultipartUploader.TooManyPartsException e) {
            assertTrue(e.getMessage().contains(String.valueOf(S3MultipartUploader.S3_MAX_PART_COUNT)));
        }
        assertEquals(0, fake.createdUploads);
        assertTrue(fake.abortedUploadIds.isEmpty());
    }

    /**
     * 保证 PartBodySupplier 与 ContentStreamProvider 的兼容性
     */
    @Test
    public void testPartBodySupplierIsAContentStreamProvider() throws Exception {
        S3MultipartUploader.PartBodySupplier supplier = () -> new ByteArrayInputStream(new byte[]{1, 2, 3});
        InputStream stream = ((software.amazon.awssdk.http.ContentStreamProvider) supplier).newStream();
        assertEquals(3, stream.readAllBytes().length);
    }
}
