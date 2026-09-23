package indi.somebottle.potatosack.clients.s3;

import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.clients.s3.entities.S3Item;
import indi.somebottle.potatosack.clients.s3.utils.S3PathUtils;
import indi.somebottle.potatosack.exceptions.ClientInitializationException;
import indi.somebottle.potatosack.tasks.entities.ZipEntryInfo;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 / S3-compatible 对象存储客户端实现
 * <p>
 * 基于 AWS SDK for Java v2 的同步 {@code S3Client}（HTTP 传输为 {@code url-connection-client}），
 * 支持 AWS S3 以及 MinIO、Cloudflare R2、Wasabi, 腾讯云 COS，阿里云 OSS 等兼容 S3 的服务。
 * </p>
 * <p>
 * <b>虚拟目录策略</b>：S3 是对象存储，key 中的 {@code /} 没有目录语义，服务端也不存在目录 inode。
 * 因此本实现采用“虚拟目录”，不为目录创建零字节 marker object：
 * <ul>
 *   <li>目录的存在性由 {@code key + "/"} 下是否存在子 object 决定；</li>
 *   <li>列表通过 {@code delimiter = "/"} 返回直接子对象与 {@code CommonPrefixes}；</li>
 *   <li>删除按 prefix 递归批量删除所有子对象；</li>
 *   <li>若服务端已存在目录 marker object，列表时会将其识别为目录并隐藏，避免同名文件与目录同时出现。</li>
 * </ul>
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>列出目录直接子项（分页）；</li>
 *   <li>查询文件/虚拟目录信息；</li>
 *   <li>下载对象到本地；</li>
 *   <li>小文件覆盖上传（PutObject）与大文件 multipart upload；</li>
 *   <li>压缩 ZIP 的同时进行 multipart upload；</li>
 *   <li>删除单个对象或递归删除一个“目录”下的所有对象。</li>
 * </ul>
 * </p>
 * <p>
 * <b>类名冲突</b>：本类与 SDK 的 {@code software.amazon.awssdk.services.s3.S3Client} 同名，
 * 因此本文件中 SDK 类型一律使用全限定名，且不 import SDK 的 {@code S3Client}。
 * </p>
 * <p>
 * <b>版本控制限制</b>：若 bucket 启用了版本控制，{@code DeleteObjects} 只会创建 delete marker，
 * 不会清理历史版本。
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.base.Client
 * @see S3FileUploader
 * @see S3StreamedZipUploader
 * @see S3MultipartUploader
 * @see indi.somebottle.potatosack.clients.s3.utils.S3PathUtils
 */
public class S3Client extends Client {
    /**
     * 单次 DeleteObjects 请求允许的最大 key 数量（S3 限制为 1000）
     */
    private static final int DELETE_BATCH_SIZE = 1000;

    /**
     * 下载文件时使用的固定缓冲区大小（8 KiB）
     */
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;

    /**
     * 默认 region
     */
    public static final String DEFAULT_REGION = "us-east-1";

    /**
     * 小文件上传使用的 Content-Type
     * <p>
     * 固定为 application/octet-stream，不按扩展名猜 MIME，避免 ZIP/二进制文件被错误处理。
     * </p>
     */
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";

    /**
     * 底层 AWS SDK 客户端
     */
    private final software.amazon.awssdk.services.s3.S3Client sdkClient;

    /**
     * 目标 bucket
     */
    private final String bucket;

    /**
     * 签名所用 region
     */
    private final String region;

    /**
     * 是否配置了自定义 endpoint
     */
    private final boolean hasCustomEndpoint;

    /**
     * 是否使用 path-style 访问
     */
    private final boolean pathStyleAccess;

