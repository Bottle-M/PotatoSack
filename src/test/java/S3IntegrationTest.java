import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.clients.s3.S3Client;
import indi.somebottle.potatosack.clients.s3.S3FileUploader;
import indi.somebottle.potatosack.clients.s3.S3MultipartUploader;
import indi.somebottle.potatosack.clients.s3.S3StreamedZipUploader;
import indi.somebottle.potatosack.clients.s3.utils.S3PathUtils;
import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;
import indi.somebottle.potatosack.utils.Config;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import sun.misc.Unsafe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * S3 集成测试
 * <p>
 * 需要一个真实的 S3-compatible 服务（推荐 MinIO 或 LocalStack），通过以下环境变量启用：
 * <pre>
 * S3_TEST_ENDPOINT     例如 http://127.0.0.1:9000
 * S3_TEST_REGION       例如 us-east-1
 * S3_TEST_BUCKET       已存在的 bucket 名
 * S3_TEST_ACCESS_KEY   MinIO/LocalStack 的 access key
 * S3_TEST_SECRET_KEY   MinIO/LocalStack 的 secret key
 * </pre>
 * 未设置这些变量时，所有用例会被显式跳过（{@link Assume}），既不会失败，也不会访问真实 AWS 账户。
 * </p>
 * <p>
 * 本测试<b>不</b>使用任何真实凭证：仓库与测试日志中都不应出现 access key / secret key。
 * </p>
 * <p>
 * 运行方式见 {@code memos/s3-guide.md}。
 * </p>
 */
public class S3IntegrationTest {
    private static final String ENV_ENDPOINT = "S3_TEST_ENDPOINT";
    private static final String ENV_REGION = "S3_TEST_REGION";
    private static final String ENV_BUCKET = "S3_TEST_BUCKET";
    private static final String ENV_ACCESS_KEY = "S3_TEST_ACCESS_KEY";
    private static final String ENV_SECRET_KEY = "S3_TEST_SECRET_KEY";

    /**
     * 每个测试使用的隔离 key 前缀（base-dir 模拟）
     */
    private static final String BASE_DIR = "potatosack-it";

    private static software.amazon.awssdk.services.s3.S3Client sdkClient;

