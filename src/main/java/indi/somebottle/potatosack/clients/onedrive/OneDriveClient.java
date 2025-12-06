package indi.somebottle.potatosack.clients.onedrive;

import com.google.gson.Gson;
import indi.somebottle.potatosack.PotatoSack;
import indi.somebottle.potatosack.clients.base.Client;
import indi.somebottle.potatosack.clients.base.entities.FileItem;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveChildrenResp;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveItem;
import indi.somebottle.potatosack.tasks.entities.ZipFilePath;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveFolderRequest;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveUpSessionCreateResp;
import indi.somebottle.potatosack.clients.onedrive.entities.OneDriveUploadRequest;
import indi.somebottle.potatosack.exceptions.ClientInitializationException;
import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import indi.somebottle.potatosack.utils.Constants;
import indi.somebottle.potatosack.utils.HttpRetryInterceptor;
import okhttp3.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class OneDriveClient extends Client {
    // Microsoft Graph API 终结点
    public static String MS_GRAPH_ENDPOINT = "https://graph.microsoft.com/v1.0";
    // API 请求中的 APP 根目录路径 (当且仅当访问 APP 目录时)
    public static String OD_API_APP_ROOT_PATH = "/drive/special/approot";
    // API 请求中的根目录路径
    public static String OD_API_ROOT_PATH = "/me/drive/root";

    private final OneDriveTokenFetcher tokenFetcher;
    private final BukkitTask tokenUpdateTask;
    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Constants.OKHTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Constants.OKHTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Constants.OKHTTP_READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(Constants.OKHTTP_CALL_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(new HttpRetryInterceptor()) // 添加拦截器，实现请求失败重试
            .build();
    private final String apiRootPath;

    /**
     * 初始化文件存储服务客户端
     *
     * @param config 配置对象
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    public OneDriveClient(Config config) throws IOException {
        super(config);
        // 获得 OneDrive 配置项
        String clientId = (String) config.getConfig("onedrive.client-id");
        String clientScrt = (String) config.getConfig("onedrive.client-secret");
        String refreshToken = (String) config.getConfig("onedrive.refresh-token");
        boolean useAppRoot = (boolean) config.getConfig("onedrive.use-app-folder");
        if (useAppRoot) {
            apiRootPath = OD_API_APP_ROOT_PATH;
        } else {
            apiRootPath = OD_API_ROOT_PATH;
        }
        // 初始化 OneDrive Token 刷新器
        tokenFetcher = new OneDriveTokenFetcher(clientId, clientScrt, refreshToken, config);
        // 初始化，获取 token
        if (!tokenFetcher.fetch()) {
            // 初始化失败，抛出异常
            throw new ClientInitializationException("Failed to initialize OneDriveClient: unable to fetch access token.");
        }
        Plugin plugin = PotatoSack.getPluginInstance();
        if (plugin != null) {
            // 设立异步任务定时更新 token
            tokenUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new OneDriveTokenUpdater(tokenFetcher), 0, 20 * 30);
        } else {
            tokenUpdateTask = null;
        }
        // 输出 Onedrive AppFolder
        // 20240614 这一步还有个作用，就是让 OneDrive 自动创建应用目录
        System.out.println("Onedrive Root Folder URL: " + getRootFolderUrl());
        // 初始化备份文件存储目录
        if (getItem(Constants.APP_DATA_FOLDER) == null) {
            System.out.println("TAKE IT EASY, the 404 problem above is not a big deal. :)");
            System.out.println("404 Detected, creating data folder in OneDrive.");
            // 如果没有建立则建立数据目录
            if (createFolder(Constants.APP_DATA_FOLDER)) {
                System.out.println("Successfully created data folder in OneDrive.");
            } else {
                throw new IOException("Failed to create data folder in OneDrive.");
            }
        }
    }

    /**
     * 获得存储根目录的网页链接
     *
     * @return 根目录网页链接 WebURL，如果没有提供会显示 unknown
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    public String getRootFolderUrl() throws IOException {
        OneDriveItem rootFolder = getItem("");
        if (rootFolder != null) {
            return rootFolder.getWebUrl();
        } else {
            return "unknown";
        }
    }

    @Override
    public void shutdown() {
        if (tokenUpdateTask != null) {
            tokenUpdateTask.cancel();
        }
    }

    /**
     * 发出子目录请求
     *
     * @param url 子目录请求 URL
     * @return 子目录中的所有项目
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     */
    private List<FileItem> requestChildren(String url) throws IOException {
        List<FileItem> resList = new ArrayList<>(); // 子目录中的所有项目
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .build();
        ResponseBody respBody = null;
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                OneDriveChildrenResp oneDriveChildrenResp = gson.fromJson(respBody.string(), OneDriveChildrenResp.class);
                resList.addAll(oneDriveChildrenResp.getValue());
                if (oneDriveChildrenResp.getNextLink() != null) {
                    // 递归分页请求
                    resList.addAll(requestChildren(oneDriveChildrenResp.getNextLink()));
                }
            } else {
                String errMsg = "Children req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return resList;
    }

    @SuppressWarnings("StringEqualsEmptyString")
    @Override
    public List<FileItem> listItems(String path) throws IOException {
        String url;
        if (path.equals("")) // 默认为根目录
            url = MS_GRAPH_ENDPOINT + apiRootPath + "/children";
        else // 指定了子目录
            url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + path + ":/children";
        return requestChildren(url);
    }

    @Override
    public OneDriveItem getItem(String path) throws IOException {
        String url;
        if (path.equals("")) // 默认为根目录
            url = MS_GRAPH_ENDPOINT + apiRootPath;
        else // 指定了子目录
            url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + path;
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .build();
        ResponseBody respBody = null;
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return gson.fromJson(respBody.string(), OneDriveItem.class);
            } else {
                String errMsg = "Item req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return null;
    }

    @Override
    public boolean downloadFile(String remotePath, String localPath) throws IOException {
        File localFile = new File(localPath);
        File parentFile = localFile.getParentFile();
        if (!parentFile.exists())
            if (!parentFile.mkdirs()) // 创建缺失的目录
                return false; // 建立失败
        OneDriveItem oneDriveItem = getItem(remotePath);
        // 若项目不存在或不是文件则无法下载
        if (oneDriveItem == null || oneDriveItem.isFolder())
            return false;
        String url = oneDriveItem.getDownloadUrl();
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .build();
        ResponseBody respBody = null;
        // 发送请求
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (!resp.isSuccessful() || respBody == null) {
                String errMsg = "Download failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            } else {
                try (InputStream inputStream = respBody.byteStream();
                     FileOutputStream fos = new FileOutputStream(localFile)) {
                    // 缓冲
                    byte[] buf = new byte[8192];
                    int bytesRead;
                    // 将数据流写入文件
                    while ((bytesRead = inputStream.read(buf)) != -1) {
                        fos.write(buf, 0, bytesRead);
                    }
                    fos.flush();
                }
                return true;
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    @Override
    public boolean streamCompressAndUpload(ZipFilePath[] zipFilePaths, String remotePath, boolean quiet) throws IOException {
        if (zipFilePaths.length == 0 || remotePath.equals(""))
            return false;
        OneDriveStreamedZipUploader uploader = new OneDriveStreamedZipUploader(this, remotePath);
        return uploader.zipSpecifiedAndUpload(zipFilePaths, quiet);
    }

    @Override
    public boolean uploadLargeFile(String localPath, String remotePath) throws IOException {
        if (!new File(localPath).exists() || remotePath.equals(""))
            return false;
        File localFile = new File(localPath);
        String uploadUrl = createUploadSession(remotePath); // 建立上传会话
        OneDriveFileUploader uploader = new OneDriveFileUploader(localFile, uploadUrl);
        return uploader.upload();
    }

    /**
     * （用于大文件上传）建立文件上传会话
     *
     * @param remotePath 远程目录路径，比如 "Documents/test.txt" 指的就是 "根目录/Documents/test.txt"
     * @return uploadUrl 上传URL
     * @throws IOException 发生网络问题 (比如 timeout) 时会抛出此错误
     * @apiNote 请保证 remotePath 不为空
     */
    public String createUploadSession(String remotePath) throws IOException {
        String remoteName = new File(remotePath).getName(); // 获取在远程目录的文件名
        String url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + remotePath + ":/createUploadSession";
        OneDriveUploadRequest upReq = new OneDriveUploadRequest(remoteName); // 构建请求表单
        String upReqJson = gson.toJson(upReq);
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .post(RequestBody.create(upReqJson, MediaType.parse("application/json")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                OneDriveUpSessionCreateResp crResp = gson.fromJson(respBody.string(), OneDriveUpSessionCreateResp.class);
                return crResp.getUploadUrl();
            } else {
                // 20240611 修改此处的错误信息
                String errMsg = "Upload session creation failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                throw new IOException(errMsg);
            }
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
    }

    @Override
    public boolean uploadFile(String localPath, String remotePath) throws IOException {
        if (!new File(localPath).exists() || remotePath.equals(""))
            return false;
        File localFile = new File(localPath);
        if (localFile.length() > Constants.MAX_SMALL_FILE_SIZE) // 超过了小文件最大允许大小，转为大文件上传
            return uploadLargeFile(localPath, remotePath);
        // 以下为小文件上传
        String url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + remotePath + ":/content?@microsoft.graph.conflictBehavior=replace";
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .put(RequestBody.create(localFile, MediaType.parse("text/plain")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "File upload failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    @Override
    public boolean deleteItem(String path) throws IOException {
        String url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + path;
        // 构造请求
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .delete() // DELETE请求
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "Delete req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (IOException e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }

    @Override
    public boolean createFolder(String path, String name) throws IOException {
        OneDriveFolderRequest folderReq = new OneDriveFolderRequest(name);
        String jsonReqBody = gson.toJson(folderReq);
        String url; // 子目录请求URL
        if (path.equals("")) // 默认为根目录
            url = MS_GRAPH_ENDPOINT + apiRootPath + "/children";
        else // 指定子目录（相对根目录）
            url = MS_GRAPH_ENDPOINT + apiRootPath + ":/" + path + ":/children";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + tokenFetcher.getAccessToken())
                .post(RequestBody.create(jsonReqBody, MediaType.parse("application/json")))
                .build();
        ResponseBody respBody = null;
        try {
            Response resp = client.newCall(req).execute();
            respBody = resp.body();
            if (resp.isSuccessful() && respBody != null) {
                return true;
            } else {
                String errMsg = "Folder req failed, code: " + resp.code() + ", message: " + resp.message();
                if (respBody != null)
                    errMsg += "\n Resp body: " + respBody.string();
                ConsoleSender.logWarn(errMsg);
            }
        } catch (Exception e) {
            ConsoleSender.logError(e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // 关闭responseBody
            if (respBody != null)
                respBody.close();
        }
        return false;
    }
}
