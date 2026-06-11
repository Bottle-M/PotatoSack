package indi.somebottle.potatosack.clients.dropbox;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.clients.dropbox.entities.DropboxItem;
import indi.somebottle.potatosack.clients.dropbox.entities.DropboxListFolderResp;
import indi.somebottle.potatosack.clients.dropbox.entities.DropboxUploadSessionStartResp;
import indi.somebottle.potatosack.clients.dropbox.utils.DropboxErrorUtils;
import indi.somebottle.potatosack.clients.dropbox.utils.DropboxPathUtils;
import indi.somebottle.potatosack.exceptions.ClientInitializationException;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dropbox 云存储客户端实现
 * <p>
 * 实现了与 Dropbox API v2 的交互，支持文件和文件夹的上传、下载、删除、列表等操作。
 * 使用 OAuth2 进行认证，通过 refresh token 自动刷新 access token。
 * </p>
 * <p>
 * 主要功能：
 * <ul>
 *   <li>文件上传：支持小文件直接上传和大文件分块上传</li>
 *   <li>流式压缩上传：边压缩边上传，无需先生成本地压缩文件</li>
 *   <li>文件下载：从 Dropbox 下载文件到本地</li>
 *   <li>目录管理：创建、删除、列出目录内容</li>
 *   <li>自动令牌刷新：后台定时检查并刷新 access token</li>
 * </ul>
 * </p>
 *
 * @see indi.somebottle.potatosack.clients.base.Client
 * @see DropboxTokenFetcher
 * @see DropboxFileUploader
 * @see DropboxStreamedZipUploader
 */
public class DropboxClient extends Client {
    /**
     * Dropbox API v2 端点地址
     */
    public static final String DROPBOX_API_ENDPOINT = "https://api.dropboxapi.com/2";

    /**
     * Dropbox Content API v2 端点地址（用于文件上传下载）
     */
    public static final String DROPBOX_CONTENT_ENDPOINT = "https://content.dropboxapi.com/2";

    /**
     * 分块上传的块大小（16MB）
     */
    public static final int CHUNK_SIZE = 1024 * 320 * 50;

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    /**
     * 访问令牌获取器，负责管理和刷新 OAuth2 令牌
     */
    private final DropboxTokenFetcher tokenFetcher;

    /**
     * 令牌自动更新任务
     */
    private final BukkitTask tokenUpdateTask;

    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Constants.OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Constants.OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Constants.OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(Constants.OKHTTP_CALL_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(new HttpRetryInterceptor())
            .build();

    /**
     * 构造 Dropbox 客户端
     * <p>
     * 初始化流程：
     * <ol>
     *   <li>从配置中读取 Dropbox 应用密钥和刷新令牌</li>
     *   <li>创建令牌获取器并获取首个访问令牌</li>
     *   <li>启动后台定时任务，每 30 秒检查并刷新令牌</li>
     *   <li>确保数据存储文件夹在 Dropbox 中存在</li>
     * </ol>
     * </p>
     *
     * @param config 配置对象，包含 Dropbox 应用密钥、密钥和刷新令牌
     * @throws IOException 当无法获取访问令牌或创建数据文件夹时抛出
     * @see indi.somebottle.potatosack.utils.Config.KEYS.CLIENT.DROPBOX
     * @see DropboxTokenFetcher
     */
    public DropboxClient(Config config) throws IOException {
        super(config);
        String appKey = (String) config.getConfig(Config.KEYS.CLIENT.DROPBOX.APP_KEY);
        String appSecret = (String) config.getConfig(Config.KEYS.CLIENT.DROPBOX.APP_SECRET);
        String refreshToken = (String) config.getConfig(Config.KEYS.CLIENT.DROPBOX.REFRESH_TOKEN);
        tokenFetcher = new DropboxTokenFetcher(appKey, appSecret, refreshToken, config);
        if (!tokenFetcher.fetch()) {
            throw new ClientInitializationException("Failed to initialize DropboxClient: unable to fetch access token.");
        }
        Plugin plugin = PotatoSack.getPluginInstance();
        if (plugin != null) {
            tokenUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new DropboxTokenUpdater(tokenFetcher), 0, 20 * 30);
        } else {
            tokenUpdateTask = null;
        }
        String dataFolderPath = buildFullPath(Constants.APP_DATA_FOLDER);
        if (!ensureFolderExists(dataFolderPath)) {
            throw new IOException("Failed to create data folder in Dropbox: " + dataFolderPath);
        }
        System.out.println("Dropbox data folder is ready: " + dataFolderPath);
    }