    /**
     * 构造 S3 客户端
     * <p>
     * 初始化流程：
     * <ol>
     *   <li>{@code super(config)} 读取并规范化 {@code client.base-dir}；</li>
     *   <li>读取并校验 S3 配置，如果配置项缺失立即抛出带配置键名的 {@link ClientInitializationException}；</li>
     *   <li>按是否提供 session token 构造静态凭证；</li>
     *   <li>构造 SDK 客户端（region、可选 endpoint、path style、HTTP 超时、重试策略）；</li>
     *   <li>用 HeadBucket 做一次最小权限探测，验证 endpoint / region / 凭证 / bucket 是否可用；</li>
     *   <li>确保 {@code PotatoSack} 数据前缀可用（虚拟目录，不创建 marker object）。</li>
     * </ol>
     * </p>
     * <p>
     * 日志只输出 bucket、region、是否使用自定义 endpoint / path style 等非敏感信息。
     * </p>
     *
     * @param config 配置对象，包含 {@code client.s3.*} 各项配置
     * @throws ClientInitializationException 配置缺失、endpoint 非法或 bucket 不可访问时抛出
     * @throws IOException                   准备数据前缀失败时抛出
     */
    public S3Client(Config config) throws ClientInitializationException, IOException {
        super(config);
        // ---------------- 1. 读取并校验配置 ----------------
        String regionConfig = stringConfig(Config.KEYS.CLIENT.S3.REGION).trim();
        String bucketConfig = stringConfig(Config.KEYS.CLIENT.S3.BUCKET).trim();
        String accessKey = stringConfig(Config.KEYS.CLIENT.S3.ACCESS_KEY).trim();
        String secretKey = stringConfig(Config.KEYS.CLIENT.S3.SECRET_KEY).trim();
        String sessionToken = stringConfig(Config.KEYS.CLIENT.S3.SESSION_TOKEN).trim();
        String endpoint = stringConfig(Config.KEYS.CLIENT.S3.ENDPOINT).trim();
        boolean usePathStyle = booleanConfig(Config.KEYS.CLIENT.S3.PATH_STYLE_ACCESS);

        requireNonEmpty(regionConfig, Config.KEYS.CLIENT.S3.REGION);
        requireNonEmpty(bucketConfig, Config.KEYS.CLIENT.S3.BUCKET);
        requireNonEmpty(accessKey, Config.KEYS.CLIENT.S3.ACCESS_KEY);
        requireNonEmpty(secretKey, Config.KEYS.CLIENT.S3.SECRET_KEY);
        // 三段式临时凭证必须同时提供 session token，避免签名与凭证类型不匹配
        if (!sessionToken.isEmpty() && (accessKey.isEmpty() || secretKey.isEmpty())) {
            throw new ClientInitializationException("S3 temporary credentials are incomplete: "
                    + Config.KEYS.CLIENT.S3.SESSION_TOKEN + " is set, but "
                    + Config.KEYS.CLIENT.S3.ACCESS_KEY + " / " + Config.KEYS.CLIENT.S3.SECRET_KEY + " are missing.");
        }

        this.bucket = bucketConfig;
        this.region = regionConfig;
        this.hasCustomEndpoint = !endpoint.isEmpty();
        this.pathStyleAccess = usePathStyle;

        // ---------------- 2. 构造静态凭证 ----------------
        AwsCredentialsProvider credentialsProvider;
        if (sessionToken.isEmpty()) {
            credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        } else {
            // 临时凭证走 AwsSessionCredentials，签名时会带上 x-amz-security-token
            credentialsProvider = StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKey, secretKey, sessionToken));
        }

        // ---------------- 3. 构造 SDK 客户端 ----------------
        Region sdkRegion;
        try {
            sdkRegion = Region.of(regionConfig);
        } catch (Exception e) {
            throw new ClientInitializationException("Invalid S3 region '" + regionConfig + "' in "
                    + Config.KEYS.CLIENT.S3.REGION + ": " + e.getMessage(), e);
        }
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(usePathStyle)
                .build();        // URLConnection 是唯一引入的 HTTP 传输实现，见 pom.xml 中对 netty-nio-client / apache-client 的排除
        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(Constants.OKHTTP_CONNECT_TIMEOUT))
                .socketTimeout(Duration.ofSeconds(Constants.OKHTTP_READ_TIMEOUT));
        // 注意：SDK 的 builder 类型不写成 S3Client.S3ClientBuilder，
        // 否则会优先解析到本插件自己的 S3Client 类而找不到嵌套类型
        software.amazon.awssdk.services.s3.S3ClientBuilder builder =
                software.amazon.awssdk.services.s3.S3Client.builder()
                        .region(sdkRegion)
                        .credentialsProvider(credentialsProvider)
                        .httpClientBuilder(httpClientBuilder)
                        .serviceConfiguration(s3Configuration)
                        // 新版 SDK 默认给上传请求附加 AWS 风格的 CRC32 校验和（含 aws-chunked trailer）。
                        // 部分 S3-compatible 服务（较旧的 MinIO / R2 等）无法处理这些头，
                        // 因此这里改为按需计算，以换取更广的兼容性；
                        // 上传完整性仍由 HTTPS 传输层保证，且 ETag 会被逐 part 校验。
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .overrideConfiguration(o -> o
                                .apiCallTimeout(Duration.ofSeconds(Constants.OKHTTP_CALL_TIMEOUT))
                                .apiCallAttemptTimeout(Duration.ofSeconds(Constants.OKHTTP_WRITE_TIMEOUT))
                                // 使用 SDK 标准的指数退避重试策略（含请求级重试）
                                .retryStrategy(RetryMode.STANDARD));
        if (hasCustomEndpoint) {
            URI endpointUri;
            try {
                endpointUri = URI.create(endpoint);
            } catch (IllegalArgumentException e) {
                throw new ClientInitializationException("Invalid S3 endpoint '" + endpoint + "' in "
                        + Config.KEYS.CLIENT.S3.ENDPOINT + ": " + e.getMessage()
                        + ". It must include the scheme, e.g. \"http://127.0.0.1:9000\".", e);
            }
            String scheme = endpointUri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || endpointUri.getHost() == null) {
                throw new ClientInitializationException("Invalid S3 endpoint '" + endpoint + "' in "
                        + Config.KEYS.CLIENT.S3.ENDPOINT
                        + ": it must include the scheme, e.g. \"http://127.0.0.1:9000\".");
            }
            builder.endpointOverride(endpointUri);
        }
        software.amazon.awssdk.services.s3.S3Client builtClient;
        try {
            builtClient = builder.build();
        } catch (Exception e) {
            throw new ClientInitializationException("Failed to build the S3 client (bucket: " + bucket
                    + ", region: " + region + "): " + e.getMessage(), e);
        }
        this.sdkClient = builtClient;

        // 日志只输出非敏感信息
        ConsoleSender.logInfo("S3 client initialized. bucket: " + bucket + ", region: " + region
                + ", custom endpoint: " + (hasCustomEndpoint ? endpoint : "<AWS default>")
                + ", path-style access: " + pathStyleAccess);

        // ---------------- 4. 最小权限的 bucket 可访问性探测 ----------------
        verifyBucketAccessible();

        // ---------------- 5. 准备数据前缀（虚拟目录，不创建 marker object） ----------------
        String dataFolderPath = buildFullPath(Constants.APP_DATA_FOLDER);
        try {
            if (!ensureFolderExists(dataFolderPath)) {
                throw new IOException("Failed to prepare S3 data prefix: " + dataFolderPath);
            }
        } catch (IllegalArgumentException e) {
            throw new ClientInitializationException("Invalid S3 data prefix '" + dataFolderPath
                    + "' derived from " + Config.KEYS.CLIENT.BASE_DIR + ": " + e.getMessage(), e);
        }
        ConsoleSender.toConsole("S3 data prefix is ready: " + S3PathUtils.toDirPrefix(S3PathUtils.normalize(dataFolderPath)));
    }

    /**
     * 释放 SDK 客户端及其连接池
     * <p>
     * 允许重复调用，重复调用不会抛出异常。
     * S3 使用静态凭证，不需要像 OneDrive/Dropbox Client 那样启动 token 刷新定时任务。
     * </p>
     */
    @Override
    public void shutdown() {
        try {
            sdkClient.close();
            ConsoleSender.logInfo("S3 client closed.");
        } catch (Exception e) {
            ConsoleSender.logWarn("Failed to close the S3 client: " + e.getMessage());
        }
    }

    // ==================== listItems ====================

    /**
     * 列出指定 prefix 下的直接子项
     * <p>
     * 使用 {@code delimiter = "/"}，因此只返回当前 prefix 的直接文件与直接子目录（CommonPrefixes），
     * 不会把孙子项直接列出来。会循环处理 {@code isTruncated} / {@code nextContinuationToken} 直到读完所有页。
     * </p>
     * <p>
     * 此处不会按名称排序。
     * </p>
     *
     * @param fullPath 完整路径（已含 base-dir）
     * @return 直接子项列表；prefix 不存在或目录为空时返回空列表
     * @throws IOException 列出失败时抛出
     */
    @Override
    protected List<FileItem> listItemsInternal(String fullPath) throws IOException {
        String normalizedKey = S3PathUtils.normalize(fullPath);
        String prefix = S3PathUtils.toDirPrefix(normalizedKey);
        List<FileItem> items = new ArrayList<>();
        String continuationToken = null;
        try {
            do {
                ListObjectsV2Request request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .delimiter("/")
                        .continuationToken(continuationToken)
                        .build();
                ListObjectsV2Response response = sdkClient.listObjectsV2(request);
                if (response.contents() != null) {
                    for (S3Object object : response.contents()) {
                        String childName = objectChildNameOf(object.key(), prefix);
                        if (childName == null) {
                            // 明显不属于当前 prefix，或就是 prefix 自身的目录 marker，跳过
                            continue;
                        }
                        items.add(S3Item.file(childName, object.key(), object.size() == null ? 0L : object.size(),
                                object.eTag(), object.lastModified()));
                    }
                }
                if (response.commonPrefixes() != null) {
                    for (CommonPrefix prefixItem : response.commonPrefixes()) {
                        String childName = commonPrefixChildNameOf(prefixItem.prefix(), prefix);
                        if (childName == null) {
                            continue;
                        }
                        // CommonPrefixes 的 prefix 一定以 "/" 结尾，映射为不带尾 "/" 的虚拟目录
                        items.add(S3Item.folder(childName, S3PathUtils.normalize(prefixItem.prefix())));
                    }
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuationToken != null && !continuationToken.isEmpty());
        } catch (S3Exception e) {
            throw new IOException("Failed to list S3 objects. bucket: " + bucket + ", prefix: "
                    + describePrefix(prefix) + ", status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
        }
        return items;
    }

    /**
     * 从子 key 中解析出相对于父 prefix 的 basename
     * <p>
     * 同时完成以下过滤：
     * <ul>
     *   <li>把以 {@code /} 结尾的目录 marker object 识别为目录并隐藏（返回 null），
     *       避免同一目录同时以文件形式出现；</li>
     *   <li>过滤掉不是当前 prefix 直接子项的 key（含 prefix 自身）。</li>
     * </ul>
     * </p>
     *
     * @param childKey 子 object key 或 common prefix
     * @param prefix   父目录 prefix（可能为空，代表 bucket 根）
     * @return 相对 basename（不带结尾 {@code /}）；应被过滤时返回 null
     */
    private static String objectChildNameOf(String childKey, String prefix) {
        if (childKey == null || childKey.equals(prefix)) {
            return null;
        }
        if (!prefix.isEmpty() && !childKey.startsWith(prefix)) {
            return null;
        }
        String relative = childKey.substring(prefix.length());
        if (relative.isEmpty()) {
            return null;
        }
        // 仅保留直接子项：delimiter 已保证这一点，这里再兜一层
        int firstSlash = relative.indexOf('/');
        if (firstSlash >= 0 && firstSlash != relative.length() - 1) {
            return null;
        }
        if (S3PathUtils.isDirMarker(relative)) {
            // 目录 marker，隐藏，由 CommonPrefixes 负责表达该目录
            return null;
        }
        return relative;
    }

    /**
     * 从 S3 CommonPrefix 中解析当前目录下的直接子目录名
     * <p>
     * CommonPrefix 的尾斜杠是 delimiter 语义的一部分，不能像普通 object
     * 的目录 marker 一样过滤掉；这里会先去掉尾斜杠，再返回目录 basename。
     * </p>
     */
    private static String commonPrefixChildNameOf(String childPrefix, String prefix) {
        if (childPrefix == null || childPrefix.equals(prefix)) {
            return null;
        }
        if (!prefix.isEmpty() && !childPrefix.startsWith(prefix)) {
            return null;
        }
        String relative = childPrefix.substring(prefix.length());
        if (!relative.endsWith("/")) {
            return null;
        }
        relative = relative.substring(0, relative.length() - 1);
        if (relative.isEmpty() || relative.indexOf('/') >= 0) {
            return null;
        }
        return relative;
    }

    // ==================== getItem ====================

    /**
     * 获得某个 key 的信息（文件或虚拟目录）
     * <p>
     * 顺序：
     * <ol>
     *   <li>空路径直接返回 bucket 根的虚拟 {@link S3Item}；</li>
     *   <li>对精确 key 发 HeadObject，成功则返回文件项；</li>
     *   <li>精确对象不存在时，查询 {@code key + "/"} 下是否有任何子 object，
     *       有则返回虚拟目录项（虚拟目录允许暂时为空内容，因此这一步是“存在性”而非“非空性”判断）；</li>
     *   <li>确实不存在才返回 null；权限错误、签名错误、网络错误等一律抛出 {@link IOException}，
     *       不会吞掉成“不存在”。</li>
     * </ol>
     * </p>
     *
     * @param fullPath 完整路径（已含 base-dir）
     * @return 文件/虚拟目录项；确实不存在时返回 null
     * @throws IOException 网络或权限等错误时抛出
     */
    @Override
    protected FileItem getItemInternal(String fullPath) throws IOException {
        String key = S3PathUtils.normalize(fullPath);
        if (key.isEmpty()) {
            // bucket 根的虚拟目录
            return S3Item.folder("", "");
        }
        // 1. 先按精确 object 查询
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
            HeadObjectResponse response = sdkClient.headObject(request);
            if (S3PathUtils.isDirMarker(key)) {
                // 服务端已存在目录 marker，按目录返回
                return S3Item.folder(lastSegmentOf(S3PathUtils.normalize(key)), S3PathUtils.normalize(key));
            }
            return S3Item.file(lastSegmentOf(key), key,
                    response.contentLength() == null ? 0L : response.contentLength(),
                    response.eTag(), response.lastModified());
        } catch (NoSuchKeyException e) {
            // 精确对象不存在，继续判断虚拟目录
        } catch (S3Exception e) {
            if (!isNotFound(e)) {
                throw new IOException("Failed to head S3 object. bucket: " + bucket + ", key: " + key
                        + ", status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new IOException("Failed to head S3 object. bucket: " + bucket + ", key: " + key
                    + ", reason: " + e.getMessage(), e);
        }
        // 2. 再判断是否为虚拟目录（prefix 下有任意子 object 即视为存在）
        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(S3PathUtils.toDirPrefix(key))
                    .delimiter("/")
                    .maxKeys(1)
                    .build();
            ListObjectsV2Response response = sdkClient.listObjectsV2(request);
            boolean hasChildren = (response.contents() != null && !response.contents().isEmpty())
                    || (response.commonPrefixes() != null && !response.commonPrefixes().isEmpty());
            if (hasChildren) {
                return S3Item.folder(lastSegmentOf(key), key);
            }
        } catch (S3Exception e) {
            if (!isNotFound(e)) {
                throw new IOException("Failed to check S3 prefix existence. bucket: " + bucket + ", prefix: "
                        + key + "/, status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new IOException("Failed to check S3 prefix existence. bucket: " + bucket + ", prefix: " + key
                    + "/, reason: " + e.getMessage(), e);
        }
        // 3. 确实不存在
        return null;
    }

    // ==================== download ====================

    /**
     * 把对象下载到本地
     * <p>
     * 先通过 {@link #getItemInternal} 确认对象存在且不是目录；不存在或为目录时返回 false。
     * 使用固定 8 KiB 缓冲区边读边写，response stream 与本地输出流都会被正确关闭。
     * </p>
     *
     * @param fullRemotePath 完整远程路径
     * @param localPath      本地文件路径
     * @return 下载是否成功
     * @throws IOException 网络或本地文件写入失败时抛出
     */
    @Override
    protected boolean downloadFileInternal(String fullRemotePath, String localPath) throws IOException {
        String key = S3PathUtils.normalize(fullRemotePath);
        if (key.isEmpty()) {
            return false;
        }
        FileItem item = getItemInternal(key);
        if (item == null || item.isFolder()) {
            ConsoleSender.logWarn("S3 download skipped, object does not exist or is a folder. key: " + key);
            return false;
        }
        File localFile = new File(localPath);
        // 注意 getParentFile() 可能为 null（例如只给了文件名），不能直接对它调用 exists()
        File parentFile = localFile.getParentFile();
        if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
            ConsoleSender.logError("Failed to create local directory for S3 download: " + parentFile.getAbsolutePath());
            return false;
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        try (ResponseInputStream<GetObjectResponse> inputStream = sdkClient.getObject(request);
             FileOutputStream fileOutputStream = new FileOutputStream(localFile)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fileOutputStream.write(buffer, 0, bytesRead);
            }
            fileOutputStream.flush();
            return true;
        } catch (NoSuchKeyException e) {
            ConsoleSender.logWarn("S3 download failed, object no longer exists. key: " + key);
            return false;
        } catch (S3Exception e) {
            if (isNotFound(e)) {
                ConsoleSender.logWarn("S3 download failed, object no longer exists. key: " + key);
                return false;
            }
            throw new IOException("Failed to download S3 object. bucket: " + bucket + ", key: " + key
                    + ", status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
        }
    }

    // ==================== upload ====================

    /**
     * 小文件上传；超过 {@link Constants#MAX_SMALL_FILE_SIZE} 时自动转为 multipart upload
     * <p>
     * 小文件直接使用 PutObject 覆盖同名 object；Content-Type 固定为 {@code application/octet-stream}。
     * 允许上传零字节文件。
     * </p>
     *
     * @param localPath      本地文件路径
     * @param fullRemotePath 完整远程路径
     * @return 上传是否成功
     * @throws IOException 网络错误时抛出
     */
    @Override
    protected boolean uploadFileInternal(String localPath, String fullRemotePath) throws IOException {
        File localFile = new File(localPath);
        String key = S3PathUtils.normalize(fullRemotePath);
        if (!localFile.exists() || !localFile.isFile() || key.isEmpty()) {
            ConsoleSender.logWarn("S3 upload skipped, invalid local file or empty remote key. local: " + localPath
                    + ", key: " + key);
            return false;
        }
        if (localFile.length() > Constants.MAX_SMALL_FILE_SIZE) {
            // 大于阈值转 multipart
            return uploadLargeFileInternal(localPath, key);
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(CONTENT_TYPE_OCTET_STREAM)
                .contentLength(localFile.length())
                .build();
        try {
            sdkClient.putObject(request, RequestBody.fromFile(localFile));
            ConsoleSender.toConsole("S3 upload success! Key: " + key + ", size: " + localFile.length() + " byte(s)");
            return true;
        } catch (S3Exception e) {
            ConsoleSender.logError("S3 upload failed. bucket: " + bucket + ", key: " + key
                    + ", status: " + describeStatus(e) + ", message: " + e.getMessage());
            return false;
        }
    }

    /**
     * 大文件上传（multipart）
     *
     * @param localPath      本地文件路径
     * @param fullRemotePath 完整远程路径
     * @return 上传是否成功
     */
    @Override
    protected boolean uploadLargeFileInternal(String localPath, String fullRemotePath) {
        File localFile = new File(localPath);
        String key = S3PathUtils.normalize(fullRemotePath);
        if (!localFile.exists() || !localFile.isFile() || key.isEmpty()) {
            ConsoleSender.logWarn("S3 large upload skipped, invalid local file or empty remote key. local: " + localPath
                    + ", key: " + key);
            return false;
        }
        return new S3FileUploader(sdkClient, bucket, key, localFile).upload();
    }

    /**
     * 压缩的同时上传（流式，不预先计算压缩后总长度）
     *
     * @param entries        要打包进 zip 的条目
     * @param fullRemotePath 完整远程路径
     * @param quiet          是否静默打包
     * @return 上传是否成功
     */
    @Override
    protected boolean streamCompressAndUploadInternal(ZipEntryInfo[] entries, String fullRemotePath, boolean quiet) {
        String key = S3PathUtils.normalize(fullRemotePath);
        if (entries.length == 0 || key.isEmpty()) {
            ConsoleSender.logWarn("S3 streamed upload skipped, no entry or empty remote key.");
            return false;
        }
        return new S3StreamedZipUploader(sdkClient, bucket, key).zipSpecifiedAndUpload(entries, quiet);
    }

    // ==================== delete ====================

    /**
     * 删除指定路径下的项目（文件或“目录”）
     * <p>
     * S3 中删除一个“目录”意味着递归删除该 prefix 下的所有 object，因此：
     * <ul>
     *   <li>空路径或规范化后为空时直接拒绝，绝不把 bucket 根解释为删除整个 bucket；</li>
     *   <li>先尝试删除精确 key，再按 {@code key + "/"} prefix 分页列出并批量删除；</li>
     *   <li>每批最多 1000 个 key，使用 DeleteObjects 批量删除，不为每个子对象单独发 DELETE；</li>
     *   <li>只要 DeleteObjectsResponse 中出现任意单项错误，整体返回失败并记录 key/code/message；</li>
     *   <li>没有匹配对象时按幂等删除成功处理，便于重试旧备份清理；</li>
     *   <li>目录 marker object 也会被一并清理，不会遗留 {@code backupId/} marker。</li>
     * </ul>
     * </p>
     * <p>
     * 注意：bucket 开启版本控制时，普通 DeleteObjects 只会创建 delete marker，
     * 不会清理旧版本；第一版不实现版本枚举与永久删除。
     * </p>
     *
     * @param fullPath 完整路径
     * @return 是否删除成功
     * @throws IOException 网络错误时抛出
     */
    @Override
    protected boolean deleteItemInternal(String fullPath) throws IOException {
        String key;
        try {
            key = S3PathUtils.requireNonRoot(fullPath);
        } catch (IllegalArgumentException e) {
            // 绝不能把空 prefix 当成“删除整个 bucket”
            ConsoleSender.logError("Refused to delete an empty S3 path (would affect the whole bucket): " + e.getMessage());
            return false;
        }
        List<String> keysToDelete = new ArrayList<>();
        keysToDelete.add(key);
        String prefix = S3PathUtils.toDirPrefix(key);
        try {
            // 分页列出所有以该 prefix 开头的 object（含目录 marker）
            String continuationToken = null;
            do {
                ListObjectsV2Request request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(continuationToken)
                        .build();
                ListObjectsV2Response response = sdkClient.listObjectsV2(request);
                if (response.contents() != null) {
                    for (S3Object object : response.contents()) {
                        if (object.key() != null && !object.key().equals(key)) {
                            keysToDelete.add(object.key());
                        }
                    }
                }
                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (continuationToken != null && !continuationToken.isEmpty());
            // 分批批量删除，每批最多 1000 个 key
            for (int start = 0; start < keysToDelete.size(); start += DELETE_BATCH_SIZE) {
                List<String> batch = keysToDelete.subList(start,
                        Math.min(start + DELETE_BATCH_SIZE, keysToDelete.size()));
                if (!deleteBatch(batch)) {
                    return false;
                }
            }
            ConsoleSender.toConsole("S3 delete success. key: " + key + ", deleted objects: " + keysToDelete.size());
            return true;
        } catch (S3Exception e) {
            throw new IOException("Failed to delete S3 objects. bucket: " + bucket + ", key: " + key
                    + ", status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
        }
    }

    /**
     * 批量删除一批 key，并检查响应中的单项错误
     *
     * @param keys 本批 key（不超过 1000 个）
     * @return 全部删除成功返回 true；出现任意单项错误返回 false
     */
    private boolean deleteBatch(List<String> keys) throws IOException {
        List<software.amazon.awssdk.services.s3.model.ObjectIdentifier> identifiers = new ArrayList<>(keys.size());
        for (String key : keys) {
            identifiers.add(software.amazon.awssdk.services.s3.model.ObjectIdentifier.builder().key(key).build());
        }
        DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(identifiers).quiet(true).build())
                .build();
        try {
            DeleteObjectsResponse response = sdkClient.deleteObjects(request);
            if (response.hasErrors() && response.errors() != null && !response.errors().isEmpty()) {
                for (S3Error error : response.errors()) {
                    ConsoleSender.logError("S3 batch delete item failed. bucket: " + bucket + ", key: " + error.key()
                            + ", code: " + error.code() + ", message: " + error.message());
                }
                return false;
            }
            return true;
        } catch (S3Exception e) {
            throw new IOException("Failed to batch delete S3 objects. bucket: " + bucket + ", count: " + keys.size()
                    + ", status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
        }
    }

    // ==================== createFolder ====================

    /**
     * 创建目录（虚拟目录）
     * <p>
     * 采用虚拟目录策略，不发起任何 marker object 上传：只要参数合法，
     * 返回 true 即表示该 key prefix 可以被后续对象使用。
     * </p>
     * <p>
     * 目录名中不允许出现 {@code /}：{@code Client.createFolder(path, name)} 的契约是建立一个子目录，
     * 需要多级目录时应由 {@code ensureFolderExists} 逐级调用。
     * </p>
     *
     * @param fullPath 父目录完整路径
     * @param name     目录名
     * @return 该虚拟目录是否可用
     * @throws IOException 不会抛出，仅为满足基类签名
     */
    @Override
    protected boolean createFolderInternal(String fullPath, String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.contains("/") || name.contains("\\")) {
            ConsoleSender.logWarn("Refused to create an S3 virtual folder with an invalid name: " + name);
            return false;
        }
        String parentKey = S3PathUtils.normalize(fullPath);
        String targetKey = S3PathUtils.normalize(parentKey.isEmpty() ? name : parentKey + "/" + name);
        if (targetKey.isEmpty()) {
            ConsoleSender.logWarn("Refused to create an S3 virtual folder at the bucket root.");
            return false;
        }
        // 虚拟目录不需要上传 marker object，S3 中空目录本来就不持久化
        ConsoleSender.toConsole("S3 virtual folder is ready: " + S3PathUtils.toDirPrefix(targetKey));
        return true;
    }

    // ==================== 工具方法 ====================

    /**
     * 用 HeadBucket 做一次最小权限探测，把常见错误转换为带配置上下文的初始化异常
     *
     * @throws ClientInitializationException bucket 不可访问时抛出
     */
    private void verifyBucketAccessible() throws ClientInitializationException {
        try {
            sdkClient.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            throw new ClientInitializationException("S3 bucket '" + bucket + "' configured in "
                    + Config.KEYS.CLIENT.S3.BUCKET + " does not exist on "
                    + endpointDescription() + ".", e);
        } catch (S3Exception e) {
            int status = e.statusCode();
            if (status == 403) {
                throw new ClientInitializationException("Access denied to S3 bucket '" + bucket + "' (" + endpointDescription()
                        + "). Please check " + Config.KEYS.CLIENT.S3.ACCESS_KEY + " / "
                        + Config.KEYS.CLIENT.S3.SECRET_KEY + " / " + Config.KEYS.CLIENT.S3.SESSION_TOKEN
                        + " and make sure the credentials have s3:ListBucket / s3:HeadBucket permission on this bucket.", e);
            }
            if (status == 404) {
                throw new ClientInitializationException("S3 bucket '" + bucket + "' configured in "
                        + Config.KEYS.CLIENT.S3.BUCKET + " was not found on " + endpointDescription()
                        + ". Please check the bucket name and " + Config.KEYS.CLIENT.S3.ENDPOINT + ".", e);
            }
            if (status == 301 || status == 400) {
                throw new ClientInitializationException("S3 bucket '" + bucket + "' is not reachable with region '"
                        + region + "' (" + Config.KEYS.CLIENT.S3.REGION + "). "
                        + "The region must match the bucket's real region. Status: " + describeStatus(e), e);
            }
            throw new ClientInitializationException("Failed to access S3 bucket '" + bucket + "' (" + endpointDescription()
                    + "). Status: " + describeStatus(e) + ", message: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ClientInitializationException("Failed to connect to " + endpointDescription()
                    + " for bucket '" + bucket + "'. Please check " + Config.KEYS.CLIENT.S3.ENDPOINT
                    + " / " + Config.KEYS.CLIENT.S3.REGION + " and the network. Reason: " + e.getMessage(), e);
        }
    }

    /**
     * 读取字符串配置项，null 视为空字符串
     *
     * @param path 配置键
     * @return 配置值字符串
     * @throws ClientInitializationException 配置值类型不是字符串时抛出
     */
    private String stringConfig(String path) throws ClientInitializationException {
        Object value = config.getConfig(path);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new ClientInitializationException("Invalid S3 configuration: " + path
                    + " must be a string, but was " + value.getClass().getSimpleName() + ".");
        }
        return (String) value;
    }

    /**
     * 读取布尔配置项，兼容 YAML 布尔值与字符串形式
     *
     * @param path 配置键
     * @return 布尔值
     * @throws ClientInitializationException 配置值无法解析为布尔值时抛出
     */
    private boolean booleanConfig(String path) throws ClientInitializationException {
        Object value = config.getConfig(path);
        if (value == null || "".equals(value)) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw new ClientInitializationException("Invalid S3 configuration: " + path
                + " must be a boolean (true/false), but was '" + value + "'.");
    }

    /**
     * 校验必填配置项非空
     *
     * @param value 配置值
     * @param path  配置键名（用于错误信息）
     * @throws ClientInitializationException 配置值为空时抛出
     */
    private static void requireNonEmpty(String value, String path) throws ClientInitializationException {
        if (value == null || value.isEmpty()) {
            throw new ClientInitializationException("S3 client is not configured. Please fill in " + path
                    + " in configs.yml.");
        }
    }

    /**
     * 获得 key 的最后一个路径段，作为 basename
     *
     * @param key 已规范化的 key
     * @return basename
     */
    private static String lastSegmentOf(String key) {
        String normalized = S3PathUtils.normalize(key);
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
    }

    /**
     * 判断 S3 异常是否为 not-found
     * <p>
     * 不同的 S3-compatible 服务对 HeadObject 404 返回的异常类型不同，
     * 因此同时覆盖 HTTP status/code 与 SDK 异常类型，不能只 catch NoSuchKeyException。
     * </p>
     *
     * @param e S3 异常
     * @return 是否为 not-found
     */
    private static boolean isNotFound(S3Exception e) {
        if (e.statusCode() == 404) {
            return true;
        }
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        return "NoSuchKey".equals(code) || "NotFound".equals(code) || "NoSuchBucket".equals(code);
    }

    /**
     * 生成 HTTP 状态描述，供日志与异常信息使用
     *
     * @param e S3 异常
     * @return 形如 "HTTP 404 (NoSuchKey)" 的描述
     */
    private static String describeStatus(S3Exception e) {
        String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
        return "HTTP " + e.statusCode() + (code == null ? "" : " (" + code + ")");
    }

    /**
     * 生成 endpoint 描述，供错误信息使用（不含任何凭证信息）
     *
     * @return endpoint 描述
     */
    private String endpointDescription() {
        return hasCustomEndpoint ? "endpoint " + config.getConfig(Config.KEYS.CLIENT.S3.ENDPOINT)
                : "the AWS S3 endpoint of region " + region;
    }

    /**
     * 描述 prefix，空 prefix 时用 bucket 根表示
     *
     * @param prefix 目录 prefix
     * @return 描述字符串
     */
    private static String describePrefix(String prefix) {
        return prefix.isEmpty() ? "<bucket root>" : prefix;
    }
}