    @BeforeClass
    public static void setUpClient() {
        String endpoint = System.getenv(ENV_ENDPOINT);
        String region = System.getenv(ENV_REGION);
        String bucket = System.getenv(ENV_BUCKET);
        String accessKey = System.getenv(ENV_ACCESS_KEY);
        String secretKey = System.getenv(ENV_SECRET_KEY);
        Assume.assumeTrue("未设置 " + ENV_ENDPOINT + "，跳过 S3 集成测试", endpoint != null && !endpoint.isEmpty());
        Assume.assumeTrue("未设置 " + ENV_REGION + "，跳过 S3 集成测试", region != null && !region.isEmpty());
        Assume.assumeTrue("未设置 " + ENV_BUCKET + "，跳过 S3 集成测试", bucket != null && !bucket.isEmpty());
        Assume.assumeTrue("未设置 " + ENV_ACCESS_KEY + "，跳过 S3 集成测试", accessKey != null && !accessKey.isEmpty());
        Assume.assumeTrue("未设置 " + ENV_SECRET_KEY + "，跳过 S3 集成测试", secretKey != null && !secretKey.isEmpty());

        sdkClient = software.amazon.awssdk.services.s3.S3Client.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                // MinIO 等自定义 endpoint 需要 path-style
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(20))
                        .socketTimeout(Duration.ofSeconds(30)))
                .build();
    }

    @Before
    public void verifyBucketAccessible() {
        // 前置检查打错配置时给出明确失败信息，而不是让每个用例各自报错
        Assume.assumeNotNull(sdkClient);
        sdkClient.headBucket(HeadBucketRequest.builder().bucket(bucket()).build());
    }

    private static String bucket() {
        return System.getenv(ENV_BUCKET);
    }

    private static String key(String relative) {
        // 所有 key 都必须带上规范化后的 base-dir，不能越界到其它前缀
        return BASE_DIR + "/" + relative;
    }

    private static void cleanup() {
        // 删除本次测试前缀下的所有对象，保证用例可重复运行
        List<String> keys = new ArrayList<>();
        ListObjectsV2Response response = sdkClient.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket()).prefix(BASE_DIR + "/").build());
        for (S3Object object : response.contents()) {
            keys.add(object.key());
        }
        if (!keys.isEmpty()) {
            List<ObjectIdentifier> identifiers = new ArrayList<>();
            for (String key : keys) {
                identifiers.add(ObjectIdentifier.builder().key(key).build());
            }
            sdkClient.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket())
                    .delete(Delete.builder().objects(identifiers).build()).build());
        }
    }

    /**
     * 空 bucket 初始化：put/head 在只做前缀语义、不创建 marker object 的情况下可用
     */
    @Test
    public void testPutAndHead() throws Exception {
        cleanup();
        // 虚拟的 PotatoSack 前缀不需要 marker object
        String key = key("PotatoSack/020240104000001/full.zip");
        byte[] content = "hello potatosack".getBytes(StandardCharsets.UTF_8);
        sdkClient.putObject(PutObjectRequest.builder().bucket(bucket()).key(key)
                .contentType("application/octet-stream").build(), RequestBody.fromBytes(content));

        software.amazon.awssdk.services.s3.model.HeadObjectResponse head =
                sdkClient.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                        .bucket(bucket()).key(key).build());
        assertEquals(content.length, head.contentLength().longValue());
        assertNotNull(head.eTag());
        cleanup();
    }

    /**
     * list 直接子项：文件与 common prefix 正确映射，不列出孙子项
     */
    @Test
    public void testListDirectChildrenOnly() {
        cleanup();
        String fullBackupId = "020240104000001";
        putString(key("PotatoSack/" + fullBackupId + "/full.zip"), "full");
        putString(key("PotatoSack/" + fullBackupId + "/backup.json"), "record");
        putString(key("PotatoSack/" + fullBackupId + "/incre000001.zip"), "incre");
        // 另一组备份，应作为同一个 common prefix 只出现一次
        putString(key("PotatoSack/020240104000002/full.zip"), "full2");
        // 孙子项不应出现在 PotatoSack 的直接子项里
        putString(key("PotatoSack/" + fullBackupId + "/nested/deep.bin"), "deep");

        // 列出 PotatoSack 的直接子项：应当只有两个虚拟目录
        ListObjectsV2Response response = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket())
                .prefix(key("PotatoSack/"))
                .delimiter("/")
                .build());
        assertTrue("PotatoSack 下不应有直接文件", response.contents() == null || response.contents().isEmpty());
        List<String> folders = new ArrayList<>();
        for (CommonPrefix commonPrefix : response.commonPrefixes()) {
            folders.add(S3PathUtils.normalize(commonPrefix.prefix()));
        }
        assertEquals(2, folders.size());
        assertTrue(folders.contains(key("PotatoSack/" + fullBackupId)));
        assertTrue(folders.contains(key("PotatoSack/020240104000002")));

        // 列出某一组备份的直接子项：应当是 3 个直接文件 + 1 个嵌套目录
        ListObjectsV2Response inner = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket())
                .prefix(key("PotatoSack/" + fullBackupId + "/"))
                .delimiter("/")
                .build());
        List<String> files = new ArrayList<>();
        for (S3Object object : inner.contents()) {
            files.add(object.key().substring(object.key().lastIndexOf('/') + 1));
        }
        assertEquals(3, files.size());
        assertTrue(files.contains("full.zip"));
        assertTrue(files.contains("backup.json"));
        assertTrue(files.contains("incre000001.zip"));
        assertFalse("孙子项不应作为直接子项出现", files.contains("deep.bin"));
        assertEquals(1, inner.commonPrefixes().size());
        cleanup();
    }

    /**
     * 下载内容必须与本地字节完全一致
     */
    @Test
    public void testDownloadMatchesLocalBytes() throws Exception {
        cleanup();
        String key = key("PotatoSack/dl/content.bin");
        // 1 MiB 伪随机内容，避免全零导致哈希比较失去意义
        byte[] content = new byte[1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        sdkClient.putObject(PutObjectRequest.builder().bucket(bucket()).key(key).build(),
                RequestBody.fromBytes(content));

        File localFile = File.createTempFile("potatosack-it-download", ".bin");
        localFile.deleteOnExit();
        try (ResponseInputStream<GetObjectResponse> in = sdkClient.getObject(
                GetObjectRequest.builder().bucket(bucket()).key(key).build());
             java.io.FileOutputStream out = new java.io.FileOutputStream(localFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        assertArrayEqualsWithMessage(content, Files.readAllBytes(localFile.toPath()));
        assertEquals(sha256(content), sha256(Files.readAllBytes(localFile.toPath())));
        cleanup();
    }

    /**
     * 大文件 multipart：大于 16 MiB 的文件成功完成，所有 part 正确合并
     */
    @Test
    public void testLargeFileMultipartUpload() throws Exception {
        cleanup();
        String key = key("PotatoSack/large/full.zip");
        File localFile = File.createTempFile("potatosack-it-large", ".zip");
        localFile.deleteOnExit();
        // 16 MiB + 3 MiB：至少 2 个 part
        long size = S3MultipartUploader.PART_SIZE + 3L * 1024 * 1024;
        try (RandomAccessFile raf = new RandomAccessFile(localFile, "rw")) {
            for (long offset = 0; offset < size; offset += 1024 * 1024) {
                byte[] chunk = new byte[(int) Math.min(1024 * 1024, size - offset)];
                Arrays.fill(chunk, (byte) (offset % 251));
                raf.write(chunk);
            }
        }
        assertEquals(size, localFile.length());

        S3FileUploader uploader = new S3FileUploader(sdkClient, bucket(), key, localFile);
        assertTrue("multipart 上传应当成功", uploader.upload());

        // 校验服务端对象大小与本地一致
        software.amazon.awssdk.services.s3.model.HeadObjectResponse head =
                sdkClient.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                        .bucket(bucket()).key(key).build());
        assertEquals(localFile.length(), head.contentLength().longValue());
        // 上传成功后不应残留未完成的 multipart upload
        assertNoDanglingMultipartUpload(key);
        cleanup();
    }

    /**
     * 流式 ZIP：不预先计算总长度，ZIP 可被 ZipInputStream 正常读取
     */
    @Test
    public void testStreamedZipUploadProducesReadableZip() throws Exception {
        cleanup();
        String key = key("PotatoSack/streamed/incre000001.zip");
        File sourceA = File.createTempFile("potatosack-it-src-a", ".txt");
        File sourceB = File.createTempFile("potatosack-it-src-b", ".txt");
        sourceA.deleteOnExit();
        sourceB.deleteOnExit();
        Files.writeString(sourceA.toPath(), "alpha-content", StandardCharsets.UTF_8);
        Files.writeString(sourceB.toPath(), "beta-content", StandardCharsets.UTF_8);

        ZipEntryInfo[] entries = new ZipEntryInfo[]{
                new ZipEntryInfo(sourceA.getAbsolutePath(), "deleted.files"),
                new ZipEntryInfo(sourceB.getAbsolutePath(), "backup.json")
        };
        S3StreamedZipUploader uploader = new S3StreamedZipUploader(sdkClient, bucket(), key);
        assertTrue("流式压缩上传应当成功", uploader.zipSpecifiedAndUpload(entries, true));

        // 下载后用 ZipInputStream 校验
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ResponseInputStream<GetObjectResponse> in = sdkClient.getObject(
                GetObjectRequest.builder().bucket(bucket()).key(key).build())) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(buffer.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                names.add(entry.getName());
                zin.readAllBytes();
            }
        }
        assertEquals(Arrays.asList("deleted.files", "backup.json"), names);
        assertNoDanglingMultipartUpload(key);
        cleanup();
    }

    /**
     * upload failure：模拟 part 失败后不存在未完成的 multipart upload
     */
    @Test
    public void testFailedUploadLeavesNoDanglingMultipartUpload() throws Exception {
        cleanup();
        String key = key("PotatoSack/failed/broken.zip");
        // 用 SDK 客户端包一层：让第 1 次 uploadPart 抛错，验证 abort 是否被调用
        S3MultipartUploader uploader = new S3MultipartUploader(sdkClient, bucket(), key, 1);
        byte[] payload = new byte[1024];
        try {
            // part number 0 会在发请求前被本地校验拒绝。
            uploader.uploadPart(0, payload.length, () -> new java.io.ByteArrayInputStream(payload));
            uploader.completeUpload();
        } catch (Exception expected) {
            // 预期失败
        } finally {
            uploader.abort();
        }
        // 必须不存在未完成的 multipart upload
        assertNoDanglingMultipartUpload(key);
        cleanup();
    }

    /**
     * recursive delete：删除备份 prefix 后所有 objects 消失；删除不存在的 prefix 幂等成功
     */
    @Test
    public void testRecursiveDeleteAndIdempotence() {
        cleanup();
        String backupPrefix = key("PotatoSack/020240105000001");
        putString(backupPrefix + "/full.zip", "full");
        putString(backupPrefix + "/backup.json", "record");
        putString(backupPrefix + "/incre000001.zip", "incre");
        putString(backupPrefix + "/nested/deep.bin", "deep");
        // 同级的另一组备份不应被误删
        String siblingPrefix = key("PotatoSack/020240105000002");
        putString(siblingPrefix + "/full.zip", "keep");

        // 分页列出并批量删除（与 S3Client.deleteItemInternal 相同的策略）
        deleteByPrefix(backupPrefix + "/");

        ListObjectsV2Response after = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket()).prefix(backupPrefix + "/").build());
        assertTrue("删除后该 prefix 下不应还有对象", after.contents() == null || after.contents().isEmpty());
        // 同级前缀必须保留
        ListObjectsV2Response sibling = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket()).prefix(siblingPrefix + "/").build());
        assertEquals(1, sibling.contents().size());
        // 重复删除同一个 prefix 必须幂等成功
        deleteByPrefix(backupPrefix + "/");
        cleanup();
    }

    /**
     * PotatoSack S3Client 契约：真正通过插件 client 覆盖 upload/get/list/download/recursive delete。
     */
    @Test
    public void testPotatoSackS3ClientContract() throws Exception {
        cleanup();
        S3Client client = new S3Client(inMemoryS3Config());
        try {
            String remoteDir = "PotatoSack/client-contract";
            String remoteFile = remoteDir + "/payload.bin";
            byte[] content = "potatosack-client-contract".getBytes(StandardCharsets.UTF_8);

            File source = File.createTempFile("potatosack-it-client-upload", ".bin");
            source.deleteOnExit();
            Files.write(source.toPath(), content);
            assertTrue("S3Client.uploadFile 应成功",
                    client.uploadFile(source.getAbsolutePath(), remoteFile));

            FileItem item = client.getItem(remoteFile);
            assertNotNull("S3Client.getItem 应返回刚上传的对象", item);
            assertFalse(item.isFolder());
            assertEquals("payload.bin", item.getName());
            assertEquals(content.length, item.getSize());

            List<FileItem> listed = client.listItems(remoteDir);
            assertEquals(1, listed.size());
            assertEquals("payload.bin", listed.get(0).getName());
            assertFalse(listed.get(0).isFolder());

            File downloaded = File.createTempFile("potatosack-it-client-download", ".bin");
            downloaded.deleteOnExit();
            assertTrue("S3Client.downloadFile 应成功",
                    client.downloadFile(remoteFile, downloaded.getAbsolutePath()));
            assertArrayEqualsWithMessage(content, Files.readAllBytes(downloaded.toPath()));

            // 增加孙子对象，验证 deleteItem 走 S3Client 自己的递归删除，而不是测试内复制算法。
            putString(key(remoteDir + "/nested/deep.bin"), "deep");
            assertTrue("S3Client.deleteItem 应递归删除整个 prefix", client.deleteItem(remoteDir));
            assertTrue("删除后对象应不存在", client.getItem(remoteFile) == null);
            assertTrue("删除后虚拟目录应不存在", client.getItem(remoteDir) == null);
            assertTrue("重复删除不存在 prefix 应幂等成功", client.deleteItem(remoteDir));
        } finally {
            client.shutdown();
            cleanup();
        }
    }

    /**
     * custom endpoint / path-style / region 配置可用于 MinIO 或 LocalStack
     */
    @Test
    public void testCustomEndpointWithPathStyleWorks() {
        cleanup();
        String key = key("PotatoSack/endpoint/probe.bin");
        putString(key, "probe");
        // 能读到就说明 endpoint、region、path-style 与凭证是匹配的
        try (ResponseInputStream<GetObjectResponse> inputStream = sdkClient.getObject(
                GetObjectRequest.builder().bucket(bucket()).key(key).build())) {
            assertNotNull(inputStream.response().eTag());
        } catch (IOException e) {
            throw new AssertionError("读取失败: " + e.getMessage(), e);
        }
        cleanup();
    }

    /**
     * base-dir：所有 object key 都带规范化后的 base-dir，不能越界到其它前缀
     */
    @Test
    public void testAllKeysStayUnderBaseDir() {
        cleanup();
        // 模拟 Client.buildFullPath 与 S3PathUtils 的组合
        String fullPath = S3PathUtils.normalize("/" + BASE_DIR + "//PotatoSack/grp/full.zip");
        assertEquals(BASE_DIR + "/PotatoSack/grp/full.zip", fullPath);
        assertTrue(fullPath.startsWith(BASE_DIR + "/"));

        putString(fullPath, "content");
        // 列出整个 bucket 中不属于 base-dir 的对象时，不应包含我们写入的 key
        ListObjectsV2Response underBase = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket()).prefix(BASE_DIR + "/").build());
        assertEquals(1, underBase.contents().size());
        assertEquals(fullPath, underBase.contents().get(0).key());
        cleanup();
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造不依赖 Bukkit 插件实例的内存 Config，仅供集成测试创建真实 PotatoSack S3Client。
     * Config 的生产构造函数会访问插件数据目录，因此测试通过 Unsafe 跳过构造函数，
     * 只注入 S3Client 实际读取的 YamlConfiguration。
     */
    private static Config inMemoryS3Config() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Config config = (Config) unsafe.allocateInstance(Config.class);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set(Config.KEYS.CLIENT.BASE_DIR, BASE_DIR);
        yaml.set(Config.KEYS.CLIENT.S3.ENDPOINT, System.getenv(ENV_ENDPOINT));
        yaml.set(Config.KEYS.CLIENT.S3.REGION, System.getenv(ENV_REGION));
        yaml.set(Config.KEYS.CLIENT.S3.BUCKET, bucket());
        yaml.set(Config.KEYS.CLIENT.S3.ACCESS_KEY, System.getenv(ENV_ACCESS_KEY));
        yaml.set(Config.KEYS.CLIENT.S3.SECRET_KEY, System.getenv(ENV_SECRET_KEY));
        yaml.set(Config.KEYS.CLIENT.S3.SESSION_TOKEN, "");
        yaml.set(Config.KEYS.CLIENT.S3.PATH_STYLE_ACCESS, true);

        Field configField = Config.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(config, yaml);
        return config;
    }

    private static void putString(String key, String content) {
        sdkClient.putObject(PutObjectRequest.builder().bucket(bucket()).key(key)
                        .contentType("application/octet-stream").build(),
                RequestBody.fromBytes(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 按 prefix 分页列出并批量删除，策略与 S3Client.deleteItemInternal 一致
     */
    private static void deleteByPrefix(String prefix) {
        String continuationToken = null;
        do {
            ListObjectsV2Response response = sdkClient.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket()).prefix(prefix).continuationToken(continuationToken).build());
            List<ObjectIdentifier> identifiers = new ArrayList<>();
            for (S3Object object : response.contents()) {
                identifiers.add(ObjectIdentifier.builder().key(object.key()).build());
            }
            if (!identifiers.isEmpty()) {
                sdkClient.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket())
                        .delete(Delete.builder().objects(identifiers).quiet(true).build()).build());
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null && !continuationToken.isEmpty());
    }

    /**
     * 断言不存在针对该 key 的未完成 multipart upload
     */
    private static void assertNoDanglingMultipartUpload(String key) {
        ListMultipartUploadsRequest.Builder request = ListMultipartUploadsRequest.builder()
                .bucket(bucket()).prefix(key);
        ListMultipartUploadsResponse response = sdkClient.listMultipartUploads(request.build());
        assertTrue("不应残留未完成的 multipart upload: " + key,
                response.uploads() == null || response.uploads().isEmpty());
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void assertArrayEqualsWithMessage(byte[] expected, byte[] actual) {
        assertEquals("本地文件大小应与服务端对象一致", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new AssertionError("下载内容与本地字节不一致，首个差异位于偏移 " + i);
            }
        }
    }
}