    @Override
    public void shutdown() {
        if (tokenUpdateTask != null) {
            tokenUpdateTask.cancel();
        }
    }

    @Override
    protected List<FileItem> listItemsInternal(String fullPath) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("path", DropboxPathUtils.toDropboxPath(fullPath));
        Request req = apiJsonRequest(DROPBOX_API_ENDPOINT + "/files/list_folder", body);
        return requestListFolder(req);
    }

    private List<FileItem> requestListFolder(Request req) throws IOException {
        List<FileItem> resList = new ArrayList<>();
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            String bodyText = respBody == null ? "" : respBody.string();
            if (!resp.isSuccessful()) {
                if (resp.code() != 409) {
                    ConsoleSender.logWarn("Dropbox list folder failed, code: " + resp.code() + ", message: " + resp.message() + "\n Resp body: " + bodyText);
                }
                return Collections.emptyList();
            }
            DropboxListFolderResp listResp = gson.fromJson(bodyText, DropboxListFolderResp.class);
            if (listResp.getEntries() != null) {
                resList.addAll(listResp.getEntries());
            }
            while (listResp.hasMore()) {
                JsonObject continueBody = new JsonObject();
                continueBody.addProperty("cursor", listResp.getCursor());
                Request continueReq = apiJsonRequest(DROPBOX_API_ENDPOINT + "/files/list_folder/continue", continueBody);
                try (Response continueResp = client.newCall(continueReq).execute()) {
                    ResponseBody continueRespBody = continueResp.body();
                    String continueBodyText = continueRespBody == null ? "" : continueRespBody.string();
                    if (!continueResp.isSuccessful()) {
                        ConsoleSender.logWarn("Dropbox list folder continue failed, code: " + continueResp.code() + ", message: " + continueResp.message() + "\n Resp body: " + continueBodyText);
                        break;
                    }
                    listResp = gson.fromJson(continueBodyText, DropboxListFolderResp.class);
                    if (listResp.getEntries() != null) {
                        resList.addAll(listResp.getEntries());
                    }
                }
            }
        }
        return resList;
    }

    @Override
    protected DropboxItem getItemInternal(String fullPath) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("path", DropboxPathUtils.toDropboxPath(fullPath));
        body.addProperty("include_deleted", false);
        Request req = apiJsonRequest(DROPBOX_API_ENDPOINT + "/files/get_metadata", body);
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            String bodyText = respBody == null ? "" : respBody.string();
            if (resp.isSuccessful()) {
                return gson.fromJson(bodyText, DropboxItem.class);
            }
            if (resp.code() != 409) {
                ConsoleSender.logWarn("Dropbox item req failed, code: " + resp.code() + ", message: " + resp.message() + "\n Resp body: " + bodyText);
            }
        }
        return null;
    }

    @Override
    protected boolean downloadFileInternal(String fullRemotePath, String localPath) throws IOException {
        File localFile = new File(localPath);
        File parentFile = localFile.getParentFile();
        if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
            return false;
        }
        JsonObject arg = new JsonObject();
        arg.addProperty("path", DropboxPathUtils.toDropboxPath(fullRemotePath));
        Request req = contentRequest(DROPBOX_CONTENT_ENDPOINT + "/files/download", arg, emptyOctetBody());
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            if (!resp.isSuccessful() || respBody == null) {
                String errMsg = "Dropbox download failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null) {
                    errMsg += "\n Resp body: " + respBody.string();
                }
                ConsoleSender.logWarn(errMsg);
                return false;
            }
            try (InputStream inputStream = respBody.byteStream();
                 FileOutputStream fos = new FileOutputStream(localFile)) {
                byte[] buf = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buf)) != -1) {
                    fos.write(buf, 0, bytesRead);
                }
                fos.flush();
            }
            return true;
        }
    }

    @Override
    protected boolean streamCompressAndUploadInternal(ZipFilePath[] zipFilePaths, String fullRemotePath, boolean quiet) {
        if (zipFilePaths.length == 0 || fullRemotePath.equals("")) {
            return false;
        }
        DropboxStreamedZipUploader uploader = new DropboxStreamedZipUploader(this, fullRemotePath);
        return uploader.zipSpecifiedAndUpload(zipFilePaths, quiet);
    }

    @Override
    protected boolean uploadLargeFileInternal(String localPath, String fullRemotePath) {
        if (!new File(localPath).exists() || fullRemotePath.equals("")) {
            return false;
        }
        DropboxFileUploader uploader = new DropboxFileUploader(new File(localPath), this, fullRemotePath);
        return uploader.upload();
    }

    @Override
    protected boolean uploadFileInternal(String localPath, String fullRemotePath) throws IOException {
        if (!new File(localPath).exists() || fullRemotePath.equals("")) {
            return false;
        }
        File localFile = new File(localPath);
        if (localFile.length() > Constants.MAX_SMALL_FILE_SIZE) {
            return uploadLargeFileInternal(localPath, fullRemotePath);
        }
        JsonObject arg = commitInfo(fullRemotePath);
        Request req = contentRequest(DROPBOX_CONTENT_ENDPOINT + "/files/upload", arg, RequestBody.create(localFile, OCTET_STREAM));
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            if (resp.isSuccessful()) {
                return true;
            }
            String errMsg = "Dropbox file upload failed, code: " + resp.code() + ", message: " + resp.message();
            if (respBody != null) {
                errMsg += "\n Resp body: " + respBody.string();
            }
            ConsoleSender.logWarn(errMsg);
        }
        return false;
    }

    @Override
    protected boolean deleteItemInternal(String fullPath) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("path", DropboxPathUtils.toDropboxPath(fullPath));
        Request req = apiJsonRequest(DROPBOX_API_ENDPOINT + "/files/delete_v2", body);
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            if (resp.isSuccessful()) {
                return true;
            }
            String errMsg = "Dropbox delete failed, code: " + resp.code() + ", message: " + resp.message();
            if (respBody != null) {
                errMsg += "\n Resp body: " + respBody.string();
            }
            ConsoleSender.logWarn(errMsg);
        }
        return false;
    }

    @Override
    protected boolean createFolderInternal(String fullPath, String name) throws IOException {
        String targetPath = fullPath.equals("") ? name : fullPath + "/" + name;
        JsonObject body = new JsonObject();
        body.addProperty("path", DropboxPathUtils.toDropboxPath(targetPath));
        body.addProperty("autorename", false);
        Request req = apiJsonRequest(DROPBOX_API_ENDPOINT + "/files/create_folder_v2", body);
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            if (resp.isSuccessful()) {
                return true;
            }
            if (resp.code() == 409) {
                FileItem existing = getItemInternal(targetPath);
                return existing != null && existing.isFolder();
            }
            String errMsg = "Dropbox folder create failed, code: " + resp.code() + ", message: " + resp.message();
            if (respBody != null) {
                errMsg += "\n Resp body: " + respBody.string();
            }
            ConsoleSender.logWarn(errMsg);
        }
        return false;
    }

    /**
     * 创建上传会话
     * <p>
     * 用于大文件上传前，创建一个上传会话并获取会话 ID。
     * 后续通过 {@link #appendUploadSession(String, long, byte[], int, int)} 分块上传数据，
     * 最后通过 {@link #finishUploadSession(String, long, String)} 完成上传。
     * </p>
     *
     * @return 上传会话 ID
     * @throws IOException 当请求失败时抛出
     * @see #appendUploadSession(String, long, byte[], int, int)
     * @see #finishUploadSession(String, long, String)
     */
    public String createUploadSession() throws IOException {
        JsonObject arg = new JsonObject();
        arg.addProperty("close", false);
        Request req = contentRequest(DROPBOX_CONTENT_ENDPOINT + "/files/upload_session/start", arg, emptyOctetBody());
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            String bodyText = respBody == null ? "" : respBody.string();
            if (resp.isSuccessful()) {
                DropboxUploadSessionStartResp startResp = gson.fromJson(bodyText, DropboxUploadSessionStartResp.class);
                return startResp.getSessionId();
            }
            throw new IOException("Dropbox upload session creation failed, code: " + resp.code() + ", message: " + resp.message() + "\n Resp body: " + bodyText);
        }
    }

    /**
     * 追加数据到上传会话
     * <p>
     * 将数据块追加到指定的上传会话。支持自动偏移量校正和重试机制。
     * 若 Dropbox 返回偏移量不匹配错误（409），会自动校正并续传。
     * </p>
     *
     * @param sessionId 上传会话 ID
     * @param offset 当前偏移量（已上传的字节数）
     * @param buffer 数据缓冲区
     * @param bufferStart 缓冲区起始位置
     * @param length 要上传的数据长度
     * @return 上传后的新偏移量
     * @throws IOException 当上传失败且重试次数耗尽时抛出
     */
    public long appendUploadSession(String sessionId, long offset, byte[] buffer, int bufferStart, int length) throws IOException {
        return appendUploadSession(sessionId, offset, buffer, bufferStart, length, 0);
    }

    private long appendUploadSession(String sessionId, long offset, byte[] buffer, int bufferStart, int length, int retry) throws IOException {
        if (length <= 0) {
            return offset;
        }
        JsonObject arg = new JsonObject();
        arg.add("cursor", uploadSessionCursor(sessionId, offset));
        arg.addProperty("close", false);
        byte[] chunkData = Arrays.copyOfRange(buffer, bufferStart, bufferStart + length);
        Request req = contentRequest(DROPBOX_CONTENT_ENDPOINT + "/files/upload_session/append_v2", arg, RequestBody.create(chunkData, OCTET_STREAM));
        Response resp;
        try {
            resp = client.newCall(req).execute();
        } catch (IOException e) {
            if (retry >= Constants.MAX_STREAMED_CHUNK_UPLOAD_RETRY) {
                throw e;
            }
            waitBeforeChunkRetry();
            return appendUploadSession(sessionId, offset, buffer, bufferStart, length, retry + 1);
        }
        try (Response response = resp) {
            ResponseBody respBody = response.body();
            String bodyText = respBody == null ? "" : respBody.string();
            if (response.isSuccessful()) {
                return offset + length;
            }
            if (response.code() == 409) {
                Long correctOffset = DropboxErrorUtils.getCorrectOffset(bodyText);
                if (correctOffset != null) {
                    long rangeEnd = offset + length;
                    ConsoleSender.toConsole("Dropbox upload offset mismatch. Correct offset: " + correctOffset + ", local range: " + offset + "-" + (rangeEnd - 1));
                    if (correctOffset == rangeEnd) {
                        return correctOffset;
                    }
                    if (correctOffset > offset && correctOffset < rangeEnd) {
                        int localSkip = (int) (correctOffset - offset);
                        return appendUploadSession(sessionId, correctOffset, buffer, bufferStart + localSkip, length - localSkip, 0);
                    }
                }
            }
            throw new IOException("Dropbox append upload failed, code: " + response.code() + ", message: " + response.message() + "\n Resp body: " + bodyText);
        }
    }

    /**
     * 完成上传会话
     * <p>
     * 完成上传会话并将文件保存到指定路径。
     * 调用此方法前应确保所有数据已通过 {@link #appendUploadSession(String, long, byte[], int, int)} 上传完毕。
     * </p>
     *
     * @param sessionId 上传会话 ID
     * @param offset 最终偏移量（文件总大小）
     * @param fullRemotePath 完整的远程文件路径
     * @return {@code true} 表示上传完成成功
     * @throws IOException 当请求失败时抛出
     */
    public boolean finishUploadSession(String sessionId, long offset, String fullRemotePath) throws IOException {
        JsonObject arg = new JsonObject();
        arg.add("cursor", uploadSessionCursor(sessionId, offset));
        arg.add("commit", commitInfo(fullRemotePath));
        Request req = contentRequest(DROPBOX_CONTENT_ENDPOINT + "/files/upload_session/finish", arg, emptyOctetBody());
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            String bodyText = respBody == null ? "" : respBody.string();
            if (resp.isSuccessful()) {
                return true;
            }
            throw new IOException("Dropbox upload session finish failed, code: " + resp.code() + ", message: " + resp.message() + "\n Resp body: " + bodyText);
        }
    }

    private Request apiJsonRequest(String url, JsonObject body) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
    }

    private Request contentRequest(String url, JsonObject arg, RequestBody body) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .header("Dropbox-API-Arg", gson.toJson(arg))
                .header("Content-Type", "application/octet-stream")
                .post(body)
                .build();
    }

    private RequestBody emptyOctetBody() {
        return RequestBody.create(new byte[0], OCTET_STREAM);
    }

    private JsonObject uploadSessionCursor(String sessionId, long offset) {
        JsonObject cursor = new JsonObject();
        cursor.addProperty("session_id", sessionId);
        cursor.addProperty("offset", offset);
        return cursor;
    }

    private JsonObject commitInfo(String fullRemotePath) {
        JsonObject commit = new JsonObject();
        commit.addProperty("path", DropboxPathUtils.toDropboxPath(fullRemotePath));
        commit.addProperty("mode", "overwrite");
        commit.addProperty("autorename", false);
        commit.addProperty("mute", false);
        commit.addProperty("strict_conflict", false);
        return commit;
    }

    private void waitBeforeChunkRetry() {
        try {
            System.out.println("Failed to request Dropbox, retrying to upload in 10 seconds...");
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println(e.getMessage());
        }
    }
}
